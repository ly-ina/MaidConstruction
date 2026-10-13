package com.example.blueprint.network.packet;

import com.example.blueprint.client.ClientPostBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * "我现在绑的是哪一台指挥台"——服务端告诉客户端，客户端拿去写终端物品的提示。
 * <p>
 * <b>为什么要走一趟网络</b>：绑定存在**服务端的玩家持久数据**里（换绑时旧那台常常在未加载区块，
 * 只有按玩家记一份才解得开，见 {@code CommandPostBindings}），而提示是客户端画的。
 * 客户端自己那张表（{@code CommandPostProjections}）只记**加载过的**那几台，走远了就查不到——
 * 可"我绑的那台到底在哪"恰恰是走远了才想看的东西。
 * <p>
 * 推的时机：登录时一次（重登、换世界都靠它），以及每次绑定 / 换绑 / 解绑之后。
 */
public class S2CPostBindingPacket {

    /** 那一台在哪个维度（形如 {@code minecraft:overworld}）；null = 没绑 */
    @Nullable
    private final String dimension;
    /** 那一格；null = 没绑 */
    @Nullable
    private final BlockPos pos;

    public S2CPostBindingPacket(@Nullable String dimension, @Nullable BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos;
    }

    /** 没绑：客户端把记录清掉 */
    public static S2CPostBindingPacket none() {
        return new S2CPostBindingPacket(null, null);
    }

    public static void encode(S2CPostBindingPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.dimension != null && msg.pos != null);
        if (msg.dimension != null && msg.pos != null) {
            buf.writeUtf(msg.dimension, 128);
            buf.writeBlockPos(msg.pos);
        }
    }

    public static S2CPostBindingPacket decode(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return none();
        }
        return new S2CPostBindingPacket(buf.readUtf(128), buf.readBlockPos());
    }

    public static void handle(S2CPostBindingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // ClientPostBinding 是客户端专属的（@OnlyIn(CLIENT)），用 DistExecutor 挡在专用服务端之外
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPostBinding.accept(msg.dimension, msg.pos)));
        context.setPacketHandled(true);
    }
}
