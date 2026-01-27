package examplefuncsplayer.units.BabyRatAttacker;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatAttacker.AttackerMemory.Mem;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;

/**
 * Target selection and prioritization for attacker rats.
 *
 * Priority system ensures attackers focus fire on the same targets:
 * 1. Critical HP enemies (guaranteed kill with one bite)
 * 2. Low HP enemies (high chance of kill)
 * 3. Focus targets from ally squeaks
 * 4. Enemies near our king (defensive priority)
 * 5. Closest enemy (fallback)
 *
 * This maximizes kill efficiency and minimizes wasted damage.
 */
public class AttackerTargeting {

    /**
     * Result class for target selection - contains target info and priority score.
     */
    public static class TargetResult {
        public final RobotInfo target;
        public final int priorityScore;
        public final String reason;  // For debugging

        public TargetResult(RobotInfo target, int priorityScore, String reason) {
            this.target = target;
            this.priorityScore = priorityScore;
            this.reason = reason;
        }
    }

    /**
     * Select the highest priority target from nearby enemies.
     * Uses a scoring system to ensure consistent target selection across rats.
     *
     * @param rc RobotController
     * @param m Memory state (for focus target info and king location)
     * @param nearbyEnemies Pre-sensed nearby enemy robots
     * @param roundNum Current round number
     * @return TargetResult with best target, or null if no valid targets
     */
    public static TargetResult selectPriorityTarget(RobotController rc, Mem m,
                                                     RobotInfo[] nearbyEnemies, int roundNum)
            throws GameActionException {

        if (nearbyEnemies == null || nearbyEnemies.length == 0) {
            return null;
        }

        MapLocation here = rc.getLocation();
        RobotInfo bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        String bestReason = "";

        // Check if our focus target is still valid
        boolean focusTargetExpired = (roundNum - m.focusTargetRound) > FOCUS_FIRE_TTL;
        if (focusTargetExpired) {
            m.focusTargetLoc = null;
            m.focusTargetID = -1;
        }

        for (RobotInfo enemy : nearbyEnemies) {
            // Skip cats - they're handled separately
            if (enemy.getType() == UnitType.CAT) continue;

            MapLocation enemyLoc = enemy.getLocation();
            int distSq = here.distanceSquaredTo(enemyLoc);
            int enemyHP = enemy.getHealth();
            int enemyID = enemy.getID();

            int score = 0;
            String reason = "";

            // === PRIORITY 1: Critical HP (guaranteed kill) ===
            if (enemyHP <= FOCUS_CRITICAL_HP) {
                score += PRIORITY_CRITICAL_HP;
                reason = "CRIT_HP";
            }
            // === PRIORITY 2: Low HP (high priority) ===
            else if (enemyHP <= FOCUS_LOW_HP_THRESHOLD) {
                score += PRIORITY_LOW_HP;
                reason = "LOW_HP";
            }

            // === PRIORITY 3: Enemy Rat King (high value target) ===
            if (enemy.getType() == UnitType.RAT_KING) {
                score += PRIORITY_ENEMY_KING;
                reason = reason.isEmpty() ? "ENEMY_KING" : reason + "+KING";
            }

            // === PRIORITY 4: Focus target from allies ===
            if (m.focusTargetLoc != null && enemyLoc.distanceSquaredTo(m.focusTargetLoc) <= 2) {
                // This enemy is at or very near the focus target location
                score += PRIORITY_FOCUS_TARGET;
                reason = reason.isEmpty() ? "FOCUS" : reason + "+FOCUS";

                // Update focus target info if this is likely the same enemy
                if (m.focusTargetID == -1 || m.focusTargetID == enemyID) {
                    m.focusTargetID = enemyID;
                    m.focusTargetHP = enemyHP;
                }
            }

            // === PRIORITY 5: Enemy near our king (defensive) ===
            if (m.kingLocation != null) {
                int distToOurKing = enemyLoc.distanceSquaredTo(m.kingLocation);
                if (distToOurKing <= 64) {  // Within 8 tiles of our king
                    score += PRIORITY_NEAR_OUR_KING;
                    reason = reason.isEmpty() ? "NEAR_KING" : reason + "+NEAR_KING";
                }
            }

            // === BASE: Distance scoring (closer = better, but less important) ===
            // Invert distance so closer enemies get higher scores
            // Max distance in vision is ~20 units (distSq ~400)
            int distanceBonus = Math.max(0, PRIORITY_CLOSEST - (distSq / 2));
            score += distanceBonus;

            // Tie-breaker: prefer lower HP at same priority
            // Small bonus (0-9) based on HP, lower HP = higher bonus
            int hpBonus = Math.max(0, 10 - (enemyHP / 10));
            score += hpBonus;

            // Tie-breaker: consistent ordering by ID (ensures all rats pick same target)
            // Subtract a tiny amount based on ID to ensure consistent selection
            score -= (enemyID % 100) / 1000.0;

            if (reason.isEmpty()) {
                reason = "DIST";
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = enemy;
                bestReason = reason;
            }
        }

        if (bestTarget == null) {
            return null;
        }

        return new TargetResult(bestTarget, bestScore, bestReason);
    }

