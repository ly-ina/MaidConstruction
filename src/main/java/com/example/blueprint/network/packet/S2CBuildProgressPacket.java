package com.example.blueprint.network.packet;

import com.example.blueprint.client.MaidBuildProgress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 施工进度：某个女仆这一单建到哪了、**此刻在干什么**。
 * <p>
 * 进度是**服务端**才有的东西（{@code BuildSession} 在服务端推进），客户端要画进度条
 * 就只能靠这条包。发的人只发给她附近的人（见 {@code BlueprintBuildController}），
 * 收到的人按时效缓存（见 {@link MaidBuildProgress}）——所以女仆停工、走远、蓝图被收走，
 * 进度条都会自己消失，不需要谁再补发一条"清空"。
 * <p>
 * {@code phase} 是个**枚举序号**而不是文案：文案得在客户端按玩家语言翻，
 * 服务端把中文/英文写死在包里就成了"切了语言也不变"。序号对应的翻译在
 * {@code hud.blueprint.maid_phase.*}。
 */
public class S2CBuildProgressPacket {

    /** 她在干什么：站着放方块 */
    public static final byte PHASE_BUILD = 0;
    /** 跑去取料（或就地取） */
    public static final byte PHASE_FETCH = 1;
    /** 走向施工站位 */
    public static final byte PHASE_WALK = 2;
    /** 缺料、正在等来源/等玩家（没有可取的来源，或者在冷却里） */
    public static final byte PHASE_STUCK = 3;

    private final int maidId;
    private final int done;
    private final int total;
    private final byte phase;

    public S2CBuildProgressPacket(int maidId, int done, int total, byte phase) {
        this.maidId = maidId;
        this.done = done;
        this.total = total;
        this.phase = phase;
    }

    public static void encode(S2CBuildProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.done);
        buf.writeInt(msg.total);
        buf.writeByte(msg.phase);
    }

    public static S2CBuildProgressPacket decode(FriendlyByteBuf buf) {
        return new S2CBuildProgressPacket(buf.readInt(), buf.readInt(), buf.readInt(), buf.readByte());
    }

    public static void handle(S2CBuildProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MaidBuildProgress.put(msg.maidId, msg.done, msg.total, msg.phase)));
        context.setPacketHandled(true);
    }
}
