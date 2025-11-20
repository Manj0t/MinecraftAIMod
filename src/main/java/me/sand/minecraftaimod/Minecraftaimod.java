package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.sand.minecraftaimod.protobuf.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


public class Minecraftaimod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("collect-state-info");
    private volatile boolean collecting = false;

    private ServerPlayerEntity agent = null;
    private String agentName = null;
//    private PlayerInventory agentInventory = null;
    World world = null;


    Socket socket = null;
    DataInputStream input = null;
    DataOutputStream output = null;

    private volatile boolean sendState = false;
    private volatile boolean sendReward = false;
    private volatile boolean recieveAction = false;
    private volatile boolean waitForNextRollout = false;
    private volatile boolean resetPlayer = false;

    private final Map<Integer, Runnable> movementActions = new HashMap<>();
    private final Map<Integer, Runnable> panCam = new HashMap<>();
//    private HashSet<Point> visitedRegion = new HashSet<>();

    private int currentHand = 0;
    private double speed = 0.24;
    double maxSpeed = 0.25;

    Vec3d lastPos = null;
    Double lastY = null;
    float penalty = 0;
    boolean wasOnGround = false;
    double agentPrevhealth = 0;

    BlockPos nearestWood = null;

    record Tuple3(int x, int y, int z) {}

    Set<Tuple3> visitedRegion = new HashSet<>();
    Set<Tuple3> visitedWood = new HashSet<>();

    int loop = 0;
    boolean isLavaArea = false;

    boolean spawnPlayer = false;

    Deque<Vec3d> pastPositions = new ArrayDeque<>();
    final int WINDOW = 10; // 10 ticks = 0.5 sec
    int lastMovement = 0;


    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    literal("start_training")
                            .then(argument("agentName", StringArgumentType.string())
                                    .executes(ctx -> {
                                        MinecraftServer server = ctx.getSource().getServer();
                                        agentName = StringArgumentType.getString(ctx, "agentName").toLowerCase();

                                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");

                                        collecting = true;
                                        ctx.getSource().sendFeedback(() -> Text.literal("Started training!"), false);

                                        try {
                                            System.out.println("INFO: Made process");


                                            socket = new Socket("localhost", 5000);
                                            socket.setTcpNoDelay(true);
                                            socket.setSoTimeout(0);

                                            input = new DataInputStream(socket.getInputStream());
                                            output = new DataOutputStream(socket.getOutputStream());
                                            System.out.println("INFO: Socket Connected");

                                            int agent_info_dim = 21;
                                            int numItems = Registries.ITEM.size();
                                            int numBlocks = Registries.BLOCK.size();
                                            int numEntities = Registries.ENTITY_TYPE.size();


                                            output.writeInt(agent_info_dim);
                                            output.writeInt(numItems);
                                            output.writeInt(numBlocks);
                                            output.writeInt(numEntities);
                                            output.flush();

                                            resetPlayer = true;
//                                            out.println("exit");
//                                            System.out.println("Python replied: " + in.readLine());

//                                            socket.close();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            System.out.println("ERROR: FAILED ");
                                        }
                                        System.out.println("Success? ");

                                        movementActions.put(0, this::moveForward);
                                        movementActions.put(1, this::moveBackward);
                                        movementActions.put(2, this::moveLeft);
                                        movementActions.put(3, this::moveRight);
                                        movementActions.put(4, null);

                                        panCam.put(0, this::lookUp);
                                        panCam.put(1, this::lookDown);
                                        panCam.put(2, this::rotateLeft);
                                        panCam.put(3, this::rotateRight);
                                        panCam.put(4, null);

                                        return 1;
            })));


            dispatcher.register(literal("stop_training").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();

                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");

                agent = null;
                agentName = null;

                collecting = false;

                if(socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                        socket = null;
                    }
                    catch (IOException e) {
                        // ignore?
                    }

                }

                ctx.getSource().sendFeedback(() -> Text.literal("Stopped training!"), false);
                return 1;
            }));

            dispatcher.register(literal("close_socket").executes(ctx -> {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                        socket = null;
                    } catch (IOException e) {
                        //ignore already closed, shouldn't ever really hit this
                    }

                }
                return 1;
            }));

            dispatcher.register(literal("save_state").executes(ctx -> {
                penalty = 10000000;
                return 1;
            }));
        });


        /*
         ************************************************
         **   Main State Information Collection Loop   **
         ************************************************
         */
        ServerTickEvents.END_SERVER_TICK.register(server -> {
                    if (!collecting) return; // only run if training started

                    if(spawnPlayer){
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
                        spawnPlayer = false;
                        return;
                    }
                    if (agent == null && agentName != null) {
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            if (agentName.equals(player.getName().getString().toLowerCase())) {
                                agent = player;
                                lastPos = null;
                                world = agent.getEntityWorld();
                                lastY = null;
                                visitedRegion.clear();
                                visitedWood.clear();
                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/gamemode survival " + agentName);
                                System.out.println("found");
                                break;
                            }
                        }
                        if (agent == null) return;
                    }
                    // Agent Position Information
                    if (resetPlayer) {
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");
                        resetPlayer = false;
                        waitForNextRollout = true;
                        return;
                    }
                    if(waitForNextRollout) {
                        lastPos = null;
                        try {
                            System.out.println("INFO: Waiting for next rollout");

                            int startRollout = input.readInt();
                            spawnPlayer = true;
                            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/time set day");
                            visitedRegion.clear();
                            visitedWood.clear();
                            lastY = null;
                            lastPos = null;
                            //                            loop += 1;
                            //                            if(loop % 5 == 0 ){
                            //                                if(isLavaArea)
                            //                                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/setworldspawn 194 74 -208");
                            //                                else
                            //                                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/setworldspawn 460 95 -225");
                            //
                            //                                isLavaArea = !isLavaArea;
                            //                            }
                            //                            System.out.println("startRollout: " + startRollout);
                            if(startRollout > 0) {
                                waitForNextRollout = false;
                                sendState = true;
                            }
                        }catch (IOException e) {
                            System.err.println("Error reading rollout command: " + e.getMessage());
                        }
                        agent = null;
                        return;
                    }

                    if(!server.getPlayerManager().getPlayerList().contains(agent)){
                        penalty = -10.0f;
                        //                                done_tag = 1;
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
                        agent = null;
                        return;
                    }

                    if (sendState) {
                        sendStateInfo(server);
                        sendState = false;
                        recieveAction = true;
                        return;
                    }
            List<Float> actions = null;
//            Recieve action
                    if (recieveAction) {
                        try {
                            int respLen = input.readInt();
                            byte[] resp = input.readNBytes(respLen);

                            Action action = Action.parseFrom(resp);

                            actions = action.getActionsList();

                            applyAction(actions);

//                            System.out.println(Arrays.deepToString(actions.toArray()));

                            recieveAction = false;
                            sendReward = true;
                        } catch (Exception e) {

                        }
                    }

                    if (sendReward) {
                        try{
                            float reward = (float) getReward(actions) + penalty;
//                            System.out.println("Reward: " + reward);
                            int done_tag = penalty == -10.0f ? 1 : 0;
                            penalty = 0;


                            output.writeFloat(reward);
                            output.writeInt(done_tag);
                            output.flush();

                            sendReward = false;


                            int continueRollout = input.readInt();

                            if(continueRollout == 1){
                                sendState = true;
                            } else{
                                resetPlayer = true;
                            }


                        }
                    catch(IOException e){
                        System.out.println("ERROR: FAILED");
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
                    }
                }

        });

    }

    private double getReward(List<Float> actions) {
        Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());
        int REGION_SIZE = 2;

        pastPositions.add(currentPos);
        if(pastPositions.size() > WINDOW){
            pastPositions.removeFirst();
        }

        if (lastPos == null) {
            lastPos = currentPos;
            agentPrevhealth = agent.getHealth();
            return 0.0;
        }

        double reward = 0.0;

        double dx = currentPos.x - lastPos.x;
        double dz = currentPos.z - lastPos.z;
        double dist = Math.sqrt(dx*dx + dz*dz);
        // ------------------------
        //  No movement PENALTY
        // ------------------------
