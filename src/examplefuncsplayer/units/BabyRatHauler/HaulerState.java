package examplefuncsplayer.units.BabyRatHauler;

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
        public boolean threatIsCat;  // true if primary threat is a cat (flee), false if enemy rat (fight)
        public int enemiesNearKing;  // count of enemies near our king
        public MapLocation priorityTarget;  // best target to attack (low HP)
        public int priorityTargetHP;  // HP of priority target

        // Attackable enemy tracking (for ATTACK state)
        public MapLocation attackableEnemy;      // Enemy we have HP advantage over
        public int attackableEnemyHP;            // Their HP
        public int attackableEnemyId;            // Their robot ID
        public boolean enemyFacingAway;          // Can we attack without retaliation?
        public int nearbyAllies;                 // Count of allies who can help

        public ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
            this.threatIsCat = false;  // Default to enemy rat (don't flee)
            this.enemiesNearKing = 0;
            this.priorityTarget = null;
            this.priorityTargetHP = Integer.MAX_VALUE;
            this.attackableEnemy = null;
            this.attackableEnemyHP = Integer.MAX_VALUE;
            this.attackableEnemyId = -1;
            this.enemyFacingAway = false;
            this.nearbyAllies = 0;
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
