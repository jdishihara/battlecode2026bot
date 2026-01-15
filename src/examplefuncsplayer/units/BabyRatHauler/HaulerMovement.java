package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatLevel;

import static examplefuncsplayer.config.Constants.*;

/**
 * Movement and digging functions for hauler rats.
 */
public class HaulerMovement {

    /**
     * Intelligent movement toward a target with threat awareness.
     */
    public static boolean intelligentMoveToward(RobotController rc, Mem m, MapLocation target,
                                                MapInfo[] nearby, ThreatInfo threat) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Safety override: don't move toward king when threatened
        if (target.equals(m.kingLocation) && threat.level != ThreatLevel.NONE && threat.source != null) {
            Direction awayThreat = threat.source.directionTo(here);
            Direction perp1 = awayThreat.rotateLeft();
            Direction perp2 = awayThreat.rotateRight();

            if (tryMoveInDirection(rc, perp1)) return true;
            if (tryMoveInDirection(rc, perp2)) return true;
            return false;
        }

        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        // Wall-following mode for getting unstuck
        if (m.wallFollowing) {
            boolean moved = performWallFollow(rc, m, desired, nearby);
            m.wallFollowTurns++;
            if (m.wallFollowTurns > 15 || (moved && canMoveToward(rc, desired))) {
                m.wallFollowing = false;
                m.wallFollowTurns = 0;
            }
            return moved;
        }

        // Try to dig blocking dirt (more aggressive now)
        if (maybeDigBlockingDirt(rc, m, desired, nearby)) return true;

