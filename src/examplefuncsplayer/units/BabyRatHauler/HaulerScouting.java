package examplefuncsplayer.units.BabyRatHauler;

import battlecode.common.*;

import examplefuncsplayer.units.BabyRatHauler.HaulerMemory.Mem;
import examplefuncsplayer.units.BabyRatHauler.HaulerState.ThreatInfo;

import static examplefuncsplayer.units.BabyRatHauler.HaulerNavigation.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerMovement.*;
import static examplefuncsplayer.units.BabyRatHauler.HaulerThreat.*;

/**
 * Scouting behavior for hauler rats.
 * Ensures rats explore until enough mines are discovered.
 */
public class HaulerScouting {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - Tune these values for your strategy
    // ═══════════════════════════════════════════════════════════════════════════

    /** Minimum number of mines we want to know about before all rats become haulers */
    public static final int TARGET_MINE_COUNT = 3;

    /** Maximum distance squared from king to consider a mine "usable" */
    public static final int MAX_MINE_DISTANCE_SQ = 900;  // 30 tiles

    /** What fraction of rats should scout when we need more mines (1 in N rats) */
    public static final int SCOUT_RATIO = 10;  // 1 in 10 rats scout - most should be hauling

    /** After this many rounds, stop scouting and just use what we have */
    public static final int MAX_SCOUT_ROUNDS = 300;

    /** Minimum rounds a scout should explore before switching back */
    public static final int MIN_SCOUT_DURATION = 30;

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOUT ROLE DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determine if this rat should be scouting for new mines.
     *
     * A rat should scout if:
     * 1. We haven't found enough mines yet (< TARGET_MINE_COUNT)
     * 2. This rat is designated as a scout (by ID)
     * 3. We're not too late in the game
     * 4. The rat isn't currently carrying cheese
     */
    public static boolean shouldBeScout(RobotController rc, Mem m) throws GameActionException {
        // Never scout if carrying cheese - deliver it first
        if (rc.getRawCheese() > 0) {
            return false;
        }

        // Stop scouting after a certain round
        if (rc.getRoundNum() > MAX_SCOUT_ROUNDS) {
            return false;
        }

        // Count usable mines (within range of king)
        int usableMines = countUsableMines(rc, m);

        // If we have enough mines, no need to scout
        if (usableMines >= TARGET_MINE_COUNT) {
            return false;
        }

        // Designate scouts by ID - ensures consistent assignment
        // Lower IDs tend to be older rats, so they've had more time to find mines
        boolean isDesignatedScout = (rc.getID() % SCOUT_RATIO) == 0;

        return isDesignatedScout;
    }

