package examplefuncsplayer.units;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * RatKing logic: spawns baby rats, broadcasts shared info, and evades cats.
 *
 * Assumes you have:
 * - examplefuncsplayer.config.Constants (all tuning constants + DIRECTIONS)
 * - examplefuncsplayer.config.Shared (shared array indices)
 */
public class RatKing {

    // ===== RAT KING STATE MACHINE =====
    enum KingState {
        SAFE,
        ALERT,
        ESCAPE
    }

    // ===== KING RUNTIME STATE =====
    static KingState kingState = KingState.SAFE;
    static int lastEscapeRound = -999;
    static int lastCatTrapRound = -999;
    static int safeRoundsCounter = 0; // hysteresis: require N safe rounds to return to SAFE

    /**
     * Main per-turn entrypoint for the Rat King.
     */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int globalCheese = rc.getGlobalCheese();
        int spawnCost = rc.getCurrentRatCost();
        int roundNum = rc.getRoundNum();

        // ===== CAT DETECTION =====
        RobotInfo nearestCat = findNearestCatForKing(rc);
        MapLocation catLocation = null;
        int catDistSq = Integer.MAX_VALUE;

        if (nearestCat != null) {
            catLocation = nearestCat.getLocation();
            catDistSq = myLocation.distanceSquaredTo(catLocation);
        }

        // ===== STATE TRANSITIONS =====
        if (nearestCat == null) {
            safeRoundsCounter++;
            if (safeRoundsCounter >= 5) {
                kingState = KingState.SAFE;
            }
        } else {
            safeRoundsCounter = 0;
            if (catDistSq <= ESCAPE_RADIUS * ESCAPE_RADIUS) {
                kingState = KingState.ESCAPE;
            } else {
                kingState = KingState.ALERT;
            }
        }

        // ===== BROADCAST TO SHARED ARRAY =====
        rc.writeSharedArray(KING_X_INDEX, myLocation.x);
        rc.writeSharedArray(KING_Y_INDEX, myLocation.y);
        rc.writeSharedArray(CHEESE_STATE_INDEX, globalCheese < LOW_CHEESE_THRESHOLD ? 0 : 1);

        // danger: 0=SAFE, 1=ALERT, 2=ESCAPE
        int dangerValue;
        switch (kingState) {
            case SAFE:   dangerValue = 0; break;
            case ALERT:  dangerValue = 1; break;
            case ESCAPE: dangerValue = 2; break;
            default:     dangerValue = 0; break;
        }
        rc.writeSharedArray(DANGER_INDEX, dangerValue);

        // cat location
        if (catLocation != null) {
            rc.writeSharedArray(CAT_X_INDEX, catLocation.x);
            rc.writeSharedArray(CAT_Y_INDEX, catLocation.y);
        }

        // ===== BEHAVIOR BY STATE =====
        switch (kingState) {
            case SAFE:
                runKingSafeState(rc, globalCheese, spawnCost, turnCount);
                break;
            case ALERT:
                runKingAlertState(rc, globalCheese, spawnCost);
                break;
            case ESCAPE:
                runKingEscapeState(rc, catLocation, catDistSq, roundNum, globalCheese);
                break;
        }

