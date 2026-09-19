package com.example.blueprint.network.packet;

import com.example.blueprint.integration.maid.MaidStudyPool;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 主人点名一样产物**用哪一条做法**。
 * <p>
 * 传的是下标而不是配方本身：池子里那条记录就是权威，界面显示的和服务端存的是同一份，
 * 没必要把配方再抄一遍传上来。主人正开着界面时不会同时在合成，所以"下标在传输途中错位"
 * 这种窗口极小；真错位了也只会选中另一条做法，改回来就是了。
 * <p>
 * <b>是"选中"不是"排到最前"</b>：列表顺序（学会的先后）不动，只改那个下标。
 * 早先那版每次选择都重排列表，顺序一直在动，主人反而记不住自己选的哪条。
 * <p>
 * 这个包只在"女仆模组存在"时才会真的发出来（界面本身就是它那条链上的），
 * 所以这里直接引用女仆的类型是安全的。
 */
public class C2SSelectStudyRecipePacket {

    private final int maidId;
    private final int productIndex;
    private final int recipeIndex;

    public C2SSelectStudyRecipePacket(int maidId, int productIndex, int recipeIndex) {
        this.maidId = maidId;
        this.productIndex = productIndex;
        this.recipeIndex = recipeIndex;
    }

    public static void encode(C2SSelectStudyRecipePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maidId);
        buf.writeInt(msg.productIndex);
        buf.writeInt(msg.recipeIndex);
    }

    public static C2SSelectStudyRecipePacket decode(FriendlyByteBuf buf) {
        return new C2SSelectStudyRecipePacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(C2SSelectStudyRecipePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(msg.maidId);
            // 只认自己的女仆：别人的池子轮不到你来选
            if (!(entity instanceof EntityMaid maid) || maid.getOwner() != player) {
                return;
            }
            // 改完不吭声：主人是在**界面里**点的这一下，而他开着界面时聊天栏是不画的，
            // 发出去也看不见。反馈由界面自己就地飘一句（见 MaidStudyScreen.toast）
            MaidStudyPool.select(maid, msg.productIndex, msg.recipeIndex);
        });
        context.setPacketHandled(true);
    }
}
