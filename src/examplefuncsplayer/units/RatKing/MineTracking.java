package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

/**
 * Mine tracking logic for the Rat King.
 */
public class MineTracking {

    /**
     * Listen for baby rat squeaks reporting mine locations via 2-squeak protocol.
     * Format: 800+x (stage 0), then 900+y (stage 1).
     * Pairs X and Y from same sender within 2 rounds.
     * Stores mines using x+1 and y+1 so 0 remains "unset".
     */
    public static void listenForMineReports(RobotController rc) throws GameActionException {
        int roundNum = rc.getRoundNum();
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        Message[] messages = rc.readSqueaks(-1);

        for (Message msg : messages) {
            int content = msg.getBytes();
            int senderID = msg.getSenderID();
            int slot = senderID & 127;

            // Check for X squeak: 800 + x
            if (content >= 800 && content < 900) {
                int x = content - 800;
                if (x >= 0 && x < mapW) {
                    KingRuntimeState.pendingMineX[slot] = x + 1;  // Store x+1 so 0 = unset
                    KingRuntimeState.pendingMineRound[slot] = roundNum;
                }
                continue;
            }

            // Check for Y squeak: 900 + y
            if (content >= 900 && content < 1000) {
                int y = content - 900;
                if (y < 0 || y >= mapH) continue;

                // Check if we have a pending X from this sender within 2 rounds
                int storedXPlus1 = KingRuntimeState.pendingMineX[slot];
                if (storedXPlus1 == 0) continue;  // No pending X
                if (roundNum - KingRuntimeState.pendingMineRound[slot] > 2) {
                    // Too old, clear it
                    KingRuntimeState.pendingMineX[slot] = 0;
                    continue;
                }

                int x = storedXPlus1 - 1;  // Decode x
                KingRuntimeState.pendingMineX[slot] = 0;    // Clear pending

                // Add mine to shared array if new (using x+1, y+1 encoding)
                addMineToSharedArray(rc, x, y);
            }
        }
    }

    /**
     * Add a mine to shared array using x+1/y+1 encoding (so 0 = unset).
     * Checks for duplicates before adding.
     */
    public static void addMineToSharedArray(RobotController rc, int x, int y) throws GameActionException {
        int mineCount = rc.readSharedArray(KingConstants.MINE_COUNT_SLOT);

        // Check for duplicates (stored values are x+1, y+1)
        for (int i = 0; i < mineCount && i < KingConstants.MAX_KNOWN_MINES; i++) {
            int storedXPlus1 = rc.readSharedArray(KingConstants.MINE_START_SLOT + i * 2);
            int storedYPlus1 = rc.readSharedArray(KingConstants.MINE_START_SLOT + i * 2 + 1);

            if (storedXPlus1 == x + 1 && storedYPlus1 == y + 1) {
                return;  // Already known
            }
        }

        // Add new mine (store as x+1, y+1)
        if (mineCount < KingConstants.MAX_KNOWN_MINES) {
            rc.writeSharedArray(KingConstants.MINE_START_SLOT + mineCount * 2, x + 1);
            rc.writeSharedArray(KingConstants.MINE_START_SLOT + mineCount * 2 + 1, y + 1);
            rc.writeSharedArray(KingConstants.MINE_COUNT_SLOT, mineCount + 1);
        }
    }

    /**
     * Broadcast any cheese mines the king can see directly.
     * King has 360 vision with 25 unit radius.
     * Uses x+1/y+1 encoding so 0 = unset.
     */
    public static void broadcastVisibleMines(RobotController rc) throws GameActionException {
        MapInfo[] visible = rc.senseNearbyMapInfos();

        for (MapInfo info : visible) {
            if (!info.hasCheeseMine()) continue;

            MapLocation mineLoc = info.getMapLocation();
            addMineToSharedArray(rc, mineLoc.x, mineLoc.y);
        }
    }
}
