package examplefuncsplayer.config;

/**
 * Shared array indices (team-shared memory slots).
 * The shared array has 64 slots, each holding values 0-1023.
 * Only Rat Kings can write; all rats can read.
 */
public final class Shared {
    private Shared() {}

    // ===== KING INFO =====
    public static final int KING_X_INDEX = 0;
    public static final int KING_Y_INDEX = 1;
    public static final int CHEESE_STATE_INDEX = 2;  // 0 = low, 1 = ok
    public static final int DANGER_INDEX = 3;        // 0 = safe, 1 = alert, 2 = escape

    // ===== CAT TRACKING =====
    public static final int CAT_X_INDEX = 4;
    public static final int CAT_Y_INDEX = 5;

    // ===== ENEMY RAT TRACKING =====
    public static final int ENEMY_RAT_X_INDEX = 6;
    public static final int ENEMY_RAT_Y_INDEX = 7;
    public static final int ENEMY_THREAT_LEVEL = 8;  // 0 = none, 1 = nearby, 2 = attacking

    // ===== ENEMY KING TRACKING =====
    public static final int ENEMY_KING_X_INDEX = 9;
    public static final int ENEMY_KING_Y_INDEX = 10;
    public static final int ENEMY_KING_SEEN_ROUND_INDEX = 11;  // Round number (mod 1000)

    // ===== SYMMETRY TRACKING (CLUSTERED SEARCH) =====
    public static final int CURRENT_SYMMETRY_INDEX = 12;       // 0=unknown, 1=rot, 2=horiz, 3=vert
    public static final int SYMMETRY_ELIMINATED_INDEX = 13;    // Bitmask: bit0=rot, bit1=horiz, bit2=vert
    public static final int SYMMETRY_SWITCH_ROUND_INDEX = 14;  // Round when we switched symmetries

    // ===== COMBAT COORDINATION =====
    public static final int SWARM_TARGET_X = 15;     // Where attackers should converge
    public static final int SWARM_TARGET_Y = 16;
    public static final int SWARM_ACTIVE_ROUND = 17; // Round when swarm was called (mod 1000)
    public static final int TRAP_COUNT_INDEX = 18;   // Approximate count of team's rat traps

    // ===== BACKSTAB TRACKING =====
    public static final int BACKSTAB_MODE_INDEX = 19; // 0 = cooperation, 1 = backstabbing

    // ===== ATTACKER COORDINATION =====
    public static final int ATTACKER_CLUSTER_X = 20;  // Rally point X for attackers
    public static final int ATTACKER_CLUSTER_Y = 21;  // Rally point Y for attackers
    public static final int ATTACKERS_AT_TARGET = 22; // Count of attackers near current target

    // ===== RESERVED FOR FUTURE USE =====
    // Indices 23-63 available for expansion
}