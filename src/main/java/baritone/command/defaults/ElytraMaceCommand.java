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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.NearbyPlayer;
import baritone.api.command.exception.CommandException;
import baritone.api.process.IElytraMaceProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ElytraMaceCommand extends Command {

    public ElytraMaceCommand(IBaritone baritone) {
        super(baritone, "elytramace", "em");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        IElytraMaceProcess elytraMace = baritone.getElytraMaceProcess();
        if (!args.hasAny()) {
            if (elytraMace.isActive()) {
                elytraMace.cancel();
                logDirect("ElytraMace cancelled");
            } else {
                elytraMace.engage(null);
                logDirect("ElytraMace engaged on the nearest player");
            }
            return;
        }
        args.requireMax(1);
        String name = args.getString();
        if (name.equalsIgnoreCase("stop") || name.equalsIgnoreCase("cancel") || name.equalsIgnoreCase("off")) {
            elytraMace.cancel();
            logDirect("ElytraMace cancelled");
            return;
        }
        elytraMace.engage(name);
        boolean inRenderDistance = ctx.world().players().stream()
                .anyMatch(p -> p != ctx.player() && p.getName().getString().equalsIgnoreCase(name));
        if (inRenderDistance) {
            logDirect(String.format("ElytraMace engaged on %s", name));
        } else {
            logDirect(String.format("ElytraMace engaged on %s (not in render distance, will keep scanning)", name));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return args.tabCompleteDatatype(NearbyPlayer.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Elytra + mace PvP mode";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The elytramace command starts the elytra + mace combat mode. As soon as the target",
                "player is seen in render distance, Baritone equips the elytra and flies to them on",
                "firework boosts. Right before impact the elytra is swapped back out for the",
                "chestplate and the fall becomes a mace smash.",
                "",
                "With Wind Burst on the mace it rides the launch back up and re-engages at the apex;",
                "without it, it climbs on the elytra itself and repeats the dive cycle.",
                "",
                "The elytra is expected in the hotbar slot configured by elytraMaceHotbarSlot",
                "(default 8); the chestplate is swapped into that same slot while flying.",
                "",
                "Usage:",
                "> elytramace <player> - Attack the player with this nickname",
                "> elytramace - Engage the nearest player",
                "> elytramace stop - Cancel the mode and put the chestplate back on"
        );
    }
}
