package com.example.blueprint.network.packet;

import com.example.blueprint.client.MaidBuildProgress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 施工进度：某个女仆这一单建到哪了。
 * <p>
 * 进度是**服务端**才有的东西（{@code BuildSession} 在服务端推进），客户端要画进度条
 * 就只能靠这条包。发的人只发给她附近的人（见 {@code BlueprintBuildController}），
 * 收到的人按时效缓存（见 {@link MaidBuildProgress}）——所以女仆停工、走远、蓝图被收走，
 * 进度条都会自己消失，不需要谁再补发一条"清空"。
 * <p>
 * 三个字段都小得可以忽略，但仍然是**节流**发的：进度条半秒跳一下足够顺滑，
 * 每 tick 一条包纯属白烧带宽。
 */
public class S2CBuildProgressPacket {

    private final int maidId;
    private final int done;
    private final int total;

    public S2CBuildProgressPacket(int maidId, int done, int total) {
        this.maidId = maidId;
        this.done = done;
        this.total = total;
    }

    public static void encode(S2CBuildProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.done);
        buf.writeInt(msg.total);
    }

    public static S2CBuildProgressPacket decode(FriendlyByteBuf buf) {
        return new S2CBuildProgressPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(S2CBuildProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MaidBuildProgress.put(msg.maidId, msg.done, msg.total)));
        context.setPacketHandled(true);
    }
}
