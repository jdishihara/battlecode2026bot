package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.Mem;
import examplefuncsplayer.units.BabyRatAttacker.AttackerState.AttackStateEnum;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerTargeting.*;

/**
 * Squeak communication, global alerts, and message processing for attacker rats.
 */
public class AttackerCommunication {

    /**
     * Read global alert from shared array and respond if active.
     * Attackers only read, never write to shared array.
     */
    public static void readGlobalAlert(RobotController rc, Mem m, int roundNum) throws GameActionException {
        int alertFlag = rc.readSharedArray(ALERT_FLAG);

        if (alertFlag == 0) {
            // No active alert
            m.alertRallyLoc = null;
            m.alertReason = 0;
            return;
        }

        int alertX = rc.readSharedArray(ALERT_X);
        int alertY = rc.readSharedArray(ALERT_Y);
        int alertExpires = rc.readSharedArray(ALERT_EXPIRES);
        int alertReason = rc.readSharedArray(ALERT_REASON);

        // Check if alert has expired (using mod 1000 for round tracking)
        int currentRoundMod = roundNum % 1000;
        int expiresDiff = alertExpires - currentRoundMod;
        if (expiresDiff < 0) expiresDiff += 1000;

        // Alert is expired if the difference is large (meaning we passed the expiry)
        // We consider it valid if expiry is within 100 rounds ahead
        boolean alertActive = expiresDiff > 0 && expiresDiff < 100;

        if (alertActive && alertX > 0 && alertY > 0) {
            // Store rally location and reason
            m.alertRallyLoc = new MapLocation(alertX, alertY);
            m.alertReason = alertReason;

            // If not already attacking a known king, respond to alert
            if (m.state != AttackStateEnum.ATTACK && m.state != AttackStateEnum.GUARD) {
                m.swarmTarget = m.alertRallyLoc;
                m.swarmStartRound = roundNum;
                m.state = AttackStateEnum.SWARM;
            }
        } else {
            // Alert expired or invalid
            m.alertRallyLoc = null;
            m.alertReason = 0;
        }
    }

    /**
     * Squeak for help when an enemy rat is an immediate threat.
     * Called once per turn during SEEK/ATTACK states.
     *
     * Triggers when:
     * - Enemy baby rat within dist² <= 8
     * - No cat within hearing range (dist <= 4)
     * - Cooldown has expired (N=10 rounds)
     *
     * @param nearbyAll Pre-sensed nearby robots (reused from run())
     * @param nearestCatAnyDistSq Distance to nearest cat (or MAX_VALUE if none)
     */
    public static void maybeSqueakHelp(RobotController rc, Mem m, int roundNum,
                                        RobotInfo[] nearbyAll, int nearestCatAnyDistSq) throws GameActionException {
        // Check cooldown
        if (roundNum - m.lastHelpSqueakRound < HELP_SQUEAK_COOLDOWN) {
            return;
        }

        // Check for cats in hearing range - don't squeak if cat nearby (avoid beaconing)
        // Uses pre-computed nearest cat distance instead of new sense call
        if (nearestCatAnyDistSq <= CAT_HEARING_DIST_SQ) {
            return;
        }

        // Check for immediate enemy threat using pre-sensed nearbyAll
        MapLocation here = rc.getLocation();
        Team enemy = rc.getTeam().opponent();
        boolean enemyThreat = false;

        for (RobotInfo r : nearbyAll) {
            if (r.getTeam() == enemy && r.getType() == UnitType.BABY_RAT) {
                int distSq = here.distanceSquaredTo(r.getLocation());
                if (distSq <= IMMEDIATE_THREAT_DIST_SQ) {
                    enemyThreat = true;
                    break;
                }
            }
        }

        if (enemyThreat) {
            // Squeak MSG_HELP encoded with our location
            int encodedMsg = encodeMsg(MSG_HELP, here.x, here.y);
            rc.squeak(encodedMsg);
            m.lastHelpSqueakRound = roundNum;
        }
    }

