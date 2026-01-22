package examplefuncsplayer.units.RatKing;

import battlecode.common.MapLocation;

/**
 * Runtime state for the Rat King.
 */
public class KingRuntimeState {
    public static KingState kingState = KingState.SAFE;
    public static int lastEscapeRound = -999;
    public static int lastCatTrapRound = -999;
    public static int lastDirtWallRound = -999;
    public static int safeRoundsCounter = 0;

    // === Enemy king tracking ===
    public static MapLocation lastKnownEnemyKingLoc = null;
    public static int lastEnemyKingSeenRound = -999;

    // === King position tracking ===
    public static MapLocation lastKingPosition = null;
    public static int stationaryRounds = 0;

    // === Symmetry management ===
    public static boolean symmetryInitialized = false;

    // === 2-SQUEAK MINE REPORT TRACKING ===
    // Arrays indexed by (senderID & 127) to pair X and Y squeaks
    public static final int[] pendingMineX = new int[KingConstants.SENDER_SLOTS];      // 0 = unset, otherwise x+1
    public static final int[] pendingMineRound = new int[KingConstants.SENDER_SLOTS];  // Round the X was received
}
