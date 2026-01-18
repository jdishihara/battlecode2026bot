package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * RatKing logic: spawns baby rats, broadcasts shared info, and evades cats.
 *
 * v4 Updates:
 * - DIRT WALL DEFENSE: When escaping from cats, king now builds 2-tile dirt walls
 * - Prioritizes dirt walls over cat traps when dirt is available
 * - Falls back to cat traps if no dirt available or wall placement fails
 */
public class RatKing {

    /**
     * Main per-turn entrypoint for the Rat King.
     */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int globalCheese = rc.getGlobalCheese();
        int spawnCost = rc.getCurrentRatCost();
        int roundNum = rc.getRoundNum();

        // ===== INITIALIZE SYMMETRY TRACKING (first turn only) =====
        if (!KingRuntimeState.symmetryInitialized) {
            SymmetryManager.initializeSymmetryTracking(rc, roundNum);
            // Write King's ID so baby rats can calculate spawn order
            rc.writeSharedArray(KING_ID_INDEX, rc.getID() % 1000);
            KingRuntimeState.symmetryInitialized = true;
        }

        // ===== UPDATE SYMMETRY STATE (every turn) =====
        SymmetryManager.updateSymmetryState(rc, roundNum);

        // ===== PROCESS INCOMING SQUEAKS =====
        EnemyDetection.processIncomingSqueaks(rc, roundNum);

        // ===== LISTEN FOR MINE REPORTS & BROADCAST VISIBLE MINES =====
        MineTracking.listenForMineReports(rc);
        MineTracking.broadcastVisibleMines(rc);

        // ===== LOOK FOR ENEMY KING DIRECTLY =====
        EnemyDetection.detectAndBroadcastEnemyKing(rc, roundNum);

        // ===== CAT DETECTION =====
        RobotInfo nearestCat = Sensing.findNearestCatForKing(rc);
        MapLocation catLocation = null;
        int catDistSq = Integer.MAX_VALUE;

        if (nearestCat != null) {
            catLocation = nearestCat.getLocation();
            catDistSq = myLocation.distanceSquaredTo(catLocation);
        }

        RobotInfo nearestEnemyRat = Sensing.findNearestEnemyRat(rc);
        MapLocation enemyRatLocation = null;
        int enemyRatDistSq = Integer.MAX_VALUE;

        if (nearestEnemyRat != null) {
            enemyRatLocation = nearestEnemyRat.getLocation();
            enemyRatDistSq = myLocation.distanceSquaredTo(enemyRatLocation);
        }

        // ===== STATE TRANSITIONS =====
        // More aggressive state transitions for better defense
        final int ENEMY_RAT_ESCAPE_RADIUS_SQ = 64;  // 8 tiles - escape earlier from enemy rats
        final int CAT_ALERT_RADIUS_SQ = ALERT_RADIUS * ALERT_RADIUS;
        final int ENEMY_RAT_ALERT_RADIUS_SQ = 144;  // 12 tiles

        if (nearestCat == null && nearestEnemyRat == null) {
            KingRuntimeState.safeRoundsCounter++;
            if (KingRuntimeState.safeRoundsCounter >= 5) {
                KingRuntimeState.kingState = KingState.SAFE;
            }
        } else {
            KingRuntimeState.safeRoundsCounter = 0;

            // Escape conditions - more aggressive thresholds
            boolean catTooClose = (catDistSq <= ESCAPE_RADIUS * ESCAPE_RADIUS);
            boolean enemyRatTooClose = (enemyRatDistSq <= ENEMY_RAT_ESCAPE_RADIUS_SQ);

            // Also escape if multiple enemy rats are nearby (swarming)
            int nearbyEnemyCount = Sensing.countNearbyEnemyRats(rc, 100); // within 10 tiles
            boolean enemySwarm = nearbyEnemyCount >= 3;

            if (catTooClose || enemyRatTooClose || enemySwarm) {
                KingRuntimeState.kingState = KingState.ESCAPE;
            } else if (catDistSq <= CAT_ALERT_RADIUS_SQ || enemyRatDistSq <= ENEMY_RAT_ALERT_RADIUS_SQ) {
                KingRuntimeState.kingState = KingState.ALERT;
            } else {
                // Distant threats - stay alert but less urgent
                KingRuntimeState.kingState = KingState.ALERT;
            }
        }

        // ===== BROADCAST TO SHARED ARRAY =====
        rc.writeSharedArray(KING_X_INDEX, myLocation.x);
        rc.writeSharedArray(KING_Y_INDEX, myLocation.y);
        rc.writeSharedArray(CHEESE_STATE_INDEX, globalCheese < LOW_CHEESE_THRESHOLD ? 0 : 1);

        int dangerValue;
        switch (KingRuntimeState.kingState) {
            case SAFE:   dangerValue = 0; break;
            case ALERT:  dangerValue = 1; break;
            case ESCAPE: dangerValue = 2; break;
            default:     dangerValue = 0; break;
        }
        rc.writeSharedArray(DANGER_INDEX, dangerValue);

        if (catLocation != null) {
            rc.writeSharedArray(CAT_X_INDEX, catLocation.x);
            rc.writeSharedArray(CAT_Y_INDEX, catLocation.y);
        }
        if (enemyRatLocation != null) {
            rc.writeSharedArray(ENEMY_RAT_X_INDEX, enemyRatLocation.x);
            rc.writeSharedArray(ENEMY_RAT_Y_INDEX, enemyRatLocation.y);
            int threatLevel = (enemyRatDistSq < 25) ? 2 : 1;
            rc.writeSharedArray(ENEMY_THREAT_LEVEL, threatLevel);
        } else {
            rc.writeSharedArray(ENEMY_THREAT_LEVEL, 0);
        }

        // ===== BEHAVIOR BY STATE =====
        switch (KingRuntimeState.kingState) {
            case SAFE:
                StateBehaviors.runKingSafeState(rc, globalCheese, spawnCost, turnCount, enemyRatLocation);
                break;
            case ALERT:
                StateBehaviors.runKingAlertState(rc, globalCheese, spawnCost, enemyRatLocation, catLocation, catDistSq, roundNum);
                break;
            case ESCAPE:
                StateBehaviors.runKingEscapeState(rc, catLocation, catDistSq, roundNum, globalCheese, enemyRatLocation);
                break;
        }
        StateBehaviors.kingCounterAttack(rc);

        // Debug string with symmetry info and dirt count
        int currentSym = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int eliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
        String symStr = currentSym == 1 ? "ROT" : currentSym == 2 ? "HOR" : currentSym == 3 ? "VER" : "UNK";
        String elimStr = ((eliminated & 1) != 0 ? "R" : "") +
                         ((eliminated & 2) != 0 ? "H" : "") +
                         ((eliminated & 4) != 0 ? "V" : "");

        rc.setIndicatorString("King " + KingRuntimeState.kingState + " | Cheese: " + globalCheese +
                " | Dirt: " + rc.getDirt() +
                " | Sym:" + symStr + " Elim:" + elimStr +
                " | EnemyKing: " + (KingRuntimeState.lastKnownEnemyKingLoc != null ? KingRuntimeState.lastKnownEnemyKingLoc : "?"));
    }
}
