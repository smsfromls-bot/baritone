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
import baritone.api.process.IElytraMaceProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.process.elytra.ElytraBehavior;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.accessor.IFireworkRocketEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Objects;

/**
 * Elytra + mace PvP mode. As soon as the target player is seen in render distance the elytra
 * is equipped and the chase continues in the air with firework boosts. Right before impact the
 * elytra is swapped back out for the chestplate and the remaining fall becomes a mace smash.
 * <p>
 * With Wind Burst on the mace the process rides the launch back up and re-engages at the apex;
 * without it, it climbs on the elytra itself and repeats the dive cycle.
 */
public final class ElytraMaceProcess extends BaritoneProcessHelper implements IElytraMaceProcess {

    /**
     * Chest armor slot in the player inventory menu (5 head, 6 chest, 7 legs, 8 feet)
     */
    private static final int CHEST_ARMOR_SLOT = 6;

    /**
     * Ticks between two firework boosts
     */
    private static final int FIREWORK_COOLDOWN_TICKS = 10;

    /**
     * Attack strength required for the smash. The impact window is only a few ticks long, so
     * waiting for a full charge after the last hand swap would miss it entirely.
     */
    private static final float SMASH_STRENGTH_THRESHOLD = 0.9F;

    /**
     * How far the aim is raised above an obstacle that blocks the raytrace, to climb over it
     */
    private static final double OBSTACLE_CLIMB = 10.0;

