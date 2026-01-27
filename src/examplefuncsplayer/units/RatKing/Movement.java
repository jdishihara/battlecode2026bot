package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * Movement and escape methods for the Rat King.
 * v5 Updates: Improved escape logic that considers both cats and enemy rats.
 */
public class Movement {

    /**
     * Try to escape from a single threat (cat or enemy rat).
     */
    public static boolean tryKingEscape(RobotController rc, MapLocation threatLocation) throws GameActionException {
        return tryKingEscapeMultipleThreat(rc, threatLocation, null);
    }

    /**
     * Escape from a threat - alias for tryKingEscape.
     */
    public static Direction tryKingEscapeFromThreat(RobotController rc, MapLocation threatLocation) throws GameActionException {
        boolean moved = tryKingEscapeMultipleThreat(rc, threatLocation, null);
        return moved ? rc.getLocation().directionTo(threatLocation).opposite() : null;
    }

    /**
     * Try to escape from multiple threats (cat and/or enemy rats).
     * Considers both threats when choosing escape direction.
     */
    public static boolean tryKingEscapeMultipleThreat(RobotController rc, MapLocation catLocation,
                                                       MapLocation enemyRatLocation) throws GameActionException {
        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // Calculate combined threat direction
        Direction awayDir;
        if (catLocation != null && enemyRatLocation != null) {
            // Both threats present - calculate average threat position
            int avgX = (catLocation.x + enemyRatLocation.x) / 2;
            int avgY = (catLocation.y + enemyRatLocation.y) / 2;
            MapLocation avgThreat = new MapLocation(avgX, avgY);
            awayDir = avgThreat.directionTo(kingLoc);
        } else if (catLocation != null) {
            awayDir = catLocation.directionTo(kingLoc);
        } else if (enemyRatLocation != null) {
            awayDir = enemyRatLocation.directionTo(kingLoc);
        } else {
            return false; // No threat
        }

        if (awayDir == Direction.CENTER) awayDir = Direction.NORTH;

        // Consider more directions for better escape options
        Direction[] candidates = {
                awayDir,
                awayDir.rotateLeft(),
                awayDir.rotateRight(),
                awayDir.rotateLeft().rotateLeft(),
                awayDir.rotateRight().rotateRight(),
                awayDir.rotateLeft().rotateLeft().rotateLeft(),
                awayDir.rotateRight().rotateRight().rotateRight()
        };

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : candidates) {
            if (!rc.canMove(dir)) continue;

            MapLocation newLoc = kingLoc.add(dir);
            int score = 0;

            // Score based on distance from cat (most dangerous)
            if (catLocation != null) {
                int currentDistSq = kingLoc.distanceSquaredTo(catLocation);
                int newDistSq = newLoc.distanceSquaredTo(catLocation);
                score += 5 * (newDistSq - currentDistSq); // Weight cat heavily
            }

            // Score based on distance from enemy rats
            if (enemyRatLocation != null) {
                int currentDistSq = kingLoc.distanceSquaredTo(enemyRatLocation);
                int newDistSq = newLoc.distanceSquaredTo(enemyRatLocation);
                score += 2 * (newDistSq - currentDistSq);
            }

            // Heavy penalty for corners and edges (can get trapped)
            if (newLoc.x <= 2 || newLoc.x >= mapWidth - 3 ||
                    newLoc.y <= 2 || newLoc.y >= mapHeight - 3) {
                score -= 100;
            }

            // Penalty for limited escape routes
            int passableNeighbors = countPassableNeighbors(newLoc, nearby);
            if (passableNeighbors <= 1) {
                score -= 150; // Severe penalty for dead ends
            } else if (passableNeighbors == 2) {
                score -= 50;
            } else if (passableNeighbors >= 5) {
                score += 20; // Bonus for open areas
            }

            // Bonus for moving toward map center (more escape options)
            MapLocation center = new MapLocation(mapWidth / 2, mapHeight / 2);
            int currentCenterDist = kingLoc.distanceSquaredTo(center);
            int newCenterDist = newLoc.distanceSquaredTo(center);
            if (newCenterDist < currentCenterDist) {
                score += 15;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
            return true;
        }
        return false;
    }

    /**
     * Proactive kiting: Move away from threats before they get dangerously close.
     * Called when in ALERT state to maintain safe distance.
     */
    public static boolean tryProactiveKite(RobotController rc, MapLocation threatLocation, int safeDistSq) throws GameActionException {
        if (threatLocation == null) return false;

        MapLocation kingLoc = rc.getLocation();
        int currentDistSq = kingLoc.distanceSquaredTo(threatLocation);

        // Only kite if threat is closer than safe distance
        if (currentDistSq >= safeDistSq) return false;

        // Try to move away
        return tryKingEscape(rc, threatLocation);
    }

    /**
     * Try to dig an escape route when cornered.
     */
    public static boolean tryDigEscapeRoute(RobotController rc, MapLocation threatLocation) throws GameActionException {
        if (!rc.isActionReady()) return false;
        if (rc.getGlobalCheese() < 20) return false; // Digging costs cheese

        MapLocation kingLoc = rc.getLocation();
        Direction awayDir = threatLocation.directionTo(kingLoc);
        if (awayDir == Direction.CENTER) return false;

        // Try to dig dirt in escape directions
        Direction[] digDirs = {
                awayDir,
                awayDir.rotateLeft(),
                awayDir.rotateRight()
        };

        for (Direction dir : digDirs) {
            MapLocation digLoc = kingLoc.add(dir);
            if (rc.canRemoveDirt(digLoc)) {
                rc.removeDirt(digLoc);
                return true;
            }
        }

        return false;
    }

