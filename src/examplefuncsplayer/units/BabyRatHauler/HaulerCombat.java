package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.RatState;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerState.*;

/**
 * Combat system for hauler rats.
 *
 * Philosophy: Never deal damage you can't convert into a kill.
 * - Focus fire to guarantee kills
 * - Trap placement to cut off retreat
 * - Surround formation to prevent escape
 * - Alpha strike coordination via squeaks
 */
public class HaulerCombat {

    // ==================== COMBAT CONSTANTS ====================

    // Damage calculation: 10 base + ceil(sqrt(cheese))
    private static final int BASE_BITE_DAMAGE = 10;
    private static final int BABY_RAT_HP = 100;
    private static final int TRAP_DAMAGE = 50;

    // Engagement thresholds
    private static final int MIN_ALLIES_TO_COMMIT = 2; // Need 2+ allies to fight
    private static final int OVERWHELMING_ADVANTAGE = 3; // 3:1 ratio = always fight
    private static final int KILL_THRESHOLD_HP = 30; // Focus on finishing low HP

    // Squeak types for combat coordination
    private static final int SQUEAK_ALPHA_STRIKE = 5;

    // ==================== SQUEAK COMMUNICATION ====================

    /**
     * Squeak to coordinate an alpha strike on a target.
     * Only call when we have enough allies to secure the kill.
     */
    public static boolean squeakAlphaStrike(RobotController rc, Mem m, MapLocation target,
                                            int roundNum) throws GameActionException {
        if (roundNum - m.lastHelpSqueakRound < 3) return false; // Brief cooldown

        int encoded = (SQUEAK_ALPHA_STRIKE << 12) | (target.x << 6) | target.y;
        rc.squeak(encoded);
        m.lastHelpSqueakRound = roundNum;
        return true;
    }

    /**
     * Squeak for help when outnumbered or low HP.
     * (4-param overload for backward compatibility)
     */
    public static boolean maybeSqueakHelp(RobotController rc, Mem m, int roundNum,
                                          int nearestCatDistSq) throws GameActionException {
        // Default to assuming we're outnumbered
        return maybeSqueakHelp(rc, m, roundNum, nearestCatDistSq, 1, 0);
    }

    /**
     * Squeak for help when outnumbered or low HP.
     */
    public static boolean maybeSqueakHelp(RobotController rc, Mem m, int roundNum,
                                          int nearestCatDistSq, int enemyCount, int allyCount)
            throws GameActionException {
        if (roundNum - m.lastHelpSqueakRound < HELP_SQUEAK_COOLDOWN) return false;
        if (nearestCatDistSq <= CAT_HEARING_DIST_SQ) return false;

        // Squeak if outnumbered or low HP
        boolean outnumbered = enemyCount > allyCount;
        boolean lowHP = rc.getHealth() < 50;
        boolean nearKing = m.kingLocation != null &&
                          rc.getLocation().distanceSquaredTo(m.kingLocation) <= 36;

        if (outnumbered || lowHP || nearKing) {
            MapLocation here = rc.getLocation();
            int encodedMsg = encodeMsg(MSG_HELP, here.x, here.y);
            rc.squeak(encodedMsg);
            m.lastHelpSqueakRound = roundNum;
            return true;
        }
        return false;
    }

    /**
     * Squeak about a cat spotted at a safe distance.
     * Alerts nearby allies without attracting cat attention.
     */
    public static boolean maybeSqueakCatSpotted(RobotController rc, Mem m, int roundNum,
                                                 int catDistSq, MapLocation catLoc)
            throws GameActionException {
        // Only squeak if cat is at safe distance
        if (catDistSq <= CAT_HEARING_DIST_SQ) return false;

        // Cooldown check
        if (roundNum - m.lastCatSqueakRound < CAT_SPOT_SQUEAK_COOLDOWN) return false;

        // Squeak cat location
        int encodedMsg = encodeMsg(MSG_CAT_SPOTTED, catLoc.x, catLoc.y);
        rc.squeak(encodedMsg);
        m.lastCatSqueakRound = roundNum;
        return true;
    }

