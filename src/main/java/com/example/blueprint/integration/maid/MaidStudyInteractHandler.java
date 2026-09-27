package com.example.blueprint.integration.maid;

import com.example.blueprint.client.MaidStudyScreenOpener;
import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;

/**
 * 打开学习池界面的入口：**蹲下 + 拿着木棍右键自己的女仆**。
 * <p>
 * 为什么走 {@link InteractMaidEvent}：女仆那边的处理顺序是
 * "先 post 这个事件 → 手里物品的 {@code interactLivingEntity} → 打开它自己的女仆界面"，
 * 而 post 的返回值就是"**要不要直接当成 SUCCESS 结束**"。也就是说：
 * <b>我们取消掉这个事件，TLM 就不会再打开它自己的界面</b>——这是它留出来的口子，
 * 它自己也拿它做背包之类的交互。
 * <p>
 * **为什么是木棍，不是空手**：空手蹲下右键被车万女仆自己占了——那是**亲亲女仆**。
 * 两边抢同一个手势的结果，是玩家想开学习池、结果亲了她一口。
 * 木棍则是个没人拿它跟人互动的东西（便宜、没别的用处），拿在手里蹲下右键
 * 不会跟 TLM 任何一条动作撞上。
 * <p>
 * 界面在客户端开（右键本来就两端都会走一遍预测），服务端不需要额外发"打开"的包；
 * 池子本身由 TLM 的 {@code TASK_DATA_SYNC} 同步，界面直接读她身上的数据。
 */
public class MaidStudyInteractHandler {

    /**
     * 触发用的物品：木棍。
     * <p>
     * 单独拎出来，是因为"换个触发物"是最可能被改的一句话——换掉这个常量就够了，
     * 别的判断都是拿它比的。
     */
    public static final Item TRIGGER_ITEM = Items.STICK;

    @SubscribeEvent
    public static void onInteractMaid(InteractMaidEvent event) {
        Player player = event.getPlayer();
        EntityMaid maid = event.getMaid();
        if (!player.isShiftKeyDown() || !event.getStack().is(TRIGGER_ITEM)) {
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
