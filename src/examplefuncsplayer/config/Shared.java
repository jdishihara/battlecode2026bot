package examplefuncsplayer.config;

/**
 * Shared array indices (team-shared memory slots).
 * Put ONLY indices / shared-memory layout here.
 */
public final class Shared {
    private Shared() {}

    public static final int KING_X_INDEX = 0;
    public static final int KING_Y_INDEX = 1;
    public static final int CHEESE_STATE_INDEX = 2;
    public static final int DANGER_INDEX = 3;
    public static final int CAT_X_INDEX = 4;
    public static final int CAT_Y_INDEX = 5;
}
