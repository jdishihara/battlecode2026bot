package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;

/**
 * Communication helpers for attacker rats.
 * Handles squeak processing, global alerts, and mesh swarm coordination.
 */
public class AttackerCommunication {

    // === CACHED DIRECTION ARRAY ===
    public static final Direction[] DIRS = Direction.values();

    // === LOCAL MESH SWARM CONSTANTS ===
    private static final int HELP_SQUEAK_COOLDOWN = 10;      // Rounds between help squeaks
    private static final int IMMEDIATE_THREAT_DIST_SQ = 8;   // Enemy within sqrt(8) ~= 2.8 tiles
    private static final int CAT_HEARING_DIST_SQ = 16;       // Cat within 4 tiles can hear
    private static final int CAT_SPOT_SQUEAK_COOLDOWN = 10;  // Rounds between cat-spot squeaks

    // === SQUEAK PROCESSING CONSTANTS ===
    private static final int LOCAL_SWARM_TTL = 15;  // Rounds to swarm after receiving MSG_HELP
    private static final int FORMATION_FRESHNESS_ROUNDS = 2;  // Only forward if msg is this fresh
    private static final int CAT_SILENCE_DIST_SQ = 16;        // Don't forward if cat within this dist²

    /** Check if a squeak message is a formation message. */
    public static boolean isFormationMsg(int msg) {
        return msg >= FORMATION_BASE && msg < FORMATION_MAX;
    }

    /**
     * Encode a formation squeak message.
     * @param seq Sequence number (0-127, will be masked)
     * @param dir Direction to move (ordinal 0-7)
     * @return Encoded formation message
     */
    public static int encodeFormation(int seq, Direction dir) {
        int dirOrdinal = dir.ordinal();  // 0-7 for NORTH, NORTHEAST, EAST, etc.
        return FORMATION_BASE + ((seq & 0x7F) * 8) + dirOrdinal;
    }

    /**
     * Decode sequence number from formation message.
     * @param msg Formation message
     * @return Sequence number (0-127)
     */
    public static int decodeFormationSeq(int msg) {
        return ((msg - FORMATION_BASE) / 8) & 0x7F;
    }

    /**
     * Decode direction from formation message.
     * @param msg Formation message
     * @return Direction ordinal (0-7), use DIRS[ordinal] to get Direction
     */
    public static int decodeFormationDir(int msg) {
        return (msg - FORMATION_BASE) % 8;
    }

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
            if (m.state != AttackState.ATTACK && m.state != AttackState.GUARD) {
                m.swarmTarget = m.alertRallyLoc;
                m.swarmStartRound = roundNum;
                m.state = AttackState.SWARM;
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
     * Wrap-safe sequence comparison for formation messages.
     * Returns true if seqA is "newer" than seqB (handles modulo 128 wraparound).
     * Uses half-window comparison: A is newer if (A - B + 128) % 128 < 64
     */
    public static boolean isNewerSeq(int seqA, int seqB) {
        if (seqB < 0) return true;  // No previous seq, A is newer
        int diff = (seqA - seqB + 128) % 128;
        return diff > 0 && diff < 64;
    }

    /**
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
                    m.lastFormationDir = DIRS[dirOrdinal];

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
                if (m.state != AttackState.ATTACK && m.state != AttackState.GUARD) {
                    m.localSwarmTarget = helpLoc;
                    m.localSwarmUntilRound = roundNum + LOCAL_SWARM_TTL;
                    m.swarmTarget = helpLoc;
                    m.swarmStartRound = roundNum;
                    m.state = AttackState.SWARM;
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
                    if (DEBUG) m.adoptedCatLoc = true;  // Flag for debug indicator
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
                    if (m.state == AttackState.SEEK || m.state == AttackState.SWARM) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_ENEMY_ATTACKING_KING:
                    if (m.state != AttackState.ATTACK) {
                        m.state = AttackState.GUARD;
                    }
                    break;

                case SQUEAK_NEED_BACKUP:
                case SQUEAK_RALLY_TO_ME:
                    if (m.state == AttackState.SEEK) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
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
}
