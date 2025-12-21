//package me.sand.minecraftaimod.mixin;
//
//import net.minecraft.entity.player.PlayerEntity;
//import net.minecraft.screen.ScreenHandler;
//import net.minecraft.screen.slot.SlotActionType;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//
//@Mixin(ScreenHandler.class)
//public abstract class ServerInventoryDropMixin {
//
//    @Inject(
//            method = "onSlotClick",
//            at = @At("HEAD")
//    )
//    private void onSlotClick(
//            int slotId,
//            int button,
//            SlotActionType actionType,
//            PlayerEntity player,
//            CallbackInfo ci
//    ) {
//        if (player.getEntityWorld().isClient()) return;
//
//        if (actionType == SlotActionType.THROW && slotId >= 0) {
//            boolean dropAll = button == 1;
//
//            System.out.println(
//                    "[SERVER DROP] player=" + player.getName().getString()
//                            + " slot=" + slotId
//                            + " dropAll=" + dropAll
//            );
//
//            // PERFECT RL signal here
//        }
//    }
//}
//
