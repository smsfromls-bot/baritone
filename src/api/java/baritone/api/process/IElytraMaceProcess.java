/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.process;

/**
 * Elytra + mace PvP mode: chases a player by flying on elytra with firework boosts,
 * swaps the elytra out for the chestplate right before impact and smashes them with
 * a mace. If the mace has Wind Burst it rides the bounce back up, otherwise it climbs
 * on its own and repeats the dive cycle.
 *
 * @since 26.1
 */
public interface IElytraMaceProcess extends IBaritoneProcess {

    /**
     * Begin attacking the player with the given username. The target is resolved
     * every tick from the entities in render distance; the elytra is equipped as
     * soon as they are seen.
     *
     * @param username The username of the player to attack, or {@code null} to
     *                 attack the nearest visible player
     */
    void engage(String username);

    /**
     * @return The username of the player being attacked, or {@code null} if not engaged
     */
    String target();

    /**
     * Cancels the mode and swaps the elytra back out for the chestplate.
     */
    default void cancel() {
        onLostControl();
    }
}