    public static int countPassableNeighbors(MapLocation loc, MapInfo[] nearby) {
        int count = 0;
        for (Direction d : DIRECTIONS) {
            MapLocation next = loc.add(d);
            if (isLocationPassable(next, nearby)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) {
                return info.isPassable();
            }
        }
        return true;
    }

    public static void trySpawnRat(RobotController rc) throws GameActionException {
        MapLocation myLocation = rc.getLocation();
        int roundNum = rc.getRoundNum();
        int globalCheese = rc.getGlobalCheese();

        // Check if we should trigger a new attacker wave
        int waveRemaining = rc.readSharedArray(ATTACKER_WAVE_REMAINING);
        int lastWaveRound = rc.readSharedArray(LAST_ATTACKER_WAVE_ROUND);
        int totalHaulers = rc.readSharedArray(TOTAL_HAULERS_SPAWNED);

        // Calculate rounds since last wave (handle mod 1000 wrap-around)
        int roundsSinceLastWave = (lastWaveRound == 0) ? ATTACKER_WAVE_COOLDOWN + 1 :
            ((roundNum - lastWaveRound + 1000) % 1000);
        if (roundsSinceLastWave > 500) roundsSinceLastWave = ATTACKER_WAVE_COOLDOWN + 1;

        // Trigger new wave if conditions are met and no wave is active
        if (waveRemaining == 0 &&
            roundNum >= ATTACKER_WAVE_MIN_ROUND &&
            globalCheese >= ATTACKER_WAVE_MIN_CHEESE &&
            totalHaulers >= ATTACKER_WAVE_MIN_HAULERS &&
            roundsSinceLastWave >= ATTACKER_WAVE_COOLDOWN) {

            waveRemaining = ATTACKER_WAVE_SIZE;
            rc.writeSharedArray(ATTACKER_WAVE_REMAINING, waveRemaining);
            rc.writeSharedArray(LAST_ATTACKER_WAVE_ROUND, roundNum % 1000);
        }

        // Determine role for next spawn
        boolean spawnAttacker = (waveRemaining > 0);

        // Set role in shared array BEFORE spawning
        rc.writeSharedArray(NEXT_SPAWN_ROLE, spawnAttacker ? 1 : 0);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation spawnLoc = new MapLocation(myLocation.x + dx, myLocation.y + dy);
                if (rc.canBuildRat(spawnLoc)) {
                    // Increment spawn count BEFORE spawning so baby rat can read it
                    int currentCount = rc.readSharedArray(SPAWN_COUNT_INDEX);
                    rc.writeSharedArray(SPAWN_COUNT_INDEX, currentCount + 1);

                    // Update wave/hauler counters BEFORE spawning
                    if (spawnAttacker) {
                        rc.writeSharedArray(ATTACKER_WAVE_REMAINING, waveRemaining - 1);
                    } else {
                        rc.writeSharedArray(TOTAL_HAULERS_SPAWNED, totalHaulers + 1);
                    }

                    rc.buildRat(spawnLoc);
                    return;
                }
            }
        }
    }

    /**
     * Roaming movement: drift toward cheese-rich areas while staying safe.
     * Called when king is in ROAMING state.
     *
     * @param target The target location (cheese-rich area or mine location)
     * @return true if movement occurred
     */
    public static boolean tryRoamingMove(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return false;
        if (target == null) return false;

        MapLocation kingLoc = rc.getLocation();
        int mapWidth = rc.getMapWidth();
        int mapHeight = rc.getMapHeight();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // If already at or very close to target, don't move
        if (kingLoc.distanceSquaredTo(target) <= 4) return false;

        Direction toTarget = kingLoc.directionTo(target);
        if (toTarget == Direction.CENTER) return false;

        // Consider directions toward target and nearby alternatives
        Direction[] candidates = {
            toTarget,
            toTarget.rotateLeft(),
            toTarget.rotateRight(),
            toTarget.rotateLeft().rotateLeft(),
            toTarget.rotateRight().rotateRight()
        };

        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        for (Direction dir : candidates) {
            if (!rc.canMove(dir)) continue;

            MapLocation newLoc = kingLoc.add(dir);
            int score = 0;

            // Score: Progress toward target
            int currentDistSq = kingLoc.distanceSquaredTo(target);
            int newDistSq = newLoc.distanceSquaredTo(target);
            score += 3 * (currentDistSq - newDistSq);

            // Penalty for edges/corners (can get trapped)
            if (newLoc.x <= 3 || newLoc.x >= mapWidth - 4 ||
                newLoc.y <= 3 || newLoc.y >= mapHeight - 4) {
                score -= 80;
            }

            // Bonus for open areas (more escape routes)
            int passableNeighbors = countPassableNeighbors(newLoc, nearby);
            if (passableNeighbors >= 5) {
                score += 30;
            } else if (passableNeighbors <= 2) {
                score -= 60;
            }

            // Prefer staying somewhat central
            MapLocation center = new MapLocation(mapWidth / 2, mapHeight / 2);
            int newCenterDist = newLoc.distanceSquaredTo(center);
            int mapDiagonalSq = (mapWidth * mapWidth + mapHeight * mapHeight) / 4;
            if (newCenterDist < mapDiagonalSq / 4) {
                score += 15; // Bonus for being relatively central
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
            return true;
        }
        return false;
    }

    /**
     * Check if king is boxed in with no good movement options.
     */
    public static boolean isKingBoxedIn(RobotController rc) throws GameActionException {
        int movableDirections = 0;
        for (Direction dir : DIRECTIONS) {
            if (rc.canMove(dir)) {
                movableDirections++;
            }
        }
        return movableDirections <= 1;
    }
}
