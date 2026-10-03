package com.example.blueprint.network.packet;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.server.CommandPostAssignments;
import com.example.blueprint.server.MaidOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端要一次"我的女仆"名单（指挥台界面与蓝图终端都用它）。
 * <p>
 * 无字段的单例：要谁的名单纯粹由"是谁在问"决定，客户端不必也不该指定——
 * 它连"我有哪些女仆"都不知道，这正是它来问的原因。
 */
public class C2SMaidListRequestPacket {

    public static final C2SMaidListRequestPacket INSTANCE = new C2SMaidListRequestPacket();

    private C2SMaidListRequestPacket() {
    }

    public static void encode(C2SMaidListRequestPacket msg, FriendlyByteBuf buf) {
        // 无字段
    }

    public static C2SMaidListRequestPacket decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }

    public static void handle(C2SMaidListRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            List<S2CMaidListPacket.Entry> entries = new ArrayList<>();
            for (MaidOwnership.Maid maid : MaidOwnership.of(player.getUUID())) {
                BlockPos post = postOf(level, maid.id());
                entries.add(new S2CMaidListPacket.Entry(maid.id(), maid.name(), post,
                        statusOf(level, post)));
            }
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CMaidListPacket(entries));
        });
        context.setPacketHandled(true);
    }

    /**
     * 她指的是哪台指挥台（没指派、或那台在别的维度时返回 null）。
     * <p>
     * 跨维度按"没指派"算：界面显示的应是**她此刻会在哪儿施工**，而不是一张要去另一个世界旅行的名单。
     */
    @Nullable
    private static BlockPos postOf(ServerLevel level, java.util.UUID maid) {
        return CommandPostAssignments.postPosIn(level, maid);
    }

    /**
     * 她此刻的工作状态。
     * <p>
     * "正在建"的判据就是**指挥台那边那套**（放好投影 + 下过开工口令 + 没暂停没完工），
     * 与施工控制器开工的条件是同一组——界面写"正在建"而她不建、或反过来，都只会让人怀疑是 bug。
     * 其余一律算**待命**：她确实被指派了，只是还没开工（或已经完工）。
     */
    private static S2CMaidListPacket.Status statusOf(ServerLevel level, @Nullable BlockPos postPos) {
        if (postPos == null) {
            return S2CMaidListPacket.Status.FREE;
        }
        if (!(level.getBlockEntity(postPos) instanceof CommandPostBlockEntity post)
                || post.getSchematicId() == null
                || !post.isStarted() || post.isPaused() || post.isCompleted()) {
            return S2CMaidListPacket.Status.STANDBY;
        }
        return S2CMaidListPacket.Status.BUILDING;
    }
}
