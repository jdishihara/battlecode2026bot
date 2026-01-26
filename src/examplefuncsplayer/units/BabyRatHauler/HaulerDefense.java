package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.DefenseTarget;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.RatState;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatLevel;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * Defense system for hauler rats.
 *
 * SQUEAK CHAIN DEFENSE SYSTEM:
 * - When enemy spotted near king, squeak to alert nearby rats
 * - All rats hearing squeak converge to defend (regardless of distance from king)
 * - Deterministic target priority: closest to king → lowest HP → lowest robot ID
 * - Sticky targeting: stay on target for N rounds to prevent thrashing
 * - Selective relay: extend squeak chain outward without back-propagation
 * - Fresh info only: ignore squeaks older than 2-3 rounds
 */
public class HaulerDefense {

    // ==================== DETERMINISTIC TARGET PRIORITY ====================

    /**
     * Compute deterministic priority score for target selection.
     * ALL rats MUST use this SAME function to pick the SAME target.
     *
     * Priority (lower score = higher priority):
     * 1. Closest to king (most dangerous) - highest weight
     * 2. Lowest HP (quick kill) - medium weight
     * 3. Lowest robot ID (deterministic tiebreaker) - lowest weight
     *
     * @return priority score (lower = attack first)
     */
    public static long computeTargetPriority(int distToKingSq, int hp, int robotId) {
        // distToKingSq: 0 to ~3600 (60² max map size)
        // hp: 0 to 500 (rat king max)
        // robotId: 10000+ (spec says IDs are >= 10000)
        //
        // We want distToKingSq to completely dominate, then HP, then ID.
        // Using: distToKingSq * 10^9 + hp * 10^6 + robotId
        // This ensures no overlap between tiers.
        return (long) distToKingSq * 1_000_000_000L + (long) hp * 1_000_000L + (long) robotId;
    }

    /**
     * Overload for RobotInfo convenience.
     */
    public static long computeTargetPriority(RobotInfo enemy, MapLocation kingLoc) {
        int distToKingSq = enemy.getLocation().distanceSquaredTo(kingLoc);
        return computeTargetPriority(distToKingSq, enemy.getHealth(), enemy.getID());
    }

    /**
     * Check if we should enter defense mode.
     * Returns true if:
     * 1. We're near our king
     * 2. There are enemy rats threatening the king
     */
    public static boolean shouldDefend(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        // Must know where our king is
        if (m.kingLocation == null) return false;

        MapLocation here = rc.getLocation();
        int distToKingSq = here.distanceSquaredTo(m.kingLocation);

        // Only defend if we're close to king
        if (distToKingSq > HAULER_DEFEND_RADIUS) return false;

        // Check if enemies are threatening the king
        return threat.level == ThreatLevel.KING_DANGER || threat.enemiesNearKing > 0;
    }

    /**
     * Enhanced threat computation that tracks enemies near king.
     * Call this instead of the basic computeThreat when defense is important.
     */
    public static ThreatInfo computeDefenseThreat(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();
        int myHP = rc.getHealth();

        // Sense all nearby robots
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(Math.max(KING_THREAT_RADIUS, CAT_FLEE_RADIUS + 9));

        // Track threats
        RobotInfo nearestCat = null;
        int catDistSq = Integer.MAX_VALUE;

        // Track enemies near king for defense
        int enemiesNearKing = 0;
        DefenseTarget bestTarget = null;
        MapLocation sharedTargetLoc = null;

        // Track attackable enemies (for ATTACK state) - enemies we have advantage over
        RobotInfo bestAttackTarget = null;
        int bestAttackScore = Integer.MIN_VALUE;
        boolean bestTargetFacingAway = false;
        int nearbyAllies = 0;

        // Read shared defense target if available
        int sharedTargetX = rc.readSharedArray(DEFENSE_TARGET_X);
        int sharedTargetY = rc.readSharedArray(DEFENSE_TARGET_Y);
        int sharedTargetRound = rc.readSharedArray(DEFENSE_TARGET_ROUND);
        int currentRound = rc.getRoundNum();

        // Check if shared target is still fresh (within 20 rounds)
        if (sharedTargetX != 0 && (currentRound - sharedTargetRound + 1000) % 1000 < HAULER_FOCUS_FIRE_ROUNDS) {
            sharedTargetLoc = new MapLocation(sharedTargetX, sharedTargetY);
        }

        for (RobotInfo r : nearbyRobots) {
            MapLocation loc = r.getLocation();
            int distToHere = here.distanceSquaredTo(loc);

            if (r.getType() == UnitType.CAT) {
                if (distToHere < catDistSq) {
                    catDistSq = distToHere;
                    nearestCat = r;
                }
            } else if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) {
                // Count allies for swarming decisions
                nearbyAllies++;
            } else if (r.getTeam() == enemyTeam && r.getType() == UnitType.BABY_RAT) {
                // Check if this enemy is near our king (for defense)
                if (m.kingLocation != null) {
                    int distToKingSq = loc.distanceSquaredTo(m.kingLocation);
                    if (distToKingSq <= KING_THREAT_RADIUS) {
                        enemiesNearKing++;

                        // Evaluate as potential target
                        DefenseTarget candidate = new DefenseTarget(loc, r.getHealth(), r.getID(), distToKingSq);

                        // If this is the shared target, prioritize it
                        if (sharedTargetLoc != null && loc.equals(sharedTargetLoc)) {
                            bestTarget = candidate;
                        } else if (bestTarget == null) {
                            bestTarget = candidate;
                        } else {
                            // Priority: 1) Low HP, 2) Closer to king
                            boolean isBetter = false;

                            // Strong preference for low HP targets (can kill quickly)
                            if (candidate.hp <= LOW_HP_THRESHOLD && bestTarget.hp > LOW_HP_THRESHOLD) {
                                isBetter = true;
                            } else if (candidate.hp > LOW_HP_THRESHOLD && bestTarget.hp <= LOW_HP_THRESHOLD) {
                                isBetter = false;
                            }
                            // Among same HP category, prefer closer to king
                            else if (candidate.hp < bestTarget.hp) {
                                isBetter = true;
                            } else if (candidate.hp == bestTarget.hp && candidate.distToKingSq < bestTarget.distToKingSq) {
                                isBetter = true;
                            }

                            if (isBetter && sharedTargetLoc == null) {
                                bestTarget = candidate;
                            }
                        }
                    }
                }

                // ===== ATTACKABLE ENEMY CHECK (for ATTACK state) =====
                // Check if this enemy is one we have advantage over
                int enemyHP = r.getHealth();
                boolean facingAway = isEnemyFacingAway(rc, r);

                // Score this target - higher = better to attack
                int score = 0;

                // HP advantage: we have more HP than them
                if (myHP > enemyHP) {
                    score += 100 + (myHP - enemyHP);
                }

                // Facing away: free hits, no retaliation
                if (facingAway) {
                    score += 200;
                }

                // Low HP enemy: quick kill
                if (enemyHP <= LOW_HP_THRESHOLD) {
                    score += 150;
                }

                // Closer enemies are better targets
                score -= distToHere;

                // Only consider if we have SOME advantage (score > 0)
                if (score > bestAttackScore && score > 0) {
                    bestAttackScore = score;
                    bestAttackTarget = r;
                    bestTargetFacingAway = facingAway;
                }
            }
        }