//        if (Math.abs(dx) < 0.02 && Math.abs(dz) < 0.02)
//            reward -= 0.01;

        // ONLY reward if dist > threshold => avoids wall-collisions giving reward
//        if (dist > 0.05)
//            reward += dist * 1.0;

        // ------------------------
        // Region explor
        // ------------------------
        int rx = (int)Math.floor(currentPos.x / REGION_SIZE);
        int ry = (int)Math.floor(currentPos.y / REGION_SIZE);
        int rz = (int)Math.floor(currentPos.z / REGION_SIZE);

        boolean isNew = visitedRegion.add(new Tuple3(rx, 0, rz));
        if (isNew) reward += 3.0;

        if(nearestWood != null) {
            double current_dist_to_wood = Math.sqrt(Math.pow(currentPos.x - nearestWood.getX(), 2) + Math.pow(currentPos.z - nearestWood.getZ(), 2));
            double prev_dist_to_wood = Math.sqrt(Math.pow(lastPos.x - nearestWood.getX(), 2) + Math.pow(lastPos.z - nearestWood.getZ(), 2));
            double diff = (prev_dist_to_wood - current_dist_to_wood);
            if(diff * 2.0 > 0.3)
                reward += diff * 2.0;

            if(current_dist_to_wood <= 2.5){
                visitedWood.add(new Tuple3(nearestWood.getX(), nearestWood.getY(), nearestWood.getZ()));
                nearestWood = null;
            }
        }



        // forward movement reward
        Vec3d forward = agent.getRotationVec(1F);
        Vec3d forwardFlat = new Vec3d(forward.x, 0, forward.z).normalize();

        Vec3d oldest = pastPositions.getFirst();
        Vec3d netMove = currentPos.subtract(oldest);
        Vec3d netMoveFlat = new  Vec3d(netMove.getX(), 0, netMove.getZ());

        double netForwardProg = forwardFlat.dotProduct(netMoveFlat);

        boolean jumped = actions.get(1) > 0.5;
        boolean onGround = agent.isOnGround();


        if (!jumped && netForwardProg > 2.0 && onGround) {
            reward += netForwardProg * 1.2;
        }

        double movement = Math.sqrt(netMove.x * netMove.x + netMove.z * netMove.z);

        // Encourage agent to make substantial movement. Longer not good movement, punish more
        if(movement > 0.05 && onGround){
            lastMovement = 0;
        }

        reward -= 0.01 * lastMovement;

        lastMovement += 1;

