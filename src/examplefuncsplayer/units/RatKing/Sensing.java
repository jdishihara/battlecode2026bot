package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;

/**
 * Sensing helper methods for the Rat King.
 */
public class Sensing {

    public static RobotInfo findNearestCatForKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(ALERT_RADIUS);
        MapLocation myLocation = rc.getLocation();

        RobotInfo nearestCat = null;
        int minDist = Integer.MAX_VALUE;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.CAT) {
                int dist = myLocation.distanceSquaredTo(robot.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestCat = robot;
                }
            }
        }
        return nearestCat;
    }

    public static RobotInfo findNearestEnemyRat(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(ALERT_RADIUS);
        Team enemy = rc.getTeam().opponent();
        MapLocation myLocation = rc.getLocation();

        RobotInfo nearestEnemy = null;
        int minDist = Integer.MAX_VALUE;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getTeam() == enemy &&
                    (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)) {
                int dist = myLocation.distanceSquaredTo(robot.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestEnemy = robot;
                }
            }
        }
        return nearestEnemy;
    }

    public static int countAlliedBabyRatsNearKing(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radius);
        int count = 0;
        Team myTeam = rc.getTeam();
        for (RobotInfo r : robots) {
            if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }

    /**
     * Count enemy baby rats within a given distance squared.
     */
    public static int countNearbyEnemyRats(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radiusSq);
        int count = 0;
        Team enemy = rc.getTeam().opponent();
        for (RobotInfo r : robots) {
            if (r.getTeam() == enemy && r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }

    /**
     * Find all enemy rats within a radius and return the closest ones.
     * Useful for threat assessment.
     */
    public static RobotInfo[] findNearbyEnemyRats(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radiusSq);
        Team enemy = rc.getTeam().opponent();

        // Count enemies first
        int count = 0;
        for (RobotInfo r : robots) {
            if (r.getTeam() == enemy &&
                (r.getType() == UnitType.BABY_RAT || r.getType() == UnitType.RAT_KING)) {
                count++;
            }
        }

        // Create result array
        RobotInfo[] enemies = new RobotInfo[count];
        int idx = 0;
        for (RobotInfo r : robots) {
            if (r.getTeam() == enemy &&
                (r.getType() == UnitType.BABY_RAT || r.getType() == UnitType.RAT_KING)) {
                enemies[idx++] = r;
            }
        }

        return enemies;
    }

    /**
     * Find visible cheese mines (cheese-rich areas for roaming target).
     * Returns the nearest cheese mine center location, or null if none visible.
     */
    public static MapLocation findNearestCheeseMine(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation myLocation = rc.getLocation();

        MapLocation nearestMine = null;
        int minDistSq = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                MapLocation mineLoc = info.getMapLocation();
                int distSq = myLocation.distanceSquaredTo(mineLoc);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                    nearestMine = mineLoc;
                }
            }
        }
        return nearestMine;
    }

    /**
     * Find the direction with the most visible cheese.
     * Used to pick roaming targets toward cheese-rich areas.
     *
     * @return Best direction to roam, or null if no cheese visible
     */
    public static MapLocation findCheeseRichDirection(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation myLocation = rc.getLocation();

        // Find the location with the most cheese, or nearest cheese
        MapLocation bestCheeseLoc = null;
        int bestCheeseAmount = 0;
        int bestDistSq = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            int cheese = info.getCheeseAmount();
            if (cheese > 0) {
                MapLocation loc = info.getMapLocation();
                int distSq = myLocation.distanceSquaredTo(loc);

                // Prefer larger cheese deposits, then closer ones
                if (cheese > bestCheeseAmount || (cheese == bestCheeseAmount && distSq < bestDistSq)) {
                    bestCheeseAmount = cheese;
                    bestDistSq = distSq;
                    bestCheeseLoc = loc;
                }
            }
        }
        return bestCheeseLoc;
    }

    /**
     * Get roaming target: Move toward closest corner unless a cheese mine is found.
     * Once a mine is discovered, prioritize moving toward mines.
     */
    public static MapLocation getRoamingTarget(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();

        // Priority 1: Visible cheese mine (switch to mine-seeking once found)
        MapLocation visibleMine = findNearestCheeseMine(rc);
        if (visibleMine != null) {
            return visibleMine;
        }

        // Priority 2: Check shared array for known mine locations
        // Stored in slots 10-25: each mine uses 2 slots (x+1, y+1), count in slot 9
        // Values are stored as x+1 and y+1, so 0 means unset
        int mineCount = rc.readSharedArray(KingConstants.MINE_COUNT_SLOT);

        MapLocation closestMine = null;
        int closestMineDist = Integer.MAX_VALUE;

        for (int i = 0; i < mineCount && i < KingConstants.MAX_KNOWN_MINES; i++) {
            int xPlus1 = rc.readSharedArray(KingConstants.MINE_START_SLOT + i * 2);
            int yPlus1 = rc.readSharedArray(KingConstants.MINE_START_SLOT + i * 2 + 1);

            if (xPlus1 > 0 && yPlus1 > 0) {
                int x = xPlus1 - 1;
                int y = yPlus1 - 1;
                MapLocation mineLoc = new MapLocation(x, y);
                int dist = myLocation.distanceSquaredTo(mineLoc);

                // Find closest mine that's worth moving to
                if (dist > 16 && dist < closestMineDist) {
                    closestMineDist = dist;
                    closestMine = mineLoc;
                }
            }
        }

        if (closestMine != null) {
            return closestMine;
        }

        // Priority 3: No mines known - move toward closest corner
        return getClosestCorner(rc, myLocation, mapWidth, mapHeight);
    }

    /**
     * Find the closest corner of the map.
     * Corners are offset slightly from edges to avoid getting trapped.
     */
    public static MapLocation getClosestCorner(RobotController rc, MapLocation myLocation,
                                                int mapWidth, int mapHeight) {
        // Offset from true corner to avoid edge traps (king is 3x3)
        final int CORNER_OFFSET = 5;

        MapLocation[] corners = {
            new MapLocation(CORNER_OFFSET, CORNER_OFFSET),                           // Bottom-left
            new MapLocation(mapWidth - 1 - CORNER_OFFSET, CORNER_OFFSET),            // Bottom-right
            new MapLocation(CORNER_OFFSET, mapHeight - 1 - CORNER_OFFSET),           // Top-left
            new MapLocation(mapWidth - 1 - CORNER_OFFSET, mapHeight - 1 - CORNER_OFFSET)  // Top-right
        };

        MapLocation closestCorner = corners[0];
        int minDistSq = myLocation.distanceSquaredTo(corners[0]);

        for (int i = 1; i < corners.length; i++) {
            int distSq = myLocation.distanceSquaredTo(corners[i]);
            if (distSq < minDistSq) {
                minDistSq = distSq;
                closestCorner = corners[i];
            }
        }

        // If already at/near corner, no need to move
        if (minDistSq <= 25) {
            return null;
        }

        return closestCorner;
    }
}
