package com.example.blueprint.network.packet;

import com.example.blueprint.integration.maid.MaidStudyPool;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 在学习池界面里**忘掉**一样产物（连同她会做的所有配方）：界面上 Shift+左键点一下就发这个包。
 * <p>
 * 传的还是产物**在池子里的下标**（跟设优先级、下单那俩包一个约定）：服务端拿到她当下那份池子，
 * 按下标取出产物再去 {@link MaidStudyPool#forget}。下标必须按池子算，不能按界面过滤后的顺序，
 * 否则搜索之后删中的会是另一样东西。
 */
public class C2SForgetStudyPacket {

    private final int maidId;
    private final int productIndex;

    public C2SForgetStudyPacket(int maidId, int productIndex) {
        this.maidId = maidId;
        this.productIndex = productIndex;
    }

    public static void encode(C2SForgetStudyPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.productIndex);
    }

    public static C2SForgetStudyPacket decode(FriendlyByteBuf buf) {
        return new C2SForgetStudyPacket(buf.readInt(), buf.readInt());
    }

    public static void handle(C2SForgetStudyPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            // 只认自己的女仆：别人的池子轮不到你来删
            if (!(entity instanceof EntityMaid maid) || maid.getOwner() != player) {
                return;
            }
            List<MaidStudyPool.Learned> pool = MaidStudyPool.known(maid);
            if (msg.productIndex < 0 || msg.productIndex >= pool.size()) {
                return;
            }
            ItemStack product = pool.get(msg.productIndex).product();
            if (MaidStudyPool.forget(maid, product)) {
                player.displayClientMessage(
                        Component.translatable("message.blueprint.study.forgotten",
                                product.getHoverName()), true);
            }
        });
        context.setPacketHandled(true);
    }
}
