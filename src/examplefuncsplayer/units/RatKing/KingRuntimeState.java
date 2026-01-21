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

    // === Symmetry management ===
    public static boolean symmetryInitialized = false;

    // === 2-SQUEAK MINE REPORT TRACKING ===
    // Arrays indexed by (senderID & 127) to pair X and Y squeaks
    public static final int[] pendingMineX = new int[KingConstants.SENDER_SLOTS];      // 0 = unset, otherwise x+1
    public static final int[] pendingMineRound = new int[KingConstants.SENDER_SLOTS];  // Round the X was received

    // === ROAMING STATE TRACKING ===
    public static int stationaryRounds = 0;           // Rounds king has stayed in same position
    public static MapLocation lastKingPosition = null; // Track position for stationary detection
    public static int lastRoamingMoveRound = -999;    // Last round king moved while roaming
    public static MapLocation roamingTarget = null;   // Current roaming destination (cheese-rich area)
    public static final int ROAMING_MOVE_INTERVAL = 4; // Move every 3-5 rounds (use 4 as middle)
    public static final int MAX_STATIONARY_ROUNDS = 5; // Max rounds to stay still before forcing movement
}
