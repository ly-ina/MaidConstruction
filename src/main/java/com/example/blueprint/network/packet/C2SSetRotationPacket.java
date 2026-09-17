package com.example.blueprint.network.packet;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 切换蓝图的投影朝向。
 */
public class C2SSetRotationPacket {

    private final Rotation rotation;

    public C2SSetRotationPacket(Rotation rotation) {
        this.rotation = rotation;
    }

    public static void encode(C2SSetRotationPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.rotation.ordinal());
    }

    public static C2SSetRotationPacket decode(FriendlyByteBuf buf) {
        Rotation[] values = Rotation.values();
        int ordinal = buf.readInt();
        return new C2SSetRotationPacket(values[Math.floorMod(ordinal, values.length)]);
    }

    public static void handle(C2SSetRotationPacket msg, Supplier<NetworkEvent.Context> ctx) {
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
            BlueprintItem.setRotation(stack, msg.rotation);
            // 换了朝向就是另一座建筑，之前的完工标记不算数
            BlueprintItem.setCompleted(stack, false);
        });
        context.setPacketHandled(true);
    }
}
