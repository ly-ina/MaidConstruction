package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import com.example.blueprint.integration.maid.MaidCraftOrder;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 撤掉女仆队列里的**某一张**单（队列列表里那个 ✕）。
 * <p>
 * 和 {@link C2SMaidCraftOrderPacket} 的"撤掉全部"分开成两个包：那个包靠 {@code count <= 0}
 * 兼管撤单，再往里塞一个下标，读代码的人得同时记住两套语义。
 * <p>
 * 权限跟下单同一把尺子（{@code orders.owner_only}）：能让谁下单，就能让谁撤单——
 * 只让下单不让撤单反而更怪。
 */
public class C2SCancelCraftOrderPacket {

    private final int maidId;
    private final int index;

    public C2SCancelCraftOrderPacket(int maidId, int index) {
        this.maidId = maidId;
        this.index = index;
    }

    public static void encode(C2SCancelCraftOrderPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.index);
    }

    public static C2SCancelCraftOrderPacket decode(FriendlyByteBuf buf) {
        return new C2SCancelCraftOrderPacket(buf.readInt(), buf.readInt());
    }

    public static void handle(C2SCancelCraftOrderPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            if (!(entity instanceof EntityMaid maid)) {
                return;
            }
            if (BlueprintConfig.ownerOnlyOrders() && maid.getOwner() != player) {
                return;
            }
            if (MaidCraftOrder.removeAt(maid, msg.index)) {
                BlueprintMod.LOGGER.info("主人从女仆 {} 的队列里撤掉了第 {} 张单",
                        maid.getUUID(), msg.index + 1);
            }
        });
        context.setPacketHandled(true);
    }
}
