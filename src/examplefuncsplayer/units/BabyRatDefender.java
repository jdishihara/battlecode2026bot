package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * DEFENDER baby rat: protects the king by intercepting and eliminating enemy rats.
 * Maintains defensive perimeter and aggressively engages threats.
 */
public class BabyRatDefender {

    enum DefenseState { 
        PATROL,     // No threats, patrol around king
        INTERCEPT,  // Enemy detected, moving to engage
        ATTACK,     // Adjacent to enemy, attacking
        FLEE_CAT    // Cat nearby, evade
    }

    static class Mem {
        MapLocation kingLocation = null;
        DefenseState state = DefenseState.PATROL;
        
        MapLocation targetEnemy = null;
        int targetEnemyID = -1;
        int turnsSinceEnemySeen = 0;
        
        // Patrol pattern
        int patrolAngle = 0;
        MapLocation patrolTarget = null;
        
        // Movement memory
        MapLocation prev1 = null;
        MapLocation prev2 = null;
        
        final Random rng;
        Mem(int id) {
            rng = new Random(7777L + id * 101L);
            patrolAngle = rng.nextInt(8);
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

    /** Main defender entry point */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc);
        
        // Update king location from shared array
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) {
            m.kingLocation = new MapLocation(kx, ky);
        }
        
        MapLocation here = rc.getLocation();
        
        // PRIORITY 1: Flee from cats
        if (shouldDefenderFlee(rc)) {
            runDefenderFlee(rc, m);
            rc.setIndicatorString("DEFENDER FLEE");
            return;
        }
        
        // PRIORITY 2: Find and prioritize threats
        RobotInfo priorityTarget = findPriorityTarget(rc, m);
        

        if (priorityTarget != null) {
            m.targetEnemy = priorityTarget.getLocation();
            m.targetEnemyID = priorityTarget.getID();
            m.turnsSinceEnemySeen = 0;
            
            // Determine if we're in attack range
            int distToTarget = here.distanceSquaredTo(m.targetEnemy);
            if (distToTarget <= 2) { // Adjacent (can attack)
                m.state = DefenseState.ATTACK;
            } else {
                m.state = DefenseState.INTERCEPT;
            }
        } else {
            m.turnsSinceEnemySeen++;
            
            // Lost target after 5 turns
            if (m.turnsSinceEnemySeen > 5) {
                m.targetEnemy = null;
                m.targetEnemyID = -1;
                m.state = DefenseState.PATROL;
            }
        }
        
        // Execute state behavior
        switch (m.state) {
            case PATROL:
                runPatrol(rc, m);
                break;
            case INTERCEPT:
                runIntercept(rc, m);
                break;
            case ATTACK:
                runAttack(rc, m);
                break;
        }
        