    /**
     * Count how many known mines are within usable distance of the king.
     */
    public static int countUsableMines(RobotController rc, Mem m) {
        if (m.kingLocation == null) {
            return m.knownMineCount;  // Can't filter by distance without king location
        }

        int count = 0;
        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation mine = m.knownMines[i];
            if (mine != null) {
                int distSq = m.kingLocation.distanceSquaredTo(mine);
                if (distSq <= MAX_MINE_DISTANCE_SQ) {
                    count++;
                }
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOUT BEHAVIOR
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute scouting behavior for one turn.
     * Returns true if the scout took an action.
     */
    public static boolean doScoutTurn(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Always scan for mines while scouting
        boolean foundNew = HaulerSensing.scanAndCacheMines(rc, m, nearby);

        // If we found a new mine, maybe squeak about it
        if (foundNew && m.knownMineCount > 0) {
            MapLocation newestMine = m.knownMines[m.knownMineCount - 1];
            if (newestMine != null) {
                reportMineViaSqueaks(rc, m, newestMine);
            }
        }

        // Pick or update exploration target
        if (m.scoutTarget == null || here.distanceSquaredTo(m.scoutTarget) <= 9) {
            m.scoutTarget = pickScoutTarget(rc, m);
            m.scoutTurnsAtTarget = 0;
        }

        m.scoutTurnsAtTarget++;

        // If stuck at target too long, pick new one
        if (m.scoutTurnsAtTarget > 40) {
            m.scoutTarget = pickScoutTarget(rc, m);
            m.scoutTurnsAtTarget = 0;
        }

        // Move toward scout target
        if (m.scoutTarget != null) {
            ThreatInfo threat = computeThreat(rc, m);
            return intelligentMoveToward(rc, m, m.scoutTarget, nearby, threat);
        }

        return false;
    }

    /**
     * Pick a target location for scouting that maximizes chance of finding new mines.
     * Prioritizes areas far from known mines.
     */
    public static MapLocation pickScoutTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        // Generate candidate positions spread across the map
        MapLocation[] candidates = new MapLocation[12];

        // Corners and edges (mines often spawn symmetrically)
        candidates[0] = new MapLocation(w / 4, h / 4);
        candidates[1] = new MapLocation(3 * w / 4, h / 4);
        candidates[2] = new MapLocation(w / 4, 3 * h / 4);
        candidates[3] = new MapLocation(3 * w / 4, 3 * h / 4);

        // Mid-edges
        candidates[4] = new MapLocation(w / 2, h / 6);
        candidates[5] = new MapLocation(w / 2, 5 * h / 6);
        candidates[6] = new MapLocation(w / 6, h / 2);
        candidates[7] = new MapLocation(5 * w / 6, h / 2);

        // Center area variations
        candidates[8] = new MapLocation(w / 3, h / 2);
        candidates[9] = new MapLocation(2 * w / 3, h / 2);
        candidates[10] = new MapLocation(w / 2, h / 3);
        candidates[11] = new MapLocation(w / 2, 2 * h / 3);

        // Score each candidate
        MapLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MapLocation candidate : candidates) {
            if (candidate == null) continue;

            int score = scoreScoutTarget(rc, m, candidate, here);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        // Fallback to random exploration if no good candidate
        if (best == null) {
            best = pickExplorationAwayFromKnownMines(rc, m);
        }

        return best;
    }

    /**
     * Score a potential scout target.
     * Higher score = better target.
     */
    private static int scoreScoutTarget(RobotController rc, Mem m, MapLocation target, MapLocation here) {
        int score = 0;

        // Reward distance from known mines (we want to find NEW mines)
        int minDistToKnownMine = Integer.MAX_VALUE;
        for (int i = 0; i < m.knownMineCount; i++) {
            MapLocation mine = m.knownMines[i];
            if (mine != null) {
                int d = target.distanceSquaredTo(mine);
                if (d < minDistToKnownMine) {
                    minDistToKnownMine = d;
                }
            }
        }
        if (minDistToKnownMine != Integer.MAX_VALUE) {
            score += minDistToKnownMine / 10;  // Farther from known mines = better
        } else {
            score += 100;  // No known mines, any target is good
        }

        // Penalty for being too far from current position (efficiency)
        int distFromHere = here.distanceSquaredTo(target);
        score -= distFromHere / 50;

        // Penalty for being too far from king (safety)
        if (m.kingLocation != null) {
            int distFromKing = m.kingLocation.distanceSquaredTo(target);
            if (distFromKing > MAX_MINE_DISTANCE_SQ) {
                score -= 50;  // Penalize targets too far from king
            }
        }

        // Bonus for targets we haven't visited recently
        if (m.lastScoutTarget != null && target.distanceSquaredTo(m.lastScoutTarget) < 100) {
            score -= 30;  // Penalize revisiting same area
        }

        return score;
    }

    /**
     * Report a mine location to the king via squeaks.
     * Uses a 2-squeak protocol: first squeak encodes X, second encodes Y.
     */
    public static void reportMineViaSqueaks(RobotController rc, Mem m, MapLocation mine) throws GameActionException {
        // Don't spam squeaks - wait between reports
        if (rc.getRoundNum() - m.lastMineReportRound < 10) return;

        // Use message format: 800 + x for X coordinate, 900 + y for Y coordinate
        // This turn: send X
        int xMsg = 800 + mine.x;
        rc.squeak(xMsg);

        // Store that we need to send Y next turn
        m.pendingMineReportY = mine.y;
        m.lastMineReportRound = rc.getRoundNum();
    }

    /**
     * Continue a mine report (send Y coordinate if we sent X last turn).
     */
    public static void continueMineReport(RobotController rc, Mem m) throws GameActionException {
        if (m.pendingMineReportY >= 0) {
            int yMsg = 900 + m.pendingMineReportY;
            rc.squeak(yMsg);
            m.pendingMineReportY = -1;  // Clear pending
        }
    }
}
