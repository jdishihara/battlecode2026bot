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
 * 
 * v2 Updates:
 * - Listens for SQUEAK_ENEMY_KING_FOUND from baby rats
 * - Broadcasts enemy king location to shared array
 * - Determines and broadcasts map symmetry type
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

    // === NEW: Enemy king tracking ===
    static MapLocation lastKnownEnemyKingLoc = null;
    static int lastEnemyKingSeenRound = -999;

    /**
     * Main per-turn entrypoint for the Rat King.
     */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int globalCheese = rc.getGlobalCheese();
        int spawnCost = rc.getCurrentRatCost();
        int roundNum = rc.getRoundNum();

        // ===== PROCESS INCOMING SQUEAKS (NEW) =====
        processIncomingSqueaks(rc, roundNum);

        // ===== LOOK FOR ENEMY KING DIRECTLY (NEW) =====
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


        rc.setIndicatorString("King " + kingState + " | Cheese: " + globalCheese +
                " | CatDist: " + (catDistSq == Integer.MAX_VALUE ? "none" : Math.sqrt(catDistSq)) +
                " | EnemyKing: " + (lastKnownEnemyKingLoc != null ? lastKnownEnemyKingLoc : "?"));
    }

    // ==================== NEW: ENEMY KING DETECTION & BROADCASTING ====================

    /**
     * Process incoming squeaks from baby rats.
     * When a baby rat finds the enemy king, broadcast the location to all rats.
     */
    static void processIncomingSqueaks(RobotController rc, int roundNum) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);
        
        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();
            int senderRound = msg.getRound();
            
            // Only process recent squeaks (within last 5 rounds)
            if (roundNum - senderRound > 5) continue;

            if (content == SQUEAK_ENEMY_KING_FOUND) {
                // Baby rat found the enemy king! Broadcast to shared array
                broadcastEnemyKingLocation(rc, senderLoc, roundNum);
                
                // Also set swarm target
                rc.writeSharedArray(SWARM_TARGET_X, senderLoc.x);
                rc.writeSharedArray(SWARM_TARGET_Y, senderLoc.y);
                rc.writeSharedArray(SWARM_ACTIVE_ROUND, roundNum % 1000);
            }
            else if (content == SQUEAK_ENEMY_ATTACKING_KING) {
                // Our rats are under attack near enemy king area
                // Could increase threat level or redirect defenders
            }
        }
    }

    /**
     * Look for enemy king directly with rat king's 360° vision.
     * If found, broadcast location and determine symmetry.
     */
    static void detectAndBroadcastEnemyKing(RobotController rc, int roundNum) throws GameActionException {
        RobotInfo enemyKing = findEnemyKing(rc);
        
        if (enemyKing != null) {
            MapLocation enemyKingLoc = enemyKing.getLocation();
            lastKnownEnemyKingLoc = enemyKingLoc;
            lastEnemyKingSeenRound = roundNum;
            
            // Broadcast to shared array
            broadcastEnemyKingLocation(rc, enemyKingLoc, roundNum);
            
            // Determine and broadcast symmetry type
            int symmetry = determineSymmetry(rc, enemyKingLoc);
            if (symmetry > 0) {
                rc.writeSharedArray(CONFIRMED_SYMMETRY_INDEX, symmetry);
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
        
        // Update local tracking
        lastKnownEnemyKingLoc = loc;
        lastEnemyKingSeenRound = roundNum;
    }

    /**
     * Find enemy rat king within vision range.
     * Rat kings have 360° vision with 25 unit radius.
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
        
        // Calculate expected positions for each symmetry
        MapLocation rotational = new MapLocation(w - 1 - ourKing.x, h - 1 - ourKing.y);
        MapLocation horizontal = new MapLocation(w - 1 - ourKing.x, ourKing.y);
        MapLocation vertical = new MapLocation(ourKing.x, h - 1 - ourKing.y);
        
        // Check which one is closest (within ~3 tiles for 3x3 king)
        if (enemyKingLoc.distanceSquaredTo(rotational) <= 18) return 1;  // Rotational
        if (enemyKingLoc.distanceSquaredTo(horizontal) <= 18) return 2;  // Horizontal
        if (enemyKingLoc.distanceSquaredTo(vertical) <= 18) return 3;    // Vertical
        
        return 0; // Unknown
    }

    // ==================== EXISTING METHODS (unchanged) ====================

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
    
    static void kingCounterAttack(RobotController rc) throws GameActionException {
        // Rat king can attack adjacent enemies (within distance 8 from center per specs)
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

    /**
     * Place defensive rat traps around the king.
     */
    static void placeDefensiveTraps(RobotController rc, MapLocation enemyRatLocation) throws GameActionException {
        if (rc.getGlobalCheese() < 150) return; // Need buffer for king consumption + trap cost

        MapLocation kingLoc = rc.getLocation();

        // If we see an enemy rat, place traps between it and the king.
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
                    return; // One trap per turn
                }
            }
        }

        // Fallback: place traps in a defensive ring around king
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
                return; // One trap per turn
            }
        }
    }

    /**
     * King SAFE state: normal spawning behavior.
     */
    static void runKingSafeState(RobotController rc, int globalCheese, int spawnCost, int turnCount,
                                 MapLocation enemyRatLocation) throws GameActionException {
        int minCheeseForSpawn = spawnCost + 100;

        if (turnCount % 10 == 0) { // Try placing traps periodically
            placeDefensiveTraps(rc, enemyRatLocation);
        }

        // Condition (1): low near-king population
        int nearbyBabies = countAlliedBabyRatsNearKing(rc, NEAR_KING_COUNT_RADIUS);
        int desiredNearKingBabies = MIN_NEAR_KING_HAULERS + MIN_NEAR_KING_ATTACKERS;
        boolean lowRats = nearbyBabies < Math.max(desiredNearKingBabies, MIN_DEFENDERS_NEAR_KING);

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
    static void runKingAlertState(RobotController rc, int globalCheese, int spawnCost,
                                  MapLocation enemyRatLocation, MapLocation catLocation,
                                  int catDistSq, int roundNum) throws GameActionException {
        int alertSpawnThreshold = LOW_CHEESE_THRESHOLD * 2;
        int minCheeseForSpawn = spawnCost + 200;

        placeDefensiveTraps(rc, enemyRatLocation);

        // Place cat traps earlier in ALERT if a cat is within mid-range.
        if (catLocation != null) {
            final int MID_RANGE = 15;
            if (catDistSq <= MID_RANGE * MID_RANGE) {
                tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);
            }
        }

        if (globalCheese > alertSpawnThreshold && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        }
        // Do not move in ALERT
    }

    /**
     * King ESCAPE state: place cat trap if in cooperation, then attempt to move away from cat.
     */
    static void runKingEscapeState(RobotController rc, MapLocation catLocation, int catDistSq,
                                   int roundNum, int globalCheese, MapLocation enemyRatLocation) throws GameActionException {
        // Do NOT spawn while escaping

        boolean canAttemptEscape = (roundNum - lastEscapeRound >= ESCAPE_COOLDOWN) ||
                (catDistSq <= PANIC_RADIUS * PANIC_RADIUS);

        if (canAttemptEscape && catLocation != null) {
            // ===== ATTEMPT CAT TRAP PLACEMENT =====
            tryPlaceCatTrapDuringEscape(rc, catLocation, roundNum, globalCheese);

            //===== PLACE RAT TRAPS FOR ENEMY RATS ====
            // Skip rat traps if cheese is low during cat escape; save actions to move/turn.
            if (globalCheese >= 200) {
                placeDefensiveTraps(rc, enemyRatLocation);
            }

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
    }

    /**
     * Check if we're in backstabbing mode.
     */
    static boolean isInBackstabMode(RobotController rc) throws GameActionException {
        // Option 1: Use a shared array flag if you track this
        // int backstabFlag = rc.readSharedArray(BACKSTAB_FLAG_INDEX);
        // return backstabFlag == 1;

        // Option 2: Simple heuristic - check if we can sense damaged enemy rats nearby
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
     */
    static boolean tryKingEscape(RobotController rc, MapLocation catLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

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
            // penalty for dead-ends (few passable neighbors)
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
        return true; // unseen = assume passable
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
