package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockContainerProvider;
import com.example.blueprint.build.BuildSession;
import com.example.blueprint.build.ItemProvider;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.item.BindingBookItem;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个女仆的施工状态机。
 * <p>
 * 流程很简单：走到结构旁边站定 -&gt; 站在原地逐块施工（带挥手和音效）-&gt;
 * 材料不够时去容器取一趟再回来 -&gt; 建完播报一次。
 * <p>
 * 施工范围不设限制，女仆站定后不再移动，所以既不需要垫脚也不需要搭桥。
 * 由 {@link MaidBuildTickHandler} 每 tick 驱动，不依赖车万女仆的 Brain。
 */
public class BlueprintBuildController {

    private enum State { MOVE_TO_SPOT, BUILD, FETCH }

    private static final int PLACE_COOLDOWN = 4;
    private static final int FETCH_COOLDOWN = 20;
    private static final int NO_SOURCE_COOLDOWN = 100;
    /** 走到站位多近算到岗 */
    private static final double ARRIVE_DISTANCE_SQR = 4.0D;
    /** 站位离结构最外围一圈往外留几格 */
    private static final int STAND_MARGIN = 2;
    /** 偏离站位超过这么远就算被别的 AI 拽走了，得回岗位 */
    private static final double LEAVE_SPOT_DISTANCE_SQR = 64.0D;
    private static final double CONTAINER_DISTANCE_SQR = 9.0D;
    /** 导航失败时的放弃门槛：只要没跑偏出这个范围，就就地把料取了 */
    private static final double ABORT_DISTANCE_SQR = 256.0D;
    /** 找落脚点时只看水平四个方向，上下两个方向站不住人 */
    private static final Direction[] HORIZONTAL_SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    /** 两次重新寻路之间至少隔这么多 tick，免得各路 AI 互相拉扯把女仆拽成原地打转 */
    private static final int REPATH_INTERVAL = 20;
    /**
     * 完工还料的总时长上限（约 30 秒）。
     * <p>
     * 还料卡住的话必须有个了结：收工那一步在还料之后，
     * 一直还不上就会让女仆永远停在"施工中"，待命状态也还不了原。
     */
    private static final int RETURN_TIMEOUT = 600;
    /**
     * 施工前的待命状态记在女仆自己的持久数据里。
     * <p>
     * 不用控制器字段来记，是因为女仆会随着区块卸载重载拿到一个新的控制器实例，
     * 字段跟着丢，完工后就再也还不回去了。
     */
    private static final String HOME_MODE_TAG = "BlueprintHomeModeBefore";
    private static final int CONTAINER_SEARCH_RADIUS = 10;
    private static final int CONTAINER_SEARCH_HEIGHT = 4;
    /** 连续走这么多 tick 还没到就认为过不去（约 6 秒） */
    private static final int MOVE_TIMEOUT = 120;
    /** 施工期间每隔多久重扫一遍（约 5 秒），用来发现中途被拆掉的方块 */
    private static final int RESCAN_INTERVAL = 100;
    /** 容器开合动画持续多少 tick */
    private static final int CONTAINER_ANIMATION_TICKS = 30;
    /** 同一类提示的最小间隔，别把聊天栏刷满 */
    private static final long MESSAGE_COOLDOWN_MS = 30_000L;
    /** 缺少材料时最多列出几种，免得聊天栏被刷屏 */
    private static final int MAX_REPORTED_MATERIALS = 5;

    private BuildSession session;
    /** 整座结构一共要多少材料。还料时用它判断"哪些是这次工程带来的" */
    private Map<Item, Integer> bill = Map.of();
    /** 这一趟实际要补的材料：已建好的部分不算，取料按这个来 */
    private Map<Item, Integer> pendingBill = Map.of();
    /** 再扣掉背包已有的之后，真正还要从容器里拿的量。用它判断值不值得跑一趟 */
    private Map<Item, Integer> shortfall = Map.of();
    /** 上次已经播报过的缺料清单。内容没变就说明是同一件事，不再重复弹 */
    @Nullable
    private Map<Item, Integer> lastReportedShortfall;
    private UUID activeId;
    private BlockPos activeAnchor;
    private Rotation activeRotation = Rotation.NONE;
    private State state = State.MOVE_TO_SPOT;
    /** 女仆的施工站位，站定后不再挪窝 */
    private BlockPos standSpot;
    /** 这一趟要去取料的来源。可能是身边的箱子，也可能是绑定书指定的远程仓库 */
    private ItemProvider fetchProvider;
    /** 完工后正在使用的还料目标 */
    private ItemProvider returnProvider;
    /** 完工后的还料流程是否已经走完，避免每 tick 重复尝试 */
    private boolean returnFinished;
    /** 还料已经耗掉多少 tick，用来兜底超时 */
    private int returnTicks;
    private BlockPos moveTarget;
    private int moveTicks = 0;
    private int repathCooldown = 0;
    private int cooldown = 0;
    private int rescanTimer = RESCAN_INTERVAL;
    private long lastMessageAt = 0;
    /** 正在播放开合动画的容器，以及剩余时间 */
    private BlockPos openContainerPos;
    private int openContainerTimer = 0;


