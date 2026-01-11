package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * ATTACKER baby rat: seeks enemy king, bites when adjacent, runs limited cat
 * flee.
 * Uses per-robot memory (keyed by rc.getID()) to avoid static state collisions.
 */
public class BabyRatAttacker {

    enum AttackState {
        SEEK, ATTACK, GUARD
    }

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
        if (kx != 0 || ky != 0)
            m.kingLocation = new MapLocation(kx, ky);

        // READ SQUEAKS: check if any ally found enemy king
        Message[] squeaks = rc.readSqueaks(-1); // -1 gets all messages from past 5 rounds
                                                // :contentReference[oaicite:2]{index=2}

        for (Message squeak : squeaks) {
            if (squeak.getBytes() == ENEMY_KING_SQUEAK_MESSAGE) { // payload :contentReference[oaicite:3]{index=3}

                int senderId = squeak.getSenderID();
                if (rc.canSenseRobot(senderId)) {
                    RobotInfo sender = rc.senseRobot(senderId);
                    if (sender.team != rc.getTeam())
                        continue;
                }

                // Another rat found the enemy king!
                m.targetKingLoc = squeak.getSource();
                m.turnsSinceKingSeen = 0;
                m.state = AttackState.ATTACK;
                break;
            }
        }

        // FLEE CATS FIRST - highest priority!
        if (shouldAttackerFlee(rc)) {
            runAttackerFlee(rc);
            rc.setIndicatorString("ATTACKER FLEE | " + m.state);
            return;
        }

        RobotInfo nearbyEnemyRatNearKing = findNearbyEnemyRatNearKing(rc, m);
        if (nearbyEnemyRatNearKing != null) {
            m.state = AttackState.GUARD;
            m.targetKingLoc = nearbyEnemyRatNearKing.getLocation();
        }

        // find enemy king
        RobotInfo enemyKing = findEnemyKing(rc);
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;

