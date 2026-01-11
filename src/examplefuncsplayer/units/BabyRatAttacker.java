package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * ATTACKER baby rat: seeks enemy king, coordinates attacks via squeaking,
 * places offensive traps, and defends allied king when threatened.
 * 
 * Key improvements (v2):
 * - Multi-symmetry exploration: checks rotational, horizontal, AND vertical symmetry
 * - Different rats check different symmetry types (spreads the search)
 * - Broadcasts enemy king location when found (via squeak + shared array)
 * - Better coordination: all rats converge once king is located
 * - Squeak-based swarming: calls allies when enemy king found
 * - Offensive trap placement: traps enemy movement paths
 * - Defensive response: guards king when enemies detected nearby
 * - Gang-up tactics: attacks when sufficient allies present
 * 
 * REQUIRED CONSTANTS (add to Constants.java if not present):
 *   public static final int ENEMY_KING_X_INDEX = 10;
 *   public static final int ENEMY_KING_Y_INDEX = 11;
 *   public static final int ENEMY_KING_SEEN_ROUND_INDEX = 12;
 *   public static final int CONFIRMED_SYMMETRY_INDEX = 13;  // 0=unknown, 1=rot, 2=horiz, 3=vert
 */
public class BabyRatAttacker {

    // === SYMMETRY TYPES ===
    private static final int SYM_ROTATIONAL = 0;   // (w-1-x, h-1-y)
    private static final int SYM_HORIZONTAL = 1;   // (w-1-x, y) - flip across vertical axis
    private static final int SYM_VERTICAL = 2;     // (x, h-1-y) - flip across horizontal axis

    enum AttackState { SEEK, ATTACK, GUARD, SWARM }

    static class Mem {
        MapLocation kingLocation = null;

        AttackState state = AttackState.SEEK;
        MapLocation targetKingLoc = null;
        int turnsSinceKingSeen = 0;
        MapLocation explorationTarget = null;
        int turnsAtTarget = 0;

        // BUG nav memory
        boolean bugging = false;
        boolean bugLeft = false;
        int bugTurns = 0;
        int failedMoves = 0;
        MapLocation prev1 = null;
        MapLocation prev2 = null;

        MapLocation mapCenter = null;
        boolean hasReachedCenter = false;

        // Combat coordination
        int lastSqueakRound = -100;
        int lastTrapRound = -100;
        MapLocation swarmTarget = null;
        int swarmStartRound = -100;

        // === NEW: Multi-symmetry exploration ===
        int assignedSymmetry = -1;       // Which symmetry this rat checks first (-1 = not assigned)
        int currentSymmetryCheck = 0;    // Current symmetry being explored (0, 1, 2)
        int symmetryCheckAttempts = 0;   // How many times we've switched symmetries
        MapLocation[] possibleEnemyKingLocs = null;  // Cached possible locations
        boolean[] symmetryEliminated = new boolean[3];  // Track which symmetries are impossible
        int confirmedSymmetry = -1;      // -1 = unknown, 0/1/2 = confirmed type

        final Random rng;
        Mem(int id) {
            rng = new Random(9001L + id * 97L);
            bugLeft = ((id / 2) % 2 == 0);
            // Assign different rats to check different symmetries first
            assignedSymmetry = id % 3;
            currentSymmetryCheck = assignedSymmetry;
        }
    }

    private static final Map<Integer, Mem> MEM = new HashMap<>();
    private static Mem mem(RobotController rc) {
        int id = rc.getID();
        Mem m = MEM.get(id);
        if (m == null) {
            m = new Mem(id);
            MEM.put(id, m);
        }
        return m;
    }

