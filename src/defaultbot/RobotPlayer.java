package defaultbot;

import battlecode.common.*;

import defaultbot.units.RatKing.RatKing;
import defaultbot.units.BabyRatAttacker.BabyRatAttacker;
import defaultbot.units.BabyRatHauler.BabyRatHauler;

import static defaultbot.config.Shared.*;

/**
 * Thin dispatcher: routes each unit to its role file.
 * All constants/state machines live in config/ + units/.
 *
 * Spawning Logic:
 * - Phase 0: Initial Hauler Phase - spawn 30 haulers first
 * - Phase 2: Main Hauler Phase - spawn haulers continuously
 * - Phase 3: Attacker Wave Phase - spawn 8 attackers every 300 rounds, then back to Phase 2
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
                        // Read spawn phase from shared array
                        // Phase 0 = initial haulers, Phase 2 = main haulers, Phase 3 = attacker wave
                        int spawnPhase = rc.readSharedArray(SPAWN_PHASE_INDEX);

                        // Attacker phase is 3; hauler phases are 0 and 2
                        isAttacker = (spawnPhase == 3);
                        roleDecided = true;

                        // Debug output
                        String phaseName = (spawnPhase == 0) ? "INIT_HAULER" :
                                           (spawnPhase == 2) ? "MAIN_HAULER" :
                                           (spawnPhase == 3) ? "ATCK_WAVE" : "UNKNOWN";
                        System.out.println("ID:" + rc.getID() +
                                " phase:" + phaseName +
                                " role:" + (isAttacker ? "ATTACKER" : "HAULER"));
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
