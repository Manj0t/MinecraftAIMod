//package me.sand.minecraftaimod.network;
//
//import com.google.gson.Gson;
//import com.mojang.brigadier.arguments.IntegerArgumentType;
//import com.mojang.brigadier.arguments.StringArgumentType;
//import me.sand.minecraftaimod.BlockRegistryDumper;
//import me.sand.minecraftaimod.ContainerType;
//import me.sand.minecraftaimod.protobuf.*;
//import net.fabricmc.api.ModInitializer;
//import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//import net.fabricmc.fabric.api.event.player.UseBlockCallback;
//import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//import net.minecraft.block.Block;
//import net.minecraft.block.BlockState;
//import net.minecraft.block.Blocks;
//import net.minecraft.component.DataComponentTypes;
//import net.minecraft.component.type.AttributeModifiersComponent;
//import net.minecraft.component.type.EquippableComponent;
//import net.minecraft.component.type.FoodComponent;
//import net.minecraft.component.type.ToolComponent;
//import net.minecraft.entity.Entity;
//import net.minecraft.entity.EquipmentSlot;
//import net.minecraft.entity.ItemEntity;
//import net.minecraft.entity.attribute.EntityAttributes;
//import net.minecraft.entity.player.PlayerEntity;
//import net.minecraft.entity.LivingEntity;
//import net.minecraft.entity.mob.Monster;
//import net.minecraft.entity.mob.Angerable;
//import net.minecraft.entity.passive.PassiveEntity;
//import net.minecraft.entity.player.PlayerInventory;
//import net.minecraft.entity.projectile.ProjectileUtil;
//import net.minecraft.item.*;
//import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
//import net.minecraft.recipe.*;
//import net.minecraft.recipe.display.RecipeDisplay;
//import net.minecraft.recipe.display.SlotDisplay;
//import net.minecraft.recipe.display.SlotDisplayContexts;
//import net.minecraft.registry.Registries;
//import net.minecraft.registry.tag.BlockTags;
//import net.minecraft.screen.FurnaceScreenHandler;
//import net.minecraft.screen.GenericContainerScreenHandler;
//import net.minecraft.screen.ScreenHandler;
//import net.minecraft.screen.slot.Slot;
//import net.minecraft.server.command.ServerCommandSource;
//import net.minecraft.server.network.ServerPlayerEntity;
//import net.minecraft.server.MinecraftServer;
//import net.minecraft.text.Text;
//import net.minecraft.util.ActionResult;
//import net.minecraft.util.Hand;
//import net.minecraft.util.Identifier;
//import net.minecraft.util.context.ContextParameterMap;
//import net.minecraft.util.hit.BlockHitResult;
//import net.minecraft.util.hit.EntityHitResult;
//import net.minecraft.util.hit.HitResult;
//import net.minecraft.util.math.BlockPos;
//import net.minecraft.util.math.Box;
//import net.minecraft.util.math.Direction;
//import net.minecraft.util.math.Vec3d;
//import net.minecraft.world.World;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.*;
//import java.net.ServerSocket;
//import java.net.Socket;
//import java.util.*;
//import java.util.List;
//
//import static net.minecraft.server.command.CommandManager.argument;
//import static net.minecraft.server.command.CommandManager.literal;
//
//
//public class Minecraftaimod implements ModInitializer {
//
//    public static final Logger LOGGER = LoggerFactory.getLogger("collect-state-info");
//    private volatile boolean collecting = false;
//
//    private ServerPlayerEntity agent = null;
//    private String agentName = null;
//    World world = null;
//
//
//    Socket socket = null;
//    private ServerSocket serverSocket;
//    DataInputStream input = null;
//    DataOutputStream output = null;
//
//    private volatile boolean sendState = false;
//    private volatile boolean sendReward = false;
//    private volatile boolean recieveAction = false;
//    private volatile boolean waitForNextRollout = false;
//    private volatile boolean resetPlayer = false;
//
//    private final Map<Integer, Runnable> movementActions = new HashMap<>();
//    private final Map<Integer, Runnable> panCam = new HashMap<>();
//    private final Map<Tuple3, Float> checkpoints = new HashMap<>();
//
//    private int currentHand = 0;
//    private double speed = 0.24;
//    double maxSpeed = 0.25;
//
//    Vec3d lastPos = null;
//    Double lastY = null;
//    float penalty = 0;
//    boolean wasOnGround = false;
//    double agentPrevhealth = 0;
//
//    BlockPos nearestWood = null;
//
//    record Tuple3(int x, int y, int z) {}
//
//    Set<Tuple3> visitedRegion = new HashSet<>();
//    Set<Tuple3> visitedWood = new HashSet<>();
//
//    int loop = 0;
//    boolean isLavaArea = false;
//
//    boolean spawnPlayer = false;
//
//    Deque<Vec3d> pastPositions = new ArrayDeque<>();
//    final int WINDOW = 10; // 10 ticks = 0.5 sec
//    int lastMovement = 0;
//
//    int prevMoveAction = -1;
//    boolean isStuck = false;
//
//    int forwardConsistency = 0;
//
//    int stuckCounter = 0;
//    int agentPort = -1;
//
//    boolean DEBUGCHECKPOINT = false;
//    boolean DEBUGSTUCK = false;
//
//    private final int REGION = 1;
//
//    boolean doLookWood = true;
//    public static ServerCommandSource cmdSrc = null;
//
//    int prev_num_logs = 0;
//
//    boolean data_collection = false;
//
//    private boolean tableNearby = false;
//    private boolean tableNearbyLastRecipeSend = false;
//
//    private final int DEFUAL_VAL = 0;
//
//    private volatile boolean inventorySwapped = false;
//    private volatile int invFromSlot = DEFUAL_VAL;
//    private volatile int invToSlot = DEFUAL_VAL;
//
//    // drop
//    private volatile boolean inventoryDropped = false;
//    private volatile int droppedSlot = DEFUAL_VAL;
//    private volatile int droppedAll = DEFUAL_VAL; // 0 = one, 1 = all, -1 = none
//
//    private volatile boolean rightClickThisTick = false;
//    private int rightClickLastTick = 0;
//    private boolean placedBlock = false;
//
//    double[][] prevInv = null;
//    boolean updateCraftingRecipes = false;
//
//    int item_crafted_id = DEFUAL_VAL;
//
//    boolean container_open_last_tick = false;
//    boolean crafted = false;
//
//    float block_break_prog = 0.0f;
//    float prev_block_break_prog = 0.0f;
//
//    // Add these fields to your class
//    private boolean collectingBlocks = false;
//    private int blocksToCollect = 0;
//    private int blocksCollected = 0;
//    private int collectAttempts = 0;
//    private List<int[][][]> collectedGrids = new ArrayList<>();
//    private Random collectRandom = new Random();
//
//    private ServerPlayerEntity blockCollectPlayer = null;
//
//    private int collectTickSkip = 0;
//    @Override
//    public void onInitialize() {
//
//        BlockRegistryDumper.dump("block_properties.json");
//
//        PayloadTypeRegistry.playC2S().register(
//                InventoryMovePayload.ID,
//                InventoryMovePayload.CODEC
//        );
//
//        PayloadTypeRegistry.playC2S().register(
//                InteractionPayload.ID,
//                InteractionPayload.CODEC
//        );
//
//        PayloadTypeRegistry.playC2S().register(
//                CraftPagesPayload.ID,
//                CraftPagesPayload.CODEC
//        );
//
//        PayloadTypeRegistry.playC2S().register(
//                SelectRecipePayload.ID,
//                SelectRecipePayload.CODEC
//        );
//
//        ServerPlayNetworking.registerGlobalReceiver(
//                InventoryMovePayload.ID,
//                (payload, context) -> {
//                    context.server().execute(() -> {
//                    // ---------- SWAP ----------
//                    if (payload.fromSlot() >= 0 && payload.toSlot() >= 0) {
//                        inventorySwapped = true;
//                        invFromSlot = payload.fromSlot();
//                        invToSlot = payload.toSlot();
//                    }
//
//                    // ---------- DROP ----------
//                    if (payload.dropFlag() == 1) {
//                        inventoryDropped = true;
//                        droppedSlot = payload.dropSlot();
//                        droppedAll = payload.dropAll();
//                    }
//                });
//                }
//        );
//
//        ServerPlayNetworking.registerGlobalReceiver(
//                SelectRecipePayload.ID,
//                (payload, context) -> {
//                    context.server().execute(() -> {
//                        var player = context.player();
//                        var recipeId = payload.recipeId();
//
//                        System.out.println(
//                                "[MinecraftAIMod] Player " + player.getName().getString()
//                                        + " selected recipe " + recipeId
//                        );
//
//                        craft(recipeId);
//                        item_crafted_id = recipeId;
//                        crafted = true;
//                    });
//                }
//        );
//
//
//        ServerPlayNetworking.registerGlobalReceiver(
//                InteractionPayload.ID,
//                (payload, context) -> {
//                    context.server().execute(() -> {
//                        rightClickThisTick = true;
//                    });
//                }
//        );
//
//
//
//        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
//            if (!world.isClient() && player == agent) {
//                System.out.println("Agent placed block at " + hitResult.getBlockPos());
//                // Your logic here
//                placedBlock = true;
//            }
//            return ActionResult.PASS;
//        });
//        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
//            dispatcher.register(
//                    literal("start_training")
//                            .then(argument("agentName", StringArgumentType.string())
//                                    .then(argument("agentPort", IntegerArgumentType.integer())
//                                        .executes(ctx -> {
//                                            cmdSrc = ctx.getSource();
//                                            MinecraftServer server = ctx.getSource().getServer();
//                                            if(!data_collection) {
//                                                agentName = StringArgumentType.getString(ctx, "agentName").toLowerCase();
//
//
//                                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
//                                            }
//
//                                            agentPort = IntegerArgumentType.getInteger(ctx, "agentPort");
//
//
//                                            collecting = true;
//
//                                            if(!data_collection)
//                                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/tick rate 35");
//                                            ctx.getSource().sendFeedback(() -> Text.literal("Started training!"), false);
//                                            try {
//                                                if (socket != null) {
//                                                    socket.close();
//                                                    socket = null;
//                                                }
//                                            } catch (IOException e) {
//                                                //nothing
//                                            }
//
//                                            try{
//                                                if(serverSocket != null){
//                                                    serverSocket.close();
//                                                    serverSocket = null;
//                                                }
//                                            }catch (IOException e){
//                                                //nothing
//                                            }
//                                            try {
////                                                System.out.println("INFO: Made process");
//
//                                                serverSocket = new ServerSocket(agentPort);
//                                                ctx.getSource().sendFeedback(() -> Text.literal("Java Server Ready, Port: " + agentPort + "..."), false);
//                                                socket = serverSocket.accept();
//                                                socket.setTcpNoDelay(true);
//                                                socket.setSoTimeout(0);
//
//                                                input = new DataInputStream(socket.getInputStream());
//                                                output = new DataOutputStream(socket.getOutputStream());
////                                                System.out.println("INFO: Socket Connected");
//
//                                                int agent_info_dim = 21;
//                                                int numItems = Registries.ITEM.size();
//                                                int numBlocks = Registries.BLOCK.size();
//                                                int numEntities = Registries.ENTITY_TYPE.size();
//
//
//                                                if(data_collection){
//                                                    sendState = true;
//                                                }else {
//                                                    output.writeInt(agent_info_dim);
//                                                    output.writeInt(numItems);
//                                                    output.writeInt(numBlocks);
//                                                    output.writeInt(numEntities);
//                                                    output.flush();
//
//                                                    resetPlayer = true;
//                                                }
//
//                                            } catch (Exception e) {
//                                                e.printStackTrace();
//                                                System.out.println("ERROR: FAILED ");
//                                            }
//                                            System.out.println("Success? ");
//
//                                            movementActions.put(0, this::moveForward);
//                                            movementActions.put(1, this::moveBackward);
//                                            movementActions.put(3, this::moveLeft);
//                                            movementActions.put(4, this::moveRight);
//                                            movementActions.put(2, this::moveForward);
//                                            movementActions.put(5, null);
//
//                                            panCam.put(0, this::lookUp);
//                                            panCam.put(1, this::lookDown);
//                                            panCam.put(2, this::rotateLeft);
//                                            panCam.put(3, this::rotateRight);
//                                            panCam.put(4, null);
//
//                                            checkpoints.put(new Tuple3((int)Math.floor(186/REGION), 0, (int)Math.floor(-206/REGION)), 50.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(192/REGION), 0, (int)Math.floor(-205/REGION)), 55.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(191/REGION), 0, (int)Math.floor(-200/REGION)), 50.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(189/REGION), 0, (int)Math.floor(-197/REGION)), 60.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(188/REGION), 0, (int)Math.floor(-200/REGION)), 70.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(184/REGION), 0, (int)Math.floor(-209/REGION)), 100.0f); // Major intersection
//
//                                            // Two exit points
//                                            checkpoints.put(new Tuple3((int)Math.floor(193/REGION), 0, (int)Math.floor(-212/REGION)), 300.0f);
//                                            checkpoints.put(new Tuple3((int)Math.floor(186/REGION), 0, (int)Math.floor(-219/REGION)), 300.0f);
//
//                                            return 1;
//                }))));
//            dispatcher.register(
//                    literal("swap").then(argument("slot1", IntegerArgumentType.integer()).then(argument("slot2", IntegerArgumentType.integer()).executes(ctx -> {
//                int slot1 = IntegerArgumentType.getInteger(ctx, "slot1");
//                int slot2 = IntegerArgumentType.getInteger(ctx, "slot2");
//                swap_items(slot1, slot2);
//
//                inventorySwapped = true;
//                invFromSlot = slot1;
//                invToSlot = slot2;
//
//                return 1;
//            }))));
//
//            dispatcher.register(
//                    literal("drop").then(argument("slot1", IntegerArgumentType.integer()).then(argument("all", IntegerArgumentType.integer()).executes(ctx -> {
//                        int slot1 = IntegerArgumentType.getInteger(ctx, "slot1");
//                        boolean all = IntegerArgumentType.getInteger(ctx, "all") == 1 ? true : false;
//                        dropItem(slot1, all);
//                        return 1;
//                    }))));
//
//            dispatcher.register(literal("get_port").executes(ctx -> {
//                ctx.getSource().sendFeedback(() -> Text.literal("PORT: " + agentPort), false);
//                return 1;
//            }));
//
//            dispatcher.register(literal("changeLookWood").executes(ctx -> {
//                doLookWood = !doLookWood;
//                ctx.getSource().sendFeedback(() -> Text.literal("Look Wood Reward set to: " + doLookWood), false);
//                return 1;
//            }));
//
//            dispatcher.register(literal("debug_checkpoint_on").executes(ctx -> {
//                DEBUGCHECKPOINT = true;
//                ctx.getSource().sendFeedback(() -> Text.literal("Checkpoint Debug On"), false);
//                return 1;
//            }));
//
//            dispatcher.register(literal("debug_stuck_on").executes(ctx -> {
//                DEBUGSTUCK = true;
//                ctx.getSource().sendFeedback(() -> Text.literal("Stuck Debug On"), false);
//                return 1;
//            }));
//
//            dispatcher.register(literal("debug_off").executes(ctx -> {
//                DEBUGCHECKPOINT = false;
//                DEBUGSTUCK = false;
//                return 1;
//            }));
//
//
//            dispatcher.register(literal("stop_training").executes(ctx -> {
//                MinecraftServer server = ctx.getSource().getServer();
//
//                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");
//
//                agent = null;
//                agentName = null;
//
//                collecting = false;
//
//                if(socket != null && !socket.isClosed()) {
//                    try {
//                        socket.close();
//                        serverSocket.close();
//                        ctx.getSource().sendFeedback(() -> Text.literal("Java Socket Closed, Port: " + agentPort + "..."), false);
//                        socket = null;
//                        serverSocket = null;
//                    }
//                    catch (IOException e) {
//                        // ignore?
//                    }
//
//                }
//
//                ctx.getSource().sendFeedback(() -> Text.literal("Stopped training!"), false);
//                return 1;
//            }));
//
//            dispatcher.register(literal("close_socket").executes(ctx -> {
//                if (socket != null && !socket.isClosed()) {
//                    try {
//                        socket.close();
//                        socket = null;
//                    } catch (IOException e) {
//                        //ignore already closed, shouldn't ever really hit this
//                    }
//
//                }
//                return 1;
//            }));
//
//            dispatcher.register(literal("save_state").executes(ctx -> {
//                penalty = 10000000;
//                return 1;
//            }));
//
//
//
//// The command just sets the flag
//            dispatcher.register(literal("collect_blocks")
//                    .then(argument("count", IntegerArgumentType.integer())
//                            .executes(ctx -> {
//                                blockCollectPlayer = ctx.getSource().getPlayer();
//                                blocksToCollect = IntegerArgumentType.getInteger(ctx, "count");
//                                blocksCollected = 0;
//                                collectTickSkip = 0;
//                                collectedGrids = new ArrayList<>();
//                                collectingBlocks = true;
//                                ctx.getSource().sendFeedback(
//                                        () -> Text.literal("Collecting blocks as you move! Fly around in creative."), false);
//                                return 1;
//                            })));
//
//            dispatcher.register(literal("stop_collecting").executes(ctx -> {
//                collectingBlocks = false;
//                Gson gson = new Gson();
//                try (FileWriter writer = new FileWriter("block_grids.json")) {
//                    gson.toJson(collectedGrids, writer);
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//                int finalSize = collectedGrids.size();
//                ctx.getSource().sendFeedback(
//                        () -> Text.literal("Saved " + finalSize + " block grids!"), false);
//                return 1;
//            }));
//        });
//
//
//
//
//        /*
//         ************************************************
//         **   Main State Information Collection Loop   **
//         ************************************************
//         */
//        ServerTickEvents.END_SERVER_TICK.register(server -> {
//
//            if (collectingBlocks && blockCollectPlayer != null) {
//                if (blocksCollected >= blocksToCollect) {
//                    collectingBlocks = false;
//                    Gson gson = new Gson();
//                    try (FileWriter writer = new FileWriter("block_grids.json")) {
//                        gson.toJson(collectedGrids, writer);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                    int finalSize = collectedGrids.size();
//                    server.getCommandManager().parseAndExecute(server.getCommandSource(),
//                            "/say Done! Saved " + finalSize + " block grids!");
//                    return;
//                }
//
//                // Only collect every 5 ticks so you move between samples
//                collectTickSkip++;
//                if (collectTickSkip < 5) return;
//                collectTickSkip = 0;
//
//                int coreRadius = 8;
//                int verticalRadius = 4;
//                int[][][] grid = new int[coreRadius*2+1][verticalRadius*2+1][coreRadius*2+1];
//
//                int ax = blockCollectPlayer.getBlockX();
//                int ay = blockCollectPlayer.getBlockY();
//                int az = blockCollectPlayer.getBlockZ();
//
//                for (int x = -coreRadius; x <= coreRadius; x++)
//                    for (int y = -verticalRadius; y <= verticalRadius; y++)
//                        for (int z = -coreRadius; z <= coreRadius; z++) {
//                            BlockState state = blockCollectPlayer.getEntityWorld()
//                                    .getBlockState(new BlockPos(ax+x, ay+y, az+z));
//                            grid[x+coreRadius][y+verticalRadius][z+coreRadius] =
//                                    Registries.BLOCK.getRawId(state.getBlock());
//                        }
//
//                collectedGrids.add(grid);
//                blocksCollected++;
//
//                if (blocksCollected % 100 == 0) {
//                    int current = blocksCollected;
//                    server.getCommandManager().parseAndExecute(server.getCommandSource(),
//                            "/say Collected " + current + "/" + blocksToCollect);
//                }
//
//                return;
//            }
//
//
//
//
//
//            if (!collecting) return; // only run if training started
//
//            if(spawnPlayer){
//                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
//                spawnPlayer = false;
//                return;
//            }
//            if (agent == null && (agentName != null || data_collection)) {
//                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
//                    if(data_collection){
//                        agent = player;
//                        world = agent.getEntityWorld();
//                        agentName = player.getName().getString();
//                        System.out.println("found " + agentName);
//
//                        break;
//                    }
//                    if (agentName.equals(player.getName().getString().toLowerCase())) {
//                        agent = player;
//                        world = agent.getEntityWorld();
//                        resetPlayer();
//                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/gamemode survival " + agentName);
//                        System.out.println("found");
//                        break;
//                    }
//                }
//                if (agent == null) return;
//            }
//            if (agent == null) return;
//            // Agent Position Information
//            if (resetPlayer) {
//                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");
//                resetPlayer = false;
//                waitForNextRollout = true;
//                return;
//            }
//
//            if(waitForNextRollout) {
//                lastPos = null;
//                try {
//
//                    int startRollout = input.readInt();
//                    spawnPlayer = true;
//                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/time set day");
//                    resetPlayer();
//                    if(startRollout > 0) {
//                        waitForNextRollout = false;
//                        sendState = true;
//                    }
//                }catch (IOException e) {
//                    System.err.println("Error reading rollout command: " + e.getMessage());
//                }
//                agent = null;
//                return;
//            }
//
//            if(!server.getPlayerManager().getPlayerList().contains(agent)){
//                penalty = -10.0f;
//                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " spawn");
//                agent = null;
//                return;
//            }
//
//            if (sendState) {
//                sendStateInfo(server);
//                sendState = false;
//                recieveAction = true;
//                return;
//            }
//
//            if(data_collection && (updateCraftingRecipes || tableNearby != tableNearbyLastRecipeSend)){
//                updateCraftingRecipes = false;
//                List<RecipeEntry<?>> craftableRecipes = getCraftableRecipes(tableNearby);
//                List<List<CraftOption>> pages = buildCraftPages(craftableRecipes);
//
//                ServerPlayNetworking.send(
//                        agent, // ServerPlayerEntity
//                        new CraftPagesPayload(pages)
//                );
//
//                tableNearbyLastRecipeSend = tableNearby;
//
//            }
//
//            List<Float> actions = null;
////            Recieve action
//            if (recieveAction) {
//                try {
//                    if(data_collection){
//                        input.readInt();
//                        int[] act = getExpertAction();
//
//                        Action.Builder actionBuilder = Action.newBuilder();
//                        for (int a : act) {
//                            actionBuilder.addActions((float) a);
//                        }
//                        Action actionMsg = actionBuilder.build();
//                        byte[] payload = actionMsg.toByteArray();
//                        output.writeInt(payload.length);
//                        output.write(payload);
//                        output.flush();
//
//                        int continue_rollout = input.readInt();
//                        if(continue_rollout == 1){
//                            sendState = true;
//                        }
//                        return;
//                    }
//                    int respLen = input.readInt();
//                    byte[] resp = input.readNBytes(respLen);
//
//                    Action action = Action.parseFrom(resp);
//
//                    actions = action.getActionsList();
//
//                    applyAction(actions);
//
//                    recieveAction = false;
//                    sendReward = true;
//                    if (agent == null) return;
//                } catch (Exception e) {
//
//                }
//            }
//
//            if (sendReward) {
//                try{
//                    float reward = (float) getReward(actions) + penalty;
//                    int done_tag = penalty == -10.0f ? 1 : 0;
//                    penalty = 0;
//
//
//                    output.writeFloat(reward);
//                    output.writeInt(done_tag);
//                    output.flush();
//
//                    sendReward = false;
//
//
//                    int continueRollout = input.readInt();
//
//                    if(continueRollout == 1){
//                        sendState = true;
//                    } else{
//                        resetPlayer = true;
//                    }
//
//
//                }
//                catch(IOException e){
//                    System.out.println("ERROR: FAILED");
//                    server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
//                }
//            }
//
//        });
//
//    }
//    private float lastYaw = 0;
//    private float lastPitch = 0;
//
//    private int[] getExpertAction() {
//        ScreenHandler handler = agent.currentScreenHandler;
//        ContainerType openContainer = getContainerType(handler);
//
//        State stateInfo = null;
//        if(openContainer == ContainerType.PlayerInventory) {
//            openContainer = ContainerType.NONE;
//        }else if(openContainer == ContainerType.UNKNOWN){
//            //Close container
//            agent.closeHandledScreen();
//            openContainer = ContainerType.NONE;
//        }
//
//        if(openContainer != ContainerType.NONE){
//            int container_size = get_Container_size(openContainer);
//
//            int inv_slot = -1;
//            int cont_slot = -1;
//
//            if(inventorySwapped){
//                if(invFromSlot > container_size){
//                    inv_slot = mapClientToServerSlotForContHandling(invFromSlot - container_size);
//                    cont_slot = invToSlot;
//                }else{
//                    inv_slot = mapClientToServerSlotForContHandling(invToSlot - container_size);
//                    cont_slot = invFromSlot;
//                }
//            }
//
//            inventorySwapped = false;
//            invFromSlot = DEFUAL_VAL;
//            invToSlot = DEFUAL_VAL;
//
//            inventoryDropped = false;
//            droppedAll = DEFUAL_VAL;
//            droppedSlot = DEFUAL_VAL;
//
//            item_crafted_id = DEFUAL_VAL;
//
//            container_open_last_tick = true;
//
//            return new int[]{
//                  inv_slot,
//                  cont_slot,
//                  0
//            };
//        }else if(container_open_last_tick){
//            container_open_last_tick = false;
//            return new int[]{
//                    0,
//                    0,
//                    1
//            };
//        }
//
//        int movement = 4;
//        int side_move = 2;
//        boolean f = agent.getPlayerInput().forward();
//        boolean b = agent.getPlayerInput().backward();
//        boolean l = agent.getPlayerInput().left();
//        boolean r = agent.getPlayerInput().right();
//        boolean j = agent.getPlayerInput().jump();
//
//        if (f && j) movement = 2;
//        else if (f) movement = 0;
//        else if (b) movement = 1;
//        else if (j) movement = 3;
//
//        if (l) side_move = 0;
//        else if (r) side_move = 1;
//
//        int item_use = 2;
//        if(placedBlock || agent.isUsingItem()){
//            item_use = 1;
//            placedBlock = false;
////            rightClickThisTick = false;
//            rightClickLastTick = 2;
//        }else if(agent.handSwinging){
//            if(rightClickLastTick > 0) rightClickLastTick -= 1;
//            else item_use = 0;
//        }
//        // Camera pan
//        float yaw = agent.getYaw();
//        float pitch = agent.getPitch();
//
//        float dy = yaw - lastYaw;
//        float dp = pitch - lastPitch;
//
//        lastYaw = yaw;
//        lastPitch = pitch;
//
//        float hThresh = 2.5f;
//        float vThresh = 1.5f;
//
//        int pan_cam = 4; // none
//
//        if (dp < -vThresh) pan_cam = 0; // up
//        else if (dp > vThresh) pan_cam = 1; // down
//        else if (dy < -hThresh) pan_cam = 2; // left
//        else if (dy > hThresh) pan_cam = 3; // right
//
//        // Hotbar
//        int hotbarSlot = agent.getInventory().getSelectedSlot();
//
//        //swap
//        int fromSlot = mapClientToServerSlot(invFromSlot);
//        int toSlot = mapClientToServerSlot(invToSlot);
//
//        //drop
//        int dSlot = droppedSlot;
//        int dAll = droppedAll;
//
//        int crafted_item_id = item_crafted_id;
//
//        int inv_act = 0;
//
//        // Force defauly values just in case something slips through to maintain consistency with agent.
//        if(inventoryDropped || inventorySwapped || crafted){
//            movement = 6;
//            item_use = 2;
//            pan_cam = 4;
//
//            if(inventorySwapped) inv_act = 1;
//            else if(inventoryDropped) inv_act = 2;
//            else inv_act = 3;
//        }
//
//        inventorySwapped = false;
//        invFromSlot = DEFUAL_VAL;
//        invToSlot = DEFUAL_VAL;
//
//        inventoryDropped = false;
//        droppedAll = DEFUAL_VAL;
//        droppedSlot = DEFUAL_VAL;
//
//        item_crafted_id = DEFUAL_VAL;
//        crafted = false;
//
////        System.out.println(movement + ", " + item_use + ", " + hotbarSlot + ", " + pan_cam + ", " + swapFlag + ", " + fromSlot + ", " + toSlot + ", " + dropFlag + ", " + dSlot + ", " + dAll + ", "  + crafted + ", " + crafted_item_id);
//
//        return new int[] {
//                inv_act,
//                movement,
//                side_move,
//                item_use,
//                hotbarSlot,
//                pan_cam,
//                fromSlot,
//                toSlot,
//                dSlot,
//                dAll,
//                crafted_item_id
//        };
//    }
//
//    private int mapClientToServerSlot(int clientSlot) {
//        if (clientSlot == -1) return -1;
//
//        // Hotbar: client 36-44 -> server 0-8
//        if (36 <= clientSlot && clientSlot <= 44) {
//            return clientSlot - 36;
//        }
//
//        // Armor slots: client 5-8 -> server 39,38,37,36 (helmet to boots)
//        if (5 <= clientSlot && clientSlot <= 8) {
//            return 39 - (clientSlot - 5);
//        }
//
//        // Off-hand: client 45 -> server 40
//        if (clientSlot == 45) {
//            return 40;
//        }
//
//        // Main inventory 9-35: same on both sides
//        // Result slot 0: same on both sides
//        return clientSlot;
//    }
//
//    private int mapClientToServerSlotForContHandling(int clientSlot) {
//        if (clientSlot == -1) return -1;
//
//        // Hotbar: client 36-44 -> server 0-8
//        if (clientSlot <= 26) {
//            return clientSlot + 9;
//        }
//
//        return clientSlot - 27;
//    }
//
//    private void resetPlayer() {
//        visitedRegion.clear();
//        visitedWood.clear();
//        lastY = null;
//        lastPos = null;
//        lastMovement = 0;
//        prevMoveAction = -1;
//        isStuck = false;
//        forwardConsistency = 0;
//        stuckCounter = 0;
//        prev_num_logs = 0;
//    }
//
//private double getReward(List<Float> actions) {
//    try {
//        Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());
//
//        if (lastPos == null) {
//            lastPos = currentPos;
//            agentPrevhealth = agent.getHealth();
//            return 0.0;
//        }
//
//        double reward = 0.0;
//
//        // movement reward
//        double dx = currentPos.x - lastPos.x;
//        double dz = currentPos.z - lastPos.z;
//        double dist = Math.sqrt(dx*dx + dz*dz);
//
//        if (dist > 0.05) {
//            reward += dist * 0.5;  // Small reward for moving
//        }
//
//        // New area discovery reward
//        int regionX = (int) Math.floor(currentPos.x / REGION);
//        int regionZ = (int) Math.floor(currentPos.z / REGION);
//        Tuple3 currentRegion = new Tuple3(regionX, 0, regionZ);
//
//        if (!visitedRegion.contains(currentRegion)) {
//            visitedRegion.add(currentRegion);
//            reward += 2.0;
//        }
//
//        // Tree finding
////        if (nearestWood != null) {
////            double current_dist_to_wood = Math.sqrt(
////                    Math.pow(currentPos.x - nearestWood.getX(), 2) +
////                            Math.pow(currentPos.z - nearestWood.getZ(), 2)
////            );
////            double prev_dist_to_wood = Math.sqrt(
////                    Math.pow(lastPos.x - nearestWood.getX(), 2) +
////                            Math.pow(lastPos.z - nearestWood.getZ(), 2)
////            );
////
////            // Reward for getting closer to tree
////            double approach_reward = (prev_dist_to_wood - current_dist_to_wood);
////            reward += approach_reward * 3.0;
////
////            // Looking at tree
////            HitResult lookingAt = raycastWithEntities(agent, 4.5);
////
////            if (lookingAt instanceof BlockHitResult bhs) {
////                BlockPos pos = bhs.getBlockPos();
////                BlockState state = agent.getEntityWorld().getBlockState(pos);
////                boolean isLog = state.isIn(BlockTags.LOGS);
////
////                if (isLog) {
////                    // Bigger reward for actually looking at the log
////                    reward += 2.0;
////
////                    // mining prog
////                    if (block_break_prog > prev_block_break_prog) {
////                        // Reward proportional to mining progress
////                        float progress_delta = block_break_prog - prev_block_break_prog;
////                        reward += progress_delta * 20.0;
////                    }
////
////                    prev_block_break_prog = block_break_prog;
////                }
////            }
////
////            // Remove visited trees from consideration
////            if (current_dist_to_wood <= 2.5) {
////                visitedWood.add(new Tuple3(
////                        nearestWood.getX(),
////                        nearestWood.getY(),
////                        nearestWood.getZ()
////                ));
////                nearestWood = null;
////            }
////        }
//        // Log collection reward
////        var agentInventory = agent.getInventory();
////        int num_logs = 0;
////        for(int i = 0; i <= agentInventory.size(); i++) {
////            ItemStack stack = agentInventory.getStack(i);
////            if (stack == null || stack.isEmpty()) continue;
////
////            if(stack.isIn(ItemTags.LOGS)){
////                num_logs += stack.getCount();
////            }
////        }
//
////        if(num_logs > prev_num_logs){
////            int logs_gained = num_logs - prev_num_logs;
////            reward += logs_gained * 50.0;
////        }
////        prev_num_logs = num_logs;
//
//        // Small penalty for standing still
//        if (dist < 0.05) {
//            reward -= 0.1;
//        }
//
//        // Penalty for collision (bumping into things)
//        boolean isTryingToMove = actions.get(1) <= 4;
//        if (agent.horizontalCollision && isTryingToMove) {
//            reward -= 0.5;
//        }
//
//        // Penalty for unnecessary jumping
//        boolean jumped = (actions.get(1) == 2 || actions.get(1) == 3);
//        if (jumped) {
//            reward -= 0.1;
//        }
//
//        // Penalty for being in air (discourages random jumping)
//        if (!agent.isOnGround() && !agent.isInLava()) {
//            reward -= 0.05;
//        }
//
//        // Big penalties for damage
//        if (agent.isInLava() || agent.isOnFire()) {
//            reward -= 5.0;
//        }
//
//        if (agent.getHealth() < agentPrevhealth) {
//            double damage = agentPrevhealth - agent.getHealth();
//            reward -= damage * 2.0;
//        }
//
//        lastPos = currentPos;
//        agentPrevhealth = agent.getHealth();
//
//        return reward;
//
//    } catch (Exception e) {
//        return 0.0;
//    }
//}
//
//
//    private void applyAction(List<Float> actions) {
//        if (agent == null) return;
//        int inv_act = actions.get(0).intValue();
//        if(inv_act == 0) {
//            int movement = actions.get(1).intValue();
//            int side_movement = actions.get(2).intValue();
//            int item_use = actions.get(3).intValue();
//            int hotbar_idx = actions.get(4).intValue();
//            int pan_id = actions.get(5).intValue();
//
//            Runnable movementAction = null;
//            if (movement <= 2) { // only care about actual movement, not jumping or standing still
//                prevMoveAction = movement;
//                movementAction = movementActions.get(movement);
//            } else {
//                prevMoveAction = -1;
//            }
//            if (movementAction != null) {
//                movementAction.run();
//            }
//
//            Runnable side_move = movementActions.get(side_movement + 3);
//            if(side_movement == 0 || side_movement == 1) side_move.run();
//
//            int jump = 0;
//            if (movement == 2 || movement == 3) {
//                jump = 1;
//            }
//
//            if (jump > 0 && agent.isOnGround() || agent.isInLava() || agent.isSubmergedInWater()) {
//                agent.jump();
//            }
//
//
//            // 2 is don't use item
//            if (item_use == 0)
//                tryHit();
//            else if (item_use == 1)
//                tryPlace();
//            else
//                stopMiningIfNeeded();
//
//            if (hotbar_idx != currentHand) {
//                agent.getInventory().setSelectedSlot(hotbar_idx);
//                currentHand = hotbar_idx;
//            }
//
//            Runnable pan_cam_action = panCam.get(pan_id);
//            if (pan_cam_action != null) {
//                pan_cam_action.run();
//            }
//        }else if(inv_act == 1){
//            int from_slot = actions.get(6).intValue();
//            int to_slot = actions.get(7).intValue();
//
//            swap_items(from_slot, to_slot);
//        }else if(inv_act == 2){
//            int drop_slot = actions.get(8).intValue();
//            boolean drop_all = actions.get(9).intValue() == 1;
//
//            dropItem(drop_slot, drop_all);
//        }else if(inv_act == 3){
//            craft(actions.get(10).intValue());
//        }else{
//            int inv_slot = actions.get(1).intValue();
//            int cont_slot = actions.get(2).intValue();
//            int close_cont = actions.get(3).intValue();
//
//            if(close_cont == 1){
//                agent.closeHandledScreen();
//            }else{
//                swap_items(inv_slot, cont_slot);
//            }
//        }
//
////        System.out.println("ACTION: ");
////        System.out.println(actions);
//
//
//    }
//
//    private BlockPos miningPos = null;
//    private int miningTicks = 0;
//    private Direction miningDir = null;
//
//
//private void tryHit() {
//    HitResult hit = raycastWithEntities(agent, 4.5);
//    agent.swingHand(Hand.MAIN_HAND);
//
//    if(hit instanceof EntityHitResult ehr){
//        Entity target = ehr.getEntity();
//        agent.attack(target);
//        agent.resetLastAttackedTicks();
//    }
//    else if(hit instanceof BlockHitResult bhr) {
//        BlockPos pos = bhr.getBlockPos();
//        BlockState state = world.getBlockState(pos);
//
//        if(!pos.equals(miningPos)) {
//            stopMiningIfNeeded();
//            agent.interactionManager.processBlockBreakingAction(
//                    pos,
//                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
//                    bhr.getSide(),  // Use actual side
//                    world.getHeight(),
//                    0
//            );
//
//            miningPos = pos.toImmutable();
//            miningDir = bhr.getSide();
//            miningTicks = 0;
//            block_break_prog = 0.0f;
//            prev_block_break_prog = 0.0f;
//            return;
//        }
//
//        miningTicks++;
//        float delta = state.calcBlockBreakingDelta(agent, world, pos);
//        block_break_prog = delta * (miningTicks + 1);
//
//        if (block_break_prog >= 1.0f) {
//            // Successfully broke block
//            agent.interactionManager.processBlockBreakingAction(
//                    pos,
//                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
//                    miningDir,
//                    world.getHeight(),
//                    0
//            );
//
//            // Reset everything
//            block_break_prog = 0.0f;
//            prev_block_break_prog = 0.0f;
//            miningPos = null;
//            miningTicks = 0;
//            miningDir = null;
//        }
//
//        return;
//    }
//    stopMiningIfNeeded();
//}
//    private boolean dropItem(int slot, boolean all){
//        PlayerInventory agentInventory = agent.getInventory();
//        ItemStack itemStack = agentInventory.getStack(slot);
//
//        if(itemStack.isEmpty()) return false;
//
//        ItemStack toDrop;
//        if(all){
//            toDrop = itemStack.copy();
//            agentInventory.setStack(slot, ItemStack.EMPTY);
//        }else{
//            toDrop = itemStack.split(1);
//            agentInventory.setStack(slot, itemStack);
//        }
//
//        agent.dropItem(toDrop, false);
//        return true;
//    }
//
//    private boolean swap_container_items(int inv_slot_idx, int container_slot_idx, ScreenHandler handler, ContainerType type){
//        List<Slot> slots = handler.slots;
//
//        int size = get_Container_size(type);
//        if(container_slot_idx < 0 || container_slot_idx >= size) return false;
//
//        Slot container_slot = slots.get(container_slot_idx);
//        ItemStack container_stack = container_slot.getStack();
//
//        PlayerInventory agentInv = agent.getInventory();
//        ItemStack inv_stack =  agentInv.getStack(inv_slot_idx);
//
//        agentInv.insertStack(inv_slot_idx, container_stack);
//        container_slot.insertStack(inv_stack);
//
//        return true;
//    }
//
//    private boolean swap_items(int slot1, int slot2) {
//        if(slot1 > 41 || slot2 > 41 || slot1 < 0 || slot2 < 0) return false;
//        if (slot1 == slot2) return false; // Wasted action, punish?
//        PlayerInventory agentInventory = agent.getInventory();
//
//        ItemStack stack1 = agentInventory.getStack(slot1);
//        ItemStack stack2 = agentInventory.getStack(slot2);
//
//        if (stack1.isEmpty() && stack2.isEmpty()) return false; // Wasted action, punish?
//
//        boolean slot1IsEquipment = slot1 >= 36 && slot1 <= 40;
//        boolean slot2IsEquipment = slot2 >= 36 && slot2 <= 40;
//
//        if (slot1IsEquipment && !stack2.isEmpty() && !canEquipInSlot(stack2, slot1)) return false;
//        if (slot2IsEquipment && !stack1.isEmpty() && !canEquipInSlot(stack1, slot2)) return false;
//
//        agentInventory.setStack(slot1, stack2);
//        agentInventory.setStack(slot2, stack1);
//
//        return true;
//    }
//
//    private boolean canEquipInSlot(ItemStack stack, int slot){
//        var equippable = stack.getComponents().get(DataComponentTypes.EQUIPPABLE);
//        if(equippable == null) return false;
//
//        EquipmentSlot eqSlot = getEquipmentSlotFromInventorySlot(slot);
//
//        if(eqSlot == null) return false;
//
//        return eqSlot == equippable.slot();
//    }
//
//    private EquipmentSlot getEquipmentSlotFromInventorySlot(int slot){
//        return switch(slot){
//            case 36 -> EquipmentSlot.FEET;    // Boots
//            case 37 -> EquipmentSlot.LEGS;    // Leggings
//            case 38 -> EquipmentSlot.CHEST;   // Chestplate
//            case 39 -> EquipmentSlot.HEAD;    // Helmet
//            case 40 -> EquipmentSlot.OFFHAND; // Offhand
//            default -> null; // Not an equipment slot
//        };
//    }
//
////    Remove
//    private List<RecipeEntry<?>> getCraftableRecipes(boolean hasTable) {
//        ServerRecipeManager recipeManager = world.getServer().getRecipeManager();
//
//        List<RecipeEntry<?>> craftable = new ArrayList<>();
//
//        for(RecipeEntry<?> entry : recipeManager.values()){
//            Recipe<?> recipe = entry.value();
//
//            if (!(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
//                continue;
//            }
//
//            if(requiresTable(recipe) && !hasTable) continue;
//
//            if(canPlayerCraftRecipe(recipe, false))
//                craftable.add(entry);
//        }
//
//        return craftable;
//    }
//
////    Remove
//    private List<List<CraftOption>> buildCraftPages(List<RecipeEntry<?>> recipes){
//        int PER_PAGE = 5;
////        int currentPage = 0;
//        List<List<CraftOption>> pages = new ArrayList<>();
//        List<CraftOption> flat = new ArrayList<>();
//
//        ContextParameterMap context = new ContextParameterMap.Builder()
//                .add(SlotDisplayContexts.REGISTRIES, world.getServer().getRegistryManager())
//                .build(SlotDisplayContexts.CONTEXT_TYPE);
//
//        for(RecipeEntry<?> entry : recipes) {
//            Recipe<?> recipe = entry.value();
//
//            Identifier itemId = null;
//            for (RecipeDisplay display : recipe.getDisplays()) {
//                ItemStack stack = display.result().getFirst(context);
//                if (!stack.isEmpty()) {
//                    itemId = Registries.ITEM.getId(stack.getItem());
//                    break; // stop after first
//                }
//            }
//            if(itemId == null) continue;
//
//            Item targetItem = Registries.ITEM.get(itemId);
//            int item_id = Registries.ITEM.getRawId(targetItem);
//            String displayName = targetItem.getName().getString();
//
//            flat.add(new CraftOption(
//                    item_id,
//                    displayName
//            ));
//
//            if(flat.size() == PER_PAGE){
//                pages.add(flat);
//                flat = new ArrayList<>();
//            }
//        }
//        if (!flat.isEmpty()) {
//            pages.add(flat);
//        }
//        return pages;
//    }
//
//    private void craft(int item_id){
////        ServerRecipeManager recipeManager = world.getServer().getRecipeManager();
//
//        Item targetItem = Registries.ITEM.get(item_id);
//        ServerRecipeManager recipeManager =  world.getServer().getRecipeManager();
//
//        ContextParameterMap context = new ContextParameterMap.Builder()
//                .add(SlotDisplayContexts.REGISTRIES, world.getServer().getRegistryManager())
//                .build(SlotDisplayContexts.CONTEXT_TYPE);
//
//        List<RecipeEntry<?>> matchingRecipes = new ArrayList<>();
//
//        for(RecipeEntry<?> recipeEntry : recipeManager.values()){
//            Recipe<?> recipe = recipeEntry.value();
//            List<RecipeDisplay> displays = recipe.getDisplays();
//
//            for(RecipeDisplay display : displays){
//                SlotDisplay resultDisplay = display.result();
//
//                if(slotDisplayContainsItem(resultDisplay, targetItem, context)){
//                    matchingRecipes.add(recipeEntry);
//                    break;
//                }
//            }
//        }
//        for(RecipeEntry<?> recipeEntry : matchingRecipes){
//            if(canPlayerCraftRecipe(recipeEntry.value(), true)){
//                System.out.println("Can craft: " + recipeEntry.id());
//                // Actually remove items and craft
//                break;
//            }
//        }
//
//    }
//
//    private void debugCrafting(Map<Ingredient, Integer> itemMap){
//        System.out.println("Player needs items");
//        for (Map.Entry<Ingredient, Integer> entry : itemMap.entrySet()) {
//            Ingredient ingredient = entry.getKey();
//            int value = entry.getValue(); // auto-unboxing Integer → int
//
//            System.out.println("Some Item -> " + value);
//        }
//    }
//
//    private boolean canPlayerCraftRecipe(Recipe<?> recipe, boolean craft){
//        IngredientPlacement placement = recipe.getIngredientPlacement();
//
//        if(requiresTable(recipe)){
//            if(!tableNearby) {
//                System.out.println("Table needs items");
//                return false;
//            }
//        }
//        // Get all ingredient slots
//        //Map the needed ingredients so we can use it to search the player inventory
//        Map<Ingredient, Integer> itemMap = new HashMap<>();
//        for(Ingredient ingredient : placement.getIngredients()){
//            itemMap.put(ingredient,  itemMap.getOrDefault(ingredient, 0) + 1);
//        }
//        if(craft)
//            debugCrafting(itemMap);
//
//        // Check if the player has said items, list item as removable if player has items
//        Map<Integer, Integer> removeIfSuccessful = new HashMap<>();
//        for (Map.Entry<Ingredient, Integer> entry : itemMap.entrySet()) {
//            Ingredient key = entry.getKey();
//            int value = entry.getValue(); // auto-unboxing Integer → int
//
//            if(!playerContains(key, value, removeIfSuccessful)) return false;
//        }
//
//
//        if(craft)
//            craftItem(recipe, removeIfSuccessful);
//
//        return true;
//    }
//
//    private boolean requiresTable(Recipe<?> recipe){
//        //Item placement is strict
//        if(recipe instanceof ShapedRecipe shapedRecipe){
//            return shapedRecipe.getWidth() > 2 || shapedRecipe.getHeight() > 2;
//        }
//
//        //Item placement does not matter
//        if(recipe instanceof ShapelessRecipe shapelessRecipe){
//            return shapelessRecipe.getIngredientPlacement().getIngredients().size() > 4;
//        }
//
//        // other recipes such as smelting, etc
//        return false;
//    }
//
//    private boolean playerContains(Ingredient ingredient, int needed, Map<Integer, Integer> removeIfSuccessful){
//        PlayerInventory agentInventory = agent.getInventory();
//        for(int i = 0; i < 36; i++) {
//            ItemStack stack = agentInventory.getStack(i);
//            if (stack.isEmpty()) continue;
//            if (!ingredient.test(stack)) continue;
//            int take = Math.min(stack.getCount(), needed);
//
//            if (take > 0) {
//                removeIfSuccessful.put(i, take);
//                needed -= take;
//            }
//
//            if (needed <= 0) {
//                return true;
//            }
//        }
//
//        // Ignore offhand item for now
//        return false;
//
//    }
//
//    private void craftItem(Recipe<?> recipe, Map<Integer, Integer> removeIfSuccessful ){
//        PlayerInventory agentInventory = agent.getInventory();
//
//        for(Map.Entry<Integer, Integer> entry : removeIfSuccessful.entrySet()){
//            int slot = entry.getKey();
//            int count = entry.getValue();
//
//            ItemStack stack = agentInventory.getStack(slot);
//            stack.decrement(count);
//        }
//
////        Give player the item here somehow
//        ItemStack result = recipe.craft(null, world.getServer().getRegistryManager()); // Get result
//        agent.getInventory().insertStack(result);
//    }
//    private boolean slotDisplayContainsItem(SlotDisplay slotDisplay, Item targetItem, ContextParameterMap context) {
//        // SlotDisplay can be different types (ItemStackSlotDisplay, ItemSlotDisplay, etc.)
//
//        List<ItemStack> stacks = slotDisplay.getStacks(context); // This gets all possible ItemStacks
//
//        for (ItemStack stack : stacks) {
//            if (stack.getItem() == targetItem) {
//                return true;
//            }
//        }
//
//        return false;
//    }
//
//    private HitResult raycastWithEntities(ServerPlayerEntity player, double reach) {
//        Vec3d start = player.getCameraPosVec(1.0f);
//        Vec3d look = player.getRotationVec(1.0f);
//        Vec3d end = start.add(look.multiply(reach));
//
//        HitResult blockHit = player.raycast(reach, 1.0f, false);
//
//        Box box = player.getBoundingBox().stretch(look.multiply(reach)).expand(1.0D);
//
//        EntityHitResult entityHit = ProjectileUtil.raycast(
//                player,
//                start,
//                end,
//                box,
//                entity -> !entity.isSpectator() && entity.isAttackable(),
//                reach * reach
//        );
//
//        if (entityHit != null) {
//            double entityDist = entityHit.getPos().squaredDistanceTo(start);
//            double blockDist = blockHit.getPos().squaredDistanceTo(start);
//
//            if (entityDist < blockDist) {
//                return entityHit;
//            }
//        }
//
//        return blockHit;
//    }
//
//    private void stopMiningIfNeeded() {
//        if (miningPos != null) {
//            agent.interactionManager.processBlockBreakingAction(
//                    miningPos,
//                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
//                    miningDir,
//                    world.getHeight(),
//                    0
//            );
//            miningPos = null;
//            miningDir = null;
//            miningTicks = 0;        }
//    }
//
//    public void tryPlace() {
//        HitResult hit = raycastWithEntities(agent, 4.5);
//        if(hit.getType() == HitResult.Type.MISS) {
//            agent.interactionManager.interactItem(agent, world, agent.getMainHandStack(), Hand.MAIN_HAND);
//            return;
//        }
//        if(hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
//            agent.swingHand(Hand.MAIN_HAND);
//
//            ActionResult result = agent.interactionManager.interactBlock(
//                    agent,
//                    world,
//                    agent.getMainHandStack(),
//                    Hand.MAIN_HAND,
//                    bhr
//            );
//        }
//
//
//    }
//
//
//    private void rotateLeft() {
//        agent.setYaw(agent.getYaw() - 5f);  // turn 5 degrees left
//    }
//
//    private void rotateRight() {
//        agent.setYaw(agent.getYaw() + 5f);  // turn 5 degrees right
//    }
//
//    private void lookUp() {
//        agent.setPitch(Math.max(agent.getPitch() - 3f, -89f)); // can't look past straight up
//    }
//
//    private void lookDown() {
//        agent.setPitch(Math.min(agent.getPitch() + 3f, 89f)); // can't look past straight down
//    }
//
//    private void clip_velocity(){
//        Vec3d vel = agent.getVelocity();
//        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
//
//        if(horizSpeed > maxSpeed){
//            double scaled = maxSpeed / horizSpeed;
//            Vec3d new_vel = new Vec3d(vel.x * scaled, vel.y, vel.z * scaled);
//            agent.setVelocity(new_vel);
//        }
//    }
//
//    private void moveForward(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//
//        Vec3d movement = new Vec3d(lookDir.x * speed, 0, lookDir.z * speed);
//        agent.addVelocity(movement);
//
//        clip_velocity();
//    }
//
//    private void moveBackward(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//
//        Vec3d movement = new Vec3d(lookDir.x * -speed, 0, lookDir.z * -speed);
//        agent.addVelocity(movement);
//
//        clip_velocity();
//    }
//
//    private void moveRight(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//
//        Vec3d movement = new Vec3d(-lookDir.z * speed, 0, lookDir.x * speed);
//        agent.addVelocity(movement);
//
//        clip_velocity();
//    }
//
//    private void moveLeft(){
//        Vec3d lookDir = agent.getRotationVec(1.0F);
//
//        Vec3d movement = new Vec3d(lookDir.z * speed, 0, -lookDir.x * speed);
//        agent.addVelocity(movement);
//
//        clip_velocity();
//    }
//
//    private void sendStateInfo(MinecraftServer server){
//        double[] agentInfo = getAgentInfo();
//        // Get agent inventory
//        // Pass through transformer to encode values as a flattened vector
//        double[][] inventoryArray = getInventory();
//
//        // Get nearby entities
//        double[][] nearbyEntities = getNearbyEntities();
//
//        // Get nearby Block information
//        double[][][] nearbyBlocks = getNearbyBlocks();
//
//        double[][][][] nearbyItems = getNearbyItemDrops();
//
//        // prolly could hard code this into the function?
//        List<Double> agentInfoList = new ArrayList<>(agentInfo.length);
//        for (double value : agentInfo) {
//            agentInfoList.add(value);
//        }
//
//        Matrix.Builder inventoryMatrix = Matrix.newBuilder();
//        for(double[] row : inventoryArray) {
//            Row.Builder rowBuilder = Row.newBuilder();
//            for(double value : row) {
//                rowBuilder.addValues(value);
//            }
//            inventoryMatrix.addRows(rowBuilder);
//        }
//
//        Matrix.Builder nearbyEntitiesMatrix = Matrix.newBuilder();
//        for(double[] row : nearbyEntities) {
//            Row.Builder rowBuilder = Row.newBuilder();
//            for(double value : row) {
//                rowBuilder.addValues(value);
//            }
//            nearbyEntitiesMatrix.addRows(rowBuilder);
//        }
//
//        Matrix3D.Builder nearbyBlocksMatrix = Matrix3D.newBuilder();
//        for(double[][] matrix : nearbyBlocks) {
//            Matrix.Builder matrixBuilder = Matrix.newBuilder();
//            for(double[] row : matrix) {
//                Row.Builder rowBuilder = Row.newBuilder();
//                for(double value : row) {
//                    rowBuilder.addValues(value);
//                }
//                matrixBuilder.addRows(rowBuilder);
//            }
//            nearbyBlocksMatrix.addMatrix(matrixBuilder);
//        }
//
//        Matrix4D.Builder nearbyItemsMatrix = Matrix4D.newBuilder();
//        for(double[][][] matrix3D : nearbyItems) {
//            Matrix3D.Builder matrix3DBuilder = Matrix3D.newBuilder();
//            for(double[][] matrix : matrix3D) {
//                Matrix.Builder matrixBuilder = Matrix.newBuilder();
//                for(double[] row : matrix) {
//                    Row.Builder rowBuilder = Row.newBuilder();
//                    for(double value : row) {
//                        rowBuilder.addValues(value);
//                    }
//                    matrixBuilder.addRows(rowBuilder);
//                }
//                matrix3DBuilder.addMatrix(matrixBuilder);
//            }
//            nearbyItemsMatrix.addMatrix3D(matrix3DBuilder);
//        }
//
//        ScreenHandler handler = agent.currentScreenHandler;
//        ContainerType openContainer = getContainerType(handler);
//
//        State stateInfo = null;
//        if(openContainer == ContainerType.PlayerInventory) {
//            openContainer = ContainerType.NONE;
//        }else if(openContainer == ContainerType.UNKNOWN){
//            //Close container
//            agent.closeHandledScreen();
//            openContainer = ContainerType.NONE;
//        }
//
//        double[][] container = getContainer(handler, openContainer);
//        double[] containerMask = getContainerMask(openContainer);
//
//        Matrix.Builder conatinerMatrix = Matrix.newBuilder();
//        for(double[] row : container) {
//            Row.Builder rowBuilder = Row.newBuilder();
//            for(double value : row) {
//                rowBuilder.addValues(value);
//            }
//            conatinerMatrix.addRows(rowBuilder);
//        }
//
//        List<Double> containerMaskList = new ArrayList<>(containerMask.length);
//        for (double value : containerMask) {
//            containerMaskList.add(value);
//        }
//
//        stateInfo = State.newBuilder()
//                .addAllAgentInfo(agentInfoList)
//                .setInventory(inventoryMatrix)
//                .setNearbyEntities(nearbyEntitiesMatrix)
//                .setNearbyBlocks(nearbyBlocksMatrix)
//                .setNearbyItemDrops(nearbyItemsMatrix)
//                .setContainerType(openContainer.ordinal())
//                .setContainer(conatinerMatrix)
//                .addAllContainerMask(containerMaskList)
//                .build();
//
//        try {
//            byte[] payload = stateInfo.toByteArray();
//            output.writeInt(payload.length);
//            output.write(payload);
//            output.flush();
//        } catch (IOException e) {
//            e.printStackTrace();
//            server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
//            System.out.println("Failed to send data to python, shutting down training...");
//        }
//    }
//
//
//    public double[] getAgentInfo(){
//        double health = agent.getHealth();
//        double hunger = agent.getHungerManager().getFoodLevel();
//        double saturation = agent.getHungerManager().getSaturationLevel();
//
////        double[] agentPos = getPos();
//
//        Vec3d vel = agent.getVelocity();
//        double vx = vel.x;
//        double vy = vel.y;
//        double vz = vel.z;
//
//        health /= 20.0;
//        hunger /= 20.0;
//        saturation /= 20.0;
//        vy /= 0.5;
//        vx /= speed;
//        vz /= speed;
//
//        BlockState blockBelow = world.getBlockState(agent.getBlockPos().down());
//        double blockBelowId = Block.STATE_IDS.getRawId(blockBelow);
//
//        double colliding = agent.horizontalCollision || agent.verticalCollision ? 1 : 0;
//        double isSneak = agent.isSneaking() ? 1 : 0;
//
//        double isOnFire = agent.isOnFire() ? 1 : 0;
//        double inWater = agent.isTouchingWater() ? 1 : 0;
//        double inLava = agent.isInLava() ? 1 : 0;
//        double onGround = agent.isOnGround() ? 1 : 0;
//        double isFalling  = vy < -0.6 ? 1 : 0;
//        double wasHurt = agent.hurtTime > 0 ? 1 : 0;
//
//        double mainHandCount = agent.getMainHandStack().getCount();
//        double mainHandSlot = agent.getInventory().getSelectedSlot();
//
//        double time = (double) (world.getTime() % 24000) / 24000.0;
//
//        double lightLevel = world.getLightLevel(agent.getBlockPos()) / 15.0;
//
//        HitResult raycast = raycastWithEntities(agent, 4.5);
//
//        double looking_at = 0;
//        double looking_at_id = 0;
//
//        double[] row;
//        switch(raycast.getType()) {
//            case BLOCK:
//                looking_at = 1;
//
//                BlockHitResult blockHit = (BlockHitResult) raycast;
//                BlockPos pos = blockHit.getBlockPos();
//                BlockState state = agent.getEntityWorld().getBlockState(pos);
//
//                row = new double[]{
//                        Registries.BLOCK.getRawId(state.getBlock()),
//                        0,
//                        0,
//                        0,
//                        0,
//                        0,
//                        0,
//                        0
//                };
//                break;
//            case ENTITY:
//                looking_at = 2;
//
//                EntityHitResult entityHit = (EntityHitResult) raycast;
//                Entity e = entityHit.getEntity();
//
//                row = new double[]{
//                        Registries.ENTITY_TYPE.getRawId(e.getType()),
//                        (e instanceof Monster) ? 1 : 0,
//                        (e instanceof Angerable) ? 1 : 0,
//                        (e instanceof PassiveEntity) ? 1 : 0,
//                        ((!(e instanceof Monster)) && (!(e instanceof Angerable)) && (!(e instanceof PassiveEntity))) ? 1 : 0,
//                        e.getX() - agent.getX(),
//                        e.getY() - agent.getY(),
//                        e.getZ() - agent.getZ()
//                };
//                break;
//            default:
//                row =  new double[]{
//                        0,
//                        0,
//                        0,
//                        0,
//                        0,
//                        0,
//                        0,
//                        0
//                };
//                break;
//        }
//
//        double normYaw = agent.getYaw() / 180.0;
//        double normPitch = agent.getPitch() / 90.0;
//
//        double[] agentInfo = new double[]{
//                health,
//                hunger,
//                saturation,
//                normYaw,
//                normPitch,
//                vx,
//                vy,
//                vz,
//                blockBelowId,
//                colliding,
//                isSneak,
//                isOnFire,
//                inWater,
//                inLava,
//                onGround,
//                isFalling,
//                wasHurt,
//                mainHandCount,
//                mainHandSlot,
//                time,
//                lightLevel,
//                block_break_prog,
//                looking_at,
//                row[0],
//                row[1],
//                row[2],
//                row[3],
//                row[4],
//                row[5],
//                row[6],
//                row[7],
//        };
//
//        return agentInfo;
//    }
//    public double[] getPos() {
//        return new double[]{ agent.getX(), agent.getY(), agent.getZ(), agent.getYaw(), agent.getPitch() };
//    }
//
//    /**
//     * Used to collect the possible utility values of an item
//     * @param item The {@link Item} to find the possible utilities of
//     * @param itemType The {@link int[]} representing the type of item, if provided [isArmor, isFodd, isTool, isWeapon]
//     * @return array of 2 values representing 2 utility options.
//     *         isArmor: [protection, toughness]
//     *         isFood: [nutrition, saturation]
//     *         isTool: [dmg per block, default mining speed]
//     *         isWeapon: [dmg, attack speed]
//     */
//    public double[] getUtility(Item item, int[] itemType) {
//        double[] utility = {0, 0};
//        // isArmor
//        if(itemType[0] == 1) {
//            // [protection, toughness]
//            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
//
//            if (modifiers == null) return utility;
//
//            for (var modifier : modifiers.modifiers()) {
//                if (modifier.attribute().equals(EntityAttributes.ARMOR)) {
//                    utility[0] += modifier.modifier().value();
//                }
//                if (modifier.attribute().equals(EntityAttributes.ARMOR_TOUGHNESS)) {
//                    utility[1] += modifier.modifier().value();
//                }
//            }
//        }
//        // isFood
//        else if(itemType[1] == 1) {
//            // [nutrition (hunger fill), saturation (how long)]
//            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);
//
//            if (food == null) return utility;
//
//            utility[0] = food.nutrition();
//            utility[1] = food.saturation();
//        }
//        // isTool
//        else if(itemType[2] == 1){
//            // [damage per block, default mining speed]
//            ToolComponent tool = item.getComponents().get(DataComponentTypes.TOOL);
//
//            if(tool == null) return utility;
//
//            utility[0] = tool.damagePerBlock();
//            utility[1] = tool.defaultMiningSpeed();
//        }
//        //isWeapon
//        else if(itemType[3] == 1){
//            // Handle Ranged weapons
//            // [based dmg, draw time]
//            if (item instanceof BowItem) {
//                utility[0] = 6.0;
//                utility[1] = 0.80;
//                return utility;
//            }
//            if  (item instanceof CrossbowItem) {
//                utility[0] = 6.0;
//                utility[1] = 0.50;
//                return utility;
//            }
//
//            // [damage, attack speed]
//            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
//
//            if (modifiers == null) return utility;
//
//            for (var modifier : modifiers.modifiers()) {
//                if (modifier.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) {
//                    utility[0] += modifier.modifier().value();
//                }
//                if (modifier.attribute().equals(EntityAttributes.ATTACK_SPEED)) {
//                    utility[1] += modifier.modifier().value();
//                }
//            }
//
//        }
//        return utility;
//    }
//
//    /**
//     * Gets the agent inventory.
//     * 0–8   : Hotbar
//     * 9–35  : Main inventory
//     * 36–39  : Armor (boots → leggings → chestplate → helmet)
//     * 40     : Off-hand
//     * 41–44  : Crafting grid output + 2x2 crafting input (ignored)
//     *
//     * @return double[][] where each row =
//     *         [item_id, isArmor, isFood, isTool, isWeapon, utility1, utility2, count, durability]
//     *         Check getUtility function for posible utility values
//     */
//    public double[][] getInventory(){
//        PlayerInventory agentInventory = agent.getInventory();
//
//        double[][] inventoryArray =  new double[41][10];
//
//        for(int i = 0; i <= 40; i++) {
//            ItemStack stack = agentInventory.getStack(i);
//
//            double[] itemInfo = getItemInfo(stack);
//            inventoryArray[i] = itemInfo;
//        }
//
//        if(prevInv == null || !Arrays.deepEquals(inventoryArray, prevInv)){
//            prevInv = deepCopy(inventoryArray);
//            updateCraftingRecipes = true;
//        }
//        return inventoryArray;
//    }
//
//    public double[][] getContainer(ScreenHandler handler, ContainerType type){
//        if(type == ContainerType.NONE) return new double[54][10];
//        List<Slot> slots = handler.slots;
//        int size = get_Container_size(type);
//
//        double[][] container = new double[54][];
//        for(int i = 0; i < size; i++){
//            Slot slot = slots.get(i);
//            ItemStack stack = slot.getStack();
//
//            double[] itemInfo = getItemInfo(stack);
//
//            container[i] = itemInfo;
//        }
//
//        for(int i = size; i < 54; i++){
//            container[i] = new double[10];
//        }
//
//        return container;
//    }
//
//    public double [] getContainerMask(ContainerType type){
//        if(type == ContainerType.NONE) return new double[54];
//
//        int size = get_Container_size(type);
//
//        double[] mask = new double[54];
//
//        for(int i = 0; i < size; i++)
//            mask[i] = 1;
//
//        return mask;
//    }
//
//    ContainerType getContainerType(ScreenHandler handler){
//        boolean containerOpen = handler != null && handler != agent.playerScreenHandler;
//        if(!containerOpen) return ContainerType.NONE; //No conatiner
//
//        if(handler instanceof GenericContainerScreenHandler gcsh) {
//            int rows = gcsh.getRows();
//            return rows == 6 ? ContainerType.DOUBLE_CHEST : ContainerType.CHEST;
//        }
//        else if(handler instanceof FurnaceScreenHandler){
//            return ContainerType.FURNACE;
//        }
//
//        // if(handler instanceof CraftingScreenHandler)
//        // Random error code. Agent handles crafting without UI, signal to close container right away.
//        // Don't want the agent to mess with any other containers either just yet. close container NOW
//        return ContainerType.UNKNOWN;
//    }
//
//    private int get_Container_size(ContainerType type){
//        if(type == ContainerType.CHEST) return 27;
//        if(type == ContainerType.DOUBLE_CHEST) return 54;
//        if(type == ContainerType.FURNACE) return 3;
//        return -1;
//    }
//
//    private double[] getItemInfo(ItemStack stack){
//        Item item = stack.getItem();
//
//        int item_id = Registries.ITEM.getRawId(item);
//        double count = stack.getCount() / 64.0;
//
//        int max = stack.getMaxDamage();
//        double durability;
//
//        if (max > 0) {
//            durability = (double)(max - stack.getDamage()) / (double) max; // in [0,1]
//        } else {
//            durability = 0.0;
//        }
//
//        EquippableComponent equip = item.getComponents().get(DataComponentTypes.EQUIPPABLE);
//        int isArmor = 0;
//        if(equip != null) {
//            EquipmentSlot slot = equip.slot();
//            isArmor = (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) ? 1 : 0;
//        }
//
//        boolean toolBool = item.getComponents().contains(DataComponentTypes.TOOL);
//        boolean foodBool = item.getComponents().contains(DataComponentTypes.FOOD);
//        boolean weaponBool = item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD;
//        boolean rangedWeaponBool = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;
//        boolean fuelBool = world.getFuelRegistry().isFuel(stack);
//
//        int isTool = toolBool ? 1 : 0;
//        int isFood = foodBool ? 1 : 0;
//        int isWeapon = 0;
//        int isFuel = fuelBool ? 1 : 0;
//
//        if(weaponBool || rangedWeaponBool) {
//            isWeapon = 1;
//            isTool = 0;
//        }
//
//        int[] itemType = {isArmor, isFood, isTool, isWeapon, isFuel};
//
//        double[] utility_value = getUtility(item, itemType);
//
//        return new double[]{item_id, isArmor, isFood, isTool, isWeapon, isFuel, utility_value[0], utility_value[1], count, durability};
//    }
//
//    private static double[][] deepCopy(double[][] src) {
//        double[][] copy = new double[src.length][];
//        for (int i = 0; i < src.length; i++) {
//            copy[i] = Arrays.copyOf(src[i], src[i].length);
//        }
//        return copy;
//    }
//
//    /**
//     * Gets Entities within a given radius of the agent
//     * @return double[][] where each row =
//     *         [entity id, isMonster, isAngerable, isPassive, isUnknown, x, y, z]
//     */
//    public double[][] getNearbyEntities(){
//        double agentSearchRadius = 10.0;
//        double[][] foundEntities = new double[10][8];
//
//        Box box = agent.getBoundingBox().expand(agentSearchRadius);
//        List<Entity> nearbyEntities = agent.getEntityWorld().getOtherEntities(agent, box);
//
//        int idx = 0;
//        for (Entity entity : nearbyEntities) {
//            if (idx >= 10) break;
//            if (entity instanceof PlayerEntity || !(entity instanceof LivingEntity)) continue;
//
//            int isMonster = entity instanceof Monster ? 1 : 0;
//            int isAngerable = entity instanceof Angerable ? 1 : 0;
//            int isPassive = entity instanceof PassiveEntity ? 1 : 0;
//            int isUnknown = (isMonster != 1 && isAngerable != 1 && isPassive != 1) ? 1 : 0;
//
//            foundEntities[idx][0] = Registries.ENTITY_TYPE.getRawId(entity.getType());
//            foundEntities[idx][1] = isMonster;
//            foundEntities[idx][2] = isAngerable;
//            foundEntities[idx][3] = isPassive;
//            foundEntities[idx][4] = isUnknown;
//            foundEntities[idx][5] = entity.getX() - agent.getX();
//            foundEntities[idx][6] = entity.getY() - agent.getY();
//            foundEntities[idx][7] = entity.getZ() - agent.getZ();
//
//            idx++;
//        }
//
//        for(int i = idx; i < 10; i++) {
//            Arrays.fill(foundEntities[i], 0);
//        }
//
//        return foundEntities;
//    }
//
//    /**
//     * Gets nearby blocks of the agent given a radius
//     * @return double[][] where each row =
//     *         [Block id, x, y, z]
//     */
//    public double[][][] getNearbyBlocks() {
//        int coreRadius = 8;
//        int verticalRadius = 4; // Vertical space not as significant
//        int tableWithinXBlocks = 3;
//        int maxTableDistSq = tableWithinXBlocks * tableWithinXBlocks;
//
//        double[][][] foundBlocks = new double[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1];
//
//        int agentBlockX = agent.getBlockX();
//        int agentBlockY = agent.getBlockY();
//        int agentBlockZ = agent.getBlockZ();
//
//        tableNearby = false;
//
//        for(int x = agentBlockX - coreRadius; x <= agentBlockX + coreRadius; x++) {
//            for (int y = agentBlockY - verticalRadius; y <= agentBlockY + verticalRadius; y++) {
//                for(int z = agentBlockZ - coreRadius; z <= agentBlockZ + coreRadius; z++) {
//                    BlockPos pos = new BlockPos(x, y, z);
//                    BlockState state = agent.getEntityWorld().getBlockState(pos);
//
//                    if(state.isOf(Blocks.CRAFTING_TABLE)){
//                        int dx = x - agentBlockX;
//                        int dy = y - agentBlockY;
//                        int dz = z - agentBlockZ;
//
//                        if (Math.abs(dx) <= tableWithinXBlocks &&
//                                Math.abs(dy) <= tableWithinXBlocks &&
//                                Math.abs(dz) <= tableWithinXBlocks) {
//                            tableNearby = true;
//                        }
//                    }
//
//                    boolean isLog = state.isIn(BlockTags.LOGS);
//                    if(isLog) {
//                        double dist_to_block = Math.sqrt(Math.pow((agentBlockX - pos.getX()), 2) + Math.pow((agentBlockY - pos.getY()), 2) + Math.pow((agentBlockZ - pos.getZ()), 2));
//                        if(nearestWood == null){
//                            nearestWood = pos; // exclude y to not reward jumping
//                        }
//                        else {
//                            double current_log_dist = Math.sqrt(Math.pow(agentBlockX - nearestWood.getX(), 2) + Math.pow(agentBlockY - nearestWood.getY(), 2) + Math.pow(agentBlockZ - nearestWood.getZ(), 2));
//                            if(dist_to_block < current_log_dist) nearestWood = pos;
//                        }
//                    }
//
//                    int relX = x - agentBlockX + coreRadius;       // shift into array bounds
//                    int relY = y - agentBlockY + verticalRadius;
//                    int relZ = z - agentBlockZ + coreRadius;
//
//                    foundBlocks[relX][relY][relZ] = Registries.BLOCK.getRawId(state.getBlock());
//                }
//            }
//        }
//
//        return foundBlocks;
//    }
//
//    public double[][][][] getNearbyItemDrops(){
//        int coreRadius = 8;
//        int verticalRadius = 3; // Vertical space not as significant
//
//        double[][][][] foundItems = new double[coreRadius * 2 + 1][verticalRadius * 2 + 1][coreRadius * 2 + 1][10];
//
//        for (int i = 0; i < foundItems.length; i++)
//            for (int j = 0; j < foundItems[i].length; j++)
//                for (int k = 0; k < foundItems[i][j].length; k++)
//                    foundItems[i][j][k] = new double[10];
//
//        int agentBlockX = agent.getBlockX();
//        int agentBlockY = agent.getBlockY();
//        int agentBlockZ = agent.getBlockZ();
//
//        Box box = new Box(
//                agent.getX() - coreRadius, agent.getY() - verticalRadius, agent.getZ() - coreRadius,
//                agent.getX() + coreRadius, agent.getY() + verticalRadius, agent.getZ() + coreRadius
//        );
//
//
//        List<ItemEntity> items = world.getEntitiesByClass(
//                ItemEntity.class,
//                box,
//                e -> true
//        );
//
//        for(ItemEntity item : items){
//            int itemX = item.getBlockX() - agentBlockX + coreRadius;
//            int itemY = item.getBlockY() - agentBlockY + verticalRadius;
//            int itemZ = item.getBlockZ() - agentBlockZ + coreRadius;
//
//            ItemStack stack = item.getStack();
//            double[] itemInfo = getItemInfo(stack);
//
//            if (itemX < 0 || itemY < 0 || itemZ < 0 ||
//                    itemX >= foundItems.length ||
//                    itemY >= foundItems[0].length ||
//                    itemZ >= foundItems[0][0].length)
//                continue;
//
//            foundItems[itemX][itemY][itemZ] = itemInfo;
//        }
//        return foundItems;
//    }
//
//
//}