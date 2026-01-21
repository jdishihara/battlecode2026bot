package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderMovement.*;

/**
 * Navigation, stuck detection, and exploration targeting for HaulerDefender rats.
 */
public class DefenderNavigation {

    /**
     * Update the recent locations array for stuck detection.
     */
    public static void updateRecentLocations(Mem m, MapLocation loc) {
        m.recentLocations[m.recentLocationIndex] = loc;
        m.recentLocationIndex = (m.recentLocationIndex + 1) % m.recentLocations.length;
    }

    /**
     * Check if the rat is stuck in a loop (visiting same few locations repeatedly).
     */
    public static boolean isStuckInLoop(Mem m) {
        if (m.recentLocations[0] == null) return false;

        int unique = 0;
        int filled = 0;

        for (int i = 0; i < m.recentLocations.length; i++) {
            MapLocation a = m.recentLocations[i];
            if (a == null) continue;
            filled++;

            boolean uniq = true;
            for (int j = 0; j < i; j++) {
                MapLocation b = m.recentLocations[j];
                if (b != null && b.equals(a)) {
                    uniq = false;
                    break;
                }
            }
            if (uniq) unique++;
        }

        // Stuck if we've filled most of the buffer and have few unique locations
        if (filled >= m.recentLocations.length - 2) {
            return unique <= 3;
        }

        return false;
    }

    /**
     * Check if rat hasn't made progress toward target.
     */
    public static boolean isNotMakingProgress(Mem m, MapLocation target) {
        if (m.recentLocations[0] == null || target == null) return false;

        int oldestIdx = m.recentLocationIndex;
        int newestIdx = (m.recentLocationIndex - 1 + m.recentLocations.length) % m.recentLocations.length;

        MapLocation oldest = m.recentLocations[oldestIdx];
        MapLocation newest = m.recentLocations[newestIdx];

        if (oldest == null || newest == null) return false;

        int oldDist = oldest.distanceSquaredTo(target);
        int newDist = newest.distanceSquaredTo(target);

        return newDist >= oldDist;
    }

    /**
     * Perform an unstuck maneuver - enter wall-following mode and try emergency directions.
     */
    public static void performUnstuckManeuver(RobotController rc, Mem m) throws GameActionException {
        boolean shouldEmergency = m.incrementStuckAndCheck();

        m.wallFollowing = true;
        m.wallFollowTurns = 0;

        if (m.stuckCounter % 2 == 0) {
            m.followLeft = !m.followLeft;
        }

        m.lastStuckLocation = rc.getLocation();

        Direction cur = rc.getDirection();
        MapLocation here = rc.getLocation();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // First, try to dig if we have cheese
        int digThreshold = shouldEmergency ? 20 : 50;
        if (rc.getGlobalCheese() >= digThreshold) {
            Direction[] digDirs = {
                    cur,
                    cur.rotateLeft(),
                    cur.rotateRight(),
                    cur.rotateLeft().rotateLeft(),
                    cur.rotateRight().rotateRight()
            };

            for (Direction dir : digDirs) {
                if (dir == null || dir == Direction.CENTER) continue;

                MapLocation digTarget = here.add(dir);
                for (MapInfo info : nearby) {
                    if (info.getMapLocation().equals(digTarget) && info.isDirt()) {
                        if (rc.getDirection() != dir && rc.canTurn(dir)) {
                            rc.turn(dir);
                            return;
                        }
                        if (rc.canRemoveDirt(digTarget)) {
                            rc.removeDirt(digTarget);
                            m.consecutiveFailedMoves = 0;
                            return;
                        }
                    }
                }
            }
        }

        // Try to move in emergency directions
        Direction[] emergencyDirs = {
                cur.rotateRight().rotateRight(),
                cur.rotateLeft().rotateLeft(),
                cur.opposite(),
                cur.rotateRight(),
                cur.rotateLeft()
        };

        for (Direction dir : emergencyDirs) {
            if (tryMoveInDirection(rc, dir)) {
                m.consecutiveFailedMoves = 0;
                return;
            }
        }

        // If truly stuck, try all 8 directions
        for (Direction dir : DIRECTIONS) {
            if (tryMoveInDirection(rc, dir)) {
                m.consecutiveFailedMoves = 0;
                return;
            }
        }
    }

