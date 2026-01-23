package defaultbot.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;

/**
 * Dirt wall defense logic for the Rat King.
 * When escaping from cats, king builds 2-tile dirt walls.
 */
public class DirtWallDefense {

    /**
     * Attempts to place a 2-tile dirt wall between the king and the cat.
     * The wall is placed perpendicular to the cat's approach direction.
     *
     * @param rc The robot controller
     * @param catLocation The location of the threatening cat
     * @param roundNum Current round number
     * @return true if at least one dirt was placed, false otherwise
     */
    public static boolean tryPlaceDirtWall(RobotController rc, MapLocation catLocation, int roundNum) throws GameActionException {
        // Check cooldown
        if (roundNum - KingRuntimeState.lastDirtWallRound < KingConstants.DIRT_WALL_COOLDOWN) {
            return false;
        }

        // Check if we have dirt available
        int availableDirt = rc.getDirt();
        if (availableDirt < 1) {
            return false;
        }

        // Check if we have enough cheese (dirt placement costs cheese)
        int globalCheese = rc.getGlobalCheese();
        if (globalCheese < KingConstants.MIN_CHEESE_FOR_DIRT) {
            return false;
        }

        // Check action cooldown
        if (!rc.isActionReady()) {
            return false;
        }

        MapLocation kingLoc = rc.getLocation();

        // Direction from king to cat (we want to block this direction)
        Direction toCat = kingLoc.directionTo(catLocation);
        if (toCat == Direction.CENTER) {
            return false;
        }

        // Get perpendicular directions for the wall
        Direction perpLeft = toCat.rotateLeft().rotateLeft(); // 90 degrees left
        Direction perpRight = toCat.rotateRight().rotateRight(); // 90 degrees right

        // Find the best location between king and cat to place the wall
        // We want to place it a couple tiles in front of the king (toward the cat)
        MapLocation wallCenter = kingLoc.add(toCat).add(toCat); // 2 tiles toward cat

        // If that's too close to cat or not valid, try 1 tile away
        if (kingLoc.distanceSquaredTo(wallCenter) > kingLoc.distanceSquaredTo(catLocation) ||
            !isValidDirtLocation(rc, wallCenter)) {
            wallCenter = kingLoc.add(toCat); // 1 tile toward cat
        }

        boolean placedAny = false;
        int dirtPlaced = 0;
        int maxDirtToPlace = Math.min(2, availableDirt);

        // Try to place dirt at wall center first
        if (dirtPlaced < maxDirtToPlace && tryPlaceSingleDirt(rc, wallCenter)) {
            placedAny = true;
            dirtPlaced++;
            KingRuntimeState.lastDirtWallRound = roundNum;
        }

        // If we can place more and action is still ready, place adjacent dirt
        if (dirtPlaced < maxDirtToPlace && rc.isActionReady()) {
            // Try perpendicular positions to create a wall
            MapLocation leftPos = wallCenter.add(perpLeft);
            MapLocation rightPos = wallCenter.add(perpRight);

            if (tryPlaceSingleDirt(rc, leftPos)) {
                placedAny = true;
                dirtPlaced++;
            } else if (rc.isActionReady() && tryPlaceSingleDirt(rc, rightPos)) {
                placedAny = true;
                dirtPlaced++;
            }
        }

        // If center didn't work, try alternative wall positions
        if (!placedAny) {
            // Try positions directly adjacent to king in the direction of cat
            Direction[] wallDirs = {toCat, toCat.rotateLeft(), toCat.rotateRight()};

            for (Direction dir : wallDirs) {
                if (!rc.isActionReady()) break;

                MapLocation pos = kingLoc.add(dir);
                if (tryPlaceSingleDirt(rc, pos)) {
                    placedAny = true;
                    KingRuntimeState.lastDirtWallRound = roundNum;
                    break;
                }
            }
        }

        return placedAny;
    }

    /**
     * Attempts to place a single dirt block at the specified location.
     *
     * @param rc The robot controller
     * @param loc The location to place dirt
     * @return true if dirt was placed, false otherwise
     */
    public static boolean tryPlaceSingleDirt(RobotController rc, MapLocation loc) throws GameActionException {
        if (rc.canPlaceDirt(loc)) {
            rc.placeDirt(loc);
            return true;
        }
        return false;
    }

    /**
     * Checks if a location is valid for dirt placement.
     * Dirt cannot be placed on walls, existing dirt, robots, cheese, or cheese mines.
     */
    public static boolean isValidDirtLocation(RobotController rc, MapLocation loc) throws GameActionException {
        // Check if location is on the map
        if (!rc.onTheMap(loc)) {
            return false;
        }

        // Check if we can sense the location
        if (!rc.canSenseLocation(loc)) {
            return false;
        }

        MapInfo info = rc.senseMapInfo(loc);

        // Cannot place on walls or existing dirt
        if (info.isWall() || info.isDirt()) {
            return false;
        }

        // Cannot place on cheese mines
        if (info.hasCheeseMine()) {
            return false;
        }

        // Cannot place if there's a robot there
        if (rc.canSenseRobotAtLocation(loc) && rc.senseRobotAtLocation(loc) != null) {
            return false;
        }

        return true;
    }

    /**
     * Attempts to dig dirt from nearby locations to build up dirt reserves.
     * Called during safe state to prepare for emergencies.
     */
    public static void tryDigDirt(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (rc.getGlobalCheese() < KingConstants.DIRT_PLACE_COST + 50) return; // Keep buffer for other actions

        MapLocation kingLoc = rc.getLocation();

        // Look for dirt in adjacent tiles
        for (Direction dir : DIRECTIONS) {
            MapLocation checkLoc = kingLoc.add(dir);
            if (rc.canRemoveDirt(checkLoc)) {
                rc.removeDirt(checkLoc);
                return;
            }
        }
    }
}