            // SQUEAK TO ALERT ALL NEARBY RATS!
            if (rc.isActionReady()) { // :contentReference[oaicite:2]{index=2}
                rc.squeak(ENEMY_KING_SQUEAK_MESSAGE); // :contentReference[oaicite:3]{index=3}
            }

        } else {
            m.turnsSinceKingSeen++;
            if (m.targetKingLoc != null && m.turnsSinceKingSeen > MAX_STALE_TARGET_TURNS) {
                m.targetKingLoc = null;
                m.state = AttackState.SEEK;
            }
        }

        switch (m.state) {
            case SEEK:
                runSeek(rc, m);
                break;
            case ATTACK:
                runAttack(rc, m);
                break;
            case GUARD:
                runGuard(rc, m);
                break;
        }

        rc.setIndicatorString("ATTACKER " + m.state +
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
        for (RobotInfo r : nearby)
            if (r.getType() == UnitType.CAT)
                return true;
        return false;
    }

    static void runAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo nearestCat = getNearestCat(rc, ATTACKER_CAT_FLEE_RADIUS);
        if (nearestCat == null)
            return;

        MapLocation here = rc.getLocation();
        Direction away = here.directionTo(nearestCat.getLocation()).opposite();

        if (rc.getDirection() != away && rc.canTurn(away))
            rc.turn(away);
        if (rc.canMoveForward())
            rc.moveForward();
    }

    static RobotInfo getNearestCat(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radius);
        MapLocation here = rc.getLocation();

        RobotInfo best = null;
        int bestD = Integer.MAX_VALUE;

        for (RobotInfo r : nearby) {
            if (r.getType() != UnitType.CAT)
                continue;
            int d = here.distanceSquaredTo(r.getLocation());
            if (d < bestD) {
                bestD = d;
                best = r;
            }
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
                if (d < bestD) {
                    bestD = d;
                    best = r;
                }
            }
        }
        return best;
    }

    static RobotInfo findNearbyEnemyRatNearKing(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null)
            return null;

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

        // Calculate symmetric positions of our king (where enemy king likely is)
        if (m.kingLocation != null) {
            int ratID = rc.getID();

            // Different rats try different symmetries to spread out
            int symmetryType = ratID % 3;
            MapLocation baseTarget;

            if (symmetryType == 0) {
                // Rotational symmetry
                baseTarget = new MapLocation(w - 1 - m.kingLocation.x, h - 1 - m.kingLocation.y);
            } else if (symmetryType == 1) {
                // Horizontal flip
                baseTarget = new MapLocation(w - 1 - m.kingLocation.x, m.kingLocation.y);
            } else {
                // Vertical flip
                baseTarget = new MapLocation(m.kingLocation.x, h - 1 - m.kingLocation.y);
            }

            // Add LARGE random offset to really spread rats out
            int offX = m.rng.nextInt(21) - 10; // -10 to +10
            int offY = m.rng.nextInt(21) - 10; // -10 to +10

            int tx = Math.max(5, Math.min(w - 6, baseTarget.x + offX));
            int ty = Math.max(5, Math.min(h - 6, baseTarget.y + offY));

            return new MapLocation(tx, ty);
        }

        // Fallback: head to opposite side of map
        int tx = (here.x < w / 2) ? (int) (w * 0.8) : (int) (w * 0.2);
        int ty = (here.y < h / 2) ? (int) (h * 0.8) : (int) (h * 0.2);
        return new MapLocation(tx, ty);
    }

    // ===== IMPROVED NAVIGATION WITH DIRT DIGGING =====

    static boolean attackerMoveToward(RobotController rc, Mem m, MapLocation target) throws GameActionException {
        if (target == null)
            return false;

        MapLocation here = rc.getLocation();
        if (here.distanceSquaredTo(target) <= 2)
            return false;

        Direction desired = here.directionTo(target);
        if (desired == Direction.CENTER)
            return false;

        // Priority list: try directions closer to target first
        Direction[] tryDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight(),
                desired.rotateLeft().rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight().rotateRight(),
                desired.opposite()
        };

        // Try to move in best available direction
        MapLocation bestMove = null;
        Direction bestDir = null;
        int bestDist = Integer.MAX_VALUE;

        for (Direction dir : tryDirs) {
            if (dir == null || dir == Direction.CENTER)
                continue;

            MapLocation next = here.add(dir);

            // Skip if off map
            if (!rc.onTheMap(next))
                continue;

            // Anti-oscillation: don't return to recent positions
            if ((m.prev1 != null && next.equals(m.prev1)) ||
                    (m.prev2 != null && next.equals(m.prev2))) {
                continue;
            }

            // Check if we can move there
            if (!rc.canMove(dir))
                continue;

            // Skip if occupied by robot
            if (rc.canSenseLocation(next)) {
                RobotInfo at = rc.senseRobotAtLocation(next);
                if (at != null)
                    continue;
            }

            // Calculate distance improvement
            int dist = next.distanceSquaredTo(target);
            if (dist < bestDist) {
                bestMove = next;
                bestDir = dir;
                bestDist = dist;
            }
        }

        // Execute the best move if found
        if (bestMove != null && bestDir != null) {
            if (rc.getDirection() != bestDir && rc.canTurn(bestDir)) {
                rc.turn(bestDir);
            }
            if (rc.canMove(bestDir)) {
                rc.move(bestDir);
                m.prev2 = m.prev1;
                m.prev1 = here;
                m.failedMoves = 0;
                return true;
            }
        }

        // Couldn't move - try to dig through dirt
        if (tryDigTowardTarget(rc, m, target, desired)) {
            m.failedMoves = 0;
            return true;
        }

        // Still stuck - increment fail counter
        m.failedMoves++;

        // If stuck too long, pick a new nearby exploration target
        if (m.failedMoves > 8) {
            m.explorationTarget = pickRandomNearbyTarget(rc, m);
            m.failedMoves = 0;
            m.turnsAtTarget = 0;
        }

        return false;
    }

    // Try to dig dirt blocking path to target
    static boolean tryDigTowardTarget(RobotController rc, Mem m, MapLocation target, Direction desired)
            throws GameActionException {
        MapLocation here = rc.getLocation();

        // Check directions in priority order
        Direction[] digDirs = {
                desired,
                desired.rotateLeft(),
                desired.rotateRight(),
                desired.rotateLeft().rotateLeft(),
                desired.rotateRight().rotateRight()
        };

        for (Direction dir : digDirs) {
            if (dir == null || dir == Direction.CENTER)
                continue;

            MapLocation digLoc = here.add(dir);

            // Check if we can sense and dig this location
            if (!rc.canSenseLocation(digLoc))
                continue;

            // Check if there's dirt here
            MapInfo info = rc.senseMapInfo(digLoc);
            if (!info.isDirt())
                continue;

            // Check if we have enough cheese to dig (costs 10 cheese)
            if (rc.getGlobalCheese() < 10)
                continue;

            // Dig it!
            rc.removeDirt(digLoc);
            return true;
        }

        return false;
    }

    // Pick a random nearby location to escape being stuck
    static MapLocation pickRandomNearbyTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        // Random offset 10-20 tiles away
        int range = 10 + m.rng.nextInt(11);
        int angle = m.rng.nextInt(8);

        int dx = 0, dy = 0;
        switch (angle) {
            case 0:
                dx = range;
                dy = 0;
                break;
            case 1:
                dx = range;
                dy = range;
                break;
            case 2:
                dx = 0;
                dy = range;
                break;
            case 3:
                dx = -range;
                dy = range;
                break;
            case 4:
                dx = -range;
                dy = 0;
                break;
            case 5:
                dx = -range;
                dy = -range;
                break;
            case 6:
                dx = 0;
                dy = -range;
                break;
            case 7:
                dx = range;
                dy = -range;
                break;
        }

        int tx = Math.max(3, Math.min(w - 4, here.x + dx));
        int ty = Math.max(3, Math.min(h - 4, here.y + dy));

        return new MapLocation(tx, ty);
    }

}