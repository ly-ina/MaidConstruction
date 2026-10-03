package com.example.blueprint.server;

import com.example.blueprint.BlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 录制态：让玩家能飞、能穿墙地绕着建筑转，好把两个角点框准。
 * <p>
 * <b>为什么不是直接切成旁观者模式</b>：旁观者模式会掐掉一切交互，连方块都点不中——
 * 而录制要做的恰恰是"右键点方块选角点"。所以这里只借旁观者的两样好处：
 * <ul>
 *   <li><b>能飞</b>（{@code abilities.mayfly / flying}）：大建筑站着看不到顶，得能上去；</li>
 *   <li><b>能穿墙</b>（{@code noPhysics}）：室内、地下室、box 里那几格，站在外面点不到。</li>
 * </ul>
 * <p>
 * <b>进来时的状态要原样记下来</b>：创造模式的玩家本来就飞着，退出录制态不该把他的飞行收走。
 * 存档落在玩家的持久数据里（{@code persistentData}）而不是内存里——这样做两件事都免费：
 * 换维度不用管，服务端重启后"这人还在录制态"也认得出；清掉那份存档就等于"不在录制态"，
 * 所以取消、完成、断线都只是同一个动作（{@link #leave}）。
 * <p>
 * <b>断线必须还原</b>：能力会跟着玩家存档写下去，不还原的话下次登录他就是个能飞的生存玩家。
 * 见 {@link #onLogout}。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecordMode {

    /** 持久数据里"我进录制态之前长什么样"的标记 */
    private static final String SAVED = "BlueprintRecordSaved";
    private static final String SAVED_DATA = "BlueprintRecordSavedData";

    private static final String KEY_MAYFLY = "mayfly";
    private static final String KEY_FLYING = "flying";
    private static final String KEY_NO_PHYSICS = "noPhysics";

    private RecordMode() {
    }

    /** 进入录制态。重复调用不会把"进之前的样子"覆盖成录制态本身 */
    public static void enter(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(SAVED)) {
            CompoundTag saved = new CompoundTag();
            saved.putBoolean(KEY_MAYFLY, player.getAbilities().mayfly);
            saved.putBoolean(KEY_FLYING, player.getAbilities().flying);
            saved.putBoolean(KEY_NO_PHYSICS, player.noPhysics);
            data.put(SAVED_DATA, saved);
            data.putBoolean(SAVED, true);
        }

        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.noPhysics = true;
        player.onUpdateAbilities();
    }

    /** 退出录制态：把进之前那套能力还回去；不在录制态时什么也不做 */
    public static void leave(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(SAVED)) {
            return;
        }

        CompoundTag saved = data.getCompound(SAVED_DATA);
        player.getAbilities().mayfly = saved.getBoolean(KEY_MAYFLY);
        // 飞行开关只在"允许飞"时才恢复：原状态本来就是不飞的，这里给 true 也没用，
        // 反而会让"明明已经落地了却还在飞"这种事留在存档里
        player.getAbilities().flying = saved.getBoolean(KEY_FLYING) && player.getAbilities().mayfly;
        player.noPhysics = saved.getBoolean(KEY_NO_PHYSICS);
        player.onUpdateAbilities();

        data.remove(SAVED);
        data.remove(SAVED_DATA);
    }

    /**
     * 断线时还原。
     * <p>
     * 这一步不能省：能力是**跟着玩家存档写下去的**，人在录制态里直接退出游戏的话，
     * 下次登录他带着 {@code mayfly} 进服，看着就像"这人在生存里会飞"。
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            leave(player);
        }
    }
}
