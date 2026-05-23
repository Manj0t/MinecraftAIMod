package me.sand.minecraftaimod.agent;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.*;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

public class AgentInventory {

    private final ServerPlayerEntity agent;
    private final World world;

    private double[][] prevInv = null;
    private boolean inventoryChanged = false;

    public AgentInventory(ServerPlayerEntity agent, World world) {
        this.agent = agent;
        this.world = world;
    }

    public boolean hasInventoryChanged() {
        return inventoryChanged;
    }

    public void clearInventoryChangedFlag() {
        inventoryChanged = false;
    }

    // ========= Swap / Drop =========

    public boolean swapItems(int slot1, int slot2) {
        if (slot1 > 41 || slot2 > 41 || slot1 < 0 || slot2 < 0) return false;
        if (slot1 == slot2) return false;
        PlayerInventory agentInventory = agent.getInventory();

        ItemStack stack1 = agentInventory.getStack(slot1);
        ItemStack stack2 = agentInventory.getStack(slot2);

        if (stack1.isEmpty() && stack2.isEmpty()) return false;

        boolean slot1IsEquipment = slot1 >= 36 && slot1 <= 40;
        boolean slot2IsEquipment = slot2 >= 36 && slot2 <= 40;

        if (slot1IsEquipment && !stack2.isEmpty() && !canEquipInSlot(stack2, slot1)) return false;
        if (slot2IsEquipment && !stack1.isEmpty() && !canEquipInSlot(stack1, slot2)) return false;

        agentInventory.setStack(slot1, stack2);
        agentInventory.setStack(slot2, stack1);
        return true;
    }

    public boolean dropItem(int slot, boolean all) {
        PlayerInventory agentInventory = agent.getInventory();
        ItemStack itemStack = agentInventory.getStack(slot);

        if (itemStack.isEmpty()) return false;

        ItemStack toDrop;
        if (all) {
            toDrop = itemStack.copy();
            agentInventory.setStack(slot, ItemStack.EMPTY);
        } else {
            toDrop = itemStack.split(1);
            agentInventory.setStack(slot, itemStack);
        }

        agent.dropItem(toDrop, false);
        return true;
    }

    public boolean swapContainerItems(int invSlotIdx, int containerSlotIdx, ScreenHandler handler, ContainerType type) {
        List<Slot> slots = handler.slots;
        int size = type.getContainerSize();
        if (containerSlotIdx < 0 || containerSlotIdx >= size) return false;

        Slot containerSlot = slots.get(containerSlotIdx);
        ItemStack containerStack = containerSlot.getStack();

        PlayerInventory agentInv = agent.getInventory();
        ItemStack invStack = agentInv.getStack(invSlotIdx);

        agentInv.insertStack(invSlotIdx, containerStack);
        containerSlot.insertStack(invStack);
        return true;
    }

    // ========= Equipment helpers =========

    private boolean canEquipInSlot(ItemStack stack, int slot) {
        var equippable = stack.getComponents().get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) return false;

        EquipmentSlot eqSlot = getEquipmentSlotFromInventorySlot(slot);
        if (eqSlot == null) return false;

