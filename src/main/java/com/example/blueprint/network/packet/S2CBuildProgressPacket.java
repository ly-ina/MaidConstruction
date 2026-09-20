package com.example.blueprint.network.packet;

import com.example.blueprint.client.MaidBuildProgress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 施工进度：某个女仆这一单建到哪了、**此刻在干什么**。
 * <p>
 * 进度是**服务端**才有的东西（{@code BuildSession} 在服务端推进），客户端要画进度条
 * 就只能靠这条包。发的人只发给她附近的人（见 {@code BlueprintBuildController}），
 * 收到的人按时效缓存（见 {@link MaidBuildProgress}）。
 * <p>
 * <b>同时带实体 id 和 UUID</b>，两个各有各的用处：
 * <ul>
 *   <li>实体 id 用来在客户端 {@code level.getEntity(id)} 找到她本人（画名字、算距离）；</li>
 *   <li>UUID 用来**认人**——实体 id 会被游戏复用（区块卸载重载、女仆移除再放出来都会换 id，
 *       旧 id 还可能被别的实体拿走），只按 id 认的话，客户端会留下一条**过期的进度**
 *       赖在屏幕上，看着就是"两条进度条来回覆盖、数字突然变出来又变回去"。</li>
 * </ul>
 * {@code phase} 是**枚举序号**而不是文案：文案得在客户端按玩家语言翻。
 */
public class S2CBuildProgressPacket {

    /** 她在干什么：站着放方块 */
    public static final byte PHASE_BUILD = 0;
    /** 跑去取料（或就地取） */
    public static final byte PHASE_FETCH = 1;
    /** 走向施工站位 */
    public static final byte PHASE_WALK = 2;
    /** 缺料、正在等来源/等玩家 */
    public static final byte PHASE_STUCK = 3;

    private final int maidId;
    private final UUID maidUuid;
    private final int done;
    private final int total;
    private final byte phase;

    public S2CBuildProgressPacket(int maidId, UUID maidUuid, int done, int total, byte phase) {
        this.maidId = maidId;
        this.maidUuid = maidUuid;
        this.done = done;
        this.total = total;
        this.phase = phase;
    }

    public static void encode(S2CBuildProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeUUID(msg.maidUuid);
        buf.writeInt(msg.done);
        buf.writeInt(msg.total);
        buf.writeByte(msg.phase);
    }

    public static S2CBuildProgressPacket decode(FriendlyByteBuf buf) {
        return new S2CBuildProgressPacket(buf.readInt(), buf.readUUID(),
                buf.readInt(), buf.readInt(), buf.readByte());
    }

    public static void handle(S2CBuildProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MaidBuildProgress.put(msg.maidId, msg.maidUuid, msg.done, msg.total, msg.phase)));
        context.setPacketHandled(true);
    }
}
