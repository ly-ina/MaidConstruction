package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 框选完成后，请服务端扫描世界，扫出来的结构**直接变成一张图纸文件**。
 * <p>
 * 与 {@link C2SCapturePacket} 的分工：那条路把结构写进**手上的蓝图**（手持蓝图框选用它），
 * 这条路只交出一份数据，落到 {@code blueprints} 目录里由玩家自己去拿。
 * 蓝图在这条路上只是"以后把某一份取到手上"的容器，录制本身不经过它。
 * <p>
 * 所以这里是三步两折：扫描必须在服务端做（客户端读到的方块实体 NBT 往往不完整），
 * 而文件只能写在玩家自己那台机器上——服务端扫完把字节发回来，客户端落盘。
 */
public class C2SCaptureToFilePacket {

    private final BlockPos pos1;
    private final BlockPos pos2;
    private final String name;

    public C2SCaptureToFilePacket(BlockPos pos1, BlockPos pos2, String name) {
        this.pos1 = pos1.immutable();
        this.pos2 = pos2.immutable();
        this.name = name;
    }

    public static void encode(C2SCaptureToFilePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos1);
        buf.writeBlockPos(msg.pos2);
        buf.writeUtf(msg.name, 128);
    }

    public static C2SCaptureToFilePacket decode(FriendlyByteBuf buf) {
        return new C2SCaptureToFilePacket(buf.readBlockPos(), buf.readBlockPos(), buf.readUtf(128));
    }

    public static void handle(C2SCaptureToFilePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            try {
                Schematic schematic = Schematic.capture(player.serverLevel(), msg.pos1, msg.pos2);
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CSchematicFilePacket(msg.name, Schematic.encode(schematic)));

                player.sendSystemMessage(Component.translatable("message.blueprint.captured",
                        schematic.getWidth(), schematic.getHeight(), schematic.getLength(),
                        schematic.countBlocks()));
            } catch (IllegalStateException e) {
                // 这份是"框选区域太大了"那类：报错文案本身就是语言键（见 Schematic.capture）
                player.sendSystemMessage(Component.translatable(e.getMessage() == null
                        ? "message.blueprint.capture_failed" : e.getMessage()));
            } catch (Exception e) {
                BlueprintMod.LOGGER.error("蓝图扫描失败", e);
                player.sendSystemMessage(Component.translatable("message.blueprint.capture_failed"));
            }
        });
        context.setPacketHandled(true);
    }
}
