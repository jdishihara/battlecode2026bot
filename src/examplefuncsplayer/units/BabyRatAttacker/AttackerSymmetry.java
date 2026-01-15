package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;

/**
 * Symmetry-related helpers for attacker rats.
 * Handles symmetry computation, elimination, and target selection.
 */
public class AttackerSymmetry {

    /**
     * Compute the effective symmetry to search, using local elimination knowledge.
     * This is separate from globalSymmetry because baby rats can't write to shared array.
     */
    public static int computeEffectiveSymmetry(int globalSymmetry, int localEliminated) {
        // If global symmetry is set and not eliminated locally, use it
        if (globalSymmetry >= SYM_ROTATIONAL && globalSymmetry <= SYM_VERTICAL) {
            int symBit = 1 << (globalSymmetry - 1);
            if ((localEliminated & symBit) == 0) {
                return globalSymmetry;
            }
        }

        // Otherwise, find first non-eliminated symmetry
        return getNextValidSymmetry(localEliminated);
    }

    /**
     * Compute all 3 possible enemy king locations based on map symmetry types.
     * Returns array indexed by (symmetry - 1): [0]=rotational, [1]=horizontal, [2]=vertical
     */
    public static MapLocation[] computePossibleEnemyKingLocations(RobotController rc, MapLocation ourKing) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int kx = ourKing.x;
        int ky = ourKing.y;

