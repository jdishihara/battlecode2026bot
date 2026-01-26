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
 * Spawning Logic: HAULERS FIRST
 * - First INITIAL_HAULER_COUNT baby rats become haulers
 * - Remaining baby rats become attackers
 * - Role is determined by spawn order (ID relative to King's ID)
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
                        // Calculate spawn order based on ID difference from King
                        int kingID = rc.readSharedArray(KING_ID_INDEX);
                        int myIDMod = rc.getID() % 1000;

                        // Calculate spawn order (handle wrap-around for mod 1000)
                        int spawnOrder = myIDMod - kingID;
                        if (spawnOrder < 0) spawnOrder += 1000;

                        // First INITIAL_HAULER_COUNT rats are haulers, rest are attackers
                        isAttacker = (spawnOrder > INITIAL_HAULER_COUNT);
                        roleDecided = true;

                        System.out.println("ID:" + rc.getID() + " spawnOrder:" + spawnOrder + " role:" + (isAttacker ? "ATTACKER" : "HAULER"));
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
