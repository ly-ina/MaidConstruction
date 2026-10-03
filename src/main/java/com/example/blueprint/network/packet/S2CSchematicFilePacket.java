package com.example.blueprint.network.packet;

import com.example.blueprint.client.BlueprintTransfer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端扫好的图纸数据，交给客户端落盘：{@code blueprints/<名字>.blueprint}。
 * <p>
 * 带的是**已经编好码的字节**（{@link com.example.blueprint.schematic.Schematic#encode}），
 * 不是结构对象：客户端要做的只有"原样写进文件"，中间不再解析一遍——
 * 解析放进这条路，只会多一次"解了又编"的往返，还要多一处格式实现。
 * <p>
 * 那边写完就在动作栏报一句（录制态没有界面），并由 {@code BlueprintLibrary.refresh()}
 * 让图纸库立刻看到这份新图纸，不必等那条五秒的自动重扫。
 */
public class S2CSchematicFilePacket {

    private final String name;
    private final byte[] data;

    public S2CSchematicFilePacket(String name, byte[] data) {
        this.name = name;
        this.data = data;
    }

    public static void encode(S2CSchematicFilePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 128);
        buf.writeByteArray(msg.data);
    }

    public static S2CSchematicFilePacket decode(FriendlyByteBuf buf) {
        return new S2CSchematicFilePacket(buf.readUtf(128), buf.readByteArray());
    }

    public static void handle(S2CSchematicFilePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // BlueprintTransfer 是客户端专属的（@OnlyIn(CLIENT)），用 DistExecutor 挡在专用服务端之外
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BlueprintTransfer.saveFromServer(msg.name, msg.data)));
        context.setPacketHandled(true);
    }
}
