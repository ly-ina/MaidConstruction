package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 直接驱动处于「蓝图施工」模式的女仆。
 * <p>
 * 这里刻意绕开了车万女仆的 Brain 调度：IMaidTask#createBrainTasks 返回的 Behavior
 * 要经过 TLM 内部多层装配才会被执行，一旦中间任何一环不满足（活动状态、记忆模块、
 * 日程阶段等）女仆就会一动不动，而且没有任何报错，极难排查。
 * 改成服务端 tick 里主动查找并驱动，行为完全可控，出问题也能立刻给出提示。
 * <p>
 * 扫描的是维度里所有已加载的女仆，不要求主人在附近——女仆可以独立留守工地施工。
 * 区块卸载后女仆本身也不再 tick，自然就停了，不会有额外开销。
 */
public class MaidBuildTickHandler {

    private static final Map<Integer, BlueprintBuildController> CONTROLLERS = new HashMap<>();
    private static final int CLEANUP_INTERVAL = 600;
    private static int tickCounter = 0;

    /** "现在不是我的上班时间"这句话的最小间隔（一分钟） */
    private static final long OFF_DUTY_COOLDOWN_MS = 60_000L;
    /** 每只女仆上次说这句话的时间 */
    private static final Map<UUID, Long> LAST_OFF_DUTY = new HashMap<>();

    /** 施工驱动连续抛异常的次数（成功一 tick 就清掉） */
    private static final Map<UUID, Integer> FAIL_COUNT = new HashMap<>();
    /** 每只女仆上次把异常写进日志的时间，见 {@link #FAIL_LOG_COOLDOWN_MS} */
    private static final Map<UUID, Long> FAIL_LOGGED_AT = new HashMap<>();
    /** 同一只女仆的报错日志最小间隔（半分钟）：她每 tick 都在试同一块，不节流就是刷屏 */
    private static final long FAIL_LOG_COOLDOWN_MS = 30_000L;
    /** 连续错这么多次就停她的工：同一处反复炸，一直试只是白刷日志 */
    private static final int MAX_CONSECUTIVE_FAILURES = 20;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
                continue;
            }
            if (!isBuildTask(maid)) {
                BlueprintBuildController stopped = CONTROLLERS.remove(maid.getId());
                if (stopped != null) {
                    // 女仆中途换了工作。施工期间动过她的待命状态，得还回去，
                    // 否则她会一直保持"不跟随"的姿势，玩家还以为女仆坏了
                    stopped.detach(maid);
                }
                continue;
            }
            // 施工也按作息表来——**默认关着**（理由见 BlueprintConfig 里那个开关的注释）：
            // 它一生效，不在上班时间她就干杵着，而这个现象跟"她坏了"几乎一模一样，
            // 所以先按 1.5.5 的老行为（昼夜不停建）跑，想要作息的人自己打开
            if (BlueprintConfig.buildOnlyOnShift() && !MaidIndustryTask.isWorkingTime(maid)) {
                warnOffDuty(level, maid);
                continue;
            }
            BlueprintBuildController controller =
                    CONTROLLERS.computeIfAbsent(maid.getId(), id -> new BlueprintBuildController());
            try {
                controller.tick(level, maid);
                // 这一 tick 平安无事：把"连续出错"的账销掉
                FAIL_COUNT.remove(maid.getUUID());
            } catch (Throwable t) {
                // 施工是**直接改世界**的：setBlock、方块实体 load、战利品表……
                // 全都有可能在别的模组手里抛异常。这里是主线程的 tick，
                // 一炸就是整台服务器陪葬——所以每只女仆单独兜住。
                // 兜住之后服务器照常跑，只是她这一 tick 白干（见 handleFailure）
                handleFailure(maid, controller, t);
            }
        }

        if (++tickCounter >= CLEANUP_INTERVAL) {
            tickCounter = 0;
            cleanup(level);
        }
    }

    /**
     * 不在上班时间时说一句，省得主人分不清"她在等天亮"和"她坏了"。
     * <p>
     * 同一只女仆一分钟最多一次：这张单子可能要等一整夜，每分钟念一遍是噪音。
     */
    private static void warnOffDuty(ServerLevel level, EntityMaid maid) {
        long now = System.currentTimeMillis();
        Long last = LAST_OFF_DUTY.get(maid.getUUID());
        if (last != null && now - last < OFF_DUTY_COOLDOWN_MS) {
            return;
        }
        LAST_OFF_DUTY.put(maid.getUUID(), now);
        MaidSpeech.say(maid, "message.blueprint.craft.off_duty");
        BlueprintMod.LOGGER.info("女仆 {} 在施工模式，但此刻不是她的工作时间（作息 {}）；"
                        + "想让她昼夜不停地建，把 config/blueprint-common.toml 里的 "
                        + "schedule.build_only_on_shift 设成 false",
                maid.getUUID(), maid.getSchedule());
    }

    /**
     * 施工驱动抛异常了：记一笔、节流地喊一声，连续出错太多次就先停她的工。
     * <p>
     * 三个考虑：
     * <ul>
     *   <li><b>不能让一只女仆把服务器带崩</b>——异常在这里被兜住，世界照常 tick；</li>
     *   <li><b>日志要节流</b>：出问题的地方每 tick 都会抛（她每 tick 都试同一块），
     *       不节流就是刷屏，把真正有用的信息淹掉；</li>
     *   <li><b>连续出错就停工</b>：既然是同一处反复炸，一直试下去只是白刷日志，
     *       停下来等她被人重新安排（把蓝图拿下来再放回去即可重试）。</li>
     * </ul>
     */
    private static void handleFailure(EntityMaid maid, BlueprintBuildController controller, Throwable t) {
        UUID id = maid.getUUID();
        int fails = FAIL_COUNT.merge(id, 1, Integer::sum);
        long now = System.currentTimeMillis();
        Long last = FAIL_LOGGED_AT.get(id);
        if (fails == 1 || last == null || now - last >= FAIL_LOG_COOLDOWN_MS) {
            FAIL_LOGGED_AT.put(id, now);
            BlueprintMod.LOGGER.error("女仆 {} 的施工驱动抛异常（累计 {} 次，已单独兜住，服务器不受影响；"
                    + "连续 {} 次就停她的工）", id, fails, MAX_CONSECUTIVE_FAILURES, t);
        }
        if (fails >= MAX_CONSECUTIVE_FAILURES) {
            FAIL_COUNT.remove(id);
            FAIL_LOGGED_AT.remove(id);
            CONTROLLERS.remove(maid.getId());
            controller.detach(maid);
            BlueprintMod.LOGGER.error("女仆 {} 连续 {} 次施工出错，先停工。"
                    + "把手上那张蓝图拿下来再放回去，可以重新开工", id, MAX_CONSECUTIVE_FAILURES);
        }
    }

    private static boolean isBuildTask(EntityMaid maid) {
        try {
            return maid.getTask() != null && BlueprintBuildTask.UID.equals(maid.getTask().getUid());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 女仆被移除后把对应的控制器清掉，避免 Map 无限增长 */
    private static void cleanup(ServerLevel level) {
        Iterator<Map.Entry<Integer, BlueprintBuildController>> it = CONTROLLERS.entrySet().iterator();
        while (it.hasNext()) {
            if (level.getEntity(it.next().getKey()) == null) {
                it.remove();
            }
        }
    }
}
