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
 * @since 26.1
 */
public interface IHuntProcess extends IBaritoneProcess {

    /**
     * Begin hunting the player with the given username. The target is resolved
     * every tick from the entities in render distance, so the hunt may be
     * started even when the player isn't currently visible.
     *
     * @param username The username of the player to hunt
     */
    void hunt(String username);

    /**
     * @return The username of the player being hunted, or {@code null} if not hunting
     */
    String target();

    /**
     * Cancels the hunt, this will clear the current target.
     */
    default void cancel() {
        onLostControl();
    }
}
