package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * State-based behaviors for the Rat King.
 */
public class StateBehaviors {

    public static void runKingSafeState(RobotController rc, int globalCheese, int spawnCost, int turnCount,
                                 MapLocation enemyRatLocation) throws GameActionException {
        int minCheeseForSpawn = spawnCost + 100;

        // Try to dig dirt during safe state to build reserves
        if (turnCount % 5 == 0 && rc.getDirt() < 10) {
            DirtWallDefense.tryDigDirt(rc);
        }

        if (turnCount % 10 == 0) {
            TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);
        }

        int nearbyBabies = Sensing.countAlliedBabyRatsNearKing(rc, NEAR_KING_COUNT_RADIUS);
        int desiredNearKingBabies = MIN_NEAR_KING_HAULERS + MIN_NEAR_KING_ATTACKERS;
        boolean lowRats = nearbyBabies < Math.max(desiredNearKingBabies, MIN_DEFENDERS_NEAR_KING);

        boolean enoughCheese = globalCheese >= minCheeseForSpawn && globalCheese >= CRITICAL_CHEESE_THRESHOLD;

        if (lowRats && enoughCheese) {
            Movement.trySpawnRat(rc);
            return;
        }

        if (turnCount <= 5 && globalCheese >= minCheeseForSpawn) {
            Movement.trySpawnRat(rc);
        } else if (globalCheese > LOW_CHEESE_THRESHOLD &&
                globalCheese >= minCheeseForSpawn &&
                turnCount % SPAWN_INTERVAL == 0) {
            Movement.trySpawnRat(rc);
        }
    }

    public static void runKingAlertState(RobotController rc, int globalCheese, int spawnCost,
                                  MapLocation enemyRatLocation, MapLocation catLocation,
                                  int catDistSq, int roundNum) throws GameActionException {
        int alertSpawnThreshold = LOW_CHEESE_THRESHOLD * 2;
        int minCheeseForSpawn = spawnCost + 200;

        TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);

        MapLocation kingLoc = rc.getLocation();
        int enemyDistSq = enemyRatLocation != null ? kingLoc.distanceSquaredTo(enemyRatLocation) : Integer.MAX_VALUE;

        // Proactive kiting - maintain distance from threats
        final int KITE_SAFE_DIST_SQ = 144; // 12 tiles
        final int KITE_CAT_SAFE_DIST_SQ = 196; // 14 tiles (cats more dangerous)

        boolean shouldKite = false;
        MapLocation kiteFrom = null;

        // Kite from cat if it's getting close
        if (catLocation != null && catDistSq < KITE_CAT_SAFE_DIST_SQ) {
            shouldKite = true;
            kiteFrom = catLocation;
        }
        // Kite from enemy rats if close and no cat threat
        else if (enemyRatLocation != null && enemyDistSq < KITE_SAFE_DIST_SQ && catLocation == null) {
            shouldKite = true;
            kiteFrom = enemyRatLocation;
        }

        // Try proactive kiting before placing defenses
        if (shouldKite && rc.isMovementReady()) {
            boolean kited = Movement.tryProactiveKite(rc, kiteFrom,
                    catLocation != null ? KITE_CAT_SAFE_DIST_SQ : KITE_SAFE_DIST_SQ);
            if (kited) {
                // Successfully moved, update position for trap placement
                kingLoc = rc.getLocation();
            }
        }

        if (catLocation != null) {
            final int MID_RANGE = 15;
            if (catDistSq <= MID_RANGE * MID_RANGE) {
                // Try dirt wall first, fall back to cat trap
                boolean placedWall = DirtWallDefense.tryPlaceDirtWall(rc, catLocation, roundNum);
                if (!placedWall) {
                    TrapPlacement.tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
                }
            }
        }

        // Place rat traps against enemy rats
        if (enemyRatLocation != null && enemyDistSq <= 100) {
            TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);
        }

        if (globalCheese > alertSpawnThreshold && globalCheese >= minCheeseForSpawn) {
            Movement.trySpawnRat(rc);
        }
    }

    public static void runKingEscapeState(RobotController rc, MapLocation catLocation, int catDistSq,
                                   int roundNum, int globalCheese, MapLocation enemyRatLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int enemyDistSq = enemyRatLocation != null ? kingLoc.distanceSquaredTo(enemyRatLocation) : Integer.MAX_VALUE;

        // Determine the primary threat
        MapLocation primaryThreat = catLocation;
        int primaryThreatDistSq = catDistSq;

        // If no cat or enemy is closer, prioritize enemy
        if (catLocation == null || (enemyRatLocation != null && enemyDistSq < catDistSq)) {
            primaryThreat = enemyRatLocation;
            primaryThreatDistSq = enemyDistSq;
        }

        boolean canAttemptEscape = (roundNum - KingRuntimeState.lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (primaryThreatDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape && primaryThreat != null) {
            // PRIORITY 0: If cornered, try to dig an escape route
            if (!rc.isMovementReady() || !hasEscapeRoute(rc, primaryThreat)) {
                Movement.tryDigEscapeRoute(rc, primaryThreat);
            }

            // PRIORITY 1: Try to place dirt wall between us and threat
            if (catLocation != null) {
                boolean placedDirtWall = DirtWallDefense.tryPlaceDirtWall(rc, catLocation, roundNum);

                // PRIORITY 2: If no dirt available, fall back to cat trap
                if (!placedDirtWall) {
                    TrapPlacement.tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
                }
            }

            // Place defensive rat traps against enemy rats
            if (enemyRatLocation != null && globalCheese >= 150) {
                TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);
            }

            // PRIORITY 3: Move away from threats (considers both cat and enemy)
            boolean escaped = Movement.tryKingEscapeMultipleThreat(rc, catLocation, enemyRatLocation);

            // If couldn't move, try digging again
            if (!escaped && rc.isActionReady()) {
                Movement.tryDigEscapeRoute(rc, primaryThreat);
            }

            KingRuntimeState.lastEscapeRound = roundNum;
            KingRuntimeState.kingState = KingState.ALERT;
        }
    }

    /**
     * Check if king has at least one escape route away from the threat.
     */
    private static boolean hasEscapeRoute(RobotController rc, MapLocation threat) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        Direction awayDir = threat.directionTo(kingLoc);
        if (awayDir == Direction.CENTER) return true;

        // Check if we can move in escape directions
        Direction[] escapeDirs = {awayDir, awayDir.rotateLeft(), awayDir.rotateRight()};
        for (Direction dir : escapeDirs) {
            if (rc.canMove(dir)) return true;
        }
        return false;
    }

    public static boolean isInBackstabMode(RobotController rc) throws GameActionException {
        // Check shared array flag
        try {
            int backstabFlag = rc.readSharedArray(BACKSTAB_MODE_INDEX);
            if (backstabFlag == 1) return true;
        } catch (Exception e) {
            // Index not set up yet
        }

        // Fallback heuristic
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getTeam() == enemyTeam &&
                    (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)) {
                if (robot.getType() == UnitType.BABY_RAT && robot.getHealth() < 100) {
                    return true;
                }
                if (robot.getType() == UnitType.RAT_KING && robot.getHealth() < 500) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void kingCounterAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());

        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT || enemy.getType() == UnitType.RAT_KING) {
                MapLocation enemyLoc = enemy.getLocation();
                int cheeseBoost = Math.min(KING_ATTACK_CHEESE_BOOST, rc.getGlobalCheese() / 10);

                if (rc.canAttack(enemyLoc, cheeseBoost)) {
                    rc.attack(enemyLoc, cheeseBoost);
                    return;
                }
            }
        }
    }
}
