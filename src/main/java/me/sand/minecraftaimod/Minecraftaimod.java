//package me.sand.minecraftaimod;
//
//import com.mojang.brigadier.arguments.StringArgumentType;
//import net.fabricmc.api.ModInitializer;
//import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//import net.minecraft.block.BlockState;
//import net.minecraft.entity.Entity;
//import net.minecraft.entity.LivingEntity;
//import net.minecraft.entity.mob.Angerable;
//import net.minecraft.entity.mob.Monster;
//import net.minecraft.entity.passive.PassiveEntity;
//import net.minecraft.entity.player.PlayerEntity;
//import net.minecraft.entity.player.PlayerInventory;
//import net.minecraft.item.ItemStack;
//import net.minecraft.server.MinecraftServer;
//import net.minecraft.server.network.ServerPlayerEntity;
//import net.minecraft.text.Text;
//import net.minecraft.util.math.BlockPos;
//import net.minecraft.util.math.Box;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//
//import static net.minecraft.server.command.CommandManager.argument;
//import static net.minecraft.server.command.CommandManager.literal;
//
//public class Minecraftaimod implements ModInitializer {
//
//    @Override
//    public void onInitialize() {
//    }
//}



package me.sand.minecraftaimod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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
            String[] inventoryArray = getInventory();
            System.out.println(Arrays.toString(inventoryArray));

            // Get nearby entities
            String[][] nearbyEntities = getNearbyEntities();
            System.out.println(Arrays.deepToString(nearbyEntities));

            // Get nearby Block information
            String[][] nearbyBlocks = getNearbyBlocks();
            System.out.println(Arrays.deepToString(nearbyBlocks));

        });
    }

    private double[] getPos() {
        return new double[]{ agent.getX(), agent.getY(), agent.getZ() };
    }

    private String[] getInventory(){
        agentInventory = agent.getInventory();
        String[] inventoryArray =  new String[agentInventory.size()];

        agentInventory = agent.getInventory();
        for(int i = 0; i < agentInventory.size(); i++) {
            ItemStack stack = agentInventory.getStack(i);

            if (stack.isEmpty()) inventoryArray[i] = null;
            else {
                inventoryArray[i] = stack.getCount() + " " + stack.getItem().getName();
            }
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