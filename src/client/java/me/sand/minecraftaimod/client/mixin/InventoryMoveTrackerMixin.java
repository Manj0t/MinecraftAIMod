package me.sand.minecraftaimod.client.mixin;

import me.sand.minecraftaimod.network.InventoryMovePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ScreenHandler.class)
public abstract class InventoryMoveTrackerMixin {
    @Unique
    private List<ItemStack> before;

    @Unique
    private boolean dropped = false;

    @Unique
    private int dropSlot = -1;

    @Unique
    private int dropAll = -1; // 0 = one, 1 = all

    @Unique
    private boolean threw = false;

    @Inject(
            method = "onSlotClick",
            at = @At("HEAD")
    )
    private void beforeClick(
            int slotIndex,
            int button,
            SlotActionType actionType,
            PlayerEntity player,
            CallbackInfo ci
    ) {


        ScreenHandler handler = (ScreenHandler) (Object) this;

        // Snapshot all slots BEFORE the action
        before = new ArrayList<>(handler.slots.size());
        for (Slot slot : handler.slots) {
            before.add(slot.getStack().copy());
        }
    }

    @Inject(
            method = "onSlotClick",
            at = @At("TAIL")
    )
    private void afterClick(
            int slotIndex,
            int button,
            SlotActionType actionType,
            PlayerEntity player,
            CallbackInfo ci
    ) {
        if (slotIndex < 0) return;
        System.out.println("[CLICK] slot=" + slotIndex + " button=" + button + " type=" + actionType);
        if (actionType == SlotActionType.THROW && slotIndex >= 0) {
            dropped = true;
            dropSlot = mapClientToServerSlot(slotIndex);
            dropAll = (button == 1) ? 1 : 0;
            String all = button == 1 ? "All" : "One";
//            System.out.println("Threw item in slot " + slotIndex);
            MinecraftClient client = MinecraftClient.getInstance();


            ClientPlayNetworking.send(
                    new InventoryMovePayload(-1, -1, 1, dropSlot, dropAll)
            );
            client.player.sendMessage(Text.literal(all + " slot " + dropSlot), false);// false = chat, true = action bar

            return;
        }

        ScreenHandler handler = (ScreenHandler) (Object) this;

        // Compare BEFORE vs AFTER
        int slot1 = -1;
        int slot2 = -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack beforeStack = before.get(i);
            ItemStack afterStack = handler.slots.get(i).getStack();

            if (!ItemStack.areEqual(beforeStack, afterStack)) {
                if (slot1 == -1) slot1 = i;
                else slot2 = i;
                System.out.println(
                        "[INV MOVE] slot " + i + ": " +
                                beforeStack + " -> " + afterStack
                );
            }
        }

        // Client side slot indexing vs server side are slightly different, fix here
        // Client side hotbar 36 - 44, Server side 0 - 8
        if(slot1 != -1) {
//            int droppedItem = dropped ? 1 : 0;
//            int droppedSlot = dropSlot;
//            int droppedAll = dropAll;

            slot1 = mapClientToServerSlot(slot1);
            slot2 = mapClientToServerSlot(slot2);
//            droppedSlot = mapClientToServerSlot(dropSlot);

            //Main inventory handled the same, no changes needed there
            ClientPlayNetworking.send(
                    new InventoryMovePayload(slot1, slot2, 0, -1, -1)
            );

            dropped = false;
            dropSlot = -1;
            dropAll = -1;
        }
        }

        @Unique
        private int mapClientToServerSlot(int clientSlot) {
            if (clientSlot == -1) return -1;

            // Hotbar: client 36-44 -> server 0-8
            if (36 <= clientSlot && clientSlot <= 44) {
                return clientSlot - 36;
            }

            // Armor slots: client 5-8 -> server 39,38,37,36 (helmet to boots)
            if (5 <= clientSlot && clientSlot <= 8) {
                return 39 - (clientSlot - 5);
            }

            // Off-hand: client 45 -> server 40
            if (clientSlot == 45) {
                return 40;
            }

            // Main inventory 9-35: same on both sides
            // Result slot 0: same on both sides
            return clientSlot;
        }
    }

