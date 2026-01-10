package examplefuncsplayer;

import battlecode.common.*;

import java.util.Random;

/**
 * RobotPlayer with cheese retrieval strategy.
 * Implements a state machine for baby rats and simple king logic.
 */
public class RobotPlayer {

    // ===== CONSTANTS & CONFIGURATION =====
    static final int INITIAL_RATS = 5; // Reduced to save cheese
    static final int CARRY_LIMIT = 20; // Max cheese per trip
    static final int LOW_CHEESE_THRESHOLD = 1500; // Higher threshold for spawning
    static final int CRITICAL_CHEESE_THRESHOLD = 300; // Stop spawning below this
    static final int CAT_FLEE_RADIUS = 12;
    static final int DELIVERY_RADIUS = 1;
    static final int SPAWN_INTERVAL = 6; // Spawn rarely - very conservative
    static final int MAX_TARGET_PURSUIT_TURNS = 30; // Fast target switching
    static final int STUCK_THRESHOLD = 3; // Get unstuck quickly

    // Shared array indices
    static final int KING_X_INDEX = 0;
    static final int KING_Y_INDEX = 1;
    static final int CHEESE_STATE_INDEX = 2;

    // ===== STATE MACHINE =====
    enum RatState {
        EXPLORE,
        COLLECT,
        RETURN,
        DELIVER,
        FLEE
    }

    // ===== STATIC VARIABLES =====
    static int turnCount = 0;
    static final Random rng = new Random(6147);
    static RatState currentState = RatState.EXPLORE;
    static RatState previousState = RatState.EXPLORE; // For returning from FLEE
    static MapLocation lastLocation = null;
    static MapLocation kingLocation = null;
    static int moveAwayCounter = 0; // For traffic control after delivery
    static int stuckCounter = 0; // Track if we're stuck in one place
    static MapLocation mapCenter = null; // Center of the map
    static MapLocation explorationTarget = null; // Current exploration target
    static boolean hasReachedCenter = false; // Track if we've been to center
    static Direction explorationDirection = null; // Unique exploration direction per rat
    static MapLocation[] recentLocations = new MapLocation[5]; // Track last 5 locations
    static int recentLocationIndex = 0; // Index for circular buffer
    static int turnsAtTarget = 0; // How long we've been pursuing current target

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    /**
     * Main run method called when robot is instantiated.
     */
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive as " + rc.getType());

