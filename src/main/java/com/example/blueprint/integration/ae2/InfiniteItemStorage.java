package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
            boolean blocksOnly = BlueprintConfig.creativeInterfaceBlocksOnly();
            KeyCounter counter = new KeyCounter();
            int skipped = 0;
            for (Item item : ForgeRegistries.ITEMS) {
                // 默认只列**可放置的方块**：这个接口是给女仆备建材的，
                // 而物品/装备/材料那一堆在建造里既用不上，又是这份清单的大头——
                // 少掉它们，终端打开和排序都快得多（配置里改成 all 可以要回全物品）
                if (blocksOnly && !isPlaceableBlock(item)) {
                    continue;
                }
                AEItemKey key = AEItemKey.of(item);
                if (key == null) {
                    continue;
                }
                // **先替 AE2 问一次名字**。
                // 有模组的物品在"不带数据、默认形态"的栈上算名字会抛异常——
                // irons_spells_js 的 CustomSpellBook 就是（它假设法术容器一定在）。
                // 而 AE2 的终端**排序时**正要对清单里的每一种问一遍显示名
                // （KeySorters → AEKey.getDisplayName），问到这种就炸，
                // 而且是**渲染界面时**崩客户端——玩家只看见"一开创造接口就崩"。
                // 所以这种物品干脆不列：它本来也不是能从创造接口正常取到的东西
                try {
                    new ItemStack(item).getHoverName();
                } catch (Throwable t) {
                    skipped++;
                    continue;
                }
                counter.add(key, AMOUNT);
            }
            allItems = counter;
            BlueprintMod.LOGGER.info("创造女仆接口向 ME 网络申报 {} 种{}（跳过了 {} 种算不出显示名的）",
                    counter.size(), blocksOnly ? "可放置的方块" : "物品", skipped);
            if (skipped > 0) {
                BlueprintMod.LOGGER.warn("有 {} 种东西算不出显示名（多半是别的模组的物品在默认形态下就抛异常），"
                                + "创造女仆接口已跳过它们——列进网络只会让 AE2 终端排序时崩溃",
                        skipped);
            }
        }
        return allItems;
    }

    /**
     * 这个物品是"能放下去的方块"吗。
     * <p>
     * 判据是 {@code BlockItem} 加上"对应的方块不是空气"：有些方块物品（比如早期版本里
     * 那些占位用的）对不上一个真方块，把它们列进去，玩家取出来也是个放不下去的东西。
     */
    private static boolean isPlaceableBlock(Item item) {
        return item instanceof BlockItem && Block.byItem(item) != Blocks.AIR;
    }
}
