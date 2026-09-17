package com.example.blueprint.integration.maid;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraftforge.common.MinecraftForge;

/**
 * 车万女仆联动入口。
 * <p>
 * 这个类带 {@link LittleMaidExtension} 注解，只有女仆模组存在时才会被反射实例化，
 * 因此即便玩家没装女仆模组，也不会有任何类加载问题。
 */
@LittleMaidExtension
public class MaidExtension implements ILittleMaid {

    private static boolean tickHandlerRegistered = false;

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new BlueprintBuildTask());

        // 施工靠自己的 tick 驱动，在这里挂上 Forge 事件总线。
        // 放在这个方法里注册，可以保证只有女仆模组真的加载了才会执行。
        if (!tickHandlerRegistered) {
            tickHandlerRegistered = true;
            MinecraftForge.EVENT_BUS.register(MaidBuildTickHandler.class);
        }
    }
}