    /**
     * Check if we should update our focus target based on a newly found target.
     * Called after selectPriorityTarget to decide if we should broadcast.
     *
     * @param m Memory state
     * @param newTarget The newly selected target
     * @param roundNum Current round
     * @return true if we should broadcast this as a new focus target
     */
    public static boolean shouldBroadcastFocusTarget(Mem m, TargetResult newTarget, int roundNum) {
        if (newTarget == null || newTarget.target == null) return false;

        int newHP = newTarget.target.getHealth();

        // Always broadcast critical HP targets
        if (newHP <= FOCUS_CRITICAL_HP) return true;

        // Broadcast low HP targets if we don't have a current focus
        if (newHP <= FOCUS_LOW_HP_THRESHOLD) {
            if (m.focusTargetLoc == null) return true;

            // Or if our current focus is stale
            if ((roundNum - m.focusTargetRound) > FOCUS_FIRE_TTL / 2) return true;

            // Or if this target is lower HP than our current focus
            if (m.focusTargetHP > 0 && newHP < m.focusTargetHP) return true;
        }

        return false;
    }

    /**
     * Calculate optimal cheese to spend on a bite based on target HP and allies nearby.
     * Uses the damage formula: 10 + ceil(sqrt(cheese))
     *
     * @param targetHP Target's current HP
     * @param alliesNearby Number of allies who might also attack
     * @param availableCheese Our available cheese (raw + global portion)
     * @return Optimal cheese to spend
     */
    public static int calculateOptimalCheeseSpend(int targetHP, int alliesNearby, int availableCheese) {
        // Base damage is 10
        int baseDamage = 10;

        // If we have allies, assume they'll also do base damage
        int expectedAllyDamage = alliesNearby * baseDamage;

        // HP we need to deal ourselves
        int hpToDeal = Math.max(0, targetHP - expectedAllyDamage);

        if (hpToDeal <= baseDamage) {
            // Base damage is enough, no need to spend cheese
            return 0;
        }

        // Calculate minimum cheese needed for the kill
        // We need: 10 + ceil(sqrt(cheese)) >= hpToDeal
        // So: ceil(sqrt(cheese)) >= hpToDeal - 10
        // So: cheese >= (hpToDeal - 10)^2
        int bonusNeeded = hpToDeal - baseDamage;
        int cheeseNeeded = bonusNeeded * bonusNeeded;

        // Cap at available cheese, but also don't overspend
        // Use a reasonable cap to avoid wasting cheese on overkill
        int maxSpend = Math.min(availableCheese, 50);  // Cap at 50 cheese per bite

        return Math.min(cheeseNeeded, maxSpend);
    }

    /**
     * Count allies attacking the same target location.
     * Used to determine if focus fire is working.
     *
     * @param rc RobotController
     * @param targetLoc Target location to check
     * @param nearbyAllies Pre-sensed nearby allies
     * @return Number of allies adjacent to the target
     */
    public static int countAlliesNearTarget(RobotController rc, MapLocation targetLoc,
                                            RobotInfo[] nearbyAllies) {
        int count = 0;
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() != UnitType.BABY_RAT) continue;
            if (ally.getLocation().distanceSquaredTo(targetLoc) <= FOCUS_ATTACK_DIST_SQ) {
                count++;
            }
        }
        return count;
    }

    /**
     * Clear expired focus target from memory.
     * Called at the start of each turn.
     */
    public static void clearExpiredFocusTarget(Mem m, int roundNum) {
        if (m.focusTargetLoc != null && (roundNum - m.focusTargetRound) > FOCUS_FIRE_TTL) {
            m.focusTargetLoc = null;
            m.focusTargetID = -1;
            m.focusTargetHP = 0;
        }
    }
}
