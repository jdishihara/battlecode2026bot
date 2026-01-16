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

        if (globalCheese > alertSpawnThreshold && globalCheese >= minCheeseForSpawn) {
            Movement.trySpawnRat(rc);
        }
    }

    public static void runKingEscapeState(RobotController rc, MapLocation catLocation, int catDistSq,
                                   int roundNum, int globalCheese, MapLocation enemyRatLocation) throws GameActionException {
        boolean canAttemptEscape = (roundNum - KingRuntimeState.lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (catDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape && catLocation != null) {
            // PRIORITY 1: Try to place dirt wall between us and cat
            boolean placedDirtWall = DirtWallDefense.tryPlaceDirtWall(rc, catLocation, roundNum);

            // PRIORITY 2: If no dirt available, fall back to cat trap
            if (!placedDirtWall) {
                TrapPlacement.tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
            }

            // Place defensive rat traps if we have cheese
            if (globalCheese >= 200) {
                TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);
            }

            // PRIORITY 3: Move away from cat
            Movement.tryKingEscape(rc, catLocation);
            KingRuntimeState.lastEscapeRound = roundNum;

            KingRuntimeState.kingState = KingState.ALERT;
        }
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
