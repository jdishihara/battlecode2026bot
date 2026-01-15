package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.Direction;

/**
 * State enums and constants for attacker rats.
 */
public class AttackerState {

    // ===== ATTACKER STATE MACHINE =====
    public enum AttackStateEnum { SEEK, ATTACK, GUARD, SWARM }

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
    public static final int FORMATION_SEQ_MODULO = 128;  // seq = roundNum % 128
    public static final int FORMATION_FRESHNESS_ROUNDS = 2;  // Only forward if msg is this fresh

    // === LEADER ELECTION ===
    public static final int LEADER_SENSE_RADIUS_SQ = 16;  // dist² for leader election group
    public static final int LEADER_STUCK_THRESHOLD = 4;   // Turns stuck before stepping down
    public static final int LEADER_COOLDOWN_ROUNDS = 8;   // Rounds to wait before re-attempting leadership

    // === LOCAL MESH SWARM ===
    public static final int HELP_SQUEAK_COOLDOWN = 10;      // Rounds between help squeaks
    public static final int IMMEDIATE_THREAT_DIST_SQ = 8;   // Enemy within sqrt(8) ~= 2.8 tiles
    public static final int CAT_HEARING_DIST_SQ = 16;       // Cat within 4 tiles can hear
    public static final int CAT_SPOT_SQUEAK_COOLDOWN = 10;  // Rounds between cat-spot squeaks
    public static final int LOCAL_SWARM_TTL = 15;           // Rounds to swarm after receiving MSG_HELP
    public static final int CAT_SILENCE_DIST_SQ = 16;       // Don't forward if cat within this dist²

    // === SWARM ===
    public static final int SWARM_ENGAGE_RADIUS_SQ = 10;  // ~3 tile radius for counting

    // 8 cardinal/diagonal directions for swarm spread
    public static final Direction[] SWARM_OFFSETS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    /** Check if a squeak message is a formation message. */
    public static boolean isFormationMsg(int msg) {
        return msg >= FORMATION_BASE && msg < FORMATION_MAX;
    }

    /**
     * Encode a formation squeak message.
     * @param seq Sequence number (0-127, will be masked)
     * @param dir Direction to move (ordinal 0-7)
     * @return Encoded formation message
     */
    public static int encodeFormation(int seq, Direction dir) {
        int dirOrdinal = dir.ordinal();  // 0-7 for NORTH, NORTHEAST, EAST, etc.
        return FORMATION_BASE + ((seq & 0x7F) * 8) + dirOrdinal;
    }

    /**
     * Decode sequence number from formation message.
     * @param msg Formation message
     * @return Sequence number (0-127)
     */
    public static int decodeFormationSeq(int msg) {
        return ((msg - FORMATION_BASE) / 8) & 0x7F;
    }

    /**
     * Decode direction from formation message.
     * @param msg Formation message
     * @return Direction ordinal (0-7), use Direction.values()[ordinal] to get Direction
     */
    public static int decodeFormationDir(int msg) {
        return (msg - FORMATION_BASE) % 8;
    }

    /**
     * Wrap-safe sequence comparison for formation messages.
     * Returns true if seqA is "newer" than seqB (handles modulo 128 wraparound).
     * Uses half-window comparison: A is newer if (A - B + 128) % 128 < 64
     */
    public static boolean isNewerSeq(int seqA, int seqB) {
        if (seqB < 0) return true;  // No previous seq, A is newer
        int diff = (seqA - seqB + 128) % 128;
        return diff > 0 && diff < 64;
    }
}
