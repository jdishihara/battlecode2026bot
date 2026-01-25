package defaultbot;

import battlecode.common.*;

import examplefuncsplayer.units.RatKing.RatKing;
import examplefuncsplayer.units.BabyRatAttacker.BabyRatAttacker;
import examplefuncsplayer.units.BabyRatHauler.BabyRatHauler;

import static examplefuncsplayer.config.Shared.*;

/**
 * Thin dispatcher: routes each unit to its role file.
 * All constants/state machines live in config/ + units/.
 *
 * Spawning Logic: 50/50 SPLIT
 * - 50% of spawned BabyRats become BabyRatAttackers (attack enemy king)
 * - 50% of spawned BabyRats become BabyRatHaulers (gather cheese)
 */
public class RobotPlayer {

    static int turnCount = 0;
    static boolean roleDecided = false;
    static boolean isAttacker = false;

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
                    // Decide role on first turn only (role is permanent)
                    if (!roleDecided) {
                        // 50/50 split: even IDs are attackers, odd IDs are haulers
                        isAttacker = (rc.getID() % 2 == 0);
                        roleDecided = true;

                        System.out.println("ID:" + rc.getID() + " role:" + (isAttacker ? "ATTACKER" : "HAULER") + " (50/50 split)");
                    }

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
