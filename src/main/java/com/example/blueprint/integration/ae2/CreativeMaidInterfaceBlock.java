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
 * 创造女仆接口：把"取之不尽的物品来源"做成一个方块。
 * <p>
 * 女仆终端对外代表的是"整张 ME 网络"，这个方块恰恰相反——它代表的是**自己**，
 * 一个什么都有、要多少有多少的库存。两条路都通向它：
 * <ul>
 *   <li><b>不接网络也能用</b>：方块自身往外暴露 ME 存储能力，绑定书指过来，
 *       女仆就能拿到任何材料。这是"女仆专属创造物品栏"的用法；</li>
 *   <li><b>接上网络就全网都能取</b>：方块把这份库存挂到网格上，
 *       于是在任意终端里都能看到并取出游戏里的所有物品。</li>
 * </ul>
 * 右键打开的是 AE2 官方终端界面，所以在网络上看到的是全网库存，
 * 没接网络时看到的就是这份创造库存。
 */
public class CreativeMaidInterfaceBlock extends Block implements EntityBlock {

    public CreativeMaidInterfaceBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeMaidInterfaceBlockEntity(pos, state);
    }

    /**
     * 只在服务端 tick：ME 网络是纯服务端的概念，客户端没有网格。
     * 这个 ticker 的唯一职责，是给节点的延迟创建做个兜底（同女仆终端）。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof CreativeMaidInterfaceBlockEntity creative) {
                creative.ensureNodeCreated();
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
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof CreativeMaidInterfaceBlockEntity creative)) {
            return InteractionResult.CONSUME;
        }

        // 和女仆终端不一样：这里**不拦**。终端离了网络就是一具空壳，
        // 而这个方块不接网络照样能用——女仆能直接取料，界面里也是它自己的创造库存。
        // 所以只把"AE 那一半现在不工作"提示一下，界面照开
        if (!creative.isNetworkReady()) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.blueprint.creative_interface_offline"), true);
        }

        MenuOpener.open(MEStorageMenu.TYPE, serverPlayer, MenuLocators.forBlockEntity(creative));
        return InteractionResult.CONSUME;
    }
}
