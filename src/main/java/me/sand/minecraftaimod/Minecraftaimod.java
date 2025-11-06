package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.sand.minecraftaimod.protobuf.StateOuterClass;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
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

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


import me.sand.minecraftaimod.protobuf.State;
import me.sand.minecraftaimod.protobuf.Matrix;
import me.sand.minecraftaimod.protobuf.Row;
import me.sand.minecraftaimod.protobuf.Action;


public class Minecraftaimod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("collect-state-info");
    private volatile boolean collecting = false;


    private ServerPlayerEntity agent = null;
    private String agentName = null;

    private PlayerInventory agentInventory = null;
    World world = null;


    Socket socket = null;
    DataInputStream input = null;
    DataOutputStream output = null;

    private volatile boolean sendState = false;
    private volatile boolean sendReward = false;
    private volatile boolean recieveAction = false;
    private volatile boolean waitForNextRollout = false;

    private final Map<Integer, Runnable> movementActions = new HashMap<>();

    int currentHand = 0;
    private double speed = 0.20;

    Vec3d lastPos = null;

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
//                                            ProcessBuilder pb = new ProcessBuilder("python3", "python_socket.py");
                                            System.out.println("INFO: Made process");
//                                            pb.start();
//                                            System.out.println("INFO: Started Process");
//                                            Thread.sleep(1000);
//                                            System.out.println("INFO: FINISHED SLEEPING");

                                            socket = new Socket("localhost", 5000);
                                            socket.setTcpNoDelay(true);
                                            socket.setSoTimeout(0);

                                            input = new DataInputStream(socket.getInputStream());
                                            output = new DataOutputStream(socket.getOutputStream());
                                            System.out.println("INFO: Socket Connected");

                                            int agent_info_dim = 28;
                                            int numItems = Registries.ITEM.size();
                                            int numBlocks = Registries.BLOCK.size();
                                            int numEntities = Registries.ENTITY_TYPE.size();


                                            output.writeInt(agent_info_dim);
                                            output.writeInt(numItems);
                                            output.writeInt(numBlocks);
                                            output.writeInt(numEntities);
                                            output.flush();

                                            waitForNextRollout = true;
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
        });


        /*
         ************************************************
         **   Main State Information Collection Loop   **
         ************************************************
         */
        ServerTickEvents.END_SERVER_TICK.register(server -> {
                    if (!collecting) return; // only run if training started
                    if (agent == null && agentName != null) {
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            if (agentName.equals(player.getName().getString().toLowerCase())) {
                                agent = player;
                                world = agent.getEntityWorld();
                                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/gamemode survival " + agentName);
                                System.out.println("found");
                                break;
                            }
                        }
                        if (agent == null) return;
                    }
                    // Agent Position Information
                    if(waitForNextRollout) {
                        lastPos = null;
                        try {
                            System.out.println("INFO: Waiting for next rollout");
                            int startRollout = input.readInt();
                            System.out.println("startRollout: " + startRollout);
                            if(startRollout > 0) {
                                waitForNextRollout = false;
                                sendState = true;
                            }
                        }catch (IOException e) {
                            System.err.println("Error reading rollout command: " + e.getMessage());
                        }
                    }
                    if (sendState) {
                        sendStateInfo(server);
                        sendState = false;
                        recieveAction = true;
                        return;
                    }

//            Recieve action
                    if (recieveAction) {
                        try {
                            int respLen = input.readInt();
                            byte[] resp = input.readNBytes(respLen);

                            Action action = Action.parseFrom(resp);

                            List<Float> actions = action.getActionsList();
                            int actionLen = actions.size();

                            applyAction(actions);

                            System.out.println("LEN: " + actionLen);
                            System.out.println(Arrays.deepToString(actions.toArray()));

                            recieveAction = false;
                            sendReward = true;
                        } catch (Exception e) {

                        }
                    }

                    if (sendReward) {
                        try{
                            float reward = (float) getReward();
                            output.writeFloat(reward);
                            output.writeInt(0);
                            output.flush();

                            sendReward = false;


                            int continueRollout = input.readInt();

                            if(continueRollout == 1){
                                sendState = true;
                            } else{
                                waitForNextRollout = true;
                            }


                        }
                    catch(IOException e){
                        System.out.println("ERROR: FAILED");
                        server.getCommandManager().parseAndExecute(server.getCommandSource(), "/stop_training");
                    }
                }

        });

    }

    private double getReward() {
        // initialize at first step
        if (lastPos == null) {
            lastPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());
            return 0.0;
        }

        Vec3d currentPos = new Vec3d(agent.getX(), agent.getY(), agent.getZ());

        double distMoved = currentPos.distanceTo(lastPos);
        lastPos = currentPos;

        double reward = distMoved * 10.0;

        if (!agent.isOnGround()) {
            reward += 0.05;
        }

        // Penalize fall damage (teaches agent to avoid cliffs)
        if (agent.fallDistance > 2.5) {
            reward -= agent.fallDistance * 0.5;
        }

        // basic exploration reward
        return reward;   // encourage movement, penalize sitting
    }

    private void applyAction(List<Float> actions) {
        int movement = actions.get(0).intValue();
        int jump = actions.get(1).intValue();
        int item_use = actions.get(2).intValue();
        int hotbar_idx = actions.get(3).intValue();

        Runnable movementAction = movementActions.get(movement);
        if (movementAction != null) {
            movementAction.run();
        }

        if(jump > 0){
            jump();
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

    }

    private void moveForward(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.x * speed, 0, lookDir.z * speed);

        agent.addVelocity(movement);
    }

    private void moveBackward(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.x * speed, 0, lookDir.z * speed);

        agent.addVelocity(movement);
    }

    private void moveLeft(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(-lookDir.z * speed, 0, lookDir.x * speed);

        agent.addVelocity(movement);
    }

    private void moveRight(){
        Vec3d lookDir = agent.getRotationVec(1.0F);

        Vec3d movement = new Vec3d(lookDir.z * speed, 0, -lookDir.x * speed);

        agent.addVelocity(movement);
    }

    private void jump(){
        if (agent.isOnGround()) {
            agent.addVelocity(0, 0.42, 0);  // vanilla jump power
        }
    }

    private void updateHand(int jump){

    }

    private void sendStateInfo(MinecraftServer server){
        double[] agentInfo = getAgentInfo();
//            System.out.println(Arrays.toString(agentInfo));
        // Get agent inventory
        // Pass through transformer to encode values as a flattened vector
        double[][] inventoryArray = getInventory();
//            System.out.println(Arrays.deepToString(inventoryArray));

        // Get nearby entities
        double[][] nearbyEntities = getNearbyEntities();
//            System.out.println(Arrays.deepToString(nearbyEntities));

        // Get nearby Block information
        double[][] nearbyBlocks = getNearbyBlocks();
//            System.out.println(Arrays.deepToString(nearbyBlocks));
        System.out.println("Sending Info now");


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

        Matrix.Builder nearbyBlocksMatrix = Matrix.newBuilder();
        for(double[] row : nearbyBlocks) {
            Row.Builder rowBuilder = Row.newBuilder();
            for(double value : row) {
                rowBuilder.addValues(value);
            }
            nearbyBlocksMatrix.addRows(rowBuilder);
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


    private double[] getAgentInfo(){
        double health = agent.getHealth();
        double hunger = agent.getHungerManager().getFoodLevel();
        double saturation = agent.getHungerManager().getSaturationLevel();

        double[] agentPos = getPos();

        Vec3d vel = agent.getVelocity();
        double vx = vel.x;
        double vy = vel.y;
        double vz = vel.z;

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

        double time = (double) world.getTime();
        double lightLevel = world.getLightLevel(agent.getBlockPos());

        HitResult raycast = agent.raycast(6.0, 0.0F, false);

        double[] looking_at = {0, 0, 0};
        double looking_at_id = 0;

        switch(raycast.getType()) {
            case BLOCK:
                looking_at[0] = 1;

                BlockHitResult blockHit = (BlockHitResult) raycast;
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = agent.getEntityWorld().getBlockState(pos);

                looking_at_id = Block.STATE_IDS.getRawId(state);
                break;
            case ENTITY:
                looking_at[1] = 1;

                EntityHitResult entityHit = (EntityHitResult) raycast;
                Entity entity = entityHit.getEntity();

                looking_at_id = Registries.ENTITY_TYPE.getRawId(entity.getType());
                break;
            case MISS:
                looking_at[2] = 1;
                break;
        }

        double[] agentInfo = new double[]{
                health,
                hunger,
                saturation,
                agentPos[0],
                agentPos[1],
                agentPos[2],
                agentPos[3],
                agentPos[4],
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
                looking_at[0],
                looking_at[1],
                looking_at[2],
                looking_at_id,
        };

        return agentInfo;
    }
    private double[] getPos() {
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
    private double[] getUtility(Item item, int[] itemType) {
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
    private double[][] getInventory(){
        agentInventory = agent.getInventory();
        double[][] inventoryArray =  new double[41][9];

        for(int i = 0; i <= 40; i++) {
            ItemStack stack = agentInventory.getStack(i);
            Item item = stack.getItem();

            int item_id = Registries.ITEM.getRawId(item);
            int count = stack.getCount();
            int durability = stack.getMaxDamage() - stack.getDamage();

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
    private double[][] getNearbyEntities(){
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
    private double[][] getNearbyBlocks() {
        int radius = 5;
        int verticalRadius = 3; // Vertical space not as significant

        int rows = ( (radius * 2) + 1) * ( (verticalRadius * 2) + 1) * ( (radius * 2) + 1 );
        double[][] foundBlocks = new double[rows][4];

        int agentBlockX = agent.getBlockX();
        int agentBlockY = agent.getBlockY();
        int agentBlockZ = agent.getBlockZ();

        int idx = 0;
        for(int x = agent.getBlockX() - radius; x <= agentBlockX + radius; x++) {
            for (int y = agent.getBlockY() - verticalRadius; y <= agentBlockY + verticalRadius; y++) {
                for(int z = agent.getBlockZ() - radius; z <= agentBlockZ + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = agent.getEntityWorld().getBlockState(pos);

                    int relativeBlockX = x -  agentBlockX;
                    int relativeBlockY = y - agentBlockY;;
                    int relativeBlockZ = z - agentBlockZ;

                    foundBlocks[idx][0] = Registries.BLOCK.getRawId(state.getBlock());
                    foundBlocks[idx][1] = relativeBlockX;
                    foundBlocks[idx][2] = relativeBlockY;
                    foundBlocks[idx][3] = relativeBlockZ;

                    idx++;
                }
            }
        }
        return foundBlocks;
    }
}