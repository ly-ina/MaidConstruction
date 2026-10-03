package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.server.MaidOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在指挥台界面里点某只女仆的名字：指派 / 撤销。
 * <p>
 * 语义先定死（DEVELOPER §12）：一只女仆同一时间只效力一台指挥台——"她该建哪处工地"必须唯一。
 * 所以指派时**先把她从别处撤下来**，不让她同时挂着两台。
 * <p>
 * 校验都在服务端做，而且都对着**真实存在的实体**看：点名的女仆得真在这个世界、还得离指挥台不远——
 * 客户端给的只是个 UUID，不能拿它当"她确实在那儿"的证据。
 */
public class C2SAssignMaidPacket {

    /** 指挥台周围这个范围内才认（比射程宽一点：女仆常常站在工地外圈） */
    private static final double MAX_MAID_DISTANCE_SQR = 64.0D * 64.0D;

    /** 超过这个距离就算"远"，指派时直接把她挪过来（16 格：正常走过去也就几步，更远就别让她跑了） */
    private static final double TELEPORT_THRESHOLD_SQR = 16.0D * 16.0D;

    private final BlockPos pos;
    private final UUID maid;
    private final boolean assign;

    public C2SAssignMaidPacket(BlockPos pos, UUID maid, boolean assign) {
        this.pos = pos.immutable();
        this.maid = maid;
        this.assign = assign;
    }

    public static void encode(C2SAssignMaidPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUUID(msg.maid);
        buf.writeBoolean(msg.assign);
    }

    public static C2SAssignMaidPacket decode(FriendlyByteBuf buf) {
        return new C2SAssignMaidPacket(buf.readBlockPos(), buf.readUUID(), buf.readBoolean());
    }

    public static void handle(C2SAssignMaidPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity be = level.getBlockEntity(msg.pos);
            if (!(be instanceof CommandPostBlockEntity post) || !post.isBoundTo(player.getUUID())) {
                return;
            }

            // 只能指派**自己的**女仆：UUID 是客户端给的，先用那张"主人 → 女仆"的表认一遍
            if (!player.getUUID().equals(MaidOwnership.ownerOf(msg.maid))) {
                return;
            }

            Entity entity = level.getEntity(msg.maid);
            if (entity == null) {
                // 她所在的区块没加载：先把"她归这台"记下，等她加载后自己开工
                if (msg.assign) {
                    post.assign(msg.maid, MaidOwnership.nameOf(msg.maid));
                } else {
                    post.unassign(msg.maid);
                }
                return;
            }
            // 拉一把：离得远的直接挪到指挥台旁边，省得她穿过半个地图走过来。
            // 落在指挥台**上方一格**：那里通常就是空的，她站定之后站位那套逻辑自己会接管
            if (msg.assign && entity.distanceToSqr(msg.pos.getCenter()) > TELEPORT_THRESHOLD_SQR) {
                BlockPos above = msg.pos.above();
                entity.teleportTo(above.getX() + 0.5D, above.getY(), above.getZ() + 0.5D);
                BlueprintMod.LOGGER.info("女仆 {} 被指派到 {}：离得太远，直接挪到指挥台旁边",
                        entity.getName().getString(), msg.pos);
            }

            if (msg.assign) {
                post.assign(msg.maid, entity.getName().getString());
            } else {
                post.unassign(msg.maid);
            }
        });
        context.setPacketHandled(true);
    }
}
