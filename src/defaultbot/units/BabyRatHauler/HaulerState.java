package defaultbot.units.BabyRatHauler;

import battlecode.common.MapLocation;

/**
 * State enums and threat tracking for hauler rats.
 */
public class HaulerState {

    // ===== HAULER STATE MACHINE =====
    public enum RatState { EXPLORE, COLLECT, RETURN, DELIVER, FLEE }

    // ===== THREAT LEVELS =====
    public enum ThreatLevel { NONE, NEAR, BLOCKING, PANIC }

    // ===== THREAT SOURCE TRACKING =====
    public static class ThreatInfo {
        public ThreatLevel level;
        public MapLocation source; // location of threat (cat or enemy rat)

        public ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
        }
    }
}
