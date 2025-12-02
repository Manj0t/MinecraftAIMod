package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
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
import java.net.ServerSocket;
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
    private ServerSocket serverSocket;
    DataInputStream input = null;
    DataOutputStream output = null;

    private volatile boolean sendState = false;
    private volatile boolean sendReward = false;
    private volatile boolean recieveAction = false;
    private volatile boolean waitForNextRollout = false;
    private volatile boolean resetPlayer = false;

    private final Map<Integer, Runnable> movementActions = new HashMap<>();
    private final Map<Integer, Runnable> panCam = new HashMap<>();
    private final Map<Tuple3, Float> checkpoints = new HashMap<>();
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

    int prevMoveAction = -1;
    boolean isStuck = false;

    int forwardConsistency = 0;

    int stuckCounter = 0;
    int agentPort = -1;

    boolean DEBUGCHECKPOINT = false;
    boolean DEBUGSTUCK = false;

    private final int REGION = 1;

    boolean doLookWood = true;
    public static ServerCommandSource cmdSrc = null;

    int prev_num_logs = 0;
    ServerPlayerEntity expert = null;

    boolean data_collection = false;
    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    literal("start_training")
                            .then(argument("agentName", StringArgumentType.string())
                                    .then(argument("agentPort", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            cmdSrc = ctx.getSource();
                                            MinecraftServer server = ctx.getSource().getServer();
                                            if(!data_collection) {
                                                agentName = StringArgumentType.getString(ctx, "agentName").toLowerCase();


                                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
                                            }

                                            agentPort = IntegerArgumentType.getInteger(ctx, "agentPort");


                                            collecting = true;

                                            if(!data_collection)
                                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/tick rate 35");
                                            ctx.getSource().sendFeedback(() -> Text.literal("Started training!"), false);
                                            try {
                                                if (socket != null) {
                                                    socket.close();
                                                    socket = null;
                                                }
                                            } catch (IOException e) {
                                                //nothing
                                            }

                                            try{
                                                if(serverSocket != null){
                                                    serverSocket.close();
                                                    serverSocket = null;
                                                }
                                            }catch (IOException e){
                                                //nothing
                                            }
                                            try {
                                                System.out.println("INFO: Made process");

                                                serverSocket = new ServerSocket(agentPort);
                                                ctx.getSource().sendFeedback(() -> Text.literal("Java Server Ready, Port: " + agentPort + "..."), false);
                                                socket = serverSocket.accept();
                                                socket.setTcpNoDelay(true);
                                                socket.setSoTimeout(0);

                                                input = new DataInputStream(socket.getInputStream());
                                                output = new DataOutputStream(socket.getOutputStream());
                                                System.out.println("INFO: Socket Connected");

                                                int agent_info_dim = 21;
                                                int numItems = Registries.ITEM.size();
                                                int numBlocks = Registries.BLOCK.size();
                                                int numEntities = Registries.ENTITY_TYPE.size();




                                                if(data_collection){
                                                    sendState = true;
                                                }else {
                                                    output.writeInt(agent_info_dim);
                                                    output.writeInt(numItems);
                                                    output.writeInt(numBlocks);
                                                    output.writeInt(numEntities);
                                                    output.flush();

                                                    resetPlayer = true;
                                                }
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
                                            movementActions.put(4, this::moveForward);
                                            movementActions.put(5, null);

                                            panCam.put(0, this::lookUp);
                                            panCam.put(1, this::lookDown);
                                            panCam.put(2, this::rotateLeft);
                                            panCam.put(3, this::rotateRight);
                                            panCam.put(4, null);

                                            checkpoints.put(new Tuple3((int)Math.floor(186/REGION), 0, (int)Math.floor(-206/REGION)), 50.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(192/REGION), 0, (int)Math.floor(-205/REGION)), 55.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(191/REGION), 0, (int)Math.floor(-200/REGION)), 50.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(189/REGION), 0, (int)Math.floor(-197/REGION)), 60.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(188/REGION), 0, (int)Math.floor(-200/REGION)), 70.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(184/REGION), 0, (int)Math.floor(-209/REGION)), 100.0f); // Major intersection

                                            // Two exit points
                                            checkpoints.put(new Tuple3((int)Math.floor(193/REGION), 0, (int)Math.floor(-212/REGION)), 300.0f);
                                            checkpoints.put(new Tuple3((int)Math.floor(186/REGION), 0, (int)Math.floor(-219/REGION)), 300.0f);

                                            return 1;
                }))));

            dispatcher.register(literal("get_port").executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal("PORT: " + agentPort), false);
                return 1;
            }));

            dispatcher.register(literal("changeLookWood").executes(ctx -> {
                doLookWood = !doLookWood;
                ctx.getSource().sendFeedback(() -> Text.literal("Look Wood Reward set to: " + doLookWood), false);
                return 1;
            }));

            dispatcher.register(literal("debug_checkpoint_on").executes(ctx -> {
                DEBUGCHECKPOINT = true;
                ctx.getSource().sendFeedback(() -> Text.literal("Checkpoint Debug On"), false);
                return 1;
            }));

            dispatcher.register(literal("debug_stuck_on").executes(ctx -> {
                DEBUGSTUCK = true;
                ctx.getSource().sendFeedback(() -> Text.literal("Stuck Debug On"), false);
                return 1;
            }));

            dispatcher.register(literal("debug_off").executes(ctx -> {
                DEBUGCHECKPOINT = false;
                DEBUGSTUCK = false;
                return 1;
            }));


            dispatcher.register(literal("stop_training").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();

                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");

                agent = null;
                agentName = null;

                collecting = false;

                if(socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                        serverSocket.close();
                        ctx.getSource().sendFeedback(() -> Text.literal("Java Socket Closed, Port: " + agentPort + "..."), false);
                        socket = null;
                        serverSocket = null;
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
            if (agent == null && (agentName != null || data_collection)) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if(data_collection){
                        agent = player;
                        world = agent.getEntityWorld();
                        agentName = player.getName().getString();
                        System.out.println("found " + agentName);

                        break;
                    }
                    if (agentName.equals(player.getName().getString().toLowerCase())) {
                        agent = player;
                        world = agent.getEntityWorld();
                        resetPlayer();
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/gamemode survival " + agentName);
                        System.out.println("found");
                        break;
                    }
                }
                if (agent == null) return;
            }
            if (agent == null) return;
            // Agent Position Information
            if (resetPlayer) {
                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");
                resetPlayer = false;
                waitForNextRollout = true;
                return;
            }
