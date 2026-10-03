package com.example.blueprint.network.packet;

import com.example.blueprint.client.MaidRoster;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端把"我的女仆"回给客户端：每只一份 {@link Entry}（id、名字、已指派到哪台指挥台）。
 * <p>
 * 名单与"指派状态"一起给，是因为指挥台界面要同时显示这两样，分两次发只会出现
 * "名单到了、状态还没到"的中间帧——那种一帧的错位玩家不会当成延迟，只会当成 bug。
 */
public class S2CMaidListPacket {

    /**
     * 她的工作状态。
     * <p>
     * 只有三种，而且**由服务端算**：名册里可能有别处的指挥台，那台的方块实体客户端根本
     * 没同步过来——让客户端照着本地那份猜，远处的女仆就会一直是错的。
     */
    public enum Status {
        /** 还没指派：她干自己的活 */
        FREE,
        /** 指派了，但那台还没下开工口令（或暂停/已完工/还没放投影）：她在待命 */
        STANDBY,
        /** 正在建 */
        BUILDING
    }

    /**
     * 一只女仆：{@code post} 为 null 表示还没被指派。
     * <p>
     * 只给 BlockPos 而不给整台指挥台：界面需要的只是"有没有指派、指到哪、她在干嘛"，点按钮时
     * 走的是"对我这台"或"最近一台属于我的"那两条路，都还要服务端再确认一次。
     */
    public record Entry(UUID id, String name, @Nullable BlockPos post, Status status) {
    }

    private final List<Entry> entries;

    public S2CMaidListPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public static void encode(S2CMaidListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.name(), 64);
            buf.writeBoolean(entry.post() != null);
            if (entry.post() != null) {
                buf.writeBlockPos(entry.post());
            }
            buf.writeEnum(entry.status());
        }
    }

    public static S2CMaidListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(64);
            BlockPos post = buf.readBoolean() ? buf.readBlockPos() : null;
            entries.add(new Entry(id, name, post, buf.readEnum(Status.class)));
        }
        return new S2CMaidListPacket(entries);
    }

    public static void handle(S2CMaidListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // MaidRoster 是客户端专属的，用 DistExecutor 挡在专用服务端之外
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MaidRoster.accept(msg.entries)));
        context.setPacketHandled(true);
    }
}
