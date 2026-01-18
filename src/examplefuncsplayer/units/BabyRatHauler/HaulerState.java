package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.MapLocation;

/**
 * State enums and threat tracking for hauler rats.
 */
public class HaulerState {

    // ===== HAULER STATE MACHINE =====
    public enum RatState { EXPLORE, COLLECT, RETURN, DELIVER, FLEE, DEFEND }

    // ===== THREAT LEVELS =====
    public enum ThreatLevel { NONE, NEAR, BLOCKING, PANIC }

    // ===== THREAT SOURCE TRACKING =====
    public static class ThreatInfo {
        public ThreatLevel level;
        public MapLocation source; // location of threat (cat or enemy rat)
        public boolean shouldDefendKing; // true if we should attack enemies near king instead of fleeing
        public MapLocation enemyToAttack; // specific enemy rat to attack when defending

        public ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
            this.shouldDefendKing = false;
            this.enemyToAttack = null;
        }

        public ThreatInfo(ThreatLevel level, MapLocation source, boolean shouldDefendKing, MapLocation enemyToAttack) {
            this.level = level;
            this.source = source;
            this.shouldDefendKing = shouldDefendKing;
            this.enemyToAttack = enemyToAttack;
        }
    }
}
