package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCommunication.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerNavigation.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCombat.*;

/**
 * Formation movement and leader selection for attacker rats.
 * Handles coordinated movement in clusters.
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

            // Set anchor before moving (leader is the anchor)
            m.formationAnchorLoc = here;
            m.formationAnchorId = rc.getID();

            // Leaders move toward objective
            Direction desiredDir = here.directionTo(objectiveLoc);
            if (desiredDir == Direction.CENTER) {
                desiredDir = Direction.NORTH; // Fallback if already at objective
            }

            // Attempt to move (with bulldoze for formation mode)
            MapLocation oldLoc = here;
            boolean moved = attackerMoveToward(rc, m, objectiveLoc, roundNum, nearbyAllies, true);

            // Update anchor to new location after moving
            if (moved) {
                m.formationAnchorLoc = rc.getLocation();
                m.leaderStuckCount = 0;  // Reset on successful move
                m.leaderWaitTurns = 0;   // Reset wait turns on successful move
            } else {
                // Movement blocked - try clump-preserving dig toward objective
                if (maybeDigForClump(rc, m, objectiveLoc, nearbyAllies, roundNum)) {
                    // Dug successfully - reset stuck count since we're making progress
                    m.leaderStuckCount = 0;
                } else {
                    m.leaderStuckCount++;
                    // If stuck too long, enter cooldown and let someone else lead
                    if (m.leaderStuckCount >= LEADER_STUCK_THRESHOLD) {
                        m.stuckCooldown = LEADER_COOLDOWN_ROUNDS;
                        m.leaderStuckCount = 0;
                    }
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
        m.leaderWaitTurns = 0;   // Reset wait turns when not leader

        // Check if we have a fresh formation source
        int FORMATION_FRESHNESS_ROUNDS = 2;
        boolean hasFreshSource = m.lastFormationSourceLoc != null &&
                                 (roundNum - m.formationSeenRound) <= FORMATION_FRESHNESS_ROUNDS;

        if (hasFreshSource) {
            // Set anchor from formation message source
            // Note: Message API doesn't provide sender ID, so use -1
            m.formationAnchorLoc = m.lastFormationSourceLoc;
            m.formationAnchorId = -1;  // Sender ID not available from Message API

            // Move to become adjacent to the formation source while making progress
            return moveAdjacentToSource(rc, m, m.lastFormationSourceLoc, objectiveLoc, roundNum, nearbyAllies);
        } else {
            // No fresh source - regroup by moving toward nearest allied attacker
            return moveTowardNearestAlly(rc, m, objectiveLoc, nearbyAllies, roundNum);
        }
    }

    /**
     * Move to become adjacent to the formation source location.
     *
     * FOLLOWER BEHAVIOR:
     * - If more than 2 tiles away from parent: HARD-FOLLOW (ignore objective, go straight to parent)
     * - If adjacent: SLIDE while maintaining adjacency (use deterministic slot based on id%8)
     * - Deterministic slot selection ensures multiple followers spread around the parent
     *
     * CLUMP-PRESERVING DIG: In HARD-FOLLOW mode, if movement fails due to dirt blocking,
     * front rats may dig to widen the choke point and keep the group together.
     */
    public static boolean moveAdjacentToSource(RobotController rc, Mem m, MapLocation sourceLoc,
                                        MapLocation objectiveLoc, int roundNum,
                                        RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();
        int distToParent = here.distanceSquaredTo(sourceLoc);

        // Distance > 2 tiles means distSq > 4 (e.g., distSq=5 is ~2.2 tiles away)
        boolean tooFarFromParent = distToParent > 4;

        // === HARD-FOLLOW MODE: More than 2 tiles away from parent ===
        if (tooFarFromParent) {
            // Ignore objective completely, just get to the parent
            // Pass roundNum and nearbyAllies for bulldoze behavior with formation mode gates
            boolean moved = attackerMoveToward(rc, m, sourceLoc, roundNum, nearbyAllies, true);
            if (!moved) {
                // Movement blocked - try digging dirt directly toward source (not objective)
                if (maybeDigBlockingDirt(rc, m, sourceLoc, nearbyAllies, roundNum, true)) {
                    return true;  // Dug successfully, counts as action taken
                }
            }
            return moved;
        }

        // === DETERMINISTIC SLOT SELECTION ===
        // Use id % 8 to pick a preferred adjacent slot around the parent
        int preferredSlot = rc.getID() % 8;
        Direction preferredDir = DIRS[preferredSlot]; // 0-7 maps to N, NE, E, SE, S, SW, W, NW

        // === ADJACENT BEHAVIOR: Slide while maintaining adjacency ===
        if (here.isAdjacentTo(sourceLoc)) {
            // Try to slide in formation direction while staying adjacent to parent
            if (m.lastFormationDir != null && m.lastFormationDir != Direction.CENTER) {
                // Fill reusable buffer: formation direction variations
                m.buf3[0] = m.lastFormationDir;
                m.buf3[1] = m.lastFormationDir.rotateLeft();
                m.buf3[2] = m.lastFormationDir.rotateRight();

                for (int i = 0; i < 3; i++) {
                    Direction slideDir = m.buf3[i];
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

        // Try preferred slot first - let canMove handle occupancy/wall checks
        Direction toPreferred = here.directionTo(preferredLoc);
        if (tryMoveDirAvoidRecent(rc, m, toPreferred, here)) {
            return true;
        }

        // Preferred slot blocked - try other slots in order of distance from preferred
        // Let canMove handle occupancy/wall checks (saves bytecode from deep sensing)
        for (int offset = 1; offset < 8; offset++) {
            int slot = (preferredSlot + offset) % 8;
            Direction slotDir = DIRS[slot];
            MapLocation slotLoc = sourceLoc.add(slotDir);

            Direction toSlot = here.directionTo(slotLoc);
            if (tryMoveDirAvoidRecent(rc, m, toSlot, here)) {
                return true;
            }
        }

        // Fallback: just move toward source directly (with bulldoze)
        return attackerMoveToward(rc, m, sourceLoc, roundNum, nearbyAllies, true);
    }

    /**
     * Move toward the nearest allied attacker to regroup.
     * Called when no fresh formation source is available.
     *
     * @param nearbyAllies Pre-sensed nearby allies (saves bytecode)
     * @param roundNum Current round number (for dig cooldown)
     */
    public static boolean moveTowardNearestAlly(RobotController rc, Mem m, MapLocation objectiveLoc,
                                         RobotInfo[] nearbyAllies, int roundNum) throws GameActionException {
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
                // Fill reusable buffer: directions that get us closer to both
                m.buf6[0] = toAlly;
                m.buf6[1] = toObj;
                m.buf6[2] = toAlly.rotateLeft();
                m.buf6[3] = toAlly.rotateRight();
                m.buf6[4] = toObj.rotateLeft();
                m.buf6[5] = toObj.rotateRight();
                for (int i = 0; i < 6; i++) {
                    if (tryMoveDirAvoidRecent(rc, m, m.buf6[i], here)) {
                        return true;
                    }
                }
            }
        }

        // No ally found or couldn't move toward them - fall back to moving toward objective (with bulldoze)
        return attackerMoveToward(rc, m, objectiveLoc, roundNum, nearbyAllies, true);
    }
}
