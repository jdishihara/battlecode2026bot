package examplefuncsplayer.units;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * Utility class for baby rats to check if they should upgrade to a Rat King.
 *
 * Upgrade conditions:
 * 1. Current king count < MAX_DESIRED_KINGS
 * 2. Global cheese >= KING_UPGRADE_CHEESE_THRESHOLD
 * 3. Baby rat count >= KING_UPGRADE_RAT_THRESHOLD
 * 4. No threats (cats/enemies) within KING_UPGRADE_SAFE_DIST_SQ
 * 5. Cooldown since last upgrade has passed
 * 6. rc.canUpgradeToRatKing() returns true (7 allies in 3x3, valid terrain)
 */
public class KingUpgrade {

    /**
     * Check if this baby rat should attempt to upgrade to a Rat King.
     * Call this once per turn from baby rat logic.
     *
     * @param rc The RobotController
     * @return true if upgrade was attempted (successful or not)
     */
    public static boolean tryUpgradeToKing(RobotController rc) throws GameActionException {
        // Quick exit if we can't upgrade (saves bytecode)
        if (!rc.canBecomeRatKing()) {
            return false;
        }

        // Read shared state
        int currentKings = rc.readSharedArray(KING_COUNT_INDEX);
        int babyRatCount = rc.readSharedArray(BABY_RAT_COUNT_INDEX);
        int lastUpgradeRound = rc.readSharedArray(LAST_KING_UPGRADE_ROUND);
        int globalCheese = rc.getGlobalCheese();
        int roundNum = rc.getRoundNum();

        // 1. Check king count limit
        if (currentKings >= MAX_DESIRED_KINGS) {
            return false;
        }

        // 2. Check cheese threshold
        if (globalCheese < KING_UPGRADE_CHEESE_THRESHOLD) {
            return false;
        }

        // 3. Check baby rat count threshold
        if (babyRatCount < KING_UPGRADE_RAT_THRESHOLD) {
            return false;
        }

        // 4. Check cooldown (handle round wraparound)
        int roundsSinceLastUpgrade;
        if (lastUpgradeRound == 0) {
            // No upgrade yet, allow it
            roundsSinceLastUpgrade = KING_UPGRADE_COOLDOWN_ROUNDS + 1;
        } else {
            roundsSinceLastUpgrade = (roundNum - lastUpgradeRound + 1000) % 1000;
            if (roundsSinceLastUpgrade > 500) {
                // Wraparound case - assume enough time has passed
                roundsSinceLastUpgrade = KING_UPGRADE_COOLDOWN_ROUNDS + 1;
            }
        }
        if (roundsSinceLastUpgrade < KING_UPGRADE_COOLDOWN_ROUNDS) {
            return false;
        }

        // 5. Check for nearby threats (cats and enemy rats)
        if (!isPositionSafeForUpgrade(rc)) {
            return false;
        }

        // All conditions met - attempt upgrade!
        rc.becomeRatKing();

        // Note: After upgrading, this code won't continue executing
        // because the robot type changes. The new king will start
        // running RatKing.java logic on the next turn.
        return true;
    }

    /**
     * Check if the current position is safe enough for king upgrade.
     * No cats or enemy rats within KING_UPGRADE_SAFE_DIST_SQ.
     */
    private static boolean isPositionSafeForUpgrade(RobotController rc) throws GameActionException {
        MapLocation here = rc.getLocation();
        Team enemyTeam = rc.getTeam().opponent();

        // Sense nearby robots
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(KING_UPGRADE_SAFE_DIST_SQ);

        for (RobotInfo robot : nearbyRobots) {
            // Check for cats
            if (robot.getType() == UnitType.CAT) {
                return false;
            }
            // Check for enemy rats
            if (robot.getTeam() == enemyTeam) {
                if (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Check if conditions are favorable for upgrading (without actually upgrading).
     * Useful for rats to decide whether to cluster for upgrade.
     */
    public static boolean areUpgradeConditionsMet(RobotController rc) throws GameActionException {
        int currentKings = rc.readSharedArray(KING_COUNT_INDEX);
        int babyRatCount = rc.readSharedArray(BABY_RAT_COUNT_INDEX);
        int globalCheese = rc.getGlobalCheese();

        return currentKings < MAX_DESIRED_KINGS
            && globalCheese >= KING_UPGRADE_CHEESE_THRESHOLD
            && babyRatCount >= KING_UPGRADE_RAT_THRESHOLD;
    }

    /**
     * Check if the cluster-for-upgrade flag is set.
     */
    public static boolean isClusteringActive(RobotController rc) throws GameActionException {
        return rc.readSharedArray(CLUSTER_FOR_UPGRADE_FLAG) == 1;
    }

    /**
     * Get the cluster rally point location.
     * Returns null if clustering is not active.
     */
    public static MapLocation getClusterRallyPoint(RobotController rc) throws GameActionException {
        if (!isClusteringActive(rc)) {
            return null;
        }
        int x = rc.readSharedArray(CLUSTER_X_INDEX);
        int y = rc.readSharedArray(CLUSTER_Y_INDEX);
        if (x == 0 && y == 0) {
            return null;
        }
        return new MapLocation(x, y);
    }

    /**
     * Check if this rat should participate in clustering.
     * Only a subset of rats should cluster - those closest to the rally point.
     */
    public static boolean shouldParticipateInClustering(RobotController rc, MapLocation rallyPoint) throws GameActionException {
        if (rallyPoint == null) {
            return false;
        }

        MapLocation here = rc.getLocation();
        int distSq = here.distanceSquaredTo(rallyPoint);

        // Rats within a reasonable distance should participate
        // Use robot ID to create some randomness in who clusters
        int maxDistSq = 100 + (rc.getID() % 50);  // 10-12 tiles
        return distSq <= maxDistSq;
    }

    /**
     * Clear the cluster flag (called after successful upgrade or timeout).
     * Note: Only kings can write to shared array, so this is called from RatKing.
     */
    public static void clearClusterFlag(RobotController rc) throws GameActionException {
        rc.writeSharedArray(CLUSTER_FOR_UPGRADE_FLAG, 0);
    }
}