        // Determine threat level
        ThreatLevel level = ThreatLevel.NONE;
        MapLocation source = null;
        boolean isCatThreat = false;  // Track if primary threat is a cat

        // Cat threats take priority for fleeing
        if (nearestCat != null) {
            source = nearestCat.getLocation();
            isCatThreat = true;  // Threat is from cat
            if (catDistSq <= 9) {
                level = ThreatLevel.PANIC;
            } else if (catDistSq <= CAT_FLEE_RADIUS * CAT_FLEE_RADIUS) {
                level = ThreatLevel.NEAR;
            }
        }

        // But if enemies are near king, that's KING_DANGER (triggers defense mode)
        if (enemiesNearKing > 0) {
            // Only override if not in cat panic
            if (level != ThreatLevel.PANIC) {
                level = ThreatLevel.KING_DANGER;
                isCatThreat = false;  // Threat is from enemy rats, NOT cat
                if (bestTarget != null) {
                    source = bestTarget.location;
                }
            }
        }

        ThreatInfo threat = new ThreatInfo(level, source);
        threat.threatIsCat = isCatThreat;
        threat.enemiesNearKing = enemiesNearKing;
        if (bestTarget != null) {
            threat.priorityTarget = bestTarget.location;
            threat.priorityTargetHP = bestTarget.hp;
        }

        // Populate attackable enemy info for ATTACK state decisions
        if (bestAttackTarget != null) {
            threat.attackableEnemy = bestAttackTarget.getLocation();
            threat.attackableEnemyHP = bestAttackTarget.getHealth();
            threat.attackableEnemyId = bestAttackTarget.getID();
            threat.enemyFacingAway = bestTargetFacingAway;
        }
        threat.nearbyAllies = nearbyAllies;

