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

    // ===== ENEMY KING TRACKING (NEW) =====
    public static final int ENEMY_KING_X_INDEX = 9;
    public static final int ENEMY_KING_Y_INDEX = 10;
    public static final int ENEMY_KING_LAST_SEEN = 11;  // Round number (mod 1000 to fit 0-1023)

    // ===== COMBAT COORDINATION (NEW) =====
    public static final int SWARM_TARGET_X = 12;     // Where attackers should converge
    public static final int SWARM_TARGET_Y = 13;
    public static final int SWARM_ACTIVE_ROUND = 14; // Round when swarm was called (mod 1000)
    public static final int TRAP_COUNT_INDEX = 15;   // Approximate count of team's rat traps

    // ===== BACKSTAB TRACKING (NEW) =====
    public static final int BACKSTAB_MODE_INDEX = 16; // 0 = cooperation, 1 = backstabbing

    // ===== RESERVED FOR FUTURE USE =====
    // Indices 17-63 available for expansion
}