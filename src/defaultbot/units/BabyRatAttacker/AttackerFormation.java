package defaultbot.units.BabyRatAttacker;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.Mem;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMovement.*;

import java.util.Arrays;

/**
 * Formation movement and leader election for attacker rats.
 */
public class AttackerFormation {

    /**
     * Check if this rat is the local leader for the given objective.
     * Leader is chosen by: smallest distance to objective, tie-break by smallest ID.
     *
     * STUCK HANDLING: If this rat is on cooldown (was stuck as leader), it excludes
     * itself from leadership consideration to let others lead.
     *
     * @param rc RobotController
     * @param m Mem state (for stuck cooldown)
     * @param objectiveLoc The objective location
     * @param nearbyAllies Pre-sensed nearby allies (to save bytecode)
     * @return true if this rat is the leader
     */
    public static boolean amILeader(RobotController rc, Mem m, MapLocation objectiveLoc, RobotInfo[] nearbyAllies)
            throws GameActionException {
        MapLocation here = rc.getLocation();
        int myId = rc.getID();
        int myDistSq = here.distanceSquaredTo(objectiveLoc);

        // If on cooldown from being stuck, exclude self from leadership
        boolean selfExcluded = m.stuckCooldown > 0;

        int bestDistSq = selfExcluded ? Integer.MAX_VALUE : myDistSq;
        int bestId = selfExcluded ? Integer.MAX_VALUE : myId;
        boolean selfIsBest = !selfExcluded;

        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;

            int allyDistSq = ally.getLocation().distanceSquaredTo(objectiveLoc);
            int allyId = ally.getID();

            // Compare: smaller dist wins, tie-break by smaller ID
            boolean isBetter = (allyDistSq < bestDistSq) ||
                               (allyDistSq == bestDistSq && allyId < bestId);

            if (isBetter) {
                bestDistSq = allyDistSq;
                bestId = allyId;
                selfIsBest = false;
            }
        }

