package defaultbot.units.BabyRatAttacker;

import battlecode.common.*;

import static examplefuncsplayer.units.BabyRatAttacker.AttackerState.*;

/**
 * Cat-aware navigation scoring and direction selection for attacker rats.
 */
public class AttackerNavigation {

    /**
     * Score a direction for cat-aware movement.
     * Higher score = better direction. Considers both target progress and cat avoidance.
     *
     * @param here Current location
     * @param dir Direction to evaluate
     * @param target Target location
     * @param catLoc Cat location (can be null)
     * @param catBand Current cat distance band
     * @return Score (higher is better), or Integer.MIN_VALUE if direction is invalid
     */
    public static int scoreCatAwareDir(MapLocation here, Direction dir, MapLocation target,
                                        MapLocation catLoc, int catBand) {
        if (dir == null || dir == Direction.CENTER) return Integer.MIN_VALUE;

        MapLocation next = here.add(dir);
        int score = 0;

        // Base score: progress toward target (lower dist = higher score)
        int currentDistSq = here.distanceSquaredTo(target);
        int nextDistSq = next.distanceSquaredTo(target);
        score += (currentDistSq - nextDistSq) * 10;  // +10 per distSq closer

        // Cat avoidance: penalize getting closer to cat
        if (catLoc != null && catBand > CAT_BAND_NONE) {
            int currentCatDistSq = here.distanceSquaredTo(catLoc);
            int nextCatDistSq = next.distanceSquaredTo(catLoc);

            // DANGER band: treat cat as soft obstacle
            if (catBand == CAT_BAND_DANGER) {
                // Hard penalty for entering pounce range
                if (nextCatDistSq <= CAT_PANIC_DIST_SQ) {
                    score -= 200;
                }
                // Heavy penalty for decreasing distance to cat
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 60;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 30;
                }
                // Lateral bonus: prefer moves perpendicular to cat direction
                Direction toCat = here.directionTo(catLoc);
                if (toCat != Direction.CENTER) {
                    Direction leftPerp = toCat.rotateLeft().rotateLeft();
                    Direction rightPerp = toCat.rotateRight().rotateRight();
                    if (dir == leftPerp || dir == rightPerp) {
                        score += 40;  // Lateral move bonus
                    } else if (dir == leftPerp.rotateLeft() || dir == rightPerp.rotateRight()) {
                        score += 20;  // Diagonal-lateral bonus
                    }
                }
            } else {
                // CAUTION band: mild steering
                if (nextCatDistSq < currentCatDistSq) {
                    score -= 15;
                } else if (nextCatDistSq > currentCatDistSq) {
                    score += 8;
                }
            }
        }

        return score;
    }

    /**
     * Reorder direction array based on cat-aware scoring.
     * Returns a new array with directions sorted by score (best first).
     * Reuses the input array to avoid allocation.
     */
    public static void sortDirsByCatScore(Direction[] dirs, MapLocation here, MapLocation target,
                                           MapLocation catLoc, int catBand) {
        // Simple insertion sort (small array, low bytecode)
        for (int i = 1; i < dirs.length; i++) {
            Direction key = dirs[i];
            int keyScore = scoreCatAwareDir(here, key, target, catLoc, catBand);
            int j = i - 1;
            while (j >= 0 && scoreCatAwareDir(here, dirs[j], target, catLoc, catBand) < keyScore) {
                dirs[j + 1] = dirs[j];
                j--;
            }
            dirs[j + 1] = key;
        }
    }
}