        return threat;
    }

    /**
     * Select the best target to attack.
     * Priority:
     * 1. Shared focus fire target (if still valid)
     * 2. Low HP enemies (can kill quickly)
     * 3. Enemies closest to king
     * 4. Adjacent enemies we can hit right now
     */
    public static MapLocation selectDefenseTarget(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        // If threat already computed a priority target, use it
        if (threat.priorityTarget != null) {
            m.currentDefenseTarget = threat.priorityTarget;
            m.defenseTargetRound = rc.getRoundNum();
            return threat.priorityTarget;
        }

        // Fallback: find any adjacent enemy we can attack
        MapLocation here = rc.getLocation();
        Team enemyTeam = rc.getTeam().opponent();
        RobotInfo[] adjacent = rc.senseNearbyRobots(2, enemyTeam);

        MapLocation bestLoc = null;
        int bestHP = Integer.MAX_VALUE;

        for (RobotInfo enemy : adjacent) {
            if (enemy.getType() == UnitType.BABY_RAT || enemy.getType() == UnitType.RAT_KING) {
                if (enemy.getHealth() < bestHP) {
                    bestHP = enemy.getHealth();
                    bestLoc = enemy.getLocation();
                }
            }
        }

        if (bestLoc != null) {
            m.currentDefenseTarget = bestLoc;
            m.defenseTargetRound = rc.getRoundNum();
        }

        return bestLoc;
    }

    /**
     * Attack the target with appropriate cheese boost.
     * Returns true if attack was successful.
     */
    public static boolean attackTarget(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        // Calculate cheese to spend
        // Use more cheese for low HP targets (finish them off) or when defending king
        int rawCheese = rc.getRawCheese();
        int globalCheese = rc.getGlobalCheese();

        int cheeseToSpend = 0;

        // Check target HP if we can sense it
        if (rc.canSenseLocation(target)) {
            RobotInfo targetRobot = rc.senseRobotAtLocation(target);
            if (targetRobot != null) {
                int targetHP = targetRobot.getHealth();

                // If target is low HP, spend enough to kill
                // Damage = 10 + ceil(sqrt(cheese))
                if (targetHP <= 10) {
                    cheeseToSpend = 0; // Base damage is enough
                } else if (targetHP <= 15) {
                    cheeseToSpend = Math.min(25, rawCheese); // 10 + 5 = 15 damage
                } else if (targetHP <= 20) {
                    cheeseToSpend = Math.min(100, rawCheese + Math.min(globalCheese / 4, 50)); // 10 + 10 = 20 damage
                } else {
                    // Standard defensive bite
                    cheeseToSpend = Math.min(HAULER_DEFENSE_BITE_BOOST, rawCheese);
                }

                // If enemy is very close to king, spend more
                if (m.kingLocation != null && target.distanceSquaredTo(m.kingLocation) <= 9) {
                    cheeseToSpend = Math.min(KING_ATTACK_CHEESE_BOOST, rawCheese + Math.min(globalCheese / 3, 30));
                }
            }
        }

        if (rc.canAttack(target, cheeseToSpend)) {
            rc.attack(target, cheeseToSpend);
            return true;
        }

        return false;
    }

    /**
     * Place defensive traps around the king.
     * Strategic placement:
     * 1. Between enemy and king
     * 2. On likely approach paths
     * 3. Adjacent to king's perimeter
     */
    public static boolean tryPlaceDefensiveTrap(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        // Check cooldowns and limits
        int roundNum = rc.getRoundNum();
        if (roundNum - m.lastDefenseTrapRound < HAULER_TRAP_COOLDOWN) return false;
        if (m.defenseTrapCount >= HAULER_MAX_DEFENSE_TRAPS) return false;
        if (rc.getGlobalCheese() < RAT_TRAP_COST + KING_RESERVE) return false;
        if (rc.getActionCooldownTurns() >= 10) return false;

        MapLocation here = rc.getLocation();
        MapLocation kingLoc = m.kingLocation;
        if (kingLoc == null) return false;

        // Best trap locations:
        // 1. Between threat and king (if threat source known)
        // 2. On the perimeter around king
        // 3. Adjacent to our current position toward king

        MapLocation[] candidates = new MapLocation[8];
        int candidateCount = 0;

        // If we have a threat source, prioritize blocking path to king
        if (threat.source != null) {
            Direction threatToKing = threat.source.directionTo(kingLoc);
            MapLocation blocking = threat.source.add(threatToKing);

            if (here.isAdjacentTo(blocking) && rc.canPlaceRatTrap(blocking)) {
                candidates[candidateCount++] = blocking;
            }

            // Also try one step further
            MapLocation blocking2 = blocking.add(threatToKing);
            if (here.isAdjacentTo(blocking2) && rc.canPlaceRatTrap(blocking2)) {
                candidates[candidateCount++] = blocking2;
            }
        }

        // Add perimeter locations around king (adjacent to king's 3x3)
        for (Direction dir : DIRECTIONS) {
            MapLocation perimeterLoc = kingLoc.add(dir).add(dir);
            if (here.isAdjacentTo(perimeterLoc) && rc.canPlaceRatTrap(perimeterLoc)) {
                candidates[candidateCount++] = perimeterLoc;
                if (candidateCount >= 8) break;
            }
        }

        // Try to place at best candidate
        for (int i = 0; i < candidateCount; i++) {
            if (rc.canPlaceRatTrap(candidates[i])) {
                rc.placeRatTrap(candidates[i]);
                m.lastDefenseTrapRound = roundNum;
                m.defenseTrapCount++;
                return true;
            }
        }

        return false;
    }

    /**
     * Squeak for defensive backup.
     * Alerts nearby haulers and attackers that king needs help.
     */
    public static boolean squeakForDefense(RobotController rc, Mem m) throws GameActionException {
        int roundNum = rc.getRoundNum();

        // Cooldown check
        if (roundNum - m.lastDefenseSqueak < SQUEAK_COOLDOWN) return false;

        // Use the existing squeak encoding for king danger
        int msg = encodeMsg(MSG_KING_DANGER, m.kingLocation.x, m.kingLocation.y);

        // Squeak is always available if action cooldown permits
        if (rc.getActionCooldownTurns() < 10) {
            rc.squeak(msg);
            m.lastDefenseSqueak = roundNum;
            return true;
        }

        return false;
    }

    /**
     * Move toward a defense target while staying near king.
     * Balances: engaging enemy vs protecting king position.
     */
    public static boolean moveTowardTarget(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction toTarget = here.directionTo(target);

        if (toTarget == Direction.CENTER) return false;

        // If moving would take us too far from king, don't move
        if (m.kingLocation != null) {
            MapLocation nextLoc = here.add(toTarget);
            int newDistToKingSq = nextLoc.distanceSquaredTo(m.kingLocation);

            // Don't stray more than 7 tiles from king while defending
            if (newDistToKingSq > 49) {
                // Instead, move toward king
                toTarget = here.directionTo(m.kingLocation);
            }
        }

        // Try to move
        Direction[] tryDirs = {
            toTarget,
            toTarget.rotateLeft(),
            toTarget.rotateRight(),
            toTarget.rotateLeft().rotateLeft(),
            toTarget.rotateRight().rotateRight()
        };

        for (Direction dir : tryDirs) {
            if (dir == Direction.CENTER) continue;

            MapLocation next = here.add(dir);

            // Check passability and blocking robots
            if (rc.canSenseLocation(next)) {
                if (!rc.senseMapInfo(next).isPassable()) continue;
                if (rc.senseRobotAtLocation(next) != null) continue;
            }

            // Turn if needed
            if (rc.getDirection() != dir && rc.canTurn(dir)) {
                rc.turn(dir);
            }

            if (rc.canMoveForward()) {
                rc.moveForward();
                return true;
            }
        }

        return false;
    }

    /**
     * Count allied rats near our position (for gang-up decisions).
     */
    public static int countNearbyAllies(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(radiusSq, rc.getTeam());
        int count = 0;
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.BABY_RAT) {
                count++;
            }
        }
        return count;
    }

    /**
     * Should we gang up on this target?
     * Returns true if we have enough allies nearby.
     */
    public static boolean shouldGangUp(RobotController rc, MapLocation target) throws GameActionException {
        if (target == null) return false;

        // Count allies near the target
        int alliesNearTarget = 0;
        RobotInfo[] nearTarget = rc.senseNearbyRobots(target, COORDINATED_ATTACK_RADIUS, rc.getTeam());

        for (RobotInfo ally : nearTarget) {
            if (ally.getType() == UnitType.BABY_RAT) {
                alliesNearTarget++;
            }
        }

        return alliesNearTarget >= GANG_UP_THRESHOLD;
    }

    // ===== OFFENSIVE TRAP TACTICS =====
    // Traps deal 50 damage + 2 turn stun for only 30 cheese - extremely efficient!
    // Trap + 2 bites = 70 damage = dead rat

    /**
     * Place offensive trap to cut off enemy retreat or flank them.
     * Call during combat when adjacent to enemy.
     *
     * Tactics:
     * - Retreat cut-off: Place behind enemy (their escape route)
     * - Flanking: Place on enemy's sides
     * - Prediction: Place in enemy's likely movement path
     *
     * @return true if trap was placed
     */
    public static boolean tryOffensiveTrap(RobotController rc, Mem m, MapLocation enemyLoc) throws GameActionException {
        if (rc.getActionCooldownTurns() >= 10) return false;

        // Check cooldown
        int roundNum = rc.getRoundNum();
        if (roundNum - m.lastOffensiveTrapRound < OFFENSIVE_TRAP_COOLDOWN) return false;

        // Keep some reserve cheese
        if (rc.getGlobalCheese() < RAT_TRAP_COST + OFFENSIVE_TRAP_CHEESE_THRESHOLD) return false;

        MapLocation here = rc.getLocation();

        // Find where enemy is likely to move (away from us = their retreat path)
        Direction toEnemy = here.directionTo(enemyLoc);
        if (toEnemy == Direction.CENTER) return false;

        // Priority locations for trap:
        // 1. Behind enemy (their retreat path - opposite of us)
        // 2. Sides of enemy (flanking)
        // 3. Diagonal behind enemy
        Direction enemyRetreat = toEnemy; // From our perspective, this is behind the enemy

        Direction[] trapDirs = {
            enemyRetreat,                    // Directly behind enemy (their escape route)
            enemyRetreat.rotateLeft(),       // Flank left
            enemyRetreat.rotateRight(),      // Flank right
            enemyRetreat.rotateLeft().rotateLeft(),   // Wide flank left
            enemyRetreat.rotateRight().rotateRight(), // Wide flank right
        };

        for (Direction dir : trapDirs) {
            MapLocation trapLoc = enemyLoc.add(dir);

            // Must be adjacent to us to place
            if (!here.isAdjacentTo(trapLoc)) continue;

            if (rc.canPlaceRatTrap(trapLoc)) {
                rc.placeRatTrap(trapLoc);
                m.lastOffensiveTrapRound = roundNum;
                m.offensiveTrapCount++;
                return true;
            }
        }

        return false;
    }

    /**
     * Place ambush trap near cheese mine.
     * Enemy haulers will walk into it while collecting.
     * Best used while scouting/exploring enemy territory.
     *
     * @return true if trap was placed
     */
    public static boolean tryCheeseMineTrap(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        if (rc.getActionCooldownTurns() >= 10) return false;

        // Need more cheese reserve for mine traps (speculative investment)
        if (rc.getGlobalCheese() < RAT_TRAP_COST + 100) return false;

        // Check cooldown
        int roundNum = rc.getRoundNum();
        if (roundNum - m.lastOffensiveTrapRound < OFFENSIVE_TRAP_COOLDOWN * 3) return false;

        MapLocation here = rc.getLocation();

        // Look for cheese mines nearby - enemies will come here
        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                MapLocation mineLoc = info.getMapLocation();

                // Only trap mines far from our king (enemy territory)
                if (m.kingLocation != null && mineLoc.distanceSquaredTo(m.kingLocation) < 100) {
                    continue; // Too close to home, skip
                }

                // Place trap adjacent to mine (not on it - cheese spawns on mine)
                for (Direction dir : DIRECTIONS) {
                    MapLocation trapLoc = mineLoc.add(dir);
                    if (!here.isAdjacentTo(trapLoc)) continue;

                    if (rc.canPlaceRatTrap(trapLoc)) {
                        rc.placeRatTrap(trapLoc);
                        m.lastOffensiveTrapRound = roundNum;
                        m.offensiveTrapCount++;
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Place a parting-shot trap when fleeing from combat.
     * The chasing enemy will likely step into it.
     *
     * @return true if trap was placed
     */
    public static boolean tryPartingShotTrap(RobotController rc, Mem m, MapLocation threatSource) throws GameActionException {
        if (rc.getActionCooldownTurns() >= 10) return false;
        if (threatSource == null) return false;

        // Need enough cheese
        if (rc.getGlobalCheese() < RAT_TRAP_COST + 50) return false;

        // Check cooldown - allow more frequent parting shots
        int roundNum = rc.getRoundNum();
        if (roundNum - m.lastOffensiveTrapRound < OFFENSIVE_TRAP_COOLDOWN) return false;

        MapLocation here = rc.getLocation();

        // Place trap between us and the threat (enemy will chase into it)
        Direction towardEnemy = here.directionTo(threatSource);
        if (towardEnemy == Direction.CENTER) return false;

        // Try placing in enemy's path
        Direction[] trapDirs = {
            towardEnemy,                     // Directly toward enemy
            towardEnemy.rotateLeft(),        // Slightly left
            towardEnemy.rotateRight(),       // Slightly right
        };

        for (Direction dir : trapDirs) {
            MapLocation trapLoc = here.add(dir);

            if (rc.canPlaceRatTrap(trapLoc)) {
                rc.placeRatTrap(trapLoc);
                m.lastOffensiveTrapRound = roundNum;
                m.offensiveTrapCount++;
                return true;
            }
        }

        return false;
    }

    // ===== SQUEAK CHAIN DEFENSE - COORDINATION =====

    /**
     * Check for defense squeaks from nearby allies.
     * FRESH INFO ONLY: Ignores squeaks older than SQUEAK_FRESHNESS_LIMIT rounds.
     *
     * Returns the enemy target location if a valid defense squeak was heard.
     * Also sets m.receivedFreshSqueak flag for relay decisions.
     *
     * Note: Squeaks travel sqrt(16) = 4 tiles. Chain relay extends range.
     */
    public static MapLocation checkDefenseSqueaks(RobotController rc, Mem m) throws GameActionException {
        int currentRound = rc.getRoundNum();
        m.receivedFreshSqueak = false;  // Reset flag

        // Read all available squeaks
        Message[] messages = rc.readSqueaks(-1);

        MapLocation bestTarget = null;
        long bestPriority = Long.MAX_VALUE;
        boolean foundFreshSqueak = false;

        for (Message msg : messages) {
            int msgRound = msg.getRound();
            int age = currentRound - msgRound;

            // FRESH INFO ONLY: Skip squeaks older than limit (default 2 rounds)
            if (age > SQUEAK_FRESHNESS_LIMIT) {
                continue;
            }

            int content = msg.getBytes();
            int type = decodeType(content);

            // Respond to enemy target squeaks and king danger squeaks
            if (type == MSG_ENEMY_TARGET || type == MSG_KING_DANGER) {
                int x = decodeX(content);
                int y = decodeY(content);
                MapLocation targetLoc = new MapLocation(x, y);

                foundFreshSqueak = true;

                // Compute priority using deterministic rule
                if (m.kingLocation != null) {
                    int distToKingSq = targetLoc.distanceSquaredTo(m.kingLocation);

                    // If we can see the target location, get actual enemy info for full priority
                    if (rc.canSenseLocation(targetLoc)) {
                        RobotInfo robot = rc.senseRobotAtLocation(targetLoc);
                        if (robot != null && robot.getTeam() != rc.getTeam()) {
                            // Full priority with HP and ID
                            long priority = computeTargetPriority(robot, m.kingLocation);
                            if (priority < bestPriority) {
                                bestPriority = priority;
                                bestTarget = targetLoc;
                            }
                        }
                        // If no enemy at exact location, enemy may have moved - still valid squeak
                        else if (bestTarget == null) {
                            bestTarget = targetLoc;
                            bestPriority = (long) distToKingSq * 1_000_000_000L;
                        }
                    } else {
                        // Can't see target - use location-based priority only
                        long priority = (long) distToKingSq * 1_000_000_000L;
                        if (priority < bestPriority) {
                            bestPriority = priority;
                            bestTarget = targetLoc;
                        }
                    }
                } else if (bestTarget == null) {
                    bestTarget = targetLoc;
                }
            }
        }

        // Track that we received a fresh squeak (for relay decision)
        if (foundFreshSqueak) {
            m.receivedFreshSqueak = true;
            m.lastReceivedDefenseSqueakRound = currentRound;
        }

        // Also check shared array for coordinated target (set by king)
        int sharedX = rc.readSharedArray(DEFENSE_TARGET_X);
        int sharedY = rc.readSharedArray(DEFENSE_TARGET_Y);
        int sharedRound = rc.readSharedArray(DEFENSE_TARGET_ROUND);

        // Shared target valid if set within last 10 rounds (fresher check)
        if (sharedX != 0 && (currentRound - sharedRound + 1000) % 1000 < 10) {
            MapLocation sharedTarget = new MapLocation(sharedX, sharedY);

            if (m.kingLocation != null) {
                int distToKingSq = sharedTarget.distanceSquaredTo(m.kingLocation);

                // Check if shared target has better priority
                if (rc.canSenseLocation(sharedTarget)) {
                    RobotInfo robot = rc.senseRobotAtLocation(sharedTarget);
                    if (robot != null && robot.getTeam() != rc.getTeam()) {
                        long priority = computeTargetPriority(robot, m.kingLocation);
                        if (priority < bestPriority) {
                            bestPriority = priority;
                            bestTarget = sharedTarget;
                        }
                    }
                } else {
                    long priority = (long) distToKingSq * 1_000_000_000L;
                    if (priority < bestPriority) {
                        bestTarget = sharedTarget;
                    }
                }
            } else if (bestTarget == null) {
                bestTarget = sharedTarget;
            }
        }

        return bestTarget;
    }

    /**
     * Squeak enemy location for focus fire coordination.
     * All defenders hearing this should attack the same target.
     */
    public static boolean squeakEnemyTarget(RobotController rc, Mem m, MapLocation enemyLoc) throws GameActionException {
        if (enemyLoc == null) return false;
        if (rc.getActionCooldownTurns() >= 10) return false;

        int roundNum = rc.getRoundNum();

        // Shorter cooldown for defense squeaks - coordination is critical
        if (roundNum - m.lastDefenseSqueak < DEFENSE_SQUEAK_COOLDOWN) return false;

        int msg = encodeMsg(MSG_ENEMY_TARGET, enemyLoc.x, enemyLoc.y);
        rc.squeak(msg);
        m.lastDefenseSqueak = roundNum;
        return true;
    }

    /**
     * Select THE target all defenders should attack (deterministic focus fire).
     *
     * STICKY TARGETING: Once committed to a target, stay on it for N rounds unless:
     * - Target dies (not visible anymore)
     * - Target moves out of range (can't sense)
     * - A MUCH closer-to-king threat appears (override condition)
     *
     * DETERMINISTIC PRIORITY (all rats compute same result):
     * 1. Closest to king (most dangerous)
     * 2. Lowest HP (quick kill)
     * 3. Lowest robot ID (tiebreaker)
     */
    public static MapLocation selectFocusFireTarget(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return null;

        MapLocation here = rc.getLocation();
        int currentRound = rc.getRoundNum();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        // Sense all nearby enemies once (bytecode optimization)
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);

        // === STICKY TARGETING: Check if we should stay on current target ===
        if (m.stickyTargetId != -1 && m.stickyTargetRoundsRemaining > 0) {
            m.stickyTargetRoundsRemaining--;

            // Try to find the sticky target among visible enemies
            RobotInfo stickyTarget = null;
            for (RobotInfo enemy : nearbyEnemies) {
                if (enemy.getID() == m.stickyTargetId) {
                    stickyTarget = enemy;
                    break;
                }
            }

            if (stickyTarget != null) {
                // Target still visible - update last known location
                MapLocation stickyLoc = stickyTarget.getLocation();
                m.stickyTargetLastKnownLoc = stickyLoc;
                int stickyDistToKingSq = stickyLoc.distanceSquaredTo(m.kingLocation);

                // Check for CLOSER-TO-KING OVERRIDE: only if new threat is MUCH closer
                boolean overrideNeeded = false;
                RobotInfo overrideTarget = null;

                for (RobotInfo enemy : nearbyEnemies) {
                    if (enemy.getID() == m.stickyTargetId) continue;
                    if (enemy.getType() != UnitType.BABY_RAT && enemy.getType() != UnitType.RAT_KING) continue;

                    int enemyDistToKingSq = enemy.getLocation().distanceSquaredTo(m.kingLocation);

                    // Override if: new threat is within 3 tiles of king AND much closer than sticky
                    if (enemyDistToKingSq <= CLOSER_THREAT_OVERRIDE_DIST_SQ &&
                        enemyDistToKingSq < stickyDistToKingSq / 2) {
                        if (overrideTarget == null ||
                            computeTargetPriority(enemy, m.kingLocation) <
                            computeTargetPriority(overrideTarget, m.kingLocation)) {
                            overrideTarget = enemy;
                            overrideNeeded = true;
                        }
                    }
                }

                if (!overrideNeeded) {
                    // Stay on sticky target
                    m.currentDefenseTarget = stickyLoc;
                    return stickyLoc;
                }

                // Override: switch to the closer threat
                if (overrideTarget != null) {
                    MapLocation newLoc = overrideTarget.getLocation();
                    m.stickyTargetId = overrideTarget.getID();
                    m.stickyTargetRoundsRemaining = STICKY_TARGET_ROUNDS;
                    m.stickyTargetLastKnownLoc = newLoc;
                    m.currentDefenseTarget = newLoc;
                    m.defenseTargetRound = currentRound;
                    return newLoc;
                }
            } else {
                // Target not visible - check if we should chase last known location
                if (m.stickyTargetLastKnownLoc != null) {
                    int distToLast = here.distanceSquaredTo(m.stickyTargetLastKnownLoc);
                    // If last known loc is close, keep moving there
                    if (distToLast <= 25) {  // Within 5 tiles
                        // Check if there's a visible enemy at last known area
                        for (RobotInfo enemy : nearbyEnemies) {
                            if (enemy.getType() != UnitType.BABY_RAT && enemy.getType() != UnitType.RAT_KING) continue;
                            int distToLastKnown = enemy.getLocation().distanceSquaredTo(m.stickyTargetLastKnownLoc);
                            if (distToLastKnown <= 8) {  // Enemy within 3 tiles of last known
                                // This might be our target who moved - re-acquire
                                MapLocation newLoc = enemy.getLocation();
                                m.stickyTargetId = enemy.getID();
                                m.stickyTargetLastKnownLoc = newLoc;
                                m.currentDefenseTarget = newLoc;
                                return newLoc;
                            }
                        }
                        // No enemy near last known, but keep moving there briefly
                        if (m.stickyTargetRoundsRemaining > STICKY_TARGET_ROUNDS / 2) {
                            return m.stickyTargetLastKnownLoc;
                        }
                    }
                }

                // Target truly lost - clear sticky state
                clearStickyTarget(m);
            }
        }

        // === FIND NEW TARGET using deterministic priority ===
        RobotInfo bestTarget = null;
        long bestPriority = Long.MAX_VALUE;

        for (RobotInfo enemy : nearbyEnemies) {
            // Only target baby rats and rat kings
            if (enemy.getType() != UnitType.BABY_RAT && enemy.getType() != UnitType.RAT_KING) {
                continue;
            }

            int distToKingSq = enemy.getLocation().distanceSquaredTo(m.kingLocation);

            // Only consider enemies within threat radius of king
            if (distToKingSq > KING_THREAT_RADIUS) continue;

            // Compute deterministic priority
            long priority = computeTargetPriority(enemy, m.kingLocation);

            if (priority < bestPriority) {
                bestPriority = priority;
                bestTarget = enemy;
            }
        }

        if (bestTarget != null) {
            MapLocation targetLoc = bestTarget.getLocation();

            // Set sticky targeting
            m.stickyTargetId = bestTarget.getID();
            m.stickyTargetRoundsRemaining = STICKY_TARGET_ROUNDS;
            m.stickyTargetLastKnownLoc = targetLoc;
            m.currentDefenseTarget = targetLoc;
            m.defenseTargetRound = currentRound;

            return targetLoc;
        }

        return null;
    }

    /**
     * Clear sticky target state when target is lost or killed.
     */
    public static void clearStickyTarget(Mem m) {
        m.stickyTargetId = -1;
        m.stickyTargetRoundsRemaining = 0;
        m.stickyTargetLastKnownLoc = null;
    }

    /**
     * Check if we should respond to a defense alert.
     * ALL nearby rats should converge to defend, regardless of distance from king.
     * Only requirement: threat must be near king.
     */
    public static boolean shouldRespondToDefense(RobotController rc, Mem m, MapLocation threatLoc) throws GameActionException {
        if (m.kingLocation == null) return false;
        if (threatLoc == null) return false;

        // Check if threat is actually near our king
        int threatDistToKing = threatLoc.distanceSquaredTo(m.kingLocation);
        if (threatDistToKing > KING_THREAT_RADIUS) return false;

        // ALL rats can respond - no distance check
        return true;
    }

    /**
     * Decide whether to relay a defense squeak to extend the chain.
     * SELECTIVE RELAY: Only relay if:
     * 1. Haven't squeaked recently (cooldown)
     * 2. Received a fresh squeak this turn
     * 3. Target is still valid (enemy exists near king)
     */
    public static boolean shouldRelaySqueak(RobotController rc, Mem m, MapLocation targetLoc) throws GameActionException {
        if (targetLoc == null) return false;
        if (m.kingLocation == null) return false;

        int currentRound = rc.getRoundNum();

        // 1. Cooldown check - don't spam squeaks
        if (currentRound - m.lastDefenseSqueak < RELAY_SQUEAK_COOLDOWN) return false;

        // 2. Only relay if we received a fresh squeak (extending the chain)
        if (!m.receivedFreshSqueak) return false;

        // 3. Validate target: check if enemy is still near king
        int targetDistToKing = targetLoc.distanceSquaredTo(m.kingLocation);
        if (targetDistToKing > KING_THREAT_RADIUS) return false;

        // 4. If we can see the target location, verify enemy is still there
        if (rc.canSenseLocation(targetLoc)) {
            RobotInfo robot = rc.senseRobotAtLocation(targetLoc);
            if (robot == null || robot.getTeam() == rc.getTeam()) {
                // No enemy at target - don't relay stale info
                return false;
            }
        }

        return true;
    }

    /**
     * Relay a defense squeak if conditions are met.
     * This extends the squeak chain to reach more allies.
     */
    public static boolean tryRelaySqueak(RobotController rc, Mem m, MapLocation targetLoc) throws GameActionException {
        if (!shouldRelaySqueak(rc, m, targetLoc)) return false;
        if (rc.getActionCooldownTurns() >= 10) return false;

        int msg = encodeMsg(MSG_ENEMY_TARGET, targetLoc.x, targetLoc.y);
        rc.squeak(msg);
        m.lastDefenseSqueak = rc.getRoundNum();
        m.receivedFreshSqueak = false;  // Clear flag after relaying
        return true;
    }

    // ==================== RATNAPPING DEFENSE SYSTEM ====================
    // Ratnapping completely disables an enemy for up to 10 turns!
    // Per spec, can ratnap if: enemy facing away OR enemy has less HP OR same team

    /**
     * Check if an enemy rat is facing away from us (can't sense us).
     * Baby rats have a 90° vision cone facing forward.
     * Returns true if we are OUTSIDE their vision cone.
     */
    public static boolean isEnemyFacingAway(RobotController rc, RobotInfo enemy) {
        if (enemy == null) return false;

        MapLocation ourLoc = rc.getLocation();
        MapLocation enemyLoc = enemy.getLocation();
        Direction enemyFacing = enemy.getDirection();

        // Baby rats have 90° cone = facing direction ± 45°
        // So the cone includes: facing, rotateLeft (45°), rotateRight (45°)
        Direction toUs = enemyLoc.directionTo(ourLoc);

        // If toUs matches enemyFacing or its immediate rotations, we're in their cone
        if (toUs == enemyFacing) return false;
        if (toUs == enemyFacing.rotateLeft()) return false;
        if (toUs == enemyFacing.rotateRight()) return false;

        // We're outside their vision cone - they can't see us!
        return true;
    }

    /**
     * Check if we can ratnap an enemy at the given location.
     * Ratnap conditions (from spec):
     * 1. Enemy is facing away (can't sense us) - WE CAN GRAB THEM
     * 2. Enemy has less health than us - WE CAN GRAB THEM
     *
     * @return true if conditions are met for ratnapping
     */
    public static boolean canRatnapEnemy(RobotController rc, Mem m, MapLocation enemyLoc) throws GameActionException {
        if (enemyLoc == null) return false;

        // Must be adjacent
        MapLocation here = rc.getLocation();
        if (!here.isAdjacentTo(enemyLoc)) return false;

        // Must be able to sense the location
        if (!rc.canSenseLocation(enemyLoc)) return false;

        // Get the enemy robot info
        RobotInfo enemy = rc.senseRobotAtLocation(enemyLoc);
        if (enemy == null) return false;

        // Must be an enemy baby rat (can't ratnap kings or cats)
        if (enemy.getTeam() == rc.getTeam()) return false;
        if (enemy.getType() != UnitType.BABY_RAT) return false;

        // Check ratnap conditions
        boolean enemyFacingAway = isEnemyFacingAway(rc, enemy);
        boolean weHaveMoreHP = rc.getHealth() > enemy.getHealth();

        return enemyFacingAway || weHaveMoreHP;
    }

    /**
     * Attempt to ratnap an enemy for defensive purposes.
     * This completely disables them for up to 10 turns!
     *
     * @return true if we successfully ratnapped the enemy
     */
    public static boolean tryDefensiveRatnap(RobotController rc, Mem m, MapLocation enemyLoc) throws GameActionException {
        if (enemyLoc == null) return false;

        int currentRound = rc.getRoundNum();

        // Cooldown to avoid spamming failed attempts
        if (currentRound - m.lastRatnapAttemptRound < 2) return false;
        m.lastRatnapAttemptRound = currentRound;

        // Check if we're already carrying someone
        if (rc.getCarrying() != null) return false;

        // Check if conditions allow ratnapping
        if (!canRatnapEnemy(rc, m, enemyLoc)) return false;

        // Try to ratnap!
        if (rc.canCarryRat(enemyLoc)) {
            rc.carryRat(enemyLoc);

            // Update our state
            m.isCarryingEnemy = true;
            m.carryStartRound = currentRound;
            m.ratnappedFrom = enemyLoc;

            // Try to get the enemy's ID for tracking
            RobotInfo enemy = rc.senseRobotAtLocation(enemyLoc);
            if (enemy != null) {
                m.ratnappedEnemyId = enemy.getID();
            }

            return true;
        }

        return false;
    }

    /**
     * Handle what to do while carrying an enemy.
     * Priority:
     * 1. Throw them away from king (if we have space)
     * 2. Carry them away from king
     * 3. Just hold them (they can't act anyway)
     *
     * Note: Auto-drops after 10 turns per spec.
     *
     * @return true if we took an action
     */
    public static boolean handleCarryingEnemy(RobotController rc, Mem m) throws GameActionException {
        if (rc.getCarrying() == null) {
            // We're not carrying anymore (auto-dropped or thrown)
            clearRatnapState(m);
            return false;
        }

        int currentRound = rc.getRoundNum();
        int turnsCarrying = currentRound - m.carryStartRound;

        // Update tracking
        m.isCarryingEnemy = true;

        // Strategy: Try to throw away from king, or carry away
        MapLocation here = rc.getLocation();

        // Option 1: Throw them away from king
        if (m.kingLocation != null) {
            Direction awayFromKing = m.kingLocation.directionTo(here);
            if (awayFromKing == Direction.CENTER) {
                awayFromKing = DIRECTIONS[m.rng.nextInt(8)];
            }

            // Turn to face away from king before throwing
            if (rc.getDirection() != awayFromKing) {
                if (rc.canTurn(awayFromKing)) {
                    rc.turn(awayFromKing);
                    return true; // Turned, will throw next turn
                }
            }

            // Try to throw
            if (rc.canThrowRat()) {
                rc.throwRat();
                clearRatnapState(m);
                return true;
            }
        }

        // Option 2: Can't throw - try to move away from king while carrying
        if (m.kingLocation != null) {
            Direction awayFromKing = m.kingLocation.directionTo(here);
            if (awayFromKing != Direction.CENTER) {
                if (tryMoveCarryingEnemy(rc, m, awayFromKing)) {
                    return true;
                }
            }
        }

        // Option 3: About to auto-drop (turn 8-9), try to position well
        if (turnsCarrying >= 8) {
            // Position so drop happens away from king
            if (m.kingLocation != null) {
                Direction toKing = here.directionTo(m.kingLocation);
                Direction awayFromKing = toKing.opposite();

                // Turn to face away from king so auto-drop puts enemy away
                if (rc.getDirection() != awayFromKing && rc.canTurn(awayFromKing)) {
                    rc.turn(awayFromKing);
                    return true;
                }
            }
        }

        // Option 4: Just hold them - enemy can't act, we're winning
        // Move randomly to avoid being predictable
        Direction randomDir = DIRECTIONS[m.rng.nextInt(8)];
        tryMoveCarryingEnemy(rc, m, randomDir);

        return true;
    }

    /**
     * Try to move while carrying an enemy.
     */
    private static boolean tryMoveCarryingEnemy(RobotController rc, Mem m, Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation target = rc.getLocation().add(dir);

        // Check if passable
        if (rc.canSenseLocation(target)) {
            if (!rc.senseMapInfo(target).isPassable()) return false;
            if (rc.senseRobotAtLocation(target) != null) return false;
        }

        // Turn if needed
        if (rc.getDirection() != dir && rc.canTurn(dir)) {
            rc.turn(dir);
        }

        // Move
        if (rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }

        return false;
    }

    /**
     * Clear ratnap state after dropping/throwing.
     */
    public static void clearRatnapState(Mem m) {
        m.isCarryingEnemy = false;
        m.carryStartRound = -1;
        m.ratnappedEnemyId = -1;
        m.ratnappedFrom = null;
    }

    /**
     * Check if we should try to ratnap during defense.
     * Ratnapping is HIGH PRIORITY because it completely disables the enemy.
     *
     * Best targets for ratnapping:
     * 1. Enemies facing away from us (guaranteed success if adjacent)
     * 2. Low-HP enemies (we can grab if our HP > theirs)
     *
     * @return the best enemy location to ratnap, or null if none
     */
    public static MapLocation findRatnapTarget(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();
        Team enemyTeam = rc.getTeam().opponent();

        // Get adjacent enemies
        RobotInfo[] adjacentEnemies = rc.senseNearbyRobots(2, enemyTeam);

        MapLocation bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo enemy : adjacentEnemies) {
            if (enemy.getType() != UnitType.BABY_RAT) continue;

            MapLocation enemyLoc = enemy.getLocation();
            if (!here.isAdjacentTo(enemyLoc)) continue;

            // Check if we can ratnap this enemy
            boolean facingAway = isEnemyFacingAway(rc, enemy);
            boolean weHaveMoreHP = rc.getHealth() > enemy.getHealth();

            if (!facingAway && !weHaveMoreHP) continue; // Can't ratnap

            // Score this target
            int score = 0;

            // Prefer enemies facing away (guaranteed grab)
            if (facingAway) score += 100;

            // Prefer enemies close to our king (more dangerous)
            if (m.kingLocation != null) {
                int distToKing = enemyLoc.distanceSquaredTo(m.kingLocation);
                score += (100 - distToKing); // Closer to king = higher score
            }

            // Prefer low HP enemies (easier to grab, also close to death)
            score += (100 - enemy.getHealth());

            if (score > bestScore) {
                bestScore = score;
                bestTarget = enemyLoc;
            }
        }

        return bestTarget;
    }
}
