package me.sand.minecraftaimod.agent;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.world.World;

import java.util.List;

public class AgentActions {

    private final ServerPlayerEntity agent;
    private final World world;

    private double speed = 0.24;
    private double maxSpeed = 0.25;
    private int currentHand = 0;

    // Mining state
    private BlockPos miningPos = null;
    private int miningTicks = 0;
    private Direction miningDir = null;
    private float blockBreakProg = 0.0f;
    private float prevBlockBreakProg = 0.0f;

    public AgentActions(ServerPlayerEntity agent, World world) {
        this.agent = agent;
        this.world = world;
    }

    public float getBlockBreakProg() {
        return blockBreakProg;
    }

    public float getPrevBlockBreakProg() {
        return prevBlockBreakProg;
    }

    // ========= Movement =========

    public void moveForward() {
        float yawRad = (float) Math.toRadians(agent.getYaw());
        agent.addVelocity(new Vec3d(-Math.sin(yawRad) * speed, 0, Math.cos(yawRad) * speed));
        clipVelocity();
    }

    public void moveBackward() {
        float yawRad = (float) Math.toRadians(agent.getYaw());
        agent.addVelocity(new Vec3d(Math.sin(yawRad) * speed, 0, -Math.cos(yawRad) * speed));
        clipVelocity();
    }

    public void moveRight() {
        Vec3d lookDir = agent.getRotationVec(1.0F);
        agent.addVelocity(new Vec3d(-lookDir.z * speed, 0, lookDir.x * speed));
        clipVelocity();
    }

    public void moveLeft() {
        Vec3d lookDir = agent.getRotationVec(1.0F);
        agent.addVelocity(new Vec3d(lookDir.z * speed, 0, -lookDir.x * speed));
        clipVelocity();
    }

    private void clipVelocity() {
        Vec3d vel = agent.getVelocity();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horizSpeed > maxSpeed) {
            double scaled = maxSpeed / horizSpeed;
            agent.setVelocity(new Vec3d(vel.x * scaled, vel.y, vel.z * scaled));
        }
    }

    // ========= Camera =========

    public void rotateLeft() {
        agent.setYaw(agent.getYaw() - 5f);
    }

    public void rotateRight() {
        agent.setYaw(agent.getYaw() + 5f);
    }

    public void lookUp() {
        agent.setPitch(Math.max(agent.getPitch() - 3f, -89f));
    }

    public void lookDown() {
        agent.setPitch(Math.min(agent.getPitch() + 3f, 89f));
    }

    // ========= Combat / Interaction =========

    public void tryHit() {
        HitResult hit = raycastWithEntities(agent, 4.5);
        agent.swingHand(Hand.MAIN_HAND);

        if (hit instanceof EntityHitResult ehr) {
            Entity target = ehr.getEntity();
            agent.attack(target);
            agent.resetLastAttackedTicks();
        } else if (hit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (!pos.equals(miningPos)) {
                stopMiningIfNeeded();
                agent.interactionManager.processBlockBreakingAction(
                        pos,
                        PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                        bhr.getSide(),
                        world.getHeight(),
                        0
                );
                miningPos = pos.toImmutable();
                miningDir = bhr.getSide();
                miningTicks = 0;
                blockBreakProg = 0.0f;
                prevBlockBreakProg = 0.0f;
                return;
            }

            miningTicks++;
            float delta = state.calcBlockBreakingDelta(agent, world, pos);
            blockBreakProg = delta * (miningTicks + 1);

            if (blockBreakProg >= 1.0f) {
                agent.interactionManager.processBlockBreakingAction(
                        pos,
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        miningDir,
                        world.getHeight(),
                        0
                );
                blockBreakProg = 0.0f;
                prevBlockBreakProg = 0.0f;
                miningPos = null;
                miningTicks = 0;
                miningDir = null;
            }
            return;
        }
        stopMiningIfNeeded();
    }

    public void tryPlace() {
        HitResult hit = raycastWithEntities(agent, 4.5);
        if (hit.getType() == HitResult.Type.MISS) {
            agent.interactionManager.interactItem(agent, world, agent.getMainHandStack(), Hand.MAIN_HAND);
            return;
        }
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            agent.swingHand(Hand.MAIN_HAND);
            agent.interactionManager.interactBlock(
                    agent, world, agent.getMainHandStack(), Hand.MAIN_HAND, bhr
            );
        }
    }

    public void stopMiningIfNeeded() {
        if (miningPos != null) {
            agent.interactionManager.processBlockBreakingAction(
                    miningPos,
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                    miningDir,
                    world.getHeight(),
                    0
            );
            miningPos = null;
            miningDir = null;
            miningTicks = 0;
        }
    }

    // ========= Hotbar =========

    public void setHotbarSlot(int slot) {
        if (slot != currentHand) {
            agent.getInventory().setSelectedSlot(slot);
            currentHand = slot;
        }
    }

    // ========= Raycast utility =========

    public static HitResult raycastWithEntities(ServerPlayerEntity player, double reach) {
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d end = start.add(look.multiply(reach));

        HitResult blockHit = player.raycast(reach, 1.0f, false);

        Box box = player.getBoundingBox().stretch(look.multiply(reach)).expand(1.0D);
        EntityHitResult entityHit = ProjectileUtil.raycast(
                player, start, end, box,
                entity -> !entity.isSpectator() && entity.isAttackable(),
                reach * reach
        );

        if (entityHit != null) {
            double entityDist = entityHit.getPos().squaredDistanceTo(start);
            double blockDist = blockHit.getPos().squaredDistanceTo(start);
            if (entityDist < blockDist) {
                return entityHit;
            }
        }
        return blockHit;
    }

    // ========= Apply full action vector =========

    public void applyAction(List<Float> actions, AgentInventory inventory, AgentCrafting crafting, boolean tableNearby) {
        if (agent == null) return;
        int invAct = actions.get(0).intValue();

        if (invAct == 0) {
            int movement = actions.get(1).intValue();
            int sideMovement = actions.get(2).intValue();
            int itemUse = actions.get(3).intValue();
            int hotbarIdx = actions.get(4).intValue();
            int panId = actions.get(5).intValue();

            // Forward/backward movement
            switch (movement) {
                case 0, 2 -> moveForward();
                case 1 -> moveBackward();
            }

            // Side movement
            switch (sideMovement) {
                case 0 -> moveLeft();
                case 1 -> moveRight();
            }

            // Jump
            if ((movement == 2 || movement == 3) && (agent.isOnGround() || agent.isInLava() || agent.isSubmergedInWater())) {
                agent.jump();
            }

            // Item use
            if (itemUse == 0) tryHit();
            else if (itemUse == 1) tryPlace();
            else stopMiningIfNeeded();

            // Hotbar
            setHotbarSlot(hotbarIdx);

            // Camera
            switch (panId) {
                case 0 -> lookUp();
                case 1 -> lookDown();
                case 2 -> rotateLeft();
                case 3 -> rotateRight();
            }
        } else if (invAct == 1) {
            inventory.swapItems(actions.get(6).intValue(), actions.get(7).intValue());
        } else if (invAct == 2) {
            inventory.dropItem(actions.get(8).intValue(), actions.get(9).intValue() == 1);
        } else if (invAct == 3) {
            crafting.craft(actions.get(10).intValue(), tableNearby);
        } else {
            int invSlot = actions.get(1).intValue();
            int contSlot = actions.get(2).intValue();
            int closeCont = actions.get(3).intValue();

            if (closeCont == 1) {
                agent.closeHandledScreen();
            } else {
                inventory.swapItems(invSlot, contSlot);
            }
        }
    }
}
