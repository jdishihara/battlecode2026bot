package defaultbot.units.BabyRatAttacker;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.Mem;
import examplefuncsplayer.units.BabyRatAttacker.AttackerState.AttackStateEnum;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.mem;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMovement.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCommunication.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerFormation.*;

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
        m.adoptedCatLoc = false;
        m.broadcastedCat = false;

        // Update cat memory if any cat seen this turn
        if (nearestCatAnyLoc != null) {
            m.lastCatLoc = nearestCatAnyLoc;
            m.lastCatSeenRound = roundNum;
            m.adoptedCatLoc = false;  // Direct sighting, not adopted
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
            m.broadcastedCat = true;
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
            if (m.state == AttackStateEnum.SEEK) {
                m.state = AttackStateEnum.ATTACK;
            }
        }

        // === PROCESS INCOMING SQUEAKS ===
        processIncomingSqueaks(rc, m, roundNum, nearestCatAnyDistSq);

        // === GUARD MODE: Check if enemy rats near our king (using pre-sensed data) ===
        if (nearbyEnemyRatNearKing != null && m.state != AttackStateEnum.ATTACK) {
            m.state = AttackStateEnum.GUARD;

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
            rc.setIndicatorString("PANIC FLEE | dist²:" + nearestCatAnyDistSq + " | " + m.state);
            return;
        }

        // === LOOK FOR ENEMY KING (using pre-sensed data) ===
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackStateEnum.ATTACK;

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
                if (m.state == AttackStateEnum.ATTACK) {
                    m.state = AttackStateEnum.SEEK;
                }
            }
        }

        // === TRY TO ELIMINATE CURRENT SYMMETRY ===
        // FIX: Pass effectiveSymmetry instead of relying on globalSymmetry
        if (m.state == AttackStateEnum.SEEK && m.possibleEnemyKingLocs != null) {
            tryEliminateCurrentSymmetry(rc, m, effectiveSymmetry, roundNum);
        }

        // === SQUEAK FOR HELP (uses pre-sensed nearbyAll) ===
        if (m.state == AttackStateEnum.SEEK || m.state == AttackStateEnum.ATTACK) {
            maybeSqueakHelp(rc, m, roundNum, nearbyAll, nearestCatAnyDistSq);
        }

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum, effectiveSymmetry, nearbyAllies); break;
            case ATTACK: runAttack(rc, m, roundNum); break;
            case GUARD:  runGuard(rc, m, roundNum); break;
            case SWARM:  runSwarm(rc, m, roundNum); break;
        }

        // Debug indicator
        String symStr = effectiveSymmetry == SYM_ROTATIONAL ? "ROT" :
                        effectiveSymmetry == SYM_HORIZONTAL ? "HOR" :
                        effectiveSymmetry == SYM_VERTICAL ? "VER" : "UNK";
        String elimStr = ((m.localSymmetryEliminated & 1) != 0 ? "R" : "") +
                         ((m.localSymmetryEliminated & 2) != 0 ? "H" : "") +
                         ((m.localSymmetryEliminated & 4) != 0 ? "V" : "");
        String roleStr = m.isLeader ? "LEADER" : "FOLLOW";
        int seqStr = m.latestFormationSeq >= 0 ? m.latestFormationSeq : (roundNum % FORMATION_SEQ_MODULO);
        String stuckStr = m.stuckCooldown > 0 ? " CD:" + m.stuckCooldown : "";
        String catBandStr = m.turnCatBand == CAT_BAND_PANIC ? "PANIC" :
                            m.turnCatBand == CAT_BAND_DANGER ? "DANGER" :
                            m.turnCatBand == CAT_BAND_CAUTION ? "CAUTION" : "-";
        String catDistStr = nearestCatAnyLoc != null ? String.valueOf(nearestCatAnyDistSq) : "-";
        String catCommStr = m.broadcastedCat ? "TX" : (m.adoptedCatLoc ? "RX" : "");
        rc.setIndicatorString(roleStr + " | " + m.state + " | Cat:" + catBandStr + "(" + catDistStr + ")" +
                (catCommStr.isEmpty() ? "" : catCommStr) +
                " | Sym:" + symStr + elimStr + " | Tgt:" + (m.currentTarget != null ? m.currentTarget : "none"));
    }

    // ==================== SYMMETRY HELPERS ====================

    /**
     * Compute the effective symmetry to search, using local elimination knowledge.
     * This is separate from globalSymmetry because baby rats can't write to shared array.
     */
    static int computeEffectiveSymmetry(int globalSymmetry, int localEliminated) {
        // If global symmetry is set and not eliminated locally, use it
        if (globalSymmetry >= SYM_ROTATIONAL && globalSymmetry <= SYM_VERTICAL) {
            int symBit = 1 << (globalSymmetry - 1);
            if ((localEliminated & symBit) == 0) {
                return globalSymmetry;
            }
        }

        // Otherwise, find first non-eliminated symmetry
        return getNextValidSymmetry(localEliminated);
    }

    /**
     * Compute all 3 possible enemy king locations based on map symmetry types.
     * Returns array indexed by (symmetry - 1): [0]=rotational, [1]=horizontal, [2]=vertical
     */
    static MapLocation[] computePossibleEnemyKingLocations(RobotController rc, MapLocation ourKing) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int kx = ourKing.x;
        int ky = ourKing.y;

        return new MapLocation[] {
            new MapLocation(w - 1 - kx, h - 1 - ky),  // [0] Rotational (180°)
            new MapLocation(w - 1 - kx, ky),          // [1] Horizontal reflection
            new MapLocation(kx, h - 1 - ky)           // [2] Vertical reflection
        };
    }

    /**
     * When we find the enemy king, figure out which symmetry type the map uses.
     * Returns the confirmed symmetry type (1, 2, or 3), or 0 if can't determine.
     */
    static int confirmSymmetryFromKingLocation(RobotController rc, Mem m, MapLocation enemyKingLoc) {
        if (m.possibleEnemyKingLocs == null) return 0;

        // King is 3x3, so check if actual location is close to any prediction
        for (int i = 0; i < 3; i++) {
            if (enemyKingLoc.distanceSquaredTo(m.possibleEnemyKingLocs[i]) <= KING_SEARCH_RADIUS) {
                int symType = i + 1; // Convert to 1-indexed

                // Mark other symmetries as eliminated
                for (int j = 0; j < 3; j++) {
                    if (j != i) {
                        m.localSymmetryEliminated |= (1 << j);
                    }
                }
                return symType;
            }
        }
        return 0;
    }

    /**
     * Try to eliminate the current symmetry if we can prove it's wrong.
     * FIX: Now takes effectiveSymmetry as parameter instead of reading globalSymmetry.
     *
     * STRICT ELIMINATION: Rats must get VERY close (adjacent or nearly adjacent)
     * to the predicted king location before eliminating. This ensures they travel
     * all the way to corners if needed.
     */
    static void tryEliminateCurrentSymmetry(RobotController rc, Mem m, int effectiveSymmetry, int roundNum)
            throws GameActionException {

        // FIX: Use effectiveSymmetry parameter instead of globalSymmetry
        if (effectiveSymmetry < SYM_ROTATIONAL || effectiveSymmetry > SYM_VERTICAL) return;

        int symIndex = effectiveSymmetry - 1; // Convert to 0-indexed
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) return; // Already eliminated

        MapLocation predicted = m.possibleEnemyKingLocs[symIndex];
        MapLocation here = rc.getLocation();
        int distToPredicted = here.distanceSquaredTo(predicted);

        boolean shouldEliminate = false;
        String reason = "";

        // === CONDITION 1: Predicted location is too close to boundary for 3x3 king ===
        // Check this first since it doesn't require being close
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        // King center must be at least 1 tile from edge (king is 3x3)
        if (predicted.x < 1 || predicted.x >= w - 1 ||
            predicted.y < 1 || predicted.y >= h - 1) {
            shouldEliminate = true;
            reason = "boundary_impossible";
        }

        // === CONDITION 2: Predicted center is blocked by wall ===
        // Can check from distance if we can sense it
        if (!shouldEliminate && rc.canSenseLocation(predicted)) {
            MapInfo info = rc.senseMapInfo(predicted);
            if (info.isWall()) {
                shouldEliminate = true;
                reason = "wall_blocked";
            }
        }

        // === CONDITION 3: STRICT - Must be VERY close to eliminate ===
        // Only eliminate if we're within 2 tiles (distSq <= 8) of the predicted CENTER
        // This ensures rats go ALL THE WAY to corners
        if (!shouldEliminate && distToPredicted <= 8) {
            m.reachedCurrentTarget = true;

            // We're right next to where the king should be - check thoroughly
            boolean foundEnemyKing = false;
            int tilesChecked = 0;

            // Check the entire 3x3 area where king would be
            for (int dx = -1; dx <= 1 && !foundEnemyKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundEnemyKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null &&
                            robot.getType() == UnitType.RAT_KING &&
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundEnemyKing = true;
                        }
                    }
                }
            }

            // Must have checked ALL 9 tiles of the 3x3 area to be sure
            if (!foundEnemyKing && tilesChecked >= 9) {
                shouldEliminate = true;
                reason = "confirmed_empty";
            }
            // If we checked at least 6 tiles and we're adjacent, that's good enough
            else if (!foundEnemyKing && tilesChecked >= 6 && distToPredicted <= 2) {
                shouldEliminate = true;
                reason = "adjacent_empty";
            }
        }

        // === CONDITION 4: We're close (within 4 tiles) and can see the ENTIRE 3x3 area is empty ===
        if (!shouldEliminate && distToPredicted <= 16) {
            boolean foundKing = false;
            int tilesChecked = 0;

            for (int dx = -1; dx <= 1 && !foundKing; dx++) {
                for (int dy = -1; dy <= 1 && !foundKing; dy++) {
                    MapLocation checkLoc = new MapLocation(predicted.x + dx, predicted.y + dy);
                    if (rc.canSenseLocation(checkLoc)) {
                        tilesChecked++;
                        RobotInfo robot = rc.senseRobotAtLocation(checkLoc);
                        if (robot != null &&
                            robot.getType() == UnitType.RAT_KING &&
                            robot.getTeam() == rc.getTeam().opponent()) {
                            foundKing = true;
                        }
                    }
                }
            }

            // Only eliminate if we checked ALL 9 tiles
            if (!foundKing && tilesChecked >= 9) {
                shouldEliminate = true;
                reason = "visual_confirm_all9";
                m.reachedCurrentTarget = true;
            }
        }

        // === CONDITION 5: Timeout - but ONLY if we've actually reached the target ===
        // Much longer timeout, and require being close
        int timeoutThreshold = SYMMETRY_SEARCH_TIMEOUT * 2; // Double the timeout

        if (!shouldEliminate && m.roundsSearchingCurrentSymmetry > timeoutThreshold) {
            // Only timeout-eliminate if we actually got close
            if (m.reachedCurrentTarget && distToPredicted <= 16) {
                shouldEliminate = true;
                reason = "timeout_at_target";
            }
            // Or if we've been searching for a VERY long time and are reasonably close
            else if (m.roundsSearchingCurrentSymmetry > timeoutThreshold * 2 && distToPredicted <= 50) {
                shouldEliminate = true;
                reason = "timeout_extended";
            }
        }

        // === ELIMINATE THIS SYMMETRY ===
        if (shouldEliminate) {
            m.localSymmetryEliminated |= (1 << symIndex);

            // Squeak to notify nearby allies
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN / 2) {
                rc.squeak(SQUEAK_SYMMETRY_ELIMINATED + (symIndex * 10));
                m.lastSqueakRound = roundNum;
            }
        }
    }

    /**
     * Get the current symmetry target location.
     */
    static MapLocation getCurrentSymmetryTarget(Mem m, int effectiveSymmetry) {
        if (m.possibleEnemyKingLocs == null || effectiveSymmetry < SYM_ROTATIONAL) {
            return m.mapCenter;
        }

        int symIndex = effectiveSymmetry - 1;

        // If current symmetry is eliminated, find first non-eliminated
        if ((m.localSymmetryEliminated & (1 << symIndex)) != 0) {
            for (int i = 0; i < 3; i++) {
                if ((m.localSymmetryEliminated & (1 << i)) == 0) {
                    return m.possibleEnemyKingLocs[i];
                }
            }
        }

        return m.possibleEnemyKingLocs[symIndex];
    }

    /**
     * Get the next symmetry to try after current one is eliminated.
     */
    static int getNextSymmetry(int current, int eliminated) {
        // Try symmetries in order: rotational -> horizontal -> vertical
        int[] order = {SYM_ROTATIONAL, SYM_HORIZONTAL, SYM_VERTICAL};

        for (int sym : order) {
            int bit = 1 << (sym - 1);
            if ((eliminated & bit) == 0) {
                return sym;
            }
        }

        // All eliminated? Return rotational as fallback
        return SYM_ROTATIONAL;
    }

    /**
     * Get the next valid (non-eliminated) symmetry to check.
     */
    static int getNextValidSymmetry(int eliminated) {
        // Priority: rotational -> horizontal -> vertical
        if ((eliminated & 1) == 0) return SYM_ROTATIONAL;  // bit 0 = rotational
        if ((eliminated & 2) == 0) return SYM_HORIZONTAL;  // bit 1 = horizontal
        if ((eliminated & 4) == 0) return SYM_VERTICAL;    // bit 2 = vertical
        return SYM_ROTATIONAL; // All eliminated, default
    }

    // ==================== STATE IMPLEMENTATIONS ====================

    static void runSeek(RobotController rc, Mem m, int roundNum, int effectiveSymmetry,
                        RobotInfo[] nearbyAllies) throws GameActionException {
        MapLocation here = rc.getLocation();

        // === OPPORTUNISTIC ATTACK: Hit adjacent enemy rats while seeking ===
        RobotInfo[] adjacentEnemies = rc.senseNearbyRobots(2, rc.getTeam().opponent());
        for (RobotInfo enemy : adjacentEnemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(enemy.getLocation(), spend)) {
                    rc.attack(enemy.getLocation(), spend);
                    break;
                }
            }
        }

        // If we have a swarm target, go there
        if (m.swarmTarget != null && roundNum - m.swarmStartRound < 30) {
            m.state = AttackStateEnum.SWARM;
            runSwarm(rc, m, roundNum);
            return;
        }

        // If we already know enemy king location, go there as a tight cluster
        if (m.targetKingLoc != null) {
            m.currentTarget = m.targetKingLoc;
            formationMoveCluster(rc, m, m.targetKingLoc, roundNum, nearbyAllies);
            return;
        }

        // === CLUSTERED SYMMETRY SEARCH ===
        // FIX: effectiveSymmetry is now passed in, already computed with local knowledge

        // Get target location for this symmetry
        MapLocation symmetryTarget;
        if (m.possibleEnemyKingLocs != null && effectiveSymmetry >= SYM_ROTATIONAL && effectiveSymmetry <= SYM_VERTICAL) {
            symmetryTarget = m.possibleEnemyKingLocs[effectiveSymmetry - 1];
        } else {
            symmetryTarget = m.mapCenter;
        }

        int distToTarget = here.distanceSquaredTo(symmetryTarget);

        // No spread - all attackers converge on the same target as a tight group
        MapLocation moveTarget = symmetryTarget;

        m.currentTarget = moveTarget;

        // Check if we've reached the target area (within 3 tiles)
        if (distToTarget <= 9) {
            m.reachedCurrentTarget = true;
        }

        // Debug: show which symmetry we're actually heading to
        rc.setIndicatorLine(here, symmetryTarget, 255, 255, 0); // Yellow line to target

        // Use tight cluster movement - stay adjacent to allies while advancing
        formationMoveCluster(rc, m, moveTarget, roundNum, nearbyAllies);
    }

    static void runAttack(RobotController rc, Mem m, int roundNum) throws GameActionException {
        if (m.targetKingLoc == null) {
            m.state = AttackStateEnum.SEEK;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation center = m.targetKingLoc;

        // === COUNT NEARBY ALLIES FOR GANG-UP ===
        int alliesNearby = countAlliesInRadius(rc, COORDINATED_ATTACK_RADIUS);
        boolean shouldGangUp = alliesNearby >= GANG_UP_THRESHOLD;

        // === TRY TO BITE ENEMY KING ===
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = new MapLocation(center.x + dx, center.y + dy);
                int spend = shouldGangUp ?
                    Math.min(BITE_CHEESE_BOOST * 2, rc.getRawCheese() + rc.getGlobalCheese() / 10) :
                    Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(tile, spend)) {
                    rc.attack(tile, spend);
                    return;
                }
            }
        }

        // === ALSO ATTACK ENEMY BABY RATS ===
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(enemy.getLocation(), spend)) {
                    rc.attack(enemy.getLocation(), spend);
                    return;
                }
            }
        }

        // === PLACE OFFENSIVE TRAP ===
        if (rc.getActionCooldownTurns() < 10 &&
            roundNum - m.lastTrapRound >= OFFENSIVE_TRAP_COOLDOWN &&
            rc.getGlobalCheese() >= TRAP_CHEESE_THRESHOLD) {
            if (tryPlaceOffensiveTrap(rc, m, center)) {
                m.lastTrapRound = roundNum;
            }
        }

        // === MOVE TOWARD TARGET AS TIGHT CLUSTER ===
        m.currentTarget = m.targetKingLoc;
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(CLUSTER_SENSE_RADIUS_SQ, rc.getTeam());
        formationMoveCluster(rc, m, m.targetKingLoc, roundNum, nearbyAllies);
    }

    static void runGuard(RobotController rc, Mem m, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Find and attack nearby enemy rats
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        RobotInfo closestEnemy = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.BABY_RAT) {
                int dist = here.distanceSquaredTo(enemy.getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestEnemy = enemy;
                }
            }
        }

        if (closestEnemy != null) {
            // Place defensive trap
            if (m.kingLocation != null &&
                rc.getGlobalCheese() >= RAT_TRAP_COST + 50 &&
                roundNum - m.lastTrapRound >= 10) {

                Direction toKing = closestEnemy.getLocation().directionTo(m.kingLocation);
                MapLocation trapLoc = closestEnemy.getLocation().add(toKing);

                if (here.isAdjacentTo(trapLoc) && rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    m.lastTrapRound = roundNum;
                }
            }

            // Attack the enemy
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
                return;
            }

            m.currentTarget = closestEnemy.getLocation();
            attackerMoveToward(rc, m, closestEnemy.getLocation());
            return;
        }

        // No enemies - return to king or resume seeking
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            m.currentTarget = m.kingLocation;
            attackerMoveToward(rc, m, m.kingLocation);
        } else {
            m.state = AttackStateEnum.SEEK;
        }
    }

    static void runSwarm(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Check if local swarm target has expired
        if (m.localSwarmTarget != null && roundNum > m.localSwarmUntilRound) {
            m.localSwarmTarget = null;
        }

        // Prefer local swarm target (from MSG_HELP) if still active, else global alert rally
        MapLocation effectiveTarget = m.localSwarmTarget != null ? m.localSwarmTarget :
                                      (m.alertRallyLoc != null ? m.alertRallyLoc : m.swarmTarget);

        if (effectiveTarget == null || roundNum - m.swarmStartRound > 30) {
            m.state = AttackStateEnum.SEEK;
            m.swarmTarget = null;
            m.localSwarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        // === SINGLE SENSE CALL: Count allies vs enemies + check for enemy king ===
        RobotInfo[] nearby = rc.senseNearbyRobots(SWARM_ENGAGE_RADIUS_SQ);
        int allyCount = 0;
        int enemyCount = 0;
        RobotInfo closestEnemy = null;
        RobotInfo enemyKing = null;
        int closestEnemyDistSq = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getTeam() == enemyTeam) {
                if (r.getType() == UnitType.RAT_KING) {
                    enemyKing = r;  // Priority target
                } else if (r.getType() == UnitType.BABY_RAT) {
                    enemyCount++;
                    int d = here.distanceSquaredTo(r.getLocation());
                    if (d < closestEnemyDistSq) {
                        closestEnemyDistSq = d;
                        closestEnemy = r;
                    }
                }
            } else if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) {
                allyCount++;
            }
        }

        // Check for enemy king (always priority target)
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.state = AttackStateEnum.ATTACK;
            m.turnsSinceKingSeen = 0;
            confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
            return;
        }

        // If arrived at target area and no king found after a while, go back to seek
        int distToTarget = here.distanceSquaredTo(effectiveTarget);
        if (distToTarget <= 16 && roundNum - m.swarmStartRound > 15) {
            m.state = AttackStateEnum.SEEK;
            m.swarmTarget = null;
            m.localSwarmTarget = null;
            return;
        }

        // === ENGAGEMENT DECISION: Only fight if we have numbers ===
        boolean haveAdvantage = (allyCount >= enemyCount + 1) || enemyCount == 0;

        if (haveAdvantage && closestEnemy != null && closestEnemyDistSq <= 8) {
            // We have advantage - engage aggressively
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
            }
        }
        // If outnumbered and enemy is close, don't attack (conserve position)

        // === MOVEMENT: Spread toward rally, but kite if outnumbered ===
        Direction offsetDir = SWARM_OFFSETS[rc.getID() % 8];

        MapLocation spreadTarget;
        if (distToTarget > 25) {
            // Far away: converge directly
            spreadTarget = effectiveTarget;
        } else if (distToTarget > 4) {
            // Medium range: apply 1-tile offset
            spreadTarget = effectiveTarget.add(offsetDir);
        } else {
            // Very close: apply 2-tile offset
            spreadTarget = effectiveTarget.add(offsetDir).add(offsetDir);
        }

        // Clamp to map bounds
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();
        spreadTarget = new MapLocation(
            Math.max(1, Math.min(mapW - 2, spreadTarget.x)),
            Math.max(1, Math.min(mapH - 2, spreadTarget.y))
        );

        // If outnumbered and enemy is adjacent, kite away while drifting to rally
        if (!haveAdvantage && closestEnemy != null && closestEnemyDistSq <= 2) {
            // Kite: move away from enemy but bias toward rally
            Direction awayFromEnemy = closestEnemy.getLocation().directionTo(here);
            Direction toRally = here.directionTo(effectiveTarget);

            // Try kite directions: away, then angled toward rally
            Direction[] kiteDirs = {
                awayFromEnemy,
                awayFromEnemy.rotateLeft(),
                awayFromEnemy.rotateRight(),
                toRally,
                toRally.rotateLeft(),
                toRally.rotateRight()
            };

            for (Direction d : kiteDirs) {
                if (d == Direction.CENTER) continue;
                MapLocation next = here.add(d);
                // Don't kite into more enemies
                if (rc.canSenseLocation(next)) {
                    RobotInfo atNext = rc.senseRobotAtLocation(next);
                    if (atNext != null) continue;
                }
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.currentTarget = spreadTarget;
                    return;
                }
            }
            // Couldn't kite, hold position
            m.currentTarget = spreadTarget;
            return;
        }

        m.currentTarget = spreadTarget;
        attackerMoveToward(rc, m, spreadTarget);
    }

    // ==================== TRAP PLACEMENT ====================

    static boolean tryPlaceOffensiveTrap(RobotController rc, Mem m, MapLocation enemyKingCenter)
            throws GameActionException {
        MapLocation here = rc.getLocation();

        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        // Predict retreat direction
        MapLocation enemySpawn = m.possibleEnemyKingLocs != null ?
            m.possibleEnemyKingLocs[0] : new MapLocation(mapW / 2, mapH / 2);

        Direction retreatDir = enemyKingCenter.directionTo(enemySpawn);

        Direction[] trapDirs = {
            retreatDir,
            retreatDir.rotateLeft(),
            retreatDir.rotateRight(),
            retreatDir.rotateLeft().rotateLeft(),
            retreatDir.rotateRight().rotateRight()
        };

        for (Direction dir : trapDirs) {
            for (int dist = 1; dist <= 2; dist++) {
                MapLocation trapLoc = enemyKingCenter;
                for (int i = 0; i < dist; i++) {
                    trapLoc = trapLoc.add(dir);
                }

                if (here.isAdjacentTo(trapLoc) && rc.canPlaceRatTrap(trapLoc)) {
                    rc.placeRatTrap(trapLoc);
                    return true;
                }
            }
        }

        Direction toEnemy = here.directionTo(enemyKingCenter);
        MapLocation fallbackLoc = here.add(toEnemy);
        if (rc.canPlaceRatTrap(fallbackLoc)) {
            rc.placeRatTrap(fallbackLoc);
            return true;
        }

        return false;
    }
}
