package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.DefenseTarget;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatLevel;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * Defense system for hauler rats.
 *
 * Key features:
 * - Prioritizes low HP enemies (kill them first for quick eliminations)
 * - Focus fire coordination via shared array
 * - Strategic trap placement around king
 * - Squeaks for backup when defending
 */
public class HaulerDefense {

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

        // Sense all nearby robots
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(Math.max(KING_THREAT_RADIUS, CAT_FLEE_RADIUS + 9));

        // Track threats
        RobotInfo nearestCat = null;
        int catDistSq = Integer.MAX_VALUE;

        // Track enemies near king for defense
        int enemiesNearKing = 0;
        DefenseTarget bestTarget = null;
        MapLocation sharedTargetLoc = null;

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
            } else if (r.getTeam() == enemyTeam && r.getType() == UnitType.BABY_RAT) {
                // Check if this enemy is near our king
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
            }
        }

        // Determine threat level
        ThreatLevel level = ThreatLevel.NONE;
        MapLocation source = null;

        // Cat threats take priority for fleeing
        if (nearestCat != null) {
            source = nearestCat.getLocation();
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
                if (bestTarget != null) {
                    source = bestTarget.location;
                }
            }
        }

        ThreatInfo threat = new ThreatInfo(level, source);
        threat.enemiesNearKing = enemiesNearKing;
        if (bestTarget != null) {
            threat.priorityTarget = bestTarget.location;
            threat.priorityTargetHP = bestTarget.hp;
        }

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

    // ===== REACTIVE SWARM DEFENSE - SQUEAK COORDINATION =====

    /**
     * Check for defense squeaks from nearby allies.
     * Returns the enemy target location if a defense squeak was heard.
     *
     * Note: Squeaks are only received within sqrt(16) = 4 tiles of sender.
     * For broader coordination, we also check the shared array.
     */
    public static MapLocation checkDefenseSqueaks(RobotController rc, Mem m) throws GameActionException {
        int currentRound = rc.getRoundNum();

        // Read all available squeaks (-1 means read all)
        Message[] messages = rc.readSqueaks(-1);

        MapLocation bestTarget = null;
        int bestPriority = Integer.MAX_VALUE;  // Lower = higher priority

        for (Message msg : messages) {
            // Skip old messages
            int msgRound = msg.getRound();
            if (currentRound - msgRound > SQUEAK_LISTEN_ROUNDS) {
                continue;
            }

            int content = msg.getBytes();
            int type = decodeType(content);

            // Respond to enemy target squeaks and king danger squeaks
            if (type == MSG_ENEMY_TARGET || type == MSG_KING_DANGER) {
                int x = decodeX(content);
                int y = decodeY(content);
                MapLocation targetLoc = new MapLocation(x, y);

                // Prioritize by distance to our king (closer = more urgent)
                if (m.kingLocation != null) {
                    int distToKing = targetLoc.distanceSquaredTo(m.kingLocation);
                    if (distToKing < bestPriority) {
                        bestPriority = distToKing;
                        bestTarget = targetLoc;
                    }
                } else {
                    bestTarget = targetLoc;
                }
            }
        }

        // Also check shared array for coordinated target (set by king or other defenders)
        int sharedX = rc.readSharedArray(DEFENSE_TARGET_X);
        int sharedY = rc.readSharedArray(DEFENSE_TARGET_Y);
        int sharedRound = rc.readSharedArray(DEFENSE_TARGET_ROUND);

        // Shared target valid if set within last 15 rounds
        if (sharedX != 0 && (currentRound - sharedRound + 1000) % 1000 < 15) {
            MapLocation sharedTarget = new MapLocation(sharedX, sharedY);

            // Prefer shared target if it's closer to king than squeak target
            if (m.kingLocation != null) {
                int distToKing = sharedTarget.distanceSquaredTo(m.kingLocation);
                if (distToKing < bestPriority) {
                    bestTarget = sharedTarget;
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
     * Select THE target all defenders should attack (focus fire).
     *
     * Priority order:
     * 1. Shared array target (if still valid and visible)
     * 2. Lowest HP enemy near king (quick kills)
     * 3. Enemy closest to king (most dangerous)
     *
     * Also broadcasts target via squeak for coordination.
     */
    public static MapLocation selectFocusFireTarget(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return null;

        MapLocation here = rc.getLocation();
        int currentRound = rc.getRoundNum();
        Team enemyTeam = rc.getTeam().opponent();

        // 1. Check if shared target is still valid
        int sharedX = rc.readSharedArray(DEFENSE_TARGET_X);
        int sharedY = rc.readSharedArray(DEFENSE_TARGET_Y);
        int sharedRound = rc.readSharedArray(DEFENSE_TARGET_ROUND);

        if (sharedX != 0 && (currentRound - sharedRound + 1000) % 1000 < 10) {
            MapLocation sharedTarget = new MapLocation(sharedX, sharedY);

            // Verify target still exists at that location
            if (rc.canSenseLocation(sharedTarget)) {
                RobotInfo robot = rc.senseRobotAtLocation(sharedTarget);
                if (robot != null && robot.getTeam() == enemyTeam) {
                    // Shared target still valid - use it
                    return sharedTarget;
                }
            }
        }

        // 2. Find best target among visible enemies near king
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, enemyTeam);

        RobotInfo bestTarget = null;
        int lowestHP = Integer.MAX_VALUE;
        int closestToKing = Integer.MAX_VALUE;

        for (RobotInfo enemy : nearbyEnemies) {
            // Only target baby rats and rat kings
            if (enemy.getType() != UnitType.BABY_RAT && enemy.getType() != UnitType.RAT_KING) {
                continue;
            }

            MapLocation enemyLoc = enemy.getLocation();
            int distToKing = enemyLoc.distanceSquaredTo(m.kingLocation);

            // Only consider enemies within threat radius of king (~7 tiles = 49 dist²)
            if (distToKing > KING_THREAT_RADIUS) continue;

            int hp = enemy.getHealth();

            // Priority:
            // 1) Enemies adjacent to king (dist² <= 18 covers king's 3x3 + 1 tile)
            // 2) Lowest HP (quick kills reduce enemy DPS)
            // 3) Closest to king (most threatening)

            boolean isBetter = false;

            if (bestTarget == null) {
                isBetter = true;
            } else {
                int bestDistToKing = bestTarget.getLocation().distanceSquaredTo(m.kingLocation);
                int bestHP = bestTarget.getHealth();

                // Critical zone: adjacent to king (highest priority)
                boolean enemyInCritical = distToKing <= 18;
                boolean bestInCritical = bestDistToKing <= 18;

                if (enemyInCritical && !bestInCritical) {
                    isBetter = true;  // Prioritize critical zone
                } else if (enemyInCritical == bestInCritical) {
                    // Same zone - prefer lower HP
                    if (hp < bestHP) {
                        isBetter = true;
                    } else if (hp == bestHP && distToKing < bestDistToKing) {
                        isBetter = true;  // Same HP, prefer closer to king
                    }
                }
            }

            if (isBetter) {
                bestTarget = enemy;
                lowestHP = hp;
                closestToKing = distToKing;
            }
        }

        if (bestTarget != null) {
            MapLocation targetLoc = bestTarget.getLocation();

            // Squeak this target for other defenders to focus fire
            squeakEnemyTarget(rc, m, targetLoc);

            // Update our tracked target
            m.currentDefenseTarget = targetLoc;
            m.defenseTargetRound = currentRound;

            return targetLoc;
        }

        return null;
    }

    /**
     * Check if we should respond to a defense alert.
     * Returns true if:
     * 1. We're within response range of our king
     * 2. The threat is near our king
     */
    public static boolean shouldRespondToDefense(RobotController rc, Mem m, MapLocation threatLoc) throws GameActionException {
        if (m.kingLocation == null) return false;
        if (threatLoc == null) return false;

        MapLocation here = rc.getLocation();

        // Check if we're within response range of king (~7 tiles)
        int distToKing = here.distanceSquaredTo(m.kingLocation);
        if (distToKing > HAULER_DEFEND_RADIUS_SQ) return false;

        // Check if threat is actually near our king
        int threatDistToKing = threatLoc.distanceSquaredTo(m.kingLocation);
        if (threatDistToKing > KING_THREAT_RADIUS) return false;

        return true;
    }
}
