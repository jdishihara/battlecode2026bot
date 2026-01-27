package defaultbot.units.RatKing;

import battlecode.common.*;

import static defaultbot.config.Constants.*;
import static defaultbot.config.Shared.*;

/**
 * Manages spawn phase logic for the Rat King.
 *
 * Phase 0: Initial Hauler Phase - spawn 30 haulers first
 * Phase 2: Main Hauler Phase - spawn haulers continuously
 * Phase 3: Attacker Wave Phase - spawn 8 attackers every 200 rounds, then back to Phase 2
 */
public class SpawnPhaseManager {

    private static int lastEnemyCountUpdate = 0;
    private static int lastAttackerWaveRound = 0;
    private static final int ATTACKER_WAVE_INTERVAL = 200;

    /**
     * Update spawn phase based on spawn counts and cheese thresholds.
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

        // Read current phase and spawn counts
        int currentPhase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        int attackersSpawned = rc.readSharedArray(ATTACKERS_SPAWNED_INDEX);
        int haulersSpawned = rc.readSharedArray(HAULERS_SPAWNED_INDEX);

        switch (currentPhase) {
            case 0: // Initial Hauler Phase
                if (haulersSpawned >= INITIAL_HAULER_COUNT) {
                    // Done with initial haulers, switch to main hauler phase
                    rc.writeSharedArray(SPAWN_PHASE_INDEX, 2);
                    lastAttackerWaveRound = roundNum; // Reset timer so first wave is 300 rounds from now
                    System.out.println("PHASE SWITCH: Initial Hauler -> Main Hauler (haulers: " + haulersSpawned + ")");
                }
                break;

            case 2: // Main Hauler Phase
                // Check if 300 rounds have passed since last attacker wave
                if (roundNum - lastAttackerWaveRound >= ATTACKER_WAVE_INTERVAL) {
                    // Switch to attacker wave phase
                    rc.writeSharedArray(SPAWN_PHASE_INDEX, 3);
                    rc.writeSharedArray(ATTACKERS_SPAWNED_INDEX, 0);
                    lastAttackerWaveRound = roundNum;
                    System.out.println("PHASE SWITCH: Main Hauler -> Attacker Wave (round: " + roundNum + ")");
                }
                break;

            case 3: // Attacker Wave Phase
                if (attackersSpawned >= ATTACKER_WAVE_SIZE) {
                    // Done with attacker wave, switch back to main hauler phase
                    rc.writeSharedArray(SPAWN_PHASE_INDEX, 2);
                    System.out.println("PHASE SWITCH: Attacker Wave -> Main Hauler (attackers spawned: " + attackersSpawned + ")");
                }
                break;
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
     * Check if we're in an attacker-spawning phase (phase 3).
     */
    public static boolean isAttackerPhase(RobotController rc) throws GameActionException {
        int phase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        return phase == 3;
    }

    /**
     * Check if we're still in initial phase (0).
     */
    public static boolean isInitialPhase(RobotController rc) throws GameActionException {
        int phase = rc.readSharedArray(SPAWN_PHASE_INDEX);
        return phase == 0;
    }
}