    /** Call this once per turn for attacker rats. */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc);
        int roundNum = rc.getRoundNum();

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // Read our king location from shared array
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) {
            m.kingLocation = new MapLocation(kx, ky);
            // Initialize possible enemy locations when we know our king's position
            if (m.possibleEnemyKingLocs == null) {
                m.possibleEnemyKingLocs = computePossibleEnemyKingLocations(rc, m.kingLocation);
            }
        }

        // === CHECK FOR CONFIRMED ENEMY KING LOCATION FROM SHARED ARRAY ===
        checkSharedEnemyKingLocation(rc, m, roundNum);

        // === CHECK FOR CONFIRMED SYMMETRY ===
        checkConfirmedSymmetry(rc, m);

        // === PROCESS INCOMING SQUEAKS ===
        processIncomingSqueaks(rc, m, roundNum);

        // === CHECK FOR SWARM TARGET FROM SHARED ARRAY ===
        checkSharedSwarmTarget(rc, m, roundNum);

        // === GUARD MODE: Check if enemy rats near our king ===
        RobotInfo nearbyEnemyRatNearKing = findNearbyEnemyRatNearKing(rc, m);
        if (nearbyEnemyRatNearKing != null) {
            m.state = AttackState.GUARD;
            m.targetKingLoc = nearbyEnemyRatNearKing.getLocation();
            
            // Squeak for backup!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                rc.squeak(SQUEAK_ENEMY_ATTACKING_KING);
                m.lastSqueakRound = roundNum;
            }
        }

        // === CAT FLEE (limited for attackers) ===
        if (shouldAttackerFlee(rc)) {
            runAttackerFlee(rc);
            rc.setIndicatorString("ATTACKER FLEE | " + m.state);
            return;
        }

        // === FIND ENEMY KING ===
        RobotInfo enemyKing = findEnemyKing(rc);
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;

            // Determine which symmetry this confirms
            confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());

            // Squeak to summon allies!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                rc.squeak(SQUEAK_ENEMY_KING_FOUND);
                m.lastSqueakRound = roundNum;
            }
        } else {
            m.turnsSinceKingSeen++;
            if (m.targetKingLoc != null && m.turnsSinceKingSeen > MAX_STALE_TARGET_TURNS) {
                m.targetKingLoc = null;
                if (m.state == AttackState.ATTACK) {
                    m.state = AttackState.SEEK;
                }
            }
        }

        // === TRY TO ELIMINATE SYMMETRIES ===
        // If we can see a location where enemy king SHOULD be but ISN'T, eliminate that symmetry
        tryEliminateSymmetries(rc, m);

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum); break;
            case ATTACK: runAttack(rc, m, roundNum); break;
            case GUARD:  runGuard(rc, m, roundNum); break;
            case SWARM:  runSwarm(rc, m, roundNum); break;
        }

        rc.setIndicatorString("ATK " + m.state +
                " Sym:" + m.currentSymmetryCheck + 
                (m.confirmedSymmetry >= 0 ? "(CONF)" : "") +
                (m.bugging ? " BUG" + (m.bugLeft ? "L" : "R") : "") +
                " | Tgt: " + (m.targetKingLoc != null ? m.targetKingLoc : "none") +
                " | Elim:" + (m.symmetryEliminated[0]?"R":"") + (m.symmetryEliminated[1]?"H":"") + (m.symmetryEliminated[2]?"V":""));
    }

    // ==================== SYMMETRY HELPERS ====================

    /**
     * Compute all 3 possible enemy king locations based on map symmetry types.
     */
    static MapLocation[] computePossibleEnemyKingLocations(RobotController rc, MapLocation ourKing) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int kx = ourKing.x;
        int ky = ourKing.y;

        return new MapLocation[] {
            new MapLocation(w - 1 - kx, h - 1 - ky),  // Rotational (180°)
            new MapLocation(w - 1 - kx, ky),          // Horizontal reflection (flip across vertical axis)
            new MapLocation(kx, h - 1 - ky)           // Vertical reflection (flip across horizontal axis)
        };
    }

    /**
     * Check shared array for confirmed enemy king location.
     */
    static void checkSharedEnemyKingLocation(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Try to read enemy king location from shared array
        // These indices need to be defined in Constants.java
        int enemyKingX = 0;
        int enemyKingY = 0;
        int seenRound = 0;
        
        try {
            enemyKingX = rc.readSharedArray(ENEMY_KING_X_INDEX);
            enemyKingY = rc.readSharedArray(ENEMY_KING_Y_INDEX);
            seenRound = rc.readSharedArray(ENEMY_KING_SEEN_ROUND_INDEX);
        } catch (Exception e) {
            // Constants not defined yet - that's ok
            return;
        }

        // If we have a recent sighting (within 50 rounds), use it
        if (enemyKingX > 0 && enemyKingY > 0) {
            int roundsAgo = roundNum - seenRound;
            if (roundsAgo < 0) roundsAgo += 2000; // Handle wrap
            
            if (roundsAgo < 50) {
                MapLocation knownLoc = new MapLocation(enemyKingX, enemyKingY);
                if (m.state == AttackState.SEEK) {
                    m.targetKingLoc = knownLoc;
                    m.state = AttackState.ATTACK;
                    m.turnsSinceKingSeen = roundsAgo;
                }
            }
        }
    }

    /**
     * Check if symmetry has been confirmed by another rat.
     */
    static void checkConfirmedSymmetry(RobotController rc, Mem m) throws GameActionException {
        try {
            int confirmed = rc.readSharedArray(CONFIRMED_SYMMETRY_INDEX);
            if (confirmed > 0 && confirmed <= 3) {
                m.confirmedSymmetry = confirmed - 1; // 1-indexed in array, 0-indexed internally
                m.currentSymmetryCheck = m.confirmedSymmetry;
                
                // Eliminate other symmetries
                for (int i = 0; i < 3; i++) {
                    if (i != m.confirmedSymmetry) {
                        m.symmetryEliminated[i] = true;
                    }
                }
            }
        } catch (Exception e) {
            // Constants not defined yet
        }
    }

    /**
     * When we find the enemy king, figure out which symmetry type the map uses.
     */
    static void confirmSymmetryFromKingLocation(RobotController rc, Mem m, MapLocation enemyKingLoc) {
        if (m.possibleEnemyKingLocs == null || m.confirmedSymmetry >= 0) return;

        // Check which symmetry prediction was closest to actual location
        // King is 3x3, so we check if the center is within ~2 tiles of prediction
        for (int i = 0; i < 3; i++) {
            if (enemyKingLoc.distanceSquaredTo(m.possibleEnemyKingLocs[i]) <= 8) {
                m.confirmedSymmetry = i;
                // Eliminate other symmetries
                for (int j = 0; j < 3; j++) {
                    m.symmetryEliminated[j] = (j != i);
                }
                break;
            }
        }
    }

    /**
     * If we can see where enemy king SHOULD be but they're NOT there, eliminate that symmetry.
     */
    static void tryEliminateSymmetries(RobotController rc, Mem m) throws GameActionException {
        if (m.possibleEnemyKingLocs == null || m.confirmedSymmetry >= 0) return;

        for (int i = 0; i < 3; i++) {
            if (m.symmetryEliminated[i]) continue;

            MapLocation predicted = m.possibleEnemyKingLocs[i];
            
            // Can we see this location?
            if (rc.canSenseLocation(predicted)) {
                // Check if there's an enemy king at or near this location (3x3 area)
                boolean foundKing = false;
                
                // Check center and surrounding tiles for the 3x3 king
                for (int dx = -1; dx <= 1 && !foundKing; dx++) {
                    for (int dy = -1; dy <= 1 && !foundKing; dy++) {
                        MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                        if (rc.canSenseLocation(checkLoc)) {
                            RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                            if (robot != null && 
                                robot.getType() == UnitType.RAT_KING && 
                                robot.getTeam() == rc.getTeam().opponent()) {
                                foundKing = true;
                            }
                        }
                    }
                }
                
                // If we can see the entire predicted area and no king, eliminate this symmetry
                if (!foundKing) {
                    boolean canSeeAll = true;
                    for (int dx = -1; dx <= 1 && canSeeAll; dx++) {
                        for (int dy = -1; dy <= 1 && canSeeAll; dy++) {
                            MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                            if (!rc.canSenseLocation(checkLoc)) {
                                canSeeAll = false;
                            }
                        }
                    }
                    if (canSeeAll) {
                        m.symmetryEliminated[i] = true;
                        
                        // If this was our current target, switch to another
                        if (m.currentSymmetryCheck == i) {
                            switchToNextSymmetry(m);
                        }
                    }
                }
            }
        }
    }

    /**
     * Switch to the next non-eliminated symmetry to explore.
     */
    static void switchToNextSymmetry(Mem m) {
        for (int attempts = 0; attempts < 3; attempts++) {
            m.currentSymmetryCheck = (m.currentSymmetryCheck + 1) % 3;
            if (!m.symmetryEliminated[m.currentSymmetryCheck]) {
                m.symmetryCheckAttempts++;
                m.turnsAtTarget = 0;
                return;
            }
        }
        // All symmetries eliminated (shouldn't happen), reset to 0
        m.currentSymmetryCheck = 0;
    }

    // ==================== STATE IMPLEMENTATIONS ====================

    static void runSeek(RobotController rc, Mem m, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();

        // If we have a swarm target, go there
        if (m.swarmTarget != null && roundNum - m.swarmStartRound < 30) {
            m.state = AttackState.SWARM;
            runSwarm(rc, m, roundNum);
            return;
        }

        // If we have a known enemy king location (from shared array or previous sighting)
        if (m.targetKingLoc != null) {
            attackerMoveToward(rc, m, m.targetKingLoc);
            return;
        }

        // Pick exploration target based on symmetry
        if (m.explorationTarget == null ||
                here.distanceSquaredTo(m.explorationTarget) <= 9 ||
                m.turnsAtTarget > MAX_TARGET_PURSUIT_TURNS) {
            
            m.explorationTarget = pickSymmetryAwareTarget(rc, m);
            m.turnsAtTarget = 0;
            
            // If we've been searching too long, try another symmetry
            if (m.symmetryCheckAttempts > 0 && m.turnsAtTarget == 0) {
                // Already switched in pickSymmetryAwareTarget
            }
        }

        m.turnsAtTarget++;
        attackerMoveToward(rc, m, m.explorationTarget);
    }

    /**
     * Pick exploration target using multi-symmetry awareness.
     */
    static MapLocation pickSymmetryAwareTarget(RobotController rc, Mem m) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        MapLocation here = rc.getLocation();

        // Head toward center first to get vision of the map
        if (!m.hasReachedCenter && m.mapCenter != null && here.distanceSquaredTo(m.mapCenter) > 100) {
            return m.mapCenter;
        }
        m.hasReachedCenter = true;

        // If we have possible enemy locations from our king's position
        if (m.possibleEnemyKingLocs != null) {
            // If symmetry is confirmed, go directly there
            if (m.confirmedSymmetry >= 0) {
                MapLocation target = m.possibleEnemyKingLocs[m.confirmedSymmetry];
                // Add slight randomness to avoid all rats clustering on exact spot
                int offX = m.rng.nextInt(5) - 2;
                int offY = m.rng.nextInt(5) - 2;
                return clampToMap(rc, target.x + offX, target.y + offY);
            }

            // Check if we should switch to a different symmetry
            if (m.turnsAtTarget > MAX_TARGET_PURSUIT_TURNS / 2 && m.symmetryCheckAttempts < 6) {
                switchToNextSymmetry(m);
            }

            // Go to current symmetry prediction
            MapLocation target = m.possibleEnemyKingLocs[m.currentSymmetryCheck];
            
            // Add randomness to spread out rats
            int offX = m.rng.nextInt(9) - 4;
            int offY = m.rng.nextInt(9) - 4;
            return clampToMap(rc, target.x + offX, target.y + offY);
        }

        // Fallback if we don't know our king location yet
        // Send to opposite quadrant
        int tx = (here.x < w / 2) ? w - 5 : 5;
        int ty = (here.y < h / 2) ? h - 5 : 5;
        return new MapLocation(tx, ty);
    }

    static MapLocation clampToMap(RobotController rc, int x, int y) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        x = Math.max(3, Math.min(w - 4, x));
        y = Math.max(3, Math.min(h - 4, y));
        return new MapLocation(x, y);
    }

    static void runAttack(RobotController rc, Mem m, int roundNum) throws GameActionException {
        if (m.targetKingLoc == null) {
            m.state = AttackState.SEEK;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation center = m.targetKingLoc;

        // === COUNT NEARBY ALLIES FOR GANG-UP ===
        int alliesNearby = countAlliesInRadius(rc, COORDINATED_ATTACK_RADIUS);
        boolean shouldGangUp = alliesNearby >= GANG_UP_THRESHOLD;

        // === TRY TO BITE ENEMY KING ===
        // King is 3x3, so check all 9 tiles
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = new MapLocation(center.x + dx, center.y + dy);
                // Spend more cheese if we have allies (gang up!)
                int spend = shouldGangUp ? 
                    Math.min(BITE_CHEESE_BOOST * 2, rc.getRawCheese() + rc.getGlobalCheese() / 10) :
                    Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(tile, spend)) {
                    rc.attack(tile, spend);
                    return;
                }
            }
        }

        // === ALSO ATTACK ENEMY BABY RATS NEAR THEIR KING ===
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(enemy.getLocation(), spend)) {
                    rc.attack(enemy.getLocation(), spend);
                    return;
                }
            }
        }

        // === PLACE OFFENSIVE TRAP ===
        if (rc.getActionCooldownTurns() < 10 && 
            roundNum - m.lastTrapRound >= OFFENSIVE_TRAP_COOLDOWN &&
            rc.getGlobalCheese() >= TRAP_CHEESE_THRESHOLD) {
            if (tryPlaceOffensiveTrap(rc, m, center)) {
                m.lastTrapRound = roundNum;
            }
        }

        // === MOVE TOWARD TARGET ===
        attackerMoveToward(rc, m, m.targetKingLoc);
    }

    static void runGuard(RobotController rc, Mem m, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Find and attack nearby enemy rats
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        RobotInfo closestEnemy = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int dist = here.distanceSquaredTo(enemy.getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestEnemy = enemy;
                }
            }
        }

        if (closestEnemy != null) {
            // === PLACE DEFENSIVE TRAP BETWEEN ENEMY AND OUR KING ===
            if (m.kingLocation != null && 
                rc.getGlobalCheese() >= RAT_TRAP_COST + 50 &&
                roundNum - m.lastTrapRound >= 10) {
                
                Direction toKing = closestEnemy.getLocation().directionTo(m.kingLocation);
                MapLocation trapLoc = closestEnemy.getLocation().add(toKing);
                
                if (here.isAdjacentTo(trapLoc) && rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    m.lastTrapRound = roundNum;
                }
            }

            // === ATTACK THE ENEMY ===
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
                return;
            }

            // Move toward enemy to attack
            attackerMoveToward(rc, m, closestEnemy.getLocation());
            return;
        }

        // No nearby enemy rats - return to patrol near king or seek
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            attackerMoveToward(rc, m, m.kingLocation);
        } else {
            m.state = AttackState.SEEK;
        }
    }

    static void runSwarm(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Move toward swarm target
        if (m.swarmTarget == null || roundNum - m.swarmStartRound > 30) {
            // Swarm expired
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();

        // If we arrived at swarm target, look for enemy king
        if (here.distanceSquaredTo(m.swarmTarget) <= 16) {
            RobotInfo enemyKing = findEnemyKing(rc);
            if (enemyKing != null) {
                m.targetKingLoc = enemyKing.getLocation();
                m.state = AttackState.ATTACK;
                m.turnsSinceKingSeen = 0;
                confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
                return;
            }
            // No king found at swarm location - search briefly then give up
            if (roundNum - m.swarmStartRound > 15) {
                m.state = AttackState.SEEK;
                m.swarmTarget = null;
                return;
            }
        }

        // Attack any enemies we encounter on the way
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(enemy.getLocation(), spend)) {
                rc.attack(enemy.getLocation(), spend);
                break;
            }
        }

        attackerMoveToward(rc, m, m.swarmTarget);
    }

    // ==================== SQUEAK COMMUNICATION ====================

    static void processIncomingSqueaks(RobotController rc, Mem m, int roundNum) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);
        
        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();
            
            // Skip if too far away
            if (rc.getLocation().distanceSquaredTo(senderLoc) > SWARM_RESPONSE_DISTANCE_SQ) {
                continue;
            }

            switch (content) {
                case SQUEAK_ENEMY_KING_FOUND:
                    // Rush to help attack enemy king!
                    if (m.state == AttackState.SEEK || m.state == AttackState.SWARM) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_ENEMY_ATTACKING_KING:
                    // Our king is under attack - switch to guard!
                    if (m.state != AttackState.ATTACK) {
                        m.state = AttackState.GUARD;
                    }
                    break;

                case SQUEAK_NEED_BACKUP:
                    // General backup request
                    if (m.state == AttackState.SEEK) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_RETREAT:
                    // Flee toward our king
                    if (m.kingLocation != null) {
                        m.explorationTarget = m.kingLocation;
                    }
                    break;
            }
        }
    }

    static void checkSharedSwarmTarget(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Check if king has broadcast a swarm target
        int swarmX = rc.readSharedArray(SWARM_TARGET_X);
        int swarmY = rc.readSharedArray(SWARM_TARGET_Y);
        int swarmRound = rc.readSharedArray(SWARM_ACTIVE_ROUND);

        if (swarmX > 0 && swarmY > 0) {
            // Check if swarm is recent (within 20 rounds)
            int roundDiff = (roundNum % 1000) - swarmRound;
            if (roundDiff < 0) roundDiff += 1000;
            
            if (roundDiff < 20 && m.state == AttackState.SEEK) {
                m.swarmTarget = new MapLocation(swarmX, swarmY);
                m.swarmStartRound = roundNum;
                m.state = AttackState.SWARM;
            }
        }
    }

    // ==================== TRAP PLACEMENT ====================

    static boolean tryPlaceOffensiveTrap(RobotController rc, Mem m, MapLocation enemyKingCenter) 
            throws GameActionException {
        MapLocation here = rc.getLocation();
        
        // Predict enemy king's retreat direction (toward their spawn)
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();
        
        // Use confirmed symmetry to better predict retreat direction
        MapLocation enemySpawn = null;
        if (m.kingLocation != null) {
            if (m.confirmedSymmetry == SYM_ROTATIONAL) {
                enemySpawn = new MapLocation(mapW - 1 - m.kingLocation.x, mapH - 1 - m.kingLocation.y);
            } else if (m.confirmedSymmetry == SYM_HORIZONTAL) {
                enemySpawn = new MapLocation(mapW - 1 - m.kingLocation.x, m.kingLocation.y);
            } else if (m.confirmedSymmetry == SYM_VERTICAL) {
                enemySpawn = new MapLocation(m.kingLocation.x, mapH - 1 - m.kingLocation.y);
            } else {
                // Unknown symmetry, use rotational as default
                enemySpawn = new MapLocation(mapW - 1 - m.kingLocation.x, mapH - 1 - m.kingLocation.y);
            }
        } else {
            enemySpawn = new MapLocation(mapW / 2, mapH / 2);
        }
        
        Direction retreatDir = enemyKingCenter.directionTo(enemySpawn);
        
        // Try to place trap in retreat path
        Direction[] trapDirs = {
            retreatDir,
            retreatDir.rotateLeft(),
            retreatDir.rotateRight(),
            retreatDir.rotateLeft().rotateLeft(),
            retreatDir.rotateRight().rotateRight()
        };
        
        for (Direction dir : trapDirs) {
            for (int dist = 1; dist <= 2; dist++) {
                MapLocation trapLoc = enemyKingCenter;
                for (int i = 0; i < dist; i++) {
                    trapLoc = trapLoc.add(dir);
                }
                
                if (here.isAdjacentTo(trapLoc) && rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    return true;
                }
            }
        }
        
        // Fallback: place trap adjacent to us toward enemy
        Direction toEnemy = here.directionTo(enemyKingCenter);
        MapLocation fallbackLoc = here.add(toEnemy);
        if (rc.canPlaceRatTrap(fallbackLoc)) {
            rc.placeRatTrap(fallbackLoc);
            return true;
        }
        
        return false;
    }

    // ==================== SENSING ====================

    static boolean shouldAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_CAT_FLEE_RADIUS);
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.CAT) return true;
        }
        return false;
    }

    static void runAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo nearestCat = getNearestCat(rc, ATTACKER_CAT_FLEE_RADIUS);
        if (nearestCat == null) return;

        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(nearestCat.getLocation()).opposite();

        if (rc.getDirection() != away && rc.canTurn(away)) rc.turn(away);
        if (rc.canMoveForward()) rc.moveForward();
    }

    static RobotInfo getNearestCat(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radius);
        MapLocation here = rc.getLocation();

        RobotInfo best = null;
        int bestD = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getType() != UnitType.CAT) continue;
            int d = here.distanceSquaredTo(r.getLocation());
            if (d < bestD) { bestD = d; best = r; }
        }
        return best;
    }

    static RobotInfo findEnemyKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);
        Team enemy = rc.getTeam().opponent();
        MapLocation here = rc.getLocation();

        RobotInfo best = null;
        int bestD = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.RAT_KING && r.getTeam() == enemy) {
                int d = here.distanceSquaredTo(r.getLocation());
                if (d < bestD) { bestD = d; best = r; }
            }
        }
        return best;
    }

    static RobotInfo findNearbyEnemyRatNearKing(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return null;

        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);
        Team enemy = rc.getTeam().opponent();

        for (RobotInfo r : nearby) {
            if (r.getTeam() == enemy && r.getType() == UnitType.BABY_RAT) {
                int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                if (distToKing <= 100) { // Within 10 units of king
                    return r;
                }
            }
        }
        return null;
    }

    static int countAlliesInRadius(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radiusSq, rc.getTeam());
        int count = 0;
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }

    // ==================== NAVIGATION (BUG + anti-oscillation) ====================

    static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        if (!m.bugging) {
            Direction[] tryDirs = {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateRight(),
                    desired.rotateLeft().rotateLeft(),
                    desired.rotateRight().rotateRight()
            };

            for (Direction d : tryDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.failedMoves = 0;
                    return true;
                }
            }

            m.failedMoves++;
            if (m.failedMoves >= 2) {
                m.bugging = true;
                m.bugTurns = 0;
            }
        }

        if (m.bugging) {
            Direction[] bugDirs;
            if (m.bugLeft) {
                bugDirs = new Direction[] {
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired,
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired.opposite().rotateRight(),
                        desired.opposite().rotateLeft(),
                        desired.opposite()
                };
            } else {
                bugDirs = new Direction[] {
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired,
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired.opposite().rotateLeft(),
                        desired.opposite().rotateRight(),
                        desired.opposite()
                };
            }

            for (Direction d : bugDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.bugTurns++;
                    m.failedMoves = 0;

                    if (rc.canMove(desired) || m.bugTurns > 12) {
                        m.bugging = false;
                        m.bugTurns = 0;
                    }
                    return true;
                }
            }

            m.bugTurns++;
            if (m.bugTurns > 12) {
                m.bugging = false;
                m.bugTurns = 0;
            }
        }

        return false;
    }

    static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation next = oldLoc.add(dir);

        // Anti-oscillation
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // Avoid occupied tiles (robots)
        if (rc.canSenseLocation(next)) {
            RobotInfo at = rc.senseRobotAtLocation(next);
            if (at != null) return false;
        }

        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
            m.prev2 = m.prev1;
            m.prev1 = oldLoc;
            return true;
        }

        return false;
    }
}