package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.IConfigManager;
import appeng.capabilities.Capabilities;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.common.MEStorageMenu;
import appeng.util.ConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 女仆终端的方块实体，同时扮演三个角色：
 * <ul>
 *   <li>{@link IInWorldGridNodeHost} —— 让自己成为 ME 网络里的一个节点；</li>
 *   <li>{@link ITerminalHost} —— 满足 AE2 终端界面的宿主契约，界面直接复用官方的；</li>
 *   <li>{@link IEnergySource} —— 从所在网络取电，供界面显示供电状态。</li>
 * </ul>
 * 另外还对外暴露标准 ME 存储能力，这样绑定书能直接把终端当作取料容器。
 */
public class MaidTerminalBlockEntity extends BlockEntity
        implements IInWorldGridNodeHost, ITerminalHost, IEnergySource, IActionHost {

    /** 升级卡槽位数，和普通终端保持一致 */
    private static final int UPGRADE_SLOTS = 3;
    /**
     * 待机功耗。
     * <p>
     * 与 AE2 的 ME 终端保持一致：官方终端其实**不耗电**——部件建立节点时压根没调用
     * {@code setIdlePowerUsage}，用的就是默认值 0。这里显式写出来，
     * 免得以後有人把它当成"随便填的数字"给调大。
     */
    private static final double IDLE_POWER = 0.0D;
    private static final String UPGRADE_TAG = "upgrades";

    /**
     * 没连上网络时的替身。
     * <p>
     * MEStorage 只有 {@code getDescription} 是抽象方法，其余全是默认的空实现，
     * 所以几行就能造出一个"什么都没有"的存储，免得界面拿到 null 直接崩掉。
     */
    private static final MEStorage EMPTY_STORAGE = new MEStorage() {
        @Override
        public Component getDescription() {
            return Component.empty();
        }
    };

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NodeListener.INSTANCE);
    private final IConfigManager configManager = new ConfigManager(this::setChanged);
    private IUpgradeInventory upgrades;
    private boolean nodeCreated;

    public MaidTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(Ae2TerminalRegistry.MAID_TERMINAL_BE.get(), pos, state);
        mainNode.setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(IDLE_POWER)
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setVisualRepresentation(new ItemStack(Ae2TerminalRegistry.MAID_TERMINAL_ITEM.get()));
    }

    // ------------------------------------------------------------------
    // 网格节点的生命周期
    // ------------------------------------------------------------------

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // 不能在这里直接 create：那会连带触发相邻区块的初始化，加载时序会乱。
        // AE2 专门提供了 onFirstTick 把创建推迟到真正的第一 tick。
        GridHelper.onFirstTick(this, MaidTerminalBlockEntity::ensureNodeCreated);
    }

    /**
     * 创建网格节点，只会真正执行一次。
     * <p>
     * 正常路径是 AE2 的 TickHandler 回调，方块自己的 ticker 也会调一遍作为兜底：
     * 万一 AE2 的调度机制有变，终端不至于一声不响地永远连不上网络。
     */
    public void ensureNodeCreated() {
        if (nodeCreated || level == null) {
            return;
        }
        nodeCreated = true;
        mainNode.create(level, worldPosition);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        mainNode.destroy();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // loadFromNBT 必须先于节点 create，否则网络归属、颜色这些会丢
        mainNode.loadFromNBT(tag);
        configManager.readFromNBT(tag);
        getUpgrades().readFromNBT(tag, UPGRADE_TAG);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        mainNode.saveToNBT(tag);
        configManager.writeToNBT(tag);
        getUpgrades().writeToNBT(tag, UPGRADE_TAG);
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    /**
     * 终端界面靠这个方法拿网络节点，用来把物品列表同步给客户端。
     * <p>
     * 少了它，界面照样能打开，但格子会是一片黑——什么都显示不出来。
     * 官方的终端部件没有这个毛病，是因为它继承的 AEBasePart 实现了这个接口。
     */
    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    // ------------------------------------------------------------------
    // 终端宿主
    // ------------------------------------------------------------------

    @Override
    public MEStorage getInventory() {
        MEStorage inventory = networkInventory();
        return inventory == null ? EMPTY_STORAGE : inventory;
    }

    @Nullable
    private MEStorage networkInventory() {
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return null;
        }
        IStorageService service = grid.getStorageService();
        return service == null ? null : service.getInventory();
    }

    @Override
    public IConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        if (upgrades == null) {
            upgrades = UpgradeInventories.forMachine(
                    Ae2TerminalRegistry.MAID_TERMINAL_ITEM.get(), UPGRADE_SLOTS, this::setChanged);
        }
        return upgrades;
    }

    /**
     * 从子菜单（比如合成数量选择）返回时，重新把终端界面打开。
     */
    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        if (player instanceof ServerPlayer serverPlayer) {
            MenuOpener.returnTo(MEStorageMenu.TYPE, serverPlayer, MenuLocators.forBlockEntity(this));
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(Ae2TerminalRegistry.MAID_TERMINAL_ITEM.get());
    }

    /**
     * 查一下终端为什么用不了，一切正常时返回 null。
     * <p>
     * AE2 的界面只会笼统地显示"离线"，玩家分不清是没接线、没电还是没频道。
     * 这里把几种情况拆开，好让人知道该去修哪一样。
     */
    @Nullable
    public Component diagnose() {
        if (!nodeCreated || !mainNode.isReady()) {
            return Component.translatable("message.blueprint.terminal_not_ready");
        }
        IGridNode node = mainNode.getNode();
        if (node == null) {
            return Component.translatable("message.blueprint.terminal_not_ready");
        }
        // 六个方向一个 AE 邻居都没有，那它只是孤零零地摆在地上，
        // 这时候网格虽然存在，却是个只装了自己的空网，报"没电"会把人带偏
        if (node.getConnectedSides().isEmpty()) {
            return Component.translatable("message.blueprint.terminal_not_connected");
        }
        IGrid grid = node.getGrid();
        if (grid == null) {
            return Component.translatable("message.blueprint.terminal_no_network");
        }
        if (!mainNode.isPowered()) {
            return Component.translatable("message.blueprint.terminal_no_power");
        }
        if (!node.meetsChannelRequirements()) {
            return Component.translatable("message.blueprint.terminal_no_channel");
        }
        return null;
    }

    @Override
    public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return 0;
        }
        IEnergyService service = grid.getEnergyService();
        return service == null ? 0 : service.extractAEPower(amount, mode, multiplier);
    }

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        // 把整个网络的库存对外暴露成标准 ME 存储能力。
        // 绑定书就是靠这一条把终端当成普通容器来取料的。
        if (cap == Capabilities.STORAGE) {
            return LazyOptional.of(this::getInventory).cast();
        }
        // 再以能力的形式宣告"我是网格节点宿主"。我们自己实现了 IInWorldGridNodeHost，
        // AE2 通常能直接认出来，但补上这一条能确保那些只查能力的连接逻辑也发现得了它。
        if (cap == Capabilities.IN_WORLD_GRID_NODE_HOST) {
            return LazyOptional.of(() -> this).cast();
        }
        return super.getCapability(cap, side);
    }

    /** 节点状态一变就让方块实体标记为待保存，免得改动在重载后丢失 */
    private enum NodeListener implements IGridNodeListener<MaidTerminalBlockEntity> {
        INSTANCE;

        @Override
        public void onSaveChanges(MaidTerminalBlockEntity nodeOwner, IGridNode node) {
            nodeOwner.setChanged();
        }
    }
}
