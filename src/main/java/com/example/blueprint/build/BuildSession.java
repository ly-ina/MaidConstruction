package com.example.blueprint.build;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次建造任务的可增量推进状态。
 * <p>
 * 放置顺序做了依赖排序：先放完整方块打底，再放半砖、火把这类依附物；
 * 第一轮放不下的会进入下一轮重试，这样火把等需要支撑的方块才能正确成型。
 * <p>
 * 整个流程是幂等的——已经和目标状态一致的方块会被跳过，
 * 因此女仆中途被打断、存档重载后重新开始也不会破坏已建成的部分。
 * <p>
 * 唯一会"破坏"世界的一步是放置本身：位置上原本站着什么，就会被目标方块顶掉。
 * 顶掉的东西交给 {@link Salvage} 处置（见 {@link #clearAt}），绝不无声销毁；
 * 而**她动不了的方块**（基岩这类谁也拆不掉的，或者要她没有的工具的）一律不碰，
 * 位置原样留着，最后算进"还剩几块放不下"。
 */
public class BuildSession {

    private static final int MAX_PASS = 3;

    private final Schematic schematic;
    /** 这座结构一共多少块。会话开始时定下来，当进度条的分母 */
    private final int total;
    private List<Schematic.BlockEntry> order;
    private List<Schematic.BlockEntry> deferred;
    private int cursor = 0;
    private int pass = 0;
    private boolean finished = false;

    /**
     * @param level  用来看工地上**已经到位**的方块
     * @param origin 锚点：结构 (0,0,0) 在世界里的落点
     */
    public BuildSession(Schematic schematic, ServerLevel level, BlockPos origin) {
        this.schematic = schematic;
        this.order = plan(schematic);
        this.deferred = new ArrayList<>();
        this.total = order.size();
        fastForward(level, origin);
    }

    /**
     * 开局先把游标推到"第一块还没到位的方块"上。
     * <p>
     * 为什么非做不可：{@link #remaining()} 是拿"游标之后还剩多少"算的，而进度条的分母是
     * "一共多少块"。会话每隔一会儿就会重建一次（为了发现中途被拆掉的方块），新会话的游标
     * 从 0 开始——**已经建好的那几百块会被算成"还没建"**，进度于是一下掉回 0，
     * 再随着她一块块掠过那些旧方块涨回来。玩家看到的就是"进度条偶尔清空又涨回来"。
     * <p>
     * 这件事不影响施工本身（{@link #step} 本来就会跳过已到位的方块），
     * 影响的只是"她报出来的那个进度"对不对。
     */
    private void fastForward(ServerLevel level, BlockPos origin) {
        while (cursor < order.size()) {
            Schematic.BlockEntry entry = order.get(cursor);
            if (!level.getBlockState(origin.offset(entry.pos())).equals(entry.state())) {
                return;
            }
            cursor++;
        }
    }

    /**
     * @param lastPlaced 本次最后放置的方块位置，调用方用它播放放置动作和音效
     */
    public record StepResult(int placed, int missing, boolean finished, int remaining,
                             @Nullable BlockPos lastPlaced) {
    }

    // ------------------------------------------------------------------
    // 放置顺序规划
    // ------------------------------------------------------------------

    public static List<Schematic.BlockEntry> plan(Schematic schematic) {
        List<Schematic.BlockEntry> entries = new ArrayList<>(schematic.entries());
        entries.sort(Comparator
                .comparingInt((Schematic.BlockEntry e) -> e.pos().getY())
                .thenComparingInt((Schematic.BlockEntry e) -> supportPriority(e.state()))
                .thenComparingInt((Schematic.BlockEntry e) -> e.pos().getZ())
                .thenComparingInt((Schematic.BlockEntry e) -> e.pos().getX()));
        return entries;
    }

    /**
     * 完整方块优先，因为它们可以独立存在；
     * 其余的（半砖、楼梯、火把、按钮……）留到后面，等支撑物就位。
     */
    private static int supportPriority(BlockState state) {
        return state.canOcclude() ? 0 : 1;
    }

    /**
     * 物料清单：建造这座结构一共需要哪些物品、各多少个。
     */
    public static Map<Item, Integer> bill(Schematic schematic) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (Schematic.BlockEntry entry : schematic.entries()) {
            // 一个方块可能要好几样东西（AE2 的线缆就是：线缆本体 + 贴上去的部件），
            // 所以逐个累加，而不是只取 Block.asItem() 那一个
            for (Item item : BlockMaterialResolver.materialsOf(entry)) {
                result.merge(item, 1, Integer::sum);
            }
        }
        return result;
    }

    /**
     * 从现在这个进度出发，还缺哪些材料、各缺多少。
     * <p>
     * 和 {@link #bill} 的区别很重要：那个算的是"把整座结构从零建起来要多少"，
     * 是个定值；这个方法会跳过工地上已经是目标状态的方块。
     * <p>
     * 取料必须按这个来。否则每缺一次料都照全量清单去搬，已经建好的部分会被
     * 反复要一遍——女仆来回跑不说，手上的材料还会越堆越多。
     */
    public Map<Item, Integer> remainingBill(ServerLevel level, BlockPos origin) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (Schematic.BlockEntry entry : schematic.entries()) {
            BlockPos world = origin.offset(entry.pos());
            if (level.getBlockState(world).equals(entry.state())) {
                // 这块已经是目标状态，不需要再为它取料
                continue;
            }
            for (Item item : BlockMaterialResolver.materialsOf(entry)) {
                result.merge(item, 1, Integer::sum);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 增量执行
    // ------------------------------------------------------------------

    public boolean isFinished() {
        return finished;
    }

    public int remaining() {
        return Math.max(0, order.size() - cursor) + deferred.size();
    }

    /**
     * 这座结构一共多少块。
     * <p>
     * 用**一开始**的块数当分母，而不是"这一轮还剩多少"：分母要是会变，进度条就会往回跳，
     * 而它是给人看的——跳一下，玩家就没法判断她到底建到哪儿了。
     */
    public int total() {
        return total;
    }

    /**
     * 已经到位多少块。
     * <p>
     * 含**开工前就已经正确**的那些：续建、被拆后重扫都会重建会话，而幂等推进会把
     * 已经建好的部分一路掠过——进度因此从那个位置接着往上走，不会从零重来。
     */
    public int done() {
        return Math.max(0, total - remaining());
    }

    /**
     * 最多放置 maxBlocks 个方块，返回本次的执行结果。
     *
     * @param salvage 位置上原有方块的去处（可为 null，那时拆下来的东西照旧凭空消失——
     *                只有"没人来收"的场合才这么传，女仆那边永远有一个实现）
     */
    public StepResult step(ServerLevel level, BlockPos origin, ItemSource source, int maxBlocks,
                           @Nullable Salvage salvage) {
        if (finished) {
            return new StepResult(0, 0, true, 0, null);
        }

        int placed = 0;
        int missing = 0;
        BlockPos lastPlaced = null;

        while (placed < maxBlocks) {
            if (cursor >= order.size()) {
                if (!deferred.isEmpty() && pass < MAX_PASS) {
                    // 上一轮因为缺少支撑没能放下的，现在支撑应该已经就位了
                    order = deferred;
                    deferred = new ArrayList<>();
                    cursor = 0;
                    pass++;
                    continue;
                }
                finished = true;
                break;
            }

            Schematic.BlockEntry entry = order.get(cursor);
            BlockPos world = origin.offset(entry.pos());

            // 幂等：已经和目标状态一致就跳过
            if (level.getBlockState(world).equals(entry.state())) {
                cursor++;
                continue;
            }

            // 支撑还没就位，留到下一轮
            if (!entry.state().canSurvive(level, world)) {
                deferred.add(entry);
                cursor++;
                continue;
            }

            Map<Item, Integer> needed = new LinkedHashMap<>();
            for (Item item : BlockMaterialResolver.materialsOf(entry)) {
                needed.merge(item, 1, Integer::sum);
            }

            if (!hasEnough(source, needed)) {
                // 缺料就停在这个方块上，下一轮还从这里继续。
                // 不能 continue 往后扫：那会一路扫到队尾，让 finished 变成 true，
                // 调用方就会误判成"已经建完"，从而再也不去取材料。
                missing++;
                break;
            }

            // 先确认全都够再动手：材料可能不止一种，中途失败会白扣掉前面那些
            for (Map.Entry<Item, Integer> required : needed.entrySet()) {
                source.consume(required.getKey(), required.getValue());
            }

            // 放下去之前先把这位置上原来的东西拆下来收走。
            // 顺序不能反：setBlock 一执行，原来的方块连同方块实体就都没了，
            // 再想算掉落物也无从下手（那正是之前"顶掉的东西凭空消失"的由来）
            //
            // 拆不掉的（基岩、屏障，或者要她没有的工具的方块）就**跳过这一块**：
            // 留到最后一并说明还剩几块放不下（见 BlueprintBuildController#onCompleted），
            // 绝不能硬盖——盖下去就是把这个方块从世界里删掉
            if (!clearAt(level, world, salvage)) {
                deferred.add(entry);
                cursor++;
                continue;
            }

            level.setBlock(world, entry.state(), Block.UPDATE_ALL);

            if (entry.blockEntity() != null) {
                BlockEntity blockEntity = level.getBlockEntity(world);
                if (blockEntity != null) {
                    blockEntity.load(entry.blockEntity());
                    blockEntity.setChanged();
                }
            }

            cursor++;
            placed++;
            lastPlaced = world;
        }

        return new StepResult(placed, missing, finished, remaining(), lastPlaced);
    }

    /**
     * 把 {@code pos} 上原本那个方块"拆下来"交给回收方，好给目标方块腾地方。
     * <p>
     * 拆下来的东西按方块自己的战利品表算，用的工具是 {@link BlockHarvest#toolFor}——
     * 也就是"她手上那把下界合金的"，没有附魔、没有剪刀。所以：
     * <ul>
     *   <li>石头出圆石、草方块出泥土、机器出它自己（模组的战利品表也照走）；</li>
     *   <li>草、树叶这类要剪刀才有收成的，她就什么也拿不到——按主人的说法，
     *       她不会为了这个特意去找剪刀。</li>
     * </ul>
     * 容器里的东西一并掏出来：原版玩家拆箱子就是这个结果，而且她已经把这格
     * 盖成别的方块了，里面的东西再不拿出来就永远没了。
     *
     * @return true 表示这一格腾出来了（本来就空，或者拆得掉）；
     *         false 表示**她动不了**这块（基岩这类谁也拆不掉的，或者要她没有的工具的），
     *         位置得原样留着——见 {@link BlockHarvest#canHarvest}
     */
    public static boolean clearAt(ServerLevel level, BlockPos pos, @Nullable Salvage salvage) {
        BlockState existing = level.getBlockState(pos);
        if (existing.isAir()) {
            return true; // 空位：没有东西要腾
        }
        ItemStack tool = BlockHarvest.toolFor(existing);
        if (!BlockHarvest.canHarvest(level, pos, existing, tool)) {
            // **动不了就别动**：以前这里是直接覆盖，于是连基岩都能被顶掉。
            // 她不是玩家，没有"消除方块"这种权力——一块基岩被删掉，
            // 比少建一块严重得多，而且没法还原
            if (salvage != null) {
                if (existing.getDestroySpeed(level, pos) < 0.0F) {
                    salvage.unbreakable(level, pos, existing);
                } else {
                    salvage.cannotHarvest(level, pos, existing);
                }
            }
            return false;
        }
        if (salvage == null) {
            return true; // 没人来收，照旧不留东西（只有"无主"的场合才这么传）
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        try {
            // 这里 entity 传 null：不是玩家拆的，也就不该有幸运、进度这些额外加成
            for (ItemStack drop : Block.getDrops(existing, level, pos, blockEntity, null, tool)) {
                if (!drop.isEmpty()) {
                    salvage.collect(level, pos, drop);
                }
            }
            if (blockEntity instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    // 先把这一格腾空、再交出去：顺序反过来的话，
                    // 交接过程中真出了岔子，东西会既在容器里又被收走一遍
                    ItemStack inside = container.getItem(slot).copy();
                    if (inside.isEmpty()) {
                        continue;
                    }
                    container.setItem(slot, ItemStack.EMPTY);
                    salvage.collect(level, pos, inside);
                }
                container.setChanged();
            }
        } catch (Exception e) {
            // 模组的战利品表、方块实体都有可能在算掉落时抛异常。
            // 建造**不能因为"收东西"失败而停下**：该让开的位置照样得让开，
            // 最多个别方块的东西没回来——而这比整座工地卡死好得多
            BlueprintMod.LOGGER.warn("拆 {} 的 {} 时收不了它的东西：{}",
                    pos, existing.getBlock(), e.toString());
        }
        return true;
    }

    /**
     * 材料是否都够。
     * <p>
     * 按"需求量"而不是"有没有"来判断：同一个方块可能要两份同种材料，
     * 只看数量是否大于零会漏判。
     */
    private static boolean hasEnough(ItemSource source, Map<Item, Integer> needed) {
        for (Map.Entry<Item, Integer> required : needed.entrySet()) {
            if (source.available(required.getKey()) < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 下一个待放置方块的世界坐标，只查询、不改变任何状态。
     * <p>
     * 女仆靠它决定该走到哪儿去——先靠近，再伸手放置，和玩家的操作距离一致。
     */
    @Nullable
    public BlockPos peekNextTarget(ServerLevel level, BlockPos origin) {
        for (int i = cursor; i < order.size(); i++) {
            Schematic.BlockEntry entry = order.get(i);
            BlockPos world = origin.offset(entry.pos());
            if (!level.getBlockState(world).equals(entry.state())) {
                return world;
            }
        }
        return null;
    }

    public Schematic getSchematic() {
        return schematic;
    }
}
