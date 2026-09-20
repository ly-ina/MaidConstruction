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
            CONTROLLERS.computeIfAbsent(maid.getId(), id -> new BlueprintBuildController())
                    .tick(level, maid);
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