        return new MapLocation[] {
            new MapLocation(w - 1 - kx, h - 1 - ky),  // [0] Rotational (180°)
            new MapLocation(w - 1 - kx, ky),          // [1] Horizontal reflection
            new MapLocation(kx, h - 1 - ky)           // [2] Vertical reflection
        };
    }

    /**
     * Read enemy king location from shared array if recent.
     */
    public static MapLocation readEnemyKingLocation(RobotController rc, int roundNum) throws GameActionException {
        int enemyKingX = rc.readSharedArray(ENEMY_KING_X_INDEX);
        int enemyKingY = rc.readSharedArray(ENEMY_KING_Y_INDEX);
        int seenRound = rc.readSharedArray(ENEMY_KING_SEEN_ROUND_INDEX);

        if (enemyKingX > 0 && enemyKingY > 0) {
            int roundsAgo = (roundNum % 1000) - seenRound;
            if (roundsAgo < 0) roundsAgo += 1000;

            if (roundsAgo < 50) {
                return new MapLocation(enemyKingX, enemyKingY);
            }
        }
        return null;
    }

    /**
     * When we find the enemy king, figure out which symmetry type the map uses.
     * Returns the confirmed symmetry type (1, 2, or 3), or 0 if can't determine.
     */
    public static int confirmSymmetryFromKingLocation(RobotController rc, Mem m, MapLocation enemyKingLoc) {
        if (m.possibleEnemyKingLocs == null) return 0;

        // King is 3x3, so check if actual location is close to any prediction
        for (int i = 0; i < 3; i++) {
            if (enemyKingLoc.distanceSquaredTo(m.possibleEnemyKingLocs[i]) <= KING_SEARCH_RADIUS) {
                int symType = i + 1; // Convert to 1-indexed

                // Mark other symmetries as eliminated
                for (int j = 0; j < 3; j++) {
                    if (j != i) {
                        m.localSymmetryEliminated |= (1 << j);
                    }
                }
                return symType;
            }
        }
        return 0;
    }

    /**
     * Try to eliminate the current symmetry if we can prove it's wrong.
     * FIX: Now takes effectiveSymmetry as parameter instead of reading globalSymmetry.
     *
     * STRICT ELIMINATION: Rats must get VERY close (adjacent or nearly adjacent)
     * to the predicted king location before eliminating. This ensures they travel
     * all the way to corners if needed.
     */
    public static void tryEliminateCurrentSymmetry(RobotController rc, Mem m, int effectiveSymmetry, int roundNum)
            throws GameActionException {

        // FIX: Use effectiveSymmetry parameter instead of globalSymmetry
        if (effectiveSymmetry < SYM_ROTATIONAL || effectiveSymmetry > SYM_VERTICAL) return;

        int symIndex = effectiveSymmetry - 1; // Convert to 0-indexed
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) return; // Already eliminated

        MapLocation predicted = m.possibleEnemyKingLocs[symIndex];
        MapLocation here = rc.getLocation();
        int distToPredicted = here.distanceSquaredTo(predicted);

        boolean shouldEliminate = false;

        // === CONDITION 1: Predicted location is too close to boundary for 3x3 king ===
        // Check this first since it doesn't require being close
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        // King center must be at least 1 tile from edge (king is 3x3)
        if (predicted.x < 1 || predicted.x >= w - 1 ||
            predicted.y < 1 || predicted.y >= h - 1) {
            shouldEliminate = true;
        }

        // === CONDITION 2: Predicted center is blocked by wall ===
        // Can check from distance if we can sense it
        if (!shouldEliminate && rc.canSenseLocation(predicted)) {
            MapInfo info = rc.senseMapInfo(predicted);
            if (info.isWall()) {
                shouldEliminate = true;
            }
        }

        // === CONDITION 3: STRICT - Must be VERY close to eliminate ===
        // Only eliminate if we're within 2 tiles (distSq <= 8) of the predicted CENTER
        // This ensures rats go ALL THE WAY to corners
        if (!shouldEliminate && distToPredicted <= 8) {
            m.reachedCurrentTarget = true;

            // We're right next to where the king should be - check thoroughly
            boolean foundEnemyKing = false;
            int tilesChecked = 0;

            // Check the entire 3x3 area where king would be
            for (int dx = -1; dx <= 1 && !foundEnemyKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundEnemyKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null &&
                            robot.getType() == UnitType.RAT_KING &&
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundEnemyKing = true;
                        }
                    }
                }
            }

            // Must have checked ALL 9 tiles of the 3x3 area to be sure
            if (!foundEnemyKing && tilesChecked >= 9) {
                shouldEliminate = true;
            }
            // If we checked at least 6 tiles and we're adjacent, that's good enough
            else if (!foundEnemyKing && tilesChecked >= 6 && distToPredicted <= 2) {
                shouldEliminate = true;
            }
        }

        // === CONDITION 4: We're close (within 4 tiles) and can see the ENTIRE 3x3 area is empty ===
        if (!shouldEliminate && distToPredicted <= 16) {
            boolean foundKing = false;
            int tilesChecked = 0;

            for (int dx = -1; dx <= 1 && !foundKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null &&
                            robot.getType() == UnitType.RAT_KING &&
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundKing = true;
                        }
                    }
                }
            }

            // Only eliminate if we checked ALL 9 tiles
            if (!foundKing && tilesChecked >= 9) {
                shouldEliminate = true;
                m.reachedCurrentTarget = true;
            }
        }

        // === CONDITION 5: Timeout - but ONLY if we've actually reached the target ===
        // Much longer timeout, and require being close
        int timeoutThreshold = SYMMETRY_SEARCH_TIMEOUT * 2; // Double the timeout

        if (!shouldEliminate && m.roundsSearchingCurrentSymmetry > timeoutThreshold) {
            // Only timeout-eliminate if we actually got close
            if (m.reachedCurrentTarget && distToPredicted <= 16) {
                shouldEliminate = true;
            }
            // Or if we've been searching for a VERY long time and are reasonably close
            else if (m.roundsSearchingCurrentSymmetry > timeoutThreshold * 2 && distToPredicted <= 50) {
                shouldEliminate = true;
            }
        }

        // === ELIMINATE THIS SYMMETRY ===
        if (shouldEliminate) {
            m.localSymmetryEliminated |= (1 << symIndex);

            // Squeak to notify nearby allies
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN / 2) {
                rc.squeak(SQUEAK_SYMMETRY_ELIMINATED + (symIndex * 10));
                m.lastSqueakRound = roundNum;
            }
        }
    }

    /**
     * Get the current symmetry target location.
     */
    public static MapLocation getCurrentSymmetryTarget(Mem m, int effectiveSymmetry) {
        if (m.possibleEnemyKingLocs == null || effectiveSymmetry < SYM_ROTATIONAL) {
            return m.mapCenter;
        }

        int symIndex = effectiveSymmetry - 1;

        // If current symmetry is eliminated, find first non-eliminated
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) {
            for (int i = 0; i < 3; i++) {
                if ((m.localSymmetryEliminated & (1 << i)) == 0) {
                    return m.possibleEnemyKingLocs[i];
                }
            }
        }

        return m.possibleEnemyKingLocs[symIndex];
    }

    /**
     * Get the next symmetry to try after current one is eliminated.
     */
    public static int getNextSymmetry(int current, int eliminated) {
        // Try symmetries in order: rotational -> horizontal -> vertical
        int[] order = {SYM_ROTATIONAL, SYM_HORIZONTAL, SYM_VERTICAL};

        for (int sym : order) {
            int bit = 1 << (sym - 1);
            if ((eliminated & bit) == 0) {
                return sym;
            }
        }

        // All eliminated? Return rotational as fallback
        return SYM_ROTATIONAL;
    }

    /**
     * Get the next valid (non-eliminated) symmetry to check.
     */
    public static int getNextValidSymmetry(int eliminated) {
        // Priority: rotational -> horizontal -> vertical
        if ((eliminated & 1) == 0) return SYM_ROTATIONAL;  // bit 0 = rotational
        if ((eliminated & 2) == 0) return SYM_HORIZONTAL;  // bit 1 = horizontal
        if ((eliminated & 4) == 0) return SYM_VERTICAL;    // bit 2 = vertical
        return SYM_ROTATIONAL; // All eliminated, default
    }
}
