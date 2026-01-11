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
    public static final int ENEMY_FLEE_RADIUS = 12;

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

    // Defensive constants
    public static final int MIN_DEFENDERS_NEAR_KING = 5;
    public static final int DEFENSIVE_TRAP_RADIUS = 2;
    public static final int KING_ATTACK_CHEESE_BOOST = 50;

    // ===== COMBAT & SWARMING (NEW) =====
    public static final int SQUEAK_COOLDOWN = 8;              // Rounds between squeaks (avoid cat attention)
    public static final int SQUEAK_LISTEN_ROUNDS = 5;         // How far back to read messages
    public static final int SWARM_RESPONSE_DISTANCE_SQ = 400; // Respond to squeaks within 20 units
    public static final int TRAP_CHEESE_THRESHOLD = 200;      // Min cheese to place offensive traps
    public static final int OFFENSIVE_TRAP_COOLDOWN = 15;     // Rounds between offensive trap placements
    public static final int MAX_TEAM_RAT_TRAPS = 25;          // From spec - max rat traps per team
    public static final int RAT_TRAP_COST = 30;               // From spec
    public static final int RAT_TRAP_DAMAGE = 50;             // From spec
    public static final int GANG_UP_THRESHOLD = 3;            // Attack if 3+ allies nearby
    public static final int COORDINATED_ATTACK_RADIUS = 16; 
  
       // 0=unknown, 1=rotational, 2=horizontal, 3=vertical
  // Check for allies in this radius

    // ===== SQUEAK MESSAGE TYPES =====
    // Encoded in the squeak's messageContent integer
    public static final int SQUEAK_ENEMY_KING_FOUND = 1;
    public static final int SQUEAK_NEED_BACKUP = 2;
    public static final int SQUEAK_ENEMY_ATTACKING_KING = 3;
    public static final int SQUEAK_RETREAT = 4;
    public static final int SQUEAK_TRAP_PLACED = 5;

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

    private Constants() {}
}