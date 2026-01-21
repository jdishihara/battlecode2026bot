package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;
import examplefuncsplayer.units.HaulerDefender.DefenderState.ThreatInfo;
import examplefuncsplayer.units.HaulerDefender.DefenderState.ThreatLevel;

import static examplefuncsplayer.config.Constants.*;

/**
 * Threat computation for HaulerDefender rats.
 *
 * SIMPLIFIED DEFENSE LOGIC:
 * - DEFEND triggers when: within 10 tiles of king AND enemy rat within 8 tiles of king
 * - Always collect cheese UNLESS enemy rats are threatening king
 */
public class DefenderThreat {

    // Distance from king within which hauler will defend (10 tiles)
    private static final int DEFEND_NEAR_KING_RADIUS_SQ = 100; // 10 tiles squared

    // Enemy must be within this distance of king to trigger defense (8 tiles)
    private static final int ENEMY_THREATENING_KING_RADIUS_SQ = 64; // 8 tiles squared

    // Cat distance threshold - if cat is closer than this, always flee (never defend vs cats)
    private static final int CAT_PANIC_DIST_SQ = 36; // 6 tiles

    /**
     * Compute threat level from nearest cat AND enemy rats.
     * Returns ThreatInfo with level, source location, and defense flag.
     *
     * DEFENSE LOGIC (simplified):
     * - Defend if: within 10 tiles of king AND enemy rat within 8 tiles of king
     * - Never defend if cat is too close (flee from cats)
     */
    public static ThreatInfo computeThreat(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Sense once with max radius to minimize bytecode
        final int MAX_SENSE_RADIUS = Math.max(CAT_FLEE_RADIUS + 3, ENEMY_FLEE_RADIUS);
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(MAX_SENSE_RADIUS);

        // Find nearest cat and enemy in a single loop
        Team myTeam = rc.getTeam();
        RobotInfo nearestCat = null, nearestEnemy = null;
        int catDistSq = Integer.MAX_VALUE, enemyDistSq = Integer.MAX_VALUE;

        for (RobotInfo r : nearbyRobots) {
            MapLocation loc = r.getLocation();
            int d = here.distanceSquaredTo(loc);

            if (r.getType() == UnitType.CAT) {
                if (d < catDistSq) { catDistSq = d; nearestCat = r; }
            } else if (r.getTeam() != myTeam &&
                       (r.getType() == UnitType.BABY_RAT || r.getType() == UnitType.RAT_KING)) {
                if (d < enemyDistSq) {
                    enemyDistSq = d; nearestEnemy = r;
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
            if (catThreat == ThreatLevel.NONE && catDistSq <= CAT_FLEE_RADIUS * CAT_FLEE_RADIUS) {
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
            else if (enemyDistSq <= ENEMY_FLEE_RADIUS * ENEMY_FLEE_RADIUS) {
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

        // ===== SIMPLIFIED DEFENSE DETERMINATION =====
        // Defend if:
        // 1. We're within 10 tiles of our king
        // 2. Enemy rat is within 8 tiles of our king
        // 3. No cat is dangerously close (cat takes priority for fleeing)
        boolean shouldDefendKing = false;
        MapLocation enemyToAttack = null;

        if (nearestEnemy != null && m.kingLocation != null) {
            int distToKingSq = here.distanceSquaredTo(m.kingLocation);
            int enemyDistToKingSq = nearestEnemy.getLocation().distanceSquaredTo(m.kingLocation);

            boolean weAreNearKing = distToKingSq <= DEFEND_NEAR_KING_RADIUS_SQ;  // within 10 tiles
            boolean enemyThreateningKing = enemyDistToKingSq <= ENEMY_THREATENING_KING_RADIUS_SQ;  // within 8 tiles
            boolean catNotDangerous = (nearestCat == null || catDistSq > CAT_PANIC_DIST_SQ);

            if (weAreNearKing && enemyThreateningKing && catNotDangerous) {
                shouldDefendKing = true;
                enemyToAttack = nearestEnemy.getLocation();
            }
        }

        return new ThreatInfo(finalLevel, finalSource, shouldDefendKing, enemyToAttack);
    }

    /**
     * Find the best enemy rat to attack when defending the king.
     * Prioritizes: low health enemies, enemies closest to king, enemies we can actually reach.
     */
    public static RobotInfo findBestAttackTarget(RobotController rc, Mem m) throws GameActionException {
        if (m.kingLocation == null) return null;

        MapLocation here = rc.getLocation();
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(ENEMY_FLEE_RADIUS);
        Team enemyTeam = rc.getTeam().opponent();

        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;

        for (RobotInfo r : nearbyRobots) {
            if (r.getTeam() != enemyTeam) continue;
            if (r.getType() != UnitType.BABY_RAT && r.getType() != UnitType.RAT_KING) continue;

            MapLocation enemyLoc = r.getLocation();
            int distToUs = here.distanceSquaredTo(enemyLoc);
            int distToKing = enemyLoc.distanceSquaredTo(m.kingLocation);

            // Score: prioritize low health, close to us, close to king
            int score = 0;
            score += (100 - r.getHealth()); // Lower health = higher priority
            score -= distToUs * 2;           // Closer to us = higher priority
            score -= distToKing;             // Closer to king = higher priority (threatening)

            // Bonus for adjacent enemies (can attack immediately)
            if (distToUs <= 2) {
                score += 50;
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = r;
            }
        }

        return bestTarget;
    }
}