    /**
     * Pick an exploration target near an assigned mine.
     */
    public static MapLocation pickExplorationNearMine(RobotController rc, Mem m, MapLocation mine) {
        int offsetX = m.rng.nextInt(9) - 4;
        int offsetY = m.rng.nextInt(9) - 4;

        int newX = Math.max(2, Math.min(rc.getMapWidth() - 3, mine.x + offsetX));
        int newY = Math.max(2, Math.min(rc.getMapHeight() - 3, mine.y + offsetY));

        return new MapLocation(newX, newY);
    }

    /**
     * Pick a new exploration target based on current position and robot ID-based strategy.
     */
    public static MapLocation pickNewExplorationTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        int strategy = (rc.getID() + m.stuckCounter) % 6;

        int tx, ty;

        switch (strategy) {
            case 0: {
                boolean top = here.y > h / 2;
                boolean left = here.x < w / 2;
                if (top && left) { tx = w - 5; ty = 5; }
                else if (top) { tx = 5; ty = 5; }
                else if (left) { tx = w - 5; ty = h - 5; }
                else { tx = 5; ty = h - 5; }
                break;
            }
            case 1: {
                tx = (here.x > w / 2) ? 5 : w - 5;
                ty = here.y + (m.rng.nextBoolean() ? 10 : -10);
                ty = Math.max(5, Math.min(h - 5, ty));
                break;
            }
            case 2: {
                tx = here.x + (m.rng.nextBoolean() ? 10 : -10);
                tx = Math.max(5, Math.min(w - 5, tx));
                ty = (here.y > h / 2) ? 5 : h - 5;
                break;
            }
            case 3: {
                tx = w / 2 + m.rng.nextInt(11) - 5;
                ty = h / 2 + m.rng.nextInt(11) - 5;
                break;
            }
            case 4: {
                if (here.x < w / 2 && here.y < h / 2) { tx = w * 3 / 4; ty = h * 3 / 4; }
                else if (here.x >= w / 2 && here.y < h / 2) { tx = w / 4; ty = h * 3 / 4; }
                else if (here.x < w / 2) { tx = w * 3 / 4; ty = h / 4; }
                else { tx = w / 4; ty = h / 4; }
                break;
            }
            default: {
                int quad = m.rng.nextInt(4);
                switch (quad) {
                    case 0: tx = w / 4; ty = h / 4; break;
                    case 1: tx = 3 * w / 4; ty = h / 4; break;
                    case 2: tx = w / 4; ty = 3 * h / 4; break;
                    default: tx = 3 * w / 4; ty = 3 * h / 4; break;
                }
                break;
            }
        }

        return new MapLocation(tx, ty);
    }

    /**
     * Pick exploration target that avoids known mines (to find new ones).
     */
    public static MapLocation pickExplorationAwayFromKnownMines(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        MapLocation[] candidates = new MapLocation[8];
        candidates[0] = new MapLocation(w / 4, h / 4);
        candidates[1] = new MapLocation(3 * w / 4, h / 4);
        candidates[2] = new MapLocation(w / 4, 3 * h / 4);
        candidates[3] = new MapLocation(3 * w / 4, 3 * h / 4);
        candidates[4] = new MapLocation(w / 2, h / 4);
        candidates[5] = new MapLocation(w / 2, 3 * h / 4);
        candidates[6] = new MapLocation(w / 4, h / 2);
        candidates[7] = new MapLocation(3 * w / 4, h / 2);

        MapLocation bestCandidate = candidates[0];
        int bestMinDist = 0;

        for (MapLocation candidate : candidates) {
            int minDistToKnownMine = Integer.MAX_VALUE;

            for (int i = 0; i < m.knownMineCount; i++) {
                MapLocation mine = m.knownMines[i];
                if (mine != null) {
                    int d = candidate.distanceSquaredTo(mine);
                    if (d < minDistToKnownMine) {
                        minDistToKnownMine = d;
                    }
                }
            }

            int distFromHere = here.distanceSquaredTo(candidate);

            if (minDistToKnownMine > bestMinDist && distFromHere < 2500) {
                bestMinDist = minDistToKnownMine;
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    /**
     * Check if we should pick a new exploration target.
     */
    public static boolean shouldPickNewExplorationTarget(RobotController rc, Mem m) {
        if (m.explorationTarget == null) return true;

        if (rc.getLocation().distanceSquaredTo(m.explorationTarget) <= 4) {
            return true;
        }

        m.turnsAtCurrentExploreTarget++;
        if (m.turnsAtCurrentExploreTarget > m.maxTurnsAtExploreTarget) {
            return true;
        }

        if (m.turnsAtCurrentExploreTarget > 15 && isNotMakingProgress(m, m.explorationTarget)) {
            return true;
        }

        return false;
    }
}
