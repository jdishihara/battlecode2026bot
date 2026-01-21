package examplefuncsplayer;

import battlecode.common.*;

import examplefuncsplayer.units.RatKing.RatKing;
// Phase 5: Re-enable attackers when needed
// import examplefuncsplayer.units.BabyRatAttacker.BabyRatAttacker;
// import examplefuncsplayer.units.BabyRatHauler.BabyRatHauler;
import examplefuncsplayer.units.HaulerDefender.HaulerDefender;

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
                    // All baby rats are now HaulerDefenders
                    // They collect cheese and defend king when enemies are nearby
                    HaulerDefender.run(rc, turnCount);

                    /* Phase 5: Re-enable attacker/hauler split when needed
                    int birthRound = rc.getRoundNum() - turnCount + 1;
                    int attackerRoundCutoff = NUM_INITIAL_ATTACKERS * 3;
                    boolean isAttacker = (birthRound <= attackerRoundCutoff);

                    if (isAttacker) {
                        BabyRatAttacker.run(rc, turnCount);
                    } else {
                        BabyRatHauler.run(rc, turnCount);
                    }
                    */
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
