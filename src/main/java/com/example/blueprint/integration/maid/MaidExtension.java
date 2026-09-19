package com.example.blueprint.integration.maid;

import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.registry.ModItems;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager;
import net.minecraft.world.item.Item;
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
        // 学习模式：跟在主人身边，把主人亲手做的东西记进她的学习池
        manager.add(new MaidStudyTask());
        // 工业模式：照学习池里下的单开工（下单时会自动切过去，见 MaidIndustryTask）
        manager.add(new MaidIndustryTask());

        // 施工、学习、做单都靠自己的 tick 驱动，在这里挂上 Forge 事件总线。
        // 放在这个方法里注册，可以保证只有女仆模组真的加载了才会执行。
        if (!tickHandlerRegistered) {
            tickHandlerRegistered = true;
            MinecraftForge.EVENT_BUS.register(MaidBuildTickHandler.class);
            MinecraftForge.EVENT_BUS.register(MaidStudyTickHandler.class);
            MinecraftForge.EVENT_BUS.register(MaidStudyInteractHandler.class);
            MinecraftForge.EVENT_BUS.register(MaidCraftTickHandler.class);
        }
    }

    /**
     * 把学习池登记进 TLM 的任务数据表。
     * <p>
     * <b>少了这一步，池子的钥匙就是悬空的。</b> 数据照样写、照样跟着存档走，
     * 但 {@code maid.getData(KEY)} 在表里查不到这把钥匙——表现是"学过的全不记得"，
     * 而且不报错，最难查。TLM 只在这个回调里收附属的数据键。
     */
    @Override
    public void registerTaskData(TaskDataRegister register) {
        register.register(MaidStudyPool.KEY);
        // 待做清单：主人在学习池里下的单
        register.register(MaidCraftOrder.KEY);
    }

    /**
     * 把绑定书注册成女仆饰品，否则饰品栏会拒绝收纳它。
     * <p>
     * BaubleManager 在自己的初始化末尾会把内部表冻结成不可变集合，
     * 冻结之后再 bind 会抛异常。这个回调正是由 TLM 在冻结前调用的，
     * 所以不要在任何地方手动提前触发初始化。
     */
    @Override
    public void bindMaidBauble(BaubleManager manager) {
        manager.bind(ModItems.BINDING_BOOK, new BindingBookBauble());

        // 无线女仆终端也做成饰品，否则饰品栏会拒绝收纳它。
        // 它只有装了 AE2 才存在，没装时 Ae2Compat 返回 null，跳过即可
        Item wirelessTerminal = Ae2Compat.wirelessTerminalItem();
        if (wirelessTerminal != null) {
            manager.bind(wirelessTerminal, new MaidWirelessBauble());
        }
    }
}
