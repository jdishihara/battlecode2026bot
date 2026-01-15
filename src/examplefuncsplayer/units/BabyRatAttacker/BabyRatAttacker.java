package examplefuncsplayer.units.BabyRatAttacker;

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

    // === CAT DISTANCE BANDS ===
    private static final int CAT_PANIC_DIST_SQ = 9;     // ~3 tiles - hard flee
    private static final int CAT_DANGER_DIST_SQ = 25;   // ~5 tiles - steer away strongly
    private static final int CAT_CAUTION_DIST_SQ = 64;  // ~8 tiles - mild steering
    private static final int CAT_BAND_NONE = 0;
    private static final int CAT_BAND_CAUTION = 1;
    private static final int CAT_BAND_DANGER = 2;
    private static final int CAT_BAND_PANIC = 3;

    // === CAT MEMORY TTL ===
    private static final int CAT_MEMORY_TTL = 8;        // Rounds to remember cat after losing sight
    private static final int CAT_MEMORY_DANGER_TTL = 2; // Very recent memory uses DANGER band

    // === FORMATION SQUEAK FORMAT ===
    // Base number chosen to avoid collision with existing squeaks (1-7, 6+n*10, and encoded msgs up to ~32k)
    // Format: FORMATION_BASE + (seq * 8) + dirOrdinal
    // - seq: sequence number 0-127 (wraps)
    // - dirOrdinal: 0-7 for 8 cardinal/diagonal directions
    private static final int FORMATION_BASE = 100000;
    private static final int FORMATION_MAX = FORMATION_BASE + (128 * 8);  // Upper bound for formation msgs

    /** Check if a squeak message is a formation message. */
    static boolean isFormationMsg(int msg) {
        return msg >= FORMATION_BASE && msg < FORMATION_MAX;
    }

    /**
     * Encode a formation squeak message.
     * @param seq Sequence number (0-127, will be masked)
     * @param dir Direction to move (ordinal 0-7)
     * @return Encoded formation message
     */
    static int encodeFormation(int seq, Direction dir) {
        int dirOrdinal = dir.ordinal();  // 0-7 for NORTH, NORTHEAST, EAST, etc.
        return FORMATION_BASE + ((seq & 0x7F) * 8) + dirOrdinal;
    }

    /**
     * Decode sequence number from formation message.
     * @param msg Formation message
     * @return Sequence number (0-127)
     */
    static int decodeFormationSeq(int msg) {
        return ((msg - FORMATION_BASE) / 8) & 0x7F;
    }

    /**
     * Decode direction from formation message.
     * @param msg Formation message
     * @return Direction ordinal (0-7), use Direction.values()[ordinal] to get Direction
     */
    static int decodeFormationDir(int msg) {
        return (msg - FORMATION_BASE) % 8;
    }

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

        // Global alert (from shared array)
        MapLocation alertRallyLoc = null;
        int alertReason = 0;

        // Local mesh squeak cooldown
        int lastHelpSqueakRound = -100;

        // Local swarm response (from nearby MSG_HELP squeaks)
        MapLocation localSwarmTarget = null;
        int localSwarmUntilRound = -1;

        // === FORMATION MOVEMENT TRACKING ===
        int latestFormationSeq = -1;           // Latest formation sequence number seen
        int formationSeenRound = -1;           // Round when formation msg was seen
        MapLocation lastFormationSourceLoc = null;  // Location of rat that sent formation msg
        Direction lastFormationDir = null;     // Direction from formation message
        int lastSeqForwarded = -1;             // Last sequence number we forwarded
        boolean isLeader = false;              // True if this rat is the local leader this turn
        int leaderStuckCount = 0;              // Turns stuck as leader (resets on move)
        int stuckCooldown = 0;                 // Rounds to wait before re-attempting leadership

        // === CAT MEMORY ===
        MapLocation lastCatLoc = null;         // Last known cat location (any range)
        int lastCatSeenRound = -100;           // Round when cat was last seen
        int lastCatSqueakRound = -100;         // For cat-spot squeak cooldown
        boolean adoptedCatLoc = false;         // True if lastCatLoc came from squeak (for debug)
        boolean broadcastedCat = false;        // True if we broadcasted cat this turn (for debug)

        // === CURRENT TURN CAT INFO (for movement layer) ===
        MapLocation turnCatLoc = null;         // Cat location this turn (null if none)
        int turnCatBand = CAT_BAND_NONE;       // Distance band this turn

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
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // === CONSOLIDATED SENSE CALLS (bytecode optimization) ===
        // Sense 1: All nearby robots for allies + leadership
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(LEADER_SENSE_RADIUS_SQ, myTeam);

        // Sense 2: All nearby robots (for cats, enemies, enemy king)
        RobotInfo[] nearbyAll = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);

        // Filter from nearbyAll: cats, enemy rats, enemy king
        RobotInfo nearestCat = null;           // Cat within flee radius (for flee behavior)
        RobotInfo enemyKing = null;
        RobotInfo nearbyEnemyRatNearKing = null;
        int nearestCatDistSq = Integer.MAX_VALUE;
        int nearestEnemyKingDistSq = Integer.MAX_VALUE;

        // Track nearest cat at ANY range (for early detection/memory)
        int nearestCatAnyDistSq = Integer.MAX_VALUE;
        MapLocation nearestCatAnyLoc = null;

        for (RobotInfo r : nearbyAll) {
            if (r.getType() == UnitType.CAT) {
                int d = here.distanceSquaredTo(r.getLocation());
                // Track nearest cat at any range
                if (d < nearestCatAnyDistSq) {
                    nearestCatAnyDistSq = d;
                    nearestCatAnyLoc = r.getLocation();
                }
                // Track cat for flee behavior (only within flee radius)
                if (d < nearestCatDistSq && d <= ATTACKER_CAT_FLEE_RADIUS) {
                    nearestCatDistSq = d;
                    nearestCat = r;
                }
            } else if (r.getTeam() == enemyTeam) {
                if (r.getType() == UnitType.RAT_KING) {
                    int d = here.distanceSquaredTo(r.getLocation());
                    if (d < nearestEnemyKingDistSq) {
                        nearestEnemyKingDistSq = d;
                        enemyKing = r;
                    }
                } else if (r.getType() == UnitType.BABY_RAT && m.kingLocation != null) {
                    int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                    if (distToKing <= 100 && nearbyEnemyRatNearKing == null) {
                        nearbyEnemyRatNearKing = r;
                    }
                }
            }
        }

        // Reset per-turn debug flags
        m.adoptedCatLoc = false;
        m.broadcastedCat = false;

        // Update cat memory if any cat seen this turn
        if (nearestCatAnyLoc != null) {
            m.lastCatLoc = nearestCatAnyLoc;
            m.lastCatSeenRound = roundNum;
            m.adoptedCatLoc = false;  // Direct sighting, not adopted
        }
        // Note: Do NOT clear lastCatLoc if no cat seen - keep as memory

        // Set current turn cat info for movement layer
        if (nearestCatAnyLoc != null) {
            // Cat currently visible - use actual position and distance
            m.turnCatLoc = nearestCatAnyLoc;
            if (nearestCatAnyDistSq <= CAT_PANIC_DIST_SQ) {
                m.turnCatBand = CAT_BAND_PANIC;
            } else if (nearestCatAnyDistSq <= CAT_DANGER_DIST_SQ) {
                m.turnCatBand = CAT_BAND_DANGER;
            } else if (nearestCatAnyDistSq <= CAT_CAUTION_DIST_SQ) {
                m.turnCatBand = CAT_BAND_CAUTION;
            } else {
                m.turnCatBand = CAT_BAND_NONE;
            }
        } else {
            // No cat visible - check memory
            int roundsSinceSeen = roundNum - m.lastCatSeenRound;
            if (m.lastCatLoc != null && roundsSinceSeen <= CAT_MEMORY_TTL) {
                // Fresh memory - use as "virtual cat" for avoidance
                m.turnCatLoc = m.lastCatLoc;
                // Very recent memory (<=2 rounds) uses DANGER band, older uses CAUTION
                m.turnCatBand = (roundsSinceSeen <= CAT_MEMORY_DANGER_TTL)
                    ? CAT_BAND_DANGER : CAT_BAND_CAUTION;
            } else {
                // No memory or stale - no cat avoidance
                m.turnCatLoc = null;
                m.turnCatBand = CAT_BAND_NONE;
            }
        }

        // === SAFE CAT-SPOT SQUEAK ===
        // Broadcast cat position if within avoidance range but outside hearing range
        if (maybeSqueakCatSpotted(rc, m, roundNum, nearestCatAnyDistSq, nearestCatAnyLoc)) {
            m.broadcastedCat = true;
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

        // === READ GLOBAL ALERT (hive mind) ===
        readGlobalAlert(rc, m, roundNum);

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
        processIncomingSqueaks(rc, m, roundNum, nearestCatAnyDistSq);

        // === GUARD MODE: Check if enemy rats near our king (using pre-sensed data) ===
        if (nearbyEnemyRatNearKing != null && m.state != AttackState.ATTACK) {
            m.state = AttackState.GUARD;

            // Squeak for backup!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                rc.squeak(SQUEAK_ENEMY_ATTACKING_KING);
                m.lastSqueakRound = roundNum;
            }
        }

        // === CAT PANIC FLEE (only in panic range) ===
        // In PANIC band, hard flee and return. In DANGER/CAUTION, movement layer handles it.
        if (m.turnCatBand == CAT_BAND_PANIC && nearestCatAnyLoc != null) {
            runAttackerFleeFrom(rc, m, nearestCatAnyLoc);
            rc.setIndicatorString("PANIC FLEE | dist²:" + nearestCatAnyDistSq + " | " + m.state);
            return;
        }

        // === LOOK FOR ENEMY KING (using pre-sensed data) ===
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

        // === SQUEAK FOR HELP (uses pre-sensed nearbyAll) ===
        if (m.state == AttackState.SEEK || m.state == AttackState.ATTACK) {
            maybeSqueakHelp(rc, m, roundNum, nearbyAll, nearestCatAnyDistSq);
        }

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum, effectiveSymmetry, nearbyAllies); break;
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
        String roleStr = m.isLeader ? "LEADER" : "FOLLOW";
        int seqStr = m.latestFormationSeq >= 0 ? m.latestFormationSeq : (roundNum % FORMATION_SEQ_MODULO);
        String stuckStr = m.stuckCooldown > 0 ? " CD:" + m.stuckCooldown : "";
        String catBandStr = m.turnCatBand == CAT_BAND_PANIC ? "PANIC" :
                            m.turnCatBand == CAT_BAND_DANGER ? "DANGER" :
                            m.turnCatBand == CAT_BAND_CAUTION ? "CAUTION" : "-";
        String catDistStr = nearestCatAnyLoc != null ? String.valueOf(nearestCatAnyDistSq) : "-";
        String catCommStr = m.broadcastedCat ? "TX" : (m.adoptedCatLoc ? "RX" : "");
        rc.setIndicatorString(roleStr + " | " + m.state + " | Cat:" + catBandStr + "(" + catDistStr + ")" +
                (catCommStr.isEmpty() ? "" : catCommStr) +
                " | Sym:" + symStr + elimStr + " | Tgt:" + (m.currentTarget != null ? m.currentTarget : "none"));
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

    static void runSeek(RobotController rc, Mem m, int roundNum, int effectiveSymmetry,
                        RobotInfo[] nearbyAllies) throws GameActionException {
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
            formationMoveSeek(rc, m, m.targetKingLoc, roundNum, nearbyAllies);
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

        // Use formation movement instead of direct movement
        formationMoveSeek(rc, m, moveTarget, roundNum, nearbyAllies);
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

    // 8 cardinal/diagonal directions for swarm spread
    private static final Direction[] SWARM_OFFSETS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    private static final int SWARM_ENGAGE_RADIUS_SQ = 10;  // ~3 tile radius for counting

    static void runSwarm(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Check if local swarm target has expired
        if (m.localSwarmTarget != null && roundNum > m.localSwarmUntilRound) {
            m.localSwarmTarget = null;
        }

        // Prefer local swarm target (from MSG_HELP) if still active, else global alert rally
        MapLocation effectiveTarget = m.localSwarmTarget != null ? m.localSwarmTarget :
                                      (m.alertRallyLoc != null ? m.alertRallyLoc : m.swarmTarget);

        if (effectiveTarget == null || roundNum - m.swarmStartRound > 30) {
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            m.localSwarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        // === SINGLE SENSE CALL: Count allies vs enemies + check for enemy king ===
        RobotInfo[] nearby = rc.senseNearbyRobots(SWARM_ENGAGE_RADIUS_SQ);
        int allyCount = 0;
        int enemyCount = 0;
        RobotInfo closestEnemy = null;
        RobotInfo enemyKing = null;
        int closestEnemyDistSq = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getTeam() == enemyTeam) {
                if (r.getType() == UnitType.RAT_KING) {
                    enemyKing = r;  // Priority target
                } else if (r.getType() == UnitType.BABY_RAT) {
                    enemyCount++;
                    int d = here.distanceSquaredTo(r.getLocation());
                    if (d < closestEnemyDistSq) {
                        closestEnemyDistSq = d;
                        closestEnemy = r;
                    }
                }
            } else if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) {
                allyCount++;
            }
        }

        // Check for enemy king (always priority target)
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.state = AttackState.ATTACK;
            m.turnsSinceKingSeen = 0;
            confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
            return;
        }

        // If arrived at target area and no king found after a while, go back to seek
        int distToTarget = here.distanceSquaredTo(effectiveTarget);
        if (distToTarget <= 16 && roundNum - m.swarmStartRound > 15) {
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            m.localSwarmTarget = null;
            return;
        }

        // === ENGAGEMENT DECISION: Only fight if we have numbers ===
        boolean haveAdvantage = (allyCount >= enemyCount + 1) || enemyCount == 0;

        if (haveAdvantage && closestEnemy != null && closestEnemyDistSq <= 8) {
            // We have advantage - engage aggressively
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
            }
        }
        // If outnumbered and enemy is close, don't attack (conserve position)

        // === MOVEMENT: Spread toward rally, but kite if outnumbered ===
        Direction offsetDir = SWARM_OFFSETS[rc.getID() % 8];

        MapLocation spreadTarget;
        if (distToTarget > 25) {
            // Far away: converge directly
            spreadTarget = effectiveTarget;
        } else if (distToTarget > 4) {
            // Medium range: apply 1-tile offset
            spreadTarget = effectiveTarget.add(offsetDir);
        } else {
            // Very close: apply 2-tile offset
            spreadTarget = effectiveTarget.add(offsetDir).add(offsetDir);
        }

        // Clamp to map bounds
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();
        spreadTarget = new MapLocation(
            Math.max(1, Math.min(mapW - 2, spreadTarget.x)),
            Math.max(1, Math.min(mapH - 2, spreadTarget.y))
        );

        // If outnumbered and enemy is adjacent, kite away while drifting to rally
        if (!haveAdvantage && closestEnemy != null && closestEnemyDistSq <= 2) {
            // Kite: move away from enemy but bias toward rally
            Direction awayFromEnemy = closestEnemy.getLocation().directionTo(here);
            Direction toRally = here.directionTo(effectiveTarget);

            // Try kite directions: away, then angled toward rally
            Direction[] kiteDirs = {
                awayFromEnemy,
                awayFromEnemy.rotateLeft(),
                awayFromEnemy.rotateRight(),
                toRally,
                toRally.rotateLeft(),
                toRally.rotateRight()
            };

            for (Direction d : kiteDirs) {
                if (d == Direction.CENTER) continue;
                MapLocation next = here.add(d);
                // Don't kite into more enemies
                if (rc.canSenseLocation(next)) {
                    RobotInfo atNext = rc.senseRobotAtLocation(next);
                    if (atNext != null) continue;
                }
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.currentTarget = spreadTarget;
                    return;
                }
            }
            // Couldn't kite, hold position
            m.currentTarget = spreadTarget;
            return;
        }

        m.currentTarget = spreadTarget;
        attackerMoveToward(rc, m, spreadTarget);
    }

    // ==================== GLOBAL ALERT (HIVE MIND) ====================

    /**
     * Read global alert from shared array and respond if active.
     * Attackers only read, never write to shared array.
     */
    static void readGlobalAlert(RobotController rc, Mem m, int roundNum) throws GameActionException {
        int alertFlag = rc.readSharedArray(ALERT_FLAG);

        if (alertFlag == 0) {
            // No active alert
            m.alertRallyLoc = null;
            m.alertReason = 0;
            return;
        }

        int alertX = rc.readSharedArray(ALERT_X);
        int alertY = rc.readSharedArray(ALERT_Y);
        int alertExpires = rc.readSharedArray(ALERT_EXPIRES);
        int alertReason = rc.readSharedArray(ALERT_REASON);

        // Check if alert has expired (using mod 1000 for round tracking)
        int currentRoundMod = roundNum % 1000;
        int expiresDiff = alertExpires - currentRoundMod;
        if (expiresDiff < 0) expiresDiff += 1000;

        // Alert is expired if the difference is large (meaning we passed the expiry)
        // We consider it valid if expiry is within 100 rounds ahead
        boolean alertActive = expiresDiff > 0 && expiresDiff < 100;

        if (alertActive && alertX > 0 && alertY > 0) {
            // Store rally location and reason
            m.alertRallyLoc = new MapLocation(alertX, alertY);
            m.alertReason = alertReason;

            // If not already attacking a known king, respond to alert
            if (m.state != AttackState.ATTACK && m.state != AttackState.GUARD) {
                m.swarmTarget = m.alertRallyLoc;
                m.swarmStartRound = roundNum;
                m.state = AttackState.SWARM;
            }
        } else {
            // Alert expired or invalid
            m.alertRallyLoc = null;
            m.alertReason = 0;
        }
    }

    // ==================== LOCAL MESH SWARM ====================

    private static final int HELP_SQUEAK_COOLDOWN = 10;      // Rounds between help squeaks
    private static final int IMMEDIATE_THREAT_DIST_SQ = 8;   // Enemy within sqrt(8) ~= 2.8 tiles
    private static final int CAT_HEARING_DIST_SQ = 16;       // Cat within 4 tiles can hear
    private static final int CAT_SPOT_SQUEAK_COOLDOWN = 10;  // Rounds between cat-spot squeaks

    /**
     * Squeak for help when an enemy rat is an immediate threat.
     * Called once per turn during SEEK/ATTACK states.
     *
     * Triggers when:
     * - Enemy baby rat within dist² <= 8
     * - No cat within hearing range (dist <= 4)
     * - Cooldown has expired (N=10 rounds)
     *
     * @param nearbyAll Pre-sensed nearby robots (reused from run())
     * @param nearestCatAnyDistSq Distance to nearest cat (or MAX_VALUE if none)
     */
    static void maybeSqueakHelp(RobotController rc, Mem m, int roundNum,
                                 RobotInfo[] nearbyAll, int nearestCatAnyDistSq) throws GameActionException {
        // Check cooldown
        if (roundNum - m.lastHelpSqueakRound < HELP_SQUEAK_COOLDOWN) {
            return;
        }

        // Check for cats in hearing range - don't squeak if cat nearby (avoid beaconing)
        // Uses pre-computed nearest cat distance instead of new sense call
        if (nearestCatAnyDistSq <= CAT_HEARING_DIST_SQ) {
            return;
        }

        // Check for immediate enemy threat using pre-sensed nearbyAll
        MapLocation here = rc.getLocation();
        Team enemy = rc.getTeam().opponent();
        boolean enemyThreat = false;

        for (RobotInfo r : nearbyAll) {
            if (r.getTeam() == enemy && r.getType() == UnitType.BABY_RAT) {
                int distSq = here.distanceSquaredTo(r.getLocation());
                if (distSq <= IMMEDIATE_THREAT_DIST_SQ) {
                    enemyThreat = true;
                    break;
                }
            }
        }

        if (enemyThreat) {
            // Squeak MSG_HELP encoded with our location
            int encodedMsg = encodeMsg(MSG_HELP, here.x, here.y);
            rc.squeak(encodedMsg);
            m.lastHelpSqueakRound = roundNum;
        }
    }

    /**
     * Squeak cat position when safe to do so.
     * Called once per turn after sensing cats.
     *
     * Triggers when:
     * - Cat sensed within avoidance radius (CAUTION or DANGER band)
     * - Nearest cat is outside cat-hearing range (distSq > 16)
     * - Cooldown has passed (10 rounds)
     *
     * @param nearestCatAnyDistSq Distance squared to nearest cat (or MAX_VALUE if none)
     * @param nearestCatAnyLoc Location of nearest cat (or null if none)
     * @return true if we broadcasted a cat-spot squeak
     */
    static boolean maybeSqueakCatSpotted(RobotController rc, Mem m, int roundNum,
                                          int nearestCatAnyDistSq, MapLocation nearestCatAnyLoc)
            throws GameActionException {
        // No cat sensed
        if (nearestCatAnyLoc == null) return false;

        // Only squeak if cat is in avoidance radius (CAUTION or DANGER)
        if (nearestCatAnyDistSq > CAT_CAUTION_DIST_SQ) return false;

        // Safety check: only squeak if cat is outside hearing range
        if (nearestCatAnyDistSq <= CAT_HEARING_DIST_SQ) return false;

        // Cooldown check
        if (roundNum - m.lastCatSqueakRound < CAT_SPOT_SQUEAK_COOLDOWN) return false;

        // Safe to squeak cat location
        int encodedMsg = encodeMsg(MSG_CAT_SPOTTED, nearestCatAnyLoc.x, nearestCatAnyLoc.y);
        rc.squeak(encodedMsg);
        m.lastCatSqueakRound = roundNum;
        return true;
    }

    // ==================== SQUEAK COMMUNICATION ====================

    private static final int LOCAL_SWARM_TTL = 15;  // Rounds to swarm after receiving MSG_HELP
    private static final int FORMATION_FRESHNESS_ROUNDS = 2;  // Only forward if msg is this fresh
    private static final int CAT_SILENCE_DIST_SQ = 16;        // Don't forward if cat within this dist²

    /**
     * Wrap-safe sequence comparison for formation messages.
     * Returns true if seqA is "newer" than seqB (handles modulo 128 wraparound).
     * Uses half-window comparison: A is newer if (A - B + 128) % 128 < 64
     */
    static boolean isNewerSeq(int seqA, int seqB) {
        if (seqB < 0) return true;  // No previous seq, A is newer
        int diff = (seqA - seqB + 128) % 128;
        return diff > 0 && diff < 64;
    }

    /**
     * @param nearestCatAnyDistSq Distance to nearest cat (or MAX_VALUE if none) - for cat silence check
     */
    static void processIncomingSqueaks(RobotController rc, Mem m, int roundNum, int nearestCatAnyDistSq) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);
        MapLocation here = rc.getLocation();

        // Track if we received a new formation message this turn (for forwarding)
        int newFormationSeq = -1;
        Direction newFormationDir = null;
        int newFormationMsgRound = -1;

        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();
            int msgRound = msg.getRound();

            // Skip if too far away
            if (here.distanceSquaredTo(senderLoc) > SWARM_RESPONSE_DISTANCE_SQ) {
                continue;
            }

            // === CHECK FOR FORMATION MESSAGE ===
            if (isFormationMsg(content)) {
                int seq = decodeFormationSeq(content);
                int dirOrdinal = decodeFormationDir(content);

                // Check if this is newer than what we've seen (wrap-safe)
                boolean isNewer = isNewerSeq(seq, m.latestFormationSeq);

                // Also accept if same seq but more recent round (tie-breaker)
                boolean isSameSeqButFresher = (seq == m.latestFormationSeq) &&
                                               (msgRound > m.formationSeenRound);

                if (isNewer || isSameSeqButFresher) {
                    // Update stored formation info
                    m.latestFormationSeq = seq;
                    m.formationSeenRound = msgRound;
                    m.lastFormationSourceLoc = senderLoc;
                    m.lastFormationDir = Direction.values()[dirOrdinal];

                    // Track for potential forwarding (only if fresh enough)
                    if (roundNum - msgRound <= FORMATION_FRESHNESS_ROUNDS) {
                        newFormationSeq = seq;
                        newFormationDir = m.lastFormationDir;
                        newFormationMsgRound = msgRound;
                    }
                }
                continue;
            }

            // === TRY DECODING AS ENCODED MESSAGE (MSG_HELP, MSG_KING_DANGER, MSG_CAT_SPOTTED, etc.) ===
            int msgType = decodeType(content);
            if (msgType == MSG_HELP) {
                // Decode location from message
                int helpX = decodeX(content);
                int helpY = decodeY(content);
                MapLocation helpLoc = new MapLocation(helpX, helpY);

                // Respond to help request - set local swarm target with TTL
                // Always refresh TTL on new HELP, update target if newer/closer
                if (m.state != AttackState.ATTACK && m.state != AttackState.GUARD) {
                    m.localSwarmTarget = helpLoc;
                    m.localSwarmUntilRound = roundNum + LOCAL_SWARM_TTL;
                    m.swarmTarget = helpLoc;
                    m.swarmStartRound = roundNum;
                    m.state = AttackState.SWARM;
                }
                continue;
            }

            if (msgType == MSG_CAT_SPOTTED) {
                // Decode cat location from message
                int catX = decodeX(content);
                int catY = decodeY(content);
                MapLocation reportedCatLoc = new MapLocation(catX, catY);

                // Adopt as memory if we don't have fresher info
                // (treat received cat info as "seen" but with slight age penalty)
                int roundsSinceOurSighting = roundNum - m.lastCatSeenRound;
                if (m.lastCatLoc == null || roundsSinceOurSighting > 2) {
                    // Our info is stale (>2 rounds old) or non-existent, adopt reported loc
                    m.lastCatLoc = reportedCatLoc;
                    // Set seen round slightly in the past so direct sightings take priority
                    m.lastCatSeenRound = roundNum - 1;
                    m.adoptedCatLoc = true;  // Flag for debug indicator
                }
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

            // Handle legacy squeak types (non-encoded)
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

        // === FORMATION MESSAGE FORWARDING ===
        // Forward if we received a new seq we haven't forwarded yet
        if (newFormationSeq >= 0 && newFormationDir != null) {
            // Check if we already forwarded this seq
            if (newFormationSeq != m.lastSeqForwarded) {
                // Freshness check already done above when setting newFormationSeq

                // Cat silence check: don't forward if cat nearby (avoid giving away position)
                // Uses pre-computed nearest cat distance instead of new sense call
                boolean catNearby = (nearestCatAnyDistSq <= CAT_SILENCE_DIST_SQ);

                if (!catNearby) {
                    // Forward the same formation message
                    int forwardMsg = encodeFormation(newFormationSeq, newFormationDir);
                    rc.squeak(forwardMsg);
                    m.lastSeqForwarded = newFormationSeq;
                }
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

    // ==================== LOCAL LEADER SELECTION ====================

    private static final int LEADER_SENSE_RADIUS_SQ = 16;  // dist² for leader election group
    private static final int LEADER_STUCK_THRESHOLD = 4;   // Turns stuck before stepping down
    private static final int LEADER_COOLDOWN_ROUNDS = 8;   // Rounds to wait before re-attempting leadership

    /**
     * Check if this rat is the local leader for the given objective.
     * Leader is chosen by: smallest distance to objective, tie-break by smallest ID.
     *
     * STUCK HANDLING: If this rat is on cooldown (was stuck as leader), it excludes
     * itself from leadership consideration to let others lead.
     *
     * @param rc RobotController
     * @param m Mem state (for stuck cooldown)
     * @param objectiveLoc The objective location
     * @param nearbyAllies Pre-sensed nearby allies (to save bytecode)
     * @return true if this rat is the leader
     */
    static boolean amILeader(RobotController rc, Mem m, MapLocation objectiveLoc, RobotInfo[] nearbyAllies)
            throws GameActionException {
        MapLocation here = rc.getLocation();
        int myId = rc.getID();
        int myDistSq = here.distanceSquaredTo(objectiveLoc);

        // If on cooldown from being stuck, exclude self from leadership
        boolean selfExcluded = m.stuckCooldown > 0;

        int bestDistSq = selfExcluded ? Integer.MAX_VALUE : myDistSq;
        int bestId = selfExcluded ? Integer.MAX_VALUE : myId;
        boolean selfIsBest = !selfExcluded;

        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;

            int allyDistSq = ally.getLocation().distanceSquaredTo(objectiveLoc);
            int allyId = ally.getID();

            // Compare: smaller dist wins, tie-break by smaller ID
            boolean isBetter = (allyDistSq < bestDistSq) ||
                               (allyDistSq == bestDistSq && allyId < bestId);

            if (isBetter) {
                bestDistSq = allyDistSq;
                bestId = allyId;
                selfIsBest = false;
            }
        }

        return selfIsBest;
    }

    // ==================== FORMATION MOVEMENT ====================

    private static final int FORMATION_SEQ_MODULO = 128;  // seq = roundNum % 128

    /**
     * Formation-based movement for SEEK state.
     * Leaders move toward objective and broadcast formation messages.
     * Followers try to stay adjacent to the formation source (leader/forwarder).
     *
     * @param rc RobotController
     * @param m Mem state
     * @param objectiveLoc The objective location to move toward
     * @param roundNum Current round number
     * @return true if movement was successful
     */
    /**
     * Formation-based movement for SEEK state.
     * Leaders move toward objective and broadcast formation messages.
     * Followers stay adjacent to formation source.
     *
     * STUCK HANDLING: If leader fails to move for LEADER_STUCK_THRESHOLD turns,
     * they enter cooldown and let another rat lead.
     *
     * @param rc RobotController
     * @param m Mem state
     * @param objectiveLoc The objective location to move toward
     * @param roundNum Current round number
     * @param nearbyAllies Pre-sensed nearby allies (saves bytecode)
     * @return true if movement was successful
     */
    static boolean formationMoveSeek(RobotController rc, Mem m, MapLocation objectiveLoc, int roundNum,
                                     RobotInfo[] nearbyAllies) throws GameActionException {

        MapLocation here = rc.getLocation();

        // Decrement stuck cooldown each turn
        if (m.stuckCooldown > 0) {
            m.stuckCooldown--;
        }

        // === LEADER BEHAVIOR ===
        if (amILeader(rc, m, objectiveLoc, nearbyAllies)) {
            m.isLeader = true;  // Track for indicator string

            // Leaders move toward objective
            Direction desiredDir = here.directionTo(objectiveLoc);
            if (desiredDir == Direction.CENTER) {
                desiredDir = Direction.NORTH; // Fallback if already at objective
            }

            // Attempt to move
            MapLocation oldLoc = here;
            boolean moved = attackerMoveToward(rc, m, objectiveLoc);

            // Track stuck status
            if (moved) {
                m.leaderStuckCount = 0;  // Reset on successful move
            } else {
                m.leaderStuckCount++;
                // If stuck too long, enter cooldown and let someone else lead
                if (m.leaderStuckCount >= LEADER_STUCK_THRESHOLD) {
                    m.stuckCooldown = LEADER_COOLDOWN_ROUNDS;
                    m.leaderStuckCount = 0;
                }
            }

            // Squeak formation message (always squeak even if move failed, to keep followers informed)
            int seq = roundNum % FORMATION_SEQ_MODULO;
            Direction dirToSqueak = moved ? rc.getDirection() : desiredDir;
            int formationMsg = encodeFormation(seq, dirToSqueak);
            rc.squeak(formationMsg);

            return moved;
        }

        // === FOLLOWER BEHAVIOR ===
        m.isLeader = false;  // Track for indicator string
        m.leaderStuckCount = 0;  // Reset stuck count when not leader

        // Check if we have a fresh formation source
        boolean hasFreshSource = m.lastFormationSourceLoc != null &&
                                 (roundNum - m.formationSeenRound) <= FORMATION_FRESHNESS_ROUNDS;

        if (hasFreshSource) {
            // Move to become adjacent to the formation source while making progress
            return moveAdjacentToSource(rc, m, m.lastFormationSourceLoc, objectiveLoc, roundNum);
        } else {
            // No fresh source - regroup by moving toward nearest allied attacker
            return moveTowardNearestAlly(rc, m, objectiveLoc, nearbyAllies);
        }
    }

    /**
     * Move to become adjacent to the formation source location.
     *
     * FOLLOWER BEHAVIOR:
     * - If more than 2 tiles away from parent: HARD-FOLLOW (ignore objective, go straight to parent)
     * - If adjacent: SLIDE while maintaining adjacency (use deterministic slot based on id%8)
     * - Deterministic slot selection ensures multiple followers spread around the parent
     */
    static boolean moveAdjacentToSource(RobotController rc, Mem m, MapLocation sourceLoc,
                                        MapLocation objectiveLoc, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();
        int distToParent = here.distanceSquaredTo(sourceLoc);

        // Distance > 2 tiles means distSq > 4 (e.g., distSq=5 is ~2.2 tiles away)
        boolean tooFarFromParent = distToParent > 4;

        // === HARD-FOLLOW MODE: More than 2 tiles away from parent ===
        if (tooFarFromParent) {
            // Ignore objective completely, just get to the parent
            return attackerMoveToward(rc, m, sourceLoc);
        }

        // === DETERMINISTIC SLOT SELECTION ===
        // Use id % 8 to pick a preferred adjacent slot around the parent
        int preferredSlot = rc.getID() % 8;
        Direction preferredDir = Direction.values()[preferredSlot]; // 0-7 maps to N, NE, E, SE, S, SW, W, NW

        // === ADJACENT BEHAVIOR: Slide while maintaining adjacency ===
        if (here.isAdjacentTo(sourceLoc)) {
            // Try to slide in formation direction while staying adjacent to parent
            if (m.lastFormationDir != null && m.lastFormationDir != Direction.CENTER) {
                // Try formation direction variations that keep us adjacent to parent
                Direction[] slideDirs = {
                    m.lastFormationDir,
                    m.lastFormationDir.rotateLeft(),
                    m.lastFormationDir.rotateRight()
                };

                for (Direction slideDir : slideDirs) {
                    MapLocation afterSlide = here.add(slideDir);
                    // Only slide if we stay adjacent to the parent's NEXT position (parent + formationDir)
                    MapLocation parentNext = sourceLoc.add(m.lastFormationDir);
                    if (afterSlide.isAdjacentTo(parentNext) || afterSlide.isAdjacentTo(sourceLoc)) {
                        if (tryMoveDirAvoidRecent(rc, m, slideDir, here)) {
                            return true;
                        }
                    }
                }
            }

            // If can't slide in formation dir, try to move to our preferred slot
            MapLocation preferredLoc = sourceLoc.add(preferredDir);
            if (!here.equals(preferredLoc) && here.isAdjacentTo(preferredLoc)) {
                Direction toPreferred = here.directionTo(preferredLoc);
                if (tryMoveDirAvoidRecent(rc, m, toPreferred, here)) {
                    return true;
                }
            }

            // Already adjacent, stay put (maintain adjacency)
            return false;
        }

        // === NOT ADJACENT YET: Move to preferred slot or closest adjacent tile ===
        // First, try to reach our deterministic preferred slot
        MapLocation preferredLoc = sourceLoc.add(preferredDir);

        // Check if preferred slot is reachable and valid
        if (rc.canSenseLocation(preferredLoc)) {
            MapInfo prefInfo = rc.senseMapInfo(preferredLoc);
            RobotInfo atPref = rc.senseRobotAtLocation(preferredLoc);

            if (!prefInfo.isWall() && atPref == null) {
                // Move toward our preferred slot
                Direction toPreferred = here.directionTo(preferredLoc);
                if (tryMoveDirAvoidRecent(rc, m, toPreferred, here)) {
                    return true;
                }
            }
        }

        // Preferred slot blocked - find any adjacent slot, prioritizing by slot distance from preferred
        Direction toSource = here.directionTo(sourceLoc);

        // Try slots in order of distance from our preferred slot
        for (int offset = 0; offset < 8; offset++) {
            int slot = (preferredSlot + offset) % 8;
            Direction slotDir = Direction.values()[slot];
            MapLocation slotLoc = sourceLoc.add(slotDir);

            if (!rc.canSenseLocation(slotLoc)) continue;

            MapInfo info = rc.senseMapInfo(slotLoc);
            if (info.isWall()) continue;

            RobotInfo atSlot = rc.senseRobotAtLocation(slotLoc);
            if (atSlot != null) continue;

            // Move toward this slot
            Direction toSlot = here.directionTo(slotLoc);
            if (tryMoveDirAvoidRecent(rc, m, toSlot, here)) {
                return true;
            }
        }

        // Fallback: just move toward source directly
        return attackerMoveToward(rc, m, sourceLoc);
    }

    /**
     * Move toward the nearest allied attacker to regroup.
     * Called when no fresh formation source is available.
     *
     * @param nearbyAllies Pre-sensed nearby allies (saves bytecode)
     */
    static boolean moveTowardNearestAlly(RobotController rc, Mem m, MapLocation objectiveLoc,
                                         RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Find nearest allied baby rat (attacker) from pre-sensed list
        RobotInfo nearestAlly = null;
        int nearestDistSq = Integer.MAX_VALUE;

        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;
            int distSq = here.distanceSquaredTo(ally.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearestAlly = ally;
            }
        }

        if (nearestAlly != null) {
            // Move toward the nearest ally, but bias toward objective
            MapLocation allyLoc = nearestAlly.getLocation();
            Direction toAlly = here.directionTo(allyLoc);
            Direction toObj = here.directionTo(objectiveLoc);

            // If ally is between us and objective, go toward ally
            // Otherwise, compromise between ally and objective
            if (nearestDistSq > 4) {
                // Try directions that get us closer to both
                Direction[] tryDirs = {toAlly, toObj, toAlly.rotateLeft(), toAlly.rotateRight(),
                                       toObj.rotateLeft(), toObj.rotateRight()};
                for (Direction d : tryDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                        return true;
                    }
                }
            }
        }

        // No ally found or couldn't move toward them - fall back to moving toward objective
        return attackerMoveToward(rc, m, objectiveLoc);
    }

    // ==================== SENSING ====================

    /**
     * Flee from a cat location in PANIC mode.
     * Prioritizes moves that escape pounce range (distSq > 9).
     * Falls back to any move away if no safe escape exists.
     */
    static void runAttackerFleeFrom(RobotController rc, Mem m, MapLocation catLoc) throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(catLoc).opposite();

        // Build flee direction priority: away, then laterals, then diagonals
        Direction[] fleeDirs = {
            away,
            away.rotateLeft(),
            away.rotateRight(),
            away.rotateLeft().rotateLeft(),
            away.rotateRight().rotateRight()
        };

        // First pass: find a move that escapes pounce range AND avoids oscillation
        for (Direction d : fleeDirs) {
            MapLocation next = here.add(d);
            // Check anti-oscillation
            if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }
            int nextCatDistSq = next.distanceSquaredTo(catLoc);
            if (nextCatDistSq > CAT_PANIC_DIST_SQ) {
                // This move escapes pounce range
                if (rc.getDirection() != d && rc.canTurn(d)) rc.turn(d);
                if (rc.canMove(d)) {
                    rc.move(d);
                    m.prev2 = m.prev1;
                    m.prev1 = here;
                    return;
                }
            }
        }

        // Second pass: any move that increases distance from cat (even if still in pounce range)
        int currentCatDistSq = here.distanceSquaredTo(catLoc);
        for (Direction d : fleeDirs) {
            MapLocation next = here.add(d);
            if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }
            int nextCatDistSq = next.distanceSquaredTo(catLoc);
            if (nextCatDistSq > currentCatDistSq) {
                if (rc.getDirection() != d && rc.canTurn(d)) rc.turn(d);
                if (rc.canMove(d)) {
                    rc.move(d);
                    m.prev2 = m.prev1;
                    m.prev1 = here;
                    return;
                }
            }
        }

        // Last resort: any valid move (including oscillation risk)
        if (rc.getDirection() != away && rc.canTurn(away)) rc.turn(away);
        if (rc.canMoveForward()) {
            rc.moveForward();
            m.prev2 = m.prev1;
            m.prev1 = here;
        }
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

    /**
     * Score a direction for cat-aware movement.
     * Higher score = better direction. Considers both target progress and cat avoidance.
     *
     * @param here Current location
     * @param dir Direction to evaluate
     * @param target Target location
     * @param catLoc Cat location (can be null)
     * @param catBand Current cat distance band
     * @return Score (higher is better), or Integer.MIN_VALUE if direction is invalid
     */
    static int scoreCatAwareDir(MapLocation here, Direction dir, MapLocation target,
                                 MapLocation catLoc, int catBand) {
        if (dir == null || dir == Direction.CENTER) return Integer.MIN_VALUE;

        MapLocation next = here.add(dir);
        int score = 0;

        // Base score: progress toward target (lower dist = higher score)
        int currentDistSq = here.distanceSquaredTo(target);
        int nextDistSq = next.distanceSquaredTo(target);
        score += (currentDistSq - nextDistSq) * 10;  // +10 per distSq closer

        // Cat avoidance: penalize getting closer to cat
        if (catLoc != null && catBand > CAT_BAND_NONE) {
            int currentCatDistSq = here.distanceSquaredTo(catLoc);
            int nextCatDistSq = next.distanceSquaredTo(catLoc);

            // DANGER band: treat cat as soft obstacle
            if (catBand == CAT_BAND_DANGER) {
                // Hard penalty for entering pounce range
                if (nextCatDistSq <= CAT_PANIC_DIST_SQ) {
                    score -= 200;
                }
                // Heavy penalty for decreasing distance to cat
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 60;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 30;
                }
                // Lateral bonus: prefer moves perpendicular to cat direction
                Direction toCat = here.directionTo(catLoc);
                if (toCat != Direction.CENTER) {
                    Direction leftPerp = toCat.rotateLeft().rotateLeft();
                    Direction rightPerp = toCat.rotateRight().rotateRight();
                    if (dir == leftPerp || dir == rightPerp) {
                        score += 40;  // Lateral move bonus
                    } else if (dir == leftPerp.rotateLeft() || dir == rightPerp.rotateRight()) {
                        score += 20;  // Diagonal-lateral bonus
                    }
                }
            } else {
                // CAUTION band: mild steering
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 15;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 8;
                }
            }
        }

        return score;
    }

    /**
     * Reorder direction array based on cat-aware scoring.
     * Returns a new array with directions sorted by score (best first).
     * Reuses the input array to avoid allocation.
     */
    static void sortDirsByCatScore(Direction[] dirs, MapLocation here, MapLocation target,
                                    MapLocation catLoc, int catBand) {
        // Simple insertion sort (small array, low bytecode)
        for (int i = 1; i < dirs.length; i++) {
            Direction key = dirs[i];
            int keyScore = scoreCatAwareDir(here, key, target, catLoc, catBand);
            int j = i - 1;
            while (j >= 0 && scoreCatAwareDir(here, dirs[j], target, catLoc, catBand) < keyScore) {
                dirs[j + 1] = dirs[j];
                j--;
            }
            dirs[j + 1] = key;
        }
    }

    static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        // In DANGER band, reject moves that land in pounce range
        boolean rejectPounce = (m.turnCatBand == CAT_BAND_DANGER) && (m.turnCatLoc != null);

        if (!m.bugging) {
            Direction[] tryDirs = {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateRight(),
                    desired.rotateLeft().rotateLeft(),
                    desired.rotateRight().rotateRight()
            };

            // Apply cat-aware reordering in DANGER or CAUTION bands
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                sortDirsByCatScore(tryDirs, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (Direction d : tryDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here, rejectPounce)) {
                    m.failedMoves = 0;
                    return true;
                }
            }

            // Fallback pass (DANGER only): if all safe moves blocked, allow pounce-range
            // (better to risk pounce than be completely stuck)
            if (rejectPounce) {
                for (Direction d : tryDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here, false)) {
                        m.failedMoves = 0;
                        return true;
                    }
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

            // Apply cat-aware reordering in DANGER or CAUTION bands (even while bugging)
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                sortDirsByCatScore(bugDirs, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (Direction d : bugDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here, rejectPounce)) {
                    m.bugTurns++;
                    m.failedMoves = 0;

                    if (rc.canMove(desired) || m.bugTurns > 12) {
                        m.bugging = false;
                        m.bugTurns = 0;
                    }
                    return true;
                }
            }

            // Fallback pass (DANGER only): allow pounce-range if stuck
            if (rejectPounce) {
                for (Direction d : bugDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here, false)) {
                        m.bugTurns++;
                        m.failedMoves = 0;

                        if (rc.canMove(desired) || m.bugTurns > 12) {
                            m.bugging = false;
                            m.bugTurns = 0;
                        }
                        return true;
                    }
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

    /**
     * Try to move in a direction, avoiding recent positions (anti-oscillation)
     * and respecting cat danger zones.
     *
     * @param rc RobotController
     * @param m Mem state (has prev1/prev2 for anti-oscillation and cat info)
     * @param dir Direction to try
     * @param oldLoc Current location before move
     * @param rejectPounceRange If true, reject moves that land in pounce range (distSq <= 9)
     * @return true if move succeeded
     */
    static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc,
                                          boolean rejectPounceRange)
            throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation next = oldLoc.add(dir);

        // Anti-oscillation: reject moves to recent positions
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // Cat pounce-range check (used in PANIC/DANGER bands)
        if (rejectPounceRange && m.turnCatLoc != null) {
            int nextCatDistSq = next.distanceSquaredTo(m.turnCatLoc);
            if (nextCatDistSq <= CAT_PANIC_DIST_SQ) {
                return false;  // Would land in pounce range
            }
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

        // If blocked by dirt, dig it
        if (rc.canSenseLocation(next)) {
            MapInfo info = rc.senseMapInfo(next);
            if (info.isDirt() && rc.canRemoveDirt(next)) {
                rc.removeDirt(next);
                return false;  // Didn't move, but dug the dirt - will move next turn
            }
        }

        return false;
    }

    /** Convenience overload - no pounce range rejection */
    static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc)
            throws GameActionException {
        return tryMoveDirAvoidRecent(rc, m, dir, oldLoc, false);
    }
}
