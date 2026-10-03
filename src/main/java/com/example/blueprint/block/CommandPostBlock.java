package com.example.blueprint.block;

import com.example.blueprint.client.BlueprintScreenOpener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * 指挥台：托管投影、指派女仆、看进度（见 DEVELOPER §12）。
 * <p>
 * 外观照原版讲台改的皮肤——它本来就该像个"摊开图纸的台子"。两个状态：
 * 朝向（{@link #FACING}），以及 {@link #LOADED}——有没有正在托管的投影，
 * 有的话整块面级全亮，隔着院子也看得出这台在干活。
 * <p>
 * 右键打开界面的入口和蓝图面板同一条路：客户端开界面、服务端把这块的状态发一份过去。
 * **没有做菜单（Menu）**：界面要的内容全在方块实体里，一个状态包比一对 Menu/Screen 简单得多，
 * 也不会因为"菜单没同步"而出现两个人同时改同一台指挥台。
 */
public class CommandPostBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 托管着投影没有：只影响外观，状态本身在方块实体里 */
    public static final BooleanProperty LOADED = BooleanProperty.create("loaded");

    public CommandPostBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LOADED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LOADED);
    }

    /** 正面朝着放它的玩家：台子上的"图纸面"对着主人，别放下去就背过去 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CommandPostBlockEntity(pos, state);
    }

    /**
     * 右键：打开指挥台界面。
     * <p>
     * 覆写的是 1.20.1 里被标了 {@code @Deprecated} 的那个重载——这一版没有提供替代写法，
     * 覆写它仍然是注册方块右键行为的标准做法（女仆终端那处同理，见 DEVELOPER §7.9）。
     */
    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 用 DistExecutor 包一层：服务端不会去加载客户端的界面类
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BlueprintScreenOpener.openCommandPost(pos));
            return InteractionResult.SUCCESS;
        }
        // 服务端：把这块的状态发一份给他。客户端那块方块实体未必是新的
        // （进世界、区块重载都会带一份默认值过来），右键这一刻补一次最省事
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof CommandPostBlockEntity post) {
            post.sendStateTo(serverPlayer);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