        Direction best = findBestPassableDirection(rc, here, desired, target);
        if (best != null) {
            if (rc.getDirection() != best && rc.canTurn(best)) {
                rc.turn(best);
            }
            if (rc.canMoveForward()) {
                rc.moveForward();
                m.consecutiveFailedMoves = 0;
                return true;
            }
            m.consecutiveFailedMoves++;
            return false;
        } else {
            // No passable direction found - try digging in any direction
            if (emergencyDig(rc, m, nearby)) return true;
            m.consecutiveFailedMoves++;
            return moveToward(rc, target);
        }
    }

    /**
     * Maybe dig blocking dirt if conditions are met.
     * Now more aggressive - will dig if it's the best path forward.
     */
    public static boolean maybeDigBlockingDirt(RobotController rc, Mem m, Direction desired, MapInfo[] nearby) throws GameActionException {
        MapLocation here = rc.getLocation();
        MapLocation forward = here.add(desired);

        boolean forwardIsDirt = false;
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(forward)) {
                forwardIsDirt = info.isDirt();
                break;
            }
        }
        if (!forwardIsDirt) return false;

        // Count passable sidesteps (less restrictive than before)
        int passableSidesteps = 0;
        Direction[] sidesteps = {
                desired.rotateLeft(),
                desired.rotateRight()
        };
        for (Direction s : sidesteps) {
            MapLocation side = here.add(s);
            for (MapInfo info : nearby) {
                if (info.getMapLocation().equals(side)) {
                    if (info.isPassable()) passableSidesteps++;
                    break;
                }
            }
        }

        // Dig if no good sidesteps OR if we've been stuck (wallFollowing) OR many failed moves
        boolean shouldDig = passableSidesteps == 0 || m.wallFollowing || m.stuckCounter > 3 || m.consecutiveFailedMoves > 3;

        // Lower threshold for digging when stuck
        int digThreshold = (m.wallFollowing || m.consecutiveFailedMoves > 3) ? 30 : DIG_MIN_CHEESE_HAULER;
        if (!shouldDig || rc.getGlobalCheese() < digThreshold) return false;

        if (rc.getDirection() != desired && rc.canTurn(desired)) rc.turn(desired);
        if (rc.canRemoveDirt(forward)) {
            rc.removeDirt(forward);
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return true;
        }
        return false;
    }

    /**
     * Emergency dig - try to dig any adjacent dirt when completely stuck.
     */
    public static boolean emergencyDig(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        if (rc.getGlobalCheese() < 20) return false;

        MapLocation here = rc.getLocation();

        // Find any adjacent dirt we can dig
        for (Direction dir : Direction.allDirections()) {
            if (dir == Direction.CENTER) continue;

            MapLocation target = here.add(dir);

            for (MapInfo info : nearby) {
                if (info.getMapLocation().equals(target) && info.isDirt()) {
                    // Turn to face the dirt
                    if (rc.getDirection() != dir && rc.canTurn(dir)) {
                        rc.turn(dir);
                    }
                    // Try to dig it
                    if (rc.canRemoveDirt(target)) {
                        rc.removeDirt(target);
                        m.consecutiveFailedMoves = 0;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Find best passable direction by scoring based on distance to target.
     */
    public static Direction findBestPassableDirection(RobotController rc, MapLocation here, Direction desired, MapLocation target) throws GameActionException {
        Direction[] allDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight(),
                desired.rotateLeft().rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight().rotateRight(),
                desired.opposite()
        };

        Direction bestDir = null;
        int bestScore = Integer.MAX_VALUE;

        for (Direction d : allDirs) {
            MapLocation next = here.add(d);
            if (!isPassable(rc, next)) continue;

            // Also check for robot blocking
            if (rc.canSenseLocation(next)) {
                RobotInfo blocker = rc.senseRobotAtLocation(next);
                if (blocker != null) continue;
            }

            int distToTarget = next.distanceSquaredTo(target);

            if (distToTarget < bestScore) {
                bestScore = distToTarget;
                bestDir = d;
            }
        }

        return bestDir;
    }

    /**
     * Check if a location in the nearby array is passable.
     */
    public static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) return info.isPassable();
        }
        return true;
    }

    /**
     * Efficient passability check using direct sensing.
     */
    public static boolean isPassable(RobotController rc, MapLocation loc) throws GameActionException {
        if (!rc.canSenseLocation(loc)) return false; // Changed: unseen is NOT passable (safer)
        return rc.senseMapInfo(loc).isPassable();
    }

    /**
     * Simple movement toward a target with rotation fallbacks.
     */
    public static boolean moveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        Direction[] tryDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight()
        };
        for (Direction d : tryDirs) {
            if (tryMoveInDirection(rc, d)) return true;
        }
        return false;
    }

    /**
     * Try to move in a specific direction, handling turning and movement.
     */
    public static boolean tryMoveInDirection(RobotController rc, Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation target = rc.getLocation().add(dir);
        if (rc.canSenseLocation(target)) {
            RobotInfo blocker = rc.senseRobotAtLocation(target);
            if (blocker != null) {
                return false;
            }
            // Also check if passable
            if (!rc.senseMapInfo(target).isPassable()) {
                return false;
            }
        }

        if (rc.getDirection() != dir && rc.canTurn(dir)) {
            rc.turn(dir);
        }

        if (rc.canMoveForward()) {
            rc.moveForward();
            return true;
        }
        return false;
    }

    /**
     * Move away from king (for traffic control after delivery).
     */
    public static void moveAwayFromKing(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return;
        MapLocation here = rc.getLocation();
        Direction away = m.kingLocation.directionTo(here);
        if (away == Direction.CENTER) away = DIRECTIONS[m.rng.nextInt(8)];
        tryMoveInDirection(rc, away);
    }

    /**
     * Simple exploration in a direction.
     */
    public static void exploreInDirection(RobotController rc, Direction dir) throws GameActionException {
        if (dir == null) dir = Direction.NORTH;
        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);
        else if (rc.canMoveForward()) rc.moveForward();
        else {
            Direction left = dir.rotateLeft();
            Direction right = dir.rotateRight();
            if (rc.canTurn(left)) rc.turn(left);
            else if (rc.canTurn(right)) rc.turn(right);
        }
    }

    /**
     * Wall-following behavior to escape tight spaces.
     * Now includes digging capability.
     */
    public static boolean performWallFollow(RobotController rc, Mem m, Direction desired, MapInfo[] nearby) throws GameActionException {
        Direction cur = rc.getDirection();

        Direction[] followDirs;
        if (m.followLeft) {
            followDirs = new Direction[] {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateLeft().rotateLeft(),
                    cur.rotateLeft(),
                    cur,
                    cur.rotateRight(),
                    desired.rotateRight()
            };
        } else {
            followDirs = new Direction[] {
                    desired,
                    desired.rotateRight(),
                    desired.rotateRight().rotateRight(),
                    cur.rotateRight(),
                    cur,
                    cur.rotateLeft(),
                    desired.rotateLeft()
            };
        }

        // Try movement first
        for (Direction dir : followDirs) {
            if (dir == null || dir == Direction.CENTER) continue;

            MapLocation next = rc.getLocation().add(dir);
            if (isPassable(rc, next)) {
                // Check for blocking robot
                if (rc.canSenseLocation(next)) {
                    RobotInfo blocker = rc.senseRobotAtLocation(next);
                    if (blocker != null) continue;
                }

                if (rc.getDirection() != dir && rc.canTurn(dir)) {
                    rc.turn(dir);
                }
                if (rc.canMoveForward()) {
                    rc.moveForward();
                    m.consecutiveFailedMoves = 0;
                    return true;
                }
            }
        }

        // If no movement possible, try digging
        if (rc.getGlobalCheese() >= 30) {
            for (Direction dir : followDirs) {
                if (dir == null || dir == Direction.CENTER) continue;

                MapLocation next = rc.getLocation().add(dir);
                for (MapInfo info : nearby) {
                    if (info.getMapLocation().equals(next) && info.isDirt()) {
                        if (rc.getDirection() != dir && rc.canTurn(dir)) {
                            rc.turn(dir);
                        }
                        if (rc.canRemoveDirt(next)) {
                            rc.removeDirt(next);
                            // Switch wall-follow direction after digging
                            m.followLeft = !m.followLeft;
                            m.consecutiveFailedMoves = 0;
                            return true;
                        }
                    }
                }
            }
        }

        // Last resort: switch follow direction
        m.followLeft = !m.followLeft;
        return false;
    }

    /**
     * Original wall follow (backward compat).
     */
    public static boolean performWallFollow(RobotController rc, Mem m, Direction desired) throws GameActionException {
        return performWallFollow(rc, m, desired, rc.senseNearbyMapInfos());
    }

    /**
     * Check if we can move toward a direction.
     */
    public static boolean canMoveToward(RobotController rc, Direction desired) throws GameActionException {
        MapLocation here = rc.getLocation();
        MapLocation target = here.add(desired);
        return isPassable(rc, target);
    }
}