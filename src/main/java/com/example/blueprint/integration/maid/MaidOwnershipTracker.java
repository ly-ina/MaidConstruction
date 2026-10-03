package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.server.MaidOwnership;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把"谁有哪些女仆"确定下来：**玩家进游戏时扫一遍**，此后世界里少了一只就划掉。
 * <p>
 * 为什么不挂在"实体进世界"上逐个记：那要在每次区块加载、每次她跨区块时都判断一遍，
 * 而真正的用处只有一处——指挥台界面要列出**我的**女仆。进游戏扫一次足够，
 * 而且扫出来的名单是稳的（不会因为区块卸载就从列表里消失）。
 * <p>
 * 这个类**只能由 {@code MaidExtension} 注册**（那段代码只在女仆模组存在时执行）：
 * 它的字节码里出现了 {@code EntityMaid} 的名字，没装女仆的客户端加载到它就会
 * NoClassDefFoundError（DEVELOPER §7.10 那条底线）。
 */
public final class MaidOwnershipTracker {

    private MaidOwnershipTracker() {
    }

    /** 进游戏：扫一遍这位玩家名下的女仆，重建她的名单 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        List<MaidOwnership.Maid> mine = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            // getOwner() 返回的是主人实体，仓库里判"是不是我的女仆"一直用它
            if (entity instanceof EntityMaid maid && maid.getOwner() == player) {
                mine.add(new MaidOwnership.Maid(maid.getUUID(), maid.getName().getString(), level.dimension()));
            }
        }
        MaidOwnership.replace(player.getUUID(), mine);
        BlueprintMod.LOGGER.info("女仆挂靠：{} 名下确认了 {} 只女仆", player.getGameProfile().getName(), mine.size());
    }

    /** 她没了（被打死、被收走）：从名单里划掉 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            MaidOwnership.forget(maid.getUUID());
        }
    }
}