        rc.setIndicatorString("King " + kingState + " | Cheese: " + globalCheese +
                " | CatDist: " + (catDistSq == Integer.MAX_VALUE ? "none" : Math.sqrt(catDistSq)));
    }

    /**
     * Find the nearest cat within ALERT_RADIUS for the king.
     */
    static RobotInfo findNearestCatForKing(RobotController rc) throws GameActionException {
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

    /**
     * Count allied baby rats near the king within a given radius.
     */
    static int countAlliedBabyRatsNearKing(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radius);
        int count = 0;
        Team myTeam = rc.getTeam();
        for (RobotInfo r : robots) {
            if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }

    /**
     * King SAFE state: normal spawning behavior.
     */
    static void runKingSafeState(RobotController rc, int globalCheese, int spawnCost, int turnCount) throws GameActionException {
        int minCheeseForSpawn = spawnCost + 100;

        // Condition (1): low near-king population
        int nearbyBabies = countAlliedBabyRatsNearKing(rc, NEAR_KING_COUNT_RADIUS);
        int desiredNearKingBabies = MIN_NEAR_KING_HAULERS + MIN_NEAR_KING_ATTACKERS;
        boolean lowRats = nearbyBabies < desiredNearKingBabies;

        // Condition (2): enough cheese
        boolean enoughCheese = globalCheese >= minCheeseForSpawn && globalCheese >= CRITICAL_CHEESE_THRESHOLD;

        // Spawn more if BOTH conditions are true
        if (lowRats && enoughCheese) {
            trySpawnRat(rc);
            return;
        }

        // Conservative fallback
        if (turnCount <= 5 && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        } else if (globalCheese > LOW_CHEESE_THRESHOLD &&
                globalCheese >= minCheeseForSpawn &&
                turnCount % SPAWN_INTERVAL == 0) {
            trySpawnRat(rc);
        }
    }

    /**
     * King ALERT state: limit spawning, do not move.
     */
    static void runKingAlertState(RobotController rc, int globalCheese, int spawnCost) throws GameActionException {
        int alertSpawnThreshold = LOW_CHEESE_THRESHOLD * 2;
        int minCheeseForSpawn = spawnCost + 200;

        if (globalCheese > alertSpawnThreshold && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        }
        // Do not move in ALERT
    }

    /**
     * King ESCAPE state: place cat trap if in cooperation, then attempt to move away from cat.
     */
    static void runKingEscapeState(RobotController rc, MapLocation catLocation, int catDistSq,
                                   int roundNum, int globalCheese) throws GameActionException {
        // Do NOT spawn while escaping

        boolean canAttemptEscape = (roundNum - lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (catDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape && catLocation != null) {
            // ===== ATTEMPT CAT TRAP PLACEMENT =====
            tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);

            // ===== THEN MOVE AWAY =====
            tryKingEscape(rc, catLocation);
            lastEscapeRound = roundNum;

            // After attempting move, transition to ALERT
            kingState = KingState.ALERT;
        }
    }

    /**
     * Attempt to place one cat trap during escape.
     * Only works in cooperation mode and respects cooldown + cheese constraints.
     */
    static void tryPlaceCatTrapDuringEscape(RobotController rc, MapLocation catLocation,
                                            int roundNum, int globalCheese) throws GameActionException {
        // Check if we're in cooperation (no backstabbing yet)
        // Assuming cooperation unless rc.isBackstabbed() exists, or use a shared array flag
        boolean inCooperation = !isInBackstabMode(rc);
        if (!inCooperation) {
            return; // Only place cat traps during cooperation
        }

        // Check cooldown (5-10 rounds between trap placements)
        final int CAT_TRAP_COOLDOWN = 7; // tune as needed
        if (roundNum - lastCatTrapRound < CAT_TRAP_COOLDOWN) {
            return;
        }

        // Check cheese safety
        final int CAT_TRAP_COST = 10; // from spec
        final int SAFE_CHEESE_BUFFER = 50; // safety margin
        if (globalCheese < CAT_TRAP_COST + SAFE_CHEESE_BUFFER) {
            return; // Don't risk king starvation
        }

        // ===== FIND BEST TRAP LOCATION =====
        MapLocation kingLoc = rc.getLocation();
        MapLocation bestTrapLoc = null;

        // Priority 1: Tile between cat and king
        Direction catToKing = catLocation.directionTo(kingLoc);
        if (catToKing != Direction.CENTER) {
            MapLocation betweenLoc = catLocation.add(catToKing);
            if (rc.canPlaceCatTrap(betweenLoc)) {
                bestTrapLoc = betweenLoc;
            }
        }

        // Priority 2: King's current tile (if priority 1 failed)
        if (bestTrapLoc == null && rc.canPlaceCatTrap(kingLoc)) {
            bestTrapLoc = kingLoc;
        }

        // Priority 3: Adjacent tiles (check up to 8 directions)
        if (bestTrapLoc == null) {
            Direction[] adjacentDirs = {
                    Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
                    Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
            };

            // Try tiles in direction away from cat first
            Direction awayFromCat = catLocation.directionTo(kingLoc);
            for (Direction dir : adjacentDirs) {
                MapLocation testLoc = kingLoc.add(dir);
                if (rc.canPlaceCatTrap(testLoc)) {
                    bestTrapLoc = testLoc;
                    // Prefer tiles that are more aligned with escape direction
                    if (dir == awayFromCat || dir == awayFromCat.rotateLeft() || dir == awayFromCat.rotateRight()) {
                        break; // Found good trap location
                    }
                }
            }
        }

        // ===== PLACE THE TRAP =====
        if (bestTrapLoc != null) {
            rc.placeCatTrap(bestTrapLoc);
            lastCatTrapRound = roundNum;
        }
        // If placement fails, just continue with escape (no spamming)
    }

    /**
     * Check if we're in backstabbing mode.
     * If you have a shared array flag for this, use it here.
     * Otherwise, check for enemy rat attacks or other indicators.
     */
    static boolean isInBackstabMode(RobotController rc) throws GameActionException {
        // Option 1: Use a shared array flag if you track this
        // int backstabFlag = rc.readSharedArray(BACKSTAB_FLAG_INDEX);
        // return backstabFlag == 1;

        // Option 2: Simple heuristic - check if we can sense damaged enemy rats nearby
        // (This is imperfect but lightweight)
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getTeam() == enemyTeam &&
                    (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)) {
                // If we see an enemy rat with less than full health, assume backstabbing started
                if (robot.getType() == UnitType.BABY_RAT && robot.getHealth() < 100) {
                    return true;
                }
                if (robot.getType() == UnitType.RAT_KING && robot.getHealth() < 500) {
                    return true;
                }
            }
        }

        // Default to cooperation mode
        return false;
    }

    /**
     * Attempt to move the king away from the cat using best escape direction.
     * Returns true if movement succeeded.
     */
    static boolean tryKingEscape(RobotController rc, MapLocation catLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();

        // Direction away from cat
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

            // prefer moving away
            int score = 3 * (newDistSq - currentDistSq);

            // penalty for edges (king is 3x3)
            if (newLoc.x <= 2 || newLoc.x >= mapWidth - 3 ||
                    newLoc.y <= 2 || newLoc.y >= mapHeight - 3) {
                score -= 50;
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
     * Try to spawn a rat at any adjacent location.
     */
    static void trySpawnRat(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();

        // Try all locations in a 5x5 around king center; canBuildRat will enforce legality
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