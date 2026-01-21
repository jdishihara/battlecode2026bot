package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;
import examplefuncsplayer.units.HaulerDefender.DefenderState.RatState;
import examplefuncsplayer.units.HaulerDefender.DefenderState.ThreatInfo;
import examplefuncsplayer.units.HaulerDefender.DefenderState.ThreatLevel;

import static examplefuncsplayer.units.HaulerDefender.DefenderMemory.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderMining.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderThreat.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderMovement.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderNavigation.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderSensing.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderScouting.*;

/**
 * HAULER-DEFENDER baby rat: cheese collection FSM (copied from defaultbot) + king defense.
 *
 * Cheese retrieval logic is identical to defaultbot/units/BabyRatHauler.
 * Defense logic is added on top: DEFEND state triggers when enemy rats threaten king.
 */
public class HaulerDefender {

    /** Call this once per turn for HaulerDefender rats. */
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
        DefenderScouting.continueMineReport(rc, m);

        // ===== SCOUTING CHECK: designated scouts explore for new mines =====
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        scanAndCacheMines(rc, m, nearby);

        if (shouldBeScout(rc, m)) {
            // This rat is a designated scout - explore for mines
            doScoutTurn(rc, m, nearby);
            if (DEBUG) rc.setIndicatorString("SCOUT | Mines:" + m.knownMineCount + " | Target:" + (m.scoutTarget != null ? m.scoutTarget.toString() : "none"));
            return;
        }

        // ===== COMPUTE THREAT (CATS + ENEMY RATS) ONCE PER TURN =====
        ThreatInfo threat = computeThreat(rc, m);

        // traffic control after delivery
        if (m.moveAwayCounter > 0) {
            // Even during traffic control, flee if threatened
            if (threat.level != ThreatLevel.NONE) {
                m.currentState = RatState.FLEE;
                m.moveAwayCounter = 0;
            } else {
                // Attempt to report mine before moving away from king
                attemptMineReport(rc, m);
                moveAwayFromKing(rc, m);
                m.moveAwayCounter--;
                if (m.moveAwayCounter == 0) {
                    if (m.kingLocation != null) {
                        Direction away = m.kingLocation.directionTo(rc.getLocation());
                        if (away != Direction.CENTER && rc.canTurn(away)) rc.turn(away);
                    }
                    m.currentState = RatState.EXPLORE;
                    m.stuckCounter = 0;
                    m.turnsAtTarget = 0;
                }
            }
            if (DEBUG) rc.setIndicatorString("HAULER TRAFFIC | " + m.currentState + " | Threat:" + threat.level);
            return;
        }