//            agent.getHungerManager().setFoodLevel(20);
//            agent.getHungerManager().setSaturationLevel(20f);

            if(waitForNextRollout) {
                lastPos = null;
                try {
                    System.out.println("INFO: Waiting for next rollout");

                    int startRollout = input.readInt();
                    spawnPlayer = true;
                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/time set day");
                    resetPlayer();
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
                System.out.println("INFO: Sending state");
                sendStateInfo(server);
                sendState = false;
                recieveAction = true;
                return;
            }
            List<Float> actions = null;
//            Recieve action
            if (recieveAction) {
                try {
                    if(data_collection){
                        input.readInt();
                        System.out.println("INFO: Ready");

                        int[] act = getExpertAction();

                        Action.Builder actionBuilder = Action.newBuilder();
                        for (int a : act) {
                            actionBuilder.addActions((float) a);
                        }
                        Action actionMsg = actionBuilder.build();
                        System.out.println("INFO: Sending Action");
                        byte[] payload = actionMsg.toByteArray();
                        output.writeInt(payload.length);
                        output.write(payload);
                        output.flush();

                        System.out.println("INFO: Getting COntinue");


                        int continue_rollout = input.readInt();
                        if(continue_rollout == 1){
                            sendState = true;
                        }
                        return;
                    }
                    int respLen = input.readInt();
                    byte[] resp = input.readNBytes(respLen);

                    Action action = Action.parseFrom(resp);

                    actions = action.getActionsList();

                    applyAction(actions);


                    recieveAction = false;
                    sendReward = true;
                    if (agent == null) return;
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

//    private void collect_training_data(MinecraftServer server){
//        if (expert == null) {
//            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
//                if (agentName.equals(player.getName().getString().toLowerCase())) {
//                    expert = player;
//                    world = expert.getEntityWorld();
//                    resetPlayer();
//                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/gamemode survival " + agentName);
//                    System.out.println("found");
//                    break;
//                }
//            }
//            if (agent == null) return;
//        }
//    }
    private float lastYaw = 0;
    private float lastPitch = 0;

    private int[] getExpertAction() {
        int movement = 6;
        boolean f = agent.getPlayerInput().forward();
        boolean b = agent.getPlayerInput().backward();
        boolean l = agent.getPlayerInput().left();
        boolean r = agent.getPlayerInput().right();
        boolean j = agent.getPlayerInput().jump();

        if (f && j) movement = 4;
        else if (f) movement = 0;
        else if (b) movement = 1;
        else if (l) movement = 2;
        else if (r) movement = 3;
        else if (j) movement = 5;

        int item_use = 2;
        if(agent.isUsingItem()){
            item_use = 1;
        }else if(agent.handSwinging){
            item_use = 0;
        }
//        panCam.put(0, this::lookUp);
//        panCam.put(1, this::lookDown);
//        panCam.put(2, this::rotateLeft);
//        panCam.put(3, this::rotateRight);
//        panCam.put(4, null);
        // Camera pan
        float yaw = agent.getYaw();
        float pitch = agent.getPitch();

        float dy = yaw - lastYaw;
        float dp = pitch - lastPitch;

        lastYaw = yaw;
        lastPitch = pitch;

        float hThresh = 2.5f;
        float vThresh = 1.5f;

        int pan_cam = 4; // none

        if (dp < -vThresh) pan_cam = 0; // up
        else if (dp > vThresh) pan_cam = 1; // down
        else if (dy < -hThresh) pan_cam = 2; // left
        else if (dy > hThresh) pan_cam = 3; // right

        // Hotbar
        int hotbarSlot = agent.getInventory().getSelectedSlot();

        System.out.println(movement + ", " + item_use + ", " + hotbarSlot + ", " + pan_cam);

        return new int[] {
                movement,
                item_use,
                hotbarSlot,
                pan_cam
        };



    }

    private void resetPlayer() {
        visitedRegion.clear();
        visitedWood.clear();
        lastY = null;
        lastPos = null;
        lastMovement = 0;
        prevMoveAction = -1;
        isStuck = false;
        forwardConsistency = 0;
        stuckCounter = 0;
        prev_num_logs = 0;
    }
    private double getReward(List<Float> actions) {
         try {
                Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());

                if (lastPos == null) {
                    lastPos = currentPos;
                    agentPrevhealth = agent.getHealth();
                    return 0.0;
                }

                double reward = 0.0;

                int rx = (int)Math.floor(currentPos.x / REGION);
                int rz = (int)Math.floor(currentPos.z / REGION);

                Tuple3 mazePos = new Tuple3(rx, 0, rz);
                boolean isNew = visitedRegion.add(mazePos);

            if (nearestWood != null) {
                double current_dist_to_wood = Math.sqrt(Math.pow(currentPos.x - nearestWood.getX(), 2) + Math.pow(currentPos.z - nearestWood.getZ(), 2));
                double prev_dist_to_wood = Math.sqrt(Math.pow(lastPos.x - nearestWood.getX(), 2) + Math.pow(lastPos.z - nearestWood.getZ(), 2));
                double diff = (prev_dist_to_wood - current_dist_to_wood);
                reward += diff;

                HitResult lookingAt = raycastWithEntities(agent, 4.5);

                if (lookingAt instanceof BlockHitResult bhs) {
                    BlockPos pos = bhs.getBlockPos();
                    BlockState state = agent.getEntityWorld().getBlockState(pos);

                    boolean isLog = state.isIn(BlockTags.LOGS);
                    if (isLog && doLookWood) {
                        reward += 1;
                    }
                }

                if (current_dist_to_wood <= 2.5) {
                    visitedWood.add(new Tuple3(nearestWood.getX(), nearestWood.getY(), nearestWood.getZ()));
                    nearestWood = null;
                }
            }

            var agentInventory = agent.getInventory();
            int num_logs = 0;
            for(int i = 0; i <= agentInventory.size(); i++) {
                ItemStack stack = agentInventory.getStack(i);
                if (stack != null || stack.isEmpty()) continue;

                if(stack.isIn(ItemTags.LOGS)){
                    num_logs += stack.getCount();
                }
            }

            if(num_logs > prev_num_logs){
                reward += 30; // Big reward, this is current goal
            }
            prev_num_logs = num_logs;

                // ---------------------------
                // Movement reward
                // ---------------------------
                double dx = currentPos.x - lastPos.x;
                double dz = currentPos.z - lastPos.z;
                double dist = Math.sqrt(dx*dx + dz*dz);

//                if (dist > 0.05)
//                    reward += dist * 0.2;

                // ---------------------------
                // Collision penalty
                // ---------------------------
                boolean isTryingToMove = actions.getFirst() <= 4;
                boolean collided = agent.horizontalCollision;

                if (collided && isTryingToMove) {
                    reward -= 0.25;
                    if(DEBUGSTUCK){
                        cmdSrc.sendFeedback(() -> Text.literal("Agent is Stuck"), false);
                    }
                }

                // ---------------------------
                // Jump penalty
                // ---------------------------
                boolean jumped = (actions.getFirst() == 4 || actions.getFirst() == 5);

                if (jumped)
                    reward -= 0.05;

                if (!agent.isOnGround() && !agent.isInLava())
                    reward -= 0.01;

                // ---------------------------
                // Lava / fire / damage
                // ---------------------------
                if (agent.isInLava() || agent.isOnFire())
                    reward -= 1.0;

                if (agent.getHealth() < agentPrevhealth)
                    reward -= 1.0;

                lastPos = currentPos;
                agentPrevhealth = agent.getHealth();
                return reward;

            } catch (Exception e) {
                return 0.0;
            }
        }


    private void applyAction(List<Float> actions) {
        if (agent == null) return;
        int movement = actions.get(0).intValue();
//        int jump = actions.get(1).intValue();
        int item_use = actions.get(1).intValue();
        int hotbar_idx = actions.get(2).intValue();
        int pan_id = actions.get(3).intValue();

        Runnable movementAction = null;

        if(movement <= 4){ // only care about actual movement, not jumping or standing still
            prevMoveAction = movement;
            movementAction = movementActions.get(movement);
        }else{
            prevMoveAction = -1;
        }
        if (movementAction != null) {
            movementAction.run();
        }

        int jump = 0;
        if(movement == 4 || movement == 5){
            jump = 1;
        }

        if(jump > 0 && agent.isOnGround() || agent.isInLava() || agent.isSubmergedInWater()) {
            agent.jump();
        }


        // 2 is don't use item
        if(item_use == 0)
            tryHit();
        else if(item_use == 1)
            tryPlace();
        else
            stopMiningIfNeeded();

        if(hotbar_idx != currentHand){
            agent.getInventory().setSelectedSlot(hotbar_idx);
            currentHand = hotbar_idx;
        }

        Runnable pan_cam_action = panCam.get(pan_id);
        if (pan_cam_action != null) {
            pan_cam_action.run();
        }


    }

    private BlockPos miningPos = null;
    private int miningTicks = 0;
    private Direction miningDir = null;

    private void tryHit() {
        HitResult hit = raycastWithEntities(agent, 4.5);
        agent.swingHand(Hand.MAIN_HAND); // Swings
        if(hit instanceof EntityHitResult ehr){
            Entity target = ehr.getEntity();
            agent.swingHand(Hand.MAIN_HAND);
            agent.attack(target);
            agent.resetLastAttackedTicks();
        }
        else if(hit instanceof BlockHitResult bhr) {

            BlockPos pos = bhr.getBlockPos();
//            Direction dir = bhr.getSide();
            // Start/continue mining'
            BlockState state = world.getBlockState(pos);
            if(!pos.equals(miningPos)) {
                stopMiningIfNeeded();
                agent.interactionManager.processBlockBreakingAction(pos, PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, miningDir, world.getHeight(), 0);

                miningPos = bhr.getBlockPos().toImmutable();
                miningDir = bhr.getSide();
                miningTicks = 0;
                return;
            }
            miningTicks++;
            float delta = state.calcBlockBreakingDelta(agent, world, pos);
            float progress = delta * (miningTicks + 1);

            // If block is fully mined, STOP will break it
            if (progress >= 0.99f) {
                boolean isLog = state.isIn(BlockTags.LOGS);
                if (isLog && doLookWood) {
                    penalty += 10;
                }


                agent.interactionManager.processBlockBreakingAction(
                        pos,
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        miningDir,
                        world.getHeight(),
                        0
                );
                miningPos = null;
                miningTicks = 0;
                miningDir = null;
            }

            return;
        }
        stopMiningIfNeeded();
    }

    private HitResult raycastWithEntities(ServerPlayerEntity player, double reach) {
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d end = start.add(look.multiply(reach));

        HitResult blockHit = player.raycast(reach, 1.0f, false);

        Box box = player.getBoundingBox().stretch(look.multiply(reach)).expand(1.0D);

        EntityHitResult entityHit = ProjectileUtil.raycast(
                player,
                start,
                end,
                box,
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

    private void stopMiningIfNeeded() {
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
            miningTicks = 0;        }
    }

    public void tryPlace() {
        HitResult hit = raycastWithEntities(agent, 4.5);
        if(hit.getType() == HitResult.Type.MISS) {
            agent.interactionManager.interactItem(agent, world, agent.getMainHandStack(), Hand.MAIN_HAND);
            return;
        }
        if(hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            agent.swingHand(Hand.MAIN_HAND);

            ActionResult result = agent.interactionManager.interactBlock(
                    agent,
                    world,
                    agent.getMainHandStack(),
                    Hand.MAIN_HAND,
                    bhr
            );
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

        HitResult raycast = raycastWithEntities(agent, 4.5);

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
                durability = 0.0;
            }

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
                isWeapon = 1;
                isTool = 0;
            }

            int[] itemType = {isArmor, isFood, isTool, isWeapon};

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

        int idx = 0;
        for (Entity entity : nearbyEntities) {
            if (idx >= 10) break;
            if (entity instanceof PlayerEntity || !(entity instanceof LivingEntity)) continue;

            int isMonster = entity instanceof Monster ? 1 : 0;
            int isAngerable = entity instanceof Angerable ? 1 : 0;
            int isPassive = entity instanceof PassiveEntity ? 1 : 0;
            int isUnknown = (isMonster != 1 && isAngerable != 1 && isPassive != 1) ? 1 : 0;

            foundEntities[idx][0] = Registries.ENTITY_TYPE.getRawId(entity.getType());
            foundEntities[idx][1] = isMonster;
            foundEntities[idx][2] = isAngerable;
            foundEntities[idx][3] = isPassive;
            foundEntities[idx][4] = isUnknown;
            foundEntities[idx][5] = entity.getX() - agent.getX();
            foundEntities[idx][6] = entity.getY() - agent.getY();
            foundEntities[idx][7] = entity.getZ() - agent.getZ();

            idx++;
        }

        for(int i = idx; i < 10; i++) {
            Arrays.fill(foundEntities[i], 0);
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
                    if(isLog) {
                        double dist_to_block = Math.sqrt(Math.pow((agentBlockX - pos.getX()), 2) + Math.pow((agentBlockY - pos.getY()), 2) + Math.pow((agentBlockZ - pos.getZ()), 2));
                        if(nearestWood == null){
                            nearestWood = pos; // exclude y to not reward jumping
                        }
                        else {
                            double current_log_dist = Math.sqrt(Math.pow(agentBlockX - nearestWood.getX(), 2) + Math.pow(agentBlockY - nearestWood.getY(), 2) + Math.pow(agentBlockZ - nearestWood.getZ(), 2));
                            if(dist_to_block < current_log_dist) nearestWood = pos;
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