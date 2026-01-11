package defaultbot;

import battlecode.common.*;

import java.util.Random;

/**
 * A simple default bot for testing purposes.
 * - Rat King: spawns baby rats and attacks nearby enemies
 * - Baby Rats: move randomly, attack enemies, and pickup cheese
 */
public class testbot {

    static int turnCount = 0;
    static Random rng;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        rng = new Random(rc.getID());

        while (true) {
            turnCount++;

            try {
                UnitType type = rc.getType();

                if (type == UnitType.RAT_KING) {
                    runRatKing(rc);
                } else if (type == UnitType.BABY_RAT) {
                    runBabyRat(rc);
                }

            } catch (GameActionException e) {
                System.out.println("GameActionException on turn " + turnCount);
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception on turn " + turnCount);
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    static void runRatKing(RobotController rc) throws GameActionException {
        // Try to spawn baby rats at an adjacent location
        MapLocation myLoc = rc.getLocation();
        Direction randomDir = getRandomDirection();
        MapLocation spawnLoc = myLoc.add(randomDir);

        if (rc.canBuildRat(spawnLoc)) {
            rc.buildRat(spawnLoc);
        }

        // Attack nearby enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = enemies[0];
            if (rc.canAttack(target.getLocation())) {
                rc.attack(target.getLocation());
            }
        }
    }

    static void runBabyRat(RobotController rc) throws GameActionException {
        // Try to pick up cheese if available
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation nearestCheese = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo info : nearby) {
            if (info.getCheeseAmount() > 0) {
                int dist = rc.getLocation().distanceSquaredTo(info.getMapLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearestCheese = info.getMapLocation();
                }
            }
        }

        if (nearestCheese != null && rc.canPickUpCheese(nearestCheese)) {
            rc.pickUpCheese(nearestCheese);
        }

        // Attack nearby enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            RobotInfo target = enemies[0];
            if (rc.canAttack(target.getLocation())) {
                rc.attack(target.getLocation());
                return;
            }
        }

        // Move randomly
        if (rc.isMovementReady()) {
            Direction randomDir = getRandomDirection();
            if (rc.canMove(randomDir)) {
                rc.move(randomDir);
            }
        }
    }

    static Direction getRandomDirection() {
        Direction[] directions = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST
        };
        return directions[rng.nextInt(directions.length)];
    }
}
