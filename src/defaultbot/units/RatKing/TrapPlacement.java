package defaultbot.units.RatKing;

import battlecode.common.*;

import static defaultbot.config.Constants.*;

/**
 * Trap placement logic for the Rat King.
 */
public class TrapPlacement {

    public static void placeDefensiveTraps(RobotController rc, MapLocation enemyRatLocation) throws GameActionException {
        if (rc.getGlobalCheese() < 150) return;

        MapLocation kingLoc = rc.getLocation();

        if (enemyRatLocation != null) {
            Direction toEnemy = kingLoc.directionTo(enemyRatLocation);
            Direction[] approachDirs = {
                    toEnemy,
                    toEnemy.rotateLeft(),
                    toEnemy.rotateRight(),
                    toEnemy.rotateLeft().rotateLeft(),
                    toEnemy.rotateRight().rotateRight()
            };

            for (Direction dir : approachDirs) {
                MapLocation trapLoc = kingLoc;
                for (int i = 0; i < DEFENSIVE_TRAP_RADIUS; i++) {
                    trapLoc = trapLoc.add(dir);
                }
                if (rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    return;
                }
            }
        }

        Direction[] trapDirs = {
                Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
                Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
        };

        int startIdx = rc.getRoundNum() % trapDirs.length;
        for (int i = 0; i < trapDirs.length; i++) {
            Direction dir = trapDirs[(startIdx + i) % trapDirs.length];
            MapLocation trapLoc = kingLoc;
            for (int step = 0; step < DEFENSIVE_TRAP_RADIUS; step++) {
                trapLoc = trapLoc.add(dir);
            }
            if (rc.canPlaceRatTrap(trapLoc)) {
                rc.placeRatTrap(trapLoc);
                return;
            }
        }
    }

    public static void tryPlaceCatTrapDuringEscape(RobotController rc, MapLocation catLocation,
                                            int roundNum, int globalCheese) throws GameActionException {
        boolean inCooperation = !StateBehaviors.isInBackstabMode(rc);
        if (!inCooperation) {
            return;
        }

        final int CAT_TRAP_COOLDOWN = 7;
        if (roundNum - KingRuntimeState.lastCatTrapRound < CAT_TRAP_COOLDOWN) {
            return;
        }

        final int CAT_TRAP_COST = 10;
        final int SAFE_CHEESE_BUFFER = 50;
        if (globalCheese < CAT_TRAP_COST + SAFE_CHEESE_BUFFER) {
            return;
        }

        MapLocation kingLoc = rc.getLocation();
        MapLocation bestTrapLoc = null;

        Direction catToKing = catLocation.directionTo(kingLoc);
        if (catToKing != Direction.CENTER) {
            MapLocation betweenLoc = catLocation.add(catToKing);
            if (rc.canPlaceCatTrap(betweenLoc)) {
                bestTrapLoc = betweenLoc;
            }
        }

        if (bestTrapLoc == null && rc.canPlaceCatTrap(kingLoc)) {
            bestTrapLoc = kingLoc;
        }

        if (bestTrapLoc == null) {
            Direction[] adjacentDirs = {
                    Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
                    Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
            };

            Direction awayFromCat = catLocation.directionTo(kingLoc);
            for (Direction dir : adjacentDirs) {
                MapLocation testLoc = kingLoc.add(dir);
                if (rc.canPlaceCatTrap(testLoc)) {
                    bestTrapLoc = testLoc;
                    if (dir == awayFromCat || dir == awayFromCat.rotateLeft() || dir == awayFromCat.rotateRight()) {
                        break;
                    }
                }
            }
        }

        if (bestTrapLoc != null) {
            rc.placeCatTrap(bestTrapLoc);
            KingRuntimeState.lastCatTrapRound = roundNum;
        }
    }
}
