package defaultbot.units.BabyRatHauler;

import battlecode.common.*;

import defaultbot.units.BabyRatHauler.HaulerMemory.Mem;

/**
 * Mine assignment and reporting system for hauler rats.
 *
 * Mine sharing system - rats report discovered mines to king via squeak,
 * king broadcasts to shared array, rats distribute across known mines.
 */
public class HaulerMining {

    // ===== SHARED ARRAY SLOTS FOR MINES =====
    public static final int MINE_COUNT_SLOT = 9;
    public static final int MINE_START_SLOT = 10;  // Slots 10-25 for up to 8 mines (2 slots each: x, y)
    public static final int MAX_KNOWN_MINES = 8;

    /**
     * Get this rat's assigned mine from the shared array.
     * Distributes rats across known mines based on robot ID.
     * Rotates assignment every 200 rounds to prevent permanent crowding.
     * Mines are stored as x+1, y+1 (so 0 = unset).
     * Falls back to locally cached mines if shared array is empty.
     */
    public static MapLocation getAssignedMine(RobotController rc, Mem m) throws GameActionException {
        int mineCount = rc.readSharedArray(MINE_COUNT_SLOT);

        // First, try to also cache any mines from shared array
        for (int i = 0; i < mineCount && i < MAX_KNOWN_MINES; i++) {
            int xSlot = MINE_START_SLOT + (i * 2);
            int ySlot = xSlot + 1;
            int xPlus1 = rc.readSharedArray(xSlot);
            int yPlus1 = rc.readSharedArray(ySlot);
            if (xPlus1 > 0 && yPlus1 > 0) {
                m.cacheMineLoc(new MapLocation(xPlus1 - 1, yPlus1 - 1));
            }
        }

        if (mineCount > 0) {
            // Rotate assignment every 200 rounds to redistribute rats
            int roundOffset = rc.getRoundNum() / 200;
            int myAssignment = (rc.getID() + roundOffset) % mineCount;

            int xSlot = MINE_START_SLOT + (myAssignment * 2);
            int ySlot = xSlot + 1;

            int xPlus1 = rc.readSharedArray(xSlot);
            int yPlus1 = rc.readSharedArray(ySlot);

            if (xPlus1 > 0 && yPlus1 > 0) {
                return new MapLocation(xPlus1 - 1, yPlus1 - 1);
            }
        }

        // Fallback: use locally cached mines if no shared array mines
        if (m.knownMineCount > 0) {
            // Distribute across known mines based on robot ID
            int myAssignment = rc.getID() % m.knownMineCount;
            MapLocation mine = m.knownMines[myAssignment];
            if (mine != null) {
                return mine;
            }
            // If that slot is null, find any non-null mine
            for (int i = 0; i < m.knownMineCount; i++) {
                if (m.knownMines[i] != null) {
                    return m.knownMines[i];
                }
            }
        }

        return null;
    }

    /**
     * Attempt to report a pending mine location to the king via 2-squeak protocol.
     * Stage 0: squeak 800 + x
     * Stage 1: squeak 900 + y, then clear pending
     * Requires being near king (distSq <= 16).
     * Note: Only one squeak per turn allowed, so this takes 2 turns.
     */
    public static void attemptMineReport(RobotController rc, Mem m) throws GameActionException {
        if (m.pendingMineReport == null) return;
        if (m.kingLocation == null) return;

        int distSq = rc.getLocation().distanceSquaredTo(m.kingLocation);
        if (distSq > 16) return;  // Must be close to king

        if (m.pendingMineStage == 0) {
            // Squeak X coordinate: 800 + x
            int squeakVal = 800 + m.pendingMineReport.x;
            rc.squeak(squeakVal);
            m.pendingMineStage = 1;
        } else {
            // Squeak Y coordinate: 900 + y
            int squeakVal = 900 + m.pendingMineReport.y;
            rc.squeak(squeakVal);
            // Done reporting this mine
            m.pendingMineReport = null;
            m.pendingMineStage = 0;
        }
    }
}
