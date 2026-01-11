package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * ATTACKER baby rat: seeks enemy king, bites when adjacent, runs limited cat flee.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 */
public class BabyRatAttacker {

    enum AttackState { SEEK, ATTACK, GUARD }

    static class Mem {
        MapLocation kingLocation = null;

        AttackState state = AttackState.SEEK;
        MapLocation targetKingLoc = null;
        int turnsSinceKingSeen = 0;
        MapLocation explorationTarget = null;
        int turnsAtTarget = 0;

        // BUG nav memory
        boolean bugging = false;
        boolean bugLeft = false;
        int bugTurns = 0;
        int failedMoves = 0;
        MapLocation prev1 = null;
        MapLocation prev2 = null;

        MapLocation mapCenter = null;
        boolean hasReachedCenter = false;

        final Random rng;
        Mem(int id) {
            rng = new Random(9001L + id * 97L);
            bugLeft = ((id / 2) % 2 == 0);
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

    /** Call this once per turn for attacker rats. */
    public static void run(RobotController rc, int turnCount) throws GameActionException {
        Mem m = mem(rc);

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // read our king
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) m.kingLocation = new MapLocation(kx, ky);
        
        RobotInfo nearbyEnemyRatNearKing = findNearbyEnemyRatNearKing(rc, m);
        if (nearbyEnemyRatNearKing != null) {
            m.state = AttackState.GUARD;
            m.targetKingLoc = nearbyEnemyRatNearKing.getLocation();
        }

        // flee only when cat very close
        if (shouldAttackerFlee(rc)) {
            runAttackerFlee(rc);
            rc.setIndicatorString("ATTACKER FLEE | " + m.state);
            return;
        }

        // find enemy king
        RobotInfo enemyKing = findEnemyKing(rc);
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;
        } else {
            m.turnsSinceKingSeen++;
            if (m.targetKingLoc != null && m.turnsSinceKingSeen > MAX_STALE_TARGET_TURNS) {
                m.targetKingLoc = null;
                m.state = AttackState.SEEK;
            }
        }

        switch (m.state) {
            case SEEK:   runSeek(rc, m); break;
            case ATTACK: runAttack(rc, m); break;
            case GUARD:  runGuard(rc, m); break;

        }

        rc.setIndicatorString("ATTACKER " + m.state +
                (m.bugging ? " BUG" + (m.bugLeft ? "L" : "R") : "") +
                " | Target: " + (m.targetKingLoc != null ? m.targetKingLoc : "none") +
                " | Fails: " + m.failedMoves);
    }

    // ===== STATES =====

    static void runSeek(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();

        if (m.targetKingLoc != null) {
            attackerMoveToward(rc, m, m.targetKingLoc);
            return;
        }

        if (m.explorationTarget == null ||
                here.distanceSquaredTo(m.explorationTarget) <= 9 ||
                m.turnsAtTarget > MAX_TARGET_PURSUIT_TURNS) {
            m.explorationTarget = pickExplorationTarget(rc, m);
            m.turnsAtTarget = 0;
        }

        m.turnsAtTarget++;
        attackerMoveToward(rc, m, m.explorationTarget);
    }

