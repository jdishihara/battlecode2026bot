package examplefuncsplayer;

import battlecode.common.*;

import examplefuncsplayer.units.RatKing.RatKing;
import examplefuncsplayer.units.BabyRatAttacker.BabyRatAttacker;
import examplefuncsplayer.units.BabyRatHauler.BabyRatHauler;

import static examplefuncsplayer.config.Shared.*;

/**
 * Thin dispatcher: routes each unit to its role file.
 * All constants/state machines live in config/ + units/.
 *
 * Spawning Logic: HAULERS with ATTACKER WAVES
 * - Default: All baby rats become haulers
 * - When wave conditions are met (round > 100, cheese > 300, haulers >= 40),
 *   spawn 8 attackers as a wave with 100 round cooldown between waves
 * - Role is determined by NEXT_SPAWN_ROLE set by king before spawn
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
                        // Read role assigned by king before this rat was spawned
                        int assignedRole = rc.readSharedArray(NEXT_SPAWN_ROLE);
                        isAttacker = (assignedRole == 1);
                        roleDecided = true;

                        System.out.println("ID:" + rc.getID() + " role:" + (isAttacker ? "ATTACKER" : "HAULER"));
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
