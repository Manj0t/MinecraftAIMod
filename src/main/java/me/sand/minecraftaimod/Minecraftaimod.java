package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEquipment;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.item.equipment.ArmorMaterial;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


public class Minecraftaimod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("collect-state-info");
    private boolean collecting = false;

    private ServerPlayerEntity agent = null;
    private String agentName = null;

    private PlayerInventory agentInventory = null;

    private double agentSearchRadius = 10.0;



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
                        System.out.println("found");
                        break;
                    }
                }
                if (agent == null) return;
            }

            // Agent Position Information
            double[] agentPos = getPos();
            System.out.println(agentName + " @ " + " X: " + agentPos[0] + " Y: " + agentPos[1] + " Z: " + agentPos[2]);

            // Get agent inventory
            // Pass through transformer to encode values as a flattened vector
            double[][] inventoryArray = getInventory();
            System.out.println(Arrays.deepToString(inventoryArray));

            // Get nearby entities
//            String[][] nearbyEntities = getNearbyEntities();
//            System.out.println(Arrays.deepToString(nearbyEntities));

            // Get nearby Block information
//            String[][] nearbyBlocks = getNearbyBlocks();
//            System.out.println(Arrays.deepToString(nearbyBlocks));

        });
    }

    private double[] getPos() {
        return new double[]{ agent.getX(), agent.getY(), agent.getZ() };
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


    private String[][] getNearbyEntities(){
        List<String[]> foundEntities = new ArrayList<>();

        Box box = agent.getBoundingBox().expand(agentSearchRadius);
        List<Entity> nearbyEntities = agent.getEntityWorld().getOtherEntities(agent, box);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof PlayerEntity || !(entity instanceof LivingEntity)) continue;

            String category;
            if (entity instanceof Monster) category = "hostile";
            else if (entity instanceof Angerable) category = "neutral";
            else if (entity instanceof PassiveEntity) category = "passive";
            else category = "unknown";

            String[] entityInfo = new String[]{
                    entity.getName().getString(),
                    entity.getType().toString(),
                    String.valueOf(entity.getX()),
                    String.valueOf(entity.getY()),
                    String.valueOf(entity.getZ()),
                    category
            };

            foundEntities.add(entityInfo);
        }

        return foundEntities.toArray(new String[0][]);
    }

    private String[][] getNearbyBlocks() {
        List<String[]> foundBlocks = new ArrayList<>();

        // incase I ever want to change it
        // Definetly need to change
        int radius = (int) agentSearchRadius;

        // Vertical space not as significant
        int verticalRadius = 3;

        for(int x = agent.getBlockX() - radius; x <= agent.getBlockX() + radius; x++) {
            for (int y = agent.getBlockY() - verticalRadius; y <= agent.getBlockY() + verticalRadius; y++) {
                for(int z = agent.getBlockZ() - radius; z <= agent.getBlockZ() + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = agent.getEntityWorld().getBlockState(pos);


                    String[] blockInfo = new String[]{
                            state.getBlock().toString(),
                            String.valueOf(x),
                            String.valueOf(y),
                            String.valueOf(z)
                    };

                    foundBlocks.add(blockInfo);
                }
            }
        }

        return foundBlocks.toArray(new String[0][]);
    }
}