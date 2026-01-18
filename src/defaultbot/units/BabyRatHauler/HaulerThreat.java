package defaultbot.units.BabyRatHauler;

import battlecode.common.*;

import defaultbot.units.BabyRatHauler.HaulerMemory.Mem;
import defaultbot.units.BabyRatHauler.HaulerState.ThreatInfo;
import defaultbot.units.BabyRatHauler.HaulerState.ThreatLevel;

import static defaultbot.config.Constants.*;

/**
 * Threat computation for hauler rats - handles cats and enemy rats.
 */
public class HaulerThreat {

    /**
     * Compute threat level from nearest cat AND enemy rats.
     * Returns ThreatInfo with level and source location.
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
                if (d < enemyDistSq && d <= ENEMY_FLEE_RADIUS * ENEMY_FLEE_RADIUS) {
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

            // NEAR: cat within flee radius (only if actually within the radius)
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
}
