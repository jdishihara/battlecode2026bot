package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCombat.*;

/**
 * Navigation and movement helpers for attacker rats.
 * Handles cat-aware pathing, bug navigation, and fleeing.
 */
public class AttackerNavigation {

    /**
     * Score a direction for cat-aware movement.
     * Higher score = better direction. Considers both target progress and cat avoidance.
     *
     * @param here Current location
     * @param dir Direction to evaluate
     * @param target Target location
     * @param catLoc Cat location (can be null)
     * @param catBand Current cat distance band
     * @return Score (higher is better), or Integer.MIN_VALUE if direction is invalid
     */
    public static int scoreCatAwareDir(MapLocation here, Direction dir, MapLocation target,
                                 MapLocation catLoc, int catBand) {
        if (dir == null || dir == Direction.CENTER) return Integer.MIN_VALUE;

        MapLocation next = here.add(dir);
        int score = 0;

        // Base score: progress toward target (lower dist = higher score)
        int currentDistSq = here.distanceSquaredTo(target);
        int nextDistSq = next.distanceSquaredTo(target);
        score += (currentDistSq - nextDistSq) * 10;  // +10 per distSq closer

        // Cat avoidance: penalize getting closer to cat
        if (catLoc != null && catBand > CAT_BAND_NONE) {
            int currentCatDistSq = here.distanceSquaredTo(catLoc);
            int nextCatDistSq = next.distanceSquaredTo(catLoc);

            // DANGER band: treat cat as soft obstacle
            if (catBand == CAT_BAND_DANGER) {
                // Hard penalty for entering pounce range
                if (nextCatDistSq <= CAT_PANIC_DIST_SQ) {
                    score -= 200;
                }
                // Heavy penalty for decreasing distance to cat
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 60;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 30;
                }
                // Lateral bonus: prefer moves perpendicular to cat direction
                Direction toCat = here.directionTo(catLoc);
                if (toCat != Direction.CENTER) {
                    Direction leftPerp = toCat.rotateLeft().rotateLeft();
                    Direction rightPerp = toCat.rotateRight().rotateRight();
                    if (dir == leftPerp || dir == rightPerp) {
                        score += 40;  // Lateral move bonus
                    } else if (dir == leftPerp.rotateLeft() || dir == rightPerp.rotateRight()) {
                        score += 20;  // Diagonal-lateral bonus
                    }
                }
            } else {
                // CAUTION band: mild steering
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 15;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 8;
                }
            }
        }

        return score;
    }

    /**
     * Reorder direction array based on cat-aware scoring.
     * Returns a new array with directions sorted by score (best first).
     * Reuses the input array to avoid allocation.
     */
    public static void sortDirsByCatScore(Direction[] dirs, MapLocation here, MapLocation target,
                                    MapLocation catLoc, int catBand) {
        // Simple insertion sort (small array, low bytecode)
        for (int i = 1; i < dirs.length; i++) {
            Direction key = dirs[i];
            int keyScore = scoreCatAwareDir(here, key, target, catLoc, catBand);
            int j = i - 1;
            while (j >= 0 && scoreCatAwareDir(here, dirs[j], target, catLoc, catBand) < keyScore) {
                dirs[j + 1] = dirs[j];
                j--;
            }
            dirs[j + 1] = key;
        }
    }

    /**
     * Move toward a target with cat-aware pathing and "bulldoze" dirt digging.
     *
     * BULLDOZE BEHAVIOR: If the primary desired direction is blocked by dirt,
     * attempt to dig it instead of immediately trying alternate directions.
     * This keeps rats on a direct path rather than going around dirt.
     *
     * @param rc RobotController
     * @param m Mem state
     * @param target Target location
     * @param roundNum Current round number (for dig cooldown)
     * @param nearbyAllies Pre-sensed allies (null for solo mode, non-null for formation mode)
     * @param requireAlliesForDig If true, require allies nearby to dig (formation mode)
     * @return true if action taken (move or dig)
     */
    public static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target, int roundNum,
                                       RobotInfo[] nearbyAllies, boolean requireAlliesForDig)
            throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        // In DANGER band, reject moves that land in pounce range
        boolean rejectPounce = (m.turnCatBand == CAT_BAND_DANGER) && (m.turnCatLoc != null);

        if (!m.bugging) {
            // === BULLDOZE CHECK: Try to dig dirt blocking the primary direction ===
            // Only attempt if desired direction is blocked (can't move that way)
            if (!rc.canMove(desired)) {
                MapLocation blockedLoc = here.add(desired);
                // Quick check: is it dirt we can dig?
                if (rc.onTheMap(blockedLoc) && rc.canSenseLocation(blockedLoc)) {
                    MapInfo blockedInfo = rc.senseMapInfo(blockedLoc);
                    if (blockedInfo.isDirt()) {
                        // Attempt to dig (gates handled inside maybeDigBlockingDirt)
                        if (maybeDigBlockingDirt(rc, m, target, nearbyAllies, roundNum, requireAlliesForDig)) {
                            m.failedMoves = 0;  // Digging counts as progress
                            return true;  // Dig consumed action, done for this turn
                        }
                    }
                }
            }

            // Fill reusable buffer instead of allocating
            m.buf5[0] = desired;
            m.buf5[1] = desired.rotateLeft();
            m.buf5[2] = desired.rotateRight();
            m.buf5[3] = desired.rotateLeft().rotateLeft();
            m.buf5[4] = desired.rotateRight().rotateRight();

            // Apply cat-aware reordering in DANGER or CAUTION bands
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                sortDirsByCatScore(m.buf5, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (int i = 0; i < 5; i++) {
                if (tryMoveDirAvoidRecent(rc, m, m.buf5[i], here, rejectPounce)) {
                    m.failedMoves = 0;
                    return true;
                }
            }

            // Fallback pass (DANGER only): if all safe moves blocked, allow pounce-range
            // (better to risk pounce than be completely stuck)
            if (rejectPounce) {
                for (int i = 0; i < 5; i++) {
                    if (tryMoveDirAvoidRecent(rc, m, m.buf5[i], here, false)) {
                        m.failedMoves = 0;
                        return true;
                    }
                }
            }

            m.failedMoves++;
            if (m.failedMoves >= 2) {
                m.bugging = true;
                m.bugTurns = 0;
            }
        }

        if (m.bugging) {
            // Fill reusable buffer instead of allocating
            if (m.bugLeft) {
                m.buf8[0] = desired.rotateLeft();
                m.buf8[1] = desired.rotateLeft().rotateLeft();
                m.buf8[2] = desired;
                m.buf8[3] = desired.rotateRight();
                m.buf8[4] = desired.rotateRight().rotateRight();
                m.buf8[5] = desired.opposite().rotateRight();
                m.buf8[6] = desired.opposite().rotateLeft();
                m.buf8[7] = desired.opposite();
            } else {
                m.buf8[0] = desired.rotateRight();
                m.buf8[1] = desired.rotateRight().rotateRight();
                m.buf8[2] = desired;
                m.buf8[3] = desired.rotateLeft();
                m.buf8[4] = desired.rotateLeft().rotateLeft();
                m.buf8[5] = desired.opposite().rotateLeft();
                m.buf8[6] = desired.opposite().rotateRight();
                m.buf8[7] = desired.opposite();
            }

            // Apply cat-aware reordering in DANGER or CAUTION bands (even while bugging)
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                sortDirsByCatScore(m.buf8, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (int i = 0; i < 8; i++) {
                if (tryMoveDirAvoidRecent(rc, m, m.buf8[i], here, rejectPounce)) {
                    m.bugTurns++;
                    m.failedMoves = 0;

                    if (rc.canMove(desired) || m.bugTurns > 12) {
                        m.bugging = false;
                        m.bugTurns = 0;
                    }
                    return true;
                }
            }

            // Fallback pass (DANGER only): allow pounce-range if stuck
            if (rejectPounce) {
                for (int i = 0; i < 8; i++) {
                    if (tryMoveDirAvoidRecent(rc, m, m.buf8[i], here, false)) {
                        m.bugTurns++;
                        m.failedMoves = 0;

                        if (rc.canMove(desired) || m.bugTurns > 12) {
                            m.bugging = false;
                            m.bugTurns = 0;
                        }
                        return true;
                    }
                }
            }

            m.bugTurns++;
            if (m.bugTurns > 12) {
                m.bugging = false;
                m.bugTurns = 0;
            }
        }

        return false;
    }

    /** Convenience overload for solo attack mode (no allies requirement for digging) */
    public static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target, int roundNum)
            throws GameActionException {
        return attackerMoveToward(rc, m, target, roundNum, null, false);
    }

    /** Legacy overload - no digging (for backward compatibility where roundNum unavailable) */
    public static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target)
            throws GameActionException {
        // Without roundNum, disable digging by passing -1 (will fail cooldown check)
        return attackerMoveToward(rc, m, target, -1, null, false);
    }

    /**
     * Try to move in a direction, avoiding recent positions (anti-oscillation)
     * and respecting cat danger zones.
     *
     * @param rc RobotController
     * @param m Mem state (has prev1/prev2 for anti-oscillation and cat info)
     * @param dir Direction to try
     * @param oldLoc Current location before move
     * @param rejectPounceRange If true, reject moves that land in pounce range (distSq <= 9)
     * @return true if move succeeded
     */
    public static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc,
                                          boolean rejectPounceRange)
            throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation next = oldLoc.add(dir);

        // Anti-oscillation: reject moves to recent positions
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // Cat pounce-range check (used in PANIC/DANGER bands)
        if (rejectPounceRange && m.turnCatLoc != null) {
            int nextCatDistSq = next.distanceSquaredTo(m.turnCatLoc);
            if (nextCatDistSq <= CAT_PANIC_DIST_SQ) {
                return false;  // Would land in pounce range
            }
        }

        // Note: rc.canMove(dir) already checks for occupied/impassable tiles,
        // so no need for separate senseRobotAtLocation check

        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
            m.prev2 = m.prev1;
            m.prev1 = oldLoc;
            return true;
        }

        return false;
    }

    /** Convenience overload - no pounce range rejection */
    public static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc)
            throws GameActionException {
        return tryMoveDirAvoidRecent(rc, m, dir, oldLoc, false);
    }

    /**
     * Flee from a cat location in PANIC mode.
     * Prioritizes moves that escape pounce range (distSq > 9).
     * Falls back to any move away if no safe escape exists.
     */
    public static void runAttackerFleeFrom(RobotController rc, Mem m, MapLocation catLoc) throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(catLoc).opposite();

        // Fill reusable buffer: away, then laterals, then diagonals
        m.buf5[0] = away;
        m.buf5[1] = away.rotateLeft();
        m.buf5[2] = away.rotateRight();
        m.buf5[3] = away.rotateLeft().rotateLeft();
        m.buf5[4] = away.rotateRight().rotateRight();

        // First pass: find a move that escapes pounce range AND avoids oscillation
        for (int i = 0; i < 5; i++) {
            Direction d = m.buf5[i];
            MapLocation next = here.add(d);
            // Check anti-oscillation
            if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }
            int nextCatDistSq = next.distanceSquaredTo(catLoc);
            if (nextCatDistSq > CAT_PANIC_DIST_SQ) {
                // This move escapes pounce range
                if (rc.getDirection() != d && rc.canTurn(d)) rc.turn(d);
                if (rc.canMove(d)) {
                    rc.move(d);
                    m.prev2 = m.prev1;
                    m.prev1 = here;
                    return;
                }
            }
        }

        // Second pass: any move that increases distance from cat (even if still in pounce range)
        int currentCatDistSq = here.distanceSquaredTo(catLoc);
        for (int i = 0; i < 5; i++) {
            Direction d = m.buf5[i];
            MapLocation next = here.add(d);
            if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }
            int nextCatDistSq = next.distanceSquaredTo(catLoc);
            if (nextCatDistSq > currentCatDistSq) {
                if (rc.getDirection() != d && rc.canTurn(d)) rc.turn(d);
                if (rc.canMove(d)) {
                    rc.move(d);
                    m.prev2 = m.prev1;
                    m.prev1 = here;
                    return;
                }
            }
        }

        // Last resort: any valid move (including oscillation risk)
        if (rc.getDirection() != away && rc.canTurn(away)) rc.turn(away);
        if (rc.canMoveForward()) {
            rc.moveForward();
            m.prev2 = m.prev1;
            m.prev1 = here;
        }
    }
}
