package defaultbot.units.BabyRatAttacker;

import battlecode.common.*;

import defaultbot.units.BabyRatAttacker.AttackerMemory.Mem;

import static defaultbot.units.BabyRatAttacker.AttackerState.*;

/**
 * Movement and flee functions for attacker rats.
 */
public class AttackerMovement {

    /**
     * Flee from a cat location in PANIC mode.
     * Prioritizes moves that escape pounce range (distSq > 9).
     * Falls back to any move away if no safe escape exists.
     */
    public static void runAttackerFleeFrom(RobotController rc, Mem m, MapLocation catLoc) throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(catLoc).opposite();

        // Build flee direction priority: away, then laterals, then diagonals
        Direction[] fleeDirs = {
            away,
            away.rotateLeft(),
            away.rotateRight(),
            away.rotateLeft().rotateLeft(),
            away.rotateRight().rotateRight()
        };

        // First pass: find a move that escapes pounce range AND avoids oscillation
        for (Direction d : fleeDirs) {
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
        for (Direction d : fleeDirs) {
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

    /**
     * Main movement function toward a target with bug navigation and cat awareness.
     */
    public static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        // In DANGER band, reject moves that land in pounce range
        boolean rejectPounce = (m.turnCatBand == CAT_BAND_DANGER) && (m.turnCatLoc != null);

        if (!m.bugging) {
            Direction[] tryDirs = {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateRight(),
                    desired.rotateLeft().rotateLeft(),
                    desired.rotateRight().rotateRight()
            };

            // Apply cat-aware reordering in DANGER or CAUTION bands
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                AttackerNavigation.sortDirsByCatScore(tryDirs, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (Direction d : tryDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here, rejectPounce)) {
                    m.failedMoves = 0;
                    return true;
                }
            }

            // Fallback pass (DANGER only): if all safe moves blocked, allow pounce-range
            // (better to risk pounce than be completely stuck)
            if (rejectPounce) {
                for (Direction d : tryDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here, false)) {
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
            Direction[] bugDirs;
            if (m.bugLeft) {
                bugDirs = new Direction[] {
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired,
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired.opposite().rotateRight(),
                        desired.opposite().rotateLeft(),
                        desired.opposite()
                };
            } else {
                bugDirs = new Direction[] {
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired,
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired.opposite().rotateLeft(),
                        desired.opposite().rotateRight(),
                        desired.opposite()
                };
            }

            // Apply cat-aware reordering in DANGER or CAUTION bands (even while bugging)
            if (m.turnCatBand >= CAT_BAND_CAUTION && m.turnCatLoc != null) {
                AttackerNavigation.sortDirsByCatScore(bugDirs, here, target, m.turnCatLoc, m.turnCatBand);
            }

            // First pass: try with pounce rejection if in DANGER
            for (Direction d : bugDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here, rejectPounce)) {
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
                for (Direction d : bugDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here, false)) {
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

        // Avoid occupied tiles
        if (rc.canSenseLocation(next)) {
            RobotInfo at = rc.senseRobotAtLocation(next);
            if (at != null) return false;
        }

        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
            m.prev2 = m.prev1;
            m.prev1 = oldLoc;
            return true;
        }

        // If blocked by dirt, dig it
        if (rc.canSenseLocation(next)) {
            MapInfo info = rc.senseMapInfo(next);
            if (info.isDirt() && rc.canRemoveDirt(next)) {
                rc.removeDirt(next);
                return false;  // Didn't move, but dug the dirt - will move next turn
            }
        }

        return false;
    }

    /** Convenience overload - no pounce range rejection */
    public static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc)
            throws GameActionException {
        return tryMoveDirAvoidRecent(rc, m, dir, oldLoc, false);
    }

    /**
     * Count allies in radius for combat decisions.
     */
    public static int countAlliesInRadius(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radiusSq, rc.getTeam());
        int count = 0;
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }
}
