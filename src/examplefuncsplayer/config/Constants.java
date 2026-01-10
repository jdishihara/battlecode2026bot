package examplefuncsplayer.config;

import battlecode.common.Direction;

public final class Constants {

    // ===== GENERAL GAMEPLAY =====
    public static final int INITIAL_RATS = 5;
    public static final int CARRY_LIMIT = 20;
    public static final int LOW_CHEESE_THRESHOLD = 1500;
    public static final int CRITICAL_CHEESE_THRESHOLD = 300;
    public static final int CAT_FLEE_RADIUS = 12;
    public static final int DELIVERY_RADIUS = 1;
    public static final int SPAWN_INTERVAL = 6;
    public static final int MAX_TARGET_PURSUIT_TURNS = 30;
    public static final int STUCK_THRESHOLD = 3;
    public static final int MIN_NEAR_KING_HAULERS = 6;
    public static final int MIN_NEAR_KING_ATTACKERS = 6;
    public static final int NEAR_KING_COUNT_RADIUS = 25;
    public static final int ENEMY_FLEE_RADIUS = 12;  // Distance to flee from enemy rats

    // ===== RAT KING =====
    public static final int ALERT_RADIUS = 18;
    public static final int ESCAPE_RADIUS = 10;
    public static final int PANIC_RADIUS = 6;
    public static final int ESCAPE_COOLDOWN = 12;

    // ===== ATTACKER =====
    public static final int ATTACKER_CAT_FLEE_RADIUS = 6;
    public static final int ATTACKER_VISION_RADIUS = 20;
    public static final int MAX_STALE_TARGET_TURNS = 50;
    public static final int BITE_CHEESE_BOOST = 10;

    // ===== DIRT DIGGING =====
    public static final int DIG_MIN_CHEESE_HAULER = 500;
    public static final int DIG_MIN_CHEESE_ATTACKER = 1200;

    // ===== DIRECTIONS =====
    public static final Direction[] DIRECTIONS = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST,
    };

    private Constants() {} // prevent instantiation
}
