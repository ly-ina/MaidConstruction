package com.example.blueprint.integration.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
                CONTROLLERS.remove(maid.getId());
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
