package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「什么都有、而且取之不尽」的 ME 存储。
 * <p>
 * 它根本没有库存这回事——{@link #extract} 不查余额也不扣数量，问多少给多少。
 * 于是这一个类同时撑起两种用途：女仆把它当创造物品栏，AE2 把它挂进网格后
 * 整张网络就"什么都能取"。
 * <p>
 * 申报的数量用 {@link Integer#MAX_VALUE}，和 AE2 自己的创造存储
 * （{@code CreativeCellInventory}）取同一个值。这个数字会原样显示在终端里，
 * 换成 {@code Long.MAX_VALUE} 那种天文数字，玩家只会以为显示坏了。
 */
public class InfiniteItemStorage implements MEStorage {

    /**
     * 对外申报的数量。
     * <p>
     * 不用 {@code Long.MAX_VALUE} 的另一个原因：网络库存是若干来源相加的结果，
     * 各家都报 long 上界的话，几张创造接口同处一网就有可能把计数加溢出。
     */
    private static final long AMOUNT = Integer.MAX_VALUE;

    /**
     * 全物品清单，所有实例共用。
     * <p>
     * 做成静态缓存是因为它只跟物品注册表有关，而注册表在模组加载完成后就固定了，
     * 同一个 JVM 里不会再变。每摆一个方块就重建一遍（动辄上千个键）纯属浪费。
     */
    private static KeyCounter allItems;

    /** 是否把塞进来的东西直接销毁（见两个工厂方法） */
    private final boolean disposeInserts;

    private InfiniteItemStorage(boolean disposeInserts) {
        this.disposeInserts = disposeInserts;
    }

    /**
     * 女仆取料和还料用的那一份，**收下**塞进来的东西（也就是直接销毁）。
     * <p>
     * 女仆建完房要把剩料还回来，这里收下总比让她抱着一堆材料、
     * 或者往别的箱子里塞要干净——反正这个方块什么都是无限的。
     */
    public static InfiniteItemStorage forMaid() {
        return new InfiniteItemStorage(true);
    }

    /**
     * 挂进 ME 网格的那一份，**拒收**。
     * <p>
     * 收下会有很实在的后果：AE2 的网络库存写东西时是按优先级逐个问下来的
     * （{@code NetworkStorage.insert}），一旦这里答"我全要"，玩家往任意终端里
     * 放进去的东西就会被静默销毁，而且他自己不会知道。
     * <p>
     * 两份实例分开，是因为"女仆还剩料"和"玩家往网络里存东西"根本不是一回事：
     * 前者是我们主动清场，后者是物品蒸发。
     */
    public static InfiniteItemStorage forGrid() {
        return new InfiniteItemStorage(false);
    }

    @Override
    public Component getDescription() {
        return Component.translatable("block.blueprint.creative_maid_interface");
    }

    /**
     * 要多少给多少。
     * <p>
     * 不分"模拟"与"真取"：模拟要把能取的数量如实报出来，真取又没有余额可以扣，
     * 两种模式的结果都是同一个数。
     */
    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !(what instanceof AEItemKey)) {
            return 0;
        }
        return amount;
    }

    /**
     * 收不收，看这一份实例是给谁用的（见 {@link #forMaid()} / {@link #forGrid()}）。
     */
    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!disposeInserts || amount <= 0) {
            return 0;
        }
        return amount;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        // addAll 是把数量抄进 out，不是把 out 指过去，
        // 所以调用方后续怎么改都不会污染这份共用的清单
        out.addAll(snapshot());
    }

    private static synchronized KeyCounter snapshot() {
        if (allItems == null) {
            KeyCounter counter = new KeyCounter();
            for (Item item : ForgeRegistries.ITEMS) {
                AEItemKey key = AEItemKey.of(item);
                if (key != null) {
                    counter.add(key, AMOUNT);
                }
            }
            allItems = counter;
        }
        return allItems;
    }
}
