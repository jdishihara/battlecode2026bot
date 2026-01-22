package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;

/**
 * Sensing utilities for hauler rats.
 * Includes mine discovery, caching, and shared array communication.
 */
public class HaulerSensing {

    // Shared array indices for mine locations - MUST MATCH HaulerMining.java
    // Slots 10-25 for up to 8 mines (2 slots each: x+1, y+1)
    // Value of 0 means no mine stored at that slot (we use 1-indexed coords)
    public static final int MINE_ARRAY_START = 10;  // Same as HaulerMining.MINE_START_SLOT
    public static final int MAX_SHARED_MINES = 8;   // Same as HaulerMining.MAX_KNOWN_MINES

    // Maximum distance squared to consider a mine for assignment
    public static final int MAX_ASSIGNMENT_DISTANCE_SQ = 900;  // ~30 tiles

    // If more than this many rats are near a mine, it's congested
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
     * Find the nearest cheese mine in sensed map info AND check known mines from memory.
     * Also caches any newly discovered mines.
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
        for (int i = 0; i < MAX_SHARED_MINES; i++) {
            int idx = MINE_ARRAY_START + i * 2;
            int x = rc.readSharedArray(idx);
            int y = rc.readSharedArray(idx + 1);
            if (x > 0 && y > 0) { // Valid mine location (we use 1-indexed to distinguish from "empty")
                MapLocation mineLoc = new MapLocation(x - 1, y - 1);
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
     * Original method signature for backward compatibility.
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
     * Scan nearby and cache any mines found. Call this every turn during exploration.
     * Returns true if a new mine was discovered.
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
        
        // Also read from shared array
        for (int i = 0; i < MAX_SHARED_MINES; i++) {
            int idx = MINE_ARRAY_START + i * 2;
            int x = rc.readSharedArray(idx);
            int y = rc.readSharedArray(idx + 1);
            if (x > 0 && y > 0) {
                MapLocation mineLoc = new MapLocation(x - 1, y - 1);
                if (m.cacheMineLoc(mineLoc)) {
                    foundNew = true;
                }
            }
        }
        
        return foundNew;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IMPROVED MINE ASSIGNMENT - Distributes rats across multiple mines
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get an assigned mine for this rat.
     * IMPROVED: Actually distributes rats across known mines instead of everyone
     * going to the nearest one.
     *
     * Assignment strategy:
     * 1. Filter mines by distance (ignore mines too far from king)
     * 2. Assign rat to a mine based on (robotID % numMines)
     * 3. If assigned mine is congested, try alternatives
     */
    public static MapLocation getOrAssignMine(RobotController rc, Mem m) throws GameActionException {
        // Refresh mine knowledge from shared array
        refreshMinesFromSharedArray(rc, m);

        // If no mines known, return null (will trigger exploration)
        if (m.knownMineCount == 0) {
            return null;
        }

        // Build list of valid mines (within distance limit)
        MapLocation[] validMines = new MapLocation[m.knownMineCount];
        int validCount = 0;

        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation mine = m.knownMines[i];
            if (mine == null) continue;

            // Check distance from king if known
            if (m.kingLocation != null) {
                int distSq = m.kingLocation.distanceSquaredTo(mine);
                if (distSq > MAX_ASSIGNMENT_DISTANCE_SQ) {
                    continue;  // Mine too far, skip it
                }
            }

            validMines[validCount++] = mine;
        }

        // If no valid mines, return nearest known mine as fallback
        if (validCount == 0) {
            return findNearestKnownMine(rc, m);
        }

        // Assign based on robot ID to distribute evenly
        int assignmentIndex = rc.getID() % validCount;
        MapLocation assigned = validMines[assignmentIndex];

        // Check if assigned mine is congested
        if (isMineCongestedNearby(rc, assigned)) {
            // Try to find a less congested alternative
            MapLocation alternative = findLessCongestedMine(rc, m, validMines, validCount, assigned);
            if (alternative != null) {
                assigned = alternative;
            }
        }

        m.assignedMine = assigned;
        return assigned;
    }

    /**
     * Refresh known mines from the shared array.
     */
    private static void refreshMinesFromSharedArray(RobotController rc, Mem m) throws GameActionException {
        for (int i = 0; i < MAX_SHARED_MINES; i++) {
            int idx = MINE_ARRAY_START + i * 2;
            int x = rc.readSharedArray(idx);
            int y = rc.readSharedArray(idx + 1);
            if (x > 0 && y > 0) {
                MapLocation mineLoc = new MapLocation(x - 1, y - 1);
                m.cacheMineLoc(mineLoc);
            }
        }
    }

    /**
     * Find the nearest known mine (fallback when no valid mines in range).
     */
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

    /**
     * Check if a mine location appears congested based on nearby allied rats.
     */
    private static boolean isMineCongestedNearby(RobotController rc, MapLocation mine) throws GameActionException {
        if (!rc.canSenseLocation(mine)) {
            return false;  // Can't tell, assume not congested
        }

        // Count allied rats near the mine
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(mine, 25, rc.getTeam());  // 5-tile radius
        int haulerCount = 0;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.BABY_RAT) {
                haulerCount++;
            }
        }

        return haulerCount >= CONGESTION_THRESHOLD;
    }

    /**
     * Find a less congested mine from the valid options.
     */
    private static MapLocation findLessCongestedMine(RobotController rc, Mem m,
            MapLocation[] validMines, int validCount, MapLocation avoid) throws GameActionException {

        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < validCount; i++) {
            MapLocation mine = validMines[i];
            if (mine == null || mine.equals(avoid)) continue;

            // Score based on distance and estimated congestion
            int distScore = -here.distanceSquaredTo(mine) / 10;  // Closer is better
            int congestionPenalty = 0;

            if (rc.canSenseLocation(mine)) {
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(mine, 25, rc.getTeam());
                congestionPenalty = -nearbyRobots.length * 20;  // Fewer rats = better
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
     * Check if we should explore for more mines.
     * Returns true if we don't know enough mines yet.
     */
    public static boolean needMoreMines(Mem m, int targetCount) {
        return m.knownMineCount < targetCount;
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
            // Cats are always threats
            if (robot.getType() == UnitType.CAT) {
                int d = here.distanceSquaredTo(robot.getLocation());
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestThreat = robot;
                }
            }
            // Enemy rats could be threats in backstab mode
            else if (robot.getTeam() != rc.getTeam()) {
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
                // Check if adjacent (distance squared <= 2 means adjacent including diagonals)
                if (here.isAdjacentTo(cheeseLoc)) {
                    return cheeseLoc;
                }
            }
        }
        return null;
    }

    /**
     * Find our rat king by sensing nearby robots.
     * Updates m.kingLocation if found.
     */
    public static MapLocation findKing(RobotController rc, Mem m) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        
        for (RobotInfo robot : nearby) {
            if (robot.getType() == UnitType.RAT_KING) {
                m.kingLocation = robot.getLocation();
                return m.kingLocation;
            }
        }
        
        // If we can't see king but have a stored location, verify it's still valid
        if (m.kingLocation != null && rc.canSenseLocation(m.kingLocation)) {
            RobotInfo atLoc = rc.senseRobotAtLocation(m.kingLocation);
            if (atLoc == null || atLoc.getType() != UnitType.RAT_KING || atLoc.getTeam() != rc.getTeam()) {
                m.kingLocation = null; // King moved or died
            }
        }
        
        return m.kingLocation;
    }
}