package com.example.blueprint;

import com.example.blueprint.item.ManualBook;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 新玩家第一次进世界时，发一本《女仆建筑说明书》。
 * <p>
 * "发过了"记在**玩家自己的存档数据**里（{@code PlayerPersisted}，会跟着存档走、
 * 死亡不掉）：同一个世界里换个维度、重登、重生都不会再发第二本，
 * 而换个世界（新存档）会重新发一次——那时候他确实又是"新人"。
 * <p>
 * 弄丢或删掉之后怎么再要一本：创造模式物品栏的模组那一栏里就有（见 {@code ModCreativeTabs}）。
 * 没做成可合成的配方是有意的——它是"说明"不是"道具"，被拆了反而让人以为少了件东西。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID)
public final class ManualGiveaway {

    /** 发过没有。带命名空间前缀，免得和别人的存档数据撞键 */
    private static final String GIVEN_TAG = "blueprint:manual_given";

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // 只有真正连上来的服务端玩家才发。单人游戏里这个事件也走服务端那一边，
        // 所以单人的新存档同样会收到
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(GIVEN_TAG)) {
            return;
        }
        data.putBoolean(GIVEN_TAG, true);

        ItemStack book = ManualBook.create();
        if (!player.getInventory().add(book)) {
            // 背包满了就丢在他脚下。**不能就这么算了**：第一次进服的玩家背包里通常
            // 已经有服务器送的一堆东西，正好塞满是很常见的——那样这本说明书就凭空没了，
            // 而"发过"的标记已经记上，他再也不会收到第二本
            player.drop(book, false);
        }
        player.sendSystemMessage(Component.translatable("manual.blueprint.given")
                .withStyle(ChatFormatting.GOLD));
    }

    private ManualGiveaway() {
    }
}
