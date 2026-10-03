package com.example.blueprint.server;

import com.example.blueprint.BlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 1.8.0 的"录制态"会在玩家身上开飞行（{@code abilities.mayfly / flying}），后来整个删了——
 * 那等于给生存玩家一个免费飞行，太容易被拿来钻空子（见 DEVELOPER §7.19）。
 * <p>
 * 这个类只剩一件事：**把那时留下的存档收尾**。飞行是写进玩家存档的，当年在录制态里直接
 * 退出游戏的人，存档里留着"进录制态之前长什么样"那份记录、能力也停在开着飞行的状态；
 * 现在没人再去还原它了，不收尾的话他下次登录就带着 {@code mayfly} 进来——看着就像
 * "这人在生存里会飞"，正是我们要根除的那个现象。
 * <p>
 * 过了 1.8.x（不再有这种存档）就连它一起删掉：删的时候顺手确认
 * {@code BlueprintRecordSaved} / {@code BlueprintRecordSavedData} 这两个键没有别的用处。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecordModeCleanup {

    /** "我进录制态之前长什么样"那份记录的标记（1.8.0 留下的键名，不能改） */
    private static final String SAVED = "BlueprintRecordSaved";
    private static final String SAVED_DATA = "BlueprintRecordSavedData";

    private static final String KEY_MAYFLY = "mayfly";
    private static final String KEY_FLYING = "flying";

    private RecordModeCleanup() {
    }

    /**
     * 登录时收尾：把能力还原成进录制态之前的样子，并清掉那份记录。
     * <p>
     * 判据就是"那份记录还在不在"——在，说明这人的存档停在 1.8.0 的录制态里；
     * 不在（绝大多数人）就什么也不做。
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(SAVED)) {
            return;
        }

        CompoundTag saved = data.getCompound(SAVED_DATA);
        player.getAbilities().mayfly = saved.getBoolean(KEY_MAYFLY);
        // 飞行开关只在"允许飞"时才恢复：原状态本来就不飞的人，这里给 true 也没用，
        // 反而会让"明明已经落地了却还在飞"这种事留在存档里
        player.getAbilities().flying = saved.getBoolean(KEY_FLYING) && player.getAbilities().mayfly;
        player.onUpdateAbilities();

        data.remove(SAVED);
        data.remove(SAVED_DATA);
        BlueprintMod.LOGGER.info("玩家 {} 的存档停在 1.8.0 的录制态里，已把飞行还回去", player.getUUID());
    }
}
