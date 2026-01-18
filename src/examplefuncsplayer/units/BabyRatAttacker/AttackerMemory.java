package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.Direction;
import battlecode.common.MapLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Per-robot memory for attacker rats.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 */
public class AttackerMemory {

    public static class Mem {
        public MapLocation kingLocation = null;

        public AttackerState.AttackStateEnum state = AttackerState.AttackStateEnum.SEEK;
        public MapLocation targetKingLoc = null;      // Confirmed enemy king location
        public int turnsSinceKingSeen = 0;
        public MapLocation currentTarget = null;       // Current movement target
        public int turnsAtTarget = 0;

        // BUG nav memory
        public boolean bugging = false;
        public boolean bugLeft = false;
        public int bugTurns = 0;
        public int failedMoves = 0;
        public MapLocation prev1 = null;
        public MapLocation prev2 = null;

        public MapLocation mapCenter = null;

        // Combat coordination
        public int lastSqueakRound = -100;
        public int lastTrapRound = -100;
        public MapLocation swarmTarget = null;
        public int swarmStartRound = -100;

        // Global alert (from shared array)
        public MapLocation alertRallyLoc = null;
        public int alertReason = 0;

        // Local mesh squeak cooldown
        public int lastHelpSqueakRound = -100;

        // Local swarm response (from nearby MSG_HELP squeaks)
        public MapLocation localSwarmTarget = null;
        public int localSwarmUntilRound = -1;

        // === FORMATION MOVEMENT TRACKING ===
        public int latestFormationSeq = -1;           // Latest formation sequence number seen
        public int formationSeenRound = -1;           // Round when formation msg was seen
        public MapLocation lastFormationSourceLoc = null;  // Location of rat that sent formation msg
        public Direction lastFormationDir = null;     // Direction from formation message
        public int lastSeqForwarded = -1;             // Last sequence number we forwarded
        public boolean isLeader = false;              // True if this rat is the local leader this turn
        public int leaderStuckCount = 0;              // Turns stuck as leader (resets on move)
        public int stuckCooldown = 0;                 // Rounds to wait before re-attempting leadership

        // === CAT MEMORY ===
        public MapLocation lastCatLoc = null;         // Last known cat location (any range)
        public int lastCatSeenRound = -100;           // Round when cat was last seen
        public int lastCatSqueakRound = -100;         // For cat-spot squeak cooldown
        public boolean adoptedCatLoc = false;         // True if lastCatLoc came from squeak (for debug)
        public boolean broadcastedCat = false;        // True if we broadcasted cat this turn (for debug)

        // === CAT VELOCITY PREDICTION ===
        public MapLocation prevCatLoc = null;         // Cat location from previous sighting
        public int prevCatSeenRound = -100;           // Round of previous sighting
        public int catVelX = 0;                       // Estimated cat velocity X (tiles per round)
        public int catVelY = 0;                       // Estimated cat velocity Y (tiles per round)
        public MapLocation predictedCatLoc = null;    // Where we think cat will be

        // === CURRENT TURN CAT INFO (for movement layer) ===
        public MapLocation turnCatLoc = null;         // Cat location this turn (null if none)
        public int turnCatBand = AttackerState.CAT_BAND_NONE;  // Distance band this turn

        // === CLUSTERED SYMMETRY SEARCH ===
        public MapLocation[] possibleEnemyKingLocs = null;  // [rot, horiz, vert] - indices 0,1,2
        public int localSymmetryEliminated = 0;             // Local bitmask of eliminated symmetries
        public int lastSymmetryChecked = 0;                 // Track which symmetry we last checked (for timeout reset)
        public boolean reachedCurrentTarget = false;        // Have we arrived at current symmetry target?
        public int roundsSearchingCurrentSymmetry = 0;      // How long we've searched current symmetry

        public final Random rng;

        public Mem(int id) {
            rng = new Random(9001L + id * 97L);
            bugLeft = ((id / 2) % 2 == 0);
        }
    }

    private static final Map<Integer, Mem> MEM = new HashMap<>();

    public static Mem mem(int id) {
        Mem m = MEM.get(id);
        if (m == null) {
            m = new Mem(id);
            MEM.put(id, m);
        }
        return m;
    }
}
