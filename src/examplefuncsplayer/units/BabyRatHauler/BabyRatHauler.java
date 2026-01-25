package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.RatState;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatLevel;

import static examplefuncsplayer.units.BabyRatHauler.HaulerMemory.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerMining.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerThreat.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerMovement.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerNavigation.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerSensing.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerScouting.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerDefense.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerCombat.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerState.*;

/**
 * HAULER baby rat: cheese collection FSM + cat-aware safety + enemy rat awareness + early deposit.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 *
 * Mine sharing system - rats report discovered mines to king via squeak,
 * king broadcasts to shared array, rats distribute across known mines.
 */
public class BabyRatHauler {

    /** Call this once per turn for hauler rats. */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc.getID());

        // init map center / exploration dir
        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            m.explorationDirection = DIRECTIONS[rc.getID() % 8];
        }

        // read king location from shared
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) m.kingLocation = new MapLocation(kx, ky);

        // ===== PROCESS INCOMING SQUEAKS (for combat coordination) =====
        int roundNum = rc.getRoundNum();
        HaulerCombat.processIncomingSqueaks(rc, m, roundNum);

        // track recent positions
        updateRecentLocations(m, rc.getLocation());

        // ===== UPDATE ASSIGNED MINE PERIODICALLY =====
        int mineCount = rc.readSharedArray(MINE_COUNT_SLOT);
        int refreshPeriod = (mineCount < 2) ? 10 : 40;
        if (rc.getRoundNum() - m.assignedMineCheckRound > refreshPeriod) {
            m.assignedMine = getAssignedMine(rc, m);
            m.assignedMineCheckRound = rc.getRoundNum();
        }

        // ===== CONTINUE PENDING MINE REPORT (2-squeak protocol) =====
        HaulerScouting.continueMineReport(rc, m);

        // ===== SCOUTING CHECK: designated scouts explore for new mines =====
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        HaulerSensing.scanAndCacheMines(rc, m, nearby);

        if (HaulerScouting.shouldBeScout(rc, m)) {
            // This rat is a designated scout - explore for mines
            HaulerScouting.doScoutTurn(rc, m, nearby);
            if (DEBUG) rc.setIndicatorString("SCOUT | Mines:" + m.knownMineCount + " | Target:" + (m.scoutTarget != null ? m.scoutTarget.toString() : "none"));
            return;
        }

        // ===== COMPUTE THREAT (CATS + ENEMY RATS + KING DANGER) ONCE PER TURN =====
        ThreatInfo threat = computeDefenseThreat(rc, m);

        // traffic control after delivery - quick exit then go to mine
        if (m.moveAwayCounter > 0) {
            // Even during traffic control, respond to threats appropriately
            if (threat.level == ThreatLevel.KING_DANGER && shouldDefend(rc, m, threat)) {
                // King in danger - switch to defense immediately!
                m.currentState = RatState.DEFEND;
                m.isDefending = true;
                m.moveAwayCounter = 0;
            } else if (threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) {
                // Cat or immediate danger - flee
                m.currentState = RatState.FLEE;
                m.moveAwayCounter = 0;
            } else {
                // Attempt to report mine before moving away from king
                attemptMineReport(rc, m);
                moveAwayFromKing(rc, m);
                m.moveAwayCounter--;
                if (m.moveAwayCounter == 0) {
                    // After traffic control, go directly to assigned mine if we have one
                    m.stuckCounter = 0;
                    m.turnsAtTarget = 0;
                    m.currentState = RatState.EXPLORE;  // EXPLORE will immediately go to assigned mine
                }
            }
            if (DEBUG) rc.setIndicatorString("HAULER TRAFFIC | " + m.currentState + " | Threat:" + threat.level);
            return;
        }

        // ===== SENSE NEARBY ENEMIES FOR COMBAT DECISIONS =====
        MapLocation here = rc.getLocation();
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(COMBAT_DISENGAGE_RADIUS_SQ);
        Team enemyTeam = rc.getTeam().opponent();

        // Separate enemies and track cat
        RobotInfo[] nearbyEnemies = new RobotInfo[nearbyRobots.length];
        int enemyCount = 0;
        int nearestCatDistSq = Integer.MAX_VALUE;
        MapLocation nearestCatLoc = null;

        for (RobotInfo r : nearbyRobots) {
            if (r.getType() == UnitType.CAT) {
                int distSq = here.distanceSquaredTo(r.getLocation());
                if (distSq < nearestCatDistSq) {
                    nearestCatDistSq = distSq;
                    nearestCatLoc = r.getLocation();
                }
            } else if (r.getTeam() == enemyTeam && r.getType() == UnitType.BABY_RAT) {
                nearbyEnemies[enemyCount++] = r;
            }
        }

        // Update cat memory
        if (nearestCatLoc != null) {
            m.lastCatLoc = nearestCatLoc;
            m.lastCatSeenRound = roundNum;
            m.turnCatLoc = nearestCatLoc;
            if (nearestCatDistSq <= CAT_PANIC_DIST_SQ) {
                m.turnCatBand = CAT_BAND_PANIC;
            } else if (nearestCatDistSq <= CAT_DANGER_DIST_SQ) {
                m.turnCatBand = CAT_BAND_DANGER;
            } else if (nearestCatDistSq <= CAT_CAUTION_DIST_SQ) {
                m.turnCatBand = CAT_BAND_CAUTION;
            } else {
                m.turnCatBand = CAT_BAND_NONE;
            }
        } else {
            m.turnCatLoc = null;
            m.turnCatBand = CAT_BAND_NONE;
        }

        // Trim enemies array
        RobotInfo[] trimmedEnemies = new RobotInfo[enemyCount];
        System.arraycopy(nearbyEnemies, 0, trimmedEnemies, 0, enemyCount);

        // ===== SAFE CAT-SPOT SQUEAK =====
        if (nearestCatLoc != null && nearestCatDistSq > CAT_HEARING_DIST_SQ) {
            HaulerCombat.maybeSqueakCatSpotted(rc, m, roundNum, nearestCatDistSq, nearestCatLoc);
        }

        // ===== FSM TRANSITIONS BASED ON THREAT =====
        // Priority 0: CAT PANIC - always flee from nearby cat (highest priority)
        if (m.turnCatBand == CAT_BAND_PANIC) {
            if (m.currentState != RatState.FLEE) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            }
        }
        // Priority 1: KING_DANGER triggers DEFEND mode
        else if (threat.level == ThreatLevel.KING_DANGER && shouldDefend(rc, m, threat)) {
            if (m.currentState != RatState.DEFEND && m.currentState != RatState.COMBAT) {
                m.previousState = m.currentState;
                m.currentState = RatState.DEFEND;
                m.isDefending = true;
            }
        }
        // Priority 2: COMBAT mode - engage enemy rats aggressively
        else if (m.currentState != RatState.RETURN && m.currentState != RatState.DELIVER) {
            // Check if we should enter combat (enemy nearby with allies to help)
            if (m.currentState != RatState.COMBAT && m.currentState != RatState.DEFEND) {
                if (HaulerCombat.shouldEnterCombat(rc, m, trimmedEnemies)) {
                    m.previousState = m.currentState;
                    m.currentState = RatState.COMBAT;
                    // Squeak for help
                    HaulerCombat.maybeSqueakHelp(rc, m, roundNum, nearestCatDistSq);
                }
            }
            // Check if we should exit combat
            else if (m.currentState == RatState.COMBAT) {
                if (HaulerCombat.shouldExitCombat(rc, m, trimmedEnemies)) {
                    m.currentState = m.previousState;
                    m.combatTarget = null;
                }
            }
        }
        // Priority 3: Exit DEFEND when no more enemies near king
        if (m.currentState == RatState.DEFEND && threat.enemiesNearKing == 0 && enemyCount == 0) {
            m.currentState = m.previousState;
            m.isDefending = false;
            m.currentDefenseTarget = null;
        }
        // Priority 4: Other cat threats trigger FLEE (but not if in combat/defending)
        else if (threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) {
            if (m.currentState != RatState.FLEE && m.currentState != RatState.DEFEND && m.currentState != RatState.COMBAT) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            }
        }
        // Priority 5: Exit FLEE when threat clears
        else if (m.currentState == RatState.FLEE && threat.level == ThreatLevel.NONE && m.turnCatBand == CAT_BAND_NONE) {
            m.currentState = m.previousState;
        }

        // execute FSM
        switch (m.currentState) {
            case EXPLORE: runExplore(rc, m, turnCount, threat); break;
            case COLLECT: runCollect(rc, m, threat); break;
            case RETURN:  runReturn(rc, m, threat); break;
            case DELIVER: runDeliver(rc, m, threat); break;
            case FLEE:    runFlee(rc, m, threat); break;
            case DEFEND:  runDefend(rc, m, threat); break;
            case COMBAT:  HaulerCombat.runCombat(rc, m, roundNum); break;
        }

        if (DEBUG) {
            String stateStr = m.currentState.toString();
            if (m.currentState == RatState.DEFEND) {
                stateStr += " E:" + threat.enemiesNearKing;
            } else if (m.currentState == RatState.COMBAT) {
                stateStr += " A:" + m.alliesNearby + " T:" + (m.combatTarget != null ? m.combatTarget.toString() : "none");
            }
            String catStr = m.turnCatBand > 0 ? " Cat:" + m.turnCatBand : "";
            rc.setIndicatorString("HAULER " + stateStr + catStr + " | Raw:" + rc.getRawCheese() + " | Mine:" + (m.assignedMine != null ? m.assignedMine.toString() : "none"));
        }
    }

    // ===== FSM STATES =====

    static void runExplore(RobotController rc, Mem m, int turnCount, ThreatInfo threat) throws GameActionException {
        // if already carrying, return
        if (rc.getRawCheese() > 0) {
            m.currentState = RatState.RETURN;
            m.stuckCounter = 0;
            m.turnsAtTarget = 0;
            return;
        }

        MapLocation here = rc.getLocation();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // Check for visible cheese first (highest priority)
        MapLocation nearestCheese = findNearestCheese(rc, nearby);
        if (nearestCheese != null) {
            m.currentState = RatState.COLLECT;
            m.stuckCounter = 0;
            m.turnsAtTarget = 0;
            return;
        }

        // Check for visible mine and remember it
        MapLocation nearestMine = findNearestCheeseMine(rc, nearby);
        if (nearestMine != null) {
            m.lastSeenMine = nearestMine;  // Remember for reporting to king
        }

        // AGGRESSIVE MINE ASSIGNMENT: If we don't have an assigned mine, try to get one every turn
        if (m.assignedMine == null) {
            m.assignedMine = getAssignedMine(rc, m);
            m.assignedMineCheckRound = rc.getRoundNum();
        }

        // Enhanced stuck detection
        if (isStuckInLoop(m)) {
            m.stuckCounter++;
            if (m.lastStuckLocation == null || !m.lastStuckLocation.equals(here)) {
                m.lastStuckLocation = here;
            }
        } else {
            m.stuckCounter = 0;
        }

        // When slightly stuck, rotate in place to scan with 90° cone before unstuck maneuver
        if (m.consecutiveFailedMoves >= 2 && m.consecutiveFailedMoves < 3) {
            Direction cur = rc.getDirection();
            Direction rotated = cur.rotateRight().rotateRight(); // 90° rotation
            if (rc.canTurn(rotated)) {
                rc.turn(rotated);
            }
            // Don't return - let it continue to check for cheese/mines after rotating
        }

        // Trigger unstuck earlier and more aggressively
        if (m.stuckCounter >= STUCK_THRESHOLD || m.consecutiveFailedMoves >= 3) {
            performUnstuckManeuver(rc, m);
            m.explorationTarget = null;
            m.turnsAtTarget = 0;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        m.turnsAtTarget++;

        // Scout system: only very few rats should scout, and only early game
        int mineCount = rc.readSharedArray(MINE_COUNT_SLOT);
        boolean shouldScout = m.isScout && mineCount < 2 && rc.getRoundNum() < 200;

        MapLocation target;

        // Priority 1: Visible mine nearby - go directly to it
        if (nearestMine != null) {
            target = nearestMine;
            m.turnsAtTarget = 0;
        }
        // Priority 2: ASSIGNED MINE - always go here if we have one (unless scouting)
        else if (m.assignedMine != null && !shouldScout) {
            target = m.assignedMine;
            // If we're at the mine area but don't see cheese, search around it
            if (here.distanceSquaredTo(m.assignedMine) <= 25) {
                // Rotate to scan when no cheese visible
                if (m.turnsAtTarget % 3 == 0 && m.turnsAtTarget > 0) {
                    Direction cur = rc.getDirection();
                    Direction rotated = cur.rotateRight().rotateRight();
                    if (rc.canTurn(rotated)) rc.turn(rotated);
                }
                // After waiting at mine, explore locally
                if (m.turnsAtTarget > 8) {
                    m.explorationTarget = pickExplorationNearMine(rc, m, m.assignedMine);
                    target = m.explorationTarget;
                    m.turnsAtTarget = 0;
                }
            }
        }
        // Priority 3: Scout symmetry speculation (only for designated scouts with few mines)
        else if (shouldScout && m.lastSeenMine != null) {
            // Compute speculated target if not set
            if (m.speculatedMineTarget == null) {
                int w = rc.getMapWidth();
                int h = rc.getMapHeight();
                int mx = m.lastSeenMine.x;
                int my = m.lastSeenMine.y;
                int sym = rc.getID() % 3;
                int sx, sy;
                if (sym == 0) { sx = w - 1 - mx; sy = h - 1 - my; }       // rot
                else if (sym == 1) { sx = w - 1 - mx; sy = my; }          // hor
                else { sx = mx; sy = h - 1 - my; }                        // ver
                sx = Math.max(0, Math.min(w - 1, sx));
                sy = Math.max(0, Math.min(h - 1, sy));
                m.speculatedMineTarget = new MapLocation(sx, sy);
            }
            target = m.speculatedMineTarget;
            // If near speculated target and no mine found, clear and fall back
            if (here.distanceSquaredTo(m.speculatedMineTarget) <= 9 && nearestMine == null) {
                m.speculatedMineTarget = null;
                m.explorationTarget = pickNewExplorationTarget(rc, m);
                target = m.explorationTarget;
                m.turnsAtTarget = 0;
            }
        }
        // Priority 4: Early game - go to center briefly (only first 40 turns)
        else if (!m.hasReachedCenter && m.mapCenter != null && turnCount < 40) {
            target = m.mapCenter;
            if (here.distanceSquaredTo(m.mapCenter) <= 35 || m.turnsAtTarget > 15) {
                m.hasReachedCenter = true;
                m.turnsAtTarget = 0;
            }
        }
        // Priority 5: No mine assigned - explore to find one
        else {
            if (m.explorationTarget == null ||
                    here.distanceSquaredTo(m.explorationTarget) <= 4 ||
                    m.turnsAtTarget > MAX_TARGET_PURSUIT_TURNS) {
                m.explorationTarget = pickNewExplorationTarget(rc, m);
                m.turnsAtTarget = 0;
            }
            target = m.explorationTarget;
        }

        if (target != null) {
            boolean moved = intelligentMoveToward(rc, m, target, nearby, threat);
            if (!moved) m.consecutiveFailedMoves++;
            else m.consecutiveFailedMoves = 0;
        } else {
            exploreInDirection(rc, m.explorationDirection);
        }
    }

    static void runCollect(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        int raw = rc.getRawCheese();

        // Dynamic threshold based on distance to king
        int earlyReturnThreshold = calculateEarlyReturnThreshold(rc, m);

        if (raw >= earlyReturnThreshold) {
            m.currentState = RatState.RETURN;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // Track any mines we see
        MapLocation nearestMine = findNearestCheeseMine(rc, nearby);
        if (nearestMine != null) {
            m.lastSeenMine = nearestMine;
        }

        MapLocation nearestCheese = findNearestCheese(rc, nearby);

        if (nearestCheese == null) {
            if (raw > 0) {
                // Have some cheese but no more visible, return it
                m.currentState = RatState.RETURN;
            } else {
                m.currentState = RatState.EXPLORE;
            }
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        if (rc.canPickUpCheese(nearestCheese)) rc.pickUpCheese(nearestCheese);
        boolean moved = intelligentMoveToward(rc, m, nearestCheese, nearby, threat);
        if (!moved) m.consecutiveFailedMoves++;
        else m.consecutiveFailedMoves = 0;
    }

    /**
     * Calculate how much cheese to collect before returning.
     * Keep this low so cheese returns frequently to prevent king starvation.
     */
    static int calculateEarlyReturnThreshold(RobotController rc, Mem m) {
        if (m.kingLocation == null) return 5;

        MapLocation here = rc.getLocation();
        int distSq = here.distanceSquaredTo(m.kingLocation);

        // Approximate travel distance
        int dist = (int) Math.sqrt(distSq);

        // Base: 5 cheese minimum, max 15
        // This ensures frequent returns to keep king fed
        int threshold = Math.min(15, 5 + (dist / 8));

        return threshold;
    }

    static void runReturn(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        if (m.kingLocation == null) {
            m.currentState = RatState.EXPLORE;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        int raw = rc.getRawCheese();
        if (raw == 0) {
            m.currentState = RatState.EXPLORE;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        // Safety check: don't walk into danger (cats OR enemy rats)
        if (threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) {
            m.currentState = RatState.FLEE;
            return;
        }

        if (rc.canTransferCheese(m.kingLocation, raw)) {
            m.currentState = RatState.DELIVER;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        MapInfo[] nearby = rc.senseNearbyMapInfos();

        // Pick up nearby cheese on the way back (opportunistic collection)
        MapLocation here = rc.getLocation();
        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                MapLocation cheeseLoc = info.getMapLocation();
                // Only pick up if it's close and roughly on our path to king
                int cheeseToKing = cheeseLoc.distanceSquaredTo(m.kingLocation);
                int hereToKing = here.distanceSquaredTo(m.kingLocation);
                if (here.distanceSquaredTo(cheeseLoc) <= 2 && cheeseToKing <= hereToKing + 4) {
                    if (rc.canPickUpCheese(cheeseLoc)) {
                        rc.pickUpCheese(cheeseLoc);
                        break;
                    }
                }
            }
        }

        boolean moved = intelligentMoveToward(rc, m, m.kingLocation, nearby, threat);
        if (!moved) m.consecutiveFailedMoves++;
        else m.consecutiveFailedMoves = 0;
    }

    static void runDeliver(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        if (m.kingLocation == null) {
            m.currentState = RatState.EXPLORE;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        int raw = rc.getRawCheese();
        if (raw == 0) {
            m.currentState = RatState.EXPLORE;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        // Safety check: don't walk into danger (cats OR enemy rats)
        if (threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) {
            m.currentState = RatState.FLEE;
            return;
        }

        if (rc.canTransferCheese(m.kingLocation, raw)) {
            rc.transferCheese(m.kingLocation, raw);

            // ===== 2-squeak mine report protocol =====
            // Set up pending mine report; will be sent during traffic control
            if (m.lastSeenMine != null) {
                m.pendingMineReport = m.lastSeenMine;
                m.pendingMineStage = 0;
            }

            m.moveAwayCounter = 2;  // Quick exit, then go back to mining
            m.consecutiveFailedMoves = 0;
            return;
        }

        MapInfo[] nearby = rc.senseNearbyMapInfos();
        boolean moved = intelligentMoveToward(rc, m, m.kingLocation, nearby, threat);
        if (!moved) m.consecutiveFailedMoves++;
        else m.consecutiveFailedMoves = 0;
    }

    static void runFlee(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        // Flee from the threat source (cat or enemy rat)
        if (threat.level == ThreatLevel.NONE || threat.source == null) {
            m.currentState = m.previousState;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation threatLoc = threat.source;

        // ===== PARTING SHOT TRAP =====
        // Before fleeing, drop a trap where the enemy will chase into
        // Only do this against enemy rats (not cats) and if we have cheese
        if (threat.level != ThreatLevel.PANIC && rc.getActionCooldownTurns() < 10) {
            // Check if threat is an enemy rat (not a cat)
            if (rc.canSenseLocation(threatLoc)) {
                RobotInfo threatRobot = rc.senseRobotAtLocation(threatLoc);
                if (threatRobot != null && threatRobot.getType() == UnitType.BABY_RAT) {
                    tryPartingShotTrap(rc, m, threatLoc);
                }
            }
        }

        // Perpendicular escape: prefer moving sideways to avoid predictable retreat
        Direction away = threatLoc.directionTo(here);
        if (away == Direction.CENTER) away = Direction.NORTH;

        Direction left = away.rotateLeft();
        Direction right = away.rotateRight();
        Direction leftLeft = left.rotateLeft();
        Direction rightRight = right.rotateRight();

        // Priority: left, right, away, leftLeft, rightRight
        Direction[] candidates = { left, right, away, leftLeft, rightRight };

        boolean moved = false;
        for (Direction dir : candidates) {
            if (tryMoveInDirection(rc, dir)) {
                moved = true;
                break;
            }
        }

        // Fallback: if no good move, try random directions
        if (!moved) {
            Direction fallback = DIRECTIONS[m.rng.nextInt(8)];
            tryMoveInDirection(rc, fallback);
        }
    }

    /**
     * DEFEND state: Protect the king from enemy rats.
     *
     * Combat priorities:
     * 1. Attack FOCUS TARGET first (king-designated, coordinated attack)
     * 2. Attack LOW HP enemies (quick kills)
     * 3. Place traps between enemies and king
     * 4. Squeak for backup when outnumbered
     * 5. Use cluster movement for coordinated attacks
     */
    static void runDefend(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        MapLocation here = rc.getLocation();
        int roundNum = rc.getRoundNum();

        // If no enemies near king anymore, exit defend
        if (threat.enemiesNearKing == 0) {
            m.currentState = m.previousState;
            m.isDefending = false;
            m.currentDefenseTarget = null;
            return;
        }

        // ===== 1. SELECT TARGET (Focus fire aware) =====
        MapLocation target = selectDefenseTarget(rc, m, threat);
        boolean isFocusTarget = threat.focusTarget != null && target != null && target.equals(threat.focusTarget);

        // ===== 2. SQUEAK FOR BACKUP & FOCUS FIRE =====
        int alliesNearby = HaulerDefense.countNearbyAllies(rc, HAULER_DEFEND_RADIUS);
        m.alliesNearby = alliesNearby;

        // Squeak for backup if outnumbered
        if (threat.enemiesNearKing > alliesNearby && m.kingLocation != null) {
            squeakForDefense(rc, m);
        }
        // Also squeak to coordinate focus fire on low HP targets
        else if (target != null && threat.priorityTargetHP > 0 && threat.priorityTargetHP <= LOW_HP_KILL_THRESHOLD) {
            squeakFocusFire(rc, m, target);
        }

        // ===== 3. TRY TO ATTACK (with focus fire boost) =====
        boolean attacked = false;
        if (target != null) {
            int distToTargetSq = here.distanceSquaredTo(target);

            // If adjacent (dist² <= 2), attack with focus fire boost if applicable
            if (distToTargetSq <= 2) {
                // Check if we have gang-up advantage for extra damage
                boolean gangUp = alliesNearby >= HaulerState.GANG_UP_THRESHOLD;
                int cheeseBoost = isFocusTarget ? FOCUS_FIRE_BITE_BOOST : HAULER_DEFENSE_BITE_BOOST;
                if (gangUp) cheeseBoost += 15; // Extra cheese when ganging up

                // Use enhanced attack with calculated boost
                attacked = attackTarget(rc, m, target, isFocusTarget);
            }
        }

        // ===== 3.5 OFFENSIVE TRAP - CUT OFF RETREAT =====
        // Extremely efficient: 50 damage + 2 turn stun for 30 cheese
        if (!attacked && target != null && rc.getActionCooldownTurns() < 10) {
            int distToTarget = here.distanceSquaredTo(target);
            if (distToTarget <= 5) {
                tryOffensiveTrap(rc, m, target);
            }
        }

        // ===== 4. PLACE DEFENSIVE TRAP =====
        if (!attacked && rc.getActionCooldownTurns() < 10) {
            tryPlaceDefensiveTrap(rc, m, threat);
        }

        // ===== 5. MOVE TOWARD TARGET (using cluster movement) =====
        if (target != null) {
            int distToTargetSq = here.distanceSquaredTo(target);

            // If not adjacent, use cluster movement for coordinated approach
            if (distToTargetSq > 2) {
                // Use cluster movement if we have allies nearby
                if (alliesNearby > 0) {
                    HaulerCombat.clusterMoveToward(rc, m, target);
                } else {
                    moveTowardTarget(rc, m, target);
                }
            }
            // If adjacent and attacked, hold position (or rotate to face)
            else if (attacked) {
                Direction toTarget = here.directionTo(target);
                if (rc.getDirection() != toTarget && rc.canTurn(toTarget)) {
                    rc.turn(toTarget);
                }
            }
        }
        // No target but still defending - patrol near king
        else if (m.kingLocation != null) {
            int distToKingSq = here.distanceSquaredTo(m.kingLocation);

            // Stay close to king
            if (distToKingSq > 25) {
                moveTowardTarget(rc, m, m.kingLocation);
            }
            // Rotate to scan for threats
            else {
                Direction cur = rc.getDirection();
                Direction rotated = cur.rotateRight().rotateRight();
                if (rc.canTurn(rotated)) {
                    rc.turn(rotated);
                }
            }
        }

        String focusStr = isFocusTarget ? " [FOCUS]" : "";
        String gangStr = alliesNearby >= HaulerState.GANG_UP_THRESHOLD ? " [GANG]" : "";
        if (DEBUG) rc.setIndicatorString("DEFEND | Enemies:" + threat.enemiesNearKing + " | Target:" +
                (target != null ? target.toString() : "none") + focusStr + gangStr + " | Allies:" + alliesNearby);
    }
}
