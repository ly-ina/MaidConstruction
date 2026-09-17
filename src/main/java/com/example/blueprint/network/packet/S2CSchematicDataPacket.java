package com.example.blueprint.network.packet;

import com.example.blueprint.client.ClientBlueprintBinder;
import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 把结构数据下发到客户端，供投影和面板预览使用。
 * <p>
 * 顺带带上名字：客户端收到后会把 id 直接写进手上那张蓝图，
 * 不去等服务端同步物品 NBT——那条链路要走容器槽位广播，
 * 慢半拍的话面板就会一直显示旧结构，得关掉重开才对。
 */
public class S2CSchematicDataPacket {

    private final UUID id;
    private final String name;
    private final CompoundTag data;

    public S2CSchematicDataPacket(UUID id, String name, CompoundTag data) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.data = data;
    }

    public static void encode(S2CSchematicDataPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.id);
        buf.writeUtf(msg.name, 64);
        buf.writeNbt(msg.data);
    }

    public static S2CSchematicDataPacket decode(FriendlyByteBuf buf) {
        return new S2CSchematicDataPacket(buf.readUUID(), buf.readUtf(64), buf.readNbt());
    }

    public static void handle(S2CSchematicDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            Schematic schematic = Schematic.read(msg.data);
            ClientSchematicCache.put(msg.id, schematic);

            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientBlueprintBinder.bind(msg.id, msg.name, schematic.getSize()));
        });
        context.setPacketHandled(true);
    }
}
