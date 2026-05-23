package me.sand.minecraftaimod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.ArrayList;

public record CraftPagesPayload(
        List<List<CraftOption>> pages
) implements CustomPayload {

    public static final Id<CraftPagesPayload> ID =
            new Id<>(Identifier.of("minecraftaimod", "craft_pages"));

    public static final PacketCodec<RegistryByteBuf, CraftPagesPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.collection(
                            ArrayList::new,
                            PacketCodecs.collection(
                                    ArrayList::new,
                                    CraftOption.CODEC
                            )
                    ),
                    CraftPagesPayload::pages,
                    CraftPagesPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}



