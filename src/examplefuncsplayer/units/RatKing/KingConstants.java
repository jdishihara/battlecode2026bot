package examplefuncsplayer.units.RatKing;

/**
 * Constants specific to the Rat King.
 */
public class KingConstants {
    // === DIRT WALL CONSTANTS ===
    public static final int DIRT_PLACE_COST = 10;
    public static final int DIRT_WALL_COOLDOWN = 3; // Rounds between wall placements
    public static final int MIN_CHEESE_FOR_DIRT = 50; // Minimum cheese to spend on dirt

    // === MINE TRACKING CONSTANTS ===
    public static final int MINE_COUNT_SLOT = 9;
    public static final int MINE_START_SLOT = 10;  // Slots 10-25 for up to 8 mines
    public static final int MAX_KNOWN_MINES = 8;

    // === 2-SQUEAK MINE REPORT TRACKING ===
    public static final int SENDER_SLOTS = 128;
}