        while (true) {
            turnCount += 1;

            try {
                if (rc.getType() == UnitType.RAT_KING) {
                    runRatKing(rc);
                } else {
                    runBabyRat(rc);
                }

            } catch (GameActionException e) {
                System.out.println("GameActionException on turn " + turnCount);
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception on turn " + turnCount);
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ===== RAT KING LOGIC =====

    /**
     * Rat King: spawns baby rats and broadcasts global signals.
     */
    static void runRatKing(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int globalCheese = rc.getGlobalCheese();
        int spawnCost = rc.getCurrentRatCost();

        // Broadcast king location and cheese state
        rc.writeSharedArray(KING_X_INDEX, myLocation.x);
        rc.writeSharedArray(KING_Y_INDEX, myLocation.y);
        rc.writeSharedArray(CHEESE_STATE_INDEX, globalCheese < LOW_CHEESE_THRESHOLD ? 0 : 1);

        // Spawning logic - conservative to preserve cheese
        int minCheeseForSpawn = spawnCost + 100; // Keep 100 cheese buffer

        // Always spawn initial rats
        if (turnCount <= 5 && globalCheese >= minCheeseForSpawn) {
            trySpawnRat(rc);
        }
        // Only spawn more rats when we have good reserves AND cheese income
        else if (globalCheese > LOW_CHEESE_THRESHOLD &&
                 globalCheese >= minCheeseForSpawn &&
                 turnCount % SPAWN_INTERVAL == 0) {
            trySpawnRat(rc);
        }
        // Emergency: never spawn if critically low
        else if (globalCheese < CRITICAL_CHEESE_THRESHOLD) {
            // Don't spawn, focus on survival
        }

        rc.setIndicatorString("King | Cheese: " + globalCheese + " | Cost: " + spawnCost);
    }

    /**
     * Try to spawn a rat at any adjacent location.
     */
    static void trySpawnRat(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        // Try all adjacent locations
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

    // ===== BABY RAT LOGIC =====

    /**
     * Baby Rat: runs state machine to collect and deliver cheese.
     */
    static void runBabyRat(RobotController rc) throws GameActionException {
        // Initialize map center and exploration direction on first turn
        if (mapCenter == null) {
            int mapWidth = rc.getMapWidth();
            int mapHeight = rc.getMapHeight();
            mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

            // Assign unique exploration direction based on robot ID
            int dirIndex = rc.getID() % 8;
            explorationDirection = directions[dirIndex];
        }

        // Update king location from shared array
        int kingX = rc.readSharedArray(KING_X_INDEX);
        int kingY = rc.readSharedArray(KING_Y_INDEX);
        kingLocation = new MapLocation(kingX, kingY);

        // Track recent locations for stuck detection
        updateRecentLocations(rc.getLocation());

        // Handle traffic control (forced move away after delivery)
        if (moveAwayCounter > 0) {
            moveAwayFromKing(rc);
            moveAwayCounter--;
            if (moveAwayCounter == 0) {
                // Face away from king before exploring
                if (kingLocation != null) {
                    Direction awayFromKing = kingLocation.directionTo(rc.getLocation());
                    if (awayFromKing != Direction.CENTER && rc.canTurn(awayFromKing)) {
                        rc.turn(awayFromKing);
                    }
                }
                currentState = RatState.EXPLORE;
                stuckCounter = 0; // Reset stuck counter
            }
            return;
        }

        // Check for cats and transition to FLEE if necessary
        if (shouldFlee(rc)) {
            if (currentState != RatState.FLEE) {
                previousState = currentState;
                currentState = RatState.FLEE;
            }
        } else if (currentState == RatState.FLEE) {
            // Cat is gone, return to previous state
            currentState = previousState;
        }

        // State machine execution
        switch (currentState) {
            case EXPLORE:
                runExploreState(rc);
                break;
            case COLLECT:
                runCollectState(rc);
                break;
            case RETURN:
                runReturnState(rc);
                break;
            case DELIVER:
                runDeliverState(rc);
                break;
            case FLEE:
                runFleeState(rc);
                break;
        }

        rc.setIndicatorString("State: " + currentState + " | Cheese: " + rc.getRawCheese());
    }

    // ===== STATE IMPLEMENTATIONS =====

    /**
     * EXPLORE: find cheese by intelligently exploring the map.
     */
    static void runExploreState(RobotController rc) throws GameActionException {
        // Check if already carrying cheese
        if (rc.getRawCheese() > 0) {
            currentState = RatState.RETURN;
            stuckCounter = 0;
            turnsAtTarget = 0;
            return;
        }

        MapLocation currentLocation = rc.getLocation();

        // Look for cheese in vision
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation nearestCheese = findNearestCheese(rc, nearby);

        if (nearestCheese != null) {
            currentState = RatState.COLLECT;
            stuckCounter = 0;
            turnsAtTarget = 0;
            return;
        }

        // Look for cheese mines in vision (cheese spawns near mines)
        MapLocation nearestMine = findNearestCheeseMine(rc, nearby);

        // Advanced stuck detection - check if we're oscillating or trapped
        if (isStuckInLoop()) {
            stuckCounter++;
        } else {
            stuckCounter = 0;
        }

        // If stuck for threshold turns, do aggressive unstuck maneuver
        if (stuckCounter >= STUCK_THRESHOLD) {
            performUnstuckManeuver(rc);
            explorationTarget = null;
            turnsAtTarget = 0;
            stuckCounter = 0;
            return;
        }

        lastLocation = currentLocation;
        turnsAtTarget++;

        // Determine exploration target with better persistence
        MapLocation target = null;

        // Priority 1: Move toward nearby cheese mine
        if (nearestMine != null) {
            target = nearestMine;
            turnsAtTarget = 0; // Reset when we find a new priority target
        }
        // Priority 2: Head toward center initially (cheese mines likely there)
        else if (!hasReachedCenter && mapCenter != null && turnCount < 80) {
            target = mapCenter;
            // Check if we've reached near the center or spent enough time
            if (currentLocation.distanceSquaredTo(mapCenter) <= 35 || turnsAtTarget > 20) {
                hasReachedCenter = true;
                turnsAtTarget = 0;
            }
        }
        // Priority 3: Systematic exploration with persistence
        else {
            // Only pick a new target if we don't have one, reached it, or pursued too long without progress
            if (explorationTarget == null ||
                currentLocation.distanceSquaredTo(explorationTarget) <= 4 ||
                turnsAtTarget > MAX_TARGET_PURSUIT_TURNS) {

                explorationTarget = pickNewExplorationTarget(rc);
                turnsAtTarget = 0;
            }
            target = explorationTarget;
        }

        // Move toward target using vision-based navigation
        if (target != null) {
            intelligentMoveToward(rc, target, nearby);
        } else {
            // Fallback: explore in assigned direction
            exploreInDirection(rc, explorationDirection);
        }
    }

    /**
     * Check if rat is stuck in a loop by analyzing recent positions.
     */
    static boolean isStuckInLoop() {
        if (recentLocations[0] == null) return false;

        // Check if we're oscillating between same few positions
        int uniquePositions = 0;
        for (int i = 0; i < recentLocations.length; i++) {
            if (recentLocations[i] == null) continue;

            boolean isUnique = true;
            for (int j = 0; j < i; j++) {
                if (recentLocations[j] != null && recentLocations[j].equals(recentLocations[i])) {
                    isUnique = false;
                    break;
                }
            }
            if (isUnique) uniquePositions++;
        }

        // If we've only been in 2 or fewer positions in last 5 turns, we're stuck
        return uniquePositions <= 2;
    }

    /**
     * Perform aggressive maneuver to get unstuck.
     */
    static void performUnstuckManeuver(RobotController rc) throws GameActionException {
        // Try turning 90 degrees and moving
        Direction currentDir = rc.getDirection();
        Direction newDir = (rng.nextBoolean()) ?
            currentDir.rotateLeft().rotateLeft() :
            currentDir.rotateRight().rotateRight();

        if (rc.canTurn(newDir)) {
            rc.turn(newDir);
        }

        // Try to move in multiple directions aggressively
        for (int i = 0; i < 3; i++) {
            if (rc.canMoveForward()) {
                rc.moveForward();
                return;
            }
            Direction tryDir = directions[rng.nextInt(8)];
            if (rc.canTurn(tryDir)) {
                rc.turn(tryDir);
            }
        }
    }

    /**
     * Explore by moving in a specific direction (sweep pattern).
     */
    static void exploreInDirection(RobotController rc, Direction dir) throws GameActionException {
        if (rc.getDirection() != dir && rc.canTurn(dir)) {
            rc.turn(dir);
        } else if (rc.canMoveForward()) {
            rc.moveForward();
        } else {
            // Blocked, try to go around obstacle
            Direction left = dir.rotateLeft();
            Direction right = dir.rotateRight();

            if (rc.canTurn(left)) {
                rc.turn(left);
            } else if (rc.canTurn(right)) {
                rc.turn(right);
            }
        }
    }

    /**
     * Update the circular buffer of recent locations.
     */
    static void updateRecentLocations(MapLocation loc) {
        recentLocations[recentLocationIndex] = loc;
        recentLocationIndex = (recentLocationIndex + 1) % recentLocations.length;
    }

    /**
     * Find nearest cheese mine in visible area.
     */
    static MapLocation findNearestCheeseMine(RobotController rc, MapInfo[] nearby) {
        MapLocation myLocation = rc.getLocation();
        MapLocation nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                int dist = myLocation.distanceSquaredTo(info.getMapLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = info.getMapLocation();
                }
            }
        }

        return nearest;
    }

    /**
     * Pick a new exploration target to ensure thorough map coverage.
     */
    static MapLocation pickNewExplorationTarget(RobotController rc) {
        MapLocation myLocation = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();

        // Use robot ID to assign different exploration strategies
        int strategy = (rc.getID() / 2) % 4;

        int targetX, targetY;

        switch (strategy) {
            case 0: // Explore corners
                boolean topHalf = myLocation.y > mapHeight / 2;
                boolean leftHalf = myLocation.x < mapWidth / 2;

                if (topHalf && leftHalf) {
                    targetX = mapWidth - 5;
                    targetY = mapHeight - 5;
                } else if (topHalf) {
                    targetX = 5;
                    targetY = mapHeight - 5;
                } else if (leftHalf) {
                    targetX = mapWidth - 5;
                    targetY = 5;
                } else {
                    targetX = 5;
                    targetY = 5;
                }
                break;

            case 1: // Explore horizontal sweep
                targetX = (myLocation.x > mapWidth / 2) ? 5 : mapWidth - 5;
                targetY = myLocation.y + (rng.nextBoolean() ? 10 : -10);
                targetY = Math.max(5, Math.min(mapHeight - 5, targetY));
                break;

            case 2: // Explore vertical sweep
                targetX = myLocation.x + (rng.nextBoolean() ? 10 : -10);
                targetX = Math.max(5, Math.min(mapWidth - 5, targetX));
                targetY = (myLocation.y > mapHeight / 2) ? 5 : mapHeight - 5;
                break;

            case 3: // Diagonal exploration
            default:
                // Move toward unexplored diagonal quadrants
                if (myLocation.x < mapWidth / 2 && myLocation.y < mapHeight / 2) {
                    targetX = mapWidth * 3 / 4;
                    targetY = mapHeight * 3 / 4;
                } else if (myLocation.x >= mapWidth / 2 && myLocation.y < mapHeight / 2) {
                    targetX = mapWidth / 4;
                    targetY = mapHeight * 3 / 4;
                } else if (myLocation.x < mapWidth / 2) {
                    targetX = mapWidth * 3 / 4;
                    targetY = mapHeight / 4;
                } else {
                    targetX = mapWidth / 4;
                    targetY = mapHeight / 4;
                }
                break;
        }

        return new MapLocation(targetX, targetY);
    }

    /**
     * Intelligently move toward target using vision to avoid obstacles.
     */
    static void intelligentMoveToward(RobotController rc, MapLocation target, MapInfo[] nearby) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        Direction desiredDir = myLocation.directionTo(target);

        // Find best passable direction in vision
        Direction bestDir = findBestPassableDirection(rc, desiredDir, nearby);

        if (bestDir != null) {
            // Turn toward best direction
            if (rc.getDirection() != bestDir && rc.canTurn(bestDir)) {
                rc.turn(bestDir);
            }
            // Move forward
            else if (rc.canMoveForward()) {
                rc.moveForward();
            }
        } else {
            // No good direction found, use standard movement
            moveToward(rc, target);
        }
    }

