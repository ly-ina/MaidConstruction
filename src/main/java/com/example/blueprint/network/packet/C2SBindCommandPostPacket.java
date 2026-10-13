package com.example.blueprint.network.packet;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.server.CommandPostBindings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 拿蓝图终端右键指挥台：绑定 / 解绑。
 * <p>
 * 为什么绑定这件事走包而不是直接改：状态在**方块实体**上（存档的一部分），只有服务端那一份算数。
 * 客户端只负责把"哪一台、是不是潜行（潜行 = 解绑）"传过来。
 * <p>
 * 语义先定死（DEVELOPER §12）：一台指挥台只属于一个绑定玩家，可以解绑换人；
 * 没绑之前谁都能绑，绑上之后别人只能看，不能动它的投影与指派。
 */
public class C2SBindCommandPostPacket {

    /** 右键点到再远就不认：与服务端自己再确认一次的射程对齐 */
    private static final double MAX_DISTANCE_SQR = 64.0D;

    private final BlockPos pos;
    private final boolean unbind;

    public C2SBindCommandPostPacket(BlockPos pos, boolean unbind) {
        this.pos = pos.immutable();
        this.unbind = unbind;
    }

    public static void encode(C2SBindCommandPostPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.unbind);
    }

    public static C2SBindCommandPostPacket decode(FriendlyByteBuf buf) {
        return new C2SBindCommandPostPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(C2SBindCommandPostPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            // 射程自己再确认一次：客户端说"我点了它"不算数
            if (player.distanceToSqr(msg.pos.getCenter()) > MAX_DISTANCE_SQR) {
                return;
            }
            BlockEntity be = level.getBlockEntity(msg.pos);
            if (!(be instanceof CommandPostBlockEntity post)) {
                player.sendSystemMessage(Component.translatable("gui.maidconstruction.post.missing"));
                return;
            }

            GlobalPos here = GlobalPos.of(level.dimension(), msg.pos);

            if (msg.unbind) {
                if (post.isBoundTo(player.getUUID())) {
                    post.unbind();
                    CommandPostBindings.unbind(player);
                    CommandPostBindings.send(player);
                    player.sendSystemMessage(Component.translatable("message.maidconstruction.post.unbound"));
                } else {
                    player.sendSystemMessage(Component.translatable("message.maidconstruction.post.not_yours"));
                }
                return;
            }

            // 别人绑着的：不动。至于"自己的 owner 已经过期"那种，这一句不拦，下面按重新绑定走
            if (post.getOwner() != null && !post.isBoundTo(player.getUUID())) {
                player.sendSystemMessage(Component.translatable("message.maidconstruction.post.taken",
                        post.getOwnerName()));
                return;
            }
            // 已经是我现在绑的那一台：不必重绑，说一声就行
            if (post.isBoundTo(player.getUUID()) && CommandPostBindings.isCurrent(player, here)) {
                player.sendSystemMessage(Component.translatable("message.maidconstruction.post.already_yours"));
                return;
            }

            // 到这儿只有两种：这台还没主人，或者它那个 owner 是**过期的话**（换绑时它没加载、
            // 没来得及清）。两种都按"绑定这一台"处理——顺带把原来那台解绑，这就是"只能绑一台"。
            GlobalPos previous = CommandPostBindings.bind(player, here);
            if (!post.isBoundTo(player.getUUID())) {
                post.bind(player.getUUID(), player.getGameProfile().getName());
            }
            // 提示里要写"绑的是哪一台"（客户端画），所以绑定/换绑之后同步一份过去
            CommandPostBindings.send(player);
            if (previous == null) {
                player.sendSystemMessage(Component.translatable("message.maidconstruction.post.bound",
                        player.getGameProfile().getName()));
            } else {
                // 换绑把话说明白，免得他以为原来那台还挂着
                player.sendSystemMessage(Component.translatable("message.maidconstruction.post.switched",
                        player.getGameProfile().getName()));
                player.sendSystemMessage(Component.translatable("message.maidconstruction.post.auto_unbound"));
            }
        });
        context.setPacketHandled(true);
    }
}
