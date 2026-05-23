package me.sand.minecraftaimod.agent;

import me.sand.minecraftaimod.network.CraftOption;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.recipe.*;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentCrafting {

    private final ServerPlayerEntity agent;
    private final World world;

    public AgentCrafting(ServerPlayerEntity agent, World world) {
        this.agent = agent;
        this.world = world;
    }

    public void craft(int itemId, boolean tableNearby) {
        Item targetItem = Registries.ITEM.get(itemId);
        ServerRecipeManager recipeManager = world.getServer().getRecipeManager();

        ContextParameterMap context = new ContextParameterMap.Builder()
                .add(SlotDisplayContexts.REGISTRIES, world.getServer().getRegistryManager())
                .build(SlotDisplayContexts.CONTEXT_TYPE);

        List<RecipeEntry<?>> matchingRecipes = new ArrayList<>();

        for (RecipeEntry<?> recipeEntry : recipeManager.values()) {
            Recipe<?> recipe = recipeEntry.value();
            for (RecipeDisplay display : recipe.getDisplays()) {
                if (slotDisplayContainsItem(display.result(), targetItem, context)) {
                    matchingRecipes.add(recipeEntry);
                    break;
                }
            }
        }

        for (RecipeEntry<?> recipeEntry : matchingRecipes) {
            if (canPlayerCraftRecipe(recipeEntry.value(), true, tableNearby)) {
                System.out.println("Can craft: " + recipeEntry.id());
                break;
            }
        }
    }

    // ========= Debug Functions, Only used in data collection =========

    public List<RecipeEntry<?>> getCraftableRecipes(boolean hasTable) {
        ServerRecipeManager recipeManager = world.getServer().getRecipeManager();
        List<RecipeEntry<?>> craftable = new ArrayList<>();

        for (RecipeEntry<?> entry : recipeManager.values()) {
            Recipe<?> recipe = entry.value();

            if (!(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) continue;
            if (requiresTable(recipe) && !hasTable) continue;
            if (canPlayerCraftRecipe(recipe, false, hasTable)) craftable.add(entry);
        }
        return craftable;
    }

    public List<List<CraftOption>> buildCraftPages(List<RecipeEntry<?>> recipes) {
        int perPage = 5;
        List<List<CraftOption>> pages = new ArrayList<>();
        List<CraftOption> flat = new ArrayList<>();

        ContextParameterMap context = new ContextParameterMap.Builder()
                .add(SlotDisplayContexts.REGISTRIES, world.getServer().getRegistryManager())
                .build(SlotDisplayContexts.CONTEXT_TYPE);

        for (RecipeEntry<?> entry : recipes) {
            Recipe<?> recipe = entry.value();

            Identifier itemId = null;
            for (RecipeDisplay display : recipe.getDisplays()) {
                ItemStack stack = display.result().getFirst(context);
                if (!stack.isEmpty()) {
                    itemId = Registries.ITEM.getId(stack.getItem());
                    break;
                }
            }
            if (itemId == null) continue;

            Item targetItem = Registries.ITEM.get(itemId);
            flat.add(new CraftOption(
                    Registries.ITEM.getRawId(targetItem),
                    targetItem.getName().getString()
            ));

            if (flat.size() == perPage) {
                pages.add(flat);
                flat = new ArrayList<>();
            }
        }
        if (!flat.isEmpty()) pages.add(flat);
        return pages;
    }

    // ========= Internal helpers =========

    private boolean canPlayerCraftRecipe(Recipe<?> recipe, boolean doCraft, boolean tableNearby) {
        IngredientPlacement placement = recipe.getIngredientPlacement();

        if(requiresTable(recipe)){
            if(!tableNearby) {
                System.out.println("Table needs items");
                return false;
            }
        }

        Map<Ingredient, Integer> itemMap = new HashMap<>();
        for (Ingredient ingredient : placement.getIngredients()) {
            itemMap.put(ingredient, itemMap.getOrDefault(ingredient, 0) + 1);
        }

        if (doCraft) debugCrafting(itemMap);

        Map<Integer, Integer> removeIfSuccessful = new HashMap<>();
        for (Map.Entry<Ingredient, Integer> entry : itemMap.entrySet()) {
            if (!playerContains(entry.getKey(), entry.getValue(), removeIfSuccessful)) return false;
        }

        if (doCraft) craftItem(recipe, removeIfSuccessful);
        return true;
    }

    private boolean requiresTable(Recipe<?> recipe){
        //Item placement is strict
        if(recipe instanceof ShapedRecipe shapedRecipe){
            return shapedRecipe.getWidth() > 2 || shapedRecipe.getHeight() > 2;
        }

        //Item placement does not matter
        if(recipe instanceof ShapelessRecipe shapelessRecipe){
            return shapelessRecipe.getIngredientPlacement().getIngredients().size() > 4;
        }

        // other recipes such as smelting, etc
        return false;
    }

    private boolean playerContains(Ingredient ingredient, int needed, Map<Integer, Integer> removeIfSuccessful) {
        PlayerInventory agentInventory = agent.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = agentInventory.getStack(i);
            if (stack.isEmpty() || !ingredient.test(stack)) continue;

            int take = Math.min(stack.getCount(), needed);
            if (take > 0) {
                removeIfSuccessful.put(i, take);
                needed -= take;
            }
            if (needed <= 0) return true;
        }

        // Ignore offhand item for now
        return false;
    }

    private void craftItem(Recipe<?> recipe, Map<Integer, Integer> removeIfSuccessful) {
        PlayerInventory agentInventory = agent.getInventory();

        for (Map.Entry<Integer, Integer> entry : removeIfSuccessful.entrySet()) {
            int slot = entry.getKey();
            int count = entry.getValue();

            ItemStack stack = agentInventory.getStack(slot);
            stack.decrement(count);
        }

        ItemStack result = recipe.craft(null, world.getServer().getRegistryManager());
        agent.getInventory().insertStack(result);
    }

    private boolean slotDisplayContainsItem(SlotDisplay slotDisplay, Item targetItem, ContextParameterMap context) {
        for (ItemStack stack : slotDisplay.getStacks(context)) {
            if (stack.getItem() == targetItem) return true;
        }
        return false;
    }

    private void debugCrafting(Map<Ingredient, Integer> itemMap) {
        System.out.println("Player needs items");
        for (Map.Entry<Ingredient, Integer> entry : itemMap.entrySet()) {
            System.out.println("Some Item -> " + entry.getValue());
        }
    }
}
