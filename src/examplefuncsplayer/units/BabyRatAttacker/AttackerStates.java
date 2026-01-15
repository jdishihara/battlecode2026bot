package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerSymmetry.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerNavigation.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerFormation.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCombat.*;
import static examplefuncsplayer.units.BabyRatAttacker.AttackerCommunication.*;

/**
 * State machine implementations for attacker rats.
 * Contains runSeek, runAttack, runGuard, and runSwarm.
 */
public class AttackerStates {

    // 8 cardinal/diagonal directions for swarm spread
    private static final Direction[] SWARM_OFFSETS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    private static final int SWARM_ENGAGE_RADIUS_SQ = 10;  // ~3 tile radius for counting

    public static void runSeek(RobotController rc, Mem m, int roundNum, int effectiveSymmetry,
                        RobotInfo[] nearbyAllies, RobotInfo[] nearbyAll) throws GameActionException {
        MapLocation here = rc.getLocation();

        // If we have a swarm target, go there
        if (m.swarmTarget != null && roundNum - m.swarmStartRound < 30) {
            m.state = AttackState.SWARM;
            runSwarm(rc, m, roundNum, nearbyAll);
            return;
        }

        // If we already know enemy king location, go there
        if (m.targetKingLoc != null) {
            m.currentTarget = m.targetKingLoc;
            formationMoveSeek(rc, m, m.targetKingLoc, roundNum, nearbyAllies);
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

        // Everyone uses the same target - keeps the group tight while traveling
        MapLocation moveTarget = symmetryTarget;

        m.currentTarget = moveTarget;

        // Check if we've reached the target area (within 3 tiles)
        if (distToTarget <= 9) {
            m.reachedCurrentTarget = true;
        }

        // Debug: show which symmetry we're actually heading to
        if (DEBUG) rc.setIndicatorLine(here, symmetryTarget, 255, 255, 0); // Yellow line to target

        // Use formation movement instead of direct movement
        formationMoveSeek(rc, m, moveTarget, roundNum, nearbyAllies);
    }

    public static void runAttack(RobotController rc, Mem m, int roundNum, RobotInfo[] nearbyAll,
                          RobotInfo[] nearbyAllies) throws GameActionException {
        if (m.targetKingLoc == null) {
            m.state = AttackState.SEEK;
            return;
        }

        MapLocation here = rc.getLocation();
        MapLocation center = m.targetKingLoc;
        Team enemyTeam = rc.getTeam().opponent();

        // === COUNT NEARBY ALLIES FOR GANG-UP (filter pre-sensed array) ===
        int alliesNearby = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.BABY_RAT &&
                here.distanceSquaredTo(ally.getLocation()) <= COORDINATED_ATTACK_RADIUS) {
                alliesNearby++;
            }
        }
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

