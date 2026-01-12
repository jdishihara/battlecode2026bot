package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * ATTACKER baby rat: CLUSTERED SYMMETRY SEARCH
 * 
 * Key strategy:
 * - ALL attackers search the SAME symmetry location together (cluster)
 * - When the cluster confirms a symmetry is wrong, ALL switch to next symmetry
 * - This keeps rats together for better combat when king is found
 * 
 * Symmetry elimination triggers:
 * 1. Rat reaches predicted location and visually confirms no king
 * 2. Rat detects impassable terrain at predicted location
 * 3. Timeout: cluster has searched current symmetry for too long
 * 4. Boundary detection: predicted location is off-map or inaccessible
 * 
 * Communication:
 * - Shared array stores current symmetry target (so all rats converge)
 * - Squeaks coordinate local movement and announce findings
 * - When king found, all nearby rats swarm
 * 
 * BUG FIX: Now passes effectiveSymmetry to tryEliminateCurrentSymmetry()
 * instead of relying on globalSymmetry (which baby rats can't write to).
 */
public class BabyRatAttacker {

    // === SYMMETRY TYPES ===
    private static final int SYM_UNKNOWN = 0;
    private static final int SYM_ROTATIONAL = 1;   // (w-1-x, h-1-y)
    private static final int SYM_HORIZONTAL = 2;   // (w-1-x, y) - flip across vertical axis
    private static final int SYM_VERTICAL = 3;     // (x, h-1-y) - flip across horizontal axis

    enum AttackState { SEEK, ATTACK, GUARD, SWARM }

    static class Mem {
        MapLocation kingLocation = null;

        AttackState state = AttackState.SEEK;
        MapLocation targetKingLoc = null;      // Confirmed enemy king location
        int turnsSinceKingSeen = 0;
        MapLocation currentTarget = null;       // Current movement target
        int turnsAtTarget = 0;

        // BUG nav memory
        boolean bugging = false;
        boolean bugLeft = false;
        int bugTurns = 0;
        int failedMoves = 0;
        MapLocation prev1 = null;
        MapLocation prev2 = null;

        MapLocation mapCenter = null;

        // Combat coordination
        int lastSqueakRound = -100;
        int lastTrapRound = -100;
        MapLocation swarmTarget = null;
        int swarmStartRound = -100;

        // === CLUSTERED SYMMETRY SEARCH ===
        MapLocation[] possibleEnemyKingLocs = null;  // [rot, horiz, vert] - indices 0,1,2
        int localSymmetryEliminated = 0;             // Local bitmask of eliminated symmetries
        int lastSymmetryChecked = 0;                 // Track which symmetry we last checked (for timeout reset)
        boolean reachedCurrentTarget = false;        // Have we arrived at current symmetry target?
        int roundsSearchingCurrentSymmetry = 0;      // How long we've searched current symmetry

        final Random rng;
        Mem(int id) {
            rng = new Random(9001L + id * 97L);
            bugLeft = ((id / 2) % 2 == 0);
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
        MapLocation here = rc.getLocation();

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // === READ OUR KING LOCATION ===
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) {
            m.kingLocation = new MapLocation(kx, ky);
            if (m.possibleEnemyKingLocs == null) {
                m.possibleEnemyKingLocs = computePossibleEnemyKingLocations(rc, m.kingLocation);
            }
        }

        // === READ GLOBAL SYMMETRY STATE ===
        int globalSymmetry = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int globalEliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);

        // Sync local eliminated state with global
        m.localSymmetryEliminated |= globalEliminated;

        // === COMPUTE EFFECTIVE SYMMETRY EARLY ===
        // This is what we're ACTUALLY searching for, using local knowledge
        int effectiveSymmetry = computeEffectiveSymmetry(globalSymmetry, m.localSymmetryEliminated);
        
        // Track how long we've been searching THIS symmetry (reset when symmetry changes)
        if (effectiveSymmetry != m.lastSymmetryChecked) {
            m.lastSymmetryChecked = effectiveSymmetry;
            m.roundsSearchingCurrentSymmetry = 0;
            m.reachedCurrentTarget = false;
        }
        m.roundsSearchingCurrentSymmetry++;

        // === CHECK FOR CONFIRMED ENEMY KING IN SHARED ARRAY ===
        MapLocation knownEnemyKing = readEnemyKingLocation(rc, roundNum);
        if (knownEnemyKing != null) {
            m.targetKingLoc = knownEnemyKing;
            if (m.state == AttackState.SEEK) {
                m.state = AttackState.ATTACK;
            }
        }

        // === PROCESS INCOMING SQUEAKS ===
        processIncomingSqueaks(rc, m, roundNum);

        // === GUARD MODE: Check if enemy rats near our king ===
        RobotInfo nearbyEnemyRatNearKing = findNearbyEnemyRatNearKing(rc, m);
        if (nearbyEnemyRatNearKing != null && m.state != AttackState.ATTACK) {
            m.state = AttackState.GUARD;
            
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

        // === LOOK FOR ENEMY KING ===
        RobotInfo enemyKing = findEnemyKing(rc);
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;

            // Confirm which symmetry this is
            int confirmedSym = confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
            
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

        // === TRY TO ELIMINATE CURRENT SYMMETRY ===
        // FIX: Pass effectiveSymmetry instead of relying on globalSymmetry
        if (m.state == AttackState.SEEK && m.possibleEnemyKingLocs != null) {
            tryEliminateCurrentSymmetry(rc, m, effectiveSymmetry, roundNum);
        }

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum, effectiveSymmetry); break;
            case ATTACK: runAttack(rc, m, roundNum); break;
            case GUARD:  runGuard(rc, m, roundNum); break;
            case SWARM:  runSwarm(rc, m, roundNum); break;
        }

        // Debug indicator
        String symStr = effectiveSymmetry == SYM_ROTATIONAL ? "ROT" : 
                        effectiveSymmetry == SYM_HORIZONTAL ? "HOR" : 
                        effectiveSymmetry == SYM_VERTICAL ? "VER" : "UNK";
        String elimStr = ((m.localSymmetryEliminated & 1) != 0 ? "R" : "") +
                         ((m.localSymmetryEliminated & 2) != 0 ? "H" : "") +
                         ((m.localSymmetryEliminated & 4) != 0 ? "V" : "");
        rc.setIndicatorString("ATK " + m.state + " | Sym:" + symStr + " Elim:" + elimStr +
                " | Tgt:" + (m.currentTarget != null ? m.currentTarget : "none") +
                " | r" + m.roundsSearchingCurrentSymmetry);
    }

    // ==================== SYMMETRY HELPERS ====================

    /**
     * Compute the effective symmetry to search, using local elimination knowledge.
     * This is separate from globalSymmetry because baby rats can't write to shared array.
     */
    static int computeEffectiveSymmetry(int globalSymmetry, int localEliminated) {
        // If global symmetry is set and not eliminated locally, use it
        if (globalSymmetry >= SYM_ROTATIONAL && globalSymmetry <= SYM_VERTICAL) {
            int symBit = 1 << (globalSymmetry - 1);
            if ((localEliminated & symBit) == 0) {
                return globalSymmetry;
            }
        }
        
        // Otherwise, find first non-eliminated symmetry
        return getNextValidSymmetry(localEliminated);
    }

    /**
     * Compute all 3 possible enemy king locations based on map symmetry types.
     * Returns array indexed by (symmetry - 1): [0]=rotational, [1]=horizontal, [2]=vertical
     */
    static MapLocation[] computePossibleEnemyKingLocations(RobotController rc, MapLocation ourKing) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int kx = ourKing.x;
        int ky = ourKing.y;

        return new MapLocation[] {
            new MapLocation(w - 1 - kx, h - 1 - ky),  // [0] Rotational (180°)
            new MapLocation(w - 1 - kx, ky),          // [1] Horizontal reflection
            new MapLocation(kx, h - 1 - ky)           // [2] Vertical reflection
        };
    }

    /**
     * Read enemy king location from shared array if recent.
     */
    static MapLocation readEnemyKingLocation(RobotController rc, int roundNum) throws GameActionException {
        int enemyKingX = rc.readSharedArray(ENEMY_KING_X_INDEX);
        int enemyKingY = rc.readSharedArray(ENEMY_KING_Y_INDEX);
        int seenRound = rc.readSharedArray(ENEMY_KING_SEEN_ROUND_INDEX);

        if (enemyKingX > 0 && enemyKingY > 0) {
            int roundsAgo = (roundNum % 1000) - seenRound;
            if (roundsAgo < 0) roundsAgo += 1000;
            
            if (roundsAgo < 50) {
                return new MapLocation(enemyKingX, enemyKingY);
            }
        }
        return null;
    }

    /**
     * When we find the enemy king, figure out which symmetry type the map uses.
     * Returns the confirmed symmetry type (1, 2, or 3), or 0 if can't determine.
     */
    static int confirmSymmetryFromKingLocation(RobotController rc, Mem m, MapLocation enemyKingLoc) {
        if (m.possibleEnemyKingLocs == null) return 0;

        // King is 3x3, so check if actual location is close to any prediction
        for (int i = 0; i < 3; i++) {
            if (enemyKingLoc.distanceSquaredTo(m.possibleEnemyKingLocs[i]) <= KING_SEARCH_RADIUS) {
                int symType = i + 1; // Convert to 1-indexed
                
                // Mark other symmetries as eliminated
                for (int j = 0; j < 3; j++) {
                    if (j != i) {
                        m.localSymmetryEliminated |= (1 << j);
                    }
                }
                return symType;
            }
        }
        return 0;
    }

    /**
     * Try to eliminate the current symmetry if we can prove it's wrong.
     * FIX: Now takes effectiveSymmetry as parameter instead of reading globalSymmetry.
     * 
     * STRICT ELIMINATION: Rats must get VERY close (adjacent or nearly adjacent)
     * to the predicted king location before eliminating. This ensures they travel
     * all the way to corners if needed.
     */
    static void tryEliminateCurrentSymmetry(RobotController rc, Mem m, int effectiveSymmetry, int roundNum) 
            throws GameActionException {
        
        // FIX: Use effectiveSymmetry parameter instead of globalSymmetry
        if (effectiveSymmetry < SYM_ROTATIONAL || effectiveSymmetry > SYM_VERTICAL) return;
        
        int symIndex = effectiveSymmetry - 1; // Convert to 0-indexed
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) return; // Already eliminated
        
        MapLocation predicted = m.possibleEnemyKingLocs[symIndex];
        MapLocation here = rc.getLocation();
        int distToPredicted = here.distanceSquaredTo(predicted);
        
        boolean shouldEliminate = false;
        String reason = "";

        // === CONDITION 1: Predicted location is too close to boundary for 3x3 king ===
        // Check this first since it doesn't require being close
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        // King center must be at least 1 tile from edge (king is 3x3)
        if (predicted.x < 1 || predicted.x >= w - 1 || 
            predicted.y < 1 || predicted.y >= h - 1) {
            shouldEliminate = true;
            reason = "boundary_impossible";
        }

        // === CONDITION 2: Predicted center is blocked by wall ===
        // Can check from distance if we can sense it
        if (!shouldEliminate && rc.canSenseLocation(predicted)) {
            MapInfo info = rc.senseMapInfo(predicted);
            if (info.isWall()) {
                shouldEliminate = true;
                reason = "wall_blocked";
            }
        }

        // === CONDITION 3: STRICT - Must be VERY close to eliminate ===
        // Only eliminate if we're within 2 tiles (distSq <= 8) of the predicted CENTER
        // This ensures rats go ALL THE WAY to corners
        if (!shouldEliminate && distToPredicted <= 8) {
            m.reachedCurrentTarget = true;
            
            // We're right next to where the king should be - check thoroughly
            boolean foundEnemyKing = false;
            int tilesChecked = 0;
            
            // Check the entire 3x3 area where king would be
            for (int dx = -1; dx <= 1 && !foundEnemyKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundEnemyKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null && 
                            robot.getType() == UnitType.RAT_KING && 
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundEnemyKing = true;
                        }
                    }
                }
            }
            
            // Must have checked ALL 9 tiles of the 3x3 area to be sure
            if (!foundEnemyKing && tilesChecked >= 9) {
                shouldEliminate = true;
                reason = "confirmed_empty";
            }
            // If we checked at least 6 tiles and we're adjacent, that's good enough
            else if (!foundEnemyKing && tilesChecked >= 6 && distToPredicted <= 2) {
                shouldEliminate = true;
                reason = "adjacent_empty";
            }
        }
        
        // === CONDITION 4: We're close (within 4 tiles) and can see the ENTIRE 3x3 area is empty ===
        if (!shouldEliminate && distToPredicted <= 16) {
            boolean foundKing = false;
            int tilesChecked = 0;
            
            for (int dx = -1; dx <= 1 && !foundKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null && 
                            robot.getType() == UnitType.RAT_KING && 
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundKing = true;
                        }
                    }
                }
            }
            
            // Only eliminate if we checked ALL 9 tiles
            if (!foundKing && tilesChecked >= 9) {
                shouldEliminate = true;
                reason = "visual_confirm_all9";
                m.reachedCurrentTarget = true;
            }
        }

        // === CONDITION 5: Timeout - but ONLY if we've actually reached the target ===
        // Much longer timeout, and require being close
        int timeoutThreshold = SYMMETRY_SEARCH_TIMEOUT * 2; // Double the timeout
        
        if (!shouldEliminate && m.roundsSearchingCurrentSymmetry > timeoutThreshold) {
            // Only timeout-eliminate if we actually got close
            if (m.reachedCurrentTarget && distToPredicted <= 16) {
                shouldEliminate = true;
                reason = "timeout_at_target";
            }
            // Or if we've been searching for a VERY long time and are reasonably close
            else if (m.roundsSearchingCurrentSymmetry > timeoutThreshold * 2 && distToPredicted <= 50) {
                shouldEliminate = true;
                reason = "timeout_extended";
            }
        }

        // === ELIMINATE THIS SYMMETRY ===
        if (shouldEliminate) {
            m.localSymmetryEliminated |= (1 << symIndex);
            
            // Squeak to notify nearby allies
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN / 2) {
                rc.squeak(SQUEAK_SYMMETRY_ELIMINATED + (symIndex * 10));
                m.lastSqueakRound = roundNum;
            }
        }
    }

    /**
     * Get the current symmetry target location.
     */
    static MapLocation getCurrentSymmetryTarget(Mem m, int effectiveSymmetry) {
        if (m.possibleEnemyKingLocs == null || effectiveSymmetry < SYM_ROTATIONAL) {
            return m.mapCenter;
        }
        
        int symIndex = effectiveSymmetry - 1;
        
        // If current symmetry is eliminated, find first non-eliminated
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) {
            for (int i = 0; i < 3; i++) {
                if ((m.localSymmetryEliminated & (1 << i)) == 0) {
                    return m.possibleEnemyKingLocs[i];
                }
            }
        }
        
        return m.possibleEnemyKingLocs[symIndex];
    }

    /**
     * Get the next symmetry to try after current one is eliminated.
     */
    static int getNextSymmetry(int current, int eliminated) {
        // Try symmetries in order: rotational -> horizontal -> vertical
        int[] order = {SYM_ROTATIONAL, SYM_HORIZONTAL, SYM_VERTICAL};
        
        for (int sym : order) {
            int bit = 1 << (sym - 1);
            if ((eliminated & bit) == 0) {
                return sym;
            }
        }
        
        // All eliminated? Return rotational as fallback
        return SYM_ROTATIONAL;
    }

    // ==================== STATE IMPLEMENTATIONS ====================

    static void runSeek(RobotController rc, Mem m, int roundNum, int effectiveSymmetry) throws GameActionException {
        MapLocation here = rc.getLocation();

        // If we have a swarm target, go there
        if (m.swarmTarget != null && roundNum - m.swarmStartRound < 30) {
            m.state = AttackState.SWARM;
            runSwarm(rc, m, roundNum);
            return;
        }

        // If we already know enemy king location, go there
        if (m.targetKingLoc != null) {
            m.currentTarget = m.targetKingLoc;
            attackerMoveToward(rc, m, m.targetKingLoc);
            return;
        }

        // === CLUSTERED SYMMETRY SEARCH ===
        // FIX: effectiveSymmetry is now passed in, already computed with local knowledge
        
        // Get target location for this symmetry
        MapLocation symmetryTarget;
        if (m.possibleEnemyKingLocs != null && effectiveSymmetry >= SYM_ROTATIONAL && effectiveSymmetry <= SYM_VERTICAL) {
            symmetryTarget = m.possibleEnemyKingLocs[effectiveSymmetry - 1];
        } else {
            symmetryTarget = m.mapCenter;
        }
        
        int distToTarget = here.distanceSquaredTo(symmetryTarget);
        
        // Only spread out when FAR from target (> 10 tiles away)
        // When close, converge on the exact location to properly check corners
        MapLocation moveTarget;
        if (distToTarget > 100) {
            // Far away - add slight spread to avoid traffic jams
            int spreadX = (rc.getID() % 5) - 2;  // -2 to +2 (smaller spread)
            int spreadY = ((rc.getID() / 5) % 5) - 2;
            moveTarget = new MapLocation(
                Math.max(2, Math.min(rc.getMapWidth() - 3, symmetryTarget.x + spreadX)),
                Math.max(2, Math.min(rc.getMapHeight() - 3, symmetryTarget.y + spreadY))
            );
        } else {
            // Close to target - go directly to the predicted location
            // This ensures rats actually reach corners
            moveTarget = symmetryTarget;
        }
        
        m.currentTarget = moveTarget;

        // Check if we've reached the target area (within 3 tiles)
        if (distToTarget <= 9) {
            m.reachedCurrentTarget = true;
        }

        // Debug: show which symmetry we're actually heading to
        rc.setIndicatorLine(here, symmetryTarget, 255, 255, 0); // Yellow line to target

        attackerMoveToward(rc, m, moveTarget);
    }

    /**
     * Get the next valid (non-eliminated) symmetry to check.
     */
    static int getNextValidSymmetry(int eliminated) {
        // Priority: rotational -> horizontal -> vertical
        if ((eliminated & 1) == 0) return SYM_ROTATIONAL;  // bit 0 = rotational
        if ((eliminated & 2) == 0) return SYM_HORIZONTAL;  // bit 1 = horizontal
        if ((eliminated & 4) == 0) return SYM_VERTICAL;    // bit 2 = vertical
        return SYM_ROTATIONAL; // All eliminated, default
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
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = new MapLocation(center.x + dx, center.y + dy);
                int spend = shouldGangUp ? 
                    Math.min(BITE_CHEESE_BOOST * 2, rc.getRawCheese() + rc.getGlobalCheese() / 10) :
                    Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(tile, spend)) {
                    rc.attack(tile, spend);
                    return;
                }
            }
        }

        // === ALSO ATTACK ENEMY BABY RATS ===
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
        m.currentTarget = m.targetKingLoc;
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
            // Place defensive trap
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

            // Attack the enemy
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
                return;
            }

            m.currentTarget = closestEnemy.getLocation();
            attackerMoveToward(rc, m, closestEnemy.getLocation());
            return;
        }

        // No enemies - return to king or resume seeking
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            m.currentTarget = m.kingLocation;
            attackerMoveToward(rc, m, m.kingLocation);
        } else {
            m.state = AttackState.SEEK;
        }
    }

    static void runSwarm(RobotController rc, Mem m, int roundNum) throws GameActionException {
        if (m.swarmTarget == null || roundNum - m.swarmStartRound > 30) {
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();

        // If we arrived, look for enemy king
        if (here.distanceSquaredTo(m.swarmTarget) <= 16) {
            RobotInfo enemyKing = findEnemyKing(rc);
            if (enemyKing != null) {
                m.targetKingLoc = enemyKing.getLocation();
                m.state = AttackState.ATTACK;
                m.turnsSinceKingSeen = 0;
                confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
                return;
            }
            
            if (roundNum - m.swarmStartRound > 15) {
                m.state = AttackState.SEEK;
                m.swarmTarget = null;
                return;
            }
        }

        // Attack enemies on the way
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(enemy.getLocation(), spend)) {
                rc.attack(enemy.getLocation(), spend);
                break;
            }
        }

        m.currentTarget = m.swarmTarget;
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

            // Handle symmetry elimination broadcast
            if (content >= SQUEAK_SYMMETRY_ELIMINATED && content < SQUEAK_SYMMETRY_ELIMINATED + 30) {
                int eliminatedSym = (content - SQUEAK_SYMMETRY_ELIMINATED) / 10;
                if (eliminatedSym >= 0 && eliminatedSym < 3) {
                    m.localSymmetryEliminated |= (1 << eliminatedSym);
                }
                continue;
            }

            switch (content) {
                case SQUEAK_ENEMY_KING_FOUND:
                    if (m.state == AttackState.SEEK || m.state == AttackState.SWARM) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_ENEMY_ATTACKING_KING:
                    if (m.state != AttackState.ATTACK) {
                        m.state = AttackState.GUARD;
                    }
                    break;

                case SQUEAK_NEED_BACKUP:
                case SQUEAK_RALLY_TO_ME:
                    if (m.state == AttackState.SEEK) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_RETREAT:
                    if (m.kingLocation != null) {
                        m.currentTarget = m.kingLocation;
                    }
                    break;
            }
        }
    }

    // ==================== TRAP PLACEMENT ====================

    static boolean tryPlaceOffensiveTrap(RobotController rc, Mem m, MapLocation enemyKingCenter) 
            throws GameActionException {
        MapLocation here = rc.getLocation();
        
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();
        
        // Predict retreat direction
        MapLocation enemySpawn = m.possibleEnemyKingLocs != null ? 
            m.possibleEnemyKingLocs[0] : new MapLocation(mapW / 2, mapH / 2);
        
        Direction retreatDir = enemyKingCenter.directionTo(enemySpawn);
        
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
                if (distToKing <= 100) {
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

    // ==================== NAVIGATION ====================

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

    static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc) 
            throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation next = oldLoc.add(dir);

        // Anti-oscillation
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // Avoid occupied tiles
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