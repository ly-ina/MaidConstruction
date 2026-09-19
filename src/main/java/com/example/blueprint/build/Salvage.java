package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 施工时被"顶掉"的方块的去处。
 * <p>
 * 建造说到底是"在这个位置放成这个方块"：位置上原本站着什么（草、树、别人搭的墙），
 * 都会被目标方块替换掉。这一步以前是 {@code level.setBlock} 直接覆盖，
 * 原方块连着它的掉落物一起凭空消失——玩家眼看着一片草地变成地基，什么也拿不回来。
 * <p>
 * 把"顶掉之后怎么收场"抽成这个接口，是因为答案只有女仆自己知道：先看她背包装不装得下，
 * 装不下再问身上的无线终端，最后才是丢在脚边。核心的建造逻辑不该掺和这些，
 * 它只要把"拆下来这么些东西"递出来就够了。
 */
public interface Salvage {

    /**
     * 收下一份拆下来的东西。
     * <p>
     * 传进来的每一份都**必须有个着落**：实现方要么塞进某个容器，要么丢在地上。
     * 默默丢掉是不行的——那就是物品蒸发，跟改动之前一样糟。
     */
    void collect(ServerLevel level, BlockPos pos, ItemStack stack);

    /**
     * 这块方块她**动不了**，位置只能原样留着：要她这一档没有的家伙
     * （模组加进来的更高挖掘等级，或者剪刀这类她压根不带在身上的工具）。
     * <p>
     * 和"拆得掉、只是没掉落"是两回事：草和树叶那种照拆不误，只是收不到东西；
     * 这种**不能硬盖**——盖下去就是把这个方块从世界里删掉，而她没有这种权力。
     * 说一声是为了让主人知道该补什么工具。
     */
    void cannotHarvest(ServerLevel level, BlockPos pos, BlockState state);

    /**
     * 这块方块**谁也拆不掉**：基岩、屏障、命令方块这类挖掘耗时是负数的。
     * <p>
     * 和 {@link #cannotHarvest} 分开是因为话不一样：那个是"给我对应的工具"，
     * 这个是"这东西本来就拿不走"。位置同样原样留着。
     */
    void unbreakable(ServerLevel level, BlockPos pos, BlockState state);
}
