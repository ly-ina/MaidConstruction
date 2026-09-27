package com.example.blueprint.client;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.UUID;

/**
 * 施工HUD：**进度条只看建筑，女仆的状态单独一行**。
 * <p>
 * 三行，从上到下：
 * <ol>
 *   <li>进度条本身——只画建筑的完成度，不带任何人的名字；</li>
 *   <li>"还剩 N 块 · X%"——也只看建筑；</li>
 *   <li>"小玉：去取材料"——**女仆此刻在干什么**，单独一行。</li>
 * </ol>
 * 为什么要把这两件事拆开：它们变化的速度差着几个数量级。一座建筑建到几成是**慢变量**
 * （半分钟才动一下），而她在取料、在走位、在等料是**快变量**（几 tick 就能来回一趟）。
 * 混在一行里的时候，快变量一抖，看上去就像整条进度都在跳；
 * 拆开之后，条是条的、字是字的，谁也别拿谁当自己的波动。
 * <p>
 * 条上的数字来自**工地本身**（工地上已经是目标状态的方块数，见 {@code BuildSession#done}），
 * 不是拿内部账倒算的，所以跟趟数、重扫、会话重建都无关。
 * <p>
 * 位置：屏幕中央偏下。为什么不在她头顶：头顶那套要按摄像机投影算世界坐标，
 * 算偏了条就飘到别人身上；而玩家看进度时本来就在看她，视线落在准星附近。
 * <p>
 * 这里**不引用任何车万女仆的类型**（用 {@code Entity} 就够）：这个类每帧都在跑，
 * 没装女仆模组的玩家也会加载到它。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BuildProgressHud {

    /**
     * 女仆离玩家这么近才显示（平方距离）。
     * <p>
     * 距离由配置给（{@code progress.radius}），默认 16 格——跟"缺料提示"用的距离一致。
     * 服务端是按同一个值（再多留 8 格余量）推送的，所以这个值改大改小，两边一起动。
     */
    private static double showDistanceSqr() {
        double radius = BlueprintConfig.progressRadius();
        return radius * radius;
    }

    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 6;
    /** 条画在准星下面一点 */
    private static final int BAR_OFFSET_Y = 22;
    /** 进度条会落在天空、岩浆、树叶上，没有一层暗底读不出边界 */
    private static final int COLOR_BACKDROP = 0xA0000000;
    private static final int COLOR_TRACK = 0xFF232323;
    private static final int COLOR_FILL = 0xFF54C46A;
    private static final int COLOR_EDGE = 0xFF8A8A8A;
    /** 建筑那行是白的，女仆那行淡一点：一眼能分清哪行是"活"、哪行是"人" */
    private static final int COLOR_BUILDING_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MAID_TEXT = 0xFFD0D0D0;

    /**
     * 现在画的是哪位（**UUID**，不是实体 id）：**锁住她**，别每帧重挑最近的。
     * <p>
     * 不锁的话，两只女仆同时在建时"最近"每帧都会变——条上的字跟着一帧一换。
     * 用 UUID 而不是实体 id：那个 id 会被游戏复用，拿它当"我记得画的是谁"会认错人。
     */
    private static UUID pinnedUuid;

    /**
     * 换人的门槛（比的是**距离的平方**）：0.25 表示"另一只要近到一半以内才换"。
     * <p>
     * 不能"稍微近一点就换"：两只女仆一左一右站在你两边时距离几乎相等，
     * 谁近一点是浮点数决定的，于是每帧都在换。
     */
    private static final double SWITCH_CLOSER_RATIO = 0.25D;

    /** 上一次"改画"画的是谁、什么时候（给日志节流用，见 {@link #logSwitch}） */
    private static int lastLoggedId = -1;
    private static long lastLoggedAt;

    private BuildProgressHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        Collection<MaidBuildProgress.Entry> records = MaidBuildProgress.active();
        if (records.isEmpty()) {
            pinnedUuid = null;
            return;
        }
        double limit = showDistanceSqr();

        // 每位女仆一条记录。先点出"此刻真能画的那几条"：实体还找得到、UUID 对得上、且在范围内
        Entity pinnedEntity = null;
        MaidBuildProgress.Entry pinnedEntry = null;
        double pinnedDistance = 0.0D;
        int visible = 0;
        for (MaidBuildProgress.Entry entry : records) {
            Entity entity = mc.level.getEntity(entry.maidId());
            if (entity == null || !entity.getUUID().equals(entry.maidUuid())) {
                continue; // 不在这个维度、区块还没加载、或者那个 id 已经被别的实体拿走了
            }
            double distance = mc.player.distanceToSqr(entity);
            if (distance > limit) {
                continue; // 太远：她自己在那儿建，屏幕上不用杵条
            }
            visible++;
            if (entry.maidUuid().equals(pinnedUuid)) {
                pinnedEntity = entity;
                pinnedEntry = entry;
                pinnedDistance = distance;
            }
        }

        // 再挑"别的"里最近的那只。**只有它比锁住的那只明显更近，才换人**
        Entity other = null;
        MaidBuildProgress.Entry otherEntry = null;
        double otherDistance = limit;
        for (MaidBuildProgress.Entry entry : records) {
            if (entry.maidUuid().equals(pinnedUuid)) {
                continue; // 锁住的那只上面已经算过了
            }
            Entity entity = mc.level.getEntity(entry.maidId());
            if (entity == null || !entity.getUUID().equals(entry.maidUuid())) {
                continue;
            }
            double distance = mc.player.distanceToSqr(entity);
            if (distance > otherDistance) {
                continue;
            }
            if (pinnedEntity != null && distance >= pinnedDistance * SWITCH_CLOSER_RATIO) {
                continue; // 锁着人：要换就得近到一半以内
            }
            otherDistance = distance;
            other = entity;
            otherEntry = entry;
        }

        Entity maid = other != null ? other : pinnedEntity;
        MaidBuildProgress.Entry shown = other != null ? otherEntry : pinnedEntry;
        if (maid == null || shown == null) {
            pinnedUuid = null;
            return;
        }
        pinnedUuid = shown.maidUuid();
        logSwitch(maid, visible);

        // **进度条只看建筑**：同一处工地（site）上，谁报的都是"这座建筑建到几成"，
        // 所以取同一处工地上最大的那个数——跟"挑中的是哪位"无关
        int done = shown.done();
        int total = shown.total();
        for (MaidBuildProgress.Entry entry : records) {
            if (entry.site() == shown.site()) {
                done = Math.max(done, entry.done());
                total = Math.max(total, entry.total());
            }
        }
        render(event.getGuiGraphics(), mc, maid, shown, done, total);
    }

    /**
     * 画三行：条、建筑、女仆。参数里的 {@code done}/{@code total} 是**这座建筑**的进度；
     * {@code progress} 只用来取"她现在在干什么"。
     */
    private static void render(GuiGraphics graphics, Minecraft mc, Entity maid,
                               MaidBuildProgress.Entry progress, int done, int total) {
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int centerY = mc.getWindow().getGuiScaledHeight() / 2;
        int x = centerX - BAR_WIDTH / 2;
        int y = centerY + BAR_OFFSET_Y;

        graphics.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 2, COLOR_BACKDROP);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOR_TRACK);
        int filled = (int) Math.round(BAR_WIDTH * Math.min(1.0D,
                total <= 0 ? 0.0D : done / (double) total));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BAR_HEIGHT, COLOR_FILL);
        }
        graphics.renderOutline(x, y, BAR_WIDTH, BAR_HEIGHT, COLOR_EDGE);

        // 第二行：**建筑**——它的名字 + 还剩几块 + 百分比。没有人的名字：
        // 这一行是慢变量，半分钟才动一下；"她此刻在干什么"在下一行
        int percent = total <= 0 ? 0 : (int) Math.round(done * 100.0D / total);
        int left = Math.max(0, total - done);
        Component building = progress.name().isEmpty()
                // 没起名就说"未命名建筑"：不显示名字的话，多工地时认不出是哪一座
                ? Component.translatable("gui.blueprint.build_progress_unnamed", left, percent)
                : Component.translatable("gui.blueprint.build_progress_named",
                        progress.name(), left, percent);
        graphics.drawString(mc.font, building,
                centerX - mc.font.width(building) / 2, y + BAR_HEIGHT + 4,
                COLOR_BUILDING_TEXT, true);

        // 第三行：**女仆**。她此刻在干什么——取料、走位、还是在等料。
        // 单独一行，因为它一会儿一变；跟上面那行混着写，看着就像整条进度在跳
        MutableComponent state = Component.empty()
                .append(maid.getDisplayName())
                .append("：")
                .append(Component.translatable(progress.phaseKey()));
        graphics.drawString(mc.font, state,
                centerX - mc.font.width(state) / 2, y + BAR_HEIGHT + 16,
                COLOR_MAID_TEXT, true);
    }

    /**
     * 换人时留一行日志（两秒最多一条）。
     * <p>
     * "条在跳"这类现象，光看屏幕分不清是几条记录在抢——这一行能看出
     * 当前画的是谁、附近共有几位在建，一眼定性。
     */
    private static void logSwitch(Entity maid, int visible) {
        if (maid.getId() == lastLoggedId) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLoggedAt < 2000L) {
            return;
        }
        lastLoggedAt = now;
        lastLoggedId = maid.getId();
        BlueprintMod.LOGGER.info("[施工进度条] 改画 {}（附近在建 {} 位）", maid.getUUID(), visible);
    }

    /**
     * 断线时把记下来的进度全清掉。
     * <p>
     * 不清的话，换存档重连之后，旧记录要等两秒超时才作废——那两秒里屏幕上会飘出
     * 一条跟当前存档毫无关系的进度条。顺带把"锁住的那位"也松开。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MaidBuildProgress.clear(null);
        pinnedUuid = null;
    }
}
