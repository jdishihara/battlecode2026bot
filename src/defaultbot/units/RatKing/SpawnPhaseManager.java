package defaultbot.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * Manages spawn phase logic for the Rat King.
 *
 * 50/50 SPLIT MODE: Role assignment is based on robot ID (even=attacker, odd=hauler).
 * This class tracks rat counts for spawn cost calculations.
 */
public class SpawnPhaseManager {

    private static int lastEnemyCountUpdate = 0;

    /**
     * Update spawn tracking - counts rats for spawn cost calculations.
     * Role assignment is handled by RobotPlayer using ID-based 50/50 split.
     * Should be called every turn by the Rat King.
     */
    public static void updateSpawnPhase(RobotController rc) throws GameActionException {
        int roundNum = rc.getRoundNum();

        // Count visible baby rats
        int visibleRatCount = countOurBabyRats(rc);
        rc.writeSharedArray(OUR_RAT_COUNT_INDEX, Math.min(visibleRatCount, 1000));

        // Update enemy count periodically
        if (roundNum - lastEnemyCountUpdate >= 5) {
            int enemyCount = estimateEnemyRatCount(rc);
            rc.writeSharedArray(ENEMY_RAT_COUNT_INDEX, Math.min(enemyCount, 1000));
            lastEnemyCountUpdate = roundNum;
        }

        // 50/50 MODE: Spawn phase not used for role assignment (ID-based split)
        // Keep phase at 1 for compatibility with any code that reads it
        int currentPhase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        if (currentPhase != 1) {
            rc.writeSharedArray(SPAWN_PHASE_INDEX, 1);
        }
    }

    /**
     * Estimate the cost to spawn N more rats based on current population.
     */
    public static int estimateCostForNRats(RobotController rc, int n) throws GameActionException {
        int numBabyRats = rc.readSharedArray(OUR_RAT_COUNT_INDEX);
        // Also count haulers spawned since our count might be low due to visibility
        int haulersSpawned = rc.readSharedArray(HAULERS_SPAWNED_INDEX);
        int attackersSpawned = rc.readSharedArray(ATTACKERS_SPAWNED_INDEX);

        // Use max of visible count and spawn tracking
        int estimatedTotal = Math.max(numBabyRats, haulersSpawned + attackersSpawned);

        int totalCost = 0;
        for (int i = 0; i < n; i++) {
            int currentCost = 10 + 10 * ((estimatedTotal + i) / 4);
            totalCost += currentCost;
        }
        return totalCost;
    }

    /**
     * Count our baby rats visible in sensor range.
     */
    private static int countOurBabyRats(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        int count = 0;

        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.BABY_RAT) {
                count++;
            }
        }

        return count;
    }

    /**
     * Estimate enemy rat count from sightings and shared array data.
     */
    private static int estimateEnemyRatCount(RobotController rc) throws GameActionException {
        Team enemyTeam = rc.getTeam().opponent();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, enemyTeam);
        int count = 0;

        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                count++;
            }
        }

        // Use max of current sighting and previous estimate (enemies may be out of range)
        int previousEstimate = rc.readSharedArray(ENEMY_RAT_COUNT_INDEX);

        // Decay previous estimate slightly to avoid stale high counts
        int decayedEstimate = (previousEstimate * 9) / 10;

        return Math.max(count, decayedEstimate);
    }

    /**
     * Estimate how many more rats we could spawn with available cheese.
     */
    public static int estimateSpawnable(RobotController rc) throws GameActionException {
        int numBabyRats = rc.readSharedArray(OUR_RAT_COUNT_INDEX);
        int spawnCost = 10 + 10 * (numBabyRats / 4);
        int availableCheese = rc.getGlobalCheese() - KING_RESERVE;

        if (availableCheese <= 0) return 0;

        int spawnable = 0;
        int remaining = availableCheese;
        int currentCost = spawnCost;

        while (remaining >= currentCost && spawnable < 50) {
            remaining -= currentCost;
            spawnable++;
            if ((numBabyRats + spawnable) % 4 == 0) {
                currentCost += 10;
            }
        }

        return spawnable;
    }

    /**
     * Check if we're in a hauler-spawning phase (phase 0 or 2).
     */
    public static boolean isHaulerPhase(RobotController rc) throws GameActionException {
        int phase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        return phase == 0 || phase == 2;
    }

    /**
     * Check if we're in an attacker-spawning phase (phase 1 or 3).
     */
    public static boolean isAttackerPhase(RobotController rc) throws GameActionException {
        int phase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        return phase == 1 || phase == 3;
    }

    /**
     * Check if we're still in initial phases (0 or 1).
     */
    public static boolean isInitialPhase(RobotController rc) throws GameActionException {
        int phase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        return phase == 0 || phase == 1;
    }
}