        return selfIsBest;
    }

    /**
     * Formation-based movement for SEEK state.
     * Leaders move toward objective and broadcast formation messages.
     * Followers stay adjacent to formation source.
     *
     * STUCK HANDLING: If leader fails to move for LEADER_STUCK_THRESHOLD turns,
     * they enter cooldown and let another rat lead.
     *
     * @param rc RobotController
     * @param m Mem state
     * @param objectiveLoc The objective location to move toward
     * @param roundNum Current round number
     * @param nearbyAllies Pre-sensed nearby allies (saves bytecode)
     * @return true if movement was successful
     */
    public static boolean formationMoveSeek(RobotController rc, Mem m, MapLocation objectiveLoc, int roundNum,
                                            RobotInfo[] nearbyAllies) throws GameActionException {

        MapLocation here = rc.getLocation();

        // Decrement stuck cooldown each turn
        if (m.stuckCooldown > 0) {
            m.stuckCooldown--;
        }

        // === LEADER BEHAVIOR ===
        if (amILeader(rc, m, objectiveLoc, nearbyAllies)) {
            m.isLeader = true;  // Track for indicator string

            // Leaders move toward objective
            Direction desiredDir = here.directionTo(objectiveLoc);
            if (desiredDir == Direction.CENTER) {
                desiredDir = Direction.NORTH; // Fallback if already at objective
            }

            // Attempt to move
            boolean moved = attackerMoveToward(rc, m, objectiveLoc);

            // Track stuck status
            if (moved) {
                m.leaderStuckCount = 0;  // Reset on successful move
            } else {
                m.leaderStuckCount++;
                // If stuck too long, enter cooldown and let someone else lead
                if (m.leaderStuckCount >= LEADER_STUCK_THRESHOLD) {
                    m.stuckCooldown = LEADER_COOLDOWN_ROUNDS;
                    m.leaderStuckCount = 0;
                }
            }

            // Squeak formation message (always squeak even if move failed, to keep followers informed)
            int seq = roundNum % FORMATION_SEQ_MODULO;
            Direction dirToSqueak = moved ? rc.getDirection() : desiredDir;
            int formationMsg = encodeFormation(seq, dirToSqueak);
            rc.squeak(formationMsg);

            return moved;
        }

        // === FOLLOWER BEHAVIOR ===
        m.isLeader = false;  // Track for indicator string
        m.leaderStuckCount = 0;  // Reset stuck count when not leader

        // Check if we have a fresh formation source
        boolean hasFreshSource = m.lastFormationSourceLoc != null &&
                                 (roundNum - m.formationSeenRound) <= FORMATION_FRESHNESS_ROUNDS;

        if (hasFreshSource) {
            // Move to become adjacent to the formation source while making progress
            return moveAdjacentToSource(rc, m, m.lastFormationSourceLoc, objectiveLoc, roundNum);
        } else {
            // No fresh source - regroup by moving toward nearest allied attacker
            return moveTowardNearestAlly(rc, m, objectiveLoc, nearbyAllies);
        }
    }

    /**
     * Move to become adjacent to the formation source location.
     *
     * FOLLOWER BEHAVIOR:
     * - If more than 2 tiles away from parent: HARD-FOLLOW (ignore objective, go straight to parent)
     * - If adjacent: SLIDE while maintaining adjacency (use deterministic slot based on id%8)
     * - Deterministic slot selection ensures multiple followers spread around the parent
     */
    public static boolean moveAdjacentToSource(RobotController rc, Mem m, MapLocation sourceLoc,
                                               MapLocation objectiveLoc, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();
        int distToParent = here.distanceSquaredTo(sourceLoc);

        // Distance > 2 tiles means distSq > 4 (e.g., distSq=5 is ~2.2 tiles away)
        boolean tooFarFromParent = distToParent > 4;

        // === HARD-FOLLOW MODE: More than 2 tiles away from parent ===
        if (tooFarFromParent) {
            // Ignore objective completely, just get to the parent
            return attackerMoveToward(rc, m, sourceLoc);
        }

        // === DETERMINISTIC SLOT SELECTION ===
        // Use id % 8 to pick a preferred adjacent slot around the parent
        int preferredSlot = rc.getID() % 8;
        Direction preferredDir = Direction.values()[preferredSlot]; // 0-7 maps to N, NE, E, SE, S, SW, W, NW

        // === ADJACENT BEHAVIOR: Slide while maintaining adjacency ===
        if (here.isAdjacentTo(sourceLoc)) {
            // Try to slide in formation direction while staying adjacent to parent
            if (m.lastFormationDir != null && m.lastFormationDir != Direction.CENTER) {
                // Try formation direction variations that keep us adjacent to parent
                Direction[] slideDirs = {
                    m.lastFormationDir,
                    m.lastFormationDir.rotateLeft(),
                    m.lastFormationDir.rotateRight()
                };

                for (Direction slideDir : slideDirs) {
                    MapLocation afterSlide = here.add(slideDir);
                    // Only slide if we stay adjacent to the parent's NEXT position (parent + formationDir)
                    MapLocation parentNext = sourceLoc.add(m.lastFormationDir);
                    if (afterSlide.isAdjacentTo(parentNext) || afterSlide.isAdjacentTo(sourceLoc)) {
                        if (tryMoveDirAvoidRecent(rc, m, slideDir, here)) {
                            return true;
                        }
                    }
                }
            }

            // If can't slide in formation dir, try to move to our preferred slot
            MapLocation preferredLoc = sourceLoc.add(preferredDir);
            if (!here.equals(preferredLoc) && here.isAdjacentTo(preferredLoc)) {
                Direction toPreferred = here.directionTo(preferredLoc);
                if (tryMoveDirAvoidRecent(rc, m, toPreferred, here)) {
                    return true;
                }
            }

            // Already adjacent, stay put (maintain adjacency)
            return false;
        }

        // === NOT ADJACENT YET: Move to preferred slot or closest adjacent tile ===
        // First, try to reach our deterministic preferred slot
        MapLocation preferredLoc = sourceLoc.add(preferredDir);

        // Check if preferred slot is reachable and valid
        if (rc.canSenseLocation(preferredLoc)) {
            MapInfo prefInfo = rc.senseMapInfo(preferredLoc);
            RobotInfo atPref = rc.senseRobotAtLocation(preferredLoc);

            if (!prefInfo.isWall() && atPref == null) {
                // Move toward our preferred slot
                Direction toPreferred = here.directionTo(preferredLoc);
                if (tryMoveDirAvoidRecent(rc, m, toPreferred, here)) {
                    return true;
                }
            }
        }

        // Preferred slot blocked - find any adjacent slot, prioritizing by slot distance from preferred
        Direction toSource = here.directionTo(sourceLoc);

        // Try slots in order of distance from our preferred slot
        for (int offset = 0; offset < 8; offset++) {
            int slot = (preferredSlot + offset) % 8;
            Direction slotDir = Direction.values()[slot];
            MapLocation slotLoc = sourceLoc.add(slotDir);

            if (!rc.canSenseLocation(slotLoc)) continue;

            MapInfo info = rc.senseMapInfo(slotLoc);
            if (info.isWall()) continue;

            RobotInfo atSlot = rc.senseRobotAtLocation(slotLoc);
            if (atSlot != null) continue;

            // Move toward this slot
            Direction toSlot = here.directionTo(slotLoc);
            if (tryMoveDirAvoidRecent(rc, m, toSlot, here)) {
                return true;
            }
        }

        // Fallback: just move toward source directly
        return attackerMoveToward(rc, m, sourceLoc);
    }

    /**
     * Move toward the nearest allied attacker to regroup.
     * Called when no fresh formation source is available.
     *
     * @param nearbyAllies Pre-sensed nearby allies (saves bytecode)
     */
    public static boolean moveTowardNearestAlly(RobotController rc, Mem m, MapLocation objectiveLoc,
                                                RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Find nearest allied baby rat (attacker) from pre-sensed list
        RobotInfo nearestAlly = null;
        int nearestDistSq = Integer.MAX_VALUE;

        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;
            int distSq = here.distanceSquaredTo(ally.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearestAlly = ally;
            }
        }

        if (nearestAlly != null) {
            // Move toward the nearest ally, but bias toward objective
            MapLocation allyLoc = nearestAlly.getLocation();
            Direction toAlly = here.directionTo(allyLoc);
            Direction toObj = here.directionTo(objectiveLoc);

            // If ally is between us and objective, go toward ally
            // Otherwise, compromise between ally and objective
            if (nearestDistSq > 4) {
                // Try directions that get us closer to both
                Direction[] tryDirs = {toAlly, toObj, toAlly.rotateLeft(), toAlly.rotateRight(),
                                       toObj.rotateLeft(), toObj.rotateRight()};
                for (Direction d : tryDirs) {
                    if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                        return true;
                    }
                }
            }
        }

        // No ally found or couldn't move toward them - fall back to moving toward objective
        return attackerMoveToward(rc, m, objectiveLoc);
    }

    // ==================== TIGHT SWARM CLUSTERING ====================

    /**
     * Score a potential move location based on cluster cohesion AND objective progress.
     * When far from objective, prioritize advancement. When close, prioritize clustering.
     *
     * @param loc The location to score
     * @param allyLocs Array of nearby ally locations
     * @param objectiveLoc The target we're moving toward
     * @param currentLoc Current location (for distance comparison)
     * @return Score for this location (higher = better)
     */
    public static int scoreClusterPosition(MapLocation loc, MapLocation[] allyLocs, MapLocation objectiveLoc,
                                           MapLocation currentLoc) {
        int score = 0;

        int currentDistToObj = currentLoc.distanceSquaredTo(objectiveLoc);
        int nextDistToObj = loc.distanceSquaredTo(objectiveLoc);

        // Determine how much to prioritize advancement vs clustering based on distance
        // Far from target (>100 distSq, ~10 tiles): prioritize advancement
        // Close to target: prioritize clustering for combat
        boolean prioritizeAdvancement = currentDistToObj > 100;

        // Score based on ally proximity (reduced when far from objective)
        int adjacencyBonus = prioritizeAdvancement ? 30 : CLUSTER_ADJACENCY_BONUS;
        int nearBonus = prioritizeAdvancement ? 10 : CLUSTER_NEAR_BONUS;

        for (MapLocation allyLoc : allyLocs) {
            if (allyLoc == null) continue;
            int distSq = loc.distanceSquaredTo(allyLoc);
            if (distSq <= 2) {
                score += adjacencyBonus;
            } else if (distSq <= CLUSTER_MAX_SPREAD_SQ) {
                score += nearBonus;
            }
        }

        // Score for moving toward objective - much higher when far away
        if (objectiveLoc != null) {
            int progressBonus = currentDistToObj - nextDistToObj;  // Positive if moving closer

            if (prioritizeAdvancement) {
                // Far from target: heavily reward progress
                score += progressBonus * 15;  // Each unit closer = 15 points
            } else {
                // Close to target: still reward progress but less
                score += progressBonus * 5;
            }
        }

        return score;
    }

    /** Backward-compatible overload */
    public static int scoreClusterPosition(MapLocation loc, MapLocation[] allyLocs, MapLocation objectiveLoc) {
        // When no current location provided, assume we're at a reasonable distance
        return scoreClusterPosition(loc, allyLocs, objectiveLoc, loc);
    }

    /**
     * Count adjacent allies from pre-sensed ally list.
     */
    public static int countAdjacentAllies(MapLocation here, RobotInfo[] nearbyAllies) {
        int count = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;
            if (here.distanceSquaredTo(ally.getLocation()) <= 2) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the centroid (average position) of nearby allies.
     */
    public static MapLocation getAllyCentroid(MapLocation here, RobotInfo[] nearbyAllies) {
        int sumX = here.x;
        int sumY = here.y;
        int count = 1;  // Include self

        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;
            sumX += ally.getLocation().x;
            sumY += ally.getLocation().y;
            count++;
        }

        return new MapLocation(sumX / count, sumY / count);
    }

    /**
     * Cluster-aware movement toward objective.
     * When far from objective, prioritizes advancement.
     * When close, prioritizes staying with allies for combat.
     *
     * @param rc RobotController
     * @param m Memory state
     * @param objectiveLoc Target location
     * @param nearbyAllies Pre-sensed nearby allies
     * @return true if movement was successful
     */
    public static boolean clusterMoveToward(RobotController rc, Mem m, MapLocation objectiveLoc,
                                            RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();
        int distToObjective = here.distanceSquaredTo(objectiveLoc);

        // Count current adjacent allies
        int currentAdjacent = countAdjacentAllies(here, nearbyAllies);

        // If we're far from objective, don't bother regrouping - just advance
        // Only regroup if we're close to the target AND have few allies
        boolean shouldRegroup = (distToObjective <= 100) && (currentAdjacent < CLUSTER_REGROUP_THRESHOLD);

        // Get ally locations for scoring
        MapLocation[] allyLocs = new MapLocation[nearbyAllies.length];
        for (int i = 0; i < nearbyAllies.length; i++) {
            if (nearbyAllies[i].getType() == UnitType.BABY_RAT) {
                allyLocs[i] = nearbyAllies[i].getLocation();
            }
        }

        // Score all possible move directions
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;

        // Also track the best "advancing" direction (toward objective)
        Direction advanceDir = here.directionTo(objectiveLoc);
        if (advanceDir == Direction.CENTER) advanceDir = Direction.NORTH;

        Direction[] allDirs = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
        };

        for (Direction dir : allDirs) {
            MapLocation next = here.add(dir);

            // Check if move is valid
            if (!rc.canSenseLocation(next)) continue;
            RobotInfo atNext = rc.senseRobotAtLocation(next);
            if (atNext != null) continue;
            MapInfo info = rc.senseMapInfo(next);
            if (info.isWall()) continue;

            // Anti-oscillation check
            if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }

            // Score this position (pass current location for distance-based scoring)
            int score = scoreClusterPosition(next, allyLocs, objectiveLoc, here);

            // Extra advancement bonus when not regrouping
            if (!shouldRegroup) {
                if (dir == advanceDir) score += 20;
                else if (dir == advanceDir.rotateLeft() || dir == advanceDir.rotateRight()) score += 12;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        // If no valid direction found, try standard movement (which has bug navigation)
        if (bestDir == null) {
            return attackerMoveToward(rc, m, objectiveLoc);
        }

        // Execute the move
        if (rc.getDirection() != bestDir && rc.canTurn(bestDir)) {
            rc.turn(bestDir);
        }

        if (rc.canMove(bestDir)) {
            rc.move(bestDir);
            m.prev2 = m.prev1;
            m.prev1 = here;
            return true;
        }

        // If scoring found a direction but can't move there, fall back to bug nav
        return attackerMoveToward(rc, m, objectiveLoc);
    }

    /**
     * Formation move that uses tight clustering.
     * Leaders move toward objective, followers cluster around them.
     * When far from objective, prioritizes advancement over clustering.
     */
    public static boolean formationMoveCluster(RobotController rc, Mem m, MapLocation objectiveLoc,
                                               int roundNum, RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();
        int distToObjective = here.distanceSquaredTo(objectiveLoc);

        // Decrement stuck cooldown each turn
        if (m.stuckCooldown > 0) {
            m.stuckCooldown--;
        }

        // Count adjacent allies
        int adjacentAllies = countAdjacentAllies(here, nearbyAllies);

        // Determine if we should prioritize advancement over clustering
        // Far from objective (>100 distSq, ~10 tiles): prioritize advancement
        boolean farFromObjective = distToObjective > 100;

        // === LEADER BEHAVIOR ===
        if (amILeader(rc, m, objectiveLoc, nearbyAllies)) {
            m.isLeader = true;

            boolean moved;
            if (farFromObjective) {
                // Far from objective: use direct movement, don't wait for cluster
                moved = attackerMoveToward(rc, m, objectiveLoc);
            } else {
                // Close to objective: use cluster movement for combat readiness
                moved = clusterMoveToward(rc, m, objectiveLoc, nearbyAllies);
            }

            // Track stuck status
            if (moved) {
                m.leaderStuckCount = 0;
            } else {
                m.leaderStuckCount++;
                if (m.leaderStuckCount >= LEADER_STUCK_THRESHOLD) {
                    m.stuckCooldown = LEADER_COOLDOWN_ROUNDS;
                    m.leaderStuckCount = 0;
                }
            }

            // Squeak formation message
            int seq = roundNum % FORMATION_SEQ_MODULO;
            Direction dirToSqueak = moved ? rc.getDirection() : here.directionTo(objectiveLoc);
            if (dirToSqueak == Direction.CENTER) dirToSqueak = Direction.NORTH;
            int formationMsg = encodeFormation(seq, dirToSqueak);
            rc.squeak(formationMsg);

            return moved;
        }

        // === FOLLOWER BEHAVIOR ===
        m.isLeader = false;
        m.leaderStuckCount = 0;

        // When far from objective, followers also prioritize advancement
        if (farFromObjective) {
            // Just move toward objective, clustering is less important
            return clusterMoveToward(rc, m, objectiveLoc, nearbyAllies);
        }

        // Close to objective: cluster for combat
        // If we have few adjacent allies, prioritize getting adjacent to someone
        if (adjacentAllies < CLUSTER_REGROUP_THRESHOLD) {
            // Find nearest ally and move adjacent to them
            RobotInfo nearestAlly = null;
            int nearestDist = Integer.MAX_VALUE;

            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() != UnitType.BABY_RAT) continue;
                int dist = here.distanceSquaredTo(ally.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestAlly = ally;
                }
            }

            if (nearestAlly != null && nearestDist > 2) {
                // Only regroup if ally is more than 1 tile away
                return moveAdjacentToSource(rc, m, nearestAlly.getLocation(), objectiveLoc, roundNum);
            }
        }

        // Otherwise, use cluster movement toward objective
        return clusterMoveToward(rc, m, objectiveLoc, nearbyAllies);
    }
}
