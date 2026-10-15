package com.example.blueprint.network.packet;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.server.CommandPostBindings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「开始建造」这道口令（DEVELOPER §12 第 4 步）。
 * <p>
 * <b>为什么要有它</b>：放好投影、指派好女仆，都只是**准备**；准备本身不该等于开工。
 * 玩家常常要先把她叫齐、把料备好、把别的事交代完才动手，而"女仆自己就开踢"这种设计
 * 一旦遇上"我先放个投影看看位置对不对"，代价就是她当场跑去搬方块。所以默认不开工，
 * 等这一句口令。
 * <p>
 * 口令是**按指挥台**下的（不是按女仆）：同一台上的女仆一起开工、一起停，
 * 而不是一只一只点名——工地上"谁先谁后"没有意义，状态只有一套。
 * <p>
 * 校验与 {@code C2SCommandPostProjectionPacket} 同一套：射程内、且必须是**绑定这台的那个玩家**。
 */
public class C2SCommandPostStartPacket {

    /**
     * 这一版**没有射程限制**：目标由"他现在绑定的那一台"决定（见 {@code handle}），
     * 隔多远、隔几个维度都能下令——只要那台的区块加载着。
     */

    private final BlockPos pos;
    /** true = 下令开工；false = 让她们停下（口令收回，投影与指派都留着） */
    private final boolean start;

    public C2SCommandPostStartPacket(BlockPos pos, boolean start) {
        this.pos = pos;
        this.start = start;
    }

    public static void encode(C2SCommandPostStartPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.start);
    }

    public static C2SCommandPostStartPacket decode(FriendlyByteBuf buf) {
        return new C2SCommandPostStartPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(C2SCommandPostStartPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            // 与放投影同一套：目标是"他现在绑定的那一台"，不是包里的坐标（这样跨维度下令也成立）
            GlobalPos target = CommandPostBindings.current(player);
            if (target == null) {
                return;
            }
            ServerLevel level = player.server.getLevel(target.dimension());
            if (level == null || !(level.getBlockEntity(target.pos()) instanceof CommandPostBlockEntity post)) {
                return;
            }
            // 只有"他现在绑的那一台"能下令开工：换绑之后旧那台的 owner 已经过期
            if (!CommandPostBindings.canUse(player, player.serverLevel(), post)) {
                player.displayClientMessage(
                        Component.translatable("message.maidconstruction.post.not_yours"), true);
                return;
            }
            if (msg.start && post.getSchematicId() == null) {
                // 没有投影就没有"开工"的对象：说清楚，别让她白跑一趟
                player.displayClientMessage(
                        Component.translatable("message.maidconstruction.post.no_projection"), true);
                return;
            }

            post.setStarted(msg.start);
            player.displayClientMessage(Component.translatable(msg.start
                    ? "message.maidconstruction.post.started" : "message.maidconstruction.post.stopped"), true);
        });
        context.setPacketHandled(true);
    }
}
