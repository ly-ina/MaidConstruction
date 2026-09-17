package com.example.blueprint.network.packet;

import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 把结构数据下发到客户端，供投影渲染使用。
 */
public class S2CSchematicDataPacket {

    private final UUID id;
    private final CompoundTag data;

    public S2CSchematicDataPacket(UUID id, CompoundTag data) {
        this.id = id;
        this.data = data;
    }

    public static void encode(S2CSchematicDataPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.id);
        buf.writeNbt(msg.data);
    }

    public static S2CSchematicDataPacket decode(FriendlyByteBuf buf) {
        return new S2CSchematicDataPacket(buf.readUUID(), buf.readNbt());
    }

    public static void handle(S2CSchematicDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> ClientSchematicCache.put(msg.id, Schematic.read(msg.data)));
        context.setPacketHandled(true);
    }
}
