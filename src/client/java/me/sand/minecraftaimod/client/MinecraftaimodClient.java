package me.sand.minecraftaimod.client;

import me.sand.minecraftaimod.CraftOption;
import me.sand.minecraftaimod.network.CraftPagesPayload;
import me.sand.minecraftaimod.network.InteractionPayload;
import me.sand.minecraftaimod.network.InventoryMovePayload;
import me.sand.minecraftaimod.network.SelectRecipePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

public class MinecraftaimodClient implements ClientModInitializer {

    private static boolean sentThisTick = false;
    public static int currentPage = 0;

    public static KeyBinding[] SELECT_KEYS = new KeyBinding[5];

    private static boolean xWasDown = false;
    private static boolean zWasDown = false;
    private static final boolean[] recipeWasDown = new boolean[5];

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(
                CraftPagesPayload.ID,
                CraftPagesPayload.CODEC
        );

//        PayloadTypeRegistry.playC2S().register(
//                SelectRecipePayload.ID,
//                SelectRecipePayload.CODEC
//        );

//        NEXT_PAGE = KeyBindingHelper.registerKeyBinding(
//                new KeyBinding(
//                        "key.minecraftaimod.next_page",
//                        InputUtil.Type.KEYSYM,
//                        GLFW.GLFW_KEY_C,
//                        KeyBinding.Category.MISC
//                )
//        );
//
//        PAGE_PREV = KeyBindingHelper.registerKeyBinding(
//                new KeyBinding(
//                        "key.minecraftaimod.page_prev",
//                        InputUtil.Type.KEYSYM,
//                        GLFW.GLFW_KEY_X,
//                        KeyBinding.Category.MISC
//                )
//        );
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            Window window = client.getWindow();
            boolean altDown =
                    InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                            InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);

            if (!altDown) return;

            // 🔒 Swallow vanilla hotbar keys 1–5 ONLY
            for (int i = 0; i < 5; i++) {
                while (client.options.hotbarKeys[i].wasPressed()) {
                    // swallow
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            sentThisTick = false;

            Window window = client.getWindow();
            long handle = window.getHandle();
            boolean altDown =
                    InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                            InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);

            boolean xDown = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_C);
            boolean zDown = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_X);

            if (!altDown) return;

            // Alt + C → next page
            if (xDown && !xWasDown) {
                System.out.println("NEXT PAGE");
                ClientCraftUI.nextPage();
            }

            if (zDown && !zWasDown) {
                System.out.println("PREV PAGE");
                ClientCraftUI.prevPage();
            }

            // Alt + 1–5 → select recipe
            for (int i = 0; i < SELECT_KEYS.length; i++) {
                boolean down = SELECT_KEYS[i].isPressed();
                if (down && !recipeWasDown[i]) {
                    if(i >= ClientCraftUI.getCurrentPage().size()) continue; // Don't index out of range

                    String item = ClientCraftUI.getItemDisplayName(i);

                    if(item == null) continue;

                    client.player.sendMessage(
                            Text.literal("Selected recipe: " + item), false );// false = chat, true = action bar
                    int item_id = ClientCraftUI.getRecipeItemId(i);
                    ClientPlayNetworking.send(
                            new SelectRecipePayload(item_id)
                    );

                }

                KeyBinding hotbar = client.options.hotbarKeys[i];
                while (hotbar.wasPressed()) {
                    // swallow the press so vanilla never sees it
                }

                recipeWasDown[i] = down; // Prevent spam, more accurate data sent for training
            }

            xWasDown = xDown;
            zWasDown = zDown;
        });

        for (int i = 0; i < 5; i++) {
            int key = GLFW.GLFW_KEY_1 + i;
            SELECT_KEYS[i] = KeyBindingHelper.registerKeyBinding(
                    new KeyBinding(
                            "key.minecraftaimod.select_" + (i + 1),
                            InputUtil.Type.KEYSYM,
                            key,
                            KeyBinding.Category.MISC
                    )

            );
        }

        ClientPlayNetworking.registerGlobalReceiver(
                CraftPagesPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        ClientCraftUI.setPages(payload.pages());
                        ClientCraftUI.resetPage();
                    });
                }
        );



        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            sendOnce();
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            sendOnce();
            return ActionResult.PASS;
        });



        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            int y = 10;
            int x = 10;

            // Draw background
            drawContext.fill(x, y, x + 150, y + 100, 0x80000000);

            // Draw text
            drawContext.drawText(
                    client.textRenderer,
                    Text.literal("HUD ACTIVE"),  // Use Text.literal()
                    x + 5,
                    y + 5,
                    0xFFFFFFFF,
                    true
            );

            y += 15;

            if (ClientCraftUI.isEmpty()) {
                drawContext.drawText(
                        client.textRenderer,
                        Text.literal("No craftable recipes"),
                        x + 5,
                        y,
                        0xFFFFFFFF,
                        true
                );
            } else {
                int i = 1;
                drawContext.drawText(
                        client.textRenderer,
                        Text.literal(ClientCraftUI.getCurrentPageIndex() + " / " + ClientCraftUI.getTotalPages()),
                        x + 5,
                        y,
                        0xFFFFFFFF,
                        true
                );

                y += 10;
                for (CraftOption opt : ClientCraftUI.getCurrentPage()) {
                    drawContext.drawText(
                            client.textRenderer,
                            Text.literal(i + ": " + opt.displayName()),
                            x + 5,
                            y,
                            0xFFFFFFFF,
                            true
                    );
                    i += 1;
                    y += 10;
                }
            }
//            drawContext.getMatrices().popMatrix();
        });


    }

    private static void sendOnce() {
        if (sentThisTick) return;
        sentThisTick = true;

        ClientPlayNetworking.send(new InteractionPayload());
    }
    }
