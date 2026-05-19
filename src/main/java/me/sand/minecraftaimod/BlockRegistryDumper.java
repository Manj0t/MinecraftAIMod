package me.sand.minecraftaimod;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class BlockRegistryDumper {

    public static void dump(String outputPath) {
        Map<String, Object> output = new HashMap<>();
        Map<String, Map<String, Integer>> blocks = new HashMap<>();

        for (Map.Entry<RegistryKey<Block>, Block> entry : Registries.BLOCK.getEntrySet()) {
            Identifier id = entry.getKey().getValue();
            Block block = entry.getValue();
            BlockState state = block.getDefaultState();

            int rawId = Registries.BLOCK.getRawId(block);

            Map<String, Integer> props = new HashMap<>();
            props.put("block_id", rawId);

            boolean solid = !state.getCollisionShape(
                    net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()
            ).isEmpty();
            props.put("is_solid", solid ? 1 : 0);

            boolean liquid = block instanceof FluidBlock;
            props.put("is_liquid", liquid ? 1 : 0);

            boolean dangerous = id.getPath().equals("lava")
                    || id.getPath().equals("fire") || id.getPath().equals("soul_fire")
                    || id.getPath().equals("cactus")
                    || id.getPath().contains("magma")
                    || id.getPath().equals("wither_rose")
                    || id.getPath().equals("sweet_berry_bush")
                    || id.getPath().equals("powder_snow")
                    || id.getPath().equals("campfire")
                    || id.getPath().equals("soul_campfire");
            props.put("is_dangerous", dangerous ? 1 : 0);

            boolean climbable = id.getPath().contains("ladder")
                    || id.getPath().contains("vine")
                    || id.getPath().contains("scaffolding");
            props.put("is_climbable", climbable ? 1 : 0);

            boolean passable = state.getCollisionShape(
                    net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()
            ).isEmpty() && state.getFluidState().isEmpty();
            props.put("is_passable", passable ? 1 : 0);

            boolean interactable = id.getPath().contains("door")
                    || id.getPath().contains("button")
                    || id.getPath().contains("lever")
                    || id.getPath().contains("chest")
                    || id.getPath().contains("crafting")
                    || id.getPath().contains("furnace")
                    || id.getPath().contains("anvil")
                    || id.getPath().contains("enchanting")
                    || id.getPath().contains("barrel")
                    || id.getPath().contains("bed");
            props.put("is_interactable", interactable ? 1 : 0);

            blocks.put(id.toString(), props);
        }

        output.put("num_blocks", Registries.BLOCK.size());
        output.put("blocks", blocks);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputPath)) {
            gson.toJson(output, writer);
            System.out.println("[BlockRegistryDumper] Wrote " + blocks.size() + " blocks to " + outputPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}