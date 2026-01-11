package examplefuncsplayer;

import battlecode.common.*;

import examplefuncsplayer.units.RatKing;
import examplefuncsplayer.units.BabyRatAttacker;
import examplefuncsplayer.units.BabyRatHauler;

/**
 * Thin dispatcher: routes each unit to its role file.
 * All constants/state machines live in config/ + units/.
 */
public class RobotPlayer {

    static int turnCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive as " + rc.getType());

        while (true) {
            turnCount++;

            try {
                UnitType type = rc.getType();

                if (type == UnitType.RAT_KING) {
                    RatKing.run(rc, turnCount);
                } else if (type == UnitType.BABY_RAT) {
                    // Keep the same role split rule you had before:
                    // even ID = attacker, odd ID = hauler
                    boolean isAttacker = (rc.getID() % 2 == 0);
                    if (isAttacker) {
                        BabyRatAttacker.run(rc, turnCount);
                    } else {
                        BabyRatHauler.run(rc, turnCount);
                    }
                } else {
                    // If Battlecode adds other unit types later, do nothing safely.
                    rc.setIndicatorString("Unhandled unit type: " + type);
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
}