    /**
     * Squeak cat position when safe to do so.
     * Called once per turn after sensing cats.
     *
     * Triggers when:
     * - Cat sensed within avoidance radius (CAUTION or DANGER band)
     * - Nearest cat is outside cat-hearing range (distSq > 16)
     * - Cooldown has passed (10 rounds)
     *
     * @param nearestCatAnyDistSq Distance squared to nearest cat (or MAX_VALUE if none)
     * @param nearestCatAnyLoc Location of nearest cat (or null if none)
     * @return true if we broadcasted a cat-spot squeak
     */
    public static boolean maybeSqueakCatSpotted(RobotController rc, Mem m, int roundNum,
                                                 int nearestCatAnyDistSq, MapLocation nearestCatAnyLoc)
            throws GameActionException {
        // No cat sensed
        if (nearestCatAnyLoc == null) return false;

        // Only squeak if cat is in avoidance radius (CAUTION or DANGER)
        if (nearestCatAnyDistSq > CAT_CAUTION_DIST_SQ) return false;

        // Safety check: only squeak if cat is outside hearing range
        if (nearestCatAnyDistSq <= CAT_HEARING_DIST_SQ) return false;

        // Cooldown check
        if (roundNum - m.lastCatSqueakRound < CAT_SPOT_SQUEAK_COOLDOWN) return false;

        // Safe to squeak cat location
        int encodedMsg = encodeMsg(MSG_CAT_SPOTTED, nearestCatAnyLoc.x, nearestCatAnyLoc.y);
        rc.squeak(encodedMsg);
        m.lastCatSqueakRound = roundNum;
        return true;
    }

    /**
     * Squeak focus fire target to coordinate attacks.
     * Called when we find a high-priority target (low HP, near our king, etc.)
     *
     * Triggers when:
     * - We have a valid focus target (from selectPriorityTarget)
     * - Target is low HP or being attacked
     * - No cat within hearing range
     * - Cooldown has expired
     *
     * @param target The priority target to broadcast
     * @param nearestCatDistSq Distance to nearest cat (for safety check)
     * @return true if we broadcasted a focus target squeak
     */
    public static boolean maybeSqueakFocusTarget(RobotController rc, Mem m, int roundNum,
                                                  RobotInfo target, int nearestCatDistSq)
            throws GameActionException {
        if (target == null) return false;

        // Cooldown check
        if (roundNum - m.lastFocusSqueakRound < FOCUS_SQUEAK_COOLDOWN) return false;

        // Safety check: don't squeak if cat is in hearing range
        if (nearestCatDistSq <= CAT_HEARING_DIST_SQ) return false;

        // Only broadcast high-priority targets (low HP or critical HP)
        int targetHP = target.getHealth();
        boolean isHighPriority = targetHP <= FOCUS_LOW_HP_THRESHOLD;

        if (!isHighPriority) return false;

        // Broadcast the focus target location
        MapLocation targetLoc = target.getLocation();
        int encodedMsg = encodeMsg(MSG_ENEMY_TARGET, targetLoc.x, targetLoc.y);
        rc.squeak(encodedMsg);
        m.lastFocusSqueakRound = roundNum;

        // Update our own focus target
        m.focusTargetLoc = targetLoc;
        m.focusTargetHP = targetHP;
        m.focusTargetID = target.getID();
        m.focusTargetRound = roundNum;

        return true;
    }

