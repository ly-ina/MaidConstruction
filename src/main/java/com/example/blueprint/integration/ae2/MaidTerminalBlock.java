package com.example.blueprint.integration.ae2;

import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.common.MEStorageMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * 女仆终端：六个面都是 ME 终端面板的方块。
 * <p>
 * 存在的理由是 AE2 自带的终端都是挂在线缆上的部件，而部件承载方块会把能力查询
 * 代理给部件本身，导致外部拿到的"存储"未必是整个网络的库存——绑定书因此认不出它。
 * 这个方块把终端做成一个规规矩矩的方块实体：自己就是网格节点宿主，
 * 自己暴露 MEStorage 能力，同时也照常复用 AE2 的终端界面。
 */
public class MaidTerminalBlock extends Block implements EntityBlock {

    public MaidTerminalBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MaidTerminalBlockEntity(pos, state);
    }

    /**
     * 只在服务端 tick——ME 网络是纯服务端的概念，客户端没有网格。
     * 这个 ticker 的唯一职责，是给节点的延迟创建做个兜底。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof MaidTerminalBlockEntity terminal) {
                terminal.ensureNodeCreated();
            }
        };
    }

    // Forge 把原版这个方法标成了废弃，但 1.20.1 里并没有提供替代重载，
    // 覆写它依旧是注册右键行为的标准做法
    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 客户端只负责反馈，真正的打开动作在服务端做
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof MaidTerminalBlockEntity terminal)) {
            return InteractionResult.CONSUME;
        }

        // 先查为什么用不了。AE2 的界面只会笼统显示"离线"，
        // 这里直接把原因讲清楚，省得玩家猜是没接电还是没频道
        Component problem = terminal.diagnose();
        if (problem != null) {
            serverPlayer.displayClientMessage(problem, true);
            return InteractionResult.CONSUME;
        }

        // 复用 AE2 自己的终端菜单：界面、同步、交互逻辑全是现成的，
        // 我们只需要把宿主对象准备好
        MenuOpener.open(MEStorageMenu.TYPE, serverPlayer, MenuLocators.forBlockEntity(terminal));
        return InteractionResult.CONSUME;
    }
}
