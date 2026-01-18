package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;

/**
 * Movement and escape methods for the Rat King.
 * v5 Updates: Improved escape logic that considers both cats and enemy rats.
 */
public class Movement {

    /**
     * Try to escape from a single threat (cat or enemy rat).
     */
    public static boolean tryKingEscape(RobotController rc, MapLocation threatLocation) throws GameActionException {
        return tryKingEscapeMultipleThreat(rc, threatLocation, null);
    }

    /**
     * Try to escape from multiple threats (cat and/or enemy rats).
     * Considers both threats when choosing escape direction.
     */
    public static boolean tryKingEscapeMultipleThreat(RobotController rc, MapLocation catLocation,
                                                       MapLocation enemyRatLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // Calculate combined threat direction
        Direction awayDir;
        if (catLocation != null && enemyRatLocation != null) {
            // Both threats present - calculate average threat position
            int avgX = (catLocation.x + enemyRatLocation.x) / 2;
            int avgY = (catLocation.y + enemyRatLocation.y) / 2;
            MapLocation avgThreat = new MapLocation(avgX, avgY);
            awayDir = avgThreat.directionTo(kingLoc);
        } else if (catLocation != null) {
            awayDir = catLocation.directionTo(kingLoc);
        } else if (enemyRatLocation != null) {
            awayDir = enemyRatLocation.directionTo(kingLoc);
        } else {
            return false; // No threat
        }

        if (awayDir == Direction.CENTER) awayDir = Direction.NORTH;

        // Consider more directions for better escape options
        Direction[] candidates = {
                awayDir,
                awayDir.rotateLeft(),
                awayDir.rotateRight(),
                awayDir.rotateLeft().rotateLeft(),
                awayDir.rotateRight().rotateRight(),
                awayDir.rotateLeft().rotateLeft().rotateLeft(),
                awayDir.rotateRight().rotateRight().rotateRight()
        };

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : candidates) {
            if (!rc.canMove(dir)) continue;

            MapLocation newLoc = kingLoc.add(dir);
            int score = 0;

            // Score based on distance from cat (most dangerous)
            if (catLocation != null) {
                int currentDistSq = kingLoc.distanceSquaredTo(catLocation);
                int newDistSq = newLoc.distanceSquaredTo(catLocation);
                score += 5 * (newDistSq - currentDistSq); // Weight cat heavily
            }

            // Score based on distance from enemy rats
            if (enemyRatLocation != null) {
                int currentDistSq = kingLoc.distanceSquaredTo(enemyRatLocation);
                int newDistSq = newLoc.distanceSquaredTo(enemyRatLocation);
                score += 2 * (newDistSq - currentDistSq);
            }

            // Heavy penalty for corners and edges (can get trapped)
            if (newLoc.x <= 2 || newLoc.x >= mapWidth - 3 ||
                    newLoc.y <= 2 || newLoc.y >= mapHeight - 3) {
                score -= 100;
            }

            // Penalty for limited escape routes
            int passableNeighbors = countPassableNeighbors(newLoc, nearby);
            if (passableNeighbors <= 1) {
                score -= 150; // Severe penalty for dead ends
            } else if (passableNeighbors == 2) {
                score -= 50;
            } else if (passableNeighbors >= 5) {
                score += 20; // Bonus for open areas
            }

            // Bonus for moving toward map center (more escape options)
            MapLocation center = new MapLocation(mapWidth / 2, mapHeight / 2);
            int currentCenterDist = kingLoc.distanceSquaredTo(center);
            int newCenterDist = newLoc.distanceSquaredTo(center);
            if (newCenterDist < currentCenterDist) {
                score += 15;
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

    /**
     * Proactive kiting: Move away from threats before they get dangerously close.
     * Called when in ALERT state to maintain safe distance.
     */
    public static boolean tryProactiveKite(RobotController rc, MapLocation threatLocation, int safeDistSq) throws GameActionException {
        if (threatLocation == null) return false;

        MapLocation kingLoc = rc.getLocation();
        int currentDistSq = kingLoc.distanceSquaredTo(threatLocation);

        // Only kite if threat is closer than safe distance
        if (currentDistSq >= safeDistSq) return false;

        // Try to move away
        return tryKingEscape(rc, threatLocation);
    }

    /**
     * Try to dig an escape route when cornered.
     */
    public static boolean tryDigEscapeRoute(RobotController rc, MapLocation threatLocation) throws GameActionException {
        if (!rc.isActionReady()) return false;
        if (rc.getGlobalCheese() < 20) return false; // Digging costs cheese

        MapLocation kingLoc = rc.getLocation();
        Direction awayDir = threatLocation.directionTo(kingLoc);
        if (awayDir == Direction.CENTER) return false;

        // Try to dig dirt in escape directions
        Direction[] digDirs = {
                awayDir,
                awayDir.rotateLeft(),
                awayDir.rotateRight()
        };

        for (Direction dir : digDirs) {
            MapLocation digLoc = kingLoc.add(dir);
            if (rc.canRemoveDirt(digLoc)) {
                rc.removeDirt(digLoc);
                return true;
            }
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
