package com.example.blueprint.build;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
     */
    public StepResult step(ServerLevel level, BlockPos origin, ItemSource source, int maxBlocks) {
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
