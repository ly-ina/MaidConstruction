package com.example.blueprint.network.packet;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 切换蓝图的投影朝向：旋转角度与翻面状态一起送。
 * <p>
 * 两者是同一件"换朝向"的两半（先翻面、后旋转）。分成两个包会有中间态——服务端收到旋转、
 * 翻面还在路上，那一瞬间它按旧翻面 + 新旋转去理解这张图，玩家看到的就是"投影抖了一下"。
 * 合成一个包，服务端要么是旧朝向、要么是新朝向，没有第三种。
 */
public class C2SSetOrientationPacket {

    private final Rotation rotation;
    private final Mirror mirror;

    public C2SSetOrientationPacket(Rotation rotation, Mirror mirror) {
        this.rotation = rotation;
        this.mirror = mirror;
    }

    public static void encode(C2SSetOrientationPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.rotation.ordinal());
        buf.writeInt(msg.mirror.ordinal());
    }

    public static C2SSetOrientationPacket decode(FriendlyByteBuf buf) {
        Rotation[] rotations = Rotation.values();
        Mirror[] mirrors = Mirror.values();
        int rotation = buf.readInt();
        int mirror = buf.readInt();
        return new C2SSetOrientationPacket(
                rotations[Math.floorMod(rotation, rotations.length)],
                mirrors[Math.floorMod(mirror, mirrors.length)]);
    }

    public static void handle(C2SSetOrientationPacket msg, Supplier<NetworkEvent.Context> ctx) {
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
            BlueprintItem.setMirror(stack, msg.mirror);
            // 换了朝向就是另一座建筑，之前的完工标记不算数
            BlueprintItem.setCompleted(stack, false);
        });
        context.setPacketHandled(true);
    }
}
