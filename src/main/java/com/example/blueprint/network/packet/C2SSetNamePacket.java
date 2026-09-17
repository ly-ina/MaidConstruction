package com.example.blueprint.network.packet;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 改蓝图的名字（面板左上角输入框的内容）。
 * <p>
 * 名字会存进物品 NBT，导出文件时也拿它当文件名。
 */
public class C2SSetNamePacket {

    private static final int MAX_LENGTH = 48;

    private final String name;

    public C2SSetNamePacket(String name) {
        this.name = name;
    }

    public static void encode(C2SSetNamePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, MAX_LENGTH);
    }

    public static C2SSetNamePacket decode(FriendlyByteBuf buf) {
        return new C2SSetNamePacket(buf.readUtf(MAX_LENGTH));
    }

    public static void handle(C2SSetNamePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = BlueprintItem.findHeld(player);
            if (!stack.isEmpty()) {
                BlueprintItem.setBlueprintName(stack, msg.name);
            }
        });
        context.setPacketHandled(true);
    }
}
