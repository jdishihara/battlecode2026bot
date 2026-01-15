package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;

/**
 * Combat-related helpers for attacker rats.
 * Handles trap placement and dirt digging.
 */
public class AttackerCombat {

    /**
     * Try to place an offensive trap near the enemy king.
     */
    public static boolean tryPlaceOffensiveTrap(RobotController rc, Mem m, MapLocation enemyKingCenter)
            throws GameActionException {
        MapLocation here = rc.getLocation();

        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        // Predict retreat direction
        MapLocation enemySpawn = m.possibleEnemyKingLocs != null ?
            m.possibleEnemyKingLocs[0] : new MapLocation(mapW / 2, mapH / 2);

        Direction retreatDir = enemyKingCenter.directionTo(enemySpawn);

        // Fill reusable buffer
        m.buf5[0] = retreatDir;
        m.buf5[1] = retreatDir.rotateLeft();
        m.buf5[2] = retreatDir.rotateRight();
        m.buf5[3] = retreatDir.rotateLeft().rotateLeft();
        m.buf5[4] = retreatDir.rotateRight().rotateRight();

        for (int di = 0; di < 5; di++) {
            Direction dir = m.buf5[di];
            for (int dist = 1; dist <= 2; dist++) {
                MapLocation trapLoc = enemyKingCenter;
                for (int j = 0; j < dist; j++) {
                    trapLoc = trapLoc.add(dir);
                }

                if (here.isAdjacentTo(trapLoc) && rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    return true;
                }
            }
        }

        Direction toEnemy = here.directionTo(enemyKingCenter);
        MapLocation fallbackLoc = here.add(toEnemy);
        if (rc.canPlaceRatTrap(fallbackLoc)) {
            rc.placeRatTrap(fallbackLoc);
            return true;
        }

        return false;
    }

