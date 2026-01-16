package defaultbot;

import battlecode.common.*;

import defaultbot.units.RatKing.RatKing;
import defaultbot.units.BabyRatAttacker.BabyRatAttacker;
import defaultbot.units.BabyRatHauler.BabyRatHauler;

import static defaultbot.config.Shared.*;

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
                    // Spawn attackers first, then haulers based on birth round
                    // Birth round = the round this rat was spawned
                    int birthRound = rc.getRoundNum() - turnCount + 1;

                    // First ~36 rounds of spawned rats are attackers (roughly NUM_INITIAL_ATTACKERS worth)
                    // This ensures attackers spawn before haulers
                    int attackerRoundCutoff = NUM_INITIAL_ATTACKERS * 3;
                    boolean isAttacker = (birthRound <= attackerRoundCutoff);

                    if (isAttacker) {
                        BabyRatAttacker.run(rc, turnCount);
                    } else {
                        BabyRatHauler.run(rc, turnCount);
                    }
                    // Debug: see role assignment values
                    if (turnCount == 1) {
                        System.out.println("ID:" + rc.getID() + " birthRound:" + birthRound + " cutoff:" + attackerRoundCutoff + " attacker:" + isAttacker);
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
