package com.example.blueprint.network.packet;

import com.example.blueprint.integration.maid.MaidStudyPool;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 主人指定"这个产物用哪个配方"：把第 {@code recipeIndex} 个配方排到最前。
 * <p>
 * 传的是**下标**而不是配方本身：池子里那个顺序就是优先级，界面显示的和服务端存的
 * 是同一份数据，没必要把配方再抄一遍传上来。主人正开着界面时不会同时在合成，
 * 所以"下标在传输途中错位"这种窗口极小；真错位了也只会把别的配方挪到最前，
 * 改回来就是了。
 * <p>
 * 这个包只在"女仆模组存在"时才会真的发出来（界面本身就是它那条链上的），
 * 所以这里直接引用女仆的类型是安全的。
 */
public class C2SSetStudyPriorityPacket {

    private final int maidId;
    private final int productIndex;
    private final int recipeIndex;

    public C2SSetStudyPriorityPacket(int maidId, int productIndex, int recipeIndex) {
        this.maidId = maidId;
        this.productIndex = productIndex;
        this.recipeIndex = recipeIndex;
    }

    public static void encode(C2SSetStudyPriorityPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.productIndex);
        buf.writeInt(msg.recipeIndex);
    }

    public static C2SSetStudyPriorityPacket decode(FriendlyByteBuf buf) {
        return new C2SSetStudyPriorityPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(C2SSetStudyPriorityPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            // 只认自己的女仆：别人的池子轮不到你来排
            if (!(entity instanceof EntityMaid maid) || maid.getOwner() != player) {
                return;
            }
            if (MaidStudyPool.promote(maid, msg.productIndex, msg.recipeIndex)) {
                player.displayClientMessage(
                        Component.translatable("message.blueprint.study.priority_set"), true);
            }
        });
        context.setPacketHandled(true);
    }
}
