package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.Mem;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMovement.*;

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
}
