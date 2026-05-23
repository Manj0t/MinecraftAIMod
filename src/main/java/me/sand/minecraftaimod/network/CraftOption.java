package me.sand.minecraftaimod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

public record CraftOption(
        int item_id,
        String displayName
) {
    public static final PacketCodec<RegistryByteBuf, CraftOption> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, CraftOption::item_id,
                    PacketCodecs.STRING,  CraftOption::displayName,
                    CraftOption::new
            );
}
