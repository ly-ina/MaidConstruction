package com.example.blueprint.network.packet;

import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端缺结构数据时主动要一份。
 * <p>
 * 正常流程下服务端录完就会把数据推过来，但物品 NBT 同步慢一步、
 * 或者玩家中途换维度时缓存可能落空，这里给个自愈的机会。
 */
public class C2SRequestSchematicPacket {

    private final UUID id;

    public C2SRequestSchematicPacket(UUID id) {
        this.id = id;
    }

    public static void encode(C2SRequestSchematicPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.id);
    }

    public static C2SRequestSchematicPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestSchematicPacket(buf.readUUID());
    }

    public static void handle(C2SRequestSchematicPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Schematic schematic = SchematicStorage.get(player.serverLevel()).get(msg.id);
            if (schematic != null) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CSchematicDataPacket(msg.id, "", schematic.write(new CompoundTag())));
            }
        });
        context.setPacketHandled(true);
    }
}
