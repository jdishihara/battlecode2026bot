package examplefuncsplayer.config;

import battlecode.common.*;

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

    // ===== SPAWN ORDER TRACKING =====
    public static final int KING_ID_INDEX = 29;       // King's ID (mod 1000) for spawn order calculation
    public static final int NUM_INITIAL_ATTACKERS = 7; // First N baby rats are attackers, rest are haulers

    // ===== SPAWN PHASE TRACKING =====
    // Phase 0: Initial Hauler Phase - spawn 30 haulers first
    // Phase 1: Initial Attacker Phase - spawn 30 attackers
    // Phase 2: Main Hauler Phase - spawn haulers continuously, check attacker threshold
    // Phase 3: Attacker Wave Phase - spawn 8 attackers, then back to Phase 2
    public static final int SPAWN_PHASE_INDEX = 30;       // 0-3 for different phases
    public static final int ENEMY_RAT_COUNT_INDEX = 31;   // Estimated enemy rat count from sightings
    public static final int OUR_RAT_COUNT_INDEX = 32;     // Our baby rat count estimate
    public static final int KING_RESERVE = 300;           // Cheese reserve for king safety

    // Initial spawn counts
    public static final int INITIAL_HAULER_COUNT = 30;    // Haulers to spawn first
    public static final int INITIAL_ATTACKER_COUNT = 30;  // Attackers to spawn after haulers

    // Attacker wave settings
    public static final int ATTACKER_WAVE_SIZE = 8;       // Number of attackers to spawn per wave

    // Shared array indices for tracking
    public static final int ATTACKERS_SPAWNED_INDEX = 38; // Count of attackers spawned in current wave/phase
    public static final int HAULERS_SPAWNED_INDEX = 39;   // Total haulers spawned

    // ===== HAULER DEFENSE COORDINATION =====
    // Focus fire system: king broadcasts priority target for all defenders to attack
    public static final int DEFENSE_TARGET_X = 33;        // X coord of priority target (0 = none)
    public static final int DEFENSE_TARGET_Y = 34;        // Y coord of priority target
    public static final int DEFENSE_TARGET_HP = 35;       // HP of priority target (for updating)
    public static final int DEFENSE_TARGET_ROUND = 36;    // Round when target was set (mod 1000)
    public static final int DEFENSE_ENEMIES_COUNT = 37;   // Count of enemies near king (for spawn decisions)

    // ===== FOCUS FIRE SYSTEM =====
    // Global target coordination - all rats attack the same enemy
    public static final int FOCUS_TARGET_X = 40;
    public static final int FOCUS_TARGET_Y = 41;
    public static final int FOCUS_TARGET_ROUND = 42;
    public static final int FOCUS_TARGET_PRIORITY = 43; // encoded: [hp:7][distance_to_king:3]
    public static final int FOCUS_TARGET_STALE_THRESHOLD = 3; // rounds before target is considered stale

    /**
     * Calculate target priority for focus fire.
     * Lower value = higher priority (more urgent to kill).
     * Encodes: [hp in upper bits][distance to king in lower 3 bits]
     *
     * @param enemyHP Enemy's current health (0-100+)
     * @param distToOurKingSq Squared distance from enemy to our king
     * @return Priority value (lower = more urgent)
     */
    public static int calcTargetPriority(int enemyHP, int distToOurKingSq) {
        // Convert distSq to a 0-7 scale (closer = lower = better)
        int distBucket = Math.min(7, (int) Math.sqrt(distToOurKingSq) / 2);
        // HP in upper bits, distance in lower 3 bits
        return (enemyHP << 3) | distBucket;
    }

    /**
     * Get the current focus fire target from shared array.
     * Returns null if no target or target is stale.
     *
     * @param rc RobotController
     * @return Focus target location, or null if none/stale
     */
    public static MapLocation getFocusTarget(RobotController rc) throws GameActionException {
        int targetRound = rc.readSharedArray(FOCUS_TARGET_ROUND);
        int currentRound = rc.getRoundNum();

        // Check if target is stale (accounting for round wraparound at 1000)
        int roundDiff = (currentRound - targetRound + 1000) % 1000;
        if (roundDiff > FOCUS_TARGET_STALE_THRESHOLD) return null;

        int x = rc.readSharedArray(FOCUS_TARGET_X);
        int y = rc.readSharedArray(FOCUS_TARGET_Y);

        // 0,0 means no target (since we use +1 encoding could also work, but 0,0 is fine)
        if (x == 0 && y == 0) return null;

        return new MapLocation(x, y);
    }

    /**
     * Update the focus fire target. Only Rat King should call this.
     * Updates if: no current target, better priority, or target is stale.
     *
     * @param rc RobotController
     * @param enemy Enemy robot to potentially target
     * @param kingLoc Our king's location (for priority calculation)
     */
    public static void updateFocusTarget(RobotController rc, RobotInfo enemy, MapLocation kingLoc)
            throws GameActionException {
        if (rc.getType() != UnitType.RAT_KING) return; // Only king writes

        int currentRound = rc.getRoundNum();
        int newPriority = calcTargetPriority(enemy.getHealth(), enemy.getLocation().distanceSquaredTo(kingLoc));

        int currentPriority = rc.readSharedArray(FOCUS_TARGET_PRIORITY);
        int currentRound_stored = rc.readSharedArray(FOCUS_TARGET_ROUND);

        // Calculate round difference (handle wraparound)
        int roundDiff = (currentRound - currentRound_stored + 1000) % 1000;

        // Update if: target is stale OR new target has better (lower) priority
        if (roundDiff > FOCUS_TARGET_STALE_THRESHOLD || newPriority < currentPriority) {
            rc.writeSharedArray(FOCUS_TARGET_X, enemy.getLocation().x);
            rc.writeSharedArray(FOCUS_TARGET_Y, enemy.getLocation().y);
            rc.writeSharedArray(FOCUS_TARGET_ROUND, currentRound % 1000);
            rc.writeSharedArray(FOCUS_TARGET_PRIORITY, newPriority);
        }
    }

    /**
     * Clear the focus fire target. Call when target is killed or lost.
     * Only Rat King should call this.
     */
    public static void clearFocusTarget(RobotController rc) throws GameActionException {
        if (rc.getType() != UnitType.RAT_KING) return;
        rc.writeSharedArray(FOCUS_TARGET_X, 0);
        rc.writeSharedArray(FOCUS_TARGET_Y, 0);
        rc.writeSharedArray(FOCUS_TARGET_PRIORITY, 1023); // Max value = lowest priority
    }

    // ===== RESERVED FOR FUTURE USE =====
    // Indices 44-63 available for expansion (23-28 used by ALERT system in Constants.java)

    // ===== SQUEAK MESSAGE ENCODING =====
    // Compact format: type (0-7, 3 bits) | x (0-63, 6 bits) | y (0-63, 6 bits)
    // Layout: [type:3][x:6][y:6] = 15 bits total
    // Bit positions: y = bits 0-5, x = bits 6-11, type = bits 12-14

    // Message type constants
    public static final int MSG_HELP = 0;             // Request backup at this location
    public static final int MSG_KING_DANGER = 1;      // Our king is under threat
    public static final int MSG_ENEMY_KING_FOUND = 2; // Found enemy king at location
    public static final int MSG_CAT_SPOTTED = 3;      // Cat seen at location (safe squeak)

    /**
     * Encode a squeak message into a single int.
     * @param type Message type (0-7)
     * @param x X coordinate (0-63)
     * @param y Y coordinate (0-63)
     * @return Encoded message int
     */
    public static int encodeMsg(int type, int x, int y) {
        return ((type & 0x7) << 12) | ((x & 0x3F) << 6) | (y & 0x3F);
    }

    /**
     * Decode message type from encoded squeak.
     * @param msg Encoded message
     * @return Message type (0-7)
     */
    public static int decodeType(int msg) {
        return (msg >> 12) & 0x7;
    }

    /**
     * Decode X coordinate from encoded squeak.
     * @param msg Encoded message
     * @return X coordinate (0-63)
     */
    public static int decodeX(int msg) {
        return (msg >> 6) & 0x3F;
    }

    /**
     * Decode Y coordinate from encoded squeak.
     * @param msg Encoded message
     * @return Y coordinate (0-63)
     */
    public static int decodeY(int msg) {
        return msg & 0x3F;
    }
}