        rc.setIndicatorString("DEFENDER " + m.state + 
            " | Target: " + (m.targetEnemy != null ? m.targetEnemy : "none") +
            " | KingDist: " + (m.kingLocation != null ? 
                Math.sqrt(here.distanceSquaredTo(m.kingLocation)) : "?"));
    }

    // ===== STATE BEHAVIORS =====
    

    static void runPatrol(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return;
        
        MapLocation here = rc.getLocation();
        int distToKing = here.distanceSquaredTo(m.kingLocation);
        
        // Define defensive perimeter (radius 6-9 from king)
        final int MIN_DEFENSE_RADIUS = 36;  // ~6 units
        final int MAX_DEFENSE_RADIUS = 81;  // ~9 units
        
        if (distToKing < MIN_DEFENSE_RADIUS) {
            // Too close, move away from king
            Direction away = here.directionTo(m.kingLocation).opposite();
            tryMoveDirection(rc, m, away);
            
        } else if (distToKing > MAX_DEFENSE_RADIUS) {
            // Too far, move toward king
            moveToward(rc, m, m.kingLocation);
            
        } else {
            // Good radius, patrol in circle
            patrolCircle(rc, m);
        }
    }
    
    static void runIntercept(RobotController rc, Mem m) throws GameActionException {
        if (m.targetEnemy == null) {
            m.state = DefenseState.PATROL;
            return;
        }
        
        MapLocation here = rc.getLocation();
        int distToTarget = here.distanceSquaredTo(m.targetEnemy);
        
        // Move aggressively toward target
        if (distToTarget > 2) {
            moveToward(rc, m, m.targetEnemy);
        } else {
            m.state = DefenseState.ATTACK;
        }
    }
    
    static void runAttack(RobotController rc, Mem m) throws GameActionException {
        if (m.targetEnemy == null) {
            m.state = DefenseState.PATROL;
            return;
        }
        
        MapLocation here = rc.getLocation();
        
        // Try to attack with cheese boost
        int cheeseBoost = calculateCheeseBoost(rc);
        
        if (rc.canAttack(m.targetEnemy, cheeseBoost)) {
            rc.attack(m.targetEnemy, cheeseBoost);
            return;
        }
        
        // Target moved, pursue
        int distToTarget = here.distanceSquaredTo(m.targetEnemy);
        if (distToTarget > 2) {
            m.state = DefenseState.INTERCEPT;
            moveToward(rc, m, m.targetEnemy);
        }
    }

    // ===== TARGET PRIORITIZATION =====
    
    static RobotInfo findPriorityTarget(RobotController rc, Mem m) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        if (enemies.length == 0) return null;
        
        MapLocation here = rc.getLocation();
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        
        for (RobotInfo enemy : enemies) {
            // Only target baby rats and rat kings
            if (enemy.getType() != UnitType.BABY_RAT && 
                enemy.getType() != UnitType.RAT_KING) {
                continue;
            }
            
            int score = calculateThreatScore(rc, m, enemy, here);
            
            if (score > bestScore) {
                bestScore = score;
                bestTarget = enemy;
            }
        }
        
        return bestTarget;
    }
    
    static int calculateThreatScore(RobotController rc, Mem m, 
                                     RobotInfo enemy, MapLocation here) {
        int score = 0;
        MapLocation enemyLoc = enemy.getLocation();
        
        // HIGHEST PRIORITY: Distance to our king
        if (m.kingLocation != null) {
            int enemyDistToKing = enemyLoc.distanceSquaredTo(m.kingLocation);
            score += (200 - enemyDistToKing); // Closer to king = much higher priority
            
            // Extra bonus if very close to king (critical threat)
            if (enemyDistToKing <= 25) {
                score += 200;
            }
        }
        
        // MEDIUM PRIORITY: Proximity to us (easier to kill)
        int distToUs = here.distanceSquaredTo(enemyLoc);
        score += (50 - distToUs);
        
        // LOW PRIORITY: Low health targets (easier kills)
        int healthRemaining = enemy.getHealth();
        score += (100 - healthRemaining) / 2;
        
        // Slight preference for baby rats over rat kings (easier to kill)
        if (enemy.getType() == UnitType.BABY_RAT) {
            score += 20;
        }
        
        // Bonus for continuing to attack current target (don't switch constantly)
        if (m.targetEnemyID == enemy.getID()) {
            score += 50;
        }
        
        return score;
    }

    // ===== CAT EVASION =====
    
    static boolean shouldDefenderFlee(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(DEFENDER_CAT_FLEE_RADIUS);
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.CAT) return true;
        }
        return false;
    }
    
    static void runDefenderFlee(RobotController rc, Mem m) throws GameActionException {
        RobotInfo nearestCat = getNearestCat(rc, DEFENDER_CAT_FLEE_RADIUS);
        if (nearestCat == null) return;
        
        MapLocation here = rc.getLocation();
        MapLocation catLoc = nearestCat.getLocation();
        
        // Flee away from cat, but try to stay near king if possible
        Direction awayCat = here.directionTo(catLoc).opposite();
        
        if (m.kingLocation != null) {
            Direction toKing = here.directionTo(m.kingLocation);
            
            // Try to flee in a direction that's both away from cat AND toward king
            Direction[] fleeDirs = {
                awayCat,
                awayCat.rotateLeft(),
                awayCat.rotateRight(),
                toKing,
                awayCat.rotateLeft().rotateLeft(),
                awayCat.rotateRight().rotateRight()
            };
            
            for (Direction dir : fleeDirs) {
                if (tryMoveDirection(rc, m, dir)) {
                    return;
                }
            }
        } else {
            // Just flee away from cat
            tryMoveDirection(rc, m, awayCat);
        }
    }
    
    static RobotInfo getNearestCat(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radius);
        MapLocation here = rc.getLocation();
        
        RobotInfo nearest = null;
        int minDist = Integer.MAX_VALUE;
        
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.CAT) {
                int dist = here.distanceSquaredTo(r.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = r;
                }
            }
        }
        return nearest;
    }

    // ===== PATROL BEHAVIOR =====
    
    static void patrolCircle(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return;
        
        // Move in circular pattern around king
        Direction[] directions = {
            Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
            Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
        };
        
        // Update patrol angle periodically
        if (rc.getRoundNum() % 3 == 0) {
            m.patrolAngle = (m.patrolAngle + 1) % 8;
        }
        
        Direction patrolDir = directions[m.patrolAngle];
        tryMoveDirection(rc, m, patrolDir);
    }

    // ===== CHEESE BOOST CALCULATION =====
    
    static int calculateCheeseBoost(RobotController rc) {
        int rawCheese = rc.getRawCheese();
        int globalCheese = rc.getGlobalCheese();
        int totalCheese = rawCheese + globalCheese;
        
        // Read threat level from shared array
        int threatLevel = 0;
        try {
            threatLevel = rc.readSharedArray(ENEMY_THREAT_LEVEL);
        } catch (GameActionException e) {
            // Ignore
        }
        
        // Aggressive cheese spending when defending king
        if (threatLevel >= 2) {
            // High threat: spend up to 100 cheese per attack
            return Math.min(100, totalCheese / 2);
        } else if (threatLevel == 1) {
            // Medium threat: spend up to 50 cheese
            return Math.min(50, totalCheese / 3);
        } else {
            // Low threat: minimal boost
            return Math.min(25, totalCheese / 5);
        }
    }

    // ===== MOVEMENT HELPERS =====
    
    static boolean moveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;
        
        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        
        if (desired == Direction.CENTER) return false;
        
        // Try primary direction and nearby alternatives
        Direction[] tryDirs = {
            desired,
            desired.rotateLeft(),
            desired.rotateRight(),
            desired.rotateLeft().rotateLeft(),
            desired.rotateRight().rotateRight()
        };
        
        for (Direction dir : tryDirs) {
            if (tryMoveDirection(rc, m, dir)) {
                return true;
            }
        }
        
        return false;
    }
    
    static boolean tryMoveDirection(RobotController rc, Mem m, Direction dir) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;
        
        MapLocation here = rc.getLocation();
        MapLocation next = here.add(dir);
        
        // Anti-oscillation: don't return to recent positions
        if ((m.prev1 != null && next.equals(m.prev1)) || 
            (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }
        
        // Avoid occupied tiles
        if (rc.canSenseLocation(next)) {
            RobotInfo occupant = rc.senseRobotAtLocation(next);
            if (occupant != null) return false;
        }
        
        // Turn if needed
        if (rc.getDirection() != dir && rc.canTurn(dir)) {
            rc.turn(dir);
        }
        
        // Move
        if (rc.canMove(dir)) {
            rc.move(dir);
            m.prev2 = m.prev1;
            m.prev1 = here;
            return true;
        }
        
        return false;
    }
}