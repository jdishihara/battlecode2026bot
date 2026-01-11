package examplefuncsplayer;

import battlecode.common.*;

import examplefuncsplayer.units.RatKing;
import examplefuncsplayer.units.BabyRatAttacker;
import examplefuncsplayer.units.BabyRatHauler;
import examplefuncsplayer.units.BabyRatDefender;  // ADD THIS

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
                    // Split into 3 roles using modulo 3:
                    // ID % 3 == 0 -> Defender (stays near king)
                    // ID % 3 == 1 -> Attacker (hunts cats/enemies)
                    // ID % 3 == 2 -> Hauler (collects cheese)
                    int role = rc.getID() % 3;
                    
                    if (role == 0) {
                        BabyRatDefender.run(rc, turnCount);
                    } else if (role == 1) {
                        BabyRatAttacker.run(rc, turnCount);
                    } else {
                        BabyRatHauler.run(rc, turnCount);
                    }
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