    public enum State {
        SCANNING("Scanning"),
        TAKEOFF("Taking off"),
        FLYING("Flying"),
        DIVE("Diving"),
        SMASH("Smashing"),
        BOUNCE("Riding wind burst"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    private State state = State.SCANNING;
    private boolean engaged;
    private String username; // null means engage the nearest player
    private Player targetEntity;
    private BetterBlockPos lastSeen;

    private final Deque<Runnable> invQueue = new ArrayDeque<>();
    private int invCooldown;
    private int fireworkCooldown;
    private int ticksInFlight;
    private boolean warnedNoFireworks;
    private boolean warnedNoMace;

    public ElytraMaceProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void engage(String username) {
        this.username = username;
        this.targetEntity = null;
        this.lastSeen = null;
        this.state = State.SCANNING;
        this.engaged = true;
        this.warnedNoFireworks = false;
        this.warnedNoMace = false;
    }

    @Override
    public String target() {
        return this.username;
    }

    @Override
    public boolean isActive() {
        return this.engaged && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public double priority() {
        return 1; // above the elytra (0) and every default (-1) process; combat takes control
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tickInventory();
        scanWorld();
        if (!isActive()) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        switch (this.state) {
            case SCANNING -> { return tickScanning(); }
            case TAKEOFF -> { return tickTakeoff(); }
            case FLYING -> { return tickFlying(); }
            case DIVE -> { return tickDive(); }
            case SMASH -> { return tickSmash(); }
            case BOUNCE -> { return tickBounce(); }
            case LANDING -> { return tickLanding(); }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        LocalPlayer player = ctx.player();
        if (player != null && player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            // give the chestplate back right away; the click queue is about to be dropped
            ctx.playerController().windowClick(player.inventoryMenu.containerId, CHEST_ARMOR_SLOT, hotbarSlot(), ContainerInput.SWAP, player);
        }
        this.engaged = false;
        this.username = null;
        this.targetEntity = null;
        this.lastSeen = null;
        this.state = State.SCANNING;
        this.invQueue.clear();
        this.invCooldown = 0;
        this.fireworkCooldown = 0;
        if (baritone.getInputOverrideHandler() != null) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }
    }

    @Override
    public String displayName0() {
        String who = this.username != null ? this.username : "nearest player";
        return "ElytraMace -> " + who + " [" + this.state.description + "]";
    }

    private PathingCommand tickScanning() {
        if (this.targetEntity == null) {
            // not visible; head to the last position the target was seen at, then wait there scanning
            if (this.lastSeen != null && ctx.player().blockPosition().distSqr(this.lastSeen) > 4) {
                return new PathingCommand(new GoalNear(this.lastSeen, 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        this.lastSeen = feetOf(this.targetEntity);
        double start = Baritone.settings().elytraMaceStartDistance.value;
        if (ctx.player().distanceToSqr(this.targetEntity) <= start * start) {
            return fightOnFoot();
        }
        if (!wearingElytra() && findElytra() == null) {
            fallbackToGroundHunt("no usable elytra");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        logDebug("target in sight, equipping elytra and taking off");
        this.state = State.TAKEOFF;
        this.ticksInFlight = 0;
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickTakeoff() {
        LocalPlayer player = ctx.player();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (this.targetEntity == null) {
            if (player.isFallFlying()) {
                this.state = State.LANDING;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            removeElytra();
            this.state = State.SCANNING;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!wearingElytra() && findElytra() == null && this.invQueue.isEmpty()) {
            fallbackToGroundHunt("no usable elytra");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        ensureElytraEquipped();
        if (!player.isFallFlying()) {
            holdMace();
            if (player.onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            } else if (player.getDeltaMovement().y < -0.377) {
                // falling fast enough: a jump press now deploys the elytra
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        this.state = State.FLYING;
        this.ticksInFlight = 0;
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickFlying() {
        LocalPlayer player = ctx.player();
        this.ticksInFlight++;
        if (!player.isFallFlying()) {
            this.state = player.onGround() ? State.SCANNING : State.TAKEOFF;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.targetEntity == null) {
            if (this.lastSeen == null || player.position().distanceToSqr(this.lastSeen.getCenter()) < 8 * 8) {
                this.state = State.LANDING;
            } else {
                steerTo(this.lastSeen.getCenter(), true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        this.lastSeen = feetOf(this.targetEntity);

        Vec3 targetEye = this.targetEntity.getEyePosition(1.0F);
        double dist = player.position().distanceTo(targetEye);
        double heightAdvantage = player.getY() - this.targetEntity.getY();

        if (dist <= Baritone.settings().huntReach.value) {
            // point blank while gliding: just hit them, elytra stays on
            holdMace();
            aimAt(targetEye);
            attackIfReady(this.targetEntity);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double climb = Baritone.settings().elytraMaceClimbHeight.value;
        double engage = Baritone.settings().elytraMaceEngageDistance.value;
        double swap = Baritone.settings().elytraMaceSwapDistance.value;

        if (dist < swap) {
            // too close to gain height, commit to whatever smash we can get
            beginSmash();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (heightAdvantage >= climb && dist < engage) {
            this.state = State.DIVE;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean climbing = heightAdvantage < climb;
        Vec3 aim = climbing
                ? new Vec3(this.targetEntity.getX(), targetEye.y + climb, this.targetEntity.getZ())
                : predictedTargetPos();
        steerTo(aim, true);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickDive() {
        LocalPlayer player = ctx.player();
        if (!player.isFallFlying()) {
            // elytra already swapped out (or broke mid-dive): nothing left to fly with
            this.state = State.SMASH;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.targetEntity == null) {
            this.state = State.LANDING;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Vec3 targetEye = this.targetEntity.getEyePosition(1.0F);
        aimAt(targetEye);
        holdMace();
        if (player.position().distanceTo(targetEye) < Baritone.settings().elytraMaceSwapDistance.value) {
            beginSmash();
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickSmash() {
        LocalPlayer player = ctx.player();
        if (player.isFallFlying()) {
            // the swap didn't land yet (or was rejected), keep trying while we still can
            removeElytra();
            this.state = State.DIVE;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.targetEntity == null) {
            if (player.onGround()) {
                this.state = State.SCANNING;
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        holdMace();
        aimAt(this.targetEntity.getEyePosition(1.0F));
        double reach = Baritone.settings().huntReach.value;
        if (ctx.player().distanceToSqr(this.targetEntity) <= reach * reach) {
            boolean hit = attackIfReady(this.targetEntity);
            if (hit && hasWindBurst(player.getMainHandItem())) {
                // the smash launched us up; wait for the apex, then re-engage
                this.state = State.BOUNCE;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        if (player.onGround()) {
            // landed, hit or missed: climb back up and repeat the cycle
            this.state = State.TAKEOFF;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickBounce() {
        LocalPlayer player = ctx.player();
        if (player.onGround()) {
            this.state = State.TAKEOFF;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.targetEntity != null) {
            aimAt(this.targetEntity.getEyePosition(1.0F));
        }
        if (player.getDeltaMovement().y < 0.2) {
            // near the apex of the wind burst launch: equip the elytra, boost towards the target and dive again
            this.state = State.TAKEOFF;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickLanding() {
        LocalPlayer player = ctx.player();
        if (this.targetEntity != null) {
            // target came back into view while gliding down
            this.state = State.TAKEOFF;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (!player.isFallFlying()) {
            removeElytra();
            this.state = State.SCANNING;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Vec3 aim = this.lastSeen != null
                ? this.lastSeen.getCenter().subtract(0, 10, 0)
                : player.position().subtract(0, 10, 0);
        steerTo(aim, false);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * On-foot fighting for when the target is closer than elytraMaceStartDistance, same as the
     * regular hunt
     */
    private PathingCommand fightOnFoot() {
        Player target = this.targetEntity;
        double distSq = ctx.player().distanceToSqr(target);
        if (distSq < 8 * 8) {
            Rotation rot = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(),
                    target.getEyePosition(1.0F),
                    ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(rot, true);
        }
        holdMace();
        double reach = Baritone.settings().huntReach.value;
        if (distSq <= reach * reach) {
            if (Baritone.settings().huntSprint.value && !ctx.player().isSprinting()) {
                ctx.player().setSprinting(true);
            }
            attackIfReady(target);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalNear(target.blockPosition(), 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void beginSmash() {
        removeElytra(); // queued; we keep diving while the swap lands
        this.state = State.SMASH;
    }

    /**
     * Attacks when the cooldown is (mostly) charged and the target is alive; returns whether a
     * hit was actually sent
     */
    private boolean attackIfReady(Player target) {
        LocalPlayer player = ctx.player();
        if (target.isSpectator() || target.isDeadOrDying()) {
            return false;
        }
        if (player.getAttackStrengthScale(0.5F) < SMASH_STRENGTH_THRESHOLD) {
            return false;
        }
        ctx.minecraft().gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Steers the glide towards the aim point. Raytraces ahead first; if a block is in the way the
     * aim is raised to climb over it while keeping the heading.
     */
    private void steerTo(Vec3 aim, boolean allowFireworks) {
        LocalPlayer player = ctx.player();
        Vec3 from = player.getEyePosition(1.0F);
        Vec3 delta = aim.subtract(from);
        double len = delta.length();
        if (len < 0.001) {
            return;
        }
        Vec3 dir = delta.normalize();
        double lookahead = Math.min(len, Baritone.settings().elytraMaceObstacleLookahead.value);
        BlockHitResult hit = ctx.world().clip(new ClipContext(
                from,
                from.add(dir.scale(lookahead)),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player));
        if (hit.getType() != HitResult.Type.MISS && len > 4) {
            Vec3 horizontal = new Vec3(dir.x, 0, dir.z);
            if (horizontal.lengthSqr() > 0.0001) {
                aim = from.add(horizontal.normalize().scale(lookahead)).add(0, OBSTACLE_CLIMB, 0);
            }
        }
        aimAt(aim);
        if (allowFireworks) {
            tryFirework(aim.y > from.y + 1);
        } else {
            holdMace();
        }
    }

    private void aimAt(Vec3 to) {
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), to, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, false);
    }

    private void tryFirework(boolean climbing) {
        LocalPlayer player = ctx.player();
        int setbackDelay = Baritone.settings().elytraFireworkSetbackUseDelay.value;
        if (this.fireworkCooldown > 0 || this.ticksInFlight < setbackDelay || isBoosted()) {
            holdMace();
            return;
        }
        double speed = Baritone.settings().elytraFireworkSpeed.value;
        boolean slow = player.getDeltaMovement().horizontalDistanceSqr() < speed * speed;
        if (!climbing && !slow) {
            holdMace();
            return;
        }
        if (countFireworks() == 0) {
            if (!this.warnedNoFireworks) {
                this.warnedNoFireworks = true;
                logDirect("Out of fireworks, gliding without boosts");
            }
            holdMace();
            return;
        }
        if (baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
            ctx.playerController().processRightClick(player, ctx.world(), InteractionHand.MAIN_HAND);
            this.fireworkCooldown = FIREWORK_COOLDOWN_TICKS;
        }
    }

    private boolean isBoosted() {
        return ctx.entitiesStream().anyMatch(e -> e instanceof FireworkRocketEntity
                && Objects.equals(((IFireworkRocketEntity) e).getBoostedEntity(), ctx.player()));
    }

    private int countFireworks() {
        int qty = 0;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        return qty;
    }

    private void scanWorld() {
        Player found;
        if (this.username != null) {
            found = ctx.entitiesStream()
                    .filter(Player.class::isInstance)
                    .map(Player.class::cast)
                    .filter(p -> p != ctx.player() && p.getName().getString().equalsIgnoreCase(this.username))
                    .findFirst()
                    .orElse(null);
        } else {
            found = ctx.entitiesStream()
                    .filter(Player.class::isInstance)
                    .map(Player.class::cast)
                    .filter(p -> p != ctx.player() && p.isAlive() && !p.isSpectator())
                    .min(Comparator.comparingDouble(p -> p.distanceToSqr(ctx.player())))
                    .orElse(null);
        }

        if (found == null) {
            if (this.targetEntity != null) {
                logDebug("lost sight of the target");
            }
            this.targetEntity = null;
            return;
        }
        if (!found.isAlive()) {
            logDirect("ElytraMace complete: " + found.getName().getString() + " is dead");
            onLostControl();
            return;
        }
        this.targetEntity = found;
    }

    private void fallbackToGroundHunt(String reason) {
        String name = this.username;
        if (name == null && this.targetEntity != null) {
            name = this.targetEntity.getName().getString();
        }
        logDirect(reason + ", falling back to a regular hunt" + (name != null ? " on " + name : ""));
        onLostControl();
        if (name != null) {
            baritone.getHuntProcess().hunt(name);
        }
    }

    private void holdMace() {
        LocalPlayer player = ctx.player();
        if (player.getMainHandItem().getItem() == Items.MACE) {
            return;
        }
        NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).getItem() == Items.MACE) {
                player.getInventory().setSelectedSlot(i);
                return;
            }
        }
        if (!this.warnedNoMace) {
            this.warnedNoMace = true;
            logDirect("No mace on the hotbar, attacking with whatever is held");
        }
    }

    private void ensureElytraEquipped() {
        if (wearingElytra() || !this.invQueue.isEmpty()) {
            return;
        }
        int slot = hotbarSlot();
        if (usableElytra(ctx.player().getInventory().getNonEquipmentItems().get(slot))) {
            queueSwapChest(slot);
            return;
        }
        // the elytra is somewhere else in the inventory: bring it to the hotbar slot first
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (usableElytra(inv.get(i))) {
                queuePickupSwap(i < 9 ? i + 36 : i, slot + 36);
                return;
            }
        }
    }

    private void removeElytra() {
        if (!wearingElytra() || !this.invQueue.isEmpty()) {
            return;
        }
        queueSwapChest(hotbarSlot());
    }

    private boolean wearingElytra() {
        return ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    private boolean usableElytra(ItemStack stack) {
        return stack.getItem() == Items.ELYTRA
                && stack.getMaxDamage() - stack.getDamageValue() > Baritone.settings().elytraMinimumDurability.value;
    }

    private ItemStack findElytra() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (usableElytra(inv.get(i))) {
                return inv.get(i);
            }
        }
        return null;
    }

    private int hotbarSlot() {
        return Math.max(0, Math.min(8, Baritone.settings().elytraMaceHotbarSlot.value));
    }

    private void queueSwapChest(int hotbarSlot) {
        // one click: swaps the chest armor slot with the hotbar slot, whichever way it goes
        queueClick(ctx.player().inventoryMenu.containerId, CHEST_ARMOR_SLOT, hotbarSlot, ContainerInput.SWAP);
    }

    private void queuePickupSwap(int slotA, int slotB) {
        queueClick(ctx.player().inventoryMenu.containerId, slotA, 0, ContainerInput.PICKUP);
        queueClick(ctx.player().inventoryMenu.containerId, slotB, 0, ContainerInput.PICKUP);
        queueClick(ctx.player().inventoryMenu.containerId, slotA, 0, ContainerInput.PICKUP);
    }

    private void queueClick(int windowId, int slotId, int button, ContainerInput type) {
        this.invQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private void tickInventory() {
        if (this.invCooldown > 0) {
            this.invCooldown--;
            return;
        }
        Runnable click = this.invQueue.poll();
        if (click != null) {
            click.run();
            this.invCooldown = 1; // one click per tick; the smash swap has no time to spare
        }
    }

    private Vec3 predictedTargetPos() {
        Player target = this.targetEntity;
        return target.getEyePosition(1.0F).add(target.getDeltaMovement().scale(5.0));
    }

    private static BetterBlockPos feetOf(Player player) {
        return new BetterBlockPos(player.getX(), player.getY(), player.getZ());
    }

    private static boolean hasWindBurst(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) {
            return false;
        }
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.unwrapKey().map(key -> key.identifier().getPath().equals("wind_burst")).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
