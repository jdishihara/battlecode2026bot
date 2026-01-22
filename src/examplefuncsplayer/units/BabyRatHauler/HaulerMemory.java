package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.Direction;
import battlecode.common.MapLocation;

import java.util.Random;

import examplefuncsplayer.units.BabyRatHauler.HaulerState.RatState;

/**
 * Per-robot memory for hauler rats.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 */
public class HaulerMemory {

    // ===== PER-ROBOT MEMORY =====
    public static class Mem {
        public RatState currentState = RatState.EXPLORE;
        public RatState previousState = RatState.EXPLORE;

        public MapLocation kingLocation = null;

        public int moveAwayCounter = 0;
        public int stuckCounter = 0;

        public MapLocation mapCenter = null;
        public MapLocation explorationTarget = null;
        public boolean hasReachedCenter = false;
        public Direction explorationDirection = null;

        // Increased from 5 to 8 for better stuck detection
        public MapLocation[] recentLocations = new MapLocation[8];
        public int recentLocationIndex = 0;

        public int turnsAtTarget = 0;

        // Wall-following for unstuck
        public boolean wallFollowing = false;
        public int wallFollowTurns = 0;
        public boolean followLeft = false;

        public MapLocation lastStuckLocation = null;
        public int consecutiveFailedMoves = 0;

        // Mine tracking
        public MapLocation lastSeenMine = null;       // Most recent mine this rat discovered
        public MapLocation assignedMine = null;       // Mine this rat is assigned to harvest
        public int assignedMineCheckRound = -100;     // Last round we checked shared array for assignment

        // NEW: Cache of all known mines (for better distribution across mines)
        public MapLocation[] knownMines = new MapLocation[8];  // Support up to 8 known mines
        public int knownMineCount = 0;

        // 2-squeak mine report protocol
        public MapLocation pendingMineReport = null;  // Mine waiting to be reported to king
        public int pendingMineStage = 0;              // 0 = need to squeak X, 1 = need to squeak Y

        // Scout system: 20% of haulers keep exploring until >=2 mines known
        public final boolean isScout;
        public MapLocation speculatedMineTarget = null;  // Symmetry-based speculation target

        // NEW: Enhanced scouting state (for HaulerScouting)
        public MapLocation scoutTarget = null;
        public int scoutTurnsAtTarget = 0;
        public MapLocation lastScoutTarget = null;

        // NEW: Mine reporting via squeaks (for HaulerScouting 2-squeak protocol)
        public int pendingMineReportY = -1;  // -1 means no pending Y coordinate
        public int lastMineReportRound = -100;  // Round when we last reported a mine

        // NEW: Delivery tracking (for traffic control)
        public boolean hasDeliveredRecently = false;
        public int turnsSinceDelivery = 0;

        // NEW: Exploration timeout (give up on unreachable targets)
        public int turnsAtCurrentExploreTarget = 0;
        public int maxTurnsAtExploreTarget = 40;

        // ===== DEFENSE STATE =====
        public MapLocation currentDefenseTarget = null;  // Enemy we're focusing on
        public int defenseTargetRound = -100;            // Round when we started targeting
        public int lastDefenseTrapRound = -100;          // Round when we last placed a defense trap
        public int defenseTrapCount = 0;                 // Traps this hauler has placed
        public boolean isDefending = false;              // Whether we're actively in defense mode
        public int lastDefenseSqueak = -100;             // Last round we squeaked for defense help

        // ===== OFFENSIVE TRAP STATE =====
        public int lastOffensiveTrapRound = -100;        // Round when we last placed an offensive trap
        public int offensiveTrapCount = 0;               // Offensive traps this hauler has placed

        public final Random rng;

        public Mem(int id) {
            rng = new Random(6147L + id * 31L);
            followLeft = (id % 2 == 0); // Alternate wall follow direction
            isScout = (id % 10 == 0);   // 10% are scouts - most should be hauling
        }

        // ===== MINE CACHING HELPERS =====

        /**
         * Add a mine to the known mines cache if not already present.
         * Returns true if this was a new mine.
         */
        public boolean cacheMineLoc(MapLocation loc) {
            if (loc == null) return false;

            // Check if already cached
            for (int i = 0; i < knownMineCount; i++) {
                if (knownMines[i] != null && knownMines[i].equals(loc)) {
                    return false; // Already known
                }
            }

            // Add to cache
            if (knownMineCount < knownMines.length) {
                knownMines[knownMineCount++] = loc;
            } else {
                // Cache full - overwrite oldest (shift left)
                for (int i = 0; i < knownMines.length - 1; i++) {
                    knownMines[i] = knownMines[i + 1];
                }
                knownMines[knownMines.length - 1] = loc;
            }

            // Also update lastSeenMine for backward compatibility
            lastSeenMine = loc;
            return true;
        }

        /**
         * Get a mine assignment based on robot ID (distributes rats across mines).
         */
        public MapLocation getAssignedMineByID(int robotId) {
            if (knownMineCount == 0) return lastSeenMine; // Fallback

            int assignedIndex = robotId % knownMineCount;
            return knownMines[assignedIndex];
        }

        /**
         * Find the nearest known mine to a location.
         */
        public MapLocation getNearestKnownMine(MapLocation from) {
            if (from == null) return lastSeenMine;

            MapLocation nearest = null;
            int nearestDist = Integer.MAX_VALUE;

            for (int i = 0; i < knownMineCount; i++) {
                if (knownMines[i] != null) {
                    int d = from.distanceSquaredTo(knownMines[i]);
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = knownMines[i];
                    }
                }
            }

            return nearest != null ? nearest : lastSeenMine;
        }

        // ===== STUCK DETECTION HELPERS =====

        /**
         * Reset stuck-related state after successful action (delivery, finding mine, etc.)
         */
        public void resetStuckState() {
            stuckCounter = 0;
            consecutiveFailedMoves = 0;
            wallFollowing = false;
            wallFollowTurns = 0;
            lastStuckLocation = null;
            for (int i = 0; i < recentLocations.length; i++) {
                recentLocations[i] = null;
            }
            recentLocationIndex = 0;
        }

        /**
         * Reset exploration state when transitioning to a new goal.
         */
        public void resetExplorationState() {
            explorationTarget = null;
            turnsAtCurrentExploreTarget = 0;
            turnsAtTarget = 0;
        }

        /**
         * Increment stuck counter and return true if we should try emergency unstuck.
         */
        public boolean incrementStuckAndCheck() {
            stuckCounter++;
            consecutiveFailedMoves++;
            return stuckCounter >= 3 || consecutiveFailedMoves >= 5;
        }
    }

    private static Mem MEM;

    public static Mem mem(int id) {
        if (MEM == null) MEM = new Mem(id);
        return MEM;
    }
}