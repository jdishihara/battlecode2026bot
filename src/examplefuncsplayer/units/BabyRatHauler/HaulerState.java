package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.Direction;
import battlecode.common.MapLocation;

/**
 * State enums and threat tracking for hauler rats.
 */
public class HaulerState {

    // ===== HAULER STATE MACHINE =====
    // Added COMBAT state for aggressive enemy engagement (not just defense)
    public enum RatState { EXPLORE, COLLECT, RETURN, DELIVER, FLEE, DEFEND, COMBAT }

    // ===== COMBAT CONSTANTS (from AttackerState) =====

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

    // === COMBAT ENGAGEMENT ===
    public static final int COMBAT_ENGAGE_RADIUS_SQ = 36;    // ~6 tiles - enter combat when enemy this close
    public static final int COMBAT_DISENGAGE_RADIUS_SQ = 64; // ~8 tiles - exit combat when enemy this far
    public static final int GANG_UP_THRESHOLD = 2;           // Attack if 2+ allies nearby (lower for haulers)
    public static final int COORDINATED_ATTACK_RADIUS = 16;  // Check for allies in this radius

    // === SQUEAK COMMUNICATION ===
    public static final int HELP_SQUEAK_COOLDOWN = 8;       // Rounds between help squeaks
    public static final int IMMEDIATE_THREAT_DIST_SQ = 8;   // Enemy within sqrt(8) ~= 2.8 tiles
    public static final int CAT_HEARING_DIST_SQ = 16;       // Cat within 4 tiles can hear
    public static final int CAT_SPOT_SQUEAK_COOLDOWN = 10;  // Rounds between cat-spot squeaks
    public static final int LOCAL_SWARM_TTL = 12;           // Rounds to respond after receiving MSG_HELP
    public static final int CAT_SILENCE_DIST_SQ = 16;       // Don't squeak if cat within this dist²

    // === CLUSTER MOVEMENT ===
    public static final int CLUSTER_ADJACENCY_BONUS = 100;  // Score bonus per adjacent ally
    public static final int CLUSTER_NEAR_BONUS = 30;        // Score bonus per ally within 2 tiles
    public static final int CLUSTER_MAX_SPREAD_SQ = 8;      // Max distSq to stay clustered
    public static final int CLUSTER_REGROUP_THRESHOLD = 1;  // Min allies adjacent before regrouping (lower for haulers)
    public static final int CLUSTER_SENSE_RADIUS_SQ = 20;   // Radius to sense allies for clustering

    // === FOCUS FIRE ===
    public static final int FOCUS_FIRE_BITE_BOOST = 25;     // Extra cheese for focus fire targets
    public static final int LOW_HP_KILL_THRESHOLD = 20;     // HP below which we try to finish kill

    // ===== THREAT LEVELS =====
    public enum ThreatLevel { NONE, NEAR, BLOCKING, PANIC, KING_DANGER }

    // ===== THREAT SOURCE TRACKING =====
    public static class ThreatInfo {
        public ThreatLevel level;
        public MapLocation source; // location of threat (cat or enemy rat)
        public int enemiesNearKing;  // count of enemies near our king
        public MapLocation priorityTarget;  // best target to attack (low HP)
        public int priorityTargetHP;  // HP of priority target
        public MapLocation focusTarget;  // king-designated focus fire target

        public ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
            this.enemiesNearKing = 0;
            this.priorityTarget = null;
            this.priorityTargetHP = Integer.MAX_VALUE;
            this.focusTarget = null;
        }
    }

    // ===== DEFENSE TARGET INFO =====
    public static class DefenseTarget {
        public MapLocation location;
        public int hp;
        public int robotId;
        public int distToKingSq;

        public DefenseTarget(MapLocation loc, int hp, int id, int distToKingSq) {
            this.location = loc;
            this.hp = hp;
            this.robotId = id;
            this.distToKingSq = distToKingSq;
        }
    }
}
