package com.example.blueprint.integration.create;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 机械动力（Create）的蓝图：**认得它、读得懂它、但不碰它**。
 * <p>
 * 装没装机械动力是两条完全不同的路：
 * <ul>
 *   <li>没装 —— {@link #isLoaded()} 一眼短路，整条路一步不走，
 *       <b>一个 Create 的类都不会被加载</b>（反射 + 注册名判识，没有硬引用，
 *       不会出现"少装一个模组就崩"）；</li>
 *   <li>装了 —— 先让它自己读（它最清楚自己的文件放哪），读不出来我们再按
 *       物品上写的 {@code File} / {@code Owner} 自己找、自己解析。</li>
 * </ul>
 * <b>这张图我们只读不改</b>：物品 NBT 一个字节都不写，施工也不消耗它；
 * 完工标记记在我们自己的存档里（见 {@code MaidBlueprint}），所以它始终是主人那张原图。
 * <p>
 * 取料、放置、回收、进度条一律按我们自己的逻辑走，这里只负责"把结构翻译成
 * 我们的 {@link Schematic}"这一件事。
 */
public final class CreateSchematic {

    private static final String MOD_ID = "create";
    private static final ResourceLocation ITEM_ID = new ResourceLocation(MOD_ID, "schematic");
    private static final String ITEM_CLASS = "com.simibubi.create.content.schematics.SchematicItem";
    private static final String PATHS_CLASS = "com.simibubi.create.foundation.utility.CreatePaths";

    /** 当前这张图读不出来的原因（说给玩家听的一句话） */
    private static String problem = "";

    @Nullable
    private static Boolean present;
    private static boolean probed;
    private static boolean broken;
    private static Method loadMethod;

    /** 机械动力自己的两个蓝图目录（反射读它的静态字段，不猜路径） */
    private static final List<Path> DIRS = new ArrayList<>();

    /** 翻好的结构按"哪张图 + 什么朝向"缓存 */
    private static final Map<String, Schematic> CACHE = new HashMap<>();

    /**
     * 已经确认读不出来的图（工地指纹）。
     * <p>
     * 必须记下来：她挑图是"主手 → 副手 → 背包，挑第一张没标完工的"。
     * 读不出来的图永远建不完、也就永远标不上完工，会一直霸占着她——
     * 排在后头的蓝图（连我们自己的）就一辈子轮不到。
     */
    private static final Set<String> FAILED = new HashSet<>();

    private CreateSchematic() {
    }

    // ------------------------------------------------------------------
    // 认不认得
    // ------------------------------------------------------------------

    public static boolean isLoaded() {
        if (present == null) {
            present = ModList.get().isLoaded(MOD_ID);
        }
        return present;
    }

    public static boolean isSchematic(ItemStack stack) {
        return !stack.isEmpty() && isLoaded() && ITEM_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    /** 这张图她现在挑得动吗（没被确认读不出来） */
    public static boolean readable(ItemStack stack) {
        if (!isSchematic(stack)) {
            return false;
        }
        String signature = signature(stack);
        return signature == null || !FAILED.contains(signature);
    }

    public static String lastProblem() {
        return problem.isEmpty() ? "原因记在日志里" : problem;
    }

    // ------------------------------------------------------------------
    // 物品上写着什么
    // ------------------------------------------------------------------

    /**
     * 蓝图定位的锚点。Create 用原版 {@link NbtUtils#writeBlockPos}（带 X/Y/Z 的复合标签），
     * 但 int 数组那种写法也一并兜住。
     */
    @Nullable
    public static BlockPos anchor(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("Anchor")) {
            return null;
        }
        try {
            if (tag.get("Anchor") instanceof CompoundTag compound) {
                return NbtUtils.readBlockPos(compound);
            }
        } catch (Exception ignored) {
            // 落到下面按数组试
        }
        try {
            int[] raw = tag.getIntArray("Anchor");
            if (raw.length == 3) {
                return new BlockPos(raw[0], raw[1], raw[2]);
            }
        } catch (Exception ignored) {
            // 读不出来就是没定位
        }
        return null;
    }

    public static Rotation rotation(ItemStack stack) {
        return readEnum(stack, "Rotation", Rotation.values());
    }

    public static Mirror mirror(ItemStack stack) {
        return readEnum(stack, "Mirror", Mirror.values());
    }

    /** 朝向怎么存是它自己的事：字符串（名字）和序号都认，认不出来就当没转过 */
    private static <E extends Enum<E>> E readEnum(ItemStack stack, String key, E[] values) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return values[0];
        }
        if (tag.contains(key, Tag.TAG_STRING)) {
            String name = tag.getString(key);
            for (E value : values) {
                if (value.name().equalsIgnoreCase(name)) {
                    return value;
                }
            }
        }
        if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            int ordinal = tag.getInt(key);
            if (ordinal >= 0 && ordinal < values.length) {
                return values[ordinal];
            }
        }
        return values[0];
    }

    /** 内容指纹：哪张图 + 什么朝向（不含锚点——同一张图建在两处，内容是一份） */
    @Nullable
    private static String contentKey(ItemStack stack, Rotation rotation, Mirror mirror) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        String file = tag.getString("File");
        if (file.isEmpty()) {
            return null;
        }
        return MOD_ID + ":" + tag.getString("Owner") + "/" + file + "|" + rotation + "|" + mirror;
    }

    /** "这一处工地"的指纹：内容 + 锚点。完工标记按它记账 */
    @Nullable
    public static String signature(ItemStack stack) {
        if (!isSchematic(stack)) {
            return null;
        }
        String key = contentKey(stack, rotation(stack), mirror(stack));
        BlockPos anchor = anchor(stack);
        if (key == null || anchor == null) {
            return null;
        }
        return key + "@" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
    }

    /**
     * 派生的结构 id：只用来判断"换没换工地"，不进蓝图库、不写进存档。
     * <p>
     * 缺了文件名也必须给个 id：上游拿不到 id 会直接撒手，
     * 连"去找文件"这步都不跑，玩家只看到"找不到结构"、什么线索都没有。
     */
    @Nullable
    public static UUID id(ItemStack stack) {
        String signature = signature(stack);
        if (signature == null) {
            CompoundTag tag = stack.getTag();
            BlockPos anchor = anchor(stack);
            if (tag == null && anchor == null) {
                return null;
            }
            signature = "create?:" + (tag == null ? "" : tag) + "@" + anchor;
        }
        return UUID.nameUUIDFromBytes(signature.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 读结构
    // ------------------------------------------------------------------

    @Nullable
    public static Schematic convert(ServerLevel level, ItemStack stack) {
        if (!isSchematic(stack)) {
            return null;
        }
        Rotation rotation = rotation(stack);
        Mirror mirror = mirror(stack);
        String key = contentKey(stack, rotation, mirror);
        if (key != null) {
            Schematic cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        Schematic result = viaCreate(level, stack, rotation, mirror);
        if (result == null) {
            result = viaFile(level, stack, rotation, mirror);
        }
        if (result == null) {
            String failed = signature(stack);
            if (failed != null) {
                FAILED.add(failed);
            }
            if (problem.isEmpty()) {
                problem = "机械动力自己读不出来，我们也没找到它的文件";
            }
            BlueprintMod.LOGGER.warn("机械动力这张蓝图读不出结构（{}），先跳过；她不会再挑这张图了", problem);
            return null;
        }

        if (key != null) {
            CACHE.put(key, result);
        }
        String ok = signature(stack);
        if (ok != null) {
            FAILED.remove(ok);
        }
        problem = "";
        return result;
    }

    /** 交给机械动力自己读。它读不到东西时返回**空壳**（不是 null），所以要验里面有没有方块 */
    @Nullable
    private static Schematic viaCreate(ServerLevel level, ItemStack stack, Rotation rotation, Mirror mirror) {
        if (!prepare()) {
            problem = "没能加载机械动力的蓝图类";
            return null;
        }
        try {
            Object loaded = loadMethod.invoke(null, level, stack);
            if (!(loaded instanceof StructureTemplate template)) {
                problem = "机械动力给回来的不是结构";
                return null;
            }
            List<?> blocks = blocksOf(template);
            if (blocks == null || blocks.isEmpty()) {
                problem = "机械动力读出来的是空结构（图多半没上传到服务器）";
                return null;
            }
            return build(template.getSize(), blocks, rotation, mirror);
        } catch (Throwable t) {
            problem = "机械动力读图出错（见日志）";
            BlueprintMod.LOGGER.warn("机械动力自己读这张蓝图出错了，改由我们自己找文件：{}", t.toString());
            return null;
        }
    }

    /**
     * 我们自己按物品上写的 {@code File} / {@code Owner} 去找那个 .nbt，然后自己解析。
     * <p>
     * 为什么不借 {@code StructureTemplate} 来读：它的方块清单藏在私有字段 {@code palettes} 里，
     * 而且它（以及机械动力的读法）对**坐标的写法**有要求。文件本身反而是稳的——
     * 玩家拷来拷去、跨版本用的都是这一份，按格式自己读更靠得住。见 {@link #parse}。
     */
    @Nullable
    private static Schematic viaFile(ServerLevel level, ItemStack stack, Rotation rotation, Mirror mirror) {
        Path path = resolve(level, stack);
        if (path == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            Schematic result = parse(NbtIo.readCompressed(in), rotation, mirror);
            if (result == null) {
                problem = "文件读出来了，可里面的方块读不懂：" + path.getFileName();
                return null;
            }
            BlueprintMod.LOGGER.info("机械动力蓝图：自己从 {} 读到了结构 {}", path, result.getSize());
            return result;
        } catch (Exception e) {
            problem = "文件读不出来：" + path.getFileName();
            BlueprintMod.LOGGER.warn("自己读 {} 没读出来：{}", path, e.toString());
            return null;
        }
    }

    /**
     * 自己解析机械动力的 .nbt（就是原版 structure 那套：{@code size} + {@code palette} +
     * {@code blocks[{pos, state, nbt}]}）。用真实的图核对过，几个坑都在这儿：
     * <ul>
     *   <li><b>{@code size} 和 {@code pos} 是"三个 IntTag 的列表"，不是 int 数组</b>——
     *       只认 int 数组的话，八千多个方块一个都落不进去，看起来"读到了"其实是空的
     *       （见 {@link #readInt3}）；</li>
     *   <li><b>它那份调色板的 0 号不一定是空气</b>（实测是 minecraft:bricks）。
     *       索引数组里的空位默认是 0，直接搬它那份的话，整栋楼的空档都会被 0 号方块填满——
     *       要建的就从八千多块变成整个体积（九万多块）。所以我们在最前面塞一个空气当 0 号，
     *       它那些状态整体往后挪一位。</li>
     * </ul>
     */
    @Nullable
    private static Schematic parse(CompoundTag src, Rotation rotation, Mirror mirror) {
        int[] size = readInt3(src, "size");
        if (size == null) {
            BlueprintMod.LOGGER.warn("机械动力蓝图的 size 读不出来");
            return null;
        }
        int sx = size[0];
        int sy = size[1];
        int sz = size[2];
        if (sx <= 0 || sy <= 0 || sz <= 0
                || sx > Schematic.MAX_SIDE || sy > Schematic.MAX_SIDE || sz > Schematic.MAX_SIDE) {
            BlueprintMod.LOGGER.warn("机械动力蓝图尺寸 {}x{}x{} 超出单边上限 {}", sx, sy, sz, Schematic.MAX_SIDE);
            return null;
        }
        long volume = (long) sx * sy * sz;
        if (volume > Schematic.MAX_VOLUME) {
            BlueprintMod.LOGGER.warn("机械动力蓝图太大：{}x{}x{}", sx, sy, sz);
            return null;
        }

        ListTag palette = src.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = src.getList("blocks", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || blocks.isEmpty()) {
            BlueprintMod.LOGGER.warn("机械动力蓝图里没有 palette 或 blocks");
            return null;
        }
        if (readInt3(blocks.getCompound(0), "pos") == null) {
            BlueprintMod.LOGGER.warn("机械动力蓝图的 pos 读不出来（第一条：{}）", blocks.getCompound(0));
            return null;
        }
        if (mirror != Mirror.NONE) {
            // 镜像要连方块朝向一起翻，那得有 BlockState 才走得通（机械动力那条路支持）；
            // 这条备用读法只处理位置，镜像先忽略——宁可朝向不对，也不能把结构读丢
            BlueprintMod.LOGGER.warn("这张机械动力蓝图带镜像，备用读法只处理旋转，镜像先忽略");
        }

        int[] indices = new int[(int) volume];
        Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            int[] pos = readInt3(entry, "pos");
            if (pos == null) {
                continue;
            }
            int x = pos[0];
            int y = pos[1];
            int z = pos[2];
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                continue; // 越界的丢掉，不为一条坏数据整份作废
            }
            int state = entry.getInt("state");
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            int index = (y * sz + z) * sx + x;
            indices[index] = state + 1; // +1：让开我们自己那份的空气位
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                CompoundTag copy = entry.getCompound("nbt").copy();
                copy.remove("x");
                copy.remove("y");
                copy.remove("z");
                blockEntities.put(index, copy);
            }
        }

        ListTag myPalette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        myPalette.add(air);
        for (int i = 0; i < palette.size(); i++) {
            myPalette.add(palette.get(i));
        }

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("size", new int[]{sx, sy, sz});
        tag.put("palette", myPalette);
        tag.putIntArray("blocks", indices);
        if (!blockEntities.isEmpty()) {
            ListTag beTag = new ListTag();
            for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
                CompoundTag holder = new CompoundTag();
                holder.putInt("i", entry.getKey());
                holder.put("nbt", entry.getValue());
                beTag.add(holder);
            }
            tag.put("block_entities", beTag);
        }

        try {
            // 走一遍自己的 read：那条路上的每条校验（体积、下标越界）对外部数据同样适用，
            // 别人的文件不该能打崩服务器
            return Schematic.read(tag).rotate(rotation);
        } catch (Exception e) {
            BlueprintMod.LOGGER.warn("机械动力蓝图的格式没通过我们的校验：{}", e.toString());
            return null;
        }
    }

    /**
     * 读一个"三个整数"的坐标，两种写法都认：int 数组，和三个 IntTag 的<b>列表</b>。
     * 这不是多虑——机械动力自己写的 .nbt 里 {@code size} 和 {@code pos} 都是列表。
     */
    @Nullable
    private static int[] readInt3(CompoundTag tag, String key) {
        if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
            int[] raw = tag.getIntArray(key);
            return raw.length == 3 ? raw : null;
        }
        ListTag list = tag.getList(key, Tag.TAG_INT);
        if (list.size() == 3) {
            return new int[]{list.getInt(0), list.getInt(1), list.getInt(2)};
        }
        return null;
    }

    /**
     * 找出文件在哪：机械动力自己的两个目录（{@code schematics} 和 {@code schematics/uploaded}，
     * 反射读它的静态字段）+ 游戏目录下的同名目录兜底；
     * 名字带不带 {@code .nbt}、前面加不加 {@code Owner} 子目录都试，
     * 都不中再按名字在目录里搜一遍（忽略大小写、后缀、子目录）。
     * <p>
     * 有这一层是因为它服务端只认 {@code schematics/uploaded/<Owner>/}，
     * 而玩家的图常常直接躺在 {@code schematics/} 里——它自己读不到，我们得能读到。
     */
    @Nullable
    private static Path resolve(ServerLevel level, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            problem = "这张图上没有任何数据";
            return null;
        }
        String file = tag.getString("File");
        String owner = tag.getString("Owner");
        if (file.isEmpty()) {
            problem = "这张图上没写文件名（File 是空的）";
            BlueprintMod.LOGGER.warn("机械动力这张蓝图上没写文件名，图上的全部内容：{}", tag);
            return null;
        }

        List<Path> dirs = new ArrayList<>(createDirs());
        Path gameDir = level.getServer().getServerDirectory().toPath();
        dirs.add(gameDir.resolve("uploaded_schematics"));
        dirs.add(gameDir.resolve("schematics"));

        List<String> names = file.endsWith(".nbt") ? List.of(file) : List.of(file + ".nbt", file);
        List<String> owners = owner.isEmpty() ? List.of("") : List.of(owner, "");
        List<Path> tried = new ArrayList<>();

        for (Path dir : dirs) {
            for (String o : owners) {
                for (String name : names) {
                    Path base = o.isEmpty() ? dir : dir.resolve(o);
                    Path candidate = base.resolve(name).normalize();
                    if (!candidate.startsWith(dir)) {
                        continue; // 目录穿越：名字里带 .. 的一律不认
                    }
                    tried.add(candidate);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        for (Path dir : dirs) {
            Path found = searchByName(dir, file);
            if (found != null) {
                BlueprintMod.LOGGER.info("机械动力蓝图 {}：按名字在 {} 下找到了 {}", file, dir, found);
                return found;
            }
        }
        problem = "找不到文件 " + file;
        BlueprintMod.LOGGER.warn("机械动力蓝图 {} 的文件没找到（找过：{}）", file, tried);
        return null;
    }

    @Nullable
    private static Path searchByName(Path dir, String file) {
        String wanted = file.toLowerCase(Locale.ROOT);
        if (wanted.endsWith(".nbt")) {
            wanted = wanted.substring(0, wanted.length() - 4);
        }
        if (wanted.isEmpty()) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(dir, 3)) {
            for (Path candidate : stream.toList()) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".nbt") && name.substring(0, name.length() - 4).equals(wanted)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 目录不存在、没权限：当作没找到
        }
        return null;
    }

    /** 机械动力自己的蓝图目录，探一次就记住；探不到也不碍事（还有游戏目录兜底） */
    private static List<Path> createDirs() {
        if (!dirsProbed) {
            dirsProbed = true;
            try {
                Class<?> cls = Class.forName(PATHS_CLASS);
                for (String name : new String[]{"SCHEMATICS_DIR", "UPLOADED_SCHEMATICS_DIR"}) {
                    if (cls.getField(name).get(null) instanceof Path path) {
                        DIRS.add(path);
                    }
                }
            } catch (Throwable t) {
                BlueprintMod.LOGGER.warn("机械动力的蓝图目录没探到，只按游戏目录找：{}", t.toString());
            }
        }
        return DIRS;
    }

    private static boolean dirsProbed;

    /** 反射只在第一次真正用到时探一次；探不到就永久放弃，不再反复试 */
    private static synchronized boolean prepare() {
        if (probed) {
            return !broken;
        }
        probed = true;
        try {
            Class<?> itemClass = Class.forName(ITEM_CLASS);
            loadMethod = itemClass.getMethod("loadSchematic", Level.class, ItemStack.class);
            Field palettes = StructureTemplate.class.getDeclaredField("palettes");
            palettes.setAccessible(true);
            PALETTES = palettes;
            return true;
        } catch (Throwable t) {
            broken = true;
            BlueprintMod.LOGGER.warn("机械动力的蓝图类没探到（{}），这项功能停用：{}", ITEM_CLASS, t.toString());
            return false;
        }
    }

    @Nullable
    private static Field PALETTES;

    /** 取结构里的方块清单（{@code palettes} 是私有字段，Forge 给 Palette#blocks() 开了口子） */
    @Nullable
    private static List<?> blocksOf(StructureTemplate template) {
        if (PALETTES == null) {
            return null;
        }
        try {
            Object raw = PALETTES.get(template);
            if (!(raw instanceof List<?> palettes) || palettes.isEmpty()) {
                return null;
            }
            Object palette = palettes.get(0);
            try {
                Object result = palette.getClass().getMethod("blocks").invoke(palette);
                return result instanceof List<?> list ? list : null;
            } catch (NoSuchMethodException e) {
                Field field = palette.getClass().getDeclaredField("blocks");
                field.setAccessible(true);
                Object result = field.get(palette);
                return result instanceof List<?> list ? list : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 按我们的格式重排：palette + 索引数组。镜像先做，旋转交给 {@link Schematic#rotate}（与原版顺序一致） */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Schematic build(Vec3i size, List<?> blocks, Rotation rotation, Mirror mirror) {
        int sx = size.getX();
        int sy = size.getY();
        int sz = size.getZ();
        if (sx <= 0 || sy <= 0 || sz <= 0
                || sx > Schematic.MAX_SIDE || sy > Schematic.MAX_SIDE || sz > Schematic.MAX_SIDE) {
            BlueprintMod.LOGGER.warn("机械动力蓝图尺寸 {}x{}x{} 超出上限", sx, sy, sz);
            return null;
        }
        long volume = (long) sx * sy * sz;
        if (volume > Schematic.MAX_VOLUME) {
            BlueprintMod.LOGGER.warn("机械动力蓝图太大：{}x{}x{}", sx, sy, sz);
            return null;
        }

        List<net.minecraft.world.level.block.state.BlockState> palette = new ArrayList<>();
        Map<net.minecraft.world.level.block.state.BlockState, Integer> lookup = new HashMap<>();
        palette.add(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        lookup.put(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 0);

        int[] indices = new int[(int) volume];
        Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();

        for (Object raw : blocks) {
            if (!(raw instanceof StructureTemplate.StructureBlockInfo info)) {
                continue;
            }
            BlockPos pos = info.pos();
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            net.minecraft.world.level.block.state.BlockState state = info.state();
            if (mirror != Mirror.NONE) {
                if (mirror == Mirror.LEFT_RIGHT) {
                    z = sz - 1 - z;
                } else {
                    x = sx - 1 - x;
                }
                state = state.mirror(mirror);
            }
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                continue;
            }
            Integer id = lookup.get(state);
            if (id == null) {
                id = palette.size();
                palette.add(state);
                lookup.put(state, id);
            }
            int index = (y * sz + z) * sx + x;
            indices[index] = id;
            CompoundTag nbt = info.nbt();
            if (nbt != null && !nbt.isEmpty()) {
                CompoundTag copy = nbt.copy();
                copy.remove("x");
                copy.remove("y");
                copy.remove("z");
                blockEntities.put(index, copy);
            }
        }

        ListTag paletteTag = new ListTag();
        for (net.minecraft.world.level.block.state.BlockState state : palette) {
            paletteTag.add(writeState(state));
        }
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("size", new int[]{sx, sy, sz});
        tag.put("palette", paletteTag);
        tag.putIntArray("blocks", indices);
        if (!blockEntities.isEmpty()) {
            ListTag beTag = new ListTag();
            for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
                CompoundTag holder = new CompoundTag();
                holder.putInt("i", entry.getKey());
                holder.put("nbt", entry.getValue());
                beTag.add(holder);
            }
            tag.put("block_entities", beTag);
        }
        try {
            return Schematic.read(tag).rotate(rotation);
        } catch (Exception e) {
            BlueprintMod.LOGGER.warn("机械动力蓝图转成我们的格式时没通过校验：{}", e.toString());
            return null;
        }
    }

    /** 方块状态写成 {@link Schematic#read} 认得的格式（Name + Properties），和它那边一致 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CompoundTag writeState(net.minecraft.world.level.block.state.BlockState state) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        tag.putString("Name", key == null ? "minecraft:air" : key.toString());
        if (!state.getValues().isEmpty()) {
            CompoundTag properties = new CompoundTag();
            for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry
                    : state.getValues().entrySet()) {
                Property property = entry.getKey();
                properties.putString(property.getName(), property.getName(entry.getValue()));
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    /** 缓存留个口子：想强制重读时用得上 */
    public static void clearCache() {
        CACHE.clear();
        FAILED.clear();
    }
}
