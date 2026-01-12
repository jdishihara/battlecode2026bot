package examplefuncsplayer.units;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * RatKing logic: spawns baby rats, broadcasts shared info, and evades cats.
 *
 * v3 Updates:
 * - CLUSTERED SYMMETRY SEARCH: Manages global symmetry state for all attackers
 * - All attackers check the same symmetry together (controlled via shared array)
 * - Listens for symmetry elimination reports from attackers
 * - Automatically switches all attackers to next symmetry when current is eliminated
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
    static int safeRoundsCounter = 0;

    // === Enemy king tracking ===
    static MapLocation lastKnownEnemyKingLoc = null;
    static int lastEnemyKingSeenRound = -999;

    // === NEW: Symmetry management ===
    static boolean symmetryInitialized = false;

    /**
     * Main per-turn entrypoint for the Rat King.
     */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int globalCheese = rc.getGlobalCheese();
        int spawnCost = rc.getCurrentRatCost();
        int roundNum = rc.getRoundNum();

        // ===== INITIALIZE SYMMETRY TRACKING (first turn only) =====
        if (!symmetryInitialized) {
            initializeSymmetryTracking(rc, roundNum);
            symmetryInitialized = true;
        }

        // ===== UPDATE SYMMETRY STATE (every turn) =====
        updateSymmetryState(rc, roundNum);

        // ===== PROCESS INCOMING SQUEAKS =====
        processIncomingSqueaks(rc, roundNum);

        // ===== LOOK FOR ENEMY KING DIRECTLY =====
        detectAndBroadcastEnemyKing(rc, roundNum);

        // ===== CAT DETECTION =====
        RobotInfo nearestCat = findNearestCatForKing(rc);
        MapLocation catLocation = null;
        int catDistSq = Integer.MAX_VALUE;

        if (nearestCat != null) {
            catLocation = nearestCat.getLocation();
            catDistSq = myLocation.distanceSquaredTo(catLocation);
        }

        RobotInfo nearestEnemyRat = findNearestEnemyRat(rc);
        MapLocation enemyRatLocation = null;
        int enemyRatDistSq = Integer.MAX_VALUE;

        if (nearestEnemyRat != null) {
            enemyRatLocation = nearestEnemyRat.getLocation();
            enemyRatDistSq = myLocation.distanceSquaredTo(enemyRatLocation);
        }

        // ===== STATE TRANSITIONS =====
        if (nearestCat == null && nearestEnemyRat == null) {
            safeRoundsCounter++;
            if (safeRoundsCounter >= 5) {
                kingState = KingState.SAFE;
            }
        } else {
            safeRoundsCounter = 0;
            if (catDistSq <= ESCAPE_RADIUS * ESCAPE_RADIUS ||
                    enemyRatDistSq <= ESCAPE_RADIUS * ESCAPE_RADIUS) {
                kingState = KingState.ESCAPE;
            } else {
                kingState = KingState.ALERT;
            }
        }

        // ===== BROADCAST TO SHARED ARRAY =====
        rc.writeSharedArray(KING_X_INDEX, myLocation.x);
        rc.writeSharedArray(KING_Y_INDEX, myLocation.y);
        rc.writeSharedArray(CHEESE_STATE_INDEX, globalCheese < LOW_CHEESE_THRESHOLD ? 0 : 1);

        int dangerValue;
        switch (kingState) {
            case SAFE:   dangerValue = 0; break;
            case ALERT:  dangerValue = 1; break;
            case ESCAPE: dangerValue = 2; break;
            default:     dangerValue = 0; break;
        }
        rc.writeSharedArray(DANGER_INDEX, dangerValue);

        if (catLocation != null) {
            rc.writeSharedArray(CAT_X_INDEX, catLocation.x);
            rc.writeSharedArray(CAT_Y_INDEX, catLocation.y);
        }
        if (enemyRatLocation != null) {
            rc.writeSharedArray(ENEMY_RAT_X_INDEX, enemyRatLocation.x);
            rc.writeSharedArray(ENEMY_RAT_Y_INDEX, enemyRatLocation.y);
            int threatLevel = (enemyRatDistSq < 25) ? 2 : 1;
            rc.writeSharedArray(ENEMY_THREAT_LEVEL, threatLevel);
        } else {
            rc.writeSharedArray(ENEMY_THREAT_LEVEL, 0);
        }

        // ===== BEHAVIOR BY STATE =====
        switch (kingState) {
            case SAFE:
                runKingSafeState(rc, globalCheese, spawnCost, turnCount, enemyRatLocation);
                break;
            case ALERT:
                runKingAlertState(rc, globalCheese, spawnCost, enemyRatLocation, catLocation, catDistSq, roundNum);
                break;
            case ESCAPE:
                runKingEscapeState(rc, catLocation, catDistSq, roundNum, globalCheese, enemyRatLocation);
                break;
        }
        kingCounterAttack(rc);

        // Debug string with symmetry info
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int eliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
        String symStr = currentSym == 1 ? "ROT" : currentSym == 2 ? "HOR" : currentSym == 3 ? "VER" : "UNK";
        String elimStr = ((eliminated & 1) != 0 ? "R" : "") +
                         ((eliminated & 2) != 0 ? "H" : "") +
                         ((eliminated & 4) != 0 ? "V" : "");

        rc.setIndicatorString("King " + kingState + " | Cheese: " + globalCheese +
                " | Sym:" + symStr + " Elim:" + elimStr +
                " | EnemyKing: " + (lastKnownEnemyKingLoc != null ? lastKnownEnemyKingLoc : "?"));
    }

    // ==================== SYMMETRY MANAGEMENT (NEW) ====================

    /**
     * Initialize symmetry tracking at game start.
     * Sets all attackers to check rotational symmetry first.
     */
    static void initializeSymmetryTracking(RobotController rc, int roundNum) throws GameActionException {
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        
        // Only initialize if not already set
        if (currentSym == 0) {
            rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, 1); // 1 = rotational (most common)
            rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, 0); // No symmetries eliminated yet
            rc.writeSharedArray(SYMMETRY_SWITCH_ROUND_INDEX, roundNum % 1000);
        }
    }

    /**
     * Update symmetry state based on attacker reports.
     * If current symmetry is eliminated, switch all attackers to next one.
     */
    static void updateSymmetryState(RobotController rc, int roundNum) throws GameActionException {
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int eliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
        
        // Check if current symmetry has been eliminated
        if (currentSym >= 1 && currentSym <= 3) {
            int currentBit = 1 << (currentSym - 1);
            
            if ((eliminated & currentBit) != 0) {
                // Current symmetry is eliminated - switch to next valid one
                int nextSym = findNextSymmetry(eliminated);
                if (nextSym != currentSym) {
                    rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, nextSym);
                    rc.writeSharedArray(SYMMETRY_SWITCH_ROUND_INDEX, roundNum % 1000);
                }
            }
        }
    }

    /**
     * Find the next non-eliminated symmetry.
     * Priority: rotational (1) -> horizontal (2) -> vertical (3)
     */
    static int findNextSymmetry(int eliminated) {
        if ((eliminated & 1) == 0) return 1; // Rotational not eliminated
        if ((eliminated & 2) == 0) return 2; // Horizontal not eliminated
        if ((eliminated & 4) == 0) return 3; // Vertical not eliminated
        return 1; // All eliminated (shouldn't happen), default to rotational
    }

    /**
     * Mark a symmetry as eliminated in the shared array.
     * @param symType 1=rotational, 2=horizontal, 3=vertical
     */
    static void eliminateSymmetry(RobotController rc, int symType) throws GameActionException {
        if (symType < 1 || symType > 3) return;
        int current = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
        int bit = 1 << (symType - 1);
        if ((current & bit) == 0) {
            rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, current | bit);
        }
    }

    // ==================== ENEMY KING DETECTION & BROADCASTING ====================

    /**
     * Process incoming squeaks from baby rats.
     * Handles: enemy king found, symmetry elimination reports
     */
    static void processIncomingSqueaks(RobotController rc, int roundNum) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);
        
        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();

            // === Handle symmetry elimination reports (NEW) ===
            if (content >= SQUEAK_SYMMETRY_ELIMINATED && content < SQUEAK_SYMMETRY_ELIMINATED + 30) {
                int eliminatedSymIndex = (content - SQUEAK_SYMMETRY_ELIMINATED) / 10;
                if (eliminatedSymIndex >= 0 && eliminatedSymIndex < 3) {
                    eliminateSymmetry(rc, eliminatedSymIndex + 1); // Convert to 1-indexed
                }
                continue;
            }

            // === Handle enemy king found ===
            if (content == SQUEAK_ENEMY_KING_FOUND) {
                // Baby rat found the enemy king! Broadcast to shared array
                broadcastEnemyKingLocation(rc, senderLoc, roundNum);
                
                // Set swarm target so all attackers converge
                rc.writeSharedArray(SWARM_TARGET_X, senderLoc.x);
                rc.writeSharedArray(SWARM_TARGET_Y, senderLoc.y);
                rc.writeSharedArray(SWARM_ACTIVE_ROUND, roundNum % 1000);
            }
            else if (content == SQUEAK_ENEMY_ATTACKING_KING) {
                // Our rats are under attack - could increase threat level
            }
        }
    }

    /**
     * Look for enemy king directly with rat king's 360° vision.
     * If found, broadcast location and confirm symmetry.
     */
    static void detectAndBroadcastEnemyKing(RobotController rc, int roundNum) throws GameActionException {
        RobotInfo enemyKing = findEnemyKing(rc);
        
        if (enemyKing != null) {
            MapLocation enemyKingLoc = enemyKing.getLocation();
            lastKnownEnemyKingLoc = enemyKingLoc;
            lastEnemyKingSeenRound = roundNum;
            
            // Broadcast to shared array
            broadcastEnemyKingLocation(rc, enemyKingLoc, roundNum);
            
            // Determine and confirm symmetry type
            int symmetry = determineSymmetry(rc, enemyKingLoc);
            if (symmetry > 0) {
                // Confirm this symmetry and eliminate others
                rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, symmetry);
                
                // Eliminate other symmetries
                int eliminateMask = 0b111 ^ (1 << (symmetry - 1)); // All except confirmed
                int currentEliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
                rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, currentEliminated | eliminateMask);
            }
        }
    }

    /**
     * Broadcast enemy king location to shared array.
     */
    static void broadcastEnemyKingLocation(RobotController rc, MapLocation loc, int roundNum) throws GameActionException {
        rc.writeSharedArray(ENEMY_KING_X_INDEX, loc.x);
        rc.writeSharedArray(ENEMY_KING_Y_INDEX, loc.y);
        rc.writeSharedArray(ENEMY_KING_SEEN_ROUND_INDEX, roundNum % 1000);
        
        lastKnownEnemyKingLoc = loc;
        lastEnemyKingSeenRound = roundNum;
    }

    /**
     * Find enemy rat king within vision range.
     */
    static RobotInfo findEnemyKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(25, rc.getTeam().opponent());
        
        for (RobotInfo robot : nearby) {
            if (robot.getType() == UnitType.RAT_KING) {
                return robot;
            }
        }
        return null;
    }

    /**
     * Determine which symmetry type the map uses based on enemy king location.
     * Returns: 1=rotational, 2=horizontal, 3=vertical, 0=unknown
     */
    static int determineSymmetry(RobotController rc, MapLocation enemyKingLoc) throws GameActionException {
        MapLocation ourKing = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        
        MapLocation rotational = new MapLocation(w - 1 - ourKing.x, h - 1 - ourKing.y);
        MapLocation horizontal = new MapLocation(w - 1 - ourKing.x, ourKing.y);
        MapLocation vertical = new MapLocation(ourKing.x, h - 1 - ourKing.y);
        
        // Check which one is closest (within ~4 tiles for 3x3 king)
        if (enemyKingLoc.distanceSquaredTo(rotational) <= 18) return 1;
        if (enemyKingLoc.distanceSquaredTo(horizontal) <= 18) return 2;
        if (enemyKingLoc.distanceSquaredTo(vertical) <= 18) return 3;
        
        return 0;
    }

    // ==================== EXISTING METHODS (unchanged) ====================

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

    static RobotInfo findNearestEnemyRat(RobotController rc) throws GameActionException {
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

    static int countAlliedBabyRatsNearKing(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radius);
        int count = 0;
        Team myTeam = rc.getTeam();
        for (RobotInfo r : robots) {
            if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }
    
    static void kingCounterAttack(RobotController rc) throws GameActionException {
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

    static void placeDefensiveTraps(RobotController rc, MapLocation enemyRatLocation) throws GameActionException {
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

    static void runKingSafeState(RobotController rc, int globalCheese, int spawnCost, int turnCount,
                                 MapLocation enemyRatLocation) throws GameActionException {
        int minCheeseForSpawn = spawnCost + 100;

        if (turnCount % 10 == 0) {
            placeDefensiveTraps(rc, enemyRatLocation);
        }

        int nearbyBabies = countAlliedBabyRatsNearKing(rc, NEAR_KING_COUNT_RADIUS);
        int desiredNearKingBabies = MIN_NEAR_KING_HAULERS + MIN_NEAR_KING_ATTACKERS;
        boolean lowRats = nearbyBabies < Math.max(desiredNearKingBabies, MIN_DEFENDERS_NEAR_KING);

        boolean enoughCheese = globalCheese >= minCheeseForSpawn && globalCheese >= CRITICAL_CHEESE_THRESHOLD;

        if (lowRats && enoughCheese) {
            trySpawnRat(rc);
            return;
        }

        if (turnCount <= 5 && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        } else if (globalCheese > LOW_CHEESE_THRESHOLD &&
                globalCheese >= minCheeseForSpawn &&
                turnCount % SPAWN_INTERVAL == 0) {
            trySpawnRat(rc);
        }
    }

    static void runKingAlertState(RobotController rc, int globalCheese, int spawnCost,
                                  MapLocation enemyRatLocation, MapLocation catLocation,
                                  int catDistSq, int roundNum) throws GameActionException {
        int alertSpawnThreshold = LOW_CHEESE_THRESHOLD * 2;
        int minCheeseForSpawn = spawnCost + 200;

        placeDefensiveTraps(rc, enemyRatLocation);

        if (catLocation != null) {
            final int MID_RANGE = 15;
            if (catDistSq <= MID_RANGE * MID_RANGE) {
                tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
            }
        }

        if (globalCheese > alertSpawnThreshold && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        }
    }

    static void runKingEscapeState(RobotController rc, MapLocation catLocation, int catDistSq,
                                   int roundNum, int globalCheese, MapLocation enemyRatLocation) throws GameActionException {
        boolean canAttemptEscape = (roundNum - lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (catDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape && catLocation != null) {
            tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);

            if (globalCheese >= 200) {
                placeDefensiveTraps(rc, enemyRatLocation);
            }

            tryKingEscape(rc, catLocation);
            lastEscapeRound = roundNum;

            kingState = KingState.ALERT;
        }
    }

    static void tryPlaceCatTrapDuringEscape(RobotController rc, MapLocation catLocation,
                                            int roundNum, int globalCheese) throws GameActionException {
        boolean inCooperation = !isInBackstabMode(rc);
        if (!inCooperation) {
            return;
        }

        final int CAT_TRAP_COOLDOWN = 7;
        if (roundNum - lastCatTrapRound < CAT_TRAP_COOLDOWN) {
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
            lastCatTrapRound = roundNum;
        }
    }

    static boolean isInBackstabMode(RobotController rc) throws GameActionException {
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

    static boolean tryKingEscape(RobotController rc, MapLocation catLocation) throws GameActionException {
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

    static int countPassableNeighbors(MapLocation loc, MapInfo[] nearby) {
        int count = 0;
        for (Direction d : DIRECTIONS) {
            MapLocation next = loc.add(d);
            if (isLocationPassable(next, nearby)) {
                count++;
            }
        }
        return count;
    }

    static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) {
                return info.isPassable();
            }
        }
        return true;
    }

    static void trySpawnRat(RobotController rc) throws GameActionException {
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