        // ===== FSM TRANSITIONS BASED ON THREAT =====
        // DEFEND takes priority when enemy rats threaten the king (defense-specific)
        if (threat.shouldDefendKing && threat.enemyToAttack != null) {
            if (m.currentState != RatState.DEFEND) {
                m.previousState = m.currentState;
                m.currentState = RatState.DEFEND;
            }
        } else if (threat.level != ThreatLevel.NONE) {
            // Flee from threats (same as defaultbot)
            if (m.currentState != RatState.FLEE && m.currentState != RatState.DEFEND) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            } else if (m.currentState == RatState.DEFEND && !threat.shouldDefendKing) {
                // Was defending but now need to flee
                m.currentState = RatState.FLEE;
            }
        } else if (m.currentState == RatState.FLEE || m.currentState == RatState.DEFEND) {
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
            default:      runExplore(rc, m, turnCount, threat); break;
        }

        if (DEBUG) rc.setIndicatorString("HAULER " + m.currentState + " | Raw:" + rc.getRawCheese() + " | Mine:" + (m.assignedMine != null ? m.assignedMine.toString() : "none"));
    }

    // ===== FSM STATES (copied from defaultbot) =====

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

        // Scout system: check if this hauler should keep exploring
        int mineCount = rc.readSharedArray(MINE_COUNT_SLOT);
        boolean shouldScout = m.isScout && mineCount < 2 && rc.getRoundNum() < 400;

        MapLocation target;

        // Priority 1: Visible mine nearby
        if (nearestMine != null) {
            target = nearestMine;
            m.turnsAtTarget = 0;
        }
        // Priority 2: Assigned mine from shared array (if we have one and not scouting)
        else if (m.assignedMine != null && !shouldScout) {
            target = m.assignedMine;
            // Reset turns at target if we've reached the assigned mine area
            if (here.distanceSquaredTo(m.assignedMine) <= 25) {
                // Rotate 90° every ~3 turns to sweep cone when no cheese visible
                if (m.turnsAtTarget % 3 == 0 && m.turnsAtTarget > 0) {
                    Direction cur = rc.getDirection();
                    Direction rotated = cur.rotateRight().rotateRight(); // 90° rotation
                    if (rc.canTurn(rotated)) rc.turn(rotated);
                }
                // We're near the mine but don't see cheese, explore around it
                if (m.turnsAtTarget > 10) {
                    // Spent too long here, pick a new exploration target nearby
                    m.explorationTarget = pickExplorationNearMine(rc, m, m.assignedMine);
                    target = m.explorationTarget;
                    m.turnsAtTarget = 0;
                }
            }
        }
        // Priority 2.5: Fallback to local mine cache when no shared assignment
        else if (!shouldScout && m.knownMineCount > 0) {
            // Use nearest known mine from local cache
            MapLocation cachedMine = m.getNearestKnownMine(here);
            if (cachedMine != null) {
                target = cachedMine;
                // Same behavior as assigned mine - explore around if near
                if (here.distanceSquaredTo(cachedMine) <= 25) {
                    if (m.turnsAtTarget % 3 == 0 && m.turnsAtTarget > 0) {
                        Direction cur = rc.getDirection();
                        Direction rotated = cur.rotateRight().rotateRight();
                        if (rc.canTurn(rotated)) rc.turn(rotated);
                    }
                    if (m.turnsAtTarget > 10) {
                        m.explorationTarget = pickExplorationNearMine(rc, m, cachedMine);
                        target = m.explorationTarget;
                        m.turnsAtTarget = 0;
                    }
                }
            } else {
                // Cache is inconsistent, fall through to exploration
                target = null;
            }
        }
        // Priority 2.75: Scout symmetry speculation
        else if (shouldScout && m.lastSeenMine != null && mineCount < 2) {
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
                // Clamp to map bounds
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
        // Priority 3: Center early game
        else if (!m.hasReachedCenter && m.mapCenter != null && turnCount < 80) {
            target = m.mapCenter;
            if (here.distanceSquaredTo(m.mapCenter) <= 35 || m.turnsAtTarget > 20) {
                m.hasReachedCenter = true;
                m.turnsAtTarget = 0;
            }
        }
        // Priority 4: Persistent exploration target
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
     * Farther from king = collect more to make the trip worthwhile.
     */
    static int calculateEarlyReturnThreshold(RobotController rc, Mem m) {
        if (m.kingLocation == null) return 10;

        MapLocation here = rc.getLocation();
        int distSq = here.distanceSquaredTo(m.kingLocation);

        // Approximate travel distance
        int dist = (int) Math.sqrt(distSq);

        // Base: 8 cheese minimum
        // Add 1 cheese per 5 tiles of distance, capped at 40
        int threshold = Math.min(40, 8 + (dist / 5));

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

            m.moveAwayCounter = 4;  // Give time to squeak X and Y near king
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

    // ===== DEFENSE-SPECIFIC STATE (not in defaultbot) =====

    /**
     * DEFEND state: Attack enemy rats threatening the king.
     * Triggers when: within 10 tiles of king AND enemy rat within 8 tiles of king.
     * Behavior: Move toward enemy, attack if adjacent.
     */
    static void runDefend(RobotController rc, Mem m, ThreatInfo threat) throws GameActionException {
        // If no enemy to attack, return to previous state
        if (threat.enemyToAttack == null) {
            m.currentState = m.previousState;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation enemyLoc = threat.enemyToAttack;

        // Find the best target (might be different from threat.enemyToAttack)
        RobotInfo bestTarget = findBestAttackTarget(rc, m);
        if (bestTarget != null) {
            enemyLoc = bestTarget.getLocation();
        }

        int distToEnemy = here.distanceSquaredTo(enemyLoc);

        // If adjacent (dist <= 2), attack!
        if (distToEnemy <= 2) {
            int cheeseBoost = 0;
            int globalCheese = rc.getGlobalCheese();
            int rawCheese = rc.getRawCheese();
            int totalCheese = globalCheese + rawCheese;

            // Use cheese boost if we have enough
            if (totalCheese > 200) {
                cheeseBoost = Math.min(25, totalCheese / 10);
            } else if (totalCheese > 100 && bestTarget != null && bestTarget.getHealth() <= 20) {
                cheeseBoost = Math.min(16, totalCheese / 8);
            }

            if (rc.canAttack(enemyLoc, cheeseBoost)) {
                rc.attack(enemyLoc, cheeseBoost);
                if (DEBUG) rc.setIndicatorString("DEFEND ATTACK! Boost:" + cheeseBoost);
                return;
            }
        }

        // Move toward the enemy to get in attack range
        // But stay relatively close to king
        if (m.kingLocation != null) {
            int distToKing = here.distanceSquaredTo(m.kingLocation);
            int enemyDistToKing = enemyLoc.distanceSquaredTo(m.kingLocation);

            // Don't chase too far from king (more than 12 tiles)
            if (distToKing > 144 && distToKing > enemyDistToKing) {
                MapInfo[] nearby = rc.senseNearbyMapInfos();
                intelligentMoveToward(rc, m, m.kingLocation, nearby, threat);
                return;
            }
        }

        // Move toward the enemy
        Direction toEnemy = here.directionTo(enemyLoc);
        if (toEnemy != Direction.CENTER) {
            if (tryMoveInDirection(rc, toEnemy)) return;
            if (tryMoveInDirection(rc, toEnemy.rotateLeft())) return;
            if (tryMoveInDirection(rc, toEnemy.rotateRight())) return;
        }
    }
}