    /**
     * Attempts to dig dirt blocking the path toward the objective, but ONLY if this rat
     * is among the "front rats" of the clump. This prevents all rats from digging and
     * spreading into a line at choke points.
     *
     * Gates:
     * - Not in CAT_BAND_DANGER or CAT_BAND_PANIC
     * - Action cooldown ready (< 10)
     * - Global cheese >= DIG_MIN_CHEESE_ATTACKER
     * - Per-rat cooldown passed (roundNum - lastClumpDigRound >= CLUMP_DIG_COOLDOWN)
     * - At least CLUMP_DIG_MIN_ALLIES allied baby rats within CLUMP_DIG_ALLY_RADIUS_SQ
     * - This rat is among top CLUMP_DIG_MAX_FRONT_RATS closest to the blocked tile
     * - The blocked tile is dirt (not wall)
     *
     * @param rc RobotController
     * @param m Mem state
     * @param objectiveLoc The location we're trying to reach
     * @param nearbyAllies Pre-sensed nearby allies
     * @param roundNum Current round number
     * @return true if we dug dirt, false otherwise
     */
    public static boolean maybeDigForClump(RobotController rc, Mem m, MapLocation objectiveLoc,
                                    RobotInfo[] nearbyAllies, int roundNum) throws GameActionException {
        // === GATE 1: Cat safety - don't dig if cat is dangerous (need mobility) ===
        if (m.turnCatBand >= CAT_BAND_DANGER) {
            return false;
        }

        // === GATE 2: Action cooldown ready ===
        if (rc.getActionCooldownTurns() >= 10) {
            return false;
        }

        // === GATE 3: Per-rat dig cooldown ===
        if (roundNum - m.lastClumpDigRound < CLUMP_DIG_COOLDOWN) {
            return false;
        }

        // === GATE 4: Sufficient global cheese ===
        if (rc.getGlobalCheese() < DIG_MIN_CHEESE_ATTACKER) {
            return false;
        }

        MapLocation here = rc.getLocation();
        Direction desiredDir = here.directionTo(objectiveLoc);
        if (desiredDir == Direction.CENTER) {
            return false;
        }

        MapLocation blockedLoc = here.add(desiredDir);

        // === GATE 5: Check if the blocking tile is actually dirt ===
        if (!rc.canSenseLocation(blockedLoc)) {
            return false;
        }
        MapInfo blockedInfo = rc.senseMapInfo(blockedLoc);
        if (!blockedInfo.isDirt()) {
            return false;  // Not dirt (might be wall, robot, or passable)
        }

        // === GATE 6: Count nearby allied baby rats - need a clump to justify digging ===
        int allyCount = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.BABY_RAT) {
                int distSq = here.distanceSquaredTo(ally.getLocation());
                if (distSq <= CLUMP_DIG_ALLY_RADIUS_SQ) {
                    allyCount++;
                }
            }
        }
        if (allyCount < CLUMP_DIG_MIN_ALLIES) {
            return false;  // Not enough allies nearby - don't dig alone
        }

        // === GATE 7: Front-of-clump check - only top N closest rats may dig ===
        // Count how many allies are closer to the blocked tile than we are
        int myDistToBlocked = here.distanceSquaredTo(blockedLoc);
        int ratsCloserThanMe = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.BABY_RAT) {
                int allyDistToBlocked = ally.getLocation().distanceSquaredTo(blockedLoc);
                if (allyDistToBlocked < myDistToBlocked) {
                    ratsCloserThanMe++;
                }
            }
        }
        // Only dig if we're among the top CLUMP_DIG_MAX_FRONT_RATS closest
        // (i.e., at most CLUMP_DIG_MAX_FRONT_RATS - 1 rats are closer)
        if (ratsCloserThanMe >= CLUMP_DIG_MAX_FRONT_RATS) {
            return false;  // Too many rats closer - let them dig instead
        }

        // === ALL GATES PASSED - ATTEMPT TO DIG ===
        // Turn toward the dirt if needed (for vision cone)
        if (rc.getDirection() != desiredDir && rc.canTurn(desiredDir)) {
            rc.turn(desiredDir);
        }

        if (rc.canRemoveDirt(blockedLoc)) {
            rc.removeDirt(blockedLoc);
            m.lastClumpDigRound = roundNum;
            return true;
        }

        return false;
    }

    /**
     * Attempts to dig dirt that is directly blocking movement toward a target.
     * This is the "bulldoze" behavior - dig through dirt instead of going around.
     *
     * Gates:
     * - Not in CAT_BAND_DANGER or CAT_BAND_PANIC
     * - Action cooldown ready (< 10)
     * - Global cheese >= DIG_MIN_CHEESE_ATTACKER
     * - Per-rat cooldown passed (roundNum - lastClumpDigRound >= CLUMP_DIG_COOLDOWN)
     * - The blocked tile is dirt (not wall, not robot)
     * - In formation modes: requires allies gate; in solo mode: allow digging alone
     *
     * @param rc RobotController
     * @param m Mem state
     * @param targetLoc The location we're trying to reach
     * @param nearbyAllies Pre-sensed nearby allies (can be null for solo mode)
     * @param roundNum Current round number
     * @param requireAllies If true, requires CLUMP_DIG_MIN_ALLIES nearby to dig
     * @return true if we dug dirt, false otherwise
     */
    public static boolean maybeDigBlockingDirt(RobotController rc, Mem m, MapLocation targetLoc,
                                        RobotInfo[] nearbyAllies, int roundNum, boolean requireAllies)
            throws GameActionException {

        // === GATE 1: Cat safety - NEVER dig in DANGER or PANIC (need mobility) ===
        if (m.turnCatBand >= CAT_BAND_DANGER) {
            return false;
        }

        // === GATE 2: Action cooldown ready ===
        if (rc.getActionCooldownTurns() >= 10) {
            return false;
        }

        // === GATE 3: Per-rat dig cooldown (prevents spam) ===
        if (roundNum - m.lastClumpDigRound < CLUMP_DIG_COOLDOWN) {
            return false;
        }

        // === GATE 4: Sufficient global cheese ===
        if (rc.getGlobalCheese() < DIG_MIN_CHEESE_ATTACKER) {
            return false;
        }

        MapLocation here = rc.getLocation();
        Direction desiredDir = here.directionTo(targetLoc);

        // If already at target, nothing to dig
        if (desiredDir == Direction.CENTER) {
            return false;
        }

        MapLocation blockedLoc = here.add(desiredDir);

        // === GATE 5: Check map bounds ===
        if (!rc.onTheMap(blockedLoc)) {
            return false;
        }

        // === GATE 6: Turn toward dirt if needed (for vision cone) ===
        // Must be able to sense the location to check if it's dirt
        if (!rc.canSenseLocation(blockedLoc)) {
            // Try turning toward it first
            if (rc.getDirection() != desiredDir && rc.canTurn(desiredDir)) {
                rc.turn(desiredDir);
            }
            // Re-check after turn
            if (!rc.canSenseLocation(blockedLoc)) {
                return false;  // Still can't sense it
            }
        }

        // === GATE 7: Check if the blocking tile is actually dirt ===
        MapInfo blockedInfo = rc.senseMapInfo(blockedLoc);
        if (!blockedInfo.isDirt()) {
            return false;  // Not dirt (wall, passable, or occupied by robot)
        }

        // === GATE 8: Allies gate (only in formation modes) ===
        if (requireAllies && nearbyAllies != null) {
            int allyCount = 0;
            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() == UnitType.BABY_RAT) {
                    int distSq = here.distanceSquaredTo(ally.getLocation());
                    if (distSq <= CLUMP_DIG_ALLY_RADIUS_SQ) {
                        allyCount++;
                    }
                }
            }
            if (allyCount < CLUMP_DIG_MIN_ALLIES) {
                return false;  // Not enough allies nearby in formation mode
            }
        }

        // === ALL GATES PASSED - ATTEMPT TO DIG ===
        // Turn toward the dirt if needed (for removeDirt's vision cone requirement)
        if (rc.getDirection() != desiredDir && rc.canTurn(desiredDir)) {
            rc.turn(desiredDir);
        }

        if (rc.canRemoveDirt(blockedLoc)) {
            rc.removeDirt(blockedLoc);
            m.lastClumpDigRound = roundNum;
            return true;
        }

        return false;
    }

    /** Convenience overload for solo mode (no allies requirement) */
    public static boolean maybeDigBlockingDirt(RobotController rc, Mem m, MapLocation targetLoc, int roundNum)
            throws GameActionException {
        return maybeDigBlockingDirt(rc, m, targetLoc, null, roundNum, false);
    }
}