//        System.out.println("prog: " + netForwardProg);

// Penalize ANY airtime
        if (!onGround && !agent.isInLava()) {
            reward -= 0.05;   // light penalty
        }
        if (actions.get(1) > 0.5)
            reward -= 0.3;

        boolean inLava = agent.isInLava();

        if (inLava || agent.isOnFire()) {
            reward -= 1.0;
        }

        // ------------------------
        // SMALL HEALTH LOSS PENALTY
        // ------------------------
        if (agent.getHealth() < agentPrevhealth)
            reward -= 1.0;

        if(dist < 0.004 && netForwardProg > 0.1){
            reward -= 0.02;
        }

        // ------------------------
        // SAVE LAST
        // ------------------------
        lastPos = currentPos;
        agentPrevhealth = agent.getHealth();
        return reward;
    }

    private void applyAction(List<Float> actions) {
        int movement = actions.get(0).intValue();
        int jump = actions.get(1).intValue();
        int item_use = actions.get(2).intValue();
        int hotbar_idx = actions.get(3).intValue();
        int pan_id = actions.get(4).intValue();

        Runnable movementAction = movementActions.get(movement);
        if (movementAction != null) {
            movementAction.run();
        }

        if(jump > 0 && agent.isOnGround()){
            agent.jump();
        }

        // 2 is don't use item
//        if(item_use < 2){
//            if(item_use == 0){
//                HitResult hit = agent.raycast(4.5, 1.0F, false); // reach ~4.5 blocks
//                if (hit.getType() != HitResult.Type.BLOCK) return;
//
//                BlockHitResult bhr = (BlockHitResult) hit;
//                BlockPos pos = bhr.getBlockPos();
//                Direction side = bhr.getSide();
//
//                agent.swingHand(Hand.MAIN_HAND);
//
//                // Survival-style mining (call this EVERY tick while "holding" LMB):
//                agent.interactionManager.updateBlockBreakingProgress(pos, side);
//            }else{
//                agent.swingHand(Hand.MAIN_HAND);
//
//                var result = agent.interactionManager.interactItem(agent, world, Hand.MAIN_HAND);
//
//                if (!result.isAccepted()) {
//                    HitResult hit = agent.raycast(4.5, 1.0F, false);
//                    if (hit.getType() == HitResult.Type.BLOCK) {
//                        agent.interactionManager.interactBlock(
//                                agent, world, Hand.MAIN_HAND, (BlockHitResult) hit
//                        );
//                    }
//                }
//            }
//        }

        if(hotbar_idx != currentHand){
            agent.getInventory().setSelectedSlot(hotbar_idx);
            currentHand = hotbar_idx;
        }

        Runnable pan_cam_action = panCam.get(pan_id);
        if (pan_cam_action != null) {
            pan_cam_action.run();
        }


    }
    private void rotateLeft() {
        agent.setYaw(agent.getYaw() - 5f);  // turn 5 degrees left
    }

    private void rotateRight() {
        agent.setYaw(agent.getYaw() + 5f);  // turn 5 degrees right
    }

    private void lookUp() {
        agent.setPitch(Math.max(agent.getPitch() - 3f, -89f)); // can't look past straight up
    }

    private void lookDown() {
        agent.setPitch(Math.min(agent.getPitch() + 3f, 89f)); // can't look past straight down
    }

