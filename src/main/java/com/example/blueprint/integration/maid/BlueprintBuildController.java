package com.example.blueprint.integration.maid;

import com.example.blueprint.build.BuildSession;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
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
    private static final int CONTAINER_SEARCH_RADIUS = 10;
    private static final int CONTAINER_SEARCH_HEIGHT = 4;
    /** 连续走这么多 tick 还没到就认为过不去（约 6 秒） */
    private static final int MOVE_TIMEOUT = 120;
    /** 施工期间每隔多久重扫一遍（约 5 秒），用来发现中途被拆掉的方块 */
    private static final int RESCAN_INTERVAL = 100;
    /** 一趟最多从容器里搬多少种材料 */
    private static final int MAX_PULL_SLOTS = 8;
    /** 容器开合动画持续多少 tick */
    private static final int CONTAINER_ANIMATION_TICKS = 30;
    /** 同一类提示的最小间隔，别把聊天栏刷满 */
    private static final long MESSAGE_COOLDOWN_MS = 30_000L;

    private BuildSession session;
    private Map<Item, Integer> bill = Map.of();
    private UUID activeId;
    private BlockPos activeAnchor;
    private Rotation activeRotation = Rotation.NONE;
    private State state = State.MOVE_TO_SPOT;
    /** 女仆的施工站位，站定后不再挪窝 */
    private BlockPos standSpot;
    private BlockPos fetchTarget;
    private BlockPos moveTarget;
    private int moveTicks = 0;
    private int cooldown = 0;
    private int rescanTimer = RESCAN_INTERVAL;
    private long lastMessageAt = 0;
    /** 正在播放开合动画的容器，以及剩余时间 */
    private BlockPos openContainerPos;
    private int openContainerTimer = 0;
    /**
     * 开工前女仆原本的待命状态，完工后要还回去。
     * null 表示还没记录过。
     */
    private Boolean homeModeBeforeWork;

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
            // 建完了，交还待命状态，女仆自己就会回去找主人
            setWorkingHomeMode(maid, false);
            onCompleted(level, maid, stack);
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
        if (working) {
            if (homeModeBeforeWork == null) {
                homeModeBeforeWork = maid.isHomeModeEnable();
            }
            if (!maid.isHomeModeEnable()) {
                maid.setHomeModeEnable(true);
            }
        } else if (homeModeBeforeWork != null) {
            maid.setHomeModeEnable(homeModeBeforeWork);
            homeModeBeforeWork = null;
        }
    }

    private void reset() {
        session = null;
        bill = Map.of();
        fetchTarget = null;
        moveTarget = null;
        standSpot = null;
        state = State.MOVE_TO_SPOT;
        cooldown = 0;
        rescanTimer = RESCAN_INTERVAL;
    }

    private void onCompleted(ServerLevel level, EntityMaid maid, ItemStack stack) {
        // 完工标记写在蓝图上，所以"施工完毕"只会播报一次
        if (!BlueprintItem.isCompleted(stack)) {
            BlueprintItem.setCompleted(stack, true);
            notify(level, maid, "message.blueprint.maid_build_done");
        }
        // 建完就撒手，不再重复扫描工地。
        // 要重新施工的话，重新定位或改朝向会清掉完工标记，女仆就会重新开工。
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

        // 一块都没放下去，基本就是没材料了，去箱子取
        BlockPos container = findContainer(level, maid);
        if (container == null) {
            cooldown = NO_SOURCE_COOLDOWN;
            notify(level, maid, "message.blueprint.maid_no_material");
            return;
        }
        fetchTarget = container;
        state = State.FETCH;
    }

    private void tickFetch(ServerLevel level, EntityMaid maid) {
        if (fetchTarget == null) {
            state = State.MOVE_TO_SPOT;
            return;
        }

        double cx = fetchTarget.getX() + 0.5D;
        double cy = fetchTarget.getY() + 0.5D;
        double cz = fetchTarget.getZ() + 0.5D;

        if (maid.distanceToSqr(cx, cy, cz) > CONTAINER_DISTANCE_SQR) {
            if (!moveTowards(level, maid, cx, cy, cz)) {
                // 这个箱子过不去，换一个
                fetchTarget = null;
                state = State.MOVE_TO_SPOT;
                cooldown = FETCH_COOLDOWN;
            }
            return;
        }

        pullFromContainer(level, maid, fetchTarget);
        fetchTarget = null;
        // 取完料回站位接着干
        state = State.MOVE_TO_SPOT;
        cooldown = FETCH_COOLDOWN;
    }

    /**
     * 让女仆走向目标，返回 true 表示还在路上，false 表示走不过去。
     */
    private boolean moveTowards(ServerLevel level, EntityMaid maid, double x, double y, double z) {
        BlockPos target = BlockPos.containing(x, y, z);
        if (!target.equals(moveTarget)) {
            moveTarget = target;
            moveTicks = 0;
        }
        if (++moveTicks > MOVE_TIMEOUT) {
            moveTicks = 0;
            moveTarget = null;
            return false;
        }
        if (maid.getNavigation().isDone() || maid.getNavigation().getPath() == null) {
            maid.getNavigation().moveTo(x, y, z, 1.0D);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 取料
    // ------------------------------------------------------------------

    private BlockPos findContainer(ServerLevel level, EntityMaid maid) {
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
        return best;
    }

    private boolean hasWantedItem(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && bill.containsKey(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private void pullFromContainer(ServerLevel level, EntityMaid maid, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        // 开箱动画 + 伸手取料的动作
        setContainerOpen(level, pos, true);
        openContainerPos = pos;
        openContainerTimer = CONTAINER_ANIMATION_TICKS;
        maid.swing(InteractionHand.MAIN_HAND);

        blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(source -> {
            IItemHandler backpack = new MaidItemSource(maid).getBackpack();
            int pulled = 0;

            // 一趟多搬几种材料，省得女仆为了每种方块来回跑
            for (int slot = 0; slot < source.getSlots() && pulled < MAX_PULL_SLOTS; slot++) {
                ItemStack inSlot = source.getStackInSlot(slot);
                if (inSlot.isEmpty() || !bill.containsKey(inSlot.getItem())) {
                    continue;
                }

                ItemStack extracted = source.extractItem(slot, Math.min(64, inSlot.getCount()), false);
                if (extracted.isEmpty()) {
                    continue;
                }

                if (backpack != null) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(backpack, extracted, false);
                    if (!remainder.isEmpty()) {
                        // 背包装不下就还回去，绝不吞玩家的东西
                        source.insertItem(slot, remainder, false);
                        return;
                    }
                } else {
                    ItemStack offHand = maid.getOffhandItem();
                    if (offHand.isEmpty()) {
                        maid.setItemInHand(InteractionHand.OFF_HAND, extracted);
                    } else {
                        source.insertItem(slot, extracted, false);
                        return;
                    }
                }
                pulled++;
            }
        });
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
        fetchTarget = null;
        moveTarget = null;
        cooldown = 0;
        state = State.MOVE_TO_SPOT;
    }

    /**
     * 女仆身上的蓝图：先主手，再副手，最后翻一遍背包。
     * 只认主手是不行的——很多玩家是把蓝图直接塞进女仆背包的。
     */
    private static ItemStack findBlueprint(EntityMaid maid) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof BlueprintItem) {
            return mainHand;
        }
        ItemStack offHand = maid.getOffhandItem();
        if (offHand.getItem() instanceof BlueprintItem) {
            return offHand;
        }
        IItemHandler backpack = maid.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (backpack != null) {
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.getItem() instanceof BlueprintItem) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private void notify(ServerLevel level, EntityMaid maid, String translationKey) {
        long now = System.currentTimeMillis();
        if (now - lastMessageAt < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessageAt = now;

        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby != null) {
            nearby.sendSystemMessage(Component.translatable(translationKey));
        }
    }
}
