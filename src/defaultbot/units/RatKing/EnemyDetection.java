package defaultbot.units.RatKing;

import battlecode.common.*;

import static defaultbot.config.Constants.*;
import static defaultbot.config.Shared.*;

/**
 * Enemy king detection and broadcasting for the Rat King.
 */
public class EnemyDetection {

    /**
     * Process incoming squeaks from baby rats.
     * Handles: enemy king found, symmetry elimination reports
     */
    public static void processIncomingSqueaks(RobotController rc, int roundNum) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);

        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();

            // === Handle symmetry elimination reports ===
            if (content >= SQUEAK_SYMMETRY_ELIMINATED && content < SQUEAK_SYMMETRY_ELIMINATED + 30) {
                int eliminatedSymIndex = (content - SQUEAK_SYMMETRY_ELIMINATED) / 10;
                if (eliminatedSymIndex >= 0 && eliminatedSymIndex < 3) {
                    SymmetryManager.eliminateSymmetry(rc, eliminatedSymIndex + 1); // Convert to 1-indexed
                }
                continue;
            }

            // === Handle enemy king found ===
            if (content == SQUEAK_ENEMY_KING_FOUND) {
                // Baby rat found the enemy king! Broadcast to shared array
                broadcastEnemyKingLocation(rc, senderLoc, roundNum);

                // Set swarm target so all attackers converge
                rc.writeSharedArray(SWARM_TARGET_X, senderLoc.x);
                rc.writeSharedArray(SWARM_TARGET_Y, senderLoc.y);
                rc.writeSharedArray(SWARM_ACTIVE_ROUND, roundNum % 1000);
            }
            else if (content == SQUEAK_ENEMY_ATTACKING_KING) {
                // Our rats are under attack - could increase threat level
            }
        }
    }

    /**
     * Look for enemy king directly with rat king's 360 vision.
     * If found, broadcast location and confirm symmetry.
     */
    public static void detectAndBroadcastEnemyKing(RobotController rc, int roundNum) throws GameActionException {
        RobotInfo enemyKing = findEnemyKing(rc);

        if (enemyKing != null) {
            MapLocation enemyKingLoc = enemyKing.getLocation();
            KingRuntimeState.lastKnownEnemyKingLoc = enemyKingLoc;
            KingRuntimeState.lastEnemyKingSeenRound = roundNum;

            // Broadcast to shared array
            broadcastEnemyKingLocation(rc, enemyKingLoc, roundNum);

            // Determine and confirm symmetry type
            int symmetry = determineSymmetry(rc, enemyKingLoc);
            if (symmetry > 0) {
                // Confirm this symmetry and eliminate others
                rc.writeSharedArray(CURRENT_SYMMETRY_INDEX, symmetry);

                // Eliminate other symmetries
                int eliminateMask = 0b111 ^ (1 << (symmetry - 1)); // All except confirmed
                int currentEliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);
                rc.writeSharedArray(SYMMETRY_ELIMINATED_INDEX, currentEliminated | eliminateMask);
            }
        }
    }

    /**
     * Broadcast enemy king location to shared array.
     */
    public static void broadcastEnemyKingLocation(RobotController rc, MapLocation loc, int roundNum) throws GameActionException {
        rc.writeSharedArray(ENEMY_KING_X_INDEX, loc.x);
        rc.writeSharedArray(ENEMY_KING_Y_INDEX, loc.y);
        rc.writeSharedArray(ENEMY_KING_SEEN_ROUND_INDEX, roundNum % 1000);

        KingRuntimeState.lastKnownEnemyKingLoc = loc;
        KingRuntimeState.lastEnemyKingSeenRound = roundNum;
    }

    /**
     * Find enemy rat king within vision range.
     */
    public static RobotInfo findEnemyKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(25, rc.getTeam().opponent());

        for (RobotInfo robot : nearby) {
            if (robot.getType() == UnitType.RAT_KING) {
                return robot;
            }
        }
        return null;
    }

    /**
     * Determine which symmetry type the map uses based on enemy king location.
     * Returns: 1=rotational, 2=horizontal, 3=vertical, 0=unknown
     */
    public static int determineSymmetry(RobotController rc, MapLocation enemyKingLoc) throws GameActionException {
        MapLocation ourKing = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        MapLocation rotational = new MapLocation(w - 1 - ourKing.x, h - 1 - ourKing.y);
        MapLocation horizontal = new MapLocation(w - 1 - ourKing.x, ourKing.y);
        MapLocation vertical = new MapLocation(ourKing.x, h - 1 - ourKing.y);

        // Check which one is closest (within ~4 tiles for 3x3 king)
        if (enemyKingLoc.distanceSquaredTo(rotational) <= 18) return 1;
        if (enemyKingLoc.distanceSquaredTo(horizontal) <= 18) return 2;
        if (enemyKingLoc.distanceSquaredTo(vertical) <= 18) return 3;

        return 0;
    }
}
