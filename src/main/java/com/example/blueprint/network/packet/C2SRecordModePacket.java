package com.example.blueprint.network.packet;

import com.example.blueprint.server.RecordMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 请求进入 / 退出"录制态"。
 * <p>
 * 为什么必须走一趟服务端：录制态要的是**能飞、能穿墙**，而这两样都在玩家的能力与实体状态上
 * （{@code abilities.mayfly / flying}、{@code noPhysics}），只有服务端改得动——
 * 客户端自己改，服务端下一拍就把能力同步回来，玩家会发现"飞了一下又掉下去"。
 */
public class C2SRecordModePacket {

    private final boolean enter;

    public C2SRecordModePacket(boolean enter) {
        this.enter = enter;
    }

    public static void encode(C2SRecordModePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enter);
    }

    public static C2SRecordModePacket decode(FriendlyByteBuf buf) {
        return new C2SRecordModePacket(buf.readBoolean());
    }

    public static void handle(C2SRecordModePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (msg.enter) {
                RecordMode.enter(player);
            } else {
                RecordMode.leave(player);
            }
        });
        context.setPacketHandled(true);
    }
}
