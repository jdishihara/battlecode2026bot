package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.Direction;
import battlecode.common.MapLocation;

/**
 * Per-robot memory and state tracking for attacker rats.
 */
public class AttackerMemory {

    // === SYMMETRY TYPES ===
    public static final int SYM_UNKNOWN = 0;
    public static final int SYM_ROTATIONAL = 1;   // (w-1-x, h-1-y)
    public static final int SYM_HORIZONTAL = 2;   // (w-1-x, y) - flip across vertical axis
    public static final int SYM_VERTICAL = 3;     // (x, h-1-y) - flip across horizontal axis

    // === CAT DISTANCE BANDS ===
    public static final int CAT_PANIC_DIST_SQ = 9;     // ~3 tiles - hard flee
    public static final int CAT_DANGER_DIST_SQ = 25;   // ~5 tiles - steer away strongly
    public static final int CAT_CAUTION_DIST_SQ = 64;  // ~8 tiles - mild steering
    public static final int CAT_BAND_NONE = 0;
    public static final int CAT_BAND_CAUTION = 1;
    public static final int CAT_BAND_DANGER = 2;
    public static final int CAT_BAND_PANIC = 3;

    // === CAT MEMORY TTL ===
    public static final int CAT_MEMORY_TTL = 8;        // Rounds to remember cat after losing sight
    public static final int CAT_MEMORY_DANGER_TTL = 2; // Very recent memory uses DANGER band

    // === FORMATION SQUEAK FORMAT ===
    // Base number chosen to avoid collision with existing squeaks (1-7, 6+n*10, and encoded msgs up to ~32k)
    // Format: FORMATION_BASE + (seq * 8) + dirOrdinal
    // - seq: sequence number 0-127 (wraps)
    // - dirOrdinal: 0-7 for 8 cardinal/diagonal directions
    public static final int FORMATION_BASE = 100000;
    public static final int FORMATION_MAX = FORMATION_BASE + (128 * 8);  // Upper bound for formation msgs

    // === CLUMP MODE CONSTANTS ===
    // Controls formation clumping behavior for coordinated movement
    public static final int CLUMP_RADIUS_SQ = 13;               // ~3.6 tiles - ideal clump radius
    public static final int CLUMP_HARD_FOLLOW_RADIUS_SQ = 25;   // ~5 tiles - max distance before hard-follow
    public static final int CLUMP_TOO_STRETCHED_MAX_DIST_SQ = 40; // leader->ally max dist² threshold
    public static final int CLUMP_WAIT_MAX_TURNS = 1;           // leader can pause at most 1 turn when stretched

    // === CLUMP DIG CONSTANTS ===
    // Controls dirt digging at choke points to preserve clump shape
    public static final int CLUMP_DIG_COOLDOWN = 8;             // Rounds between clump digs per rat
    public static final int CLUMP_DIG_MIN_ALLIES = 4;           // Min allied baby rats nearby to justify digging
    public static final int CLUMP_DIG_ALLY_RADIUS_SQ = 16;      // Radius to count allies (4 tiles)
    public static final int CLUMP_DIG_MAX_FRONT_RATS = 2;       // Only top N closest rats may dig

    // === LEADER SELECTION CONSTANTS ===
    public static final int LEADER_SENSE_RADIUS_SQ = 16;  // dist² for leader election group
    public static final int LEADER_STUCK_THRESHOLD = 4;   // Turns stuck before stepping down
    public static final int LEADER_COOLDOWN_ROUNDS = 8;   // Rounds to wait before re-attempting leadership

    // === FORMATION CONSTANTS ===
    public static final int FORMATION_SEQ_MODULO = 128;  // seq = roundNum % 128

    // === ATTACK STATE MACHINE ===
    public enum AttackState { SEEK, ATTACK, GUARD, SWARM }

    // === PER-ROBOT MEMORY ===
    public static class Mem {
        public MapLocation kingLocation = null;

