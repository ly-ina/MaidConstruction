package com.example.blueprint.item;

import com.example.blueprint.BlueprintMod;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 让绑定书能抢到方块的右键。
 * <p>
 * 原版的交互顺序是**方块优先**：手持物品右键箱子时，箱子的 use 会先把界面打开
 * 并返回"已处理"，物品的 useOn 压根不会被调用——所以绑定书直接点容器是绑不上的。
 * <p>
 * 这里在方块处理之前把方块那一步掐掉（只掐方块，不放行物品），
 * 交互就会自然落到绑定书自己的 useOn 上。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID)
public final class BindingBookEvents {

    private BindingBookEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof BindingBookItem)) {
            return;
        }
        // 只 DENY 方块，不动物品：绑定/解除绑定的逻辑仍旧写在 BindingBookItem#useOn 里，
        // 这里纯粹是替它把路让开
        event.setUseBlock(Event.Result.DENY);
    }
}
