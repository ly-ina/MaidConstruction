package com.example.blueprint.schematic;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockEntityRotationResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一个被记录下来的多方块结构。
 * <p>
 * 采用和原版 structure 一样的 palette + 索引数组存储方式，
 * 相比逐方块存储可以省下大量 NBT 体积。
 */
public final class Schematic {

    /** 单条边上限，防止玩家误选到超大区域把内存打爆 */
    public static final int MAX_SIDE = 128;
    /**
     * 总体积上限。
     * 结构数据需要整体同步到客户端用于投影，定得太大很容易把网络包撑爆，
     * 26 万方块（约 64³）已经远超任何多方块机器的规模。
     */
    public static final int MAX_VOLUME = 262_144;

    private final Vec3i size;
    private final List<BlockState> palette;
    /** 每个位置对应的 palette 下标，index = (y * sizeZ + z) * sizeX + x */
    private final int[] blocks;
    /** 方块索引 -&gt; 方块实体 NBT */
    private final Map<Integer, CompoundTag> blockEntities;

    private Schematic(Vec3i size, List<BlockState> palette, int[] blocks, Map<Integer, CompoundTag> blockEntities) {
        this.size = size;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
    }

    /**
     * 世界里的一个方块（相对于结构原点）。
     */
    public record BlockEntry(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {
    }

    // ------------------------------------------------------------------
    // 捕获
    // ------------------------------------------------------------------

    /**
     * 扫描世界中由两个角点框定的区域，生成 Schematic。
     */
    public static Schematic capture(Level level, BlockPos a, BlockPos b) {
        BlockPos min = new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));

        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;

