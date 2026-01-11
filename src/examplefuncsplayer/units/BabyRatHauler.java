package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * HAULER baby rat: cheese collection FSM + cat-aware safety + enemy rat awareness + early deposit.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 */
public class BabyRatHauler {

    // ===== HAULER STATE MACHINE =====
    enum RatState { EXPLORE, COLLECT, RETURN, DELIVER, FLEE }

    // ===== THREAT LEVELS =====
    enum ThreatLevel { NONE, NEAR, BLOCKING, PANIC }

    // ===== THREAT SOURCE TRACKING =====
    static class ThreatInfo {
        ThreatLevel level;
        MapLocation source; // location of threat (cat or enemy rat)

        ThreatInfo(ThreatLevel level, MapLocation source) {
            this.level = level;
            this.source = source;
        }
    }

    // ===== PER-ROBOT MEMORY =====
    static class Mem {
        RatState currentState = RatState.EXPLORE;
        RatState previousState = RatState.EXPLORE;

        MapLocation kingLocation = null;

        int moveAwayCounter = 0;
        int stuckCounter = 0;

        MapLocation mapCenter = null;
        MapLocation explorationTarget = null;
        boolean hasReachedCenter = false;
        Direction explorationDirection = null;

        MapLocation[] recentLocations = new MapLocation[5];
        int recentLocationIndex = 0;

        int turnsAtTarget = 0;

        // Wall-following for unstuck
        boolean wallFollowing = false;
        int wallFollowTurns = 0;
        boolean followLeft = false;
        
        MapLocation lastStuckLocation = null;
        int consecutiveFailedMoves = 0;

        final Random rng;
        Mem(int id) { 
            rng = new Random(6147L + id * 31L);
            followLeft = (id % 2 == 0); // Alternate wall follow direction
        }
    }

    private static final Map<Integer, Mem> MEM = new HashMap<>();
    private static Mem mem(RobotController rc) {
        int id = rc.getID();
        Mem m = MEM.get(id);
        if (m == null) {
            m = new Mem(id);
            MEM.put(id, m);
        }
        return m;
    }

