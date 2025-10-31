package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


public class Minecraftaimod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("collect-state-info");
    private boolean collecting = false;

    private ServerPlayerEntity agent = null;
    private String agentName = null;

    private PlayerInventory agentInventory = null;
    World world = null;



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

                                        return 1;
                                    })));

            dispatcher.register(literal("stop_training").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                server.getCommandManager().parseAndExecute(server.getCommandSource(), "/player " + agentName + " kill");

                agent = null;
                agentName = null;

                collecting = false;
                ctx.getSource().sendFeedback(() -> Text.literal("Stopped training!"), false);
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
                        System.out.println("found");
                        break;
                    }
                }
                if (agent == null) return;
            }
            // Agent Position Information
            double[] agentInfo = getAgentInfo();
            System.out.println(Arrays.toString(agentInfo));
            // Get agent inventory
            // Pass through transformer to encode values as a flattened vector
            double[][] inventoryArray = getInventory();
            System.out.println(Arrays.deepToString(inventoryArray));

            // Get nearby entities
            double[][] nearbyEntities = getNearbyEntities();
            System.out.println(Arrays.deepToString(nearbyEntities));

            // Get nearby Block information
            double[][] nearbyBlocks = getNearbyBlocks();
            System.out.println(Arrays.deepToString(nearbyBlocks));

        });
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

        double[] agentInfo = new double[]{
                health,
                hunger,
                saturation,
                agentPos[0],
                agentPos[1],
                agentPos[2],
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
     *         [item_id, count, durability, isArmor, isFood, isTool, isWeapon, utility1, utility2]
     *         Check getUtility function for posible utility values
     */
    private double[][] getInventory(){
        agentInventory = agent.getInventory();
        double[][] inventoryArray =  new double[41][9];

        for(int i = 0; i <= 40; i++) {
            ItemStack stack = agentInventory.getStack(i);
            Item item = stack.getItem();

            int item_id = Item.getRawId(item);
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
            inventoryArray[i][1] = count;
            inventoryArray[i][2] = durability;
            inventoryArray[i][3] = isArmor;
            inventoryArray[i][4] = isFood;
            inventoryArray[i][5] = isTool;
            inventoryArray[i][6] = isWeapon;
            inventoryArray[i][7] = utility_value[0];
            inventoryArray[i][8] = utility_value[1];;
        }

        return inventoryArray;
    }

    /**
     * Gets Entities within a given radius of the agent
     * @return double[][] where each row =
     *         [entity id, x, y, z, isMonster, isAngerable, isPassive, isUnknown]
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
            foundEntities[i][1] = entity.getX() - agent.getX();
            foundEntities[i][2] = entity.getY() - agent.getY();
            foundEntities[i][3] = entity.getZ() - agent.getZ();
            foundEntities[i][4] = isMonster;
            foundEntities[i][5] = isAngerable;
            foundEntities[i][6] = isPassive;
            foundEntities[i][7] = isUnknown;
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

                    foundBlocks[idx][0] = Block.STATE_IDS.getRawId(state);
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