//    private void moveForward(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//        agent.setVelocity(lookDir.x * speed, agent.getVelocity().y, lookDir.z * speed);
//    }
//
//    private void moveBackward(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//        agent.setVelocity(-lookDir.x * speed, agent.getVelocity().y, -lookDir.z * speed);
//    }
//
//    private void moveRight(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//        agent.setVelocity(-lookDir.z * speed, agent.getVelocity().y, lookDir.x * speed);
//    }
//
//    private void moveLeft(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//        agent.setVelocity(lookDir.z * speed, agent.getVelocity().y, -lookDir.x * speed);
//    }

    private void clip_velocity(){
        Vec3d vel = agent.getVelocity();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        if(horizSpeed > maxSpeed){
            double scaled = maxSpeed / horizSpeed;
            Vec3d new_vel = new Vec3d(vel.x * scaled, vel.y, vel.z * scaled);
            agent.setVelocity(new_vel);
        }
    }

    private void moveForward(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.x * speed, 0, lookDir.z * speed);
        agent.addVelocity(movement);

        clip_velocity();
    }

    private void moveBackward(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.x * -speed, 0, lookDir.z * -speed);
        agent.addVelocity(movement);

        clip_velocity();
    }

    private void moveRight(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(-lookDir.z * speed, 0, lookDir.x * speed);
        agent.addVelocity(movement);

        clip_velocity();
    }

    private void moveLeft(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.z * speed, 0, -lookDir.x * speed);
        agent.addVelocity(movement);

        clip_velocity();
    }


