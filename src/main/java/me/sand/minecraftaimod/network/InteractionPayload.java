package me.sand.minecraftaimod.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record InteractionPayload() implements CustomPayload {

    public static final CustomPayload.Id<InteractionPayload> ID =
            new CustomPayload.Id<>(
                    Identifier.of("minecraftaimod", "right_click")
            );

    public static final PacketCodec<PacketByteBuf, InteractionPayload> CODEC =
            PacketCodec.unit(new InteractionPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}

