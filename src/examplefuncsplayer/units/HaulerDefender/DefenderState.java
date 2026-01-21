package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.MapLocation;

/**
 * State enums and threat tracking for HaulerDefender rats.
 * Simplified defense logic: defend when near king and enemy rats threaten king.
 */
public class DefenderState {

    // ===== HAULER-DEFENDER STATE MACHINE =====
    // Same as defaultbot (EXPLORE, COLLECT, RETURN, DELIVER, FLEE) + DEFEND for defense
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