    /**
     * Process incoming squeaks - respond to alpha strike calls.
     */
    public static void processIncomingSqueaks(RobotController rc, Mem m, int roundNum)
            throws GameActionException {
        // Read messages from recent rounds
        Message[] messages = rc.readSqueaks(-1);
        MapLocation here = rc.getLocation();

        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();

            if (senderLoc == null || here.distanceSquaredTo(senderLoc) > SWARM_RESPONSE_DISTANCE_SQ) continue;

            int msgType = content >> 12;

            if (msgType == SQUEAK_ALPHA_STRIKE) {
                // Decode target location
                int targetX = (content >> 6) & 0x3F;
                int targetY = content & 0x3F;
                MapLocation targetLoc = new MapLocation(targetX, targetY);

                // Set as priority combat target
                m.alphaStrikeTarget = targetLoc;
                m.alphaStrikeRound = roundNum;

                // Enter combat if close enough
                if (here.distanceSquaredTo(targetLoc) <= COMBAT_ENGAGE_RADIUS_SQ) {
                    m.previousState = m.currentState;
                    m.currentState = RatState.COMBAT;
                }
                continue;
            }

            // Handle other message types...
            int legacyType = decodeType(content);
            if (legacyType == MSG_HELP) {
                int helpX = decodeX(content);
                int helpY = decodeY(content);
                MapLocation helpLoc = new MapLocation(helpX, helpY);

                if (m.currentState != RatState.RETURN && m.currentState != RatState.DELIVER) {
                    int distToHelp = here.distanceSquaredTo(helpLoc);
                    if (distToHelp <= COMBAT_DISENGAGE_RADIUS_SQ * 2) {
                        m.localSwarmTarget = helpLoc;
                        m.localSwarmUntilRound = roundNum + LOCAL_SWARM_TTL;
                    }
                }
            }
        }
    }

    // ==================== COMBAT ENGAGEMENT DECISION ====================

    /**
     * Analyze the battlefield and decide if we should fight.
     * Returns a CombatAnalysis with all relevant info.
     */
    public static class CombatAnalysis {
        public RobotInfo[] enemies;
        public int enemyCount;
        public int allyCount;
        public int alliesAdjacentToEnemy; // Can attack this turn
        public int totalAllyDamage; // Potential damage this turn
        public RobotInfo lowestHPEnemy;
        public RobotInfo closestEnemy;
        public RobotInfo enemyNearKing;
        public boolean canSecureKill; // Can we kill someone this turn?
        public boolean shouldEngage;
        public boolean shouldRetreat;
    }

    public static CombatAnalysis analyzeBattlefield(RobotController rc, Mem m,
                                                     RobotInfo[] nearbyRobots)
            throws GameActionException {
        CombatAnalysis analysis = new CombatAnalysis();
        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        // Separate and analyze robots
        analysis.enemies = new RobotInfo[nearbyRobots.length];
        analysis.enemyCount = 0;
        analysis.allyCount = 0;
        analysis.alliesAdjacentToEnemy = 0;

        int lowestHP = Integer.MAX_VALUE;
        int closestDistSq = Integer.MAX_VALUE;
        int closestToKingDistSq = Integer.MAX_VALUE;

        // First pass: identify enemies
        for (RobotInfo r : nearbyRobots) {
            if (r.getType() != UnitType.BABY_RAT) continue;

            if (r.getTeam() == enemyTeam) {
                analysis.enemies[analysis.enemyCount++] = r;

                int distSq = here.distanceSquaredTo(r.getLocation());

                if (r.getHealth() < lowestHP) {
                    lowestHP = r.getHealth();
                    analysis.lowestHPEnemy = r;
                }
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    analysis.closestEnemy = r;
                }
                if (m.kingLocation != null) {
                    int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                    if (distToKing < closestToKingDistSq) {
                        closestToKingDistSq = distToKing;
                        analysis.enemyNearKing = r;
                    }
                }
            } else if (r.getTeam() == myTeam) {
                analysis.allyCount++;
            }
        }

        if (analysis.enemyCount == 0) {
            analysis.shouldEngage = false;
            return analysis;
        }

        // Second pass: count allies that can attack the closest enemy
        if (analysis.closestEnemy != null) {
            MapLocation enemyLoc = analysis.closestEnemy.getLocation();
            for (RobotInfo r : nearbyRobots) {
                if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) {
                    if (r.getLocation().distanceSquaredTo(enemyLoc) <= 2) {
                        analysis.alliesAdjacentToEnemy++;
                    }
                }
            }
            // Include ourselves if adjacent
            if (here.distanceSquaredTo(enemyLoc) <= 2) {
                analysis.alliesAdjacentToEnemy++;
            }
        }

        // Calculate potential damage
        analysis.totalAllyDamage = analysis.alliesAdjacentToEnemy * BASE_BITE_DAMAGE;

        // Can we secure a kill?
        analysis.canSecureKill = analysis.lowestHPEnemy != null &&
                                 analysis.totalAllyDamage >= analysis.lowestHPEnemy.getHealth();

        // Decision logic
        int advantage = analysis.allyCount - analysis.enemyCount;

        // ALWAYS engage if:
        // 1. We can secure a kill this turn
        // 2. We have overwhelming numerical advantage (3:1)
        // 3. Enemy is threatening our king
        // 4. We're responding to an alpha strike call

        boolean alphaStrikeActive = m.alphaStrikeTarget != null &&
                                    rc.getRoundNum() - m.alphaStrikeRound <= 5;

        boolean kingThreatened = analysis.enemyNearKing != null &&
                                 closestToKingDistSq <= 25;

        analysis.shouldEngage = analysis.canSecureKill ||
                                analysis.allyCount >= analysis.enemyCount * OVERWHELMING_ADVANTAGE ||
                                kingThreatened ||
                                alphaStrikeActive ||
                                (advantage >= 0 && analysis.allyCount >= MIN_ALLIES_TO_COMMIT);

        // Retreat only if badly outnumbered AND can't kill anyone AND king not threatened
        analysis.shouldRetreat = !analysis.canSecureKill &&
                                 advantage < -1 &&
                                 !kingThreatened &&
                                 rc.getHealth() < 50;

        return analysis;
    }

    // ==================== TARGET SELECTION ====================

    /**
     * Select the best target using combat analysis.
     * Priority:
     * 1. Alpha strike target (team-coordinated)
     * 2. Focus fire target (king-designated)
     * 3. Enemy threatening king
     * 4. Low HP enemy we can kill
     * 5. Nearest enemy
     */
    public static RobotInfo selectTarget(RobotController rc, Mem m, CombatAnalysis analysis)
            throws GameActionException {
        if (analysis.enemyCount == 0) return null;

        // Check alpha strike target first
        if (m.alphaStrikeTarget != null && rc.getRoundNum() - m.alphaStrikeRound <= 5) {
            for (int i = 0; i < analysis.enemyCount; i++) {
                RobotInfo enemy = analysis.enemies[i];
                if (enemy != null && enemy.getLocation().equals(m.alphaStrikeTarget)) {
                    return enemy;
                }
            }
        }

        // Check focus fire target from king
        MapLocation focusTarget = getFocusTarget(rc);
        if (focusTarget != null) {
            for (int i = 0; i < analysis.enemyCount; i++) {
                RobotInfo enemy = analysis.enemies[i];
                if (enemy != null && enemy.getLocation().equals(focusTarget)) {
                    return enemy;
                }
            }
        }

        // Enemy threatening king takes priority
        if (analysis.enemyNearKing != null) {
            return analysis.enemyNearKing;
        }

        // Low HP enemy we can kill
        if (analysis.canSecureKill && analysis.lowestHPEnemy != null) {
            return analysis.lowestHPEnemy;
        }

        // Default to closest
        return analysis.closestEnemy;
    }

    // ==================== ATTACK EXECUTION ====================

    /**
     * Execute attack with smart cheese spending.
     * Calculates exact cheese needed to help secure the kill.
     */
    public static boolean executeAttack(RobotController rc, Mem m, RobotInfo target,
                                        CombatAnalysis analysis) throws GameActionException {
        if (target == null) return false;

        MapLocation targetLoc = target.getLocation();
        MapLocation here = rc.getLocation();

        if (here.distanceSquaredTo(targetLoc) > 2) return false;
        if (!rc.isActionReady()) return false;

        int targetHP = target.getHealth();
        int rawCheese = rc.getRawCheese();
        int globalCheese = rc.getGlobalCheese();

        // Calculate how much damage team can deal
        int otherAllyDamage = (analysis.alliesAdjacentToEnemy - 1) * BASE_BITE_DAMAGE;
        int hpRemaining = targetHP - otherAllyDamage;

        // Calculate cheese needed for us to finish the kill
        int cheeseToSpend = 0;

        if (hpRemaining <= BASE_BITE_DAMAGE) {
            // Base damage is enough
            cheeseToSpend = 0;
        } else {
            // Need extra damage: ceil(sqrt(cheese)) = extra damage needed
            int extraDamageNeeded = hpRemaining - BASE_BITE_DAMAGE;
            // cheese = (extraDamage)^2
            cheeseToSpend = extraDamageNeeded * extraDamageNeeded;

            // Cap at what we have (prefer raw cheese)
            int maxSpend = rawCheese + Math.min(globalCheese / 4, 50);
            cheeseToSpend = Math.min(cheeseToSpend, maxSpend);

            // Don't overspend - diminishing returns
            cheeseToSpend = Math.min(cheeseToSpend, 100); // +10 damage cap
        }

        // Bonus cheese if enemy is near our king
        if (m.kingLocation != null && targetLoc.distanceSquaredTo(m.kingLocation) <= 16) {
            int bonusCheese = Math.min(25, rawCheese);
            cheeseToSpend = Math.min(cheeseToSpend + bonusCheese, rawCheese + globalCheese / 3);
        }

        if (rc.canAttack(targetLoc, cheeseToSpend)) {
            rc.attack(targetLoc, cheeseToSpend);
            return true;
        }

        return false;
    }

    // ==================== TRAP PLACEMENT ====================

    /**
     * Place trap to cut off enemy retreat path.
     * Trap goes BEHIND the enemy (their likely escape route).
     */
    public static boolean placeHerdingTrap(RobotController rc, Mem m, RobotInfo target)
            throws GameActionException {
        if (target == null || !rc.isActionReady()) return false;
        if (rc.getGlobalCheese() < RAT_TRAP_COST + 20) return false;

        MapLocation here = rc.getLocation();
        MapLocation enemyLoc = target.getLocation();

        // Enemy retreat direction = away from us (or away from our king)
        Direction retreatDir;
        if (m.kingLocation != null && enemyLoc.distanceSquaredTo(m.kingLocation) <= 36) {
            retreatDir = m.kingLocation.directionTo(enemyLoc);
        } else {
            retreatDir = here.directionTo(enemyLoc);
        }

        // Try to place trap in retreat path
        Direction[] tryDirs = {retreatDir, retreatDir.rotateLeft(), retreatDir.rotateRight()};

        for (Direction dir : tryDirs) {
            MapLocation trapLoc = enemyLoc.add(dir);

            // Check if we can sense and place there
            if (!rc.canSenseLocation(trapLoc)) continue;

            // Verify it's in our vision cone
            Direction toTrap = here.directionTo(trapLoc);
            if (!isInVisionCone(rc.getDirection(), toTrap)) continue;

            if (rc.canPlaceRatTrap(trapLoc)) {
                rc.placeRatTrap(trapLoc);
                m.lastTrapPlaced = trapLoc;
                m.lastTrapRound = rc.getRoundNum();
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a direction is within our 90° vision cone.
     */
    private static boolean isInVisionCone(Direction facing, Direction toTarget) {
        int facingOrd = facing.ordinal();
        int targetOrd = toTarget.ordinal();
        int diff = Math.abs(facingOrd - targetOrd);
        if (diff > 4) diff = 8 - diff;
        return diff <= 1;
    }

    // ==================== MOVEMENT ====================

    /**
     * Cluster movement - move toward target while staying near allies.
     * Used for coordinated attacks.
     */
    public static boolean clusterMoveToward(RobotController rc, Mem m, MapLocation target)
            throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction toTarget = here.directionTo(target);

        if (toTarget == Direction.CENTER) return false;

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

            // Anti-oscillation
            if ((m.prev1 != null && next.equals(m.prev1)) ||
                (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }

            if (!rc.canSenseLocation(next)) continue;
            MapInfo info = rc.senseMapInfo(next);
            if (!info.isPassable()) continue;
            if (rc.senseRobotAtLocation(next) != null) continue;

            // Turn if needed
            if (rc.getDirection() != dir && rc.canTurn(dir)) {
                rc.turn(dir);
            }

            if (rc.canMoveForward()) {
                rc.moveForward();
                m.prev2 = m.prev1;
                m.prev1 = here;
                return true;
            }
        }

        return false;
    }

    /**
     * Move to surround the target instead of chasing linearly.
     * Each rat takes a position based on ID to spread around enemy.
     */
    public static boolean surroundMoveToward(RobotController rc, Mem m, MapLocation targetLoc,
                                             CombatAnalysis analysis) throws GameActionException {
        if (targetLoc == null) return false;

        MapLocation here = rc.getLocation();
        int distToTarget = here.distanceSquaredTo(targetLoc);

        // If already adjacent, we're in position
        if (distToTarget <= 2) {
            // Face the target
            Direction toTarget = here.directionTo(targetLoc);
            if (rc.getDirection() != toTarget && rc.canTurn(toTarget)) {
                rc.turn(toTarget);
            }
            return true;
        }

        // Calculate our surround position based on ID
        int slot = rc.getID() % 8;
        Direction[] surroundDirs = Direction.allDirections();
        Direction myAssignedDir = surroundDirs[slot];

        // Our goal position is adjacent to enemy in our assigned direction
        MapLocation goalPos = targetLoc.add(myAssignedDir);

        // If goal is blocked, try adjacent slots
        if (!isValidMoveTarget(rc, goalPos)) {
            goalPos = targetLoc.add(surroundDirs[(slot + 1) % 8]);
            if (!isValidMoveTarget(rc, goalPos)) {
                goalPos = targetLoc.add(surroundDirs[(slot + 7) % 8]);
            }
        }

        // Move toward our goal position
        return clusterMoveToward(rc, m, goalPos);
    }

    private static boolean isValidMoveTarget(RobotController rc, MapLocation loc)
            throws GameActionException {
        if (!rc.canSenseLocation(loc)) return true; // Assume valid if can't see
        MapInfo info = rc.senseMapInfo(loc);
        if (!info.isPassable()) return false;
        RobotInfo robot = rc.senseRobotAtLocation(loc);
        return robot == null || robot.getTeam() == rc.getTeam();
    }

    // ==================== MAIN COMBAT RUNNER ====================

    /**
     * Main combat state execution.
     */
    public static void runCombat(RobotController rc, Mem m, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Sense all nearby robots
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1);

        // Check for cat - flee if too close
        for (RobotInfo r : nearbyRobots) {
            if (r.getType() == UnitType.CAT) {
                int catDistSq = here.distanceSquaredTo(r.getLocation());
                if (catDistSq <= CAT_PANIC_DIST_SQ) {
                    m.currentState = RatState.FLEE;
                    m.lastCatLoc = r.getLocation();
                    m.lastCatSeenRound = roundNum;
                    return;
                }
            }
        }

        // Analyze battlefield
        CombatAnalysis analysis = analyzeBattlefield(rc, m, nearbyRobots);

        // No enemies - exit combat
        if (analysis.enemyCount == 0) {
            m.currentState = m.previousState;
            m.combatTarget = null;
            m.alphaStrikeTarget = null;
            return;
        }

        // Update ally count in memory
        m.alliesNearby = analysis.allyCount;

        // Squeak for help if needed
        int nearestCatDistSq = Integer.MAX_VALUE;
        for (RobotInfo r : nearbyRobots) {
            if (r.getType() == UnitType.CAT) {
                nearestCatDistSq = Math.min(nearestCatDistSq,
                    here.distanceSquaredTo(r.getLocation()));
            }
        }
        maybeSqueakHelp(rc, m, roundNum, nearestCatDistSq, analysis.enemyCount, analysis.allyCount);

        // If we can secure a kill, squeak alpha strike
        if (analysis.canSecureKill && analysis.lowestHPEnemy != null) {
            squeakAlphaStrike(rc, m, analysis.lowestHPEnemy.getLocation(), roundNum);
        }

        // Select target
        RobotInfo target = selectTarget(rc, m, analysis);

        if (target == null) {
            m.currentState = m.previousState;
            return;
        }

        MapLocation targetLoc = target.getLocation();
        int distToTarget = here.distanceSquaredTo(targetLoc);

        // Update combat memory
        m.combatTarget = targetLoc;
        m.combatTargetHP = target.getHealth();
        m.turnsSinceTargetSeen = 0;

        // === COMBAT ACTIONS (in priority order) ===

        // 1. Attack if adjacent
        if (distToTarget <= 2 && rc.isActionReady()) {
            executeAttack(rc, m, target, analysis);
        }

        // 2. Place herding trap if enemy might flee
        if (rc.isActionReady() && distToTarget <= 5 && analysis.allyCount >= analysis.enemyCount) {
            placeHerdingTrap(rc, m, target);
        }

        // 3. Move to surround (not just chase)
        if (distToTarget > 2 && rc.isMovementReady()) {
            surroundMoveToward(rc, m, targetLoc, analysis);
        } else if (distToTarget <= 2) {
            // Adjacent - face target for vision
            Direction toTarget = here.directionTo(targetLoc);
            if (rc.getDirection() != toTarget && rc.canTurn(toTarget)) {
                rc.turn(toTarget);
            }
        }

        // 4. If we should retreat, do so strategically (toward allies/king)
        if (analysis.shouldRetreat && rc.isMovementReady()) {
            retreatStrategically(rc, m, targetLoc);
        }
    }

    /**
     * Retreat toward our traps or toward allies, not randomly.
     */
    private static void retreatStrategically(RobotController rc, Mem m, MapLocation enemyLoc)
            throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction awayFromEnemy = enemyLoc.directionTo(here);

        // Prefer retreating toward king (allies likely there)
        Direction retreatDir = awayFromEnemy;
        if (m.kingLocation != null) {
            Direction toKing = here.directionTo(m.kingLocation);
            // Blend: prefer direction that's both away from enemy and toward king
            if (toKing != awayFromEnemy.opposite()) {
                retreatDir = toKing;
            }
        }

        // If we placed a trap recently, try to lead enemy through it
        if (m.lastTrapPlaced != null && rc.getRoundNum() - m.lastTrapRound <= 15) {
            Direction toTrap = here.directionTo(m.lastTrapPlaced);
            Direction trapToEnemy = m.lastTrapPlaced.directionTo(enemyLoc);
            // Position so trap is between us and enemy
            if (trapToEnemy == awayFromEnemy || trapToEnemy == awayFromEnemy.rotateLeft() ||
                trapToEnemy == awayFromEnemy.rotateRight()) {
                retreatDir = toTrap.opposite(); // Go past the trap
            }
        }

        clusterMoveToward(rc, m, here.add(retreatDir).add(retreatDir));
    }

    // ==================== ENTRY POINT ====================

    /**
     * Check if we should enter combat (called from main hauler loop).
     */
    public static boolean shouldEnterCombat(RobotController rc, Mem m, RobotInfo[] nearbyRobots)
            throws GameActionException {
        CombatAnalysis analysis = analyzeBattlefield(rc, m, nearbyRobots);

        if (analysis.enemyCount == 0) return false;

        // Store analysis results
        m.alliesNearby = analysis.allyCount;

        // Check for alpha strike call
        if (m.alphaStrikeTarget != null && rc.getRoundNum() - m.alphaStrikeRound <= 5) {
            return true;
        }

        // Check for local swarm target (help call)
        if (m.localSwarmTarget != null && m.localSwarmUntilRound >= rc.getRoundNum()) {
            int distToSwarm = rc.getLocation().distanceSquaredTo(m.localSwarmTarget);
            if (distToSwarm <= COMBAT_ENGAGE_RADIUS_SQ) {
                return true;
            }
        }

        return analysis.shouldEngage;
    }

    /**
     * Check if we should exit combat (no more enemies or need to flee).
     */
    public static boolean shouldExitCombat(RobotController rc, Mem m, RobotInfo[] nearbyEnemies)
            throws GameActionException {
        // Count actual enemies
        int enemyCount = 0;
        for (RobotInfo r : nearbyEnemies) {
            if (r != null && r.getType() == UnitType.BABY_RAT) {
                enemyCount++;
            }
        }

        // Exit if no enemies
        if (enemyCount == 0) return true;

        // Exit if low HP and outnumbered
        if (rc.getHealth() < 30 && m.alliesNearby < enemyCount) {
            return true;
        }

        // Stay in combat
        return false;
    }
}
