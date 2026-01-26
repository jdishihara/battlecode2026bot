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
        } else if (globalCheese > LOW_CHEESE_THRESHOLD && globalCheese >= minCheeseForSpawn) {
            // Use slower spawn interval for hauler phases (0, 2), normal for attacker phases (1, 3)
            boolean isHaulerPhase = SpawnPhaseManager.isHaulerPhase(rc);
            int interval = isHaulerPhase ? HAULER_SPAWN_INTERVAL : SPAWN_INTERVAL;
            if (turnCount % interval == 0) {
                Movement.trySpawnRat(rc);
            }
        }
    }

    /**
     * Roaming state: King moves toward cheese-rich areas when safe.
     */
    public static void runKingRoamingState(RobotController rc, int globalCheese, int spawnCost,
                                           int turnCount, int roundNum) throws GameActionException {
        int minCheeseForSpawn = spawnCost + 100;

        // Still spawn rats while roaming
        int nearbyBabies = Sensing.countAlliedBabyRatsNearKing(rc, NEAR_KING_COUNT_RADIUS);
        int desiredNearKingBabies = MIN_NEAR_KING_HAULERS + MIN_NEAR_KING_ATTACKERS;
        boolean lowRats = nearbyBabies < Math.max(desiredNearKingBabies, MIN_DEFENDERS_NEAR_KING);

        boolean enoughCheese = globalCheese >= minCheeseForSpawn && globalCheese >= CRITICAL_CHEESE_THRESHOLD;

        if (lowRats && enoughCheese) {
            Movement.trySpawnRat(rc);
        }

        // Try to dig dirt to build reserves
        if (turnCount % 5 == 0 && rc.getDirt() < 10) {
            DirtWallDefense.tryDigDirt(rc);
        }

        // Move toward cheese-rich areas
        if (rc.isMovementReady() && turnCount % 3 == 0) {
            MapLocation roamTarget = Sensing.getRoamingTarget(rc);
            if (roamTarget != null) {
                Movement.tryRoamingMove(rc, roamTarget);
            }
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
        int enemyRatDistSq = enemyRatLocation != null ? rc.getLocation().distanceSquaredTo(enemyRatLocation) : Integer.MAX_VALUE;
        boolean hasCatThreat = catLocation != null;
        boolean hasRatThreat = enemyRatLocation != null;

        // Determine primary threat (closest one)
        MapLocation primaryThreat = null;
        boolean primaryIsCat = false;

        if (hasCatThreat && hasRatThreat) {
            if (catDistSq <= enemyRatDistSq) {
                primaryThreat = catLocation;
                primaryIsCat = true;
            } else {
                primaryThreat = enemyRatLocation;
            }
        } else if (hasCatThreat) {
            primaryThreat = catLocation;
            primaryIsCat = true;
        } else if (hasRatThreat) {
            primaryThreat = enemyRatLocation;
        }

        if (primaryThreat == null) {
            KingRuntimeState.kingState = KingState.ALERT;
            return;
        }

        int primaryDistSq = primaryIsCat ? catDistSq : enemyRatDistSq;
        boolean canAttemptEscape = (roundNum - KingRuntimeState.lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (primaryDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape) {
            if (primaryIsCat) {
                // Escaping from cat: dirt walls and cat traps
                boolean placedDirtWall = DirtWallDefense.tryPlaceDirtWall(rc, catLocation, roundNum);
                if (!placedDirtWall) {
                    TrapPlacement.tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
                }
            } else {
                // Escaping from enemy rat: place rat traps in retreat path
                TrapPlacement.placeTrapsInRetreatPath(rc, enemyRatLocation);
            }

            // Also place defensive rat traps if we have cheese and there are enemy rats
            if (globalCheese >= 200 && enemyRatLocation != null) {
                TrapPlacement.placeDefensiveTraps(rc, enemyRatLocation);
            }

            // Move away from the primary threat
            Movement.tryKingEscapeFromThreat(rc, primaryThreat);
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

        // Fallback heuristic - use cached robots to save bytecode
        RobotInfo[] nearbyRobots = KingRuntimeState.cachedNearbyRobots;
        if (nearbyRobots == null) {
            // Cache not initialized, initialize it
            Sensing.initializeSensingCache(rc);
            nearbyRobots = KingRuntimeState.cachedNearbyRobots;
        }

        Team enemyTeam = rc.getTeam().opponent();

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
        // Use cached robots to save bytecode
        RobotInfo[] nearbyRobots = KingRuntimeState.cachedNearbyRobots;
        if (nearbyRobots == null) {
            Sensing.initializeSensingCache(rc);
            nearbyRobots = KingRuntimeState.cachedNearbyRobots;
        }

        MapLocation kingLoc = KingRuntimeState.cachedKingLocation;
        if (kingLoc == null) {
            kingLoc = rc.getLocation();
        }

        Team enemyTeam = rc.getTeam().opponent();
        final int ATTACK_RADIUS_SQ = 8;  // Original radius

        for (RobotInfo enemy : nearbyRobots) {
            if (enemy.getTeam() != enemyTeam) continue;
            if (enemy.getType() != UnitType.BABY_RAT && enemy.getType() != UnitType.RAT_KING) continue;

            // Preserve original radius filter
            if (kingLoc.distanceSquaredTo(enemy.getLocation()) > ATTACK_RADIUS_SQ) continue;

            MapLocation enemyLoc = enemy.getLocation();
            int cheeseBoost = Math.min(KING_ATTACK_CHEESE_BOOST, rc.getGlobalCheese() / 10);

            if (rc.canAttack(enemyLoc, cheeseBoost)) {
                rc.attack(enemyLoc, cheeseBoost);
                return;
            }
        }
    }
}
