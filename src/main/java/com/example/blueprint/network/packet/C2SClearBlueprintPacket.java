package com.example.blueprint.network.packet;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 把蓝图还原成空白状态，可以重新录制。
 * 没有需要传输的字段，用单例即可。
 */
public class C2SClearBlueprintPacket {

    public static final C2SClearBlueprintPacket INSTANCE = new C2SClearBlueprintPacket();

    private C2SClearBlueprintPacket() {
    }

    public static void encode(C2SClearBlueprintPacket msg, FriendlyByteBuf buf) {
        // 无字段
    }

    public static C2SClearBlueprintPacket decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }

    public static void handle(C2SClearBlueprintPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = BlueprintItem.findHeld(player);
            if (!stack.isEmpty()) {
                BlueprintItem.clearSchematic(stack);
            }
        });
        context.setPacketHandled(true);
    }
}