        public AttackState state = AttackState.SEEK;
        public MapLocation targetKingLoc = null;      // Confirmed enemy king location
        public int turnsSinceKingSeen = 0;
        public MapLocation currentTarget = null;       // Current movement target
        public int turnsAtTarget = 0;

        // BUG nav memory
        public boolean bugging = false;
        public boolean bugLeft = false;
        public int bugTurns = 0;
        public int failedMoves = 0;
        public MapLocation prev1 = null;
        public MapLocation prev2 = null;

        public MapLocation mapCenter = null;

        // Combat coordination
        public int lastSqueakRound = -100;
        public int lastTrapRound = -100;
        public MapLocation swarmTarget = null;
        public int swarmStartRound = -100;

        // Global alert (from shared array)
        public MapLocation alertRallyLoc = null;
        public int alertReason = 0;

        // Local mesh squeak cooldown
        public int lastHelpSqueakRound = -100;

        // Local swarm response (from nearby MSG_HELP squeaks)
        public MapLocation localSwarmTarget = null;
        public int localSwarmUntilRound = -1;

        // === FORMATION MOVEMENT TRACKING ===
        public int latestFormationSeq = -1;           // Latest formation sequence number seen
        public int formationSeenRound = -1;           // Round when formation msg was seen
        public MapLocation lastFormationSourceLoc = null;  // Location of rat that sent formation msg
        public Direction lastFormationDir = null;     // Direction from formation message
        public int lastSeqForwarded = -1;             // Last sequence number we forwarded
        public boolean isLeader = false;              // True if this rat is the local leader this turn
        public int leaderStuckCount = 0;              // Turns stuck as leader (resets on move)
        public int stuckCooldown = 0;                 // Rounds to wait before re-attempting leadership

        // === CLUMP MODE ANCHOR TRACKING ===
        public MapLocation formationAnchorLoc = null; // Current anchor location (leader's position)
        public int formationAnchorId = -1;            // ID of anchor rat (-1 if unknown/unavailable)
        public int leaderWaitTurns = 0;               // Turns leader has waited for stretched followers

        // === CLUMP DIG COOLDOWN ===
        public int lastClumpDigRound = -100;          // Round when we last dug for clump (prevents spam)

        // === CAT MEMORY ===
        public MapLocation lastCatLoc = null;         // Last known cat location (any range)
        public int lastCatSeenRound = -100;           // Round when cat was last seen
        public int lastCatSqueakRound = -100;         // For cat-spot squeak cooldown
        public boolean adoptedCatLoc = false;         // True if lastCatLoc came from squeak (for debug)
        public boolean broadcastedCat = false;        // True if we broadcasted cat this turn (for debug)

        // === CURRENT TURN CAT INFO (for movement layer) ===
        public MapLocation turnCatLoc = null;         // Cat location this turn (null if none)
        public int turnCatBand = CAT_BAND_NONE;       // Distance band this turn

        // === CLUSTERED SYMMETRY SEARCH ===
        public MapLocation[] possibleEnemyKingLocs = null;  // [rot, horiz, vert] - indices 0,1,2
        public int localSymmetryEliminated = 0;             // Local bitmask of eliminated symmetries
        public int lastSymmetryChecked = 0;                 // Track which symmetry we last checked (for timeout reset)
        public boolean reachedCurrentTarget = false;        // Have we arrived at current symmetry target?
        public int roundsSearchingCurrentSymmetry = 0;      // How long we've searched current symmetry

        // === REUSABLE DIRECTION BUFFERS (avoid per-turn allocation) ===
        public final Direction[] buf3 = new Direction[3];
        public final Direction[] buf5 = new Direction[5];
        public final Direction[] buf6 = new Direction[6];
        public final Direction[] buf8 = new Direction[8];

        public Mem(int id) {
            bugLeft = ((id / 2) % 2 == 0);
        }
    }

    // Static per-robot state (each robot runs in its own JVM)
    private static Mem m;

    public static Mem mem(int id) {
        if (m == null) {
            m = new Mem(id);
        }
        return m;
    }
}