    static void runAttack(RobotController rc, Mem m) throws GameActionException {
        if (m.targetKingLoc == null) {
            m.state = AttackState.SEEK;
            return;
        }

        // Try to bite any tile of the 3x3 king area
        MapLocation center = m.targetKingLoc;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = new MapLocation(center.x + dx, center.y + dy);
                int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(tile, spend)) {
                    rc.attack(tile, spend);
                    return;
                }
            }
        }

        attackerMoveToward(rc, m, m.targetKingLoc);
    }
    static void runGuard(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();

        //Find and attack nearby enemy rats

        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        RobotInfo closestEnemy = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo enemy : enemies){
            if (enemy.getType() == UnitType.BABY_RAT){
                int dist = here.distanceSquaredTo(enemy.getLocation());
                if (dist < closestDist){
                    closestDist = dist;
                    closestEnemy = enemy;
                }
            }
        }
        if (closestEnemy != null) {
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
                return;
            }
            
            // Move toward enemy to attack
            attackerMoveToward(rc, m, closestEnemy.getLocation());
            return;
        }
        // No nearby enemy rats, move back to guarding position near kin
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            attackerMoveToward(rc, m, m.kingLocation);
        } else {
            m.state = AttackState.SEEK;
        }
    }

    // ===== SENSING =====

    static boolean shouldAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_CAT_FLEE_RADIUS);
        for (RobotInfo r : nearby) if (r.getType() == UnitType.CAT) return true;
        return false;
    }

    static void runAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo nearestCat = getNearestCat(rc, ATTACKER_CAT_FLEE_RADIUS);
        if (nearestCat == null) return;

        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(nearestCat.getLocation()).opposite();

        if (rc.getDirection() != away && rc.canTurn(away)) rc.turn(away);
        if (rc.canMoveForward()) rc.moveForward();
    }

    static RobotInfo getNearestCat(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radius);
        MapLocation here = rc.getLocation();

        RobotInfo best = null;
        int bestD = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getType() != UnitType.CAT) continue;
            int d = here.distanceSquaredTo(r.getLocation());
            if (d < bestD) { bestD = d; best = r; }
        }
        return best;
    }

    static RobotInfo findEnemyKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);
        Team enemy = rc.getTeam().opponent();
        MapLocation here = rc.getLocation();

        RobotInfo best = null;
        int bestD = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.RAT_KING && r.getTeam() == enemy) {
                int d = here.distanceSquaredTo(r.getLocation());
                if (d < bestD) { bestD = d; best = r; }
            }
        }
        return best;
    }
    static RobotInfo findNearbyEnemyRatNearKing(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return null;
        
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_VISION_RADIUS);
        Team enemy = rc.getTeam().opponent();
        
        for (RobotInfo r : nearby) {
            if (r.getTeam() == enemy && r.getType() == UnitType.BABY_RAT) {
                // Check if enemy is near our king
                int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                if (distToKing <= 100) { // Within 10 units of king
                    return r;
                }
            }
        }
        return null;
    }

    // ===== EXPLORATION TARGETING =====

    static MapLocation pickExplorationTarget(RobotController rc, Mem m) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        MapLocation here = rc.getLocation();

        // head toward center first
        if (!m.hasReachedCenter && m.mapCenter != null && here.distanceSquaredTo(m.mapCenter) > 100) {
            return m.mapCenter;
        }
        m.hasReachedCenter = true;

        // assume enemy king roughly symmetric to ours
        if (m.kingLocation != null) {
            int ex = w - 1 - m.kingLocation.x;
            int ey = h - 1 - m.kingLocation.y;

            int offX = m.rng.nextInt(11) - 5;
            int offY = m.rng.nextInt(11) - 5;

            ex = Math.max(3, Math.min(w - 4, ex + offX));
            ey = Math.max(3, Math.min(h - 4, ey + offY));

            return new MapLocation(ex, ey);
        }

        // fallback: opposite corner from our current position
        int tx = (here.x < w / 2) ? w - 5 : 5;
        int ty = (here.y < h / 2) ? h - 5 : 5;
        return new MapLocation(tx, ty);
    }

    // ===== ATTACKER NAV (BUG + anti-oscillation + avoid occupied tiles) =====

    static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null) return false;

        MapLocation here = rc.getLocation();
        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER) return false;

        if (!m.bugging) {
            Direction[] tryDirs = {
                    desired,
                    desired.rotateLeft(),
                    desired.rotateRight(),
                    desired.rotateLeft().rotateLeft(),
                    desired.rotateRight().rotateRight()
            };

            for (Direction d : tryDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.failedMoves = 0;
                    return true;
                }
            }

            m.failedMoves++;
            if (m.failedMoves >= 2) {
                m.bugging = true;
                m.bugTurns = 0;
            }
        }

        if (m.bugging) {
            Direction[] bugDirs;
            if (m.bugLeft) {
                bugDirs = new Direction[] {
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired,
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired.opposite().rotateRight(),
                        desired.opposite().rotateLeft(),
                        desired.opposite()
                };
            } else {
                bugDirs = new Direction[] {
                        desired.rotateRight(),
                        desired.rotateRight().rotateRight(),
                        desired,
                        desired.rotateLeft(),
                        desired.rotateLeft().rotateLeft(),
                        desired.opposite().rotateLeft(),
                        desired.opposite().rotateRight(),
                        desired.opposite()
                };
            }

            for (Direction d : bugDirs) {
                if (tryMoveDirAvoidRecent(rc, m, d, here)) {
                    m.bugTurns++;
                    m.failedMoves = 0;

                    if (rc.canMove(desired) || m.bugTurns > 12) {
                        m.bugging = false;
                        m.bugTurns = 0;
                    }
                    return true;
                }
            }

            m.bugTurns++;
            if (m.bugTurns > 12) {
                m.bugging = false;
                m.bugTurns = 0;
            }
        }

        return false;
    }

    static boolean tryMoveDirAvoidRecent(RobotController rc, Mem m, Direction dir, MapLocation oldLoc) throws GameActionException {
        if (dir == null || dir == Direction.CENTER) return false;

        MapLocation next = oldLoc.add(dir);

        // anti-oscillation
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // avoid occupied tiles (robots)
        if (rc.canSenseLocation(next)) {
            RobotInfo at = rc.senseRobotAtLocation(next);
            if (at != null) return false;
        }

        if (rc.getDirection() != dir && rc.canTurn(dir)) rc.turn(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
            m.prev2 = m.prev1;
            m.prev1 = oldLoc;
            return true;
        }

        return false;
    }
}
