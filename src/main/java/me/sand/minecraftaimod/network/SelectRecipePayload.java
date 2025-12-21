package me.sand.minecraftaimod.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SelectRecipePayload(int recipeId) implements CustomPayload {

    public static final Id<SelectRecipePayload> ID =
            new CustomPayload.Id<>(
                    Identifier.of("minecraftaimod", "select_recipe")
            );

    public static final PacketCodec<PacketByteBuf, SelectRecipePayload> CODEC =
            PacketCodec.of(
                    // write
                    (payload, buf) -> {
                        buf.writeInt(payload.recipeId);

                    },
                    // read
                    buf -> new SelectRecipePayload(
                            buf.readInt()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
