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
 * 按下/解除学习池里的"暂停键"：整样产物**或者**其中某一条配方。
 * <p>
 * 一个包管两级，靠 {@code recipeIndex} 区分（{@code < 0} 就是整样产物）：
 * 两者都是"主人对同一份清单按了一下暂停"，分成两个包只会多一份几乎一样的编解码样板。
 * 传的仍是**池子下标**（跟设优先级、下单、忘掉那几个包一个约定），服务端按下标
 * 取出产物再落到 {@link MaidStudyPool}，不把配方本身抄一遍传上来。
 * <p>
 * <b>只影响还没开始做的</b>：已经挂着的单照常做完，主人不必先去撤单——
 * 这正是"停用"跟"忘掉"的区别，见 {@link MaidStudyPool#setDisabled}。
 */
public class C2SToggleStudyUsePacket {

    private final int maidId;
    private final int productIndex;
    private final int recipeIndex;
    private final boolean disabled;

    public C2SToggleStudyUsePacket(int maidId, int productIndex, int recipeIndex, boolean disabled) {
        this.maidId = maidId;
        this.productIndex = productIndex;
        this.recipeIndex = recipeIndex;
        this.disabled = disabled;
    }

    public static void encode(C2SToggleStudyUsePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.productIndex);
        buf.writeInt(msg.recipeIndex);
        buf.writeBoolean(msg.disabled);
    }

    public static C2SToggleStudyUsePacket decode(FriendlyByteBuf buf) {
        return new C2SToggleStudyUsePacket(buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean());
    }

    public static void handle(C2SToggleStudyUsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            // 只认自己的女仆：别人的池子轮不到你来按暂停
            if (!(entity instanceof EntityMaid maid) || maid.getOwner() != player) {
                return;
            }
            List<MaidStudyPool.Learned> pool = MaidStudyPool.known(maid);
            if (msg.productIndex < 0 || msg.productIndex >= pool.size()) {
                return;
            }
            ItemStack product = pool.get(msg.productIndex).product();
            if (msg.recipeIndex < 0) {
                if (MaidStudyPool.setDisabled(maid, product, msg.disabled)) {
                    player.displayClientMessage(Component.translatable(msg.disabled
                                    ? "message.blueprint.study.product_off"
                                    : "message.blueprint.study.product_on",
                            product.getHoverName()), true);
                }
                return;
            }
            if (MaidStudyPool.setRecipeDisabled(maid, product, msg.recipeIndex, msg.disabled)) {
                player.displayClientMessage(Component.translatable(msg.disabled
                                ? "message.blueprint.study.recipe_off"
                                : "message.blueprint.study.recipe_on",
                        product.getHoverName(), msg.recipeIndex + 1), true);
            }
        });
        context.setPacketHandled(true);
    }
}
