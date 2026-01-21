package examplefuncsplayer.units.RatKing;

/**
 * State machine states for the Rat King.
 */
public enum KingState {
    SAFE,
    ROAMING,  // Mobile king: drift toward cheese-rich areas
    ALERT,
    ESCAPE
}
