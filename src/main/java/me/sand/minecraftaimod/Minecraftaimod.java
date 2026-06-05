package me.sand.minecraftaimod;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.sand.minecraftaimod.agent.*;
import me.sand.minecraftaimod.agent.ContainerType;
import me.sand.minecraftaimod.agent.MazeGenerator;
import me.sand.minecraftaimod.network.CraftPagesPayload;
import me.sand.minecraftaimod.network.CraftOption;
import me.sand.minecraftaimod.network.InteractionPayload;
import me.sand.minecraftaimod.network.InventoryMovePayload;
import me.sand.minecraftaimod.network.SelectRecipePayload;
import me.sand.minecraftaimod.protobuf.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class Minecraftaimod implements ModInitializer {

    // ========= Connection =========
    private ServerSocket serverSocket;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int agentPort = -1;

    // ========= Agent state =========
    private ServerPlayerEntity agent = null;
    private String agentName = null;
    private World world = null;

    private AgentActions actions;
    private AgentInventory inventory;
    private AgentObservation observation;
    private AgentCrafting crafting;
    private RewardCalculator rewards;
    private float lastYaw = 0;
    private float lastPitch = 0;

    // ========= Tick loop flags =========
    private volatile boolean collecting = false;
    private volatile boolean sendState = false;
    private volatile boolean sendReward = false;
    private volatile boolean receiveAction = false;
    private volatile boolean waitForNextRollout = false;
    private volatile boolean resetPlayer = false;
    private boolean spawnPlayer = false;
    private float penalty = 0;

    // ========= Data collection mode =========
    private boolean dataCollection = true;
    private boolean tableNearbyLastRecipeSend = false;

    // ========= Inventory action flags (set by network receivers) =========
    private static final int DEFAULT_VAL = 0;
    private volatile boolean inventorySwapped = false;
    private volatile int invFromSlot = DEFAULT_VAL;
    private volatile int invToSlot = DEFAULT_VAL;
    private volatile boolean inventoryDropped = false;
    private volatile int droppedSlot = DEFAULT_VAL;
    private volatile int droppedAll = DEFAULT_VAL;
    private volatile boolean rightClickThisTick = false;
    private int rightClickLastTick = 0;
    private boolean placedBlock = false;
    private int itemCraftedId = DEFAULT_VAL;
    private boolean crafted = false;
    private boolean containerOpenLastTick = false;

    // ========= Block collection (debug tool) =========
    private boolean collectingBlocks = false;
    private int blocksToCollect = 0;
    private int blocksCollected = 0;
    private int collectTickSkip = 0;
    private List<int[][][]> collectedGrids = new ArrayList<>();
    private ServerPlayerEntity blockCollectPlayer = null;

    public static ServerCommandSource cmdSrc = null;

    // ========= Maze =========
    private MazeGenerator currentMaze = null;
    private BlockPos mazeOrigin = new BlockPos(0, 64, 0); // fixed origin
    private int rolloutsSinceNewMaze = 0;
    private static final int ROLLOUTS_PER_MAZE = 1;
    private int mazeWidth = 5;   // add these
    private int mazeLength = 5;
    private boolean lavaEnabled = false;

    // ========= Frame Skip =========
    private int actionRepeatCounter = 0;
    private static final int ACTION_REPEAT = 4;
    private List<Float> lastActionList = null;
    private float accumulatedReward = 0;

    @Override
    public void onInitialize() {
        BlockRegistryDumper.dump("block_properties.json");
        registerPayloads();
        registerNetworkReceivers();
        registerCommands();
        registerTickLoop();
    }

    // ================================================
    //  Registration
    // ================================================

    private void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(InventoryMovePayload.ID, InventoryMovePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(InteractionPayload.ID, InteractionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CraftPagesPayload.ID, CraftPagesPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectRecipePayload.ID, SelectRecipePayload.CODEC);
    }

    private void registerNetworkReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(InventoryMovePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                if (payload.fromSlot() >= 0 && payload.toSlot() >= 0) {
                    inventorySwapped = true;
                    invFromSlot = payload.fromSlot();
                    invToSlot = payload.toSlot();
                }
                if (payload.dropFlag() == 1) {
                    inventoryDropped = true;
                    droppedSlot = payload.dropSlot();
                    droppedAll = payload.dropAll();
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SelectRecipePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                System.out.println("[MinecraftAIMod] Player " + context.player().getName().getString()
                        + " selected recipe " + payload.recipeId());
                crafting.craft(payload.recipeId(), observation.isTableNearby());
                itemCraftedId = payload.recipeId();
                crafted = true;
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(InteractionPayload.ID, (payload, context) -> {
            context.server().execute(() -> rightClickThisTick = true);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient() && player == agent) {
                placedBlock = true;
            }
            return ActionResult.PASS;
        });
    }

    // ================================================
    //  Commands
    // ================================================

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(literal("start_training")
                    .then(argument("agentName", StringArgumentType.string())
                            .then(argument("agentPort", IntegerArgumentType.integer())
                                    .executes(ctx -> startTraining(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "agentName"),
                                            IntegerArgumentType.getInteger(ctx, "agentPort"))))));

            dispatcher.register(literal("stop_training").executes(ctx -> stopTraining(ctx.getSource())));

            dispatcher.register(literal("swap")
                    .then(argument("slot1", IntegerArgumentType.integer())
                            .then(argument("slot2", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        int s1 = IntegerArgumentType.getInteger(ctx, "slot1");
                                        int s2 = IntegerArgumentType.getInteger(ctx, "slot2");
                                        if (inventory != null) inventory.swapItems(s1, s2);
                                        inventorySwapped = true;
                                        invFromSlot = s1;
                                        invToSlot = s2;
                                        return 1;
                                    }))));

            dispatcher.register(literal("drop")
                    .then(argument("slot1", IntegerArgumentType.integer())
                            .then(argument("all", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        if (inventory != null)
                                            inventory.dropItem(
                                                    IntegerArgumentType.getInteger(ctx, "slot1"),
                                                    IntegerArgumentType.getInteger(ctx, "all") == 1);
                                        return 1;
                                    }))));

            dispatcher.register(literal("get_port").executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal("PORT: " + agentPort), false);
                return 1;
            }));

            dispatcher.register(literal("close_socket").executes(ctx -> {
                closeSocket();
                return 1;
            }));

            dispatcher.register(literal("save_state").executes(ctx -> {
                penalty = 10000000;
                return 1;
            }));

            // Block collection debug commands
            dispatcher.register(literal("collect_blocks")
                    .then(argument("count", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                blockCollectPlayer = ctx.getSource().getPlayer();
                                blocksToCollect = IntegerArgumentType.getInteger(ctx, "count");
                                blocksCollected = 0;
                                collectTickSkip = 0;
                                collectedGrids = new ArrayList<>();
                                collectingBlocks = true;
                                ctx.getSource().sendFeedback(() -> Text.literal("Collecting blocks as you move!"), false);
                                return 1;
                            })));

            dispatcher.register(literal("stop_collecting").executes(ctx -> {
                collectingBlocks = false;
                saveBlockGrids();
                int size = collectedGrids.size();
                ctx.getSource().sendFeedback(() -> Text.literal("Saved " + size + " block grids!"), false);
                return 1;
            }));

            dispatcher.register(literal("generate_maze")
                    .then(argument("width", IntegerArgumentType.integer(3, 20))
                            .then(argument("length", IntegerArgumentType.integer(3, 20))
                                    .executes(ctx -> {
                                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                                        ServerWorld world = ctx.getSource().getWorld();
                                        int w = IntegerArgumentType.getInteger(ctx, "width");
                                        int l = IntegerArgumentType.getInteger(ctx, "length");

                                        MazeGenerator maze = new MazeGenerator(w, l, 3, world);
                                        maze.generate(world, player.getBlockPos());

                                        player.requestTeleport(
                                                maze.startPos.getX() + 0.5,
                                                maze.startPos.getY(),
                                                maze.startPos.getZ() + 0.5
                                        );
                                        player.setYaw(0);
                                        player.setPitch(0);

                                        ctx.getSource().sendFeedback(
                                                () -> Text.literal("Generated " + w + "x" + l + " maze!"), false);
                                        return 1;
                                    }))));

            dispatcher.register(literal("maze_size")
                    .then(argument("width", IntegerArgumentType.integer(3, 20))
                            .then(argument("length", IntegerArgumentType.integer(3, 20))
                                    .executes(ctx -> {
                                        mazeWidth = IntegerArgumentType.getInteger(ctx, "width");
                                        mazeLength = IntegerArgumentType.getInteger(ctx, "length");
                                        rolloutsSinceNewMaze = ROLLOUTS_PER_MAZE;
                                        ctx.getSource().sendFeedback(
                                                () -> Text.literal("Maze size → " + mazeWidth + "x" + mazeLength
                                                        + " (next rollout)"), false);
                                        return 1;
                                    }))));

            dispatcher.register(literal("maze_lava")
                    .executes(ctx -> {
                        lavaEnabled = !lavaEnabled;
                        rolloutsSinceNewMaze = ROLLOUTS_PER_MAZE; // force new maze
                        boolean enabled = lavaEnabled;
                        ctx.getSource().sendFeedback(
                                () -> Text.literal("Lava hazards: " + (enabled ? "ON" : "OFF")
                                        + " (next rollout)"), false);
                        return 1;
                    }));

        });
    }

    // ================================================
    //  Start / Stop training
    // ================================================

    private int startTraining(ServerCommandSource source, String name, int port) {
        cmdSrc = source;
        MinecraftServer server = source.getServer();
        agentName = name.toLowerCase();

        if (!dataCollection) {
            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
        }

        agentPort = port;
        collecting = true;

        if (!dataCollection)
            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/tick rate 35");

        source.sendFeedback(() -> Text.literal("Started training!"), false);

        closeSocket();
        try {
            serverSocket = new ServerSocket(agentPort);
            source.sendFeedback(() -> Text.literal("Java Server Ready, Port: " + agentPort + "..."), false);
            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());

            if (dataCollection) {
                sendState = true;
            } else {
                output.writeInt(31); // agent_info_dim (health,hunger,sat,yaw,pitch,vx,vy,vz,blockBelow,collide,sneak,fire,water,lava,ground,falling,hurt,handCount,handSlot,time,light,breakProg,lookingAt,raycast[8])
                output.writeInt(Registries.ITEM.size());
                output.writeInt(Registries.BLOCK.size());
                output.writeInt(Registries.ENTITY_TYPE.size());
                output.flush();
                resetPlayer = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: Socket setup failed");
        }

        return 1;
    }

    private int stopTraining(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");

        agent = null;
        agentName = null;
        collecting = false;
        closeSocket();

        source.sendFeedback(() -> Text.literal("Stopped training!"), false);
        return 1;
    }

    private void closeSocket() {
        try { if (socket != null) { socket.close(); socket = null; } } catch (IOException ignored) {}
        try { if (serverSocket != null) { serverSocket.close(); serverSocket = null; } } catch (IOException ignored) {}
    }

    // ================================================
    //  Agent helper init
    // ================================================

    private void initAgentHelpers() {
        inventory = new AgentInventory(agent, world);
        actions = new AgentActions(agent, world);
        observation = new AgentObservation(agent, world, inventory, actions);
        crafting = new AgentCrafting(agent, world);
        rewards = new RewardCalculator(agent);
        if (currentMaze != null && currentMaze.endPos != null) {
            rewards.setGoal(new net.minecraft.util.math.Vec3d(
                    currentMaze.endPos.getX() + 0.5,
                    currentMaze.endPos.getY(),
                    currentMaze.endPos.getZ() + 0.5));
        }
    }

    private void resetAgentState() {
        if (rewards != null) rewards.reset();
        actionRepeatCounter = 0;
        accumulatedReward = 0;
        lastActionList = null;
    }
    // ================================================
    //  Main tick loop
    // ================================================

    private void registerTickLoop() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {

            // Block collection mode (debug tool)
            if (collectingBlocks && blockCollectPlayer != null) {
                handleBlockCollection(server);
                return;
            }

            if (!collecting) return;

            // Spawn player if needed
            if (spawnPlayer) {
                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
                spawnPlayer = false;
                return;
            }

            // Find agent
            if (agent == null && agentName != null) {
                findAgent(server);
                if (agent == null) return;
            }
            if (agent == null) return;

            // Reset player
            if (resetPlayer) {
                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");
                resetPlayer = false;
                waitForNextRollout = true;
                return;
            }

            // Wait for next rollout signal
            if (waitForNextRollout) {
                handleWaitForRollout(server);
                return;
            }

            // Check agent still alive
            if (!server.getPlayerManager().getPlayerList().contains(agent)) {
                penalty = -10.0f;
                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
                agent = null;
                return;
            }

            if (actionRepeatCounter > 0) {
                if (lastActionList != null) {
                    actions.applyAction(lastActionList, inventory, crafting, observation.isTableNearby());
                    accumulatedReward += (float) rewards.getReward(lastActionList);
                }
                actionRepeatCounter--;
                return;
            }

            // Send state
            if (sendState) {
                observation.sendStateInfo(server, output);
                sendState = false;
                receiveAction = true;
                return;
            }

            // Update crafting recipes if needed (data collection mode)
            if (dataCollection && (inventory.hasInventoryChanged() || observation.isTableNearby() != tableNearbyLastRecipeSend)) {
                inventory.clearInventoryChangedFlag();
                List<RecipeEntry<?>> craftableRecipes = crafting.getCraftableRecipes(observation.isTableNearby());
                List<List<CraftOption>> pages = crafting.buildCraftPages(craftableRecipes);
                ServerPlayNetworking.send(agent, new CraftPagesPayload(pages));
                tableNearbyLastRecipeSend = observation.isTableNearby();
            }

            // Receive and apply action
            List<Float> actionList = null;
            if (receiveAction) {
                actionList = handleReceiveAction(server);
                if (actionList == null && !dataCollection) return;
            }

            // Send reward
            if (sendReward) {
                handleSendReward(server, actionList);
            }
        });
    }

    // ================================================
    //  Tick loop helpers
    // ================================================

    private void findAgent(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (dataCollection) {
                agent = player;
                world = agent.getEntityWorld();
                agentName = player.getName().getString();
                initAgentHelpers();
                break;
            }
            if (agentName.equals(player.getName().getString().toLowerCase())) {
                agent = player;
                world = agent.getEntityWorld();
                initAgentHelpers();
                resetAgentState();
                server.getCommandManager().parseAndExecute(server.getCommandSource(),
                        "/gamemode survival " + agentName);

                // Teleport to maze start
                if (currentMaze != null) {
                    agent.requestTeleport(
                            currentMaze.startPos.getX() + 0.5,
                            currentMaze.startPos.getY(),
                            currentMaze.startPos.getZ() + 0.5
                    );
                    agent.setYaw(0);
                    agent.setPitch(0);
                }
                break;
            }
        }
    }

    private void handleWaitForRollout(MinecraftServer server) {
        try {
            int startRollout = input.readInt();
            spawnPlayer = true;
            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/time set day");

            // Regenerate maze only every ROLLOUTS_PER_MAZE rollouts so the policy
            // has time to learn the current layout before facing a new one
            ServerWorld serverWorld = server.getOverworld();
            rolloutsSinceNewMaze++;
            if (currentMaze == null || rolloutsSinceNewMaze >= ROLLOUTS_PER_MAZE) {
                currentMaze = new MazeGenerator(mazeWidth, mazeLength, 3, serverWorld);
                currentMaze.lavaEnabled = lavaEnabled;
                currentMaze.generate(serverWorld, mazeOrigin);
                rolloutsSinceNewMaze = 0;
                System.out.println("[Maze] New maze generated");
            } else {
                System.out.println("[Maze] Reusing maze (" + rolloutsSinceNewMaze + "/" + ROLLOUTS_PER_MAZE + ")");
            }

            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/kill @e[type=!player]");
            resetAgentState();
            if (rewards != null && currentMaze.endPos != null) {
                rewards.setGoal(new net.minecraft.util.math.Vec3d(
                        currentMaze.endPos.getX() + 0.5,
                        currentMaze.endPos.getY(),
                        currentMaze.endPos.getZ() + 0.5));
            }
            if (startRollout > 0) {
                waitForNextRollout = false;
                sendState = true;
            }
        } catch (IOException e) {
            System.err.println("Error reading rollout command: " + e.getMessage());
        }
        agent = null;
    }

    private List<Float> handleReceiveAction(MinecraftServer server) {
        try {
            if (dataCollection) {
                input.readInt();
                int[] act = getExpertAction();

                Action.Builder actionBuilder = Action.newBuilder();
                for (int a : act) actionBuilder.addActions((float) a);
                byte[] payload = actionBuilder.build().toByteArray();
                output.writeInt(payload.length);
                output.write(payload);
                output.flush();

                int continueRollout = input.readInt();
                if (continueRollout == 1) sendState = true;
                return null;
            }

            int respLen = input.readInt();
            byte[] resp = input.readNBytes(respLen);
            Action action = Action.parseFrom(resp);
            List<Float> actionList = action.getActionsList();

            actions.applyAction(actionList, inventory, crafting, observation.isTableNearby());
            lastActionList = actionList;
            actionRepeatCounter = ACTION_REPEAT - 1; // -1 because this tick already applied it
            accumulatedReward = 0;
            receiveAction = false;
            sendReward = true;
            return actionList;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleSendReward(MinecraftServer server, List<Float> actionList) {
        try {
            float reward = (float) rewards.getReward(actionList) + accumulatedReward + penalty;
            accumulatedReward = 0;
            int doneTag = penalty == -10.0f ? 1 : 0;
            penalty = 0;

            // Check if agent reached the maze exit — reward once per episode via claimGoalReached()
            if (currentMaze != null && agent != null && doneTag == 0) {
                BlockPos agentBlock = agent.getBlockPos();
                BlockPos goal = currentMaze.endPos;
                if (agentBlock.getX() == goal.getX() && agentBlock.getZ() == goal.getZ()
                        && rewards.claimGoalReached()) {
                    reward += 50.0f;
                    doneTag = 1;
                    // Do not set resetPlayer: the rollout protocol must continue uninterrupted.
                    // GAE uses doneTag=1 as an episode boundary without ending the rollout.
                }
            }

            output.writeFloat(reward);
            output.writeInt(doneTag);
            output.flush();

            sendReward = false;

            int continueRollout = input.readInt();
            if (continueRollout == 1) {
                sendState = true;
            } else {
                resetPlayer = true;
            }
        } catch (IOException e) {
            System.out.println("ERROR: FAILED");
            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
        }
    }

    // ================================================
    //  Expert action (data collection)
    // ================================================

    private int[] getExpertAction() {
        var handler = agent.currentScreenHandler;
        me.sand.minecraftaimod.agent.ContainerType openContainer = inventory.getContainerType(handler);

        if (openContainer == me.sand.minecraftaimod.agent.ContainerType.PlayerInventory) {
            openContainer = me.sand.minecraftaimod.agent.ContainerType.NONE;
        } else if (openContainer == me.sand.minecraftaimod.agent.ContainerType.UNKNOWN) {
            agent.closeHandledScreen();
            openContainer = me.sand.minecraftaimod.agent.ContainerType.NONE;
        }

        if (openContainer != ContainerType.NONE) {
            int containerSize = openContainer.getContainerSize();
            int invSlot = -1, contSlot = -1;

            if (inventorySwapped) {
                if (invFromSlot > containerSize) {
                    invSlot = AgentInventory.mapClientToServerSlotForContHandling(invFromSlot - containerSize);
                    contSlot = invToSlot;
                } else {
                    invSlot = AgentInventory.mapClientToServerSlotForContHandling(invToSlot - containerSize);
                    contSlot = invFromSlot;
                }
            }

            clearActionFlags();
            containerOpenLastTick = true;
            return new int[]{invSlot, contSlot, 0};
        } else if (containerOpenLastTick) {
            containerOpenLastTick = false;
            return new int[]{0, 0, 1};
        }

        // Movement
        int movement = 4, sideMove = 2;
        var input = agent.getPlayerInput();
        if (input.forward() && input.jump()) movement = 2;
        else if (input.forward()) movement = 0;
        else if (input.backward()) movement = 1;
        else if (input.jump()) movement = 3;

        if (input.left()) sideMove = 0;
        else if (input.right()) sideMove = 1;

        // Item use
        int itemUse = 2;
        if (placedBlock || agent.isUsingItem()) {
            itemUse = 1;
            placedBlock = false;
            rightClickLastTick = 2;
        } else if (agent.handSwinging) {
            if (rightClickLastTick > 0) rightClickLastTick -= 1;
            else itemUse = 0;
        }

        // Camera
        float yaw = agent.getYaw(), pitch = agent.getPitch();
        float dy = yaw - lastYaw;
        float dp = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;

        float hThresh = 2.5f;
        float vThresh = 1.5f;
        int panCam = 4;

        if (dp < -vThresh) panCam = 0;
        else if (dp > vThresh) panCam = 1;
        else if (dy < -hThresh) panCam = 2;
        else if (dy > hThresh) panCam = 3;

        int hotbarSlot = agent.getInventory().getSelectedSlot();
        int fromSlot = AgentInventory.mapClientToServerSlot(invFromSlot);
        int toSlot = AgentInventory.mapClientToServerSlot(invToSlot);
        int dSlot = droppedSlot;
        int dAll = droppedAll;
        int craftedItemId = itemCraftedId;

        int invAct = 0;
        if (inventoryDropped || inventorySwapped || crafted) {
            movement = 6;
            itemUse = 2;
            panCam = 4;
            if (inventorySwapped) invAct = 1;
            else if (inventoryDropped) invAct = 2;
            else invAct = 3;
        }

        clearActionFlags();

        return new int[]{invAct, movement, sideMove, itemUse, hotbarSlot, panCam,
                fromSlot, toSlot, dSlot, dAll, craftedItemId};
    }

    private void clearActionFlags() {
        inventorySwapped = false;
        invFromSlot = DEFAULT_VAL;
        invToSlot = DEFAULT_VAL;
        inventoryDropped = false;
        droppedAll = DEFAULT_VAL;
        droppedSlot = DEFAULT_VAL;
        itemCraftedId = DEFAULT_VAL;
        crafted = false;
    }

    // ================================================
    //  Block collection (debug tool)
    // ================================================

    private void handleBlockCollection(MinecraftServer server) {
        if (blocksCollected >= blocksToCollect) {
            collectingBlocks = false;
            saveBlockGrids();
            int size = collectedGrids.size();
            server.getCommandManager().parseAndExecute(server.getCommandSource(),
                    "/say Done! Saved " + size + " block grids!");
            return;
        }

        collectTickSkip++;
        if (collectTickSkip < 5) return;
        collectTickSkip = 0;

        int coreRadius = 8, verticalRadius = 4;
        int[][][] grid = new int[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1];

        int ax = blockCollectPlayer.getBlockX();
        int ay = blockCollectPlayer.getBlockY();
        int az = blockCollectPlayer.getBlockZ();

        for (int x = -coreRadius; x <= coreRadius; x++)
            for (int y = -verticalRadius; y <= verticalRadius; y++)
                for (int z = -coreRadius; z <= coreRadius; z++) {
                    BlockState state = blockCollectPlayer.getEntityWorld()
                            .getBlockState(new BlockPos(ax + x, ay + y, az + z));
                    grid[x + coreRadius][y + verticalRadius][z + coreRadius] =
                            Registries.BLOCK.getRawId(state.getBlock());
                }

        collectedGrids.add(grid);
        blocksCollected++;

        if (blocksCollected % 100 == 0) {
            int current = blocksCollected;
            server.getCommandManager().parseAndExecute(server.getCommandSource(),
                    "/say Collected " + current + "/" + blocksToCollect);
        }
    }

    private void saveBlockGrids() {
        try (FileWriter writer = new FileWriter("block_grids.json")) {
            new Gson().toJson(collectedGrids, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
