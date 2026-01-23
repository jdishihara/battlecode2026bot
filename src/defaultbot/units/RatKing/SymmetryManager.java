package defaultbot.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Shared.*;

/**
 * Symmetry tracking and management for the Rat King.
 */
public class SymmetryManager {

    /**
     * Initialize symmetry tracking at game start.
     * Sets all attackers to check rotational symmetry first.
     */
    public static void initializeSymmetryTracking(RobotController rc, int roundNum) throws GameActionException {
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);

        // Only initialize if not already set
        if (currentSym == 0) {
            rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, 1); // 1 = rotational (most common)
            rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, 0); // No symmetries eliminated yet
            rc.writeSharedArray(SYMMETRY_SWITCH_ROUND_INDEX, roundNum % 1000);
        }
    }

    /**
     * Update symmetry state based on attacker reports.
     * If current symmetry is eliminated, switch all attackers to next one.
     */
    public static void updateSymmetryState(RobotController rc, int roundNum) throws GameActionException {
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int eliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);

        // Check if current symmetry has been eliminated
        if (currentSym >= 1 && currentSym <= 3) {
            int currentBit = 1 << (currentSym - 1);

            if ((eliminated & currentBit) != 0) {
                // Current symmetry is eliminated - switch to next valid one
                int nextSym = findNextSymmetry(eliminated);
                if (nextSym != currentSym) {
                    rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, nextSym);
                    rc.writeSharedArray(SYMMETRY_SWITCH_ROUND_INDEX, roundNum % 1000);
                }
            }
        }
    }

    /**
     * Find the next non-eliminated symmetry.
     * Priority: rotational (1) -> horizontal (2) -> vertical (3)
     */
    public static int findNextSymmetry(int eliminated) {
        if ((eliminated & 1) == 0) return 1; // Rotational not eliminated
        if ((eliminated & 2) == 0) return 2; // Horizontal not eliminated
        if ((eliminated & 4) == 0) return 3; // Vertical not eliminated
        return 1; // All eliminated (shouldn't happen), default to rotational
    }

    /**
     * Mark a symmetry as eliminated in the shared array.
     * @param symType 1=rotational, 2=horizontal, 3=vertical
     */
    public static void eliminateSymmetry(RobotController rc, int symType) throws GameActionException {
        if (symType < 1 || symType > 3) return;
        int current = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
        int bit = 1 << (symType - 1);
        if ((current & bit) == 0) {
            rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, current | bit);
        }
    }
}
