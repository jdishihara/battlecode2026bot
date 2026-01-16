package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;

/**
 * Movement and escape methods for the Rat King.
 */
public class Movement {

    public static boolean tryKingEscape(RobotController rc, MapLocation catLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        Direction awayDir = catLocation.directionTo(kingLoc);
        if (awayDir == Direction.CENTER) awayDir = Direction.NORTH;

        Direction[] candidates = {
                awayDir,
                awayDir.rotateLeft(),
                awayDir.rotateRight(),
                awayDir.rotateLeft().rotateLeft(),
                awayDir.rotateRight().rotateRight()
        };

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : candidates) {
            if (!rc.canMove(dir)) continue;

            MapLocation newLoc = kingLoc.add(dir);
            int currentDistSq = kingLoc.distanceSquaredTo(catLocation);
            int newDistSq = newLoc.distanceSquaredTo(catLocation);

            int score = 3 * (newDistSq - currentDistSq);

            if (newLoc.x <= 2 || newLoc.x >= mapWidth - 3 ||
                    newLoc.y <= 2 || newLoc.y >= mapHeight - 3) {
                score -= 50;
            }

            int passableNeighbors = countPassableNeighbors(newLoc, nearby);
            if (passableNeighbors <= 1) {
                score -= 80;
            } else if (passableNeighbors == 2) {
                score -= 30;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
            return true;
        }
        return false;
    }

    public static int countPassableNeighbors(MapLocation loc, MapInfo[] nearby) {
        int count = 0;
        for (Direction d : DIRECTIONS) {
            MapLocation next = loc.add(d);
            if (isLocationPassable(next, nearby)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) {
                return info.isPassable();
            }
        }
        return true;
    }

    public static void trySpawnRat(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation spawnLoc = new MapLocation(myLocation.x + dx, myLocation.y + dy);
                if (rc.canBuildRat(spawnLoc)) {
                    rc.buildRat(spawnLoc);
                    return;
                }
            }
        }
    }
}
