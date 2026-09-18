package com.example.blueprint.integration.maid;

import com.example.blueprint.client.MaidStudyScreenOpener;
import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;

/**
 * 打开学习池界面的入口：**蹲下 + 空手右键自己的女仆**。
 * <p>
 * 为什么走 {@link InteractMaidEvent}：女仆那边的处理顺序是
 * "先 post 这个事件 → 手里物品的 {@code interactLivingEntity} → 打开它自己的女仆界面"，
 * 而 post 的返回值就是"**要不要直接当成 SUCCESS 结束**"。也就是说：
 * <b>我们取消掉这个事件，TLM 就不会再打开它自己的界面</b>——这是它留出来的口子，
 * 它自己也拿它做背包之类的交互。
 * <p>
 * 条件是"蹲下 + 空手"两条同时满足，理由是女仆界面是主人最常用的东西：
 * 普通右键、拿东西右键都照旧归 TLM，只有这个明确没人用的组合归我们。
 * <p>
 * 界面在客户端开（右键本来就两端都会走一遍预测），服务端不需要额外发"打开"的包；
 * 池子本身由 TLM 的 {@code TASK_DATA_SYNC} 同步，界面直接读她身上的数据。
 */
public class MaidStudyInteractHandler {

    @SubscribeEvent
    public static void onInteractMaid(InteractMaidEvent event) {
        Player player = event.getPlayer();
        EntityMaid maid = event.getMaid();
        if (!player.isShiftKeyDown() || !event.getStack().isEmpty()) {
            return;
        }
        if (maid.getOwner() != player) {
            return;
        }
        // 取消 = 女仆那边直接 SUCCESS 收场：不开它自己的界面，也不动手里那件东西。
        // 这一步两端都要做：服务端不取消，TLM 那边照样开它自己的界面。
        event.setCanceled(true);
        // 开界面只认**逻辑客户端**，而且必须在这一份上开。
        // 单人游戏里这个事件要走两遍：客户端线程一遍、集成服务端线程一遍。
        // DistExecutor 只认**物理端**（单人游戏的物理端就是 CLIENT），认不出线程，
        // 于是服务端线程那一份也会跑来开界面——Minecraft.getInstance().setScreen()
        // 撞上 RenderSystem 的线程断言，报 "Rendersystem called from wrong thread"，直接闪退。
        // （专用服务端物理端是 DEDICATED_SERVER，压根不执行，所以这坑只在单人游戏里炸。）
        if (!player.level().isClientSide()) {
            return;
        }
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MaidStudyScreenOpener.open(maid.getId()));
    }

    private MaidStudyInteractHandler() {
    }
}
