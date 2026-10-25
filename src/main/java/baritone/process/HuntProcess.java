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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IHuntProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Hunt down and attack a player. Resolves the target by username every tick
 * from the entities in render distance, paths to it, and melees it once in
 * reach until it dies.
 */
public final class HuntProcess extends BaritoneProcessHelper implements IHuntProcess {

    /**
     * Distance at which the process starts turning the camera towards the target
     */
    private static final double AIM_RANGE_SQ = 8 * 8;

    /**
     * Material tags ordered from cheapest to most expensive, used to rank weapons
     */
    private static final List<TagKey<Item>> WEAPON_MATERIAL_TAGS = List.of(
            ItemTags.WOODEN_TOOL_MATERIALS,
            ItemTags.STONE_TOOL_MATERIALS,
            ItemTags.IRON_TOOL_MATERIALS,
            ItemTags.GOLD_TOOL_MATERIALS,
            ItemTags.DIAMOND_TOOL_MATERIALS,
            ItemTags.NETHERITE_TOOL_MATERIALS
    );

    private String username;
    private Player targetEntity;
    private BetterBlockPos lastSeen;

    public HuntProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void hunt(String username) {
        this.username = username;
        this.targetEntity = null;
        this.lastSeen = null;
    }

    @Override
    public String target() {
        return this.username;
    }

    @Override
    public boolean isActive() {
        return this.username != null && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        scanWorld();

        if (this.username == null) { // the hunt ended this tick (target eliminated)
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (this.targetEntity == null) {
            // not visible; head to the last position the target was seen at, then wait there scanning
            if (this.lastSeen != null && player.blockPosition().distSqr(this.lastSeen) > 4) {
                return new PathingCommand(new GoalNear(this.lastSeen, 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.lastSeen = new BetterBlockPos(this.targetEntity.getX(), this.targetEntity.getY(), this.targetEntity.getZ());
        double distSq = player.distanceToSqr(this.targetEntity);

        // face the target well before entering reach, so hits register server side
        if (distSq < AIM_RANGE_SQ) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(),
                    this.targetEntity.getEyePosition(1.0F),
                    ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(rot, true);
        }

        double reach = Baritone.settings().huntReach.value;
        if (distSq <= reach * reach) {
            attack(this.targetEntity);
            // stand our ground while swinging; if the target escapes reach, the next tick paths after it again
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (Baritone.settings().huntPreferSword.value) {
            holdBestWeapon();
        }
        return new PathingCommand(new GoalNear(this.targetEntity.blockPosition(), 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        this.username = null;
        this.targetEntity = null;
        this.lastSeen = null;
    }

    @Override
    public String displayName0() {
        return "Hunting " + this.username;
    }

    private void scanWorld() {
        Player found = ctx.entitiesStream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .filter(p -> p != ctx.player() && p.getName().getString().equalsIgnoreCase(this.username))
                .findFirst()
                .orElse(null);

        if (found == null) {
            if (this.targetEntity != null) {
                logDebug("lost sight of " + this.username);
            }
            this.targetEntity = null;
            return;
        }

        if (!found.isAlive()) {
            logDirect(String.format("Hunt complete: %s is dead", this.username));
            onLostControl();
            return;
        }

        if (this.targetEntity == null && this.lastSeen == null) {
            logDebug("target " + this.username + " in sight");
        }
        this.targetEntity = found;
    }

    private void attack(Player target) {
        LocalPlayer player = ctx.player();
        if (target.isSpectator() || target.isDeadOrDying()) {
            return;
        }
        // let the attack cooldown fully charge so every hit deals maximum damage
        if (player.getAttackStrengthScale(0.5F) < 1.0F) {
            return;
        }
        // sprint hits apply extra knockback, keeping the target within reach
        if (Baritone.settings().huntSprint.value && !player.isSprinting()) {
            player.setSprinting(true);
        }
        ctx.minecraft().gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Hold the best sword (or failing that, axe) on the hotbar. Only switches
     * when the score improves, since changing held items resets the attack cooldown.
     */
    private void holdBestWeapon() {
        int bestSlot = -1;
        int bestScore = weaponScore(ctx.player().getMainHandItem());
        for (int i = 0; i < 9; i++) {
            int score = weaponScore(ctx.player().getInventory().getNonEquipmentItems().get(i));
            if (score > bestScore) {
                bestSlot = i;
                bestScore = score;
            }
        }
        if (bestSlot != -1) {
            ctx.player().getInventory().setSelectedSlot(bestSlot);
        }
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        boolean sword = stack.is(ItemTags.SWORDS);
        boolean axe = stack.is(ItemTags.AXES);
        if (!sword && !axe) {
            return -1;
        }
        for (int i = 0; i < WEAPON_MATERIAL_TAGS.size(); i++) {
            if (stack.is(WEAPON_MATERIAL_TAGS.get(i))) {
                return (sword ? 100 : 50) + i;
            }
        }
        return sword ? 99 : 49;
    }
}
