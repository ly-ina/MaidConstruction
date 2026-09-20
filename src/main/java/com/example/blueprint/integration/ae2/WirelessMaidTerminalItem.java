package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGrid;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.ISubMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 无线女仆终端：女仆饰品形态的 ME 终端。
 * <p>
 * 它刻意继承 AE2 的 {@link WirelessTerminalItem} 而不是自己从零写：官方的
 * {@code WirelessTerminalMenuHost} 构造函数里写着
 * {@code "Can only use this class with subclasses of WirelessTerminalItem"}，
 * 继承过来，面板、升级槽、"在物品栏里直接打开"的入口、链接、提示就全是现成的。
 * <p>
 * <b>凡是跟"能不能开面板"有关的判定，一律不覆写</b>（见 §7.11）。这里踩过一轮坑：
 * 为了让"没链接也能开面板"，把三个方法都覆写成了自己那套，结果
 * <ul>
 *   <li>{@code getLinkedGrid} 一覆写，"设备未链接"的提示就没了——因为**提示本来就写在它里面**，
 *       玩家看到的是"右键没反应"；</li>
 *   <li>{@code checkPreconditions} 一覆写，打开面板的前置条件就和官方分叉；</li>
 *   <li>{@code getMenuHost} 一覆写，界面宿主也换了人。</li>
 * </ul>
 * 现在只保留三处自己的东西：
 * <ul>
 *   <li><b>取电</b>：插了 {@link Ae2TerminalRegistry#MAID_BINDING_CARD} 就从网络取电，
 *       内置电池不再流失（远距离/跨维度时耗电很快，靠内置电池跑不了多远）；</li>
 *   <li><b>跨维度耗电</b>：AE2 算不出跨维度的距离，会给出 1e154 量级的速率，
 *       这里照 AE2WTLib 量子桥卡的取值压到 {@value #CROSS_DIMENSION_DRAIN} AE/tick；</li>
 *   <li><b>潜行右键空气断开链接</b>（官方只能在访问点界面里断）。</li>
 * </ul>
 * <p>
 * <b>不要拦方块上的右键。</b> AE2 的充能器没有界面，它靠右键把物品收进去
 * （{@code ChargerBlock.onActivated}）；无线访问点、驱动器同理。拦下来就等于这台终端
 * 永远充不上电、也放不进访问点。
 */
public class WirelessMaidTerminalItem extends WirelessTerminalItem {

    /**
     * 内置电池容量。
     * <p>
     * 插了卡之后电从网络来，这个电池只是"没插卡时"的续航；
     * 取 AE2 无线终端同一量级，够跑一阵子。
     */
    private static final double CAPACITY = 1_600_000.0D;

    /**
     * 跨维度时的固定耗电。
     * <p>
     * <b>取值参照 AE2WTLib 的量子桥卡。</b> 那边同样覆写了
     * {@code setPowerDrainPerTick(double)}，在射程判定过不去（也就是它靠量子网络桥
     * 跨维度/超远距离连着）时，不用 AE2 算出来的速率，改成这个固定值。
     * <p>
     * 为什么必须压住：{@code WirelessTerminalMenuHost.getWapSqDistance} 在
     * "访问点跟玩家不同维度"和"访问点没在工作"两种情况下**直接返回
     * {@code Double.MAX_VALUE}**，于是 {@code currentDistanceFromGrid} 变成
     * {@code sqrt(MAX) ≈ 1.3e154}，再乘 `wirelessTerminalDrainMultiplier` 就是要命的量级——
     * 一 tick 就能把整张网络抽干，跨维度等于没法用。
     */
    private static final double CROSS_DIMENSION_DRAIN = 22.5D;

    public WirelessMaidTerminalItem(Properties properties) {
        super(() -> CAPACITY, properties);
    }

    // ------------------------------------------------------------------
    // 右键
    // ------------------------------------------------------------------

    /** 潜行右键空气断开链接（等价于访问点界面里的"断开"），否则交给父类去开面板。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // **不是真人在用，就别走"开面板"这条路**。
        // 女仆身上带着它时，TLM 那边会当物品"用"一下，而 AE2 的打开流程里
        // 会喊"无法找到所链接的网络""超出范围"这类话——那些话是给玩家看的，
        // 落到主人聊天框里就成了每几秒刷一次的噪音。
        // 判据：服务端的假玩家（TLM 给女仆代操作时用的那种）没有连接
        if (!level.isClientSide
                && player instanceof ServerPlayer serverPlayer
                && serverPlayer.connection == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                WirelessMaidLink.unlink(stack);
                player.displayClientMessage(
                        Component.translatable("message.blueprint.wireless_unbound"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    // ------------------------------------------------------------------
    // 供电：插卡后从网络取
    // ------------------------------------------------------------------

    /**
     * 插了卡就对外声明"满电"。
     * <p>
     * 这一条不是摆设：界面宿主取电时先用
     * {@code Math.min(amount, getAECurrentPower(stack))} 夹一次上限，
     * 电池是空的话，光覆写 {@link #usePower} 也没用——它根本不会来问。
     */
    @Override
    public double getAECurrentPower(ItemStack stack) {
        return WirelessMaidLink.hasBindingCard(stack) ? getAEMaxPower(stack) : super.getAECurrentPower(stack);
    }

    /**
     * 插了卡就问网络"到底有没有这么多电"，而不是无条件答"有"。
     * <p>
     * <b>不能无条件返回 true。</b> 官方只是先问这个方法，答"有"之后就照常去扣电；
     * 扣不到照样会把菜单判为失效——表现就是面板一闪而过。如实回答的话，
     * 没电时走到的是官方那条"设备未通电"的提示，玩家知道该去充能器了。
     * <p>
     * 问的时候用 {@link Actionable#SIMULATE}：只是探一探，不扣电。
     */
    @Override
    public boolean hasPower(Player player, double amount, ItemStack stack) {
        if (WirelessMaidLink.hasBindingCard(stack)) {
            IGrid grid = WirelessMaidLink.resolveGrid(player.level(), stack);
            if (grid != null) {
                return WirelessMaidLink.extractNetworkPower(grid, amount, Actionable.SIMULATE) >= amount;
            }
        }
        return super.hasPower(player, amount, stack);
    }

    /**
     * 取电：插了卡先问网络要，网络给不出来再退回内置电池。
     * <p>
     * <b>先 SIMULATE 再 MODULATE，不能直接抽。</b> AE2 的 {@code extractAEPower} 是
     * "能抽多少抽多少"：抽不满时这里会返回 false 转去用电池，而那半截已经被从网络里扣掉了
     * ——等于凭空烧掉。先模拟一次确认能给够，再真正扣。
     * <p>
     * 回退是必要的——网络没电、没加载、或者干脆被拆了的时候，
     * 终端不该表现得像坏了，电池里剩多少还能用多少。
     */
    @Override
    public boolean usePower(Player player, double amount, ItemStack stack) {
        if (WirelessMaidLink.hasBindingCard(stack)) {
            IGrid grid = WirelessMaidLink.resolveGrid(player.level(), stack);
            if (grid != null
                    && WirelessMaidLink.extractNetworkPower(grid, amount, Actionable.SIMULATE) >= amount) {
                WirelessMaidLink.extractNetworkPower(grid, amount, Actionable.MODULATE);
                return true;
            }
        }
        return super.usePower(player, amount, stack);
    }

    // ------------------------------------------------------------------
    // 界面宿主
    // ------------------------------------------------------------------

    /**
     * 插了女仆绑定卡才换成自己的宿主，没插卡时保持官方的原样。
     * <p>
     * 卡片在这里的身份就是"跨维度开关"：只有插了卡，才会有"跨维度怎么算耗电"这件事。
     * 其余一切（打开的前置条件、射程判定、失效关面板）仍旧完全走官方那套。
     */
    @Override
    public ItemMenuHost getMenuHost(Player player, int slot, ItemStack stack, BlockPos pos) {
        if (!WirelessMaidLink.hasBindingCard(stack)) {
            return super.getMenuHost(player, slot, stack, pos);
        }
        return new WirelessMaidTerminalMenuHost(player, slot, stack,
                (p, subMenu) -> openFromInventory(p, slot));
    }

    /**
     * 只为了把跨维度的耗电压下来而复写的宿主（见 {@link #CROSS_DIMENSION_DRAIN}）。
     * <p>
     * 刻意**只覆写 {@code setPowerDrainPerTick}**：判定与失效逻辑全都留在官方那边，
     * 免得又走上"自己接管判定、结果提示全没了"的老路（§7.11）。
     */
    private static class WirelessMaidTerminalMenuHost extends WirelessTerminalMenuHost {

        WirelessMaidTerminalMenuHost(Player player, int slot, ItemStack stack,
                                     BiConsumer<Player, ISubMenu> returnToMainMenu) {
            super(player, slot, stack, returnToMainMenu);
        }

        /**
         * 父类每 tick 会把 AE2 按距离算出来的速率交给这个方法，这里负责把它按住。
         * <p>
         * 距离算不出来时（见 {@link #distanceIsMeaningless()}），AE2 给的是
         * 1e154 量级的值——照单全收就是"一 tick 抽干整张网络"。
         */
        @Override
        protected void setPowerDrainPerTick(double requested) {
            super.setPowerDrainPerTick(distanceIsMeaningless() ? CROSS_DIMENSION_DRAIN : requested);
        }

        /** 链接跨维度、或者链接的那台访问点不在工作——两种情况下 AE2 的"距离"都是 {@code Double.MAX_VALUE}。 */
        private boolean distanceIsMeaningless() {
            ItemStack stack = getItemStack();
            GlobalPos linked = WirelessMaidLink.linkedPosition(stack);
            if (linked == null || !linked.dimension().equals(getPlayer().level().dimension())) {
                // 跨维度没有"距离"可言，这正是绑定卡要放开的那种用法
                return true;
            }
            IWirelessAccessPoint accessPoint =
                    WirelessMaidLink.linkedAccessPoint(getPlayer().level(), stack);
            return accessPoint == null || !accessPoint.isActive();
        }
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        GlobalPos linked = WirelessMaidLink.linkedPosition(stack);
        tooltip.add(linked == null
                ? Component.translatable("tooltip.blueprint.wireless_unbound")
                : Component.translatable("tooltip.blueprint.wireless_bound",
                        linked.dimension().location().toString(),
                        linked.pos().getX(), linked.pos().getY(), linked.pos().getZ()));
        if (WirelessMaidLink.hasBindingCard(stack)) {
            tooltip.add(Component.translatable("tooltip.blueprint.wireless_card_installed"));
        }
        tooltip.add(Component.translatable("tooltip.blueprint.wireless_hint_open"));
        tooltip.add(Component.translatable("tooltip.blueprint.wireless_hint_bind"));
        tooltip.add(Component.translatable("tooltip.blueprint.wireless_hint_unbind"));
        tooltip.add(Component.translatable("tooltip.blueprint.wireless_maid"));
    }
}
