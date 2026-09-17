package com.example.blueprint.build;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
    private List<Schematic.BlockEntry> order;
    private List<Schematic.BlockEntry> deferred;
    private int cursor = 0;
    private int pass = 0;
    private boolean finished = false;

    public BuildSession(Schematic schematic) {
        this.schematic = schematic;
        this.order = plan(schematic);
        this.deferred = new ArrayList<>();
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
            Item item = entry.state().getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            result.merge(item, 1, Integer::sum);
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

            Item item = entry.state().getBlock().asItem();
            if (item != Items.AIR) {
                if (source.available(item) <= 0 || !source.consume(item, 1)) {
                    // 缺料就停在这个方块上，下一轮还从这里继续。
                    // 不能 continue 往后扫：那会一路扫到队尾，让 finished 变成 true，
                    // 调用方就会误判成"已经建完"，从而再也不去取材料。
                    missing++;
                    break;
                }
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
