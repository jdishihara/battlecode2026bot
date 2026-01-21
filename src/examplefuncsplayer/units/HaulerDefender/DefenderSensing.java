package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;

/**
 * Sensing utilities for HaulerDefender rats.
 * Includes mine discovery, caching, and shared array communication.
 */
public class DefenderSensing {

    // Mine slot constants - use DefenderMining values directly to avoid duplication
    // Slot 9 = mine count, Slots 10+ = mine coordinates (x+1, y+1 pairs)
    public static final int MAX_ASSIGNMENT_DISTANCE_SQ = 900;
    public static final int CONGESTION_THRESHOLD = 5;

    /**
     * Find the nearest cheese in sensed map info.
     */
    public static MapLocation findNearestCheese(RobotController rc, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestD = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                int d = here.distanceSquaredTo(info.getMapLocation());
                if (d < bestD) {
                    bestD = d;
                    best = info.getMapLocation();
                }
            }
        }
        return best;
    }

    /**
     * Find the nearest cheese mine in sensed map info.
     */
    public static MapLocation findNearestCheeseMine(RobotController rc, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestD = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                int d = here.distanceSquaredTo(info.getMapLocation());
                if (d < bestD) {
                    bestD = d;
                    best = info.getMapLocation();
                }
            }
        }
        return best;
    }

    /**
     * Find the nearest cheese mine in sensed map info AND check known mines from memory.
     * Also caches any newly discovered mines.
     * (Improved version from defaultbot)
     */
    public static MapLocation findNearestCheeseMine(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestD = Integer.MAX_VALUE;

        // First, scan visible area for mines and cache them
        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                MapLocation mineLoc = info.getMapLocation();
                int d = here.distanceSquaredTo(mineLoc);
                if (d < bestD) {
                    bestD = d;
                    best = mineLoc;
                }
                // Cache this mine in memory
                m.cacheMineLoc(mineLoc);
            }
        }

        // Also check our cached known mines
        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation knownMine = m.knownMines[i];
            if (knownMine != null) {
                int d = here.distanceSquaredTo(knownMine);
                if (d < bestD) {
                    bestD = d;
                    best = knownMine;
                }
            }
        }

        // Try to read mines from shared array (if rat king has written them)
        // King uses x+1/y+1 encoding so 0 means "unset"
        int mineCount = rc.readSharedArray(DefenderMining.MINE_COUNT_SLOT);
        for (int i = 0; i < mineCount && i < DefenderMining.MAX_KNOWN_MINES; i++) {
            int xPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2);
            int yPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2 + 1);
            if (xPlus1 > 0 && yPlus1 > 0) { // Valid mine location (x+1, y+1 encoding)
                MapLocation mineLoc = new MapLocation(xPlus1 - 1, yPlus1 - 1);
                m.cacheMineLoc(mineLoc);
                int d = here.distanceSquaredTo(mineLoc);
                if (d < bestD) {
                    bestD = d;
                    best = mineLoc;
                }
            }
        }

        return best;
    }

    /**
     * Check if we should explore for more mines.
     * Returns true if we don't know enough mines yet.
     */
    public static boolean needMoreMines(Mem m, int targetCount) {
        return m.knownMineCount < targetCount;
    }

    /**
     * Scan nearby and cache any mines found.
     */
    public static boolean scanAndCacheMines(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        boolean foundNew = false;

        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                if (m.cacheMineLoc(info.getMapLocation())) {
                    foundNew = true;
                }
            }
        }

        // Also read from shared array (King uses x+1/y+1 encoding)
        int mineCount = rc.readSharedArray(DefenderMining.MINE_COUNT_SLOT);
        for (int i = 0; i < mineCount && i < DefenderMining.MAX_KNOWN_MINES; i++) {
            int xPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2);
            int yPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2 + 1);
            if (xPlus1 > 0 && yPlus1 > 0) {
                MapLocation mineLoc = new MapLocation(xPlus1 - 1, yPlus1 - 1);
                if (m.cacheMineLoc(mineLoc)) {
                    foundNew = true;
                }
            }
        }

        return foundNew;
    }

    /**
     * Get an assigned mine for this rat.
     */
    public static MapLocation getOrAssignMine(RobotController rc, Mem m) throws GameActionException {
        refreshMinesFromSharedArray(rc, m);

        if (m.knownMineCount == 0) {
            return null;
        }

        MapLocation[] validMines = new MapLocation[m.knownMineCount];
        int validCount = 0;

        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation mine = m.knownMines[i];
            if (mine == null) continue;

            if (m.kingLocation != null) {
                int distSq = m.kingLocation.distanceSquaredTo(mine);
                if (distSq > MAX_ASSIGNMENT_DISTANCE_SQ) {
                    continue;
                }
            }

            validMines[validCount++] = mine;
        }

        if (validCount == 0) {
            return findNearestKnownMine(rc, m);
        }

        int assignmentIndex = rc.getID() % validCount;
        MapLocation assigned = validMines[assignmentIndex];

        if (isMineCongestedNearby(rc, assigned)) {
            MapLocation alternative = findLessCongestedMine(rc, m, validMines, validCount, assigned);
            if (alternative != null) {
                assigned = alternative;
            }
        }

        m.assignedMine = assigned;
        return assigned;
    }

    private static void refreshMinesFromSharedArray(RobotController rc, Mem m) throws GameActionException {
        int mineCount = rc.readSharedArray(DefenderMining.MINE_COUNT_SLOT);
        for (int i = 0; i < mineCount && i < DefenderMining.MAX_KNOWN_MINES; i++) {
            int xPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2);
            int yPlus1 = rc.readSharedArray(DefenderMining.MINE_START_SLOT + i * 2 + 1);
            if (xPlus1 > 0 && yPlus1 > 0) {
                MapLocation mineLoc = new MapLocation(xPlus1 - 1, yPlus1 - 1);
                m.cacheMineLoc(mineLoc);
            }
        }
    }

    private static MapLocation findNearestKnownMine(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        MapLocation nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation mine = m.knownMines[i];
            if (mine != null) {
                int d = here.distanceSquaredTo(mine);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = mine;
                }
            }
        }

        return nearest;
    }

    private static boolean isMineCongestedNearby(RobotController rc, MapLocation mine) throws GameActionException {
        if (!rc.canSenseLocation(mine)) {
            return false;
        }

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(mine, 25, rc.getTeam());
        int haulerCount = 0;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT) {
                haulerCount++;
            }
        }

        return haulerCount >= CONGESTION_THRESHOLD;
    }

    private static MapLocation findLessCongestedMine(RobotController rc, Mem m,
            MapLocation[] validMines, int validCount, MapLocation avoid) throws GameActionException {

        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < validCount; i++) {
            MapLocation mine = validMines[i];
            if (mine == null || mine.equals(avoid)) continue;

            int distScore = -here.distanceSquaredTo(mine) / 10;
            int congestionPenalty = 0;

            if (rc.canSenseLocation(mine)) {
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(mine, 25, rc.getTeam());
                congestionPenalty = -nearbyRobots.length * 20;
            }

            int score = distScore + congestionPenalty;
            if (score > bestScore) {
                bestScore = score;
                best = mine;
            }
        }

        return best;
    }

    /**
     * Scan for any robots (cats, enemies) and return the nearest threat.
     */
    public static RobotInfo findNearestThreat(RobotController rc) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots();
        MapLocation here = rc.getLocation();
        RobotInfo nearestThreat = null;
        int nearestDist = Integer.MAX_VALUE;

        for (RobotInfo robot : robots) {
            if (robot.getType() == UnitType.CAT) {
                int d = here.distanceSquaredTo(robot.getLocation());
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestThreat = robot;
                }
            } else if (robot.getTeam() != rc.getTeam()) {
                int d = here.distanceSquaredTo(robot.getLocation());
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestThreat = robot;
                }
            }
        }
        return nearestThreat;
    }

    /**
     * Check if there's cheese at an adjacent location we can pick up.
     */
    public static MapLocation findAdjacentCheese(RobotController rc, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();

        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                MapLocation cheeseLoc = info.getMapLocation();
                if (here.isAdjacentTo(cheeseLoc)) {
                    return cheeseLoc;
                }
            }
        }
        return null;
    }

    /**
     * Find our rat king by sensing nearby robots.
     */
    public static MapLocation findKing(RobotController rc, Mem m) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());

        for (RobotInfo robot : nearby) {
            if (robot.getType() == UnitType.RAT_KING) {
                m.kingLocation = robot.getLocation();
                return m.kingLocation;
            }
        }

        if (m.kingLocation != null && rc.canSenseLocation(m.kingLocation)) {
            RobotInfo atLoc = rc.senseRobotAtLocation(m.kingLocation);
            if (atLoc == null || atLoc.getType() != UnitType.RAT_KING || atLoc.getTeam() != rc.getTeam()) {
                m.kingLocation = null;
            }
        }

        return m.kingLocation;
    }
}
