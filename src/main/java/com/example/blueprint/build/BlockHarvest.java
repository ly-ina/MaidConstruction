package com.example.blueprint.build;

import com.example.blueprint.BlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "她拿什么拆掉一块挡路的方块"——女仆的默认挖掘配置。
 * <p>
 * 规则只有两条，都很朴素：
 * <ol>
 *   <li><b>等级一律按下界合金算</b>（原版最高那一档）。方块认哪一类工具看它身上的
 *       {@code mineable_with_*} 标签——原版和模组都用这套标签声明工具类别——
 *       按类别给一把下界合金的同款。所以石头给镐、木头给斧、土给锹。</li>
 *   <li><b>她不会为了某一块去翻工具</b>。剪刀、剑这类特殊家伙不在她的行李里：
 *       草和树叶要剪刀才有收成，她就空着手去拆，收不到什么是意料之中。
 *       主人真想要那些东西，自己动手就是了——这是刻意的分工，不是她偷懒。</li>
 * </ol>
 * <p>
 * "挖不挖得动"直接沿用原版那句判据（{@code requiresCorrectToolForDrops} 配
 * {@link ItemStack#isCorrectToolForDrops}）。后者在 Forge 里会转问
 * {@link net.minecraftforge.common.TierSortingRegistry}，于是**模组加进来的、
 * 比下界合金更高的等级**（或者别的工具类别）也能如实认出来，
 * 不必自己维护一张"谁比谁硬"的表——那种表迟早会和模组对不上。
 */
public final class BlockHarvest {

    /**
     * "这块她可以拆"的白名单标签：{@code blueprint:maid_clearable}。
     * <p>
     * 默认是空的（模组自己带了一份空标签，数据包往上一加就生效）。它只解决一种情况：
     * <b>方块本身不可破坏，但某个模组的工具能破它</b>。她永远只带自己那套家伙、
     * 不会去用别的工具（见类注释），所以这种方块她只能干看着——除非有谁替主人说一句
     * "这块她可以拆"。数据包里加一行即可：
     * <pre>
     * {
     *   "values": ["some_mod:breakable_bedrock"]
     * }
     * </pre>
     * 判据排在最前面：进了这个标签就是**明确授权**，硬度多少、要不要工具都不再过问。
     */
    public static final TagKey<Block> MAID_CLEARABLE =
            BlockTags.create(new ResourceLocation(BlueprintMod.MOD_ID, "maid_clearable"));

    private BlockHarvest() {
    }

    /** 她拆这块方块时手上拿的家伙；方块不认工具（徒手就能拆）时返回空手 */
    public static ItemStack toolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return new ItemStack(Items.NETHERITE_PICKAXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return new ItemStack(Items.NETHERITE_AXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return new ItemStack(Items.NETHERITE_SHOVEL);
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return new ItemStack(Items.NETHERITE_HOE);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 这一格她动得了吗。说"不"的两种情况：
     * <ul>
     *   <li><b>谁也拆不掉的</b>：挖掘耗时是负数的那种（基岩、屏障、命令方块）。
     *       判据和玩家手里那把工具用的是同一条——"挖掉它要多久小于零"——
     *       所以模组里那些"不可破坏"的方块也一并管住；</li>
     *   <li><b>要她没有的工具的</b>：等级高过下界合金，或者压根是别的类别。</li>
     * </ul>
     * <p>
     * 说"不"的意思**不是**"拆得掉但没掉落"——那种（草、树叶）照拆不误、只是收不到东西。
     * 这里的"不"是**别动它**：她不是玩家，没有"消除方块"这种权力，
     * 硬盖下去的后果是把基岩这类东西从世界里删掉。
     * <p>
     * <b>这里判的是"方块当下在这个世界里硬不硬"，不看名字。</b>所以模组要是把基岩换成
     * 它自己那种"能挖的基岩"、或者改了它的硬度，她立刻就能拆——不需要任何改动。
     * 反过来，模组如果只是给**工具**加特技（方块本身还是不可破坏），她这条路走不通：
     * 她不会去用那件工具，于是方块留着不动、说一声、算进"还剩几块放不下"。
     * 主人自己敲掉那一块之后，下一轮重扫她就会照蓝图补上。
     * 真想让某块"不可破坏但她的工具能破"的方块也归她拆，把它加进
     * {@link #MAID_CLEARABLE} 即可。
     */
    public static boolean canHarvest(BlockGetter level, BlockPos pos, BlockState state, ItemStack tool) {
        if (state.is(MAID_CLEARABLE)) {
            return true; // 主人（或模组）明确说过这块能拆，不再过问硬度和工具
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
    }
}
