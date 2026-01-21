package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;

/**
 * Mine assignment and reporting system for HaulerDefender rats.
 */
public class DefenderMining {

    public static final int MINE_COUNT_SLOT = 9;
    public static final int MINE_START_SLOT = 10;
    public static final int MAX_KNOWN_MINES = 8;

    /**
     * Get this rat's assigned mine from the shared array.
     */
    public static MapLocation getAssignedMine(RobotController rc, Mem m) throws GameActionException {
        int mineCount = rc.readSharedArray(MINE_COUNT_SLOT);

        if (mineCount == 0) return null;

        int roundOffset = rc.getRoundNum() / 200;
        int myAssignment = (rc.getID() + roundOffset) % mineCount;

        int xSlot = MINE_START_SLOT + (myAssignment * 2);
        int ySlot = xSlot + 1;

        int xPlus1 = rc.readSharedArray(xSlot);
        int yPlus1 = rc.readSharedArray(ySlot);

        if (xPlus1 == 0 || yPlus1 == 0) return null;

        return new MapLocation(xPlus1 - 1, yPlus1 - 1);
    }

    /**
     * Attempt to report a pending mine location to the king via 2-squeak protocol.
     */
    public static void attemptMineReport(RobotController rc, Mem m) throws GameActionException {
        if (m.pendingMineReport == null) return;
        if (m.kingLocation == null) return;

        int distSq = rc.getLocation().distanceSquaredTo(m.kingLocation);
        if (distSq > 16) return;

        if (m.pendingMineStage == 0) {
            int squeakVal = 800 + m.pendingMineReport.x;
            rc.squeak(squeakVal);
            m.pendingMineStage = 1;
        } else {
            int squeakVal = 900 + m.pendingMineReport.y;
            rc.squeak(squeakVal);
            m.pendingMineReport = null;
            m.pendingMineStage = 0;
        }
    }
}
