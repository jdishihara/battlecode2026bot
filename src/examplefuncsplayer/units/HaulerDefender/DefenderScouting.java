package examplefuncsplayer.units.HaulerDefender;

import battlecode.common.*;

import examplefuncsplayer.units.HaulerDefender.DefenderMemory.Mem;
import examplefuncsplayer.units.HaulerDefender.DefenderState.ThreatInfo;

import static examplefuncsplayer.units.HaulerDefender.DefenderNavigation.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderMovement.*;
import static examplefuncsplayer.units.HaulerDefender.DefenderThreat.*;

/**
 * Scouting behavior for HaulerDefender rats.
 * Copied from defaultbot/units/BabyRatHauler/HaulerScouting.java
 */
public class DefenderScouting {

    public static final int TARGET_MINE_COUNT = 3;
    public static final int MAX_MINE_DISTANCE_SQ = 900;
    public static final int SCOUT_RATIO = 3;
    public static final int MAX_SCOUT_ROUNDS = 300;
    public static final int MIN_SCOUT_DURATION = 30;

    public static boolean shouldBeScout(RobotController rc, Mem m) throws GameActionException {
        if (rc.getRawCheese() > 0) {
            return false;
        }
        if (rc.getRoundNum() > MAX_SCOUT_ROUNDS) {
            return false;
        }
        int usableMines = countUsableMines(rc, m);
        if (usableMines >= TARGET_MINE_COUNT) {
            return false;
        }
        boolean isDesignatedScout = (rc.getID() % SCOUT_RATIO) == 0;
        return isDesignatedScout;
    }

    public static int countUsableMines(RobotController rc, Mem m) {
        if (m.kingLocation == null) {
            return m.knownMineCount;
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

    public static boolean doScoutTurn(RobotController rc, Mem m, MapInfo[] nearby) throws GameActionException {
        MapLocation here = rc.getLocation();

        boolean foundNew = DefenderSensing.scanAndCacheMines(rc, m, nearby);

        if (foundNew && m.knownMineCount > 0) {
            MapLocation newestMine = m.knownMines[m.knownMineCount - 1];
            if (newestMine != null) {
                reportMineViaSqueaks(rc, m, newestMine);
            }
        }

        if (m.scoutTarget == null || here.distanceSquaredTo(m.scoutTarget) <= 9) {
            m.scoutTarget = pickScoutTarget(rc, m);
            m.scoutTurnsAtTarget = 0;
        }

        m.scoutTurnsAtTarget++;

        if (m.scoutTurnsAtTarget > 40) {
            m.scoutTarget = pickScoutTarget(rc, m);
            m.scoutTurnsAtTarget = 0;
        }

        if (m.scoutTarget != null) {
            ThreatInfo threat = computeThreat(rc, m);
            return intelligentMoveToward(rc, m, m.scoutTarget, nearby, threat);
        }

        return false;
    }

    public static MapLocation pickScoutTarget(RobotController rc, Mem m) {
        MapLocation here = rc.getLocation();
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        MapLocation[] candidates = new MapLocation[12];
        candidates[0] = new MapLocation(w / 4, h / 4);
        candidates[1] = new MapLocation(3 * w / 4, h / 4);
        candidates[2] = new MapLocation(w / 4, 3 * h / 4);
        candidates[3] = new MapLocation(3 * w / 4, 3 * h / 4);
        candidates[4] = new MapLocation(w / 2, h / 6);
        candidates[5] = new MapLocation(w / 2, 5 * h / 6);
        candidates[6] = new MapLocation(w / 6, h / 2);
        candidates[7] = new MapLocation(5 * w / 6, h / 2);
        candidates[8] = new MapLocation(w / 3, h / 2);
        candidates[9] = new MapLocation(2 * w / 3, h / 2);
        candidates[10] = new MapLocation(w / 2, h / 3);
        candidates[11] = new MapLocation(w / 2, 2 * h / 3);

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

        if (best == null) {
            best = pickExplorationAwayFromKnownMines(rc, m);
        }

        return best;
    }

    private static int scoreScoutTarget(RobotController rc, Mem m, MapLocation target, MapLocation here) {
        int score = 0;

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
            score += minDistToKnownMine / 10;
        } else {
            score += 100;
        }

        int distFromHere = here.distanceSquaredTo(target);
        score -= distFromHere / 50;

        if (m.kingLocation != null) {
            int distFromKing = m.kingLocation.distanceSquaredTo(target);
            if (distFromKing > MAX_MINE_DISTANCE_SQ) {
                score -= 50;
            }
        }

        if (m.lastScoutTarget != null && target.distanceSquaredTo(m.lastScoutTarget) < 100) {
            score -= 30;
        }

        return score;
    }

    public static void reportMineViaSqueaks(RobotController rc, Mem m, MapLocation mine) throws GameActionException {
        if (rc.getRoundNum() - m.lastMineReportRound < 10) return;

        int xMsg = 800 + mine.x;
        rc.squeak(xMsg);

        m.pendingMineReportY = mine.y;
        m.lastMineReportRound = rc.getRoundNum();
    }

    public static void continueMineReport(RobotController rc, Mem m) throws GameActionException {
        if (m.pendingMineReportY >= 0) {
            int yMsg = 900 + m.pendingMineReportY;
            rc.squeak(yMsg);
            m.pendingMineReportY = -1;
        }
    }
}