    // ------------------------------------------------------------------
    // 每 tick 驱动
    // ------------------------------------------------------------------

    public void tick(ServerLevel level, EntityMaid maid) {
        tickOpenContainer(level);

        ItemStack stack = findBlueprint(maid);
        if (stack.isEmpty()) {
            // 没带蓝图就安静待着，不用刷屏提醒
            setWorkingHomeMode(maid, false);
            reset();
            return;
        }
        if (!BlueprintItem.isMaidBuildEnabled(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_forbidden");
            return;
        }
        if (!BlueprintItem.hasSchematic(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_empty_blueprint");
            return;
        }
        if (!BlueprintItem.hasAnchor(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_no_anchor");
            return;
        }

        refreshSession(level, maid, stack);
        if (session == null) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_no_schematic");
            return;
        }
        if (session.isFinished()) {
            // 建完了。先把背包里剩下的建造材料还回容器，还干净了再收工，
            // 免得女仆带着一兜子石头跟着主人到处跑
            if (!returnFinished && tickReturn(level, maid)) {
                return;
            }
            // 交还待命状态，女仆自己就会回去找主人
            setWorkingHomeMode(maid, false);
            onCompleted(level, maid, stack);
            return;
        }

        // 蓝图自己已经标着"完工"，那就别再往工地跑了。
        // 少了这一条，工地上的方块只要被拆掉几块，session 就不再是 finished，
        // 女仆会颠颠地跑过去补，补完又不满、不满又去……看起来就是没完没了地来回跑
        if (BlueprintItem.isCompleted(stack)) {
            setWorkingHomeMode(maid, false);
            return;
        }

        // 施工期间钉在岗位上，别往主人那边跑
        setWorkingHomeMode(maid, true);
        // 这里不能清完工标记：重扫后 session 是刚重建的，还没走过一遍，
        // isFinished() 自然是 false，此时清标记会导致每次重扫都重新播报一遍"施工完毕"。
        // 只在真的放下方块时才清（见 tickBuild）。
        //
        // 施工期间定期重扫：session 的游标只往前走，已经放好的方块要是被人拆了，
        // 光靠顺序推进是发现不了的，得从头再扫一遍才能补上。
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            rescan(level);
        }
        if (session == null) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_no_schematic");
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (state == State.FETCH) {
            tickFetch(level, maid);
        } else if (state == State.MOVE_TO_SPOT) {
            tickMoveToSpot(level, maid);
        } else {
            tickBuild(level, maid, stack);
        }
    }

    /**
     * 施工期间开启待命模式，完工后还原。
     * <p>
     * 车万女仆的 MaidFollowOwnerTask 会在离主人太远时把女仆拽回去，
     * 甚至直接传送走——而它内部会先判断 isHomeModeEnable()。
     * 所以施工时开着待命，她就不会中途跑掉；
     * 完工后关掉，她立刻恢复跟随（离得远的话 TLM 会自己把她传送回主人身边）。
     * <p>
     * 这里会记住开工前的原始状态，不会把玩家自己设的待命给覆盖掉。
     */
    private void setWorkingHomeMode(EntityMaid maid, boolean working) {
        CompoundTag data = maid.getPersistentData();
        if (working) {
            if (!data.contains(HOME_MODE_TAG)) {
                data.putBoolean(HOME_MODE_TAG, maid.isHomeModeEnable());
                BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 开工，记下原本的待命状态 = {}",
                        maid.getUUID(), data.getBoolean(HOME_MODE_TAG));
            }
            if (!maid.isHomeModeEnable()) {
                maid.setHomeModeEnable(true);
            }
        } else if (data.contains(HOME_MODE_TAG)) {
            boolean original = data.getBoolean(HOME_MODE_TAG);
            data.remove(HOME_MODE_TAG);
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 收工，待命状态还原为 {}",
                    maid.getUUID(), original);
            maid.setHomeModeEnable(original);
        }
    }

    private void reset() {
        session = null;
        bill = Map.of();
        pendingBill = Map.of();
        shortfall = Map.of();
        lastReportedShortfall = null;
        fetchProvider = null;
        returnProvider = null;
        returnFinished = false;
        returnTicks = 0;
        moveTarget = null;
        standSpot = null;
        state = State.MOVE_TO_SPOT;
        cooldown = 0;
        rescanTimer = RESCAN_INTERVAL;
    }

    /**
     * 女仆不再由本控制器驱动时调用（中途换工作、实体被移除等），
     * 把施工期间改过的东西一律还原。
     * <p>
     * 少了这一步，玩家给女仆换个任务，她的待命状态就被永久改掉了，
     * 而且看起来毫无缘由。
     */
    public void detach(EntityMaid maid) {
        setWorkingHomeMode(maid, false);
        reset();
    }

    private void onCompleted(ServerLevel level, EntityMaid maid, ItemStack stack) {
        // 完工标记写在蓝图上，所以"施工完毕"只会播报一次
        if (!BlueprintItem.isCompleted(stack)) {
            BlueprintItem.setCompleted(stack, true);
            notify(level, maid, "message.blueprint.maid_build_done");
        }
        // 建完就把蓝图收回背包。手腾出来之后，findBlueprint 自然会挑到
        // 背包里下一张还没建完的图，女仆接着干下一单，不需要玩家盯着换图。
        stashFinishedBlueprint(maid);
        // 建完就撒手，不再重复扫描工地。
        // 要重新施工的话，重新定位或改朝向会清掉完工标记，女仆就会重新开工。
    }

    /**
     * 把建完的蓝图从手上收回背包。
     * <p>
     * 手腾空之后，下一轮查找就会落到背包里那张还没建完的图上，
     * 女仆于是自己接着干下一单。
     */
    private void stashFinishedBlueprint(EntityMaid maid) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            return;
        }

        InteractionHand hand;
        if (maid.getMainHandItem().getItem() instanceof BlueprintItem) {
            hand = InteractionHand.MAIN_HAND;
        } else if (maid.getOffhandItem().getItem() instanceof BlueprintItem) {
            hand = InteractionHand.OFF_HAND;
        } else {
            // 已经在背包里了，不用动
            return;
        }

        ItemStack blueprint = maid.getItemInHand(hand);
        // 先模拟一次插入：背包塞不下就让它继续留在手上，绝不能把蓝图弄丢
        ItemStack probe = ItemHandlerHelper.insertItemStacked(backpack, blueprint.copy(), true);
        if (!probe.isEmpty()) {
            return;
        }

        ItemStack toStore = blueprint.copy();
        maid.setItemInHand(hand, ItemStack.EMPTY);
        ItemHandlerHelper.insertItemStacked(backpack, toStore, false);
    }

    /**
     * 从头重建施工进度。
     * <p>
     * 因为建造是幂等的（已经和目标状态一致的方块会被跳过），
     * 重建后会很快掠过已建好的部分，落到真正缺块的地方。
     * 只换 session，不动站位和其他状态，免得女仆来回跑。
     */
    private void rescan(ServerLevel level) {
        if (activeId == null) {
            return;
        }
        Schematic base = SchematicStorage.get(level).get(activeId);
        if (base == null) {
            return;
        }
        Schematic schematic = base.rotate(activeRotation);
        session = new BuildSession(schematic);
        bill = BuildSession.bill(schematic);
    }

    // ------------------------------------------------------------------
    // 站位
    // ------------------------------------------------------------------

    private void tickMoveToSpot(ServerLevel level, EntityMaid maid) {
        if (standSpot == null) {
            state = State.BUILD;
            return;
        }

        double x = standSpot.getX() + 0.5D;
        double y = standSpot.getY();
        double z = standSpot.getZ() + 0.5D;

        if (maid.distanceToSqr(x, y, z) <= ARRIVE_DISTANCE_SQR) {
            state = State.BUILD;
            return;
        }

        if (!moveTowards(level, maid, x, y, z)) {
            // 到不了站位也无所谓，站着不动照样能建
            state = State.BUILD;
        }
    }

    /**
     * 在结构最外围一圈之外一点找个落脚点。
     * <p>
     * 要求很简单：脚能着地就行（脚下实心、身体没被埋）。
     * 站在外圈之外，建造时才不会被自己正在放的方块埋住。
     */
    private BlockPos resolveStandSpot(ServerLevel level, BlockPos anchor, Vec3i size) {
        int minX = anchor.getX() - STAND_MARGIN;
        int maxX = anchor.getX() + size.getX() - 1 + STAND_MARGIN;
        int minZ = anchor.getZ() - STAND_MARGIN;
        int maxZ = anchor.getZ() + size.getZ() - 1 + STAND_MARGIN;

        for (BlockPos column : outerRing(minX, maxX, minZ, maxZ)) {
            BlockPos spot = findGroundSpot(level, column, anchor.getY());
            if (spot != null) {
                return spot;
            }
        }
        // 外圈全是悬空的（比如把建筑挂在半空），退回中心
        return new BlockPos(anchor.getX() + size.getX() / 2, anchor.getY(), anchor.getZ() + size.getZ() / 2);
    }

    /** 外圈上的柱子，y 先统一填 0，具体高度后面再找 */
    private List<BlockPos> outerRing(int minX, int maxX, int minZ, int maxZ) {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            ring.add(new BlockPos(x, 0, minZ));
            ring.add(new BlockPos(x, 0, maxZ));
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            ring.add(new BlockPos(minX, 0, z));
            ring.add(new BlockPos(maxX, 0, z));
        }
        return ring;
    }

    /** 在这根柱子上找脚能着地的高度，优先和结构底部齐平 */
    @Nullable
    private BlockPos findGroundSpot(ServerLevel level, BlockPos column, int baseY) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos candidate = new BlockPos(column.getX(), baseY + dy, column.getZ());
            if (canStandAt(level, candidate)) {
                return candidate;
            }
        }
        for (int dy = -1; dy >= -4; dy--) {
            BlockPos candidate = new BlockPos(column.getX(), baseY + dy, column.getZ());
            if (canStandAt(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        // 脚着地，且身体这一格没被埋
        return level.getBlockState(pos.below()).canOcclude()
                && !level.getBlockState(pos).canOcclude();
    }

    // ------------------------------------------------------------------
    // 施工
    // ------------------------------------------------------------------

    private void tickBuild(ServerLevel level, EntityMaid maid, ItemStack stack) {
        // 被别的 AI 拽离岗位了，先回去
        if (standSpot != null
                && maid.distanceToSqr(standSpot.getX() + 0.5D, standSpot.getY(), standSpot.getZ() + 0.5D)
                > LEAVE_SPOT_DISTANCE_SQR) {
            state = State.MOVE_TO_SPOT;
            return;
        }

        BlockPos target = session.peekNextTarget(level, activeAnchor);

        // 面朝正在建的位置，看起来像盯着活儿在干
        if (target != null) {
            maid.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        }

        BuildSession.StepResult result = session.step(level, activeAnchor, new MaidItemSource(maid), 1);

        if (result.placed() > 0) {
            // 真的动工了，说明这处工地还没完工
            if (BlueprintItem.isCompleted(stack)) {
                BlueprintItem.setCompleted(stack, false);
            }
            // 挥手 + 这个方块的放置音效
            maid.swing(InteractionHand.MAIN_HAND);
            playPlaceSound(level, result.lastPlaced());
            cooldown = PLACE_COOLDOWN;
            return;
        }
        if (result.finished()) {
            return;
        }

        // 一块都没放下去，基本就是没材料了。出发前重算一遍"还差多少"——
        // 已经建好的部分不该再要，否则每缺一次料都会把整座建筑的量重搬一遍
        pendingBill = session.remainingBill(level, activeAnchor);
        if (pendingBill.isEmpty()) {
            // 什么也不缺却一块也放不下，多半是别的原因卡住了（比如支撑还没就位）。
            // 这种时候去取料只会白跑一趟
            cooldown = NO_SOURCE_COOLDOWN;
            return;
        }

        // 再扣掉背包里已经有的。这一步不能省：决定"值不值得跑一趟"的是还差多少，
        // 不是清单上有多少——背包里躺着的那部分不该再算进去，
        // 否则容器里只有这些已有的东西时，女仆会白跑一趟，还报告说背包满了
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        shortfall = backpack == null
                ? pendingBill
                : ItemProvider.missingAmounts(backpack, pendingBill);
        if (shortfall.isEmpty()) {
            cooldown = NO_SOURCE_COOLDOWN;
            return;
        }

        ItemProvider provider = findProvider(level, maid);
        if (provider == null) {
            cooldown = NO_SOURCE_COOLDOWN;
            notifyMissingMaterials(level, maid);
            return;
        }
        fetchProvider = provider;
        state = State.FETCH;
        BlueprintMod.LOGGER.info("女仆 {} 出发去 {} 取料（来源：{}，缺口 {} 种）",
                maid.getUUID(), provider.interactPos(), provider.getClass().getSimpleName(),
                pendingBill.size());
    }

    private void tickFetch(ServerLevel level, EntityMaid maid) {
        if (fetchProvider == null) {
            state = State.MOVE_TO_SPOT;
            return;
        }

        BlockPos container = fetchProvider.interactPos();
        double cx = container.getX() + 0.5D;
        double cz = container.getZ() + 0.5D;

        // 到没到只看水平距离：容器常常比女仆高一格或低一格
        // （放在台子上、或者摆在地板下），把垂直差算进去，
        // "明明就站在箱子边上"会被判成还没走到
        double horizontalSqr = maid.distanceToSqr(cx, maid.getY() + 0.5D, cz);

        if (horizontalSqr > CONTAINER_DISTANCE_SQR) {
            if (moveTowardsContainer(level, maid, container)) {
                return;
            }
            // 导航走不过去。但取料靠的是直接访问容器的物品栏，
            // 并不真的要求女仆站到跟前，所以只要没跑偏太远，
            // 就就地把料取了，免得她在障碍物外面反复折返
            if (horizontalSqr > ABORT_DISTANCE_SQR) {
                BlueprintMod.LOGGER.warn("女仆 {} 够不到取料目标 {}，相距约 {} 格，放弃这一趟",
                        maid.getUUID(), container, (int) Math.sqrt(horizontalSqr));
                fetchProvider = null;
                state = State.MOVE_TO_SPOT;
                cooldown = FETCH_COOLDOWN;
                return;
            }
        }

        // 到地方了就把导航停掉，否则她还会继续往箱子里挤，看着像在箱子上蹦跶
        maid.getNavigation().stop();

        pullFromProvider(level, maid, fetchProvider);
        fetchProvider = null;
        // 取完料回站位接着干
        state = State.MOVE_TO_SPOT;
        cooldown = FETCH_COOLDOWN;
    }

    /**
     * 让女仆走向目标，返回 true 表示还在路上，false 表示走不过去。
     * <p>
     * 重新寻路做了节流：女仆的其他 AI（待命、跟随之类）会时不时把 navigation 清掉，
     * 一发现路径没了就立刻重算的话，她会不停地在原地重新起步，
     * 看起来就是在打转而不是在赶路。
     */
    private boolean moveTowards(ServerLevel level, EntityMaid maid, double x, double y, double z) {
        BlockPos target = BlockPos.containing(x, y, z);
        if (!target.equals(moveTarget)) {
            moveTarget = target;
            moveTicks = 0;
            repathCooldown = 0;
        }
        if (++moveTicks > MOVE_TIMEOUT) {
            moveTicks = 0;
            moveTarget = null;
            repathCooldown = 0;
            return false;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (maid.getNavigation().isDone() || maid.getNavigation().getPath() == null) {
            repathCooldown = REPATH_INTERVAL;
            maid.getNavigation().moveTo(x, y, z, 1.0D);
        }
        return true;
    }

    /**
     * 走向容器旁边的落脚点，而不是容器本身。
     * <p>
     * 导航目标要是直接设成容器方块，那一格正站着箱子，女仆根本迈不进去，
     * 于是她会贴着箱子来回蹭、甚至往上跳——看起来特别傻。
     * 瞄准旁边的空位，她才会规规矩矩走到旁边站好。
     *
     * @return true 表示还在路上，false 表示走不过去
     */
    private boolean moveTowardsContainer(ServerLevel level, EntityMaid maid, BlockPos container) {
        BlockPos stand = resolveStandNear(level, container);
        if (stand == null) {
            // 四周实在没有落脚的地方，只能退而求其次瞄准容器自己
            return moveTowards(level, maid,
                    container.getX() + 0.5D, container.getY() + 0.5D, container.getZ() + 0.5D);
        }
        // 落脚点的 y 用方块底部，和施工站位那边保持一致
        return moveTowards(level, maid, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
    }

    /**
     * 在容器四周找一个能站人的格子。
     * <p>
     * 只查水平四个方向，正上方和正下方都站不住人。
     * 先看同层，再看上一层——容器镶在台子里时，站到旁边地面上更自然。
     */
    @Nullable
    private BlockPos resolveStandNear(ServerLevel level, BlockPos container) {
        for (Direction direction : HORIZONTAL_SIDES) {
            BlockPos side = container.relative(direction);
            if (canStandAt(level, side)) {
                return side;
            }
            BlockPos above = side.above();
            if (canStandAt(level, above)) {
                return above;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 取料
    // ------------------------------------------------------------------

    /**
     * 找一个能取到料的来源。
     * <p>
     * 优先就近取材：身边半径内随便哪个容器有材料就够，省得女仆满基地跑。
     * 附近实在没有，才去翻绑定书——那是玩家特意指定的远程仓库，
     * 通常离得很远，所以只当兜底。
     */
    @Nullable
    private ItemProvider findProvider(ServerLevel level, EntityMaid maid) {
        ItemProvider nearby = findNearbyContainer(level, maid);
        if (nearby != null) {
            BlueprintMod.LOGGER.info("女仆 {} 就近取材：{}", maid.getUUID(), nearby.interactPos());
            return nearby;
        }
        ItemProvider bound = findBoundProvider(level, maid);
        if (bound != null) {
            BlueprintMod.LOGGER.info("女仆 {} 改用绑定书指定的目标：{}", maid.getUUID(), bound.interactPos());
        }
        return bound;
    }

    /** 扫一圈身边的普通容器，返回最近的那个装着所需材料的 */
    @Nullable
    private ItemProvider findNearbyContainer(ServerLevel level, EntityMaid maid) {
        BlockPos center = maid.blockPosition();
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - CONTAINER_SEARCH_HEIGHT);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + CONTAINER_SEARCH_HEIGHT);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CONTAINER_SEARCH_RADIUS, 0, -CONTAINER_SEARCH_RADIUS).atY(minY),
                center.offset(CONTAINER_SEARCH_RADIUS, 0, CONTAINER_SEARCH_RADIUS).atY(maxY))) {

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (handler == null || !hasWantedItem(handler)) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best == null ? null : new BlockContainerProvider(level, best);
    }

    /**
     * 照绑定书上的地址去远程取料。
     */
    @Nullable
    private ItemProvider findBoundProvider(ServerLevel level, EntityMaid maid) {
        ItemStack book = findBindingBook(maid);
        if (book.isEmpty()) {
            return null;
        }

        ResourceLocation dimension = BindingBookItem.getBoundDimension(book);
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            // 隔着维度走不过去。多半是玩家忘了，提示一句总比杵着不动强
            notify(level, maid, "message.blueprint.maid_bound_other_dimension");
            return null;
        }

        ItemProvider provider = createBoundProvider(level, maid);
        if (provider == null) {
            return null;
        }
        if (provider.hasAny(shortfall)) {
            return provider;
        }

        // 坐标还在，但里面已经没有需要的材料了
        notify(level, maid, "message.blueprint.maid_bound_empty");
        return null;
    }

    /**
     * 在绑定书指的位置造一个来源。
     * <p>
     * 同一个坐标可能是普通箱子，也可能接着 ME 网络，所以两种都试：
     * 先问 AE2（没装 AE2 时会直接返回 null），不行再按普通容器处理。
     * <p>
     * 这里刻意不判断里面有没有料——取料和还料都要用这个坐标，
     * 区别只是调用方要不要再检查一次内容，所以"造"和"挑"分开。
     */
    @Nullable
    private ItemProvider createBoundProvider(ServerLevel level, EntityMaid maid) {
        ItemStack book = findBindingBook(maid);
        if (book.isEmpty()) {
            return null;
        }

        BlockPos pos = BindingBookItem.getBoundPos(book);
        if (pos == null) {
            return null;
        }

        ResourceLocation dimension = BindingBookItem.getBoundDimension(book);
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            return null;
        }

        ItemProvider ae2 = Ae2Compat.createProvider(level, pos);
        return ae2 != null ? ae2 : new BlockContainerProvider(level, pos);
    }

    private boolean hasWantedItem(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && shortfall.containsKey(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把材料从来源搬进女仆背包。
     * <p>
     * 具体怎么搬交给 {@link ItemProvider} 自己决定：普通容器是逐槽抽取，
     * ME 网络得"先模拟再提取"，两者差得很远，主流程不该掺和进去。
     */
    private void pullFromProvider(ServerLevel level, EntityMaid maid, ItemProvider provider) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            notify(level, maid, "message.blueprint.maid_no_backpack");
            return;
        }

        BlockPos pos = provider.interactPos();
        if (level.getBlockEntity(pos) != null) {
            // 开合动画走原版 blockEvent 通道，只有箱子这类容器会响应；
            // ME 网络的机器对 id=1 没反应，调用它也无害。
            setContainerOpen(level, pos, true);
            openContainerPos = pos;
            openContainerTimer = CONTAINER_ANIMATION_TICKS;
        }
        maid.swing(InteractionHand.MAIN_HAND);

        // 上限按背包实际空位来，不再写死一个种类数：
        // 背包越大（装了升级）越该一趟搬够，否则材料种类一多，
        // 每趟只能带回固定几样，剩下的永远凑不齐
        int moved = provider.transferInto(backpack, pendingBill, Math.max(1, countEmptySlots(backpack)));
        if (moved == 0) {
            // 空手而归有两种原因，得分清楚：背包塞不下，或者这容器里压根没有还缺的那几样。
            // 提示写错会让玩家顺着错的线索去翻背包，而问题其实在别处
            boolean hasWanted = provider.hasAny(shortfall);
            BlueprintMod.LOGGER.warn("女仆 {} 在 {} 没取到材料（{}还缺的 {} 种，背包空余 {} 格）",
                    maid.getUUID(), pos, hasWanted ? "容器里有" : "容器里没有",
                    shortfall.size(), countEmptySlots(backpack));
            notify(level, maid, hasWanted
                    ? "message.blueprint.maid_backpack_full"
                    : "message.blueprint.maid_source_empty");
        } else {
            BlueprintMod.LOGGER.info("女仆 {} 从 {} 取到 {} 种材料", maid.getUUID(), pos, moved);
        }
    }

    /** 排查用：背包还剩几个空格，"取不到料"时一眼就能看出是不是塞满了 */
    private static int countEmptySlots(IItemHandler handler) {
        int empty = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    // ------------------------------------------------------------------
    // 完工后还料
    // ------------------------------------------------------------------

    /**
     * 把背包里剩下的建造材料还回容器。
     *
     * @return true 表示还没还完，下一 tick 接着来；false 表示不用还了
     */
    private boolean tickReturn(ServerLevel level, EntityMaid maid) {
        if (leftoverMaterials(maid).isEmpty()) {
            returnProvider = null;
            returnTicks = 0;
            returnFinished = true;
            return false;
        }

        // 兜底超时：还料要是卡住了（容器始终塞不下、或者来回走不到），
        // 也必须有个了结。收工那一步排在还料之后，一直还不上，
        // 女仆就会永远停在"施工中"，待命状态也还不回来
        if (++returnTicks > RETURN_TIMEOUT) {
            notify(level, maid, "message.blueprint.maid_return_failed");
            returnProvider = null;
            returnTicks = 0;
            returnFinished = true;
            return false;
        }

        if (returnProvider == null) {
            returnProvider = findReturnTarget(level, maid);
            if (returnProvider == null) {
                notify(level, maid, "message.blueprint.maid_return_no_target");
                returnFinished = true;
                return false;
            }
        }

        BlockPos container = returnProvider.interactPos();
        double cx = container.getX() + 0.5D;
        double cz = container.getZ() + 0.5D;
        double horizontalSqr = maid.distanceToSqr(cx, maid.getY() + 0.5D, cz);

        if (horizontalSqr > CONTAINER_DISTANCE_SQR) {
            if (moveTowardsContainer(level, maid, container)) {
                return true;
            }
            if (horizontalSqr > ABORT_DISTANCE_SQR) {
                notify(level, maid, "message.blueprint.maid_return_unreachable");
                returnProvider = null;
                returnFinished = true;
                return false;
            }
        }

        // 站定之后停掉导航，别让她继续往容器里钻
        maid.getNavigation().stop();

        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            returnFinished = true;
            return false;
        }

        int moved = returnProvider.acceptInto(backpack, bill);
        BlueprintMod.LOGGER.info("女仆 {} 向 {} 归还了 {} 种剩余材料", maid.getUUID(), container, moved);
        maid.swing(InteractionHand.MAIN_HAND);
        returnProvider = null;

        if (moved == 0) {
            // 一种都塞不回去（多半是容器满了），再试只会原地打转
            notify(level, maid, "message.blueprint.maid_return_failed");
            returnFinished = true;
            return false;
        }
        // 可能还有剩的，下一 tick 再来一趟
        return true;
    }

    /** 背包里属于建造清单的那部分材料，女仆自己的杂物不算 */
    private Map<Item, Integer> leftoverMaterials(EntityMaid maid) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            return Map.of();
        }
        Map<Item, Integer> leftover = new HashMap<>();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (!stack.isEmpty() && bill.containsKey(stack.getItem())) {
                leftover.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return leftover;
    }

    /**
     * 挑一个还料的地方：优先绑定书指定的容器——那本来就是玩家安排的仓库，
     * 退而求其次才用身边的箱子。
     */
    @Nullable
    private ItemProvider findReturnTarget(ServerLevel level, EntityMaid maid) {
        ItemProvider bound = createBoundProvider(level, maid);
        return bound != null ? bound : findNearbyAcceptor(level, maid);
    }

    /** 身边最近的一个标准容器，不管里面装的是什么 */
    @Nullable
    private ItemProvider findNearbyAcceptor(ServerLevel level, EntityMaid maid) {
        BlockPos center = maid.blockPosition();
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - CONTAINER_SEARCH_HEIGHT);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + CONTAINER_SEARCH_HEIGHT);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CONTAINER_SEARCH_RADIUS, 0, -CONTAINER_SEARCH_RADIUS).atY(minY),
                center.offset(CONTAINER_SEARCH_RADIUS, 0, CONTAINER_SEARCH_RADIUS).atY(maxY))) {

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null
                    || !blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best == null ? null : new BlockContainerProvider(level, best);
    }

    // ------------------------------------------------------------------
    // 动画与音效
    // ------------------------------------------------------------------

    /**
     * 让容器播放开合动画。
     * <p>
     * 走原版的 blockEvent 通道：箱子、陷阱箱、末影箱、桶这类容器
     * 的方块实体都会响应 id=1 的事件来开合盖子，而且事件会自动同步给客户端。
     */
    private void setContainerOpen(ServerLevel level, BlockPos pos, boolean open) {
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, open ? 1 : 0);
    }

    private void tickOpenContainer(ServerLevel level) {
        if (openContainerTimer > 0 && --openContainerTimer <= 0 && openContainerPos != null) {
            setContainerOpen(level, openContainerPos, false);
            openContainerPos = null;
        }
    }

    /** 播放方块放置音效，音量压低一点，免得连续施工时太吵 */
    private void playPlaceSound(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null) {
            return;
        }
        SoundType soundType = level.getBlockState(pos).getSoundType();
        level.playSound(null, pos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                soundType.getVolume() * 0.6F, soundType.getPitch());
    }

    // ------------------------------------------------------------------
    // 状态维护
    // ------------------------------------------------------------------

    private void refreshSession(ServerLevel level, EntityMaid maid, ItemStack stack) {
        UUID id = BlueprintItem.getSchematicId(stack);
        BlockPos anchor = BlueprintItem.getAnchor(stack);
        Rotation rotation = BlueprintItem.getRotation(stack);

        if (id == null || anchor == null) {
            return;
        }
        if (session != null && Objects.equals(id, activeId)
                && Objects.equals(anchor, activeAnchor)
                && rotation == activeRotation) {
            return;
        }

        Schematic base = SchematicStorage.get(level).get(id);
        if (base == null) {
            return;
        }

        // 女仆按蓝图当前朝向施工
        Schematic schematic = base.rotate(rotation);

        session = new BuildSession(schematic);
        bill = BuildSession.bill(schematic);
        activeId = id;
        activeAnchor = anchor;
        activeRotation = rotation;
        standSpot = resolveStandSpot(level, anchor, schematic.getSize());
        fetchProvider = null;
        returnProvider = null;
        returnFinished = false;
        moveTarget = null;
        cooldown = 0;
        state = State.MOVE_TO_SPOT;
    }

    /**
     * 女仆身上的蓝图：先主手，再副手，最后翻一遍背包。
     * <p>
     * 优先挑**还没建完**的那张。多张蓝图同时带在身上时（建完的会被自动收进背包），
     * 要是还抱着已完工的图不放，女仆就会对着它一直发呆，不会去干下一张。
     * 万一身上全是完工的，就随便返回一张，好让上层把"施工完毕"播报出去。
     */
    private static ItemStack findBlueprint(EntityMaid maid) {
        ItemStack finished = null;

        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof BlueprintItem) {
            if (!BlueprintItem.isCompleted(mainHand)) {
                return mainHand;
            }
            finished = mainHand;
        }

        ItemStack offHand = maid.getOffhandItem();
        if (offHand.getItem() instanceof BlueprintItem) {
            if (!BlueprintItem.isCompleted(offHand)) {
                return offHand;
            }
            if (finished == null) {
                finished = offHand;
            }
        }

        IItemHandler backpack = maid.getMaidInv();
        if (backpack != null) {
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (!(stack.getItem() instanceof BlueprintItem)) {
                    continue;
                }
                if (!BlueprintItem.isCompleted(stack)) {
                    return stack;
                }
                if (finished == null) {
                    finished = stack;
                }
            }
        }
        return finished == null ? ItemStack.EMPTY : finished;
    }

    /**
     * 找女仆身上的绑定书。
     * <p>
     * 顺序是主手、副手、饰品栏、背包。饰品栏排在背包前面，
     * 因为那才是这本书该待的地方，塞进背包只是权宜之计。
     */
    private static ItemStack findBindingBook(EntityMaid maid) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof BindingBookItem) {
            return mainHand;
        }
        ItemStack offHand = maid.getOffhandItem();
        if (offHand.getItem() instanceof BindingBookItem) {
            return offHand;
        }

        // 饰品栏本身就是个 ItemStackHandler，可以直接按槽位遍历
        BaubleItemHandler baubles = maid.getMaidBauble();
        if (baubles != null) {
            for (int i = 0; i < baubles.getSlots(); i++) {
                ItemStack stack = baubles.getStackInSlot(i);
                if (stack.getItem() instanceof BindingBookItem) {
                    return stack;
                }
            }
        }

        IItemHandler backpack = maid.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (backpack != null) {
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.getItem() instanceof BindingBookItem) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private void notify(ServerLevel level, EntityMaid maid, String translationKey, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastMessageAt < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessageAt = now;

        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby != null) {
            nearby.sendSystemMessage(Component.translatable(translationKey, args));
        }
    }

    /**
     * 找不到材料时，把缺哪些、各缺多少讲清楚。
     * <p>
     * 光说一句"找不到材料"，玩家还得自己拿着蓝图去对账。物品名用
     * {@link Component} 传而不是先转成字符串——那样会在服务端就固定成某种语言，
     * 客户端换成别的语言也翻不动了。
     */
    private void notifyMissingMaterials(ServerLevel level, EntityMaid maid) {
        // 附近没人就先不说，也别记成"已播报"——否则玩家赶回来时反而听不到
        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby == null) {
            return;
        }

        // 同一批缺料只说一次。女仆每隔几秒就会重试一遍，照实播报的话
        // 聊天栏会被同一句话刷满。等清单变了（又建了一部分、或者换了蓝图）再提醒。
        // 这里也不走 notify：那条路径有 30 秒冷却，会让这条重要提示被别的消息挤掉。
        if (shortfall.equals(lastReportedShortfall)) {
            return;
        }
        lastReportedShortfall = Map.copyOf(shortfall);

        if (shortfall.isEmpty()) {
            nearby.sendSystemMessage(Component.translatable("message.blueprint.maid_no_material"));
            return;
        }

        MutableComponent list = Component.empty();
        int shown = 0;
        for (Map.Entry<Item, Integer> entry : shortfall.entrySet()) {
            if (shown++ >= MAX_REPORTED_MATERIALS) {
                break;
            }
            if (shown > 1) {
                list.append(", ");
            }
            list.append(entry.getKey().getDescription())
                    .append(" x")
                    .append(String.valueOf(entry.getValue()));
        }
        if (shortfall.size() > MAX_REPORTED_MATERIALS) {
            list.append(Component.translatable("message.blueprint.maid_missing_more",
                    shortfall.size() - MAX_REPORTED_MATERIALS));
        }

        BlueprintMod.LOGGER.info("女仆 {} 缺少建造材料：{}", maid.getUUID(), shortfall);
        nearby.sendSystemMessage(Component.translatable("message.blueprint.maid_missing_materials", list));
    }
}