        // === ALSO ATTACK ENEMY BABY RATS (filter pre-sensed array, dist² <= 8) ===
        for (RobotInfo r : nearbyAll) {
            if (r.getTeam() == enemyTeam && r.getType() == UnitType.BABY_RAT) {
                int distSq = here.distanceSquaredTo(r.getLocation());
                if (distSq <= 8) {
                    int spend = Math.min(BITE_CHEESE_BOOST, rc.getRawCheese());
                    if (rc.canAttack(r.getLocation(), spend)) {
                        rc.attack(r.getLocation(), spend);
                        return;
                    }
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

        // === MOVE TOWARD TARGET (solo mode bulldoze - no allies requirement) ===
        m.currentTarget = m.targetKingLoc;
        attackerMoveToward(rc, m, m.targetKingLoc, roundNum);
    }

    public static void runGuard(RobotController rc, Mem m, int roundNum, RobotInfo[] nearbyAll)
            throws GameActionException {
        MapLocation here = rc.getLocation();
        Team enemyTeam = rc.getTeam().opponent();

        // Find and attack nearby enemy rats (filter pre-sensed array, dist² <= 20)
        RobotInfo closestEnemy = null;
        int closestDist = Integer.MAX_VALUE;

        for (RobotInfo r : nearbyAll) {
            if (r.getTeam() == enemyTeam && r.getType() == UnitType.BABY_RAT) {
                int dist = here.distanceSquaredTo(r.getLocation());
                if (dist <= 20 && dist < closestDist) {
                    closestDist = dist;
                    closestEnemy = r;
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
            attackerMoveToward(rc, m, closestEnemy.getLocation(), roundNum);
            return;
        }

        // No enemies - return to king or resume seeking
        if (m.kingLocation != null && here.distanceSquaredTo(m.kingLocation) > 50) {
            m.currentTarget = m.kingLocation;
            attackerMoveToward(rc, m, m.kingLocation, roundNum);
        } else {
            m.state = AttackState.SEEK;
        }
    }

    public static void runSwarm(RobotController rc, Mem m, int roundNum, RobotInfo[] nearbyAll)
            throws GameActionException {
        // Check if local swarm target has expired
        if (m.localSwarmTarget != null && roundNum > m.localSwarmUntilRound) {
            m.localSwarmTarget = null;
        }

        // Prefer local swarm target (from MSG_HELP) if still active, else global alert rally
        MapLocation effectiveTarget = m.localSwarmTarget != null ? m.localSwarmTarget :
                                      (m.alertRallyLoc != null ? m.alertRallyLoc : m.swarmTarget);

        if (effectiveTarget == null || roundNum - m.swarmStartRound > 30) {
            m.state = AttackState.SEEK;
            m.swarmTarget = null;
            m.localSwarmTarget = null;
            return;
        }

        MapLocation here = rc.getLocation();
        Team myTeam = rc.getTeam();
        Team enemyTeam = myTeam.opponent();

        // === FILTER PRE-SENSED ARRAY: Count allies vs enemies + check for enemy king ===
        // Filter for dist² <= SWARM_ENGAGE_RADIUS_SQ (10)
        int allyCount = 0;
        int enemyCount = 0;
        RobotInfo closestEnemy = null;
        RobotInfo enemyKing = null;
        int closestEnemyDistSq = Integer.MAX_VALUE;

        for (RobotInfo r : nearbyAll) {
            int distSq = here.distanceSquaredTo(r.getLocation());
            if (distSq > SWARM_ENGAGE_RADIUS_SQ) continue;  // Filter by distance

            if (r.getTeam() == enemyTeam) {
                if (r.getType() == UnitType.RAT_KING) {
                    enemyKing = r;  // Priority target
                } else if (r.getType() == UnitType.BABY_RAT) {
                    enemyCount++;
                    if (distSq < closestEnemyDistSq) {
                        closestEnemyDistSq = distSq;
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
            m.state = AttackState.ATTACK;
            m.turnsSinceKingSeen = 0;
            confirmSymmetryFromKingLocation(rc, m, enemyKing.getLocation());
            return;
        }

        // If arrived at target area and no king found after a while, go back to seek
        int distToTarget = here.distanceSquaredTo(effectiveTarget);
        if (distToTarget <= 16 && roundNum - m.swarmStartRound > 15) {
            m.state = AttackState.SEEK;
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

            // Fill reusable buffer: away, then angled toward rally
            m.buf6[0] = awayFromEnemy;
            m.buf6[1] = awayFromEnemy.rotateLeft();
            m.buf6[2] = awayFromEnemy.rotateRight();
            m.buf6[3] = toRally;
            m.buf6[4] = toRally.rotateLeft();
            m.buf6[5] = toRally.rotateRight();

            for (int i = 0; i < 6; i++) {
                Direction d = m.buf6[i];
                if (d == Direction.CENTER) continue;
                // Let canMove handle occupancy check (saves bytecode from senseRobotAtLocation)
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
        attackerMoveToward(rc, m, spreadTarget, roundNum);
    }
}
