package com.example.blueprint.network.packet;

import com.example.blueprint.integration.maid.MaidCraftOrder;
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
 * 在学习池界面里**下单**或者**撤单**。
 * <p>
 * 一个包干两件事，靠 {@code count} 区分（{@code <= 0} 就是撤掉全部）：
 * 两者都是"主人对这张清单的一次操作"，分成两个包只会多一份几乎一样的编解码样板。
 * <p>
 * 传的是产物**在池子里的下标**，跟设优先级那个包一个约定（见
 * {@link C2SSetStudyPriorityPacket}）；服务端拿到的是她当下那份池子，
 * 所以下标必须按池子算，不能按界面过滤后的顺序。
 */
public class C2SMaidCraftOrderPacket {

    private final int maidId;
    private final int productIndex;
    private final int count;

    public C2SMaidCraftOrderPacket(int maidId, int productIndex, int count) {
        this.maidId = maidId;
        this.productIndex = productIndex;
        this.count = count;
    }

    public static void encode(C2SMaidCraftOrderPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.productIndex);
        buf.writeInt(msg.count);
    }

    public static C2SMaidCraftOrderPacket decode(FriendlyByteBuf buf) {
        return new C2SMaidCraftOrderPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(C2SMaidCraftOrderPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            // 只认自己的女仆：别人的单轮不到你来下
            if (!(entity instanceof EntityMaid maid) || maid.getOwner() != player) {
                return;
            }

            if (msg.count <= 0) {
                MaidCraftOrder.clearAll(maid);
                player.displayClientMessage(
                        Component.translatable("message.blueprint.craft.cleared"), true);
                return;
            }

            List<MaidStudyPool.Learned> pool = MaidStudyPool.known(maid);
            if (msg.productIndex < 0 || msg.productIndex >= pool.size()) {
                return;
            }
            ItemStack product = pool.get(msg.productIndex).product();
            int ordered = MaidCraftOrder.order(maid, product, msg.count);
            player.displayClientMessage(ordered > 0
                            ? Component.translatable("message.blueprint.craft.ordered",
                                    product.getHoverName(), ordered, MaidCraftOrder.pendingAmount(maid))
                            : Component.translatable("message.blueprint.craft.order_full"),
                    true);
        });
        context.setPacketHandled(true);
    }
}
