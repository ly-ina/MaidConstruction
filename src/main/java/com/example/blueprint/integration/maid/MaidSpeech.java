package com.example.blueprint.integration.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

/**
 * 女仆"开口"的统一格式：**「她的名字：正文」**。
 * <p>
 * 为什么统一到一个地方：这些提示本来就是她说的——她没学会、她缺材料、她做完了、
 * 她取不到仓库……全部摊成第三人称的"女仆找不到材料"，读起来像系统日志，
 * 而不是一个站在你面前的姑娘在讲话。加上名字之后，同一句话读起来就是她在跟你汇报。
 * <p>
 * 名字取 {@code getDisplayName()}，所以主人在命名牌上改过的名字也跟得上；
 * 冠词、冒号这些**不写进语言文件**——省得每个语种都得记得带一遍格式，
 * 也省得译文把冒号写错（中英标点不同，很容易漏）。
 */
public final class MaidSpeech {

    private MaidSpeech() {
    }

    /** 包成她说话的样子（不发送）。界面里自己显示的地方也用这个，格式才一致 */
    public static MutableComponent speak(EntityMaid maid, Component body) {
        return Component.empty()
                .append(maid.getDisplayName())
                .append("：")
                .append(body);
    }

    /**
     * 说给主人听：包好名字发到聊天栏。
     * <p>
     * 主人不在线（或者已经不是她的主人了）就什么都不做——她不是播音员，
     * 没人在听就不用喊。
     */
    public static void say(EntityMaid maid, String key, Object... args) {
        if (maid.getOwner() instanceof Player owner) {
            owner.sendSystemMessage(speak(maid, Component.translatable(key, args)));
        }
    }
}
