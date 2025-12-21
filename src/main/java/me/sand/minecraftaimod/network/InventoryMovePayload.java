package me.sand.minecraftaimod.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record InventoryMovePayload(int fromSlot, int toSlot, int dropFlag, int dropSlot, int dropAll)
        implements CustomPayload {

    public static final CustomPayload.Id<InventoryMovePayload> ID =
            new CustomPayload.Id<>(
                    Identifier.of("minecraftaimod", "inventory_move")
            );

    public static final PacketCodec<PacketByteBuf, InventoryMovePayload> CODEC =
            PacketCodec.of(
                    // write
                    (payload, buf) -> {
                        buf.writeInt(payload.fromSlot());
                        buf.writeInt(payload.toSlot());
                        buf.writeInt(payload.dropFlag());
                        buf.writeInt(payload.dropSlot());
                        buf.writeInt(payload.dropAll());
                    },
                    // read
                    buf -> new InventoryMovePayload(
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readInt()
                    )
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
