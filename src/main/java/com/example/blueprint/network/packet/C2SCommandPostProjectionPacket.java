package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在图纸库里点「投影」之后，玩家在世界里摆好了位置与朝向，按 E 定下来：托管到某台指挥台上。
 * 也可以用来取消那台的投影。
 * <p>
 * <b>为什么带的是文件本身</b>：图纸库里的东西是**客户端硬盘上的文件**，服务端读不到；
 * 而"这台指挥台托着哪份结构"必须服务端说了算（存档、别人也得看得见）。
 * 所以这一步顺手把那份文件**上传成一份结构数据**（`SchematicStorage`，与"取到手上"同一条路），
 * 指挥台记的是它的 id——之后渲染与施工都按 id 去要，不必再关心文件在谁那儿。
 * <p>
 * 位置与朝向由玩家在世界里摆（与蓝图那颗锚点同一套手感），所以也一并带过来：
 * {@code anchor} 是结构的最小角，{@code rotation} / {@code mirror} 是朝向。
 */
public class C2SCommandPostProjectionPacket {

    /** 与服务端自己再确认一次的射程对齐：按**指挥台**算，玩家可能站在投影位置上 */
    private static final double MAX_DISTANCE_SQR = 64.0D;

    /** 结构数据一份最多这么大，与导入导出那条路同一个上限 */
    private static final int MAX_FILE_BYTES = 1_000_000;

    private final BlockPos pos;
    /** null = 取消投影 */
    private final String name;
    private final byte[] data;
    private final BlockPos anchor;
    private final Rotation rotation;
    private final Mirror mirror;

    private C2SCommandPostProjectionPacket(BlockPos pos, String name, byte[] data,
                                           BlockPos anchor, Rotation rotation, Mirror mirror) {
        this.pos = pos.immutable();
        this.name = name;
        this.data = data;
        this.anchor = anchor == null ? null : anchor.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
    }

    /** 放置：把这份图纸托管到这一台指挥台上，投影钉在 anchor 那一格 */
    public static C2SCommandPostProjectionPacket place(BlockPos pos, String name, byte[] data,
                                                       BlockPos anchor, Rotation rotation, Mirror mirror) {
        return new C2SCommandPostProjectionPacket(pos, name, data, anchor, rotation, mirror);
    }

    /** 取消：撤掉这一台的投影（不动已经指派的施工） */
    public static C2SCommandPostProjectionPacket cancel(BlockPos pos) {
        return new C2SCommandPostProjectionPacket(pos, null, null, null, Rotation.NONE, Mirror.NONE);
    }

    public static void encode(C2SCommandPostProjectionPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.name != null);
        if (msg.name != null) {
            buf.writeUtf(msg.name, 128);
            buf.writeByteArray(msg.data);
            buf.writeBlockPos(msg.anchor);
            buf.writeVarInt(msg.rotation.ordinal());
            buf.writeVarInt(msg.mirror.ordinal());
        }
    }

    public static C2SCommandPostProjectionPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        if (!buf.readBoolean()) {
            return cancel(pos);
        }
        String name = buf.readUtf(128);
        byte[] data = buf.readByteArray(MAX_FILE_BYTES);
        BlockPos anchor = buf.readBlockPos();
        Rotation rotation = Rotation.values()[Math.floorMod(buf.readVarInt(), Rotation.values().length)];
        Mirror mirror = Mirror.values()[Math.floorMod(buf.readVarInt(), Mirror.values().length)];
        return place(pos, name, data, anchor, rotation, mirror);
    }

    public static void handle(C2SCommandPostProjectionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (player.distanceToSqr(msg.pos.getCenter()) > MAX_DISTANCE_SQR) {
                return;
            }
            BlockEntity be = level.getBlockEntity(msg.pos);
            // 只有**绑定玩家**能放与取消：投影对所有人可见，动它得是主人（DEVELOPER §12）
            if (!(be instanceof CommandPostBlockEntity post) || !post.isBoundTo(player.getUUID())) {
                return;
            }

            if (msg.name == null) {
                post.clearProjection();
                return;
            }
            if (msg.anchor == null) {
                return;
            }

            try {
                Schematic schematic = Schematic.decode(msg.data);
                UUID id = SchematicStorage.get(level).put(schematic);
                post.placeProjection(id, msg.name, msg.anchor, msg.rotation, msg.mirror);
            } catch (Exception e) {
                BlueprintMod.LOGGER.warn("图纸库里的这份读不出来，投影没放成：{}（{}）", msg.name, e.toString());
            }
        });
        context.setPacketHandled(true);
    }
}
