package defaultbot.units.BabyRatHauler;

import battlecode.common.MapLocation;

/**
 * State enums and threat tracking for hauler rats.
 */
public class HaulerState {

    // ===== HAULER STATE MACHINE =====
    public enum RatState { EXPLORE, COLLECT, RETURN, DELIVER, FLEE, DEFEND }

    // ===== THREAT LEVELS =====
    public enum ThreatLevel { NONE, NEAR, BLOCKING, PANIC, KING_DANGER }

    // ===== THREAT SOURCE TRACKING =====
    public static class ThreatInfo {
        public ThreatLevel level;
        public MapLocation source; // location of threat (cat or enemy rat)
        public int enemiesNearKing;  // count of enemies near our king
        public MapLocation priorityTarget;  // best target to attack (low HP)
        public int priorityTargetHP;  // HP of priority target

        public ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
            this.enemiesNearKing = 0;
            this.priorityTarget = null;
            this.priorityTargetHP = Integer.MAX_VALUE;
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