    /** Call this once per turn for hauler rats. */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc);

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

        // ===== COMPUTE THREAT (CATS + ENEMY RATS) ONCE PER TURN =====
        ThreatInfo threat = computeThreat(rc, m);

        // traffic control after delivery
        if (m.moveAwayCounter > 0) {
            // Even during traffic control, flee if threatened
            if (threat.level != ThreatLevel.NONE) {
                m.currentState = RatState.FLEE;
                m.moveAwayCounter = 0;
            } else {
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
            rc.setIndicatorString("HAULER TRAFFIC | " + m.currentState + " | Threat:" + threat.level);
            return;
        }

        // ===== FSM TRANSITIONS BASED ON THREAT =====
        if (threat.level != ThreatLevel.NONE) {
            if (m.currentState != RatState.FLEE) {
                m.previousState = m.currentState;
                m.currentState = RatState.FLEE;
            }
        } else if (m.currentState == RatState.FLEE) {
            m.currentState = m.previousState;
        }

        // execute FSM
        switch (m.currentState) {
            case EXPLORE: runExplore(rc, m, turnCount, threat); break;
            case COLLECT: runCollect(rc, m, threat); break;
            case RETURN:  runReturn(rc, m, threat); break;
            case DELIVER: runDeliver(rc, m, threat); break;
            case FLEE:    runFlee(rc, m, threat); break;
        }

        rc.setIndicatorString("HAULER " + m.currentState + " | Raw:" + rc.getRawCheese() + " | Threat:" + threat.level);
    }

    // ===== THREAT COMPUTATION =====

    /**
     * Compute threat level from nearest cat AND enemy rats.
     * Returns ThreatInfo with level and source location.
     */
    static ThreatInfo computeThreat(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Sense once with max radius to minimize bytecode
        final int MAX_SENSE_RADIUS = Math.max(CAT_FLEE_RADIUS + 3, ENEMY_FLEE_RADIUS);
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(MAX_SENSE_RADIUS);

        // Find nearest cat
        RobotInfo nearestCat = null;
        int catDistSq = Integer.MAX_VALUE;
        for (RobotInfo r : nearbyRobots) {
            if (r.getType() == UnitType.CAT) {
                int d = here.distanceSquaredTo(r.getLocation());
                if (d < catDistSq) {
                    catDistSq = d;
                    nearestCat = r;
                }
            }
        }

        // Find nearest enemy baby rat (and optionally rat king)
        Team myTeam = rc.getTeam();
        RobotInfo nearestEnemy = null;
        int enemyDistSq = Integer.MAX_VALUE;
        for (RobotInfo r : nearbyRobots) {
            if (r.getTeam() != myTeam) {
                if (r.getType() == UnitType.BABY_RAT || r.getType() == UnitType.RAT_KING) {
                    int d = here.distanceSquaredTo(r.getLocation());
                    if (d < enemyDistSq && d <= ENEMY_FLEE_RADIUS * ENEMY_FLEE_RADIUS) {
                        enemyDistSq = d;
                        nearestEnemy = r;
                    }
                }
            }
        }

        // ===== COMPUTE CAT THREAT =====
        ThreatLevel catThreat = ThreatLevel.NONE;
        MapLocation catLoc = null;

        if (nearestCat != null) {
            catLoc = nearestCat.getLocation();

            // PANIC: cat very close
            final int PANIC_THRESHOLD_SQ = 9; // distance ~3
            if (catDistSq <= PANIC_THRESHOLD_SQ) {
                catThreat = ThreatLevel.PANIC;
            }
            // BLOCKING: cat between hauler and king
            else if (m.kingLocation != null) {
                int catToKingDistSq = catLoc.distanceSquaredTo(m.kingLocation);
                int hereToKingDistSq = here.distanceSquaredTo(m.kingLocation);

                if (catToKingDistSq < hereToKingDistSq && catDistSq < hereToKingDistSq) {
                    catThreat = ThreatLevel.BLOCKING;
                }
            }

            // NEAR: cat within flee radius
            if (catThreat == ThreatLevel.NONE) {
                catThreat = ThreatLevel.NEAR;
            }
        }

        // ===== COMPUTE ENEMY RAT THREAT =====
        ThreatLevel enemyThreat = ThreatLevel.NONE;
        MapLocation enemyLoc = null;

        if (nearestEnemy != null) {
            enemyLoc = nearestEnemy.getLocation();

            // PANIC: enemy adjacent (can attack us)
            if (enemyDistSq <= 2) {
                enemyThreat = ThreatLevel.PANIC;
            }
            // NEAR: enemy within flee radius
            else {
                enemyThreat = ThreatLevel.NEAR;
            }
        }

        // ===== MERGE THREATS (PRIORITY: PANIC > BLOCKING > NEAR > NONE) =====
        ThreatLevel finalLevel;
        MapLocation finalSource;

        // Both at PANIC? Choose closest
        if (catThreat == ThreatLevel.PANIC && enemyThreat == ThreatLevel.PANIC) {
            if (catDistSq <= enemyDistSq) {
                finalLevel = ThreatLevel.PANIC;
                finalSource = catLoc;
            } else {
                finalLevel = ThreatLevel.PANIC;
                finalSource = enemyLoc;
            }
        }
        // Enemy PANIC overrides anything except cat PANIC
        else if (enemyThreat == ThreatLevel.PANIC) {
            finalLevel = ThreatLevel.PANIC;
            finalSource = enemyLoc;
        }
        // Cat PANIC
        else if (catThreat == ThreatLevel.PANIC) {
            finalLevel = ThreatLevel.PANIC;
            finalSource = catLoc;
        }
        // Cat BLOCKING (enemy can't block like cats do)
        else if (catThreat == ThreatLevel.BLOCKING) {
            finalLevel = ThreatLevel.BLOCKING;
            finalSource = catLoc;
        }
        // Either is NEAR
        else if (catThreat == ThreatLevel.NEAR || enemyThreat == ThreatLevel.NEAR) {
            // Prefer fleeing from whichever is closer
            if (enemyThreat == ThreatLevel.NEAR && catThreat == ThreatLevel.NEAR) {
                if (enemyDistSq <= catDistSq) {
                    finalLevel = ThreatLevel.NEAR;
                    finalSource = enemyLoc;
                } else {
                    finalLevel = ThreatLevel.NEAR;
                    finalSource = catLoc;
                }
            } else if (enemyThreat == ThreatLevel.NEAR) {
                finalLevel = ThreatLevel.NEAR;
                finalSource = enemyLoc;
            } else {
                finalLevel = ThreatLevel.NEAR;
                finalSource = catLoc;
            }
        }
        // No threats
        else {
            finalLevel = ThreatLevel.NONE;
            finalSource = null;
        }

        return new ThreatInfo(finalLevel, finalSource);
    }

    // ===== FSM STATES =====

    static void runExplore(RobotController rc, Mem m, int turnCount, ThreatInfo threat) throws GameActionException {
        // If threatened, already in FLEE (FSM transitions handle this)
        // This state should only run when threat == NONE

        // if already carrying, return
        if (rc.getRawCheese() > 0) {
            m.currentState = RatState.RETURN;
            m.stuckCounter = 0;
            m.turnsAtTarget = 0;
            return;
        }

        MapLocation here = rc.getLocation();
        MapInfo[] nearby = rc.senseNearbyMapInfos();

        MapLocation nearestCheese = findNearestCheese(rc, nearby);
        if (nearestCheese != null) {
            m.currentState = RatState.COLLECT;
            m.stuckCounter = 0;
            m.turnsAtTarget = 0;
            return;
        }

        MapLocation nearestMine = findNearestCheeseMine(rc, nearby);

        // Enhanced stuck detection
        if (isStuckInLoop(m)) {
            m.stuckCounter++;
            if (m.lastStuckLocation == null || !m.lastStuckLocation.equals(here)) {
                m.lastStuckLocation = here;
            }
        } else {
            m.stuckCounter = 0;
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

        MapLocation target;

        // priority 1: mine
        if (nearestMine != null) {
            target = nearestMine;
            m.turnsAtTarget = 0;
        }
        // priority 2: center early
        else if (!m.hasReachedCenter && m.mapCenter != null && turnCount < 80) {
            target = m.mapCenter;
            if (here.distanceSquaredTo(m.mapCenter) <= 35 || m.turnsAtTarget > 20) {
                m.hasReachedCenter = true;
                m.turnsAtTarget = 0;
            }
        }
        // priority 3: persistent exploration target
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
        // Early return policy: deposit smaller loads more frequently
        final int EARLY_RETURN_RAW = 12; // tunable: 10-15
        int raw = rc.getRawCheese();

        if (raw >= EARLY_RETURN_RAW) {
            m.currentState = RatState.RETURN;
            m.stuckCounter = 0;
            m.consecutiveFailedMoves = 0;
            return;
        }

        MapInfo[] nearby = rc.senseNearbyMapInfos();
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
            m.moveAwayCounter = 2;
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

    // ===== MOVEMENT / DIGGING =====

    static boolean intelligentMoveToward(RobotController rc, Mem m, MapLocation target,
                                         MapInfo[] nearby, ThreatInfo threat) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Safety override: don't move toward king when threatened
        if (target.equals(m.kingLocation) && threat.level != ThreatLevel.NONE && threat.source != null) {
            // Do a safe sidestep instead (perpendicular to threat source)
            Direction awayThreat = threat.source.directionTo(here);
            Direction perp1 = awayThreat.rotateLeft();
            Direction perp2 = awayThreat.rotateRight();

            if (tryMoveInDirection(rc, perp1)) return true;
            if (tryMoveInDirection(rc, perp2)) return true;
            return false;
        }

        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        // Wall-following mode for getting unstuck
        if (m.wallFollowing) {
            boolean moved = performWallFollow(rc, m, desired, nearby);
            m.wallFollowTurns++;
            if (m.wallFollowTurns > 15 || moved && canMoveToward(rc, desired, nearby)) {
                m.wallFollowing = false;
                m.wallFollowTurns = 0;
            }
            return moved;
        }

        // dig only for haulers when global cheese is decent
        if (maybeDigBlockingDirt(rc, m, desired, nearby)) return true;

        Direction best = findBestPassableDirection(here, desired, nearby);
        if (best != null) {
            boolean moved = false;
            if (rc.getDirection() != best && rc.canTurn(best)) {
                rc.turn(best);
                moved = true;
            }
            if (rc.canMoveForward()) {
                rc.moveForward();
                moved = true;
            }
            return moved;
        } else {
            return moveToward(rc, target);
        }
    }

    static boolean maybeDigBlockingDirt(RobotController rc, Mem m, Direction desired, MapInfo[] nearby) throws GameActionException {
        MapLocation here = rc.getLocation();
        MapLocation forward = here.add(desired);

        boolean forwardIsDirt = false;
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(forward)) {
                forwardIsDirt = info.isDirt();
                break;
            }
        }
        if (!forwardIsDirt) return false;

        // if a sidestep is passable, don't dig
        Direction[] sidesteps = {
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight()
        };
        for (Direction s : sidesteps) {
            MapLocation side = here.add(s);
            for (MapInfo info : nearby) {
                if (info.getMapLocation().equals(side)) {
                    if (info.isPassable()) return false;
                    break;
                }
            }
        }

        if (rc.getGlobalCheese() < DIG_MIN_CHEESE_HAULER) return false;

        if (rc.getDirection() != desired && rc.canTurn(desired)) rc.turn(desired);
        if (rc.canRemoveDirt(forward)) {
            rc.removeDirt(forward);
            return true;
        }
        return false;
    }

    static Direction findBestPassableDirection(MapLocation here, Direction desired, MapInfo[] nearby) {
        Direction[] tryDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight(),
                desired.rotateLeft().rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight().rotateRight()
        };
        
        // Find first passable direction that's not a dead end
        for (Direction d : tryDirs) {
            MapLocation nxt = here.add(d);
            if (isLocationPassable(nxt, nearby)) {
                // Check if this direction leads to a dead end (surrounded by walls/dirt)
                int passableNeighbors = 0;
                for (Direction checkDir : DIRECTIONS) {
                    MapLocation beyond = nxt.add(checkDir);
                    if (isLocationPassable(beyond, nearby)) {
                        passableNeighbors++;
                    }
                }
                // If there are at least 2 passable neighbors, it's not a dead end
                if (passableNeighbors >= 2) {
                    return d;
                }
            }
        }
        
        // If no good direction found, try any passable direction
        for (Direction d : tryDirs) {
            MapLocation nxt = here.add(d);
            if (isLocationPassable(nxt, nearby)) return d;
        }
        return null;
    }

    static boolean isLocationPassable(MapLocation loc, MapInfo[] nearby) {
        for (MapInfo info : nearby) {
            if (info.getMapLocation().equals(loc)) return info.isPassable();
        }
        return true; // unseen = assume passable
    }

    static boolean moveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        Direction[] tryDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight()
        };
        for (Direction d : tryDirs) {
            if (tryMoveInDirection(rc, d)) return true;
        }
        return false;
    }

    static boolean tryMoveInDirection(RobotController rc, Direction dir) throws GameActionException {
        boolean did = false;
        if (dir == null || dir == Direction.CENTER) return false;

        // Check if there's a robot blocking our path
        MapLocation target = rc.getLocation().add(dir);
        if (rc.canSenseLocation(target)) {
            RobotInfo blocker = rc.senseRobotAtLocation(target);
            if (blocker != null) {
                // Don't try to move into occupied space
                return false;
            }
        }

        if (rc.getDirection() != dir && rc.canTurn(dir)) {
            rc.turn(dir);
            did = true;
        }
        if (rc.canMoveForward()) {
            rc.moveForward();
            did = true;
        }
        return did;
    }

    static void moveAwayFromKing(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return;
        MapLocation here = rc.getLocation();
        Direction away = m.kingLocation.directionTo(here);
        if (away == Direction.CENTER) away = DIRECTIONS[m.rng.nextInt(8)];
        tryMoveInDirection(rc, away);
    }

    static void exploreInDirection(RobotController rc, Direction dir) throws GameActionException {
        if (dir == null) dir = Direction.NORTH;
        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);
        else if (rc.canMoveForward()) rc.moveForward();
        else {
            Direction left = dir.rotateLeft();
            Direction right = dir.rotateRight();
            if (rc.canTurn(left)) rc.turn(left);
            else if (rc.canTurn(right)) rc.turn(right);
        }
    }

    // ===== STUCK / TARGETING =====

    static void updateRecentLocations(Mem m, MapLocation loc) {
        m.recentLocations[m.recentLocationIndex] = loc;
        m.recentLocationIndex = (m.recentLocationIndex + 1) % m.recentLocations.length;
    }

    static boolean isStuckInLoop(Mem m) {
        if (m.recentLocations[0] == null) return false;

        int unique = 0;
        for (int i = 0; i < m.recentLocations.length; i++) {
            MapLocation a = m.recentLocations[i];
            if (a == null) continue;
            boolean uniq = true;
            for (int j = 0; j < i; j++) {
                MapLocation b = m.recentLocations[j];
                if (b != null && b.equals(a)) { uniq = false; break; }
            }
            if (uniq) unique++;
        }
        return unique <= 2;
    }

    static void performUnstuckManeuver(RobotController rc, Mem m) throws GameActionException {
        // Enter wall-following mode to escape
        m.wallFollowing = true;
        m.wallFollowTurns = 0;
        
        // Try to move in any direction immediately
        Direction cur = rc.getDirection();
        Direction[] emergencyDirs = {
                cur.rotateRight().rotateRight(),
                cur.rotateLeft().rotateLeft(),
                cur.opposite(),
                cur.rotateRight(),
                cur.rotateLeft()
        };
        
        for (Direction dir : emergencyDirs) {
            if (tryMoveInDirection(rc, dir)) {
                return;
            }
        }
        
        // If truly stuck, try all 8 directions
        for (Direction dir : DIRECTIONS) {
            if (tryMoveInDirection(rc, dir)) {
                return;
            }
        }
    }
    
    /**
     * Wall-following behavior to escape tight spaces.
     * Tries to hug a wall while making progress toward target.
     */
    static boolean performWallFollow(RobotController rc, Mem m, Direction desired, MapInfo[] nearby) throws GameActionException {
        Direction cur = rc.getDirection();
        
        // Choose which side to follow based on memory
        Direction[] followDirs;
        if (m.followLeft) {
            followDirs = new Direction[] {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateLeft().rotateLeft(),
                    cur.rotateLeft(),
                    cur,
                    cur.rotateRight(),
                    desired.rotateRight()
            };
        } else {
            followDirs = new Direction[] {
                    desired,
                    desired.rotateRight(),
                    desired.rotateRight().rotateRight(),
                    cur.rotateRight(),
                    cur,
                    cur.rotateLeft(),
                    desired.rotateLeft()
            };
        }
        
        // Try each direction, checking for passability
        for (Direction dir : followDirs) {
            if (dir == null || dir == Direction.CENTER) continue;
            
            MapLocation next = rc.getLocation().add(dir);
            if (isLocationPassable(next, nearby)) {
                if (rc.getDirection() != dir && rc.canTurn(dir)) {
                    rc.turn(dir);
                }
                if (rc.canMoveForward()) {
                    rc.moveForward();
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if we can move toward a direction (for exiting wall-follow mode).
     */
    static boolean canMoveToward(RobotController rc, Direction desired, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();
        MapLocation target = here.add(desired);
        return isLocationPassable(target, nearby);
    }

    static MapLocation pickNewExplorationTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int strategy = (rc.getID() / 2) % 4;

        int tx, ty;

        switch (strategy) {
            case 0: {
                boolean top = here.y > h / 2;
                boolean left = here.x < w / 2;
                if (top && left) { tx = w - 5; ty = h - 5; }
                else if (top) { tx = 5; ty = h - 5; }
                else if (left) { tx = w - 5; ty = 5; }
                else { tx = 5; ty = 5; }
                break;
            }
            case 1: {
                tx = (here.x > w / 2) ? 5 : w - 5;
                ty = here.y + (m.rng.nextBoolean() ? 10 : -10);
                ty = Math.max(5, Math.min(h - 5, ty));
                break;
            }
            case 2: {
                tx = here.x + (m.rng.nextBoolean() ? 10 : -10);
                tx = Math.max(5, Math.min(w - 5, tx));
                ty = (here.y > h / 2) ? 5 : h - 5;
                break;
            }
            default: {
                if (here.x < w / 2 && here.y < h / 2) { tx = w * 3 / 4; ty = h * 3 / 4; }
                else if (here.x >= w / 2 && here.y < h / 2) { tx = w / 4; ty = h * 3 / 4; }
                else if (here.x < w / 2) { tx = w * 3 / 4; ty = h / 4; }
                else { tx = w / 4; ty = h / 4; }
                break;
            }
        }

        return new MapLocation(tx, ty);
    }

    // ===== SENSING =====

    static MapLocation findNearestCheese(RobotController rc, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestD = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                int d = here.distanceSquaredTo(info.getMapLocation());
                if (d < bestD) { bestD = d; best = info.getMapLocation(); }
            }
        }
        return best;
    }

    static MapLocation findNearestCheeseMine(RobotController rc, MapInfo[] nearby) {
        MapLocation here = rc.getLocation();
        MapLocation best = null;
        int bestD = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.hasCheeseMine()) {
                int d = here.distanceSquaredTo(info.getMapLocation());
                if (d < bestD) { bestD = d; best = info.getMapLocation(); }
            }
        }
        return best;
    }
}