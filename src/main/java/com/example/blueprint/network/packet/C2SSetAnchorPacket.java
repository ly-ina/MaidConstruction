package com.example.blueprint.network.packet;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 设置 / 清除蓝图的投影锚点。
 * 锚点是结构 (0,0,0) 在世界中的落点，女仆建造时也依赖它。
 */
public class C2SSetAnchorPacket {

    private final boolean clear;
    private final BlockPos anchor;

    public C2SSetAnchorPacket(BlockPos anchor) {
        this.clear = false;
        this.anchor = anchor.immutable();
    }

    public C2SSetAnchorPacket(boolean clear) {
        this.clear = clear;
        this.anchor = BlockPos.ZERO;
    }

    public static void encode(C2SSetAnchorPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.clear);
        buf.writeBlockPos(msg.anchor);
    }

    public static C2SSetAnchorPacket decode(FriendlyByteBuf buf) {
        boolean clear = buf.readBoolean();
        BlockPos pos = buf.readBlockPos();
        return clear ? new C2SSetAnchorPacket(true) : new C2SSetAnchorPacket(pos);
    }

    public static void handle(C2SSetAnchorPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = BlueprintItem.findHeld(player);
            if (stack.isEmpty()) {
                return;
            }
            if (msg.clear) {
                BlueprintItem.clearAnchor(stack);
            } else {
                BlueprintItem.setAnchor(stack, msg.anchor);
                // 重新定位等于换了个工地，完工状态作废，女仆会照着新位置重新施工
                BlueprintItem.setCompleted(stack, false);
            }
        });
        context.setPacketHandled(true);
    }
}