        if (sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE) {
            throw new IllegalStateException("schematic.side_too_large");
        }
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            throw new IllegalStateException("schematic.volume_too_large");
        }

        Vec3i size = new Vec3i(sx, sy, sz);
        Map<BlockState, Integer> paletteLookup = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int[] blocks = new int[(int) volume];
        Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);

                    BlockState state = level.getBlockState(cursor);
                    Integer id = paletteLookup.get(state);
                    if (id == null) {
                        id = palette.size();
                        palette.add(state);
                        paletteLookup.put(state, id);
                    }
                    int index = (y * sz + z) * sx + x;
                    blocks[index] = id;

                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    if (blockEntity != null) {
                        // **这里照实录，一个键都不删。**
                        // 原因：材料判定（BlockMaterialResolver）要靠这份 NBT 才知道
                        // "这个位置需要哪些物品"——AE2 线缆要的是贴在面上的部件、机器要的是它自己，
                        // 录的时候就剔掉，她会直接"找不到材料"。
                        // 防复制挪到**放置**那一步去剔（见 BuildSession.step）
                        blockEntities.put(index, blockEntity.saveWithoutMetadata());
                    }
                }
            }
        }

        return new Schematic(size, palette, blocks, blockEntities);
    }

    /**
     * 剔掉方块实体 NBT 里的**物品库存**，只留配置（朝向、频道、优先级这些）。
     * <p>
     * 混合两套判据，缺一不可：
     * <ul>
     *   <li><b>已知键</b>：原版容器的 {@code Items}、AE2 的 {@code inv} 与发送清单……
     *       这些名字是确定的，直接删最稳；</li>
     *   <li><b>形状兜底</b>：任何"元素是物品栈形状（{@code id} + {@code Count}）的列表"
     *       都删掉——别的模组各有各的键名，认名字认不全，认形状才兜得住。</li>
     * </ul>
     * 为什么非要剔：录制是"记结构"，容器里的东西不属于结构。
     * 原样录下来的话，放下去就是**凭空复制**（样板、磁盘、整箱材料都会翻倍），
     * 那是漏洞不是功能。
     */
    public static void stripContainerItems(CompoundTag tag) {
        for (String key : ITEM_INVENTORY_KEYS) {
            tag.remove(key);
        }
        List<String> drop = new ArrayList<>();
        List<CompoundTag> nested = new ArrayList<>();
        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            if (looksLikeItemList(value)) {
                drop.add(key);
                continue;
            }
            // **往里钻**：格雷把物品放在 ForgeCaps 里（能力序列化那一层），
            // 只看顶层等于什么都没剔——机器里的东西照样子复制出来
            if (value instanceof CompoundTag child) {
                nested.add(child);
            } else if (value instanceof ListTag list) {
                for (Tag element : list) {
                    if (element instanceof CompoundTag child) {
                        nested.add(child);
                    }
                }
            }
        }
        drop.forEach(tag::remove);
        for (CompoundTag child : nested) {
            stripContainerItems(child);
        }
    }

    /**
     * 各模组放物品（或流体）常用的键名。这些是拿真实蓝图扫出来的，
     * 不是照着印象列的——格雷（GregTech）与 AE2 系扩展的都在里面。
     */
    private static final String[] ITEM_INVENTORY_KEYS = {
            // 原版 / 通用
            "Items", "items", "inv", "inventory", "Inventory",
            // AE2 及其扩展（样板、发送清单、退回槽）
            "sendList", "send_list", "buffer", "patterns", "returnInv",
            // 格雷（GregTech）：电路槽、机器内缓冲、机器槽、流体罐
            "circuitInventory", "internalBuffer", "machineSlot", "tank"
    };

    /** 这个标签看起来是不是"一串物品栈"：元素带 {@code id} 又有 {@code Count}/{@code count} */
    private static boolean looksLikeItemList(Tag value) {
        if (!(value instanceof ListTag list) || list.isEmpty()) {
            return false;
        }
        int checked = Math.min(list.size(), 4);
        for (int i = 0; i < checked; i++) {
            if (!(list.get(i) instanceof CompoundTag entry)) {
                return false;
            }
            boolean hasId = entry.contains("id", Tag.TAG_STRING) || entry.contains("id", Tag.TAG_INT)
                    || entry.contains("item", Tag.TAG_STRING);
            boolean hasCount = entry.contains("Count", Tag.TAG_ANY_NUMERIC)
                    || entry.contains("count", Tag.TAG_ANY_NUMERIC);
            if (!(hasId && hasCount)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 访问
    // ------------------------------------------------------------------

    public Vec3i getSize() {
        return size;
    }

    public int getWidth() {
        return size.getX();
    }

    public int getHeight() {
        return size.getY();
    }

    public int getLength() {
        return size.getZ();
    }

    public int volume() {
        return blocks.length;
    }

    public int index(int x, int y, int z) {
        return (y * size.getZ() + z) * size.getX() + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < size.getX() && y < size.getY() && z < size.getZ();
    }

    public BlockState stateAt(int x, int y, int z) {
        return palette.get(blocks[index(x, y, z)]);
    }

    public boolean isAir(int x, int y, int z) {
        return palette.get(blocks[index(x, y, z)]).isAir();
    }

    /**
     * 该位置的方块实体 NBT（已剔除坐标字段，放置时会重新绑定位置）。
     */
    @Nullable
    public CompoundTag blockEntityAt(int x, int y, int z) {
        return blockEntities.get(index(x, y, z));
    }

    /**
     * 列出所有非空气方块，坐标相对于结构原点。
     * 顺序为 y -&gt; z -&gt; x，建造规划器会再对它做依赖排序。
     */
    public List<BlockEntry> entries() {
        List<BlockEntry> result = new ArrayList<>();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = stateAt(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    result.add(new BlockEntry(new BlockPos(x, y, z), state, blockEntityAt(x, y, z)));
                }
            }
        }
        return result;
    }

    /**
     * 按给定角度旋转整个结构，返回旋转后的新实例（原实例不变）。
     * <p>
     * NONE 会直接返回自身，避免无谓的复制。
     * 旋转是绕 Y 轴的：方块位置重新映射，方块自身的状态（朝向、连接等）
     * 交给 BlockState#rotate 处理，方块实体 NBT 原样搬运。
     */
    @SuppressWarnings("deprecation")
    public Schematic rotate(Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return this;
        }

        int sx = getWidth();
        int sy = getHeight();
        int sz = getLength();
        boolean swapAxes = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;

        Vec3i newSize = new Vec3i(swapAxes ? sz : sx, sy, swapAxes ? sx : sz);
        int newWidth = newSize.getX();
        int newLength = newSize.getZ();

        Map<BlockState, Integer> paletteLookup = new HashMap<>();
        List<BlockState> newPalette = new ArrayList<>();
        int[] newBlocks = new int[blocks.length];
        Map<Integer, CompoundTag> newBlockEntities = new LinkedHashMap<>();

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int oldIndex = index(x, y, z);

                    int newX;
                    int newZ;
                    switch (rotation) {
                        case CLOCKWISE_90 -> {
                            newX = sz - 1 - z;
                            newZ = x;
                        }
                        case CLOCKWISE_180 -> {
                            newX = sx - 1 - x;
                            newZ = sz - 1 - z;
                        }
                        case COUNTERCLOCKWISE_90 -> {
                            newX = z;
                            newZ = sx - 1 - x;
                        }
                        default -> {
                            newX = x;
                            newZ = z;
                        }
                    }

                    BlockState rotated = rotateState(palette.get(blocks[oldIndex]), rotation);
                    Integer id = paletteLookup.get(rotated);
                    if (id == null) {
                        id = newPalette.size();
                        newPalette.add(rotated);
                        paletteLookup.put(rotated, id);
                    }

                    int newIndex = (y * newLength + newZ) * newWidth + newX;
                    newBlocks[newIndex] = id;

                    CompoundTag blockEntity = blockEntities.get(oldIndex);
                    if (blockEntity != null) {
                        newBlockEntities.put(newIndex,
                                BlockEntityRotationResolver.rotate(rotated, blockEntity, rotation));
                    }
                }
            }
        }

        return new Schematic(newSize, newPalette, newBlocks, newBlockEntities);
    }

    /**
     * 旋转方块状态，连那些没覆写 {@code Block#rotate} 的方块一起转。
     * <p>
     * 原版 {@code BlockState#rotate} 是委托给 {@code Block#rotate} 的，而那个的默认实现
     * 直接返回原状态——只有楼梯、箱子这类主动覆写过的方块才会真的动朝向。
     * AE2 的机器（磁盘驱动器之类）继承的是它自己的基类，没覆写，于是朝向纹丝不动。
     * <p>
     * 所以这里在方块自己转完之后，再按**原始状态**的值把所有朝向属性重设一遍。
     * 用的是原值而不是转过之后的值，因此不会出现"转了两次"。
     */
    @SuppressWarnings("deprecation")
    private static BlockState rotateState(BlockState state, Rotation rotation) {
        BlockState result = state.rotate(rotation);
        for (Property<?> property : state.getProperties()) {
            if (property instanceof DirectionProperty directionProperty) {
                result = result.setValue(directionProperty,
                        rotateDirection(state.getValue(directionProperty), rotation));
            }
        }
        return result;
    }

    /**
     * 绕 Y 轴旋转一个方向。
     * <p>
     * 上下不受影响，而 {@code Direction#getClockWise} 对 UP/DOWN 会直接抛异常，得先挡掉。
     */
    public static Direction rotateDirection(Direction direction, Rotation rotation) {
        if (direction.getAxis() == Direction.Axis.Y) {
            return direction;
        }
        return switch (rotation) {
            case CLOCKWISE_90 -> direction.getClockWise();
            case COUNTERCLOCKWISE_90 -> direction.getCounterClockWise();
            case CLOCKWISE_180 -> direction.getOpposite();
            default -> direction;
        };
    }

    /**
     * 非空气方块的数量。
     * 单独提供这个方法是为了预览时决定降采样步长，避免为了数个数就去分配整个方块列表。
     */
    public int countBlocks() {
        int count = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (!palette.get(blocks[i]).isAir()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 结构中空气方块所占的比例，用于提示玩家框选区域是否太过空旷。
     */
    public float airRatio() {
        int air = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (palette.get(blocks[i]).isAir()) {
                air++;
            }
        }
        return blocks.length == 0 ? 1.0F : (float) air / blocks.length;
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public CompoundTag write(CompoundTag tag) {
        tag.putIntArray("size", new int[]{size.getX(), size.getY(), size.getZ()});

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(writeBlockState(state));
        }
        tag.put("palette", paletteTag);

        tag.putIntArray("blocks", blocks);

        if (!blockEntities.isEmpty()) {
            ListTag blockEntityTag = new ListTag();
            for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
                CompoundTag holder = new CompoundTag();
                holder.putInt("i", entry.getKey());
                holder.put("nbt", entry.getValue());
                blockEntityTag.add(holder);
            }
            tag.put("block_entities", blockEntityTag);
        }

        return tag;
    }

    public static Schematic read(CompoundTag tag) {
        int[] rawSize = tag.getIntArray("size");
        if (rawSize.length != 3) {
            throw new IllegalStateException("schematic.invalid_data");
        }
        Vec3i size = new Vec3i(rawSize[0], rawSize[1], rawSize[2]);

        List<BlockState> palette = new ArrayList<>();
        ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(readBlockState(paletteTag.getCompound(i)));
        }

        int[] blocks = tag.getIntArray("blocks");
        int expected = size.getX() * size.getY() * size.getZ();
        if (blocks.length != expected) {
            throw new IllegalStateException("schematic.invalid_data");
        }

        Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        ListTag blockEntityTag = tag.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntityTag.size(); i++) {
            CompoundTag holder = blockEntityTag.getCompound(i);
            blockEntities.put(holder.getInt("i"), holder.getCompound("nbt"));
        }

        return new Schematic(size, palette, blocks, blockEntities);
    }

    // ------------------------------------------------------------------
    // 方块状态序列化
    // <p>
    // 这里没有使用 NbtUtils：1.20.1 的 NbtUtils.readBlockState 需要额外传入
    // HolderGetter<Block>，而蓝图的读写在很多场合下拿不到当前世界的 registry，
    // 自己按同样的格式读写反而更可控。
    // ------------------------------------------------------------------

    private static CompoundTag writeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        tag.putString("Name", key == null ? "minecraft:air" : key.toString());

        if (!state.getValues().isEmpty()) {
            CompoundTag properties = new CompoundTag();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                writeProperty(properties, entry.getKey(), entry.getValue());
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeProperty(CompoundTag tag, Property<?> property, Comparable<?> value) {
        tag.putString(property.getName(), ((Property) property).getName(value));
    }

    private static BlockState readBlockState(CompoundTag tag) {
        if (!tag.contains("Name", Tag.TAG_STRING)) {
            return Blocks.AIR.defaultBlockState();
        }

        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(tag.getString("Name")));
        if (block == null) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = block.defaultBlockState();

        if (tag.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag properties = tag.getCompound("Properties");
            StateDefinition<Block, BlockState> definition = block.getStateDefinition();
            for (String key : properties.getAllKeys()) {
                Property<?> property = definition.getProperty(key);
                if (property != null) {
                    state = applyProperty(state, property, properties.getString(key));
                }
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, Property<?> property, String value) {
        Property raw = (Property) property;
        Optional parsed = raw.getValue(value);
        return parsed.isPresent() ? state.setValue(raw, (Comparable) parsed.get()) : state;
    }
}
