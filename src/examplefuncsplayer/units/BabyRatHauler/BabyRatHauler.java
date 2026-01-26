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
import static examplefuncsplayer.units.BabyRatHauler.HaulerMovement.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerNavigation.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerSensing.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerScouting.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerDefense.*;

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

        // ===== HANDLE CARRYING STATE (from ratnapping) =====
        // If we're carrying an enemy, handle that before any FSM logic
        if (rc.getCarrying() != null) {
            boolean isCatPanic = false;

            // Check if we're in cat panic specifically (not just enemy rat panic)
            if (threat.level == ThreatLevel.PANIC && threat.source != null) {
                if (rc.canSenseLocation(threat.source)) {
                    RobotInfo threatRobot = rc.senseRobotAtLocation(threat.source);
                    if (threatRobot != null && threatRobot.getType() == UnitType.CAT) {
                        isCatPanic = true;
                        // Drop enemy and flee from cat
                        Direction away = threat.source.directionTo(rc.getLocation());
                        if (rc.canDropRat(away)) {
                            rc.dropRat(away);
                        }
                        clearRatnapState(m);
                        m.currentState = RatState.FLEE;
                        // Continue to FSM to handle flee
                    }
                }
            }

            // If NOT cat panic, handle carrying normally (even if in enemy rat panic)
            // We keep carrying because the carried enemy can't hurt us and we can use them!
            if (!isCatPanic && rc.getCarrying() != null) {
                handleCarryingEnemy(rc, m);
                if (DEBUG) rc.setIndicatorString("CARRYING ENEMY | Turns:" + (rc.getRoundNum() - m.carryStartRound));
                return; // Handled carrying, done for this turn
            }
        } else if (m.isCarryingEnemy) {
            // We were carrying but not anymore (auto-dropped or threw)
            clearRatnapState(m);
        }

        // ===== CHECK FOR DEFENSE SQUEAKS FROM ALLIES =====
        // Respond to focus fire coordination - all nearby haulers attack the same target
        MapLocation squeakedTarget = checkDefenseSqueaks(rc, m);
        if (squeakedTarget != null && shouldRespondToDefense(rc, m, squeakedTarget)) {
            // Another rat spotted an enemy near our king - join the defense!
            boolean justEnteredDefend = (m.currentState != RatState.DEFEND);
            if (justEnteredDefend) {
                m.previousState = m.currentState;
                m.currentState = RatState.DEFEND;
                m.isDefending = true;

                // RELAY SQUEAK: Extend the chain to reach more allies!
                // This helps rats further away hear about the threat faster
                squeakEnemyTarget(rc, m, squeakedTarget);
            }
            m.currentDefenseTarget = squeakedTarget;
            // Update threat info with the squeaked target
            if (threat.priorityTarget == null) {
                threat.priorityTarget = squeakedTarget;
            }
        }

        // traffic control after delivery - quick exit then go to mine
        if (m.moveAwayCounter > 0) {
            // Even during traffic control, respond to threats appropriately
            if (threat.level == ThreatLevel.KING_DANGER && shouldDefend(rc, m, threat)) {
                // King in danger - switch to defense immediately!
                m.currentState = RatState.DEFEND;
                m.isDefending = true;
                m.moveAwayCounter = 0;
            } else if ((threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) && threat.threatIsCat) {
                // Cat danger only - flee from cats, NOT enemy rats
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

        // ===== FSM TRANSITIONS BASED ON THREAT =====
        // CRITICAL: We NEVER flee from enemy rats - only from cats!
        // Enemy rats should trigger DEFEND (if near king) or be ignored (continue task)

        // Priority 1: KING_DANGER triggers DEFEND mode (highest priority)
        if (threat.level == ThreatLevel.KING_DANGER && shouldDefend(rc, m, threat)) {
            if (m.currentState != RatState.DEFEND) {
                m.previousState = m.currentState;
                m.currentState = RatState.DEFEND;
                m.isDefending = true;

                // IMMEDIATE SQUEAK: Alert nearby rats to converge on the enemy!
                // This speeds up the squeak chain for faster swarming
                MapLocation alertTarget = threat.priorityTarget != null ? threat.priorityTarget : threat.source;
                if (alertTarget != null) {
                    squeakEnemyTarget(rc, m, alertTarget);
                }
            }
        }
        // Priority 2: Exit DEFEND when no more enemies near king
        else if (m.currentState == RatState.DEFEND && threat.enemiesNearKing == 0) {
            // Only exit if sticky target is also gone
            boolean stickyStillValid = false;
            if (m.stickyTargetId != -1 && m.stickyTargetLastKnownLoc != null) {
                if (rc.canSenseLocation(m.stickyTargetLastKnownLoc)) {
                    RobotInfo robot = rc.senseRobotAtLocation(m.stickyTargetLastKnownLoc);
                    stickyStillValid = (robot != null && robot.getTeam() != rc.getTeam());
                }
            }
            if (!stickyStillValid) {
                m.currentState = m.previousState;
                m.isDefending = false;
                m.currentDefenseTarget = null;
                clearStickyTarget(m);
            }
        }
        // Priority 3: CAT threats trigger FLEE (ONLY cats, NEVER enemy rats)
        // Defenders also flee from cats in panic range
        else if (threat.level == ThreatLevel.PANIC && threat.threatIsCat) {
            // Only flee from cats - this is a cat panic
            if (m.currentState != RatState.FLEE) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            }
        }
        // Priority 4: Other CAT threats (NEAR/BLOCKING) trigger FLEE - but ONLY if cat, not enemy rat
        else if (threat.level != ThreatLevel.NONE && threat.level != ThreatLevel.KING_DANGER && threat.threatIsCat) {
            if (m.currentState != RatState.FLEE && m.currentState != RatState.DEFEND) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            }
        }
        // Priority 5: Exit FLEE when threat clears OR when threat is only enemy rats (not cats)
        else if (m.currentState == RatState.FLEE && (threat.level == ThreatLevel.NONE || !threat.threatIsCat)) {
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
        }

        if (DEBUG) {
            String stateStr = m.currentState.toString();
            if (m.currentState == RatState.DEFEND) {
                stateStr += " E:" + threat.enemiesNearKing;
            }
            rc.setIndicatorString("HAULER " + stateStr + " | Raw:" + rc.getRawCheese() + " | Mine:" + (m.assignedMine != null ? m.assignedMine.toString() : "none"));
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

        // Safety check: only flee from CATS, not enemy rats
        // We fight enemy rats, we don't run from them
        if ((threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) && threat.threatIsCat) {
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

        // Safety check: only flee from CATS, not enemy rats
        // We fight enemy rats, we don't run from them
        if ((threat.level == ThreatLevel.PANIC || threat.level == ThreatLevel.BLOCKING) && threat.threatIsCat) {
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

        // ===== CAT TRAP PLACEMENT =====
        // If fleeing from a cat, place a cat trap in its path to slow it down
        if (rc.getActionCooldownTurns() < 10 && rc.getGlobalCheese() >= 10) {
            if (rc.canSenseLocation(threatLoc)) {
                RobotInfo threatRobot = rc.senseRobotAtLocation(threatLoc);
                if (threatRobot != null && threatRobot.getType() == UnitType.CAT) {
                    tryPlaceCatTrapWhileFleeing(rc, here, threatLoc);
                }
            }
        }

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
     * PRIORITY ORDER:
     * 1. Handle carrying enemy (if we ratnapped someone)
     * 2. Try to RATNAP an enemy (completely disables them!)
     * 3. Attack (with focus fire coordination)
     * 4. Place traps
     * 5. Move to engage
     *
     * SQUEAK CHAIN DEFENSE SYSTEM:
     * - Deterministic target priority: closest to king → lowest HP → lowest robot ID
     * - Sticky targeting: stay on target for N rounds to prevent thrashing
     * - Selective relay: extend squeak chain to reach more allies
     * - All nearby rats converge regardless of distance from king
     */
    static void runDefend(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        MapLocation here = rc.getLocation();

        // ===== PRIORITY 0: HANDLE IF WE'RE CARRYING AN ENEMY =====
        // If we already ratnapped someone, handle that first
        if (rc.getCarrying() != null) {
            handleCarryingEnemy(rc, m);
            if (DEBUG) rc.setIndicatorString("DEFEND CARRYING | Turns:" + (rc.getRoundNum() - m.carryStartRound));
            return;
        } else if (m.isCarryingEnemy) {
            // We were carrying but auto-dropped - clear state
            clearRatnapState(m);
        }

        // If no enemies near king anymore, check if we should exit defense
        if (threat.enemiesNearKing == 0) {
            // Check if our sticky target still exists
            boolean targetStillValid = false;
            if (m.stickyTargetId != -1 && m.stickyTargetLastKnownLoc != null) {
                if (rc.canSenseLocation(m.stickyTargetLastKnownLoc)) {
                    RobotInfo robot = rc.senseRobotAtLocation(m.stickyTargetLastKnownLoc);
                    if (robot != null && robot.getTeam() != rc.getTeam()) {
                        targetStillValid = true;
                    }
                } else {
                    // Can't sense - if we're close, keep chasing briefly
                    int dist = here.distanceSquaredTo(m.stickyTargetLastKnownLoc);
                    targetStillValid = (dist <= 36 && m.stickyTargetRoundsRemaining > 0);
                }
            }

            if (!targetStillValid) {
                // Exit defense mode
                m.currentState = m.previousState;
                m.isDefending = false;
                m.currentDefenseTarget = null;
                clearStickyTarget(m);
                return;
            }
        }

        // ===== 1. SELECT TARGET (DETERMINISTIC FOCUS FIRE with STICKY TARGETING) =====
        // This uses the new priority: closest to king → lowest HP → lowest robot ID
        // Sticky targeting prevents thrashing between targets
        MapLocation target = selectFocusFireTarget(rc, m);

        // Fallback to old method if focus fire found nothing
        if (target == null) {
            target = selectDefenseTarget(rc, m, threat);
        }

        // ===== 2. RELAY SQUEAK (extend chain to reach more allies) =====
        // Only relay if we received a fresh squeak and cooldown allows
        if (target != null) {
            tryRelaySqueak(rc, m, target);
        }

        // ===== 3. SQUEAK FOR BACKUP =====
        int alliesNearby = countNearbyAllies(rc, HAULER_DEFEND_RADIUS);
        if (target != null && !m.receivedFreshSqueak) {
            // If we found target ourselves (not from squeak), broadcast it
            squeakEnemyTarget(rc, m, target);
        }
        // Also squeak for general backup if outnumbered
        if (threat.enemiesNearKing > alliesNearby && m.kingLocation != null) {
            squeakForDefense(rc, m);
        }

        // ===== 4. TRY TO RATNAP (HIGHEST COMBAT PRIORITY!) =====
        // Ratnapping completely disables an enemy for up to 10 turns!
        // This is MORE valuable than a single bite (10-20 dmg vs 10 turns of no enemy actions)
        if (rc.getActionCooldownTurns() < 10) {
            // First check if our target is ratnapable
            if (target != null && here.isAdjacentTo(target)) {
                if (tryDefensiveRatnap(rc, m, target)) {
                    if (DEBUG) rc.setIndicatorString("DEFEND RATNAPPED TARGET!");
                    return; // Successfully ratnapped, will handle carrying next turn
                }
            }

            // Also check for other ratnapable enemies nearby (e.g., flankers facing away)
            MapLocation ratnapTarget = findRatnapTarget(rc, m);
            if (ratnapTarget != null && !ratnapTarget.equals(target)) {
                if (tryDefensiveRatnap(rc, m, ratnapTarget)) {
                    if (DEBUG) rc.setIndicatorString("DEFEND RATNAPPED FLANKER!");
                    return;
                }
            }
        }

        // ===== 5. TRY TO ATTACK =====
        boolean attacked = false;
        if (target != null) {
            int distToTargetSq = here.distanceSquaredTo(target);

            // If adjacent (dist² <= 2), attack
            if (distToTargetSq <= 2) {
                attacked = attackTarget(rc, m, target);
            }
        }

        // ===== 6. OFFENSIVE TRAP - CUT OFF RETREAT =====
        // Traps deal 50 damage + 2 turn stun for only 30 cheese
        // Trap + 2 bites = 70 damage = dead rat
        if (!attacked && target != null && rc.getActionCooldownTurns() < 10) {
            int distToTarget = here.distanceSquaredTo(target);
            if (distToTarget <= 5) {
                tryOffensiveTrap(rc, m, target);
            }
        }

        // ===== 7. PLACE DEFENSIVE TRAP =====
        if (!attacked && rc.getActionCooldownTurns() < 10) {
            tryPlaceDefensiveTrap(rc, m, threat);
        }

        // ===== 8. MOVE TOWARD TARGET OR PATROL NEAR KING =====
        if (target != null) {
            int distToTargetSq = here.distanceSquaredTo(target);

            // If not adjacent, move toward target
            if (distToTargetSq > 2) {
                moveTowardTarget(rc, m, target);
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

            // Move toward king if far
            if (distToKingSq > 36) {
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

        // Debug indicator
        String stickyStr = m.stickyTargetId != -1 ? "S:" + m.stickyTargetRoundsRemaining : "-";
        if (DEBUG) rc.setIndicatorString("DEFEND | E:" + threat.enemiesNearKing + " | " + stickyStr +
                " | Tgt:" + (target != null ? target.toString() : "none") + " | A:" + alliesNearby);
    }

    /**
     * Try to place a cat trap while fleeing from a cat.
     * Places the trap in the cat's path (between us and the cat).
     * Cat traps cost 10 cheese and deal 100 damage + 2 turn stun.
     */
    static boolean tryPlaceCatTrapWhileFleeing(RobotController rc, MapLocation here, MapLocation catLoc)
            throws GameActionException {
        Direction toCat = here.directionTo(catLoc);
        if (toCat == Direction.CENTER) return false;

        // Try to place trap in the cat's approach path
        // Priority: directly toward cat, then adjacent directions
        Direction[] trapDirs = {
            toCat,
            toCat.rotateLeft(),
            toCat.rotateRight()
        };

        for (Direction dir : trapDirs) {
            MapLocation trapLoc = here.add(dir);
            if (rc.canPlaceCatTrap(trapLoc)) {
                rc.placeCatTrap(trapLoc);
                return true;
            }
        }

        return false;
    }
}
