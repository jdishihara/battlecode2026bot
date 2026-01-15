package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerSymmetry.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerStates.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCommunication.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerNavigation.*;

/**
 * ATTACKER baby rat: CLUSTERED SYMMETRY SEARCH
 *
 * Key strategy:
 * - ALL attackers search the SAME symmetry location together (cluster)
 * - When the cluster confirms a symmetry is wrong, ALL switch to next symmetry
 * - This keeps rats together for better combat when king is found
 *
 * Symmetry elimination triggers:
 * 1. Rat reaches predicted location and visually confirms no king
 * 2. Rat detects impassable terrain at predicted location
 * 3. Timeout: cluster has searched current symmetry for too long
 * 4. Boundary detection: predicted location is off-map or inaccessible
 *
 * Communication:
 * - Shared array stores current symmetry target (so all rats converge)
 * - Squeaks coordinate local movement and announce findings
 * - When king found, all nearby rats swarm
 *
 * BUG FIX: Now passes effectiveSymmetry to tryEliminateCurrentSymmetry()
 * instead of relying on globalSymmetry (which baby rats can't write to).
 */
public class BabyRatAttacker {

    /** Call this once per turn for attacker rats. */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc.getID());
        int roundNum = rc.getRoundNum();
        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // === CONSOLIDATED SENSE CALLS (bytecode optimization) ===
        // Sense 1: All nearby robots for allies + leadership
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(LEADER_SENSE_RADIUS_SQ, myTeam);

        // Sense 2: All nearby robots (for cats, enemies, enemy king)
        RobotInfo[] nearbyAll = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);

        // Filter from nearbyAll: cats, enemy rats, enemy king
        RobotInfo nearestCat = null;           // Cat within flee radius (for flee behavior)
        RobotInfo enemyKing = null;
        RobotInfo nearbyEnemyRatNearKing = null;
        int nearestCatDistSq = Integer.MAX_VALUE;
        int nearestEnemyKingDistSq = Integer.MAX_VALUE;

        // Track nearest cat at ANY range (for early detection/memory)
        int nearestCatAnyDistSq = Integer.MAX_VALUE;
        MapLocation nearestCatAnyLoc = null;

        for (RobotInfo r : nearbyAll) {
            if (r.getType() == UnitType.CAT) {
                int d = here.distanceSquaredTo(r.getLocation());
                // Track nearest cat at any range
                if (d < nearestCatAnyDistSq) {
                    nearestCatAnyDistSq = d;
                    nearestCatAnyLoc = r.getLocation();
                }
                // Track cat for flee behavior (only within flee radius)
                if (d < nearestCatDistSq && d <= ATTACKER_CAT_FLEE_RADIUS) {
                    nearestCatDistSq = d;
                    nearestCat = r;
                }
            } else if (r.getTeam() == enemyTeam) {
                if (r.getType() == UnitType.RAT_KING) {
                    int d = here.distanceSquaredTo(r.getLocation());
                    if (d < nearestEnemyKingDistSq) {
                        nearestEnemyKingDistSq = d;
                        enemyKing = r;
                    }
                } else if (r.getType() == UnitType.BABY_RAT && m.kingLocation != null) {
                    int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                    if (distToKing <= 100 && nearbyEnemyRatNearKing == null) {
                        nearbyEnemyRatNearKing = r;
                    }
                }
            }
        }

        // Reset per-turn debug flags
        if (DEBUG) {
            m.adoptedCatLoc = false;
            m.broadcastedCat = false;
        }

        // Update cat memory if any cat seen this turn
        if (nearestCatAnyLoc != null) {
            m.lastCatLoc = nearestCatAnyLoc;
            m.lastCatSeenRound = roundNum;
            if (DEBUG) m.adoptedCatLoc = false;  // Direct sighting, not adopted
        }
        // Note: Do NOT clear lastCatLoc if no cat seen - keep as memory

        // Set current turn cat info for movement layer
        if (nearestCatAnyLoc != null) {
            // Cat currently visible - use actual position and distance
            m.turnCatLoc = nearestCatAnyLoc;
            if (nearestCatAnyDistSq <= CAT_PANIC_DIST_SQ) {
                m.turnCatBand = CAT_BAND_PANIC;
            } else if (nearestCatAnyDistSq <= CAT_DANGER_DIST_SQ) {
                m.turnCatBand = CAT_BAND_DANGER;
            } else if (nearestCatAnyDistSq <= CAT_CAUTION_DIST_SQ) {
                m.turnCatBand = CAT_BAND_CAUTION;
            } else {
                m.turnCatBand = CAT_BAND_NONE;
            }
        } else {
            // No cat visible - check memory
            int roundsSinceSeen = roundNum - m.lastCatSeenRound;
            if (m.lastCatLoc != null && roundsSinceSeen <= CAT_MEMORY_TTL) {
                // Fresh memory - use as "virtual cat" for avoidance
                m.turnCatLoc = m.lastCatLoc;
                // Very recent memory (<=2 rounds) uses DANGER band, older uses CAUTION
                m.turnCatBand = (roundsSinceSeen <= CAT_MEMORY_DANGER_TTL)
                    ? CAT_BAND_DANGER : CAT_BAND_CAUTION;
            } else {
                // No memory or stale - no cat avoidance
                m.turnCatLoc = null;
                m.turnCatBand = CAT_BAND_NONE;
            }
        }

        // === SAFE CAT-SPOT SQUEAK ===
        // Broadcast cat position if within avoidance range but outside hearing range
        if (maybeSqueakCatSpotted(rc, m, roundNum, nearestCatAnyDistSq, nearestCatAnyLoc)) {
            if (DEBUG) m.broadcastedCat = true;
        }

        // === READ OUR KING LOCATION ===
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) {
            m.kingLocation = new MapLocation(kx, ky);
            if (m.possibleEnemyKingLocs == null) {
                m.possibleEnemyKingLocs = computePossibleEnemyKingLocations(rc, m.kingLocation);
            }
        }

        // === READ GLOBAL ALERT (hive mind) ===
        readGlobalAlert(rc, m, roundNum);

        // === READ GLOBAL SYMMETRY STATE ===
        int globalSymmetry = rc.readSharedArray(CURRENT_SYMMETRY_INDEX);
        int globalEliminated = rc.readSharedArray(SYMMETRY_ELIMINATED_INDEX);

        // Sync local eliminated state with global
        m.localSymmetryEliminated |= globalEliminated;

        // === COMPUTE EFFECTIVE SYMMETRY EARLY ===
        // This is what we're ACTUALLY searching for, using local knowledge
        int effectiveSymmetry = computeEffectiveSymmetry(globalSymmetry, m.localSymmetryEliminated);

        // Track how long we've been searching THIS symmetry (reset when symmetry changes)
        if (effectiveSymmetry != m.lastSymmetryChecked) {
            m.lastSymmetryChecked = effectiveSymmetry;
            m.roundsSearchingCurrentSymmetry = 0;
            m.reachedCurrentTarget = false;
        }
        m.roundsSearchingCurrentSymmetry++;

        // === CHECK FOR CONFIRMED ENEMY KING IN SHARED ARRAY ===
        MapLocation knownEnemyKing = readEnemyKingLocation(rc, roundNum);
        if (knownEnemyKing != null) {
            m.targetKingLoc = knownEnemyKing;
            if (m.state == AttackState.SEEK) {
                m.state = AttackState.ATTACK;
            }
        }

        // === PROCESS INCOMING SQUEAKS ===
        processIncomingSqueaks(rc, m, roundNum, nearestCatAnyDistSq);

        // === GUARD MODE: Check if enemy rats near our king (using pre-sensed data) ===
        if (nearbyEnemyRatNearKing != null && m.state != AttackState.ATTACK) {
            m.state = AttackState.GUARD;

            // Squeak for backup!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                rc.squeak(SQUEAK_ENEMY_ATTACKING_KING);
                m.lastSqueakRound = roundNum;
            }
        }

        // === CAT PANIC FLEE (only in panic range) ===
        // In PANIC band, hard flee and return. In DANGER/CAUTION, movement layer handles it.
        if (m.turnCatBand == CAT_BAND_PANIC && nearestCatAnyLoc != null) {
            runAttackerFleeFrom(rc, m, nearestCatAnyLoc);
            if (DEBUG) rc.setIndicatorString("PANIC FLEE | dist²:" + nearestCatAnyDistSq + " | " + m.state);
            return;
        }

        // === LOOK FOR ENEMY KING (using pre-sensed data) ===
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;

            // Confirm which symmetry this is
            int confirmedSym = confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());

            // Squeak to summon allies!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                rc.squeak(SQUEAK_ENEMY_KING_FOUND);
                m.lastSqueakRound = roundNum;
            }
        } else {
            m.turnsSinceKingSeen++;
            if (m.targetKingLoc != null && m.turnsSinceKingSeen > MAX_STALE_TARGET_TURNS) {
                m.targetKingLoc = null;
                if (m.state == AttackState.ATTACK) {
                    m.state = AttackState.SEEK;
                }
            }
        }

        // === TRY TO ELIMINATE CURRENT SYMMETRY ===
        // FIX: Pass effectiveSymmetry instead of relying on globalSymmetry
        if (m.state == AttackState.SEEK && m.possibleEnemyKingLocs != null) {
            tryEliminateCurrentSymmetry(rc, m, effectiveSymmetry, roundNum);
        }

        // === SQUEAK FOR HELP (uses pre-sensed nearbyAll) ===
        if (m.state == AttackState.SEEK || m.state == AttackState.ATTACK) {
            maybeSqueakHelp(rc, m, roundNum, nearbyAll, nearestCatAnyDistSq);
        }

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum, effectiveSymmetry, nearbyAllies, nearbyAll); break;
            case ATTACK: runAttack(rc, m, roundNum, nearbyAll, nearbyAllies); break;
            case GUARD:  runGuard(rc, m, roundNum, nearbyAll); break;
            case SWARM:  runSwarm(rc, m, roundNum, nearbyAll); break;
        }

        // Debug indicator (wrapped to save bytecode in tournament)
        if (DEBUG) {
            String symStr = effectiveSymmetry == SYM_ROTATIONAL ? "ROT" :
                            effectiveSymmetry == SYM_HORIZONTAL ? "HOR" :
                            effectiveSymmetry == SYM_VERTICAL ? "VER" : "UNK";
            String elimStr = ((m.localSymmetryEliminated & 1) != 0 ? "R" : "") +
                             ((m.localSymmetryEliminated & 2) != 0 ? "H" : "") +
                             ((m.localSymmetryEliminated & 4) != 0 ? "V" : "");
            String roleStr = m.isLeader ? "LEADER" : "FOLLOW";
            String catBandStr = m.turnCatBand == CAT_BAND_PANIC ? "PANIC" :
                                m.turnCatBand == CAT_BAND_DANGER ? "DANGER" :
                                m.turnCatBand == CAT_BAND_CAUTION ? "CAUTION" : "-";
            String catDistStr = nearestCatAnyLoc != null ? String.valueOf(nearestCatAnyDistSq) : "-";
            String catCommStr = m.broadcastedCat ? "TX" : (m.adoptedCatLoc ? "RX" : "");
            rc.setIndicatorString(roleStr + " | " + m.state + " | Cat:" + catBandStr + "(" + catDistStr + ")" +
                    (catCommStr.isEmpty() ? "" : catCommStr) +
                    " | Sym:" + symStr + elimStr + " | Tgt:" + (m.currentTarget != null ? m.currentTarget : "none"));
        }
    }
}
