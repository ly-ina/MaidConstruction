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
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
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
 * 创造女仆接口的方块实体。
 * <p>
 * 结构上跟 {@link MaidTerminalBlockEntity} 是同一套：网格节点宿主 + 终端宿主 +
 * 能量源，右键复用 AE2 官方终端界面。差别在存储那一环——
 * <ul>
 *   <li>{@link IStorageProvider}：把 {@link InfiniteItemStorage} 挂到网格上，
 *       整张网络因此"什么都能取"；</li>
 *   <li>对外暴露的 ME 存储能力：**永远**是这份创造库存，与网络无关。</li>
 * </ul>
 */
public class CreativeMaidInterfaceBlockEntity extends BlockEntity
        implements IInWorldGridNodeHost, IStorageProvider, ITerminalHost, IEnergySource, IActionHost {

    /** 升级卡槽位数，和普通终端保持一致 */
    private static final int UPGRADE_SLOTS = 3;
    /** 待机功耗。与女仆终端、AE2 官方终端一致：终端本身不耗电 */
    private static final double IDLE_POWER = 0.0D;
    private static final String UPGRADE_TAG = "upgrades";

    /**
     * 直接暴露给女仆的那一份：取之不尽，且**收下**她还不回来的剩料。
     * <p>
     * 它不依赖任何网络，所以绑定书一指过来就能用，跟方块接没接网、通没通电无关。
     */
    private final MEStorage maidStorage = InfiniteItemStorage.forMaid();

    /**
     * 挂到网格上的那一份：同样取之不尽，但**拒收**。
     * <p>
     * 两者分开的原因见 {@link InfiniteItemStorage#forGrid()}——挂在网络上的那份
     * 一旦收下，玩家往终端里放的东西就会凭空消失。
     */
    private final MEStorage gridStorage = InfiniteItemStorage.forGrid();

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NodeListener.INSTANCE);
    private final IConfigManager configManager = new ConfigManager(this::setChanged);
    private IUpgradeInventory upgrades;
    private boolean nodeCreated;

    public CreativeMaidInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(Ae2TerminalRegistry.CREATIVE_MAID_INTERFACE_BE.get(), pos, state);
        // addService 必须在节点 create 之前调用：它是"这个节点能对外提供什么服务"的登记，
        // 等网格建起来再补登记，存储就挂不上去了
        mainNode.addService(IStorageProvider.class, this)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(IDLE_POWER)
                .setInWorldNode(true)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setVisualRepresentation(
                        new ItemStack(Ae2TerminalRegistry.CREATIVE_MAID_INTERFACE_ITEM.get()));
    }

    // ------------------------------------------------------------------
    // 网格节点的生命周期（与女仆终端一致）
    // ------------------------------------------------------------------

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // 不能在这里直接 create：那会连带触发相邻区块的初始化，加载时序会乱。
        // AE2 专门提供了 onFirstTick 把创建推迟到真正的第一 tick。
        GridHelper.onFirstTick(this, CreativeMaidInterfaceBlockEntity::ensureNodeCreated);
    }

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

    /** 终端界面靠这个方法拿网络节点，用来把物品列表同步给客户端 */
    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    // ------------------------------------------------------------------
    // 挂进网格的创造库存
    // ------------------------------------------------------------------

    @Override
    public void mountInventories(IStorageMounts mounts) {
        // 用默认优先级：这是创造方块，没必要去抢在别人的存储前面
        mounts.mount(gridStorage);
    }

    /**
     * AE 侧现在能不能用（接了网络、通了电、分到了频道）。
     * <p>
     * 只用来给玩家提示，**不影响女仆取料**——那一条走的是方块自己的能力，
     * 跟网络通不通没有关系。
     */
    public boolean isNetworkReady() {
        if (!nodeCreated || !mainNode.isReady()) {
            return false;
        }
        IGridNode node = mainNode.getNode();
        return node != null
                && !node.getConnectedSides().isEmpty()
                && mainNode.isPowered()
                && node.meetsChannelRequirements();
    }

    // ------------------------------------------------------------------
    // 终端宿主
    // ------------------------------------------------------------------

    @Override
    public MEStorage getInventory() {
        // 界面里优先看整张网络的库存（其中已经包含挂上去的创造库存），
        // 没接网络就退回女仆那一份——所以这个界面永远不可能是空的
        MEStorage network = networkInventory();
        return network != null ? network : maidStorage;
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
                    Ae2TerminalRegistry.CREATIVE_MAID_INTERFACE_ITEM.get(), UPGRADE_SLOTS, this::setChanged);
        }
        return upgrades;
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        if (player instanceof ServerPlayer serverPlayer) {
            MenuOpener.returnTo(MEStorageMenu.TYPE, serverPlayer, MenuLocators.forBlockEntity(this));
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(Ae2TerminalRegistry.CREATIVE_MAID_INTERFACE_ITEM.get());
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
        // 这一条是女仆取料用的：**永远**给这份创造库存，不去问网络。
        // 女仆要的保证是"什么材料都拿得到"，而这个保证不该取决于
        // 方块有没有接到网络、有没有通电、AE2 的挂载有没有跑完。
        if (cap == Capabilities.STORAGE) {
            return LazyOptional.of(() -> maidStorage).cast();
        }
        // 同时以能力的形式宣告"我是网格节点宿主"
        if (cap == Capabilities.IN_WORLD_GRID_NODE_HOST) {
            return LazyOptional.of(() -> this).cast();
        }
        return super.getCapability(cap, side);
    }

    /** 节点状态一变就让方块实体标记为待保存，免得改动在重载后丢失 */
    private enum NodeListener implements IGridNodeListener<CreativeMaidInterfaceBlockEntity> {
        INSTANCE;

        @Override
        public void onSaveChanges(CreativeMaidInterfaceBlockEntity nodeOwner, IGridNode node) {
            nodeOwner.setChanged();
        }
    }
}
