package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatLevel;

import static examplefuncsplayer.config.Constants.*;

/**
 * Threat computation for hauler rats - handles cats and enemy rats.
 */
public class HaulerThreat {

    /**
     * Check if an enemy rat is facing away from us (can't see us).
     * Baby rats have a 90° vision cone facing forward.
     * Returns true if we are OUTSIDE their vision cone.
     */
    public static boolean isEnemyFacingAway(MapLocation ourLoc, RobotInfo enemy) {
        if (enemy == null) return false;

        MapLocation enemyLoc = enemy.getLocation();
        Direction enemyFacing = enemy.getDirection();

        // Baby rats have 90° cone = facing direction ± 45°
        Direction toUs = enemyLoc.directionTo(ourLoc);

        // If toUs matches enemyFacing or its immediate rotations, we're in their cone
        if (toUs == enemyFacing) return false;
        if (toUs == enemyFacing.rotateLeft()) return false;
        if (toUs == enemyFacing.rotateRight()) return false;

        // We're outside their vision cone - they can't see us!
        return true;
    }

    /**
     * Compute threat level from nearest cat AND enemy rats.
     * Returns ThreatInfo with level and source location.
     * Also identifies attackable enemies (HP advantage or facing away).
     */
    public static ThreatInfo computeThreat(RobotController rc, Mem m) throws GameActionException {
        MapLocation here = rc.getLocation();
        int myHP = rc.getHealth();

        // Sense once with max radius to minimize bytecode
        final int MAX_SENSE_RADIUS = Math.max(CAT_FLEE_RADIUS + 3, ENEMY_FLEE_RADIUS);
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(MAX_SENSE_RADIUS);

        // Find nearest cat and enemy in a single loop
        Team myTeam = rc.getTeam();
        RobotInfo nearestCat = null, nearestEnemy = null;
        int catDistSq = Integer.MAX_VALUE, enemyDistSq = Integer.MAX_VALUE;

        // Track best attackable enemy (we have advantage over)
        RobotInfo bestAttackTarget = null;
        int bestAttackScore = Integer.MIN_VALUE;
        boolean bestTargetFacingAway = false;

        // Count allies for swarming decision
        int nearbyAllies = 0;

        for (RobotInfo r : nearbyRobots) {
            MapLocation loc = r.getLocation();
            int d = here.distanceSquaredTo(loc);

            if (r.getType() == UnitType.CAT) {
                if (d < catDistSq) { catDistSq = d; nearestCat = r; }
            } else if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) {
                // Count allies
                nearbyAllies++;
            } else if (r.getTeam() != myTeam &&
                       (r.getType() == UnitType.BABY_RAT || r.getType() == UnitType.RAT_KING)) {
                if (d < enemyDistSq && d <= ENEMY_FLEE_RADIUS * ENEMY_FLEE_RADIUS) {
                    enemyDistSq = d; nearestEnemy = r;
                }

                // Check if this enemy is attackable (we have advantage)
                if (r.getType() == UnitType.BABY_RAT) {
                    int enemyHP = r.getHealth();
                    boolean facingAway = isEnemyFacingAway(here, r);

                    // Score this target - higher = better to attack
                    int score = 0;

                    // HP advantage: we have more HP than them
                    if (myHP > enemyHP) {
                        score += 100 + (myHP - enemyHP); // Base score + HP difference
                    }

                    // Facing away: free hits, no retaliation
                    if (facingAway) {
                        score += 200; // Very high priority - guaranteed safe attack
                    }

                    // Low HP enemy: quick kill
                    if (enemyHP <= LOW_HP_THRESHOLD) {
                        score += 150; // Priority kill target
                    }

                    // Closer enemies are better targets
                    score -= d; // Penalize distance

                    if (score > bestAttackScore) {
                        bestAttackScore = score;
                        bestAttackTarget = r;
                        bestTargetFacingAway = facingAway;
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
                // Only panic if enemy has HP advantage or is a rat king
                int enemyHP = nearestEnemy.getHealth();
                if (enemyHP >= myHP || nearestEnemy.getType() == UnitType.RAT_KING) {
                    enemyThreat = ThreatLevel.PANIC;
                } else {
                    // We have HP advantage - treat as NEAR, not PANIC (stay and fight)
                    enemyThreat = ThreatLevel.NEAR;
                }
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

        ThreatInfo result = new ThreatInfo(finalLevel, finalSource);

        // Populate attackable enemy info for ATTACK state decisions
        if (bestAttackTarget != null && bestAttackScore > 0) {
            result.attackableEnemy = bestAttackTarget.getLocation();
            result.attackableEnemyHP = bestAttackTarget.getHealth();
            result.attackableEnemyId = bestAttackTarget.getID();
            result.enemyFacingAway = bestTargetFacingAway;
        }
        result.nearbyAllies = nearbyAllies;

        return result;
    }
}
