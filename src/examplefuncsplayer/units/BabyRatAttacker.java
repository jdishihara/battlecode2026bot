package examplefuncsplayer.units;

import battlecode.common.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.config.Shared.*;

/**
 * ATTACKER baby rat: seeks enemy king, coordinates attacks via squeaking,
 * places offensive traps, and defends allied king when threatened.
 * 
 * Key improvements:
 * - Squeak-based swarming: calls allies when enemy king found
 * - Offensive trap placement: traps enemy movement paths
 * - Defensive response: guards king when enemies detected nearby
 * - Gang-up tactics: attacks when sufficient allies present
 */
public class BabyRatAttacker {

    enum AttackState { SEEK, ATTACK, GUARD, SWARM }

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

        // Combat coordination (NEW)
        int lastSqueakRound = -100;
        int lastTrapRound = -100;
        MapLocation swarmTarget = null;
        int swarmStartRound = -100;

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
        int roundNum = rc.getRoundNum();

        if (m.mapCenter == null) {
            m.mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        }

        // Read our king location from shared array
        int kx = rc.readSharedArray(KING_X_INDEX);
        int ky = rc.readSharedArray(KING_Y_INDEX);
        if (kx != 0 || ky != 0) m.kingLocation = new MapLocation(kx, ky);

        // === PROCESS INCOMING SQUEAKS ===
        processIncomingSqueaks(rc, m, roundNum);

        // === CHECK FOR SWARM TARGET FROM SHARED ARRAY ===
        checkSharedSwarmTarget(rc, m, roundNum);

