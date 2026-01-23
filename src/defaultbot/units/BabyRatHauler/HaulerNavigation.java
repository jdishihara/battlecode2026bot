package defaultbot.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerMovement.*;

/**
 * Navigation, stuck detection, and exploration targeting for hauler rats.
 */
public class HaulerNavigation {

    /**
     * Update the recent locations array for stuck detection.
     */
    public static void updateRecentLocations(Mem m, MapLocation loc) {
        m.recentLocations[m.recentLocationIndex] = loc;
        m.recentLocationIndex = (m.recentLocationIndex + 1) % m.recentLocations.length;
    }

    /**
     * Check if the rat is stuck in a loop (visiting same few locations repeatedly).
     * Triggers earlier than before for faster unstuck response.
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
        // Changed threshold: stuck if <= 3 unique locations (was 2)
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

        // Get oldest and newest recorded locations
        int oldestIdx = m.recentLocationIndex;
        int newestIdx = (m.recentLocationIndex - 1 + m.recentLocations.length) % m.recentLocations.length;

        MapLocation oldest = m.recentLocations[oldestIdx];
        MapLocation newest = m.recentLocations[newestIdx];

        if (oldest == null || newest == null) return false;

        int oldDist = oldest.distanceSquaredTo(target);
        int newDist = newest.distanceSquaredTo(target);

        // Not making progress if we're not getting closer
        return newDist >= oldDist;
    }

    /**
     * Perform an unstuck maneuver - enter wall-following mode and try emergency directions.
     * Now includes digging capability.
     */
    public static void performUnstuckManeuver(RobotController rc, Mem m) throws GameActionException {
        // Use the helper to increment and check
        boolean shouldEmergency = m.incrementStuckAndCheck();

        // Enter wall-following mode to escape
        m.wallFollowing = true;
        m.wallFollowTurns = 0;

        // Toggle follow direction each time we get stuck
        if (m.stuckCounter % 2 == 0) {
            m.followLeft = !m.followLeft;
        }

        // Record this stuck location
        m.lastStuckLocation = rc.getLocation();

        Direction cur = rc.getDirection();
        MapLocation here = rc.getLocation();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // First, try to dig if we have cheese (more aggressive when stuck longer)
        int digThreshold = shouldEmergency ? 20 : 50;
        if (rc.getGlobalCheese() >= digThreshold) {
            // Try digging in directions that might help
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
                            return; // Turn this round, dig next
                        }
                        if (rc.canRemoveDirt(digTarget)) {
                            rc.removeDirt(digTarget);
                            m.consecutiveFailedMoves = 0; // Reset on successful action
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
     * Used when we're at the mine but don't see cheese - explore the area.
     */
    public static MapLocation pickExplorationNearMine(RobotController rc, Mem m, MapLocation mine) {
        int offsetX = m.rng.nextInt(9) - 4;  // -4 to +4
        int offsetY = m.rng.nextInt(9) - 4;

        int newX = Math.max(2, Math.min(rc.getMapWidth() - 3, mine.x + offsetX));
        int newY = Math.max(2, Math.min(rc.getMapHeight() - 3, mine.y + offsetY));

        return new MapLocation(newX, newY);
    }

    /**
     * Pick a new exploration target based on current position and robot ID-based strategy.
     * Now also considers known mine locations to find new mines.
     */
    public static MapLocation pickNewExplorationTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        // Use robot ID + stuck counter for variation in strategy
        int strategy = (rc.getID() + m.stuckCounter) % 6;

        int tx, ty;

        switch (strategy) {
            case 0: {
                // Explore opposite corner from current position
                boolean top = here.y > h / 2;
                boolean left = here.x < w / 2;
                if (top && left) { tx = w - 5; ty = 5; }
                else if (top) { tx = 5; ty = 5; }
                else if (left) { tx = w - 5; ty = h - 5; }
                else { tx = 5; ty = h - 5; }
                break;
            }
            case 1: {
                // Horizontal sweep
                tx = (here.x > w / 2) ? 5 : w - 5;
                ty = here.y + (m.rng.nextBoolean() ? 10 : -10);
                ty = Math.max(5, Math.min(h - 5, ty));
                break;
            }
            case 2: {
                // Vertical sweep
                tx = here.x + (m.rng.nextBoolean() ? 10 : -10);
                tx = Math.max(5, Math.min(w - 5, tx));
                ty = (here.y > h / 2) ? 5 : h - 5;
                break;
            }
            case 3: {
                // Center exploration (mines often near center)
                tx = w / 2 + m.rng.nextInt(11) - 5;
                ty = h / 2 + m.rng.nextInt(11) - 5;
                break;
            }
            case 4: {
                // Diagonal exploration
                if (here.x < w / 2 && here.y < h / 2) { tx = w * 3 / 4; ty = h * 3 / 4; }
                else if (here.x >= w / 2 && here.y < h / 2) { tx = w / 4; ty = h * 3 / 4; }
                else if (here.x < w / 2) { tx = w * 3 / 4; ty = h / 4; }
                else { tx = w / 4; ty = h / 4; }
                break;
            }
            default: {
                // Random quadrant
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
     * Useful for scout rats.
     */
    public static MapLocation pickExplorationAwayFromKnownMines(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        // Generate several candidate targets
        MapLocation[] candidates = new MapLocation[8];
        candidates[0] = new MapLocation(w / 4, h / 4);
        candidates[1] = new MapLocation(3 * w / 4, h / 4);
        candidates[2] = new MapLocation(w / 4, 3 * h / 4);
        candidates[3] = new MapLocation(3 * w / 4, 3 * h / 4);
        candidates[4] = new MapLocation(w / 2, h / 4);
        candidates[5] = new MapLocation(w / 2, 3 * h / 4);
        candidates[6] = new MapLocation(w / 4, h / 2);
        candidates[7] = new MapLocation(3 * w / 4, h / 2);

        // Find candidate farthest from all known mines
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

            // Also consider distance from current location (don't pick something too far)
            int distFromHere = here.distanceSquaredTo(candidate);

            // Score: maximize distance from known mines, but not too far from us
            if (minDistToKnownMine > bestMinDist && distFromHere < 2500) {
                bestMinDist = minDistToKnownMine;
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    /**
     * Check if we should pick a new exploration target (timeout or reached current one).
     */
    public static boolean shouldPickNewExplorationTarget(RobotController rc, Mem m) {
        if (m.explorationTarget == null) return true;

        // Reached target
        if (rc.getLocation().distanceSquaredTo(m.explorationTarget) <= 4) {
            return true;
        }

        // Timeout
        m.turnsAtCurrentExploreTarget++;
        if (m.turnsAtCurrentExploreTarget > m.maxTurnsAtExploreTarget) {
            return true;
        }

        // Not making progress for too long
        if (m.turnsAtCurrentExploreTarget > 15 && isNotMakingProgress(m, m.explorationTarget)) {
            return true;
        }

        return false;
    }
}