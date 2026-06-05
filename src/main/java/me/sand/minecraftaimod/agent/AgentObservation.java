package me.sand.minecraftaimod.agent;

import me.sand.minecraftaimod.protobuf.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AgentObservation {

    private final ServerPlayerEntity agent;
    private final World world;
    private final AgentInventory inventory;
    private final AgentActions actions;

    private boolean tableNearby = false;
    private BlockPos nearestWood = null;

    public AgentObservation(ServerPlayerEntity agent, World world, AgentInventory inventory, AgentActions actions) {
        this.agent = agent;
        this.world = world;
        this.inventory = inventory;
        this.actions = actions;
    }

    public boolean isTableNearby() {
        return tableNearby;
    } // needed in Agent Actions

    public BlockPos getNearestWood() {
        return nearestWood;
    }

    // ========= Agent info =========

    public double[] getAgentInfo() {
        double health = agent.getHealth() / 20.0;
        double hunger = agent.getHungerManager().getFoodLevel() / 20.0;
        double saturation = agent.getHungerManager().getSaturationLevel() / 20.0;

        Vec3d vel = agent.getVelocity();
        double vx = vel.x / 0.24;
        double vy = vel.y / 0.5;
        double vz = vel.z / 0.24;

        BlockState blockBelow = world.getBlockState(agent.getBlockPos().down());
        double blockBelowId = Block.STATE_IDS.getRawId(blockBelow);

        double colliding = agent.horizontalCollision || agent.verticalCollision ? 1 : 0;
        double isSneak = agent.isSneaking() ? 1 : 0;
        double isOnFire = agent.isOnFire() ? 1 : 0;
        double inWater = agent.isTouchingWater() ? 1 : 0;
        double inLava = agent.isInLava() ? 1 : 0;
        double onGround = agent.isOnGround() ? 1 : 0;
        double isFalling = vy < -0.6 ? 1 : 0;
        double wasHurt = agent.hurtTime > 0 ? 1 : 0;

        double mainHandCount = agent.getMainHandStack().getCount();
        double mainHandSlot = agent.getInventory().getSelectedSlot();
        double time = (double) (world.getTime() % 24000) / 24000.0;
        double lightLevel = world.getLightLevel(agent.getBlockPos()) / 15.0;

        // Raycast - what is the agent looking at
        HitResult raycast = AgentActions.raycastWithEntities(agent, 4.5);
        double lookingAt = 0;
        double[] row;

        switch (raycast.getType()) {
            case BLOCK:
                lookingAt = 1;
                BlockHitResult blockHit = (BlockHitResult) raycast;
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = agent.getEntityWorld().getBlockState(pos);
                row = new double[]{Registries.BLOCK.getRawId(state.getBlock()), 0, 0, 0, 0, 0, 0, 0};
                break;
            case ENTITY:
                lookingAt = 2;
                EntityHitResult entityHit = (EntityHitResult) raycast;
                Entity e = entityHit.getEntity();
                row = new double[]{
                        Registries.ENTITY_TYPE.getRawId(e.getType()),
                        (e instanceof Monster) ? 1 : 0,
                        (e instanceof Angerable) ? 1 : 0,
                        (e instanceof PassiveEntity) ? 1 : 0,
                        (!(e instanceof Monster) && !(e instanceof Angerable) && !(e instanceof PassiveEntity)) ? 1 : 0,
                        e.getX() - agent.getX(),
                        e.getY() - agent.getY(),
                        e.getZ() - agent.getZ()
                };
                break;
            default:
                row = new double[]{0, 0, 0, 0, 0, 0, 0, 0};
                break;
        }

        double normYaw = agent.getYaw() / 180.0;
        double normPitch = agent.getPitch() / 90.0;

        return new double[]{
                health, hunger, saturation, normYaw, normPitch,
                vx, vy, vz, blockBelowId, colliding,
                isSneak, isOnFire, inWater, inLava, onGround,
                isFalling, wasHurt, mainHandCount, mainHandSlot, time,
                lightLevel, actions.getBlockBreakProg(), lookingAt,
                row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7]
        };
    }

    public double[] getPos() {
        return new double[]{agent.getX(), agent.getY(), agent.getZ(), agent.getYaw(), agent.getPitch()};
    }

    // ========= Nearby entities =========

    public double[][] getNearbyEntities() {
        double agentSearchRadius = 10.0;
        double[][] foundEntities = new double[10][8];

        Box box = agent.getBoundingBox().expand(agentSearchRadius);
        List<Entity> nearbyEntities = agent.getEntityWorld().getOtherEntities(agent, box);

        int idx = 0;
        for (Entity entity : nearbyEntities) {
            if (idx >= 10) break;
            if (entity instanceof PlayerEntity || !(entity instanceof LivingEntity)) continue;

            int isMonster = entity instanceof Monster ? 1 : 0;
            int isAngerable = entity instanceof Angerable ? 1 : 0;
            int isPassive = entity instanceof PassiveEntity ? 1 : 0;
            int isUnknown = (isMonster != 1 && isAngerable != 1 && isPassive != 1) ? 1 : 0;

            foundEntities[idx] = new double[]{
                    Registries.ENTITY_TYPE.getRawId(entity.getType()),
                    isMonster, isAngerable, isPassive, isUnknown,
                    entity.getX() - agent.getX(),
                    entity.getY() - agent.getY(),
                    entity.getZ() - agent.getZ()
            };
            idx++;
        }

        for (int i = idx; i < 10; i++) {
            Arrays.fill(foundEntities[i], 0);
        }
        return foundEntities;
    }

    // ========= Nearby blocks =========

    public double[][][] getNearbyBlocks() {
        int coreRadius = 8;
        int verticalRadius = 4;
        int tableWithinXBlocks = 3;

        double[][][] foundBlocks = new double[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1];

        int ax = agent.getBlockX();
        int ay = agent.getBlockY();
        int az = agent.getBlockZ();

        tableNearby = false;
        nearestWood = null;

        for (int x = ax - coreRadius; x <= ax + coreRadius; x++) {
            for (int y = ay - verticalRadius; y <= ay + verticalRadius; y++) {
                for (int z = az - coreRadius; z <= az + coreRadius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = agent.getEntityWorld().getBlockState(pos);

                    // Check for crafting table
                    if (state.isOf(Blocks.CRAFTING_TABLE)) {
                        if (Math.abs(x - ax) <= tableWithinXBlocks &&
                                Math.abs(y - ay) <= tableWithinXBlocks &&
                                Math.abs(z - az) <= tableWithinXBlocks) {
                            tableNearby = true;
                        }
                    }

                    // Track nearest wood
                    if (state.isIn(BlockTags.LOGS)) {
                        double dist = Math.sqrt(Math.pow(ax - x, 2) + Math.pow(ay - y, 2) + Math.pow(az - z, 2));
                        if (nearestWood == null) {
                            nearestWood = pos;
                        } else {
                            double currentDist = Math.sqrt(Math.pow(ax - nearestWood.getX(), 2)
                                    + Math.pow(ay - nearestWood.getY(), 2)
                                    + Math.pow(az - nearestWood.getZ(), 2));
                            if (dist < currentDist) nearestWood = pos;
                        }
                    }

                    int relX = x - ax + coreRadius;
                    int relY = y - ay + verticalRadius;
                    int relZ = z - az + coreRadius;
                    foundBlocks[relX][relY][relZ] = Registries.BLOCK.getRawId(state.getBlock());
                }
            }
        }
        return foundBlocks;
    }

    // ========= Nearby item drops =========

    public double[][][][] getNearbyItemDrops() {
        int coreRadius = 8;
        int verticalRadius = 3;

        double[][][][] foundItems = new double[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1][10];

        for (int i = 0; i < foundItems.length; i++)
            for (int j = 0; j < foundItems[i].length; j++)
                for (int k = 0; k < foundItems[i][j].length; k++)
                    foundItems[i][j][k] = new double[10];

        int ax = agent.getBlockX();
        int ay = agent.getBlockY();
        int az = agent.getBlockZ();

        Box box = new Box(
                agent.getX() - coreRadius, agent.getY() - verticalRadius, agent.getZ() - coreRadius,
                agent.getX() + coreRadius, agent.getY() + verticalRadius, agent.getZ() + coreRadius
        );

        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box, e -> true);

        for (ItemEntity item : items) {
            int ix = item.getBlockX() - ax + coreRadius;
            int iy = item.getBlockY() - ay + verticalRadius;
            int iz = item.getBlockZ() - az + coreRadius;

            if (ix < 0 || iy < 0 || iz < 0 ||
                    ix >= foundItems.length ||
                    iy >= foundItems[0].length ||
                    iz >= foundItems[0][0].length)
                continue;

            foundItems[ix][iy][iz] = inventory.getItemInfo(item.getStack());
        }
        return foundItems;
    }

    private double isWalkable(World w, int x, int y, int z) {
        BlockPos footPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);

        boolean footClear = !w.getBlockState(footPos).isSolidBlock(w, footPos);
        boolean headClear = !w.getBlockState(headPos).isSolidBlock(w, headPos);

        return (footClear && headClear) ? 1.0 : 0.0;
    }

    public double[] getDirectionLabels() {
        float yawRad = (float) Math.toRadians(agent.getYaw());
        int ax = agent.getBlockX();
        int ay = agent.getBlockY();
        int az = agent.getBlockZ();

        // Forward/backward relative to yaw
        int fx = ax + Math.round(-((float)Math.sin(yawRad)));
        int fz = az + Math.round((float)Math.cos(yawRad));
        int bx = ax + Math.round((float)Math.sin(yawRad));
        int bz = az + Math.round(-((float)Math.cos(yawRad)));

        // Left/right (perpendicular to yaw)
        int lx = ax + Math.round((float)Math.cos(yawRad));
        int lz = az + Math.round((float)Math.sin(yawRad));
        int rx = ax + Math.round(-((float)Math.cos(yawRad)));
        int rz = az + Math.round(-((float)Math.sin(yawRad)));

        World w = agent.getEntityWorld();

        return new double[] {
                isWalkable(w, fx, ay, fz), // Forward
                isWalkable(w, bx, ay, bz), // Backward
                isWalkable(w, lx, ay, lz), // Left
                isWalkable(w, rx, ay, rz)  // Right
        };
    }

    // ========= Send full state to Python =========

    public void sendStateInfo(MinecraftServer server, DataOutputStream output) {
        double[] agentInfo = getAgentInfo();
        double[][] inventoryArray = inventory.getInventory();
        double[][] nearbyEntities = getNearbyEntities();
        double[][][] nearbyBlocks = getNearbyBlocks();
        double[][][][] nearbyItems = getNearbyItemDrops();
        double[] walkableSpaces = getDirectionLabels();

        List<Double> walkableSpacesList = new ArrayList<>(walkableSpaces.length);
        for(double v : walkableSpaces) walkableSpacesList.add(v);

        // Agent info
        List<Double> agentInfoList = new ArrayList<>(agentInfo.length);
        for (double v : agentInfo) agentInfoList.add(v);

        // Inventory
        Matrix.Builder inventoryMatrix = Matrix.newBuilder();
        for (double[] row : inventoryArray) {
            Row.Builder rb = Row.newBuilder();
            for (double v : row) rb.addValues(v);
            inventoryMatrix.addRows(rb);
        }

        // Entities
        Matrix.Builder entitiesMatrix = Matrix.newBuilder();
        for (double[] row : nearbyEntities) {
            Row.Builder rb = Row.newBuilder();
            for (double v : row) rb.addValues(v);
            entitiesMatrix.addRows(rb);
        }

        // Blocks
        Matrix3D.Builder blocksMatrix = Matrix3D.newBuilder();
        for (double[][] matrix : nearbyBlocks) {
            Matrix.Builder mb = Matrix.newBuilder();
            for (double[] row : matrix) {
                Row.Builder rb = Row.newBuilder();
                for (double v : row) rb.addValues(v);
                mb.addRows(rb);
            }
            blocksMatrix.addMatrix(mb);
        }

        // Item drops
        Matrix4D.Builder itemsMatrix = Matrix4D.newBuilder();
        for (double[][][] m3d : nearbyItems) {
            Matrix3D.Builder m3b = Matrix3D.newBuilder();
            for (double[][] matrix : m3d) {
                Matrix.Builder mb = Matrix.newBuilder();
                for (double[] row : matrix) {
                    Row.Builder rb = Row.newBuilder();
                    for (double v : row) rb.addValues(v);
                    mb.addRows(rb);
                }
                m3b.addMatrix(mb);
            }
            itemsMatrix.addMatrix3D(m3b);
        }

        // Container
        ScreenHandler handler = agent.currentScreenHandler;
        ContainerType openContainer = inventory.getContainerType(handler);

        if (openContainer == ContainerType.PlayerInventory) {
            openContainer = ContainerType.NONE;
        } else if (openContainer == ContainerType.UNKNOWN) {
            agent.closeHandledScreen();
            openContainer = ContainerType.NONE;
        }

        double[][] container = inventory.getContainer(handler, openContainer);
        double[] containerMask = inventory.getContainerMask(openContainer);

        Matrix.Builder containerMatrix = Matrix.newBuilder();
        for (double[] row : container) {
            Row.Builder rb = Row.newBuilder();
            for (double v : row) rb.addValues(v);
            containerMatrix.addRows(rb);
        }

        List<Double> containerMaskList = new ArrayList<>(containerMask.length);
        for (double v : containerMask) containerMaskList.add(v);

        // Build protobuf
        State stateInfo = State.newBuilder()
                .addAllAgentInfo(agentInfoList)
                .addAllIsWalkable(walkableSpacesList)
                .setInventory(inventoryMatrix)
                .setNearbyEntities(entitiesMatrix)
                .setNearbyBlocks(blocksMatrix)
                .setNearbyItemDrops(itemsMatrix)
                .setContainerType(openContainer.ordinal())
                .setContainer(containerMatrix)
                .addAllContainerMask(containerMaskList)
                .build();

        try {
            byte[] payload = stateInfo.toByteArray();
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
            System.out.println("Failed to send data to python, shutting down training...");
        }
    }
}