    /**
     * Process incoming squeak messages from nearby rats.
     * Handles formation messages, help requests, cat warnings, and legacy squeaks.
     *
     * @param nearestCatAnyDistSq Distance to nearest cat (or MAX_VALUE if none) - for cat silence check
     */
    public static void processIncomingSqueaks(RobotController rc, Mem m, int roundNum, int nearestCatAnyDistSq) throws GameActionException {
        Message[] messages = rc.readSqueaks(-1);
        MapLocation here = rc.getLocation();

        // Track if we received a new formation message this turn (for forwarding)
        int newFormationSeq = -1;
        Direction newFormationDir = null;
        int newFormationMsgRound = -1;

        for (Message msg : messages) {
            int content = msg.getBytes();
            MapLocation senderLoc = msg.getSource();
            int msgRound = msg.getRound();

            // Skip if too far away
            if (here.distanceSquaredTo(senderLoc) > SWARM_RESPONSE_DISTANCE_SQ) {
                continue;
            }

            // === CHECK FOR FORMATION MESSAGE ===
            if (isFormationMsg(content)) {
                int seq = decodeFormationSeq(content);
                int dirOrdinal = decodeFormationDir(content);

                // Check if this is newer than what we've seen (wrap-safe)
                boolean isNewer = isNewerSeq(seq, m.latestFormationSeq);

                // Also accept if same seq but more recent round (tie-breaker)
                boolean isSameSeqButFresher = (seq == m.latestFormationSeq) &&
                                               (msgRound > m.formationSeenRound);

                if (isNewer || isSameSeqButFresher) {
                    // Update stored formation info
                    m.latestFormationSeq = seq;
                    m.formationSeenRound = msgRound;
                    m.lastFormationSourceLoc = senderLoc;
                    m.lastFormationDir = Direction.values()[dirOrdinal];

                    // Track for potential forwarding (only if fresh enough)
                    if (roundNum - msgRound <= FORMATION_FRESHNESS_ROUNDS) {
                        newFormationSeq = seq;
                        newFormationDir = m.lastFormationDir;
                        newFormationMsgRound = msgRound;
                    }
                }
                continue;
            }

            // === TRY DECODING AS ENCODED MESSAGE (MSG_HELP, MSG_KING_DANGER, MSG_CAT_SPOTTED, etc.) ===
            int msgType = decodeType(content);
            if (msgType == MSG_HELP) {
                // Decode location from message
                int helpX = decodeX(content);
                int helpY = decodeY(content);
                MapLocation helpLoc = new MapLocation(helpX, helpY);

                // Respond to help request - set local swarm target with TTL
                // Always refresh TTL on new HELP, update target if newer/closer
                if (m.state != AttackStateEnum.ATTACK && m.state != AttackStateEnum.GUARD) {
                    m.localSwarmTarget = helpLoc;
                    m.localSwarmUntilRound = roundNum + LOCAL_SWARM_TTL;
                    m.swarmTarget = helpLoc;
                    m.swarmStartRound = roundNum;
                    m.state = AttackStateEnum.SWARM;
                }
                continue;
            }

            if (msgType == MSG_CAT_SPOTTED) {
                // Decode cat location from message
                int catX = decodeX(content);
                int catY = decodeY(content);
                MapLocation reportedCatLoc = new MapLocation(catX, catY);

                // Adopt as memory if we don't have fresher info
                // (treat received cat info as "seen" but with slight age penalty)
                int roundsSinceOurSighting = roundNum - m.lastCatSeenRound;
                if (m.lastCatLoc == null || roundsSinceOurSighting > 2) {
                    // Our info is stale (>2 rounds old) or non-existent, adopt reported loc
                    m.lastCatLoc = reportedCatLoc;
                    // Set seen round slightly in the past so direct sightings take priority
                    m.lastCatSeenRound = roundNum - 1;
                    m.adoptedCatLoc = true;  // Flag for debug indicator
                }
                continue;
            }

            if (msgType == MSG_ENEMY_TARGET) {
                // Decode focus fire target location from message
                int targetX = decodeX(content);
                int targetY = decodeY(content);
                MapLocation targetLoc = new MapLocation(targetX, targetY);

                // Only respond if we're close enough to help
                int distToTarget = here.distanceSquaredTo(targetLoc);
                if (distToTarget > FOCUS_RESPONSE_DIST_SQ) continue;

                // Check if this is a newer/better target than what we have
                int roundsSinceOurTarget = roundNum - m.focusTargetRound;
                boolean shouldAdopt = false;

                if (m.focusTargetLoc == null || roundsSinceOurTarget > FOCUS_FIRE_TTL) {
                    // No current target or target expired - adopt new one
                    shouldAdopt = true;
                } else if (distToTarget < here.distanceSquaredTo(m.focusTargetLoc)) {
                    // New target is closer - adopt it
                    shouldAdopt = true;
                }

                if (shouldAdopt) {
                    m.focusTargetLoc = targetLoc;
                    m.focusTargetRound = roundNum;
                    m.focusTargetHP = -1;  // Unknown HP, will verify on sight
                    m.focusTargetID = -1;  // Unknown ID, will verify on sight
                }
                continue;
            }

            // Handle symmetry elimination broadcast
            if (content >= SQUEAK_SYMMETRY_ELIMINATED && content < SQUEAK_SYMMETRY_ELIMINATED + 30) {
                int eliminatedSym = (content - SQUEAK_SYMMETRY_ELIMINATED) / 10;
                if (eliminatedSym >= 0 && eliminatedSym < 3) {
                    m.localSymmetryEliminated |= (1 << eliminatedSym);
                }
                continue;
            }

            // Handle legacy squeak types (non-encoded)
            switch (content) {
                case SQUEAK_ENEMY_KING_FOUND:
                    if (m.state == AttackStateEnum.SEEK || m.state == AttackStateEnum.SWARM) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackStateEnum.SWARM;
                    }
                    break;

                case SQUEAK_ENEMY_ATTACKING_KING:
                    if (m.state != AttackStateEnum.ATTACK) {
                        m.state = AttackStateEnum.GUARD;
                    }
                    break;

                case SQUEAK_NEED_BACKUP:
                case SQUEAK_RALLY_TO_ME:
                    if (m.state == AttackStateEnum.SEEK) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackStateEnum.SWARM;
                    }
                    break;

                case SQUEAK_RETREAT:
                    if (m.kingLocation != null) {
                        m.currentTarget = m.kingLocation;
                    }
                    break;
            }
        }

        // === FORMATION MESSAGE FORWARDING ===
        // Forward if we received a new seq we haven't forwarded yet
        if (newFormationSeq >= 0 && newFormationDir != null) {
            // Check if we already forwarded this seq
            if (newFormationSeq != m.lastSeqForwarded) {
                // Freshness check already done above when setting newFormationSeq

                // Cat silence check: don't forward if cat nearby (avoid giving away position)
                // Uses pre-computed nearest cat distance instead of new sense call
                boolean catNearby = (nearestCatAnyDistSq <= CAT_SILENCE_DIST_SQ);

                if (!catNearby) {
                    // Forward the same formation message
                    int forwardMsg = encodeFormation(newFormationSeq, newFormationDir);
                    rc.squeak(forwardMsg);
                    m.lastSeqForwarded = newFormationSeq;
                }
            }
        }
    }

    /**
     * Read enemy king location from shared array if recent.
     */
    public static MapLocation readEnemyKingLocation(RobotController rc, int roundNum) throws GameActionException {
        int enemyKingX = rc.readSharedArray(ENEMY_KING_X_INDEX);
        int enemyKingY = rc.readSharedArray(ENEMY_KING_Y_INDEX);
        int seenRound = rc.readSharedArray(ENEMY_KING_SEEN_ROUND_INDEX);

        if (enemyKingX > 0 && enemyKingY > 0) {
            int roundsAgo = (roundNum % 1000) - seenRound;
            if (roundsAgo < 0) roundsAgo += 1000;

            if (roundsAgo < 50) {
                return new MapLocation(enemyKingX, enemyKingY);
            }
        }
        return null;
    }
}