        return eqSlot == equippable.slot();
    }

    private EquipmentSlot getEquipmentSlotFromInventorySlot(int slot) {
        return switch (slot) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            case 40 -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    // ========= Inventory observation =========

    /**
     * Gets the agent inventory.
     * 0–8   : Hotbar
     * 9–35  : Main inventory
     * 36–39 : Armor (boots → leggings → chestplate → helmet)
     * 40    : Off-hand
     *
     * @return double[][] where each row =
     *         [item_id, isArmor, isFood, isTool, isWeapon, isFuel, utility1, utility2, count, durability]
     */
    public double[][] getInventory() {
        PlayerInventory agentInventory = agent.getInventory();
        double[][] inventoryArray = new double[41][10];

        for (int i = 0; i <= 40; i++) {
            inventoryArray[i] = getItemInfo(agentInventory.getStack(i));
        }

        if (prevInv == null || !Arrays.deepEquals(inventoryArray, prevInv)) {
            prevInv = deepCopy(inventoryArray);
            inventoryChanged = true;
        }
        return inventoryArray;
    }

    // ========= Container observation =========

    public ContainerType getContainerType(ScreenHandler handler) {
        boolean containerOpen = handler != null && handler != agent.playerScreenHandler;
        if (!containerOpen) return ContainerType.NONE;

        if (handler instanceof GenericContainerScreenHandler gcsh) {
            int rows = gcsh.getRows();
            return rows == 6 ? ContainerType.DOUBLE_CHEST : ContainerType.CHEST;
        } else if (handler instanceof FurnaceScreenHandler) {
            return ContainerType.FURNACE;
        }

        return ContainerType.UNKNOWN;
    }

    public double[][] getContainer(ScreenHandler handler, ContainerType type) {
        if (type == ContainerType.NONE) return new double[54][10];
        List<Slot> slots = handler.slots;
        int size = type.getContainerSize();

        double[][] container = new double[54][];
        for (int i = 0; i < size; i++) {
            container[i] = getItemInfo(slots.get(i).getStack());
        }
        for (int i = size; i < 54; i++) {
            container[i] = new double[10];
        }
        return container;
    }

    public double[] getContainerMask(ContainerType type) {
        if (type == ContainerType.NONE) return new double[54];

        int size = type.getContainerSize();
        double[] mask = new double[54];
        for (int i = 0; i < size; i++) mask[i] = 1;
        return mask;
    }

    // ========= Item info =========

    public double[] getItemInfo(ItemStack stack) {
        Item item = stack.getItem();

        int itemId = Registries.ITEM.getRawId(item);
        double count = stack.getCount() / 64.0;

        int max = stack.getMaxDamage();
        double durability = max > 0 ? (double) (max - stack.getDamage()) / (double) max : 0.0;

        EquippableComponent equip = item.getComponents().get(DataComponentTypes.EQUIPPABLE);
        int isArmor = 0;
        if (equip != null) {
            isArmor = (equip.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) ? 1 : 0;
        }

        boolean toolBool = item.getComponents().contains(DataComponentTypes.TOOL);
        boolean foodBool = item.getComponents().contains(DataComponentTypes.FOOD);
        boolean weaponBool = item == Items.WOODEN_SWORD || item == Items.STONE_SWORD
                || item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD
                || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD;
        boolean rangedWeaponBool = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;
        boolean fuelBool = world.getFuelRegistry().isFuel(stack);

        int isTool = toolBool ? 1 : 0;
        int isFood = foodBool ? 1 : 0;
        int isWeapon = 0;
        int isFuel = fuelBool ? 1 : 0;

        if (weaponBool || rangedWeaponBool) {
            isWeapon = 1;
            isTool = 0;
        }

        int[] itemType = {isArmor, isFood, isTool, isWeapon, isFuel};
        double[] utility = getUtility(item, itemType);

        return new double[]{itemId, isArmor, isFood, isTool, isWeapon, isFuel, utility[0], utility[1], count, durability};
    }

    /**
     * Collects utility values for an item based on its type.
     * isArmor: [protection, toughness]
     * isFood:  [nutrition, saturation]
     * isTool:  [dmg per block, default mining speed]
     * isWeapon:[dmg, attack speed]
     */
    private double[] getUtility(Item item, int[] itemType) {
        double[] utility = {0, 0};

        if (itemType[0] == 1) {
            // Armor: [protection, toughness]
            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (modifiers == null) return utility;
            for (var modifier : modifiers.modifiers()) {
                if (modifier.attribute().equals(EntityAttributes.ARMOR)) utility[0] += modifier.modifier().value();
                if (modifier.attribute().equals(EntityAttributes.ARMOR_TOUGHNESS)) utility[1] += modifier.modifier().value();
            }
        } else if (itemType[1] == 1) {
            // Food: [nutrition, saturation]
            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);
            if (food == null) return utility;
            utility[0] = food.nutrition();
            utility[1] = food.saturation();
        } else if (itemType[2] == 1) {
            // Tool: [damage per block, mining speed]
            ToolComponent tool = item.getComponents().get(DataComponentTypes.TOOL);
            if (tool == null) return utility;
            utility[0] = tool.damagePerBlock();
            utility[1] = tool.defaultMiningSpeed();
        } else if (itemType[3] == 1) {
            // Weapon
            if (item instanceof BowItem) { utility[0] = 6.0; utility[1] = 0.80; return utility; }
            if (item instanceof CrossbowItem) { utility[0] = 6.0; utility[1] = 0.50; return utility; }

            AttributeModifiersComponent modifiers = item.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (modifiers == null) return utility;
            for (var modifier : modifiers.modifiers()) {
                if (modifier.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) utility[0] += modifier.modifier().value();
                if (modifier.attribute().equals(EntityAttributes.ATTACK_SPEED)) utility[1] += modifier.modifier().value();
            }
        }
        return utility;
    }

    // ========= Slot mapping =========

    public static int mapClientToServerSlot(int clientSlot) {
        if (clientSlot == -1) return -1;
        if (36 <= clientSlot && clientSlot <= 44) return clientSlot - 36;
        if (5 <= clientSlot && clientSlot <= 8) return 39 - (clientSlot - 5);
        if (clientSlot == 45) return 40;
        return clientSlot;
    }

    public static int mapClientToServerSlotForContHandling(int clientSlot) {
        if (clientSlot == -1) return -1;
        if (clientSlot <= 26) return clientSlot + 9;
        return clientSlot - 27;
    }

    // ========= Util =========

    private static double[][] deepCopy(double[][] src) {
        double[][] copy = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = Arrays.copyOf(src[i], src[i].length);
        }
        return copy;
    }
}
