package com.example.blueprint;

import com.example.blueprint.integration.maid.BlueprintBuildController;
import com.example.blueprint.integration.maid.MaidBlueprint;
import com.example.blueprint.integration.maid.MaidBuildTickHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;

/**
 * {@code /blueprint}：看她到底在干什么。
 * <p>
 * 为什么要有它：进度条只在**站得够近**的时候画，而她可能在几百格外的另一处工地，
 * 甚至另一个维度。玩家想知道"她还在建吗、建的是哪一座、建到几成"时，缺的可能只是
 * 一个不用跑过去的办法。
 * <ul>
 *   <li>{@code /blueprint list} —— 所有在施工的女仆：谁、在哪座工地、还剩多少块；</li>
 *   <li>{@code /blueprint where} —— 离你最近的那处工地（报的是**建筑的锚点**，
 *       不是她本人的位置：要定位的是建筑）；</li>
 *   <li>{@code /blueprint stop} —— 把她手上的蓝图收回来，她立刻收工、待命状态还原。</li>
 * </ul>
 * 这几个子命令都**只读、或者只动她自己手上那件东西**：不动世界、不动别人的东西。
 * <p>
 * 这个类引用了车万女仆的类型（{@code EntityMaid}），但**没装女仆也不会崩**：
 * 引用只出现在方法体里，JVM 是**执行到**那一句才去解析类；而下面每个入口第一句
 * 都是"先从控制器表里拿人"——表是空的，后面的代码一次都不会跑
 * （1.6.4 那次客户端崩溃的教训：每帧都跑的地方不能出现这类引用）。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID)
public final class BlueprintCommand {

    /** {@code where} / {@code stop} 找"最近的那只"时的半径（格） */
    private static final int NEAR_RADIUS = 64;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 谁都能看自己的女仆在干什么；动她手上的蓝图另判主人（见 stop）
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("blueprint")
                .requires(source -> source.hasPermission(0))
                .executes(context -> list(context.getSource()));
        // 一个子命令一句：这么写不用数括号，加一条也只是多一行
        root.then(Commands.literal("list").executes(context -> list(context.getSource())));
        root.then(Commands.literal("where").executes(context -> where(context.getSource())));
        root.then(Commands.literal("stop").executes(context -> stop(context.getSource())));
        event.getDispatcher().register(root);
    }

    /** 所有在施工的女仆 */
    private static int list(CommandSourceStack source) {
        List<EntityMaid> maids = MaidBuildTickHandler.buildingMaids(source.getServer());
        if (maids.isEmpty()) {
            reply(source, "command.blueprint.none");
            return 0;
        }
        reply(source, "command.blueprint.count", maids.size());
        for (EntityMaid maid : maids) {
            BlueprintBuildController.Snapshot snapshot = MaidBuildTickHandler.snapshotOf(maid);
            reply(source, "command.blueprint.line",
                    maid.getDisplayName(),
                    Component.translatable(stateKey(snapshot)),
                    snapshot.total() - snapshot.done(),
                    site(source, maid, snapshot.anchor()));
        }
        return maids.size();
    }

    /** 离你最近的那处工地 */
    private static int where(CommandSourceStack source) {
        EntityMaid maid = nearest(source);
        if (maid == null) {
            reply(source, "command.blueprint.where_none", NEAR_RADIUS);
            return 0;
        }
        BlueprintBuildController.Snapshot snapshot = MaidBuildTickHandler.snapshotOf(maid);
        BlockPos anchor = snapshot.anchor();
        int left = snapshot.total() - snapshot.done();
        if (anchor == null) {
            // 没有原点就没法"定位"：直说她缺什么，比报一串空坐标有用
            reply(source, "command.blueprint.where_no_anchor", maid.getDisplayName(), left);
            return 1;
        }
        int away = (int) Math.round(Math.sqrt(source.getPosition().distanceTo(anchor.getCenter())));
        reply(source, "command.blueprint.where_line",
                maid.getDisplayName(), site(source, maid, anchor), away, left);
        return 1;
    }

    /**
     * 把蓝图从她手上收回来。
     * <p>
     * 这就是"让她停下"最干净的一步：她身上的蓝图一没，控制器下一 tick 自己就会
     * {@code reset()}——收工、待命状态、作息坐标全部还原（那条路本来就是为"中途换工作"
     * 写的，这里只是换个触发方式）。比在施工中途直接去掰状态机安全得多。
     */
    private static int stop(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            reply(source, "command.blueprint.stop_player_only");
            return 0;
        }
        EntityMaid maid = nearest(source);
        if (maid == null) {
            reply(source, "command.blueprint.stop_none", NEAR_RADIUS);
            return 0;
        }
        if (maid.getOwner() != player) {
            reply(source, "command.blueprint.stop_not_owner", maid.getDisplayName());
            return 0;
        }
        ItemStack taken = takeBlueprint(maid);
        if (taken.isEmpty()) {
            reply(source, "command.blueprint.stop_nothing", maid.getDisplayName());
            return 0;
        }
        // 还给她主人；装不下就丢在**他**脚边（不外送：蓝图是玩家的东西，掉在远处等于弄丢）
        if (player.getInventory().add(taken)) {
            reply(source, "command.blueprint.stop_done", maid.getDisplayName());
        } else {
            player.drop(taken, false);
            reply(source, "command.blueprint.stop_dropped", maid.getDisplayName());
        }
        return 1;
    }

    /** 离命令执行点最近的那只"在施工"的女仆（只算同一个维度） */
    @Nullable
    private static EntityMaid nearest(CommandSourceStack source) {
        EntityMaid nearest = null;
        double best = (double) NEAR_RADIUS * NEAR_RADIUS;
        for (EntityMaid maid : MaidBuildTickHandler.buildingMaids(source.getServer())) {
            if (!maid.level().dimension().equals(source.getLevel().dimension())) {
                continue;
            }
            double distance = maid.distanceToSqr(source.getPosition());
            if (distance < best) {
                best = distance;
                nearest = maid;
            }
        }
        return nearest;
    }

    /** 把她身上那张蓝图的**实物**取出来：先主手、再副手，最后翻背包 */
    private static ItemStack takeBlueprint(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem();
        if (MaidBlueprint.usable(main)) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return main.copy();
        }
        ItemStack off = maid.getOffhandItem();
        if (MaidBlueprint.usable(off)) {
            maid.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            return off.copy();
        }
        IItemHandler backpack = maid.getMaidInv();
        if (backpack != null) {
            for (int slot = 0; slot < backpack.getSlots(); slot++) {
                if (MaidBlueprint.usable(backpack.getStackInSlot(slot))) {
                    return backpack.extractItem(slot, 1, false);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 她在干什么：沿用进度条那几条译文，不再造一套同义的说法 */
    private static String stateKey(BlueprintBuildController.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case "FETCH" -> "hud.blueprint.maid_phase.fetch";
            case "MOVE_TO_SPOT" -> "hud.blueprint.maid_phase.walk";
            default -> "hud.blueprint.maid_phase.build";
        };
    }

    /** 工地位置：同一个维度只报坐标，别的维度把维度名也带上（不然那串数字没法用） */
    private static Component site(CommandSourceStack source, EntityMaid maid, @Nullable BlockPos anchor) {
        if (anchor == null) {
            return Component.translatable("command.blueprint.no_anchor");
        }
        String coords = anchor.getX() + " " + anchor.getY() + " " + anchor.getZ();
        if (maid.level().dimension().equals(source.getLevel().dimension())) {
            return Component.literal(coords);
        }
        return Component.literal(maid.level().dimension().location() + " " + coords);
    }

    /** 回一句话给执行者（译文由他的客户端查，所以这里只传键和参数） */
    private static void reply(CommandSourceStack source, String key, Object... args) {
        source.sendSuccess(() -> Component.translatable(key, args), false);
    }

    private BlueprintCommand() {
    }
}
