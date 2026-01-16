package examplefuncsplayer.units.RatKing;

import battlecode.common.*;

import static examplefuncsplayer.config.Constants.*;

/**
 * Sensing helper methods for the Rat King.
 */
public class Sensing {

    public static RobotInfo findNearestCatForKing(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(ALERT_RADIUS);
        MapLocation myLocation = rc.getLocation();

        RobotInfo nearestCat = null;
        int minDist = Integer.MAX_VALUE;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getType() == UnitType.CAT) {
                int dist = myLocation.distanceSquaredTo(robot.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestCat = robot;
                }
            }
        }
        return nearestCat;
    }

    public static RobotInfo findNearestEnemyRat(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(ALERT_RADIUS);
        Team enemy = rc.getTeam().opponent();
        MapLocation myLocation = rc.getLocation();

        RobotInfo nearestEnemy = null;
        int minDist = Integer.MAX_VALUE;

        for (RobotInfo robot : nearbyRobots) {
            if (robot.getTeam() == enemy &&
                    (robot.getType() == UnitType.BABY_RAT || robot.getType() == UnitType.RAT_KING)) {
                int dist = myLocation.distanceSquaredTo(robot.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestEnemy = robot;
                }
            }
        }
        return nearestEnemy;
    }

    public static int countAlliedBabyRatsNearKing(RobotController rc, int radius) throws GameActionException {
        RobotInfo[] robots = rc.senseNearbyRobots(radius);
        int count = 0;
        Team myTeam = rc.getTeam();
        for (RobotInfo r : robots) {
            if (r.getTeam() == myTeam && r.getType() == UnitType.BABY_RAT) count++;
        }
        return count;
    }
}