        // === GUARD MODE: Check if enemy rats near our king ===
        RobotInfo nearbyEnemyRatNearKing = findNearbyEnemyRatNearKing(rc, m);
        if (nearbyEnemyRatNearKing != null) {
            m.state = AttackState.GUARD;
            m.targetKingLoc = nearbyEnemyRatNearKing.getLocation();
            
            // Squeak for backup!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                // squeak() returns true if successful, false if max squeaks reached
                if (rc.squeak(SQUEAK_ENEMY_ATTACKING_KING)) {
                    m.lastSqueakRound = roundNum;
                }
            }
        }

        // === CAT FLEE (limited for attackers) ===
        if (shouldAttackerFlee(rc)) {
            runAttackerFlee(rc);
            rc.setIndicatorString("ATTACKER FLEE | " + m.state);
            return;
        }

        // === FIND ENEMY KING ===
        RobotInfo enemyKing = findEnemyKing(rc);
        if (enemyKing != null) {
            m.targetKingLoc = enemyKing.getLocation();
            m.turnsSinceKingSeen = 0;
            m.state = AttackState.ATTACK;

            // Squeak to summon allies!
            if (roundNum - m.lastSqueakRound >= SQUEAK_COOLDOWN) {
                // squeak() returns true if successful
                if (rc.squeak(SQUEAK_ENEMY_KING_FOUND)) {
                    m.lastSqueakRound = roundNum;
                }
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

        // === STATE MACHINE ===
        switch (m.state) {
            case SEEK:   runSeek(rc, m, roundNum); break;
            case ATTACK: runAttack(rc, m, roundNum); break;
            case GUARD:  runGuard(rc, m, roundNum); break;
            case SWARM:  runSwarm(rc, m, roundNum); break;
        }

        rc.setIndicatorString("ATK " + m.state +
                (m.bugging ? " BUG" + (m.bugLeft ? "L" : "R") : "") +
                " | Tgt: " + (m.targetKingLoc != null ? m.targetKingLoc : "none") +
                " | Swm: " + (m.swarmTarget != null ? m.swarmTarget : "none"));
    }

    // ==================== STATE IMPLEMENTATIONS ====================

    static void runSeek(RobotController rc, Mem m, int roundNum) throws GameActionException {
        MapLocation here = rc.getLocation();

        // If we have a swarm target, go there
        if (m.swarmTarget != null && roundNum - m.swarmStartRound < 30) {
            m.state = AttackState.SWARM;
            runSwarm(rc, m, roundNum);
            return;
        }

        // If we have a stale enemy king location, investigate it
        if (m.targetKingLoc != null) {
            attackerMoveToward(rc, m, m.targetKingLoc);
            return;
        }

        // Pick exploration target
        if (m.explorationTarget == null ||
                here.distanceSquaredTo(m.explorationTarget) <= 9 ||
                m.turnsAtTarget > MAX_TARGET_PURSUIT_TURNS) {
            m.explorationTarget = pickExplorationTarget(rc, m);
            m.turnsAtTarget = 0;
        }

        m.turnsAtTarget++;
        attackerMoveToward(rc, m, m.explorationTarget);
    }

    static void runAttack(RobotController rc, Mem m, int roundNum) throws GameActionException {
        if (m.targetKingLoc == null) {
            m.state = AttackState.SEEK;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation center = m.targetKingLoc;

        // === COUNT NEARBY ALLIES FOR GANG-UP ===
        int alliesNearby = countAlliesInRadius(rc, COORDINATED_ATTACK_RADIUS);
        boolean shouldGangUp = alliesNearby >= GANG_UP_THRESHOLD;

        // === TRY TO BITE ENEMY KING ===
        // King is 3x3, so check all 9 tiles
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation tile = new MapLocation(center.x + dx, center.y + dy);
                // Spend more cheese if we have allies (gang up!)
                int spend = shouldGangUp ? 
                    Math.min(BITE_CHEESE_BOOST * 2, rc.getRawCheese() + rc.getGlobalCheese() / 10) :
                    Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                if (rc.canAttack(tile, spend)) {
                    rc.attack(tile, spend);
                    return;
                }
            }
        }

        // === ALSO ATTACK ENEMY BABY RATS NEAR THEIR KING ===
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

        // === MOVE TOWARD TARGET ===
        attackerMoveToward(rc, m, m.targetKingLoc);
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
            // === PLACE DEFENSIVE TRAP BETWEEN ENEMY AND OUR KING ===
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

            // === ATTACK THE ENEMY ===
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(closestEnemy.getLocation(), spend)) {
                rc.attack(closestEnemy.getLocation(), spend);
                return;
            }

            // Move toward enemy to attack
            attackerMoveToward(rc, m, closestEnemy.getLocation());
            return;
        }

        // No nearby enemy rats - return to patrol near king or seek
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            attackerMoveToward(rc, m, m.kingLocation);
        } else {
            m.state = AttackState.SEEK;
        }
    }

    static void runSwarm(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Move toward swarm target
        if (m.swarmTarget == null || roundNum - m.swarmStartRound > 30) {
            // Swarm expired
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();

        // If we arrived at swarm target, look for enemy king
        if (here.distanceSquaredTo(m.swarmTarget) <= 16) {
            RobotInfo enemyKing = findEnemyKing(rc);
            if (enemyKing != null) {
                m.targetKingLoc = enemyKing.getLocation();
                m.state = AttackState.ATTACK;
                m.turnsSinceKingSeen = 0;
                return;
            }
            // No king found at swarm location - search briefly then give up
            if (roundNum - m.swarmStartRound > 15) {
                m.state = AttackState.SEEK;
                m.swarmTarget = null;
                return;
            }
        }

        // Attack any enemies we encounter on the way
        RobotInfo[] enemies = rc.senseNearbyRobots(8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
            if (rc.canAttack(enemy.getLocation(), spend)) {
                rc.attack(enemy.getLocation(), spend);
                break;
            }
        }

        attackerMoveToward(rc, m, m.swarmTarget);
    }

    // ==================== SQUEAK COMMUNICATION ====================

    static void processIncomingSqueaks(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Read all squeaks from the past 5 rounds using -1
        // Per spec: "message will have the messageContent integer, the robot's ID, 
        // current location at the time it squeaked, and the round number"
        // Message class likely has public fields: bytes/content, senderID, senderLocation/loc, round
        Message[] messages = rc.readSqueaks(-1);
        
        for (Message msg : messages) {
            // Access Message public methods
            // Available methods: getBytes(), getSource(), getSenderID(), getRound()
            int content = msg.getBytes();  // The messageContent integer
            MapLocation senderLoc = msg.getSource();  // Location at time of squeak
            
            // Skip if too far away (squeaks only travel sqrt(16) but we may want broader response)
            if (rc.getLocation().distanceSquaredTo(senderLoc) > SWARM_RESPONSE_DISTANCE_SQ) {
                continue;
            }

            switch (content) {
                case SQUEAK_ENEMY_KING_FOUND:
                    // Rush to help attack enemy king!
                    if (m.state == AttackState.SEEK || m.state == AttackState.SWARM) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_ENEMY_ATTACKING_KING:
                    // Our king is under attack - switch to guard!
                    if (m.state != AttackState.ATTACK) {
                        m.state = AttackState.GUARD;
                    }
                    break;

                case SQUEAK_NEED_BACKUP:
                    // General backup request
                    if (m.state == AttackState.SEEK) {
                        m.swarmTarget = senderLoc;
                        m.swarmStartRound = roundNum;
                        m.state = AttackState.SWARM;
                    }
                    break;

                case SQUEAK_RETREAT:
                    // Flee toward our king
                    if (m.kingLocation != null) {
                        m.explorationTarget = m.kingLocation;
                    }
                    break;
            }
        }
    }

    static void checkSharedSwarmTarget(RobotController rc, Mem m, int roundNum) throws GameActionException {
        // Check if king has broadcast a swarm target
        int swarmX = rc.readSharedArray(SWARM_TARGET_X);
        int swarmY = rc.readSharedArray(SWARM_TARGET_Y);
        int swarmRound = rc.readSharedArray(SWARM_ACTIVE_ROUND);

        if (swarmX > 0 && swarmY > 0) {
            // Check if swarm is recent (within 20 rounds)
            int roundDiff = (roundNum % 1000) - swarmRound;
            if (roundDiff < 0) roundDiff += 1000;
            
            if (roundDiff < 20 && m.state == AttackState.SEEK) {
                m.swarmTarget = new MapLocation(swarmX, swarmY);
                m.swarmStartRound = roundNum;
                m.state = AttackState.SWARM;
            }
        }
    }

    // ==================== TRAP PLACEMENT ====================

    static boolean tryPlaceOffensiveTrap(RobotController rc, Mem m, MapLocation enemyKingCenter) 
            throws GameActionException {
        MapLocation here = rc.getLocation();
        
        // Predict enemy king's retreat direction (toward their spawn)
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();
        
        // Estimate enemy spawn as opposite of our king
        MapLocation enemySpawn = null;
        if (m.kingLocation != null) {
            enemySpawn = new MapLocation(mapW - 1 - m.kingLocation.x, mapH - 1 - m.kingLocation.y);
        } else {
            enemySpawn = new MapLocation(mapW / 2, mapH / 2);
        }
        
        Direction retreatDir = enemyKingCenter.directionTo(enemySpawn);
        
        // Try to place trap in retreat path
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
        
        // Fallback: place trap adjacent to us toward enemy
        Direction toEnemy = here.directionTo(enemyKingCenter);
        MapLocation fallbackLoc = here.add(toEnemy);
        if (rc.canPlaceRatTrap(fallbackLoc)) {
            rc.placeRatTrap(fallbackLoc);
            return true;
        }
        
        return false;
    }

    // ==================== SENSING ====================

    static boolean shouldAttackerFlee(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(ATTACKER_CAT_FLEE_RADIUS);
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.CAT) return true;
        }
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
                int distToKing = r.getLocation().distanceSquaredTo(m.kingLocation);
                if (distToKing <= 100) { // Within 10 units of king
                    return r;
                }
            }
        }
        return null;
    }

    static int countAlliesInRadius(RobotController rc, int radiusSq) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(radiusSq, rc.getTeam());
        int count = 0;
        for (RobotInfo r : nearby) {
            if (r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }

    // ==================== EXPLORATION TARGETING ====================

    static MapLocation pickExplorationTarget(RobotController rc, Mem m) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        MapLocation here = rc.getLocation();

        // Head toward center first
        if (!m.hasReachedCenter && m.mapCenter != null && here.distanceSquaredTo(m.mapCenter) > 100) {
            return m.mapCenter;
        }
        m.hasReachedCenter = true;

        // Assume enemy king roughly symmetric to ours
        if (m.kingLocation != null) {
            int ex = w - 1 - m.kingLocation.x;
            int ey = h - 1 - m.kingLocation.y;

            int offX = m.rng.nextInt(11) - 5;
            int offY = m.rng.nextInt(11) - 5;

            ex = Math.max(3, Math.min(w - 4, ex + offX));
            ey = Math.max(3, Math.min(h - 4, ey + offY));

            return new MapLocation(ex, ey);
        }

        // Fallback: opposite corner from current position
        int tx = (here.x < w / 2) ? w - 5 : 5;
        int ty = (here.y < h / 2) ? h - 5 : 5;
        return new MapLocation(tx, ty);
    }

    // ==================== NAVIGATION (BUG + anti-oscillation) ====================

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

        // Anti-oscillation
        if ((m.prev1 != null && next.equals(m.prev1)) || (m.prev2 != null && next.equals(m.prev2))) {
            return false;
        }

        // Avoid occupied tiles (robots)
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