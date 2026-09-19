package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * 施工进度条：**站在女仆附近时**，屏幕中央偏下显示她这一单建到哪了。
 * <p>
 * 为什么放在准星下面而不是她头顶：头顶那套要按摄像机投影算世界坐标，算偏了条就飘到别人身上；
 * 而玩家看进度的时候本来就在看她——视线落在准星附近，条画在那儿不用挪眼睛。
 * <p>
 * 有几条守则是"看的人"的体验：
 * <ul>
 *   <li><b>只在附近显示</b>（{@value #SHOW_DISTANCE_SQR} 的平方根 = 16 格）：
 *       远处她自己在干活，屏幕上杵着一条进度条只是干扰；</li>
 *   <li><b>有几条在建就只显示最近那个</b>：同时显示三条互相压着，谁也不是谁的进度；</li>
 *   <li><b>带上她的名字</b>：多人服里两三个女仆都在建，不带名字根本不知道是谁的。</li>
 * </ul>
 * 这里**不引用任何车万女仆的类型**（用 {@code Entity} 就够）：这个类每帧都在跑，
 * 没装女仆模组的玩家也会加载到它。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BuildProgressHud {

    /** 女仆离玩家这么近才显示（平方距离；跟"缺料提示"用的 16 格保持一致） */
    private static final double SHOW_DISTANCE_SQR = 16.0D * 16.0D;
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 6;
    /** 条画在准星下面一点 */
    private static final int BAR_OFFSET_Y = 22;
    /** 进度条会落在天空、岩浆、树叶上，没有一层暗底读不出边界 */
    private static final int COLOR_BACKDROP = 0xA0000000;
    private static final int COLOR_TRACK = 0xFF232323;
    private static final int COLOR_FILL = 0xFF54C46A;
    private static final int COLOR_EDGE = 0xFF8A8A8A;

    private BuildProgressHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        Map<Integer, MaidBuildProgress.Entry> active = MaidBuildProgress.active();
        if (active.isEmpty()) {
            return;
        }
        Entity nearest = null;
        MaidBuildProgress.Entry progress = null;
        double nearestDistance = SHOW_DISTANCE_SQR;
        for (Map.Entry<Integer, MaidBuildProgress.Entry> candidate : active.entrySet()) {
            Entity entity = mc.level.getEntity(candidate.getKey());
            if (entity == null) {
                continue; // 不在这个维度、或者区块还没加载
            }
            double distance = mc.player.distanceToSqr(entity);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = entity;
                progress = candidate.getValue();
            }
        }
        if (nearest != null) {
            render(event.getGuiGraphics(), mc, nearest, progress);
        }
    }

    private static void render(GuiGraphics graphics, Minecraft mc, Entity maid,
                               MaidBuildProgress.Entry progress) {
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int centerY = mc.getWindow().getGuiScaledHeight() / 2;
        int x = centerX - BAR_WIDTH / 2;
        int y = centerY + BAR_OFFSET_Y;

        graphics.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 2, COLOR_BACKDROP);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOR_TRACK);
        int filled = (int) Math.round(BAR_WIDTH * Math.min(1.0D,
                progress.total() <= 0 ? 0.0D : progress.done() / (double) progress.total()));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BAR_HEIGHT, COLOR_FILL);
        }
        graphics.renderOutline(x, y, BAR_WIDTH, BAR_HEIGHT, COLOR_EDGE);

        // 名字 + 还剩几块 + 百分比：只给一条光秃秃的进度条，玩家还得自己算
        Component text = Component.empty()
                .append(maid.getDisplayName())
                .append("：")
                .append(Component.translatable("gui.blueprint.build_progress",
                        progress.left(), progress.percent()));
        graphics.drawString(mc.font, text,
                centerX - mc.font.width(text) / 2, y + BAR_HEIGHT + 4, 0xFFFFFF, true);
    }
}