    /**
     * Find the best passable direction toward target using vision.
     */
    static Direction findBestPassableDirection(RobotController rc, Direction desired, MapInfo[] nearby) {
        MapLocation myLocation = rc.getLocation();

        // Try directions in order of preference
        Direction[] tryDirs = {
            desired,
            desired.rotateLeft(),
            desired.rotateRight(),
            desired.rotateLeft().rotateLeft(),
            desired.rotateRight().rotateRight()
        };

        for (Direction dir : tryDirs) {
            MapLocation nextLoc = myLocation.add(dir);

            // Check if this location is passable in our vision
            if (isLocationPassable(nextLoc, nearby)) {
                return dir;
            }
        }

        return null;
    }

    /**
     * Check if a location is passable based on visible map info.
     */
    static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) {
                return info.isPassable();
            }
        }
        // If not in vision, assume passable
        return true;
    }

    /**
     * COLLECT: move toward and pick up cheese.
     */
    static void runCollectState(RobotController rc) throws GameActionException {
        // Check if carrying enough cheese
        if (rc.getRawCheese() >= CARRY_LIMIT) {
            currentState = RatState.RETURN;
            stuckCounter = 0;
            return;
        }

        // Look for cheese
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation nearestCheese = findNearestCheese(rc, nearby);

        if (nearestCheese == null) {
            // No cheese visible, go back to exploring
            currentState = RatState.EXPLORE;
            stuckCounter = 0;
            return;
        }

        // Try to pick up cheese at the target location
        if (rc.canPickUpCheese(nearestCheese)) {
            rc.pickUpCheese(nearestCheese);
        }

        // Move toward cheese using intelligent navigation
        intelligentMoveToward(rc, nearestCheese, nearby);
    }

    /**
     * RETURN: bring cheese back to king.
     */
    static void runReturnState(RobotController rc) throws GameActionException {
        if (kingLocation == null) {
            currentState = RatState.EXPLORE;
            stuckCounter = 0;
            return;
        }

        MapLocation myLocation = rc.getLocation();

        // Check if adjacent to king or within delivery range
        int distToKing = myLocation.distanceSquaredTo(kingLocation);
        if (distToKing <= 10) { // Within 3 tiles (3x3 king occupies some space)
            currentState = RatState.DELIVER;
            stuckCounter = 0;
            return;
        }

        // Move toward king using intelligent navigation
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        intelligentMoveToward(rc, kingLocation, nearby);
    }

    /**
     * DELIVER: transfer cheese to king and move away.
     */
    static void runDeliverState(RobotController rc) throws GameActionException {
        if (kingLocation == null) {
            currentState = RatState.EXPLORE;
            stuckCounter = 0;
            return;
        }

        int rawCheese = rc.getRawCheese();

        // If no cheese to deliver, go back to exploring
        if (rawCheese == 0) {
            currentState = RatState.EXPLORE;
            stuckCounter = 0;
            return;
        }

        // Try to transfer all raw cheese to king
        if (rc.canTransferCheese(kingLocation, rawCheese)) {
            rc.transferCheese(kingLocation, rawCheese);

            // After delivery, force move away for traffic control
            moveAwayCounter = 2;
            return;
        }

        // If can't deliver, try to move closer to king
        moveToward(rc, kingLocation);
    }

    /**
     * FLEE: run away from cats.
     */
    static void runFleeState(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyCats = rc.senseNearbyRobots(CAT_FLEE_RADIUS, rc.getTeam().opponent());

        if (nearbyCats.length == 0) {
            // No cats, return to previous state
            currentState = previousState;
            return;
        }

        // Find nearest cat
        RobotInfo nearestCat = nearbyCats[0];
        MapLocation myLocation = rc.getLocation();

        // Calculate direction away from cat
        Direction awayFromCat = myLocation.directionTo(nearestCat.getLocation()).opposite();

        // Try to move away
        if (rc.canTurn(awayFromCat)) {
            rc.turn(awayFromCat);
        }

        if (rc.canMoveForward()) {
            rc.moveForward();
        }
    }

    // ===== MOVEMENT HELPERS =====

    /**
     * Bytecode-cheap movement toward target using greedy + bug hybrid.
     */
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        Direction desiredDir = myLocation.directionTo(target);

        // Try directions in order: desired, 45° left, 45° right, 90° left, 90° right
        Direction[] tryDirs = {
            desiredDir,
            desiredDir.rotateLeft(),
            desiredDir.rotateRight(),
            desiredDir.rotateLeft().rotateLeft(),
            desiredDir.rotateRight().rotateRight()
        };

        for (Direction dir : tryDirs) {
            if (tryMoveInDirection(rc, dir)) {
                return;
            }
        }
    }

    /**
     * Attempt to move in a specific direction.
     * Returns true if successful.
     */
    static boolean tryMoveInDirection(RobotController rc, Direction dir) throws GameActionException {
        // First turn to face the direction
        if (rc.getDirection() != dir) {
            if (rc.canTurn(dir)) {
                rc.turn(dir);
                return true; // Spent turn turning
            }
            return false;
        }

        // Then try to move forward
        if (rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }

        return false;
    }

    /**
     * Move away from king for traffic control.
     */
    static void moveAwayFromKing(RobotController rc) throws GameActionException {
        if (kingLocation == null) return;

        MapLocation myLocation = rc.getLocation();
        Direction awayDir = kingLocation.directionTo(myLocation);

        if (awayDir == Direction.CENTER) {
            // Pick a random direction if we're on the king
            awayDir = directions[rng.nextInt(8)];
        }

        tryMoveInDirection(rc, awayDir);
    }

    // ===== UTILITY METHODS =====

    /**
     * Find the nearest cheese from visible map tiles.
     */
    static MapLocation findNearestCheese(RobotController rc, MapInfo[] nearby) {
        MapLocation myLocation = rc.getLocation();
        MapLocation nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                int dist = myLocation.distanceSquaredTo(info.getMapLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = info.getMapLocation();
                }
            }
        }

        return nearest;
    }

    /**
     * Check if we should flee from cats.
     */
    static boolean shouldFlee(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyCats = rc.senseNearbyRobots(CAT_FLEE_RADIUS, rc.getTeam().opponent());
        return nearbyCats.length > 0;
    }
}