//    private void jump(){
//        if (agent.isOnGround()) {
//            Vec3d vel = agent.getVelocity();
//            agent.setVelocity(vel.x, 0.5, vel.z);
//            wasOnGround = true;
//
//        }
//    }

    private void updateHand(int jump){

    }

    private void sendStateInfo(MinecraftServer server){
        double[] agentInfo = getAgentInfo();
        // Get agent inventory
        // Pass through transformer to encode values as a flattened vector
        double[][] inventoryArray = getInventory();

        // Get nearby entities
        double[][] nearbyEntities = getNearbyEntities();

        // Get nearby Block information
        double[][][] nearbyBlocks = getNearbyBlocks();

        // prolly could hard code this into the function?
        List<Double> agentInfoList = new ArrayList<>(agentInfo.length);
        for (double value : agentInfo) {
            agentInfoList.add(value);
        }

        Matrix.Builder inventoryMatrix = Matrix.newBuilder();
        for(double[] row : inventoryArray) {
            Row.Builder rowBuilder = Row.newBuilder();
            for(double value : row) {
                rowBuilder.addValues(value);
            }
            inventoryMatrix.addRows(rowBuilder);
        }

        Matrix.Builder nearbyEntitiesMatrix = Matrix.newBuilder();
        for(double[] row : nearbyEntities) {
            Row.Builder rowBuilder = Row.newBuilder();
            for(double value : row) {
                rowBuilder.addValues(value);
            }
            nearbyEntitiesMatrix.addRows(rowBuilder);
        }

        Matrix3D.Builder nearbyBlocksMatrix = Matrix3D.newBuilder();
        for(double[][] matrix : nearbyBlocks) {
            Matrix.Builder matrixBuilder = Matrix.newBuilder();
            for(double[] row : matrix) {
                Row.Builder rowBuilder = Row.newBuilder();
                for(double value : row) {
                    rowBuilder.addValues(value);
                }
                matrixBuilder.addRows(rowBuilder);
            }
            nearbyBlocksMatrix.addMatrix(matrixBuilder);
        }

        State stateInfo = State.newBuilder()
                .addAllAgentInfo(agentInfoList)
                .setInventory(inventoryMatrix)
                .setNearbyEntities(nearbyEntitiesMatrix)
                .setNearbyBlocks(nearbyBlocksMatrix)
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


    public double[] getAgentInfo(){
        double health = agent.getHealth();
        double hunger = agent.getHungerManager().getFoodLevel();
        double saturation = agent.getHungerManager().getSaturationLevel();

//        double[] agentPos = getPos();

        Vec3d vel = agent.getVelocity();
        double vx = vel.x;
        double vy = vel.y;
        double vz = vel.z;

        health /= 20.0;
        hunger /= 20.0;
        saturation /= 20.0;
        vy /= 0.5;
        vx /= speed;
        vz /= speed;

        BlockState blockBelow = world.getBlockState(agent.getBlockPos().down());
        double blockBelowId = Block.STATE_IDS.getRawId(blockBelow);

        double colliding = agent.horizontalCollision || agent.verticalCollision ? 1 : 0;
        double isSneak = agent.isSneaking() ? 1 : 0;

        double isOnFire = agent.isOnFire() ? 1 : 0;
        double inWater = agent.isTouchingWater() ? 1 : 0;
        double inLava = agent.isInLava() ? 1 : 0;
        double onGround = agent.isOnGround() ? 1 : 0;
        double isFalling  = vy < -0.6 ? 1 : 0;
        double wasHurt = agent.hurtTime > 0 ? 1 : 0;

        double mainHandCount = agent.getMainHandStack().getCount();
        double mainHandSlot = agent.getInventory().getSelectedSlot();

        double time = (double) (world.getTime() % 24000) / 24000.0;

        double lightLevel = world.getLightLevel(agent.getBlockPos()) / 15.0;

        HitResult raycast = agent.raycast(6.0, 0.0F, false);

        double looking_at = 0;
        double looking_at_id = 0;

        double[] row;
        switch(raycast.getType()) {
            case BLOCK:
                looking_at = 1;

                BlockHitResult blockHit = (BlockHitResult) raycast;
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = agent.getEntityWorld().getBlockState(pos);

                row = new double[]{
                        Registries.BLOCK.getRawId(state.getBlock()),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                };
                break;
            case ENTITY:
                looking_at = 2;

                EntityHitResult entityHit = (EntityHitResult) raycast;
                Entity e = entityHit.getEntity();

                row = new double[]{
                        Registries.ENTITY_TYPE.getRawId(e.getType()),
                        (e instanceof Monster) ? 1 : 0,
                        (e instanceof Angerable) ? 1 : 0,
                        (e instanceof PassiveEntity) ? 1 : 0,
                        ((!(e instanceof Monster)) && (!(e instanceof Angerable)) && (!(e instanceof PassiveEntity))) ? 1 : 0,
                        e.getX() - agent.getX(),
                        e.getY() - agent.getY(),
                        e.getZ() - agent.getZ()
                };
                break;
            default:
                row =  new double[]{
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                };
                break;
        }

        double normYaw = agent.getYaw() / 180.0;
        double normPitch = agent.getPitch() / 90.0;

        double[] agentInfo = new double[]{
                health,
                hunger,
                saturation,
                normYaw,
                normPitch,
                vx,
                vy,
                vz,
                blockBelowId,
                colliding,
                isSneak,
                isOnFire,
                inWater,
                inLava,
                onGround,
                isFalling,
                wasHurt,
                mainHandCount,
                mainHandSlot,
                time,
                lightLevel,
                looking_at,
                row[0],
                row[1],
                row[2],
                row[3],
                row[4],
                row[5],
                row[6],
                row[7],
        };

        return agentInfo;
    }
    public double[] getPos() {
        return new double[]{ agent.getX(), agent.getY(), agent.getZ(), agent.getYaw(), agent.getPitch() };
    }

    /**
     * Used to collect the possible utility values of an item
     * @param item The {@link Item} to find the possible utilities of
     * @param itemType The {@link int[]} representing the type of item, if provided [isArmor, isFodd, isTool, isWeapon]
     * @return array of 2 values representing 2 utility options.
     *         isArmor: [protection, toughness]
     *         isFood: [nutrition, saturation]
     *         isTool: [dmg per block, default mining speed]
     *         isWeapon: [dmg, attack speed]
     */
    public double[] getUtility(Item item, int[] itemType) {
        double[] utility = {0, 0};
        // isArmor
        if(itemType[0] == 1) {
            // [protection, toughness]
            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);

            if (modifiers == null) return utility;

            for (var modifier : modifiers.modifiers()) {
                if (modifier.attribute().equals(EntityAttributes.ARMOR)) {
                    utility[0] += modifier.modifier().value();
                }
                if (modifier.attribute().equals(EntityAttributes.ARMOR_TOUGHNESS)) {
                    utility[1] += modifier.modifier().value();
                }
            }
        }
        // isFood
        else if(itemType[1] == 1) {
            // [nutrition (hunger fill), saturation (how long)]
            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);

            if (food == null) return utility;

            utility[0] = food.nutrition();
            utility[1] = food.saturation();
        }
        // isTool
        else if(itemType[2] == 1){
            // [damage per block, default mining speed]
            ToolComponent tool = item.getComponents().get(DataComponentTypes.TOOL);

            if(tool == null) return utility;

            utility[0] = tool.damagePerBlock();
            utility[1] = tool.defaultMiningSpeed();
        }
        //isWeapon
        else if(itemType[3] == 1){
            // Handle Ranged weapons
            // [based dmg, draw time]
            if (item instanceof BowItem) {
                utility[0] = 6.0;
                utility[1] = 0.80;
                return utility;
            }
            if  (item instanceof CrossbowItem) {
                utility[0] = 6.0;
                utility[1] = 0.50;
                return utility;
            }

            // [damage, attack speed]
            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);

            if (modifiers == null) return utility;

            for (var modifier : modifiers.modifiers()) {
                if (modifier.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) {
                    utility[0] += modifier.modifier().value();
                }
                if (modifier.attribute().equals(EntityAttributes.ATTACK_SPEED)) {
                    utility[1] += modifier.modifier().value();
                }
            }

        }
        return utility;
    }

    /**
     * Gets the agent inventory.
     * 0–8   : Hotbar
     * 9–35  : Main inventory
     * 36–39  : Armor (boots → leggings → chestplate → helmet)
     * 40     : Off-hand
     * 41–44  : Crafting grid output + 2x2 crafting input (ignored)
     *
     * @return double[][] where each row =
     *         [item_id, isArmor, isFood, isTool, isWeapon, utility1, utility2, count, durability]
     *         Check getUtility function for posible utility values
     */
    public double[][] getInventory(){
        PlayerInventory agentInventory = agent.getInventory();
        double[][] inventoryArray =  new double[41][9];

        for(int i = 0; i <= 40; i++) {
            ItemStack stack = agentInventory.getStack(i);
            Item item = stack.getItem();

            int item_id = Registries.ITEM.getRawId(item);
            double count = stack.getCount() / 64.0;

            int max = stack.getMaxDamage();
            double durability;

            int isDamageable = (max > 0) ? 1 : 0;
            if (max > 0) {
                durability = (double)(max - stack.getDamage()) / (double) max; // in [0,1]
            } else {
                durability = 0.0; // or 1.0, but be consistent; add isDamageable flag if you can
            }

            // ------ Get Boolean Values For one-hot encoding of item type ------ //
            EquippableComponent equip = item.getComponents().get(DataComponentTypes.EQUIPPABLE);
            int isArmor = 0;
            if(equip != null) {
                EquipmentSlot slot = equip.slot();
                isArmor = (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) ? 1 : 0;
            }

            boolean toolBool = item.getComponents().contains(DataComponentTypes.TOOL);
            boolean foodBool = item.getComponents().contains(DataComponentTypes.FOOD);
            boolean weaponBool = item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD;
            boolean rangedWeaponBool = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;

            int isTool = toolBool ? 1 : 0;
            int isFood = foodBool ? 1 : 0;
            int isWeapon = 0;

            if(weaponBool || rangedWeaponBool) {
                // Sword is both tool and weapon, but we want it to only be classified as weapon
                isWeapon = 1;
                isTool = 0;
            }

            int[] itemType = {isArmor, isFood, isTool, isWeapon};

            // Get utility value based on what the item is. We will get 2 per option
            double[] utility_value = getUtility(item, itemType);

            inventoryArray[i][0] = item_id;
            inventoryArray[i][1] = isArmor;
            inventoryArray[i][2] = isFood;
            inventoryArray[i][3] = isTool;
            inventoryArray[i][4] = isWeapon;
            inventoryArray[i][5] = utility_value[0];
            inventoryArray[i][6] = utility_value[1];
            inventoryArray[i][7] = count;
            inventoryArray[i][8] = durability;
        }

        return inventoryArray;
    }

    /**
     * Gets Entities within a given radius of the agent
     * @return double[][] where each row =
     *         [entity id, isMonster, isAngerable, isPassive, isUnknown, x, y, z]
     */
    public double[][] getNearbyEntities(){
        double agentSearchRadius = 10.0;
        double[][] foundEntities = new double[10][8];

        Box box = agent.getBoundingBox().expand(agentSearchRadius);
        List<Entity> nearbyEntities = agent.getEntityWorld().getOtherEntities(agent, box);

        for (int i = 0; i < nearbyEntities.size(); i++) {
            Entity entity = nearbyEntities.get(i);
            if (entity instanceof PlayerEntity || !(entity instanceof LivingEntity)) continue;

            int isMonster = entity instanceof Monster ? 1 : 0;
            int isAngerable = entity instanceof Angerable ? 1 : 0;
            int isPassive = entity instanceof PassiveEntity ? 1 : 0;
            int isUnknown = (isMonster != 1 && isAngerable != 1 && isPassive != 1) ? 1 : 0;

            foundEntities[i][0] = Registries.ENTITY_TYPE.getRawId(entity.getType());
            foundEntities[i][1] = isMonster;
            foundEntities[i][2] = isAngerable;
            foundEntities[i][3] = isPassive;
            foundEntities[i][4] = isUnknown;
            foundEntities[i][5] = entity.getX() - agent.getX();
            foundEntities[i][6] = entity.getY() - agent.getY();
            foundEntities[i][7] = entity.getZ() - agent.getZ();
        }

        // Runs and sets default value for no entities if less than 10 entities found
        for(int i = nearbyEntities.size(); i < 10; i++) {
            foundEntities[i][0] = 0;
            foundEntities[i][1] = 0;
            foundEntities[i][2] = 0;
            foundEntities[i][3] = 0;
            foundEntities[i][4] = 0;
            foundEntities[i][5] = 0;
            foundEntities[i][6] = 0;
            foundEntities[i][7] = 0;
        }

        return foundEntities;
    }

    /**
     * Gets nearby blocks of the agent given a radius
     * @return double[][] where each row =
     *         [Block id, x, y, z]
     */
    public double[][][] getNearbyBlocks() {
        int coreRadius = 8;
        int verticalRadius = 4; // Vertical space not as significant

        double[][][] foundBlocks = new double[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1];

        int agentBlockX = agent.getBlockX();
        int agentBlockY = agent.getBlockY();
        int agentBlockZ = agent.getBlockZ();

        for(int x = agentBlockX - coreRadius; x <= agentBlockX + coreRadius; x++) {
            for (int y = agentBlockY - verticalRadius; y <= agentBlockY + verticalRadius; y++) {
                for(int z = agentBlockZ - coreRadius; z <= agentBlockZ + coreRadius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = agent.getEntityWorld().getBlockState(pos);

                    boolean isLog = state.isIn(BlockTags.LOGS);
                    if(isLog && !visitedWood.contains(new Tuple3(pos.getX(), pos.getY(), pos.getZ()))) {
                        double dist_to_block = Math.sqrt(Math.pow((agentBlockX - pos.getX()), 2) + Math.pow((agentBlockY - pos.getY()), 2) + Math.pow((agentBlockZ - pos.getZ()), 2));
                        if(nearestWood == null){
                            nearestWood = new BlockPos(x, pos.getY(), z); // exclude y to not reward jumping
                        }
                        else {
                            double current_log_dist = Math.sqrt(Math.pow(agentBlockX - nearestWood.getX(), 2) + Math.pow(agentBlockY - nearestWood.getY(), 2) + Math.pow(agentBlockZ - nearestWood.getZ(), 2));
                            if(dist_to_block < current_log_dist) nearestWood = new BlockPos(x, pos.getY(), z);
                        }
                    }

                    int relX = x - agentBlockX + coreRadius;       // shift into array bounds
                    int relY = y - agentBlockY + verticalRadius;
                    int relZ = z - agentBlockZ + coreRadius;

                    foundBlocks[relX][relY][relZ] = Registries.BLOCK.getRawId(state.getBlock());
                }
            }
        }

        return foundBlocks;
    }
}