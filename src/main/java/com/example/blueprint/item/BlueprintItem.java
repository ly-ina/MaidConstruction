package com.example.blueprint.item;

import com.example.blueprint.client.BlueprintScreenOpener;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SCapturePacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetOrientationPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 蓝图本体。
 * <p>
 * 交互流程：
 * <ol>
 *   <li>空蓝图右键方块 → 选定第一个角点</li>
 *   <li>再右键另一个方块 → 框定区域，服务端扫描并生成结构</li>
 *   <li>拿着有内容的蓝图移动，投影会跟随视线显示</li>
 *   <li>右键方块 → 把投影固定到该位置（写入锚点）</li>
 *   <li>右键空气 → 开始建造；Shift + 右键空气 → 取消锚点</li>
 * </ol>
 * 女仆手持蓝图时会读取锚点与结构 ID，自行取料建造。
 */
public class BlueprintItem extends Item {

    private static final String KEY_SCHEMATIC = "Schematic";
    private static final String KEY_SIZE = "Size";
    private static final String KEY_NAME = "Name";
    private static final String KEY_POS1 = "Pos1";
    private static final String KEY_ANCHOR = "Anchor";
    private static final String KEY_ROTATION = "Rotation";
    /** 翻面状态。取值与机械动力那张图同名同格式（"Mirror"），两边的读法也就一致 */
    private static final String KEY_MIRROR = "Mirror";
    private static final String KEY_COMPLETED = "Completed";
    private static final String KEY_MAID_BUILD = "MaidBuild";

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------
    // NBT 读写
    // ------------------------------------------------------------------

    public static boolean hasSchematic(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(KEY_SCHEMATIC);
    }

    @Nullable
    public static UUID getSchematicId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(KEY_SCHEMATIC)) {
            return null;
        }
        return tag.getUUID(KEY_SCHEMATIC);
    }

    public static void setSchematic(ItemStack stack, UUID id, Vec3i size, String name) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(KEY_SCHEMATIC, id);
        tag.putIntArray(KEY_SIZE, new int[]{size.getX(), size.getY(), size.getZ()});
        tag.putString(KEY_NAME, name == null ? "" : name);
    }

    @Nullable
    public static Vec3i getSize(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_SIZE)) {
            return null;
        }
        int[] raw = tag.getIntArray(KEY_SIZE);
        if (raw.length != 3) {
            return null;
        }
        return new Vec3i(raw[0], raw[1], raw[2]);
    }

    /**
     * 注意：不能叫 getName，Item 里已经有一个静态的同名方法，会冲突。
     */
    public static String getBlueprintName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(KEY_NAME);
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString(KEY_NAME, name == null ? "" : name);
    }

    public static boolean hasPos1(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(KEY_POS1);
    }

    @Nullable
    public static BlockPos getPos1(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_POS1)) {
            return null;
        }
        int[] raw = tag.getIntArray(KEY_POS1);
        return raw.length == 3 ? new BlockPos(raw[0], raw[1], raw[2]) : null;
    }

    public static void setPos1(ItemStack stack, BlockPos pos) {
        stack.getOrCreateTag().putIntArray(KEY_POS1, new int[]{pos.getX(), pos.getY(), pos.getZ()});
    }

    public static void clearSelection(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(KEY_POS1);
        }
    }

    /**
     * 把蓝图还原成空白状态：结构、朝向、锚点全部抹掉，可以重新录制。
     * <p>
     * 注意这里不删除 SchematicStorage 里的结构数据——蓝图物品可能被复制过，
     * 其他副本仍可能指向同一份数据，贸然删除会让它们失效。
     */
    public static void clearSchematic(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(KEY_SCHEMATIC);
        tag.remove(KEY_SIZE);
        tag.remove(KEY_NAME);
        tag.remove(KEY_ROTATION);
        tag.remove(KEY_MIRROR);
        tag.remove(KEY_ANCHOR);
    }

    public static boolean hasAnchor(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(KEY_ANCHOR);
    }

    @Nullable
    public static BlockPos getAnchor(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_ANCHOR)) {
            return null;
        }
        int[] raw = tag.getIntArray(KEY_ANCHOR);
        return raw.length == 3 ? new BlockPos(raw[0], raw[1], raw[2]) : null;
    }

    public static void setAnchor(ItemStack stack, BlockPos anchor) {
        stack.getOrCreateTag().putIntArray(KEY_ANCHOR, new int[]{anchor.getX(), anchor.getY(), anchor.getZ()});
    }

    public static void clearAnchor(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(KEY_ANCHOR);
        }
    }

    public static Rotation getRotation(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_ROTATION)) {
            return Rotation.NONE;
        }
        Rotation[] values = Rotation.values();
        return values[Math.floorMod(tag.getInt(KEY_ROTATION), values.length)];
    }

    public static void setRotation(ItemStack stack, Rotation rotation) {
        stack.getOrCreateTag().putInt(KEY_ROTATION, rotation.ordinal());
    }

    /** 顺时针推进 90°，返回旋转后的朝向 */
    public static Rotation cycleRotation(ItemStack stack) {
        Rotation[] values = Rotation.values();
        Rotation next = values[(getRotation(stack).ordinal() + 1) % values.length];
        setRotation(stack, next);
        return next;
    }

    /**
     * 当前有没有翻面。
     * <p>
     * 只认"翻"与"没翻"两种：翻面 + 四向旋转已经能凑出全部 8 种朝向，界面上因此是个开关。
     * 存的是原版 {@code Mirror} 的序号，读的时候防越界，越界当没翻。
     */
    public static Mirror getMirror(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_MIRROR)) {
            return Mirror.NONE;
        }
        Mirror[] values = Mirror.values();
        return values[Math.floorMod(tag.getInt(KEY_MIRROR), values.length)];
    }

    public static void setMirror(ItemStack stack, Mirror mirror) {
        stack.getOrCreateTag().putInt(KEY_MIRROR, mirror.ordinal());
    }

    /** 翻面了没有。问"翻没翻"的地方比问"翻的是哪条轴"多得多，单独给一个 */
    public static boolean isMirrored(ItemStack stack) {
        return getMirror(stack) != Mirror.NONE;
    }

    /** 开关一次翻面，返回翻面后的状态 */
    public static Mirror toggleMirror(ItemStack stack) {
        Mirror next = getMirror(stack) == Mirror.NONE ? Mirror.LEFT_RIGHT : Mirror.NONE;
        setMirror(stack, next);
        return next;
    }

    /**
     * 目标建筑是否已经建完。
     * <p>
     * 存在物品 NBT 里而不是女仆的内存里，这样存档重载、女仆换班之后
     * 依然知道这处工地已经完工，不会反复播报"施工完毕"。
     */
    public static boolean isCompleted(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(KEY_COMPLETED);
    }

    public static void setCompleted(ItemStack stack, boolean completed) {
        stack.getOrCreateTag().putBoolean(KEY_COMPLETED, completed);
    }

    /** 女仆是否允许用这张蓝图施工，默认允许 */
    public static boolean isMaidBuildEnabled(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(KEY_MAID_BUILD) || tag.getBoolean(KEY_MAID_BUILD);
    }

    public static void setMaidBuildEnabled(ItemStack stack, boolean enabled) {
        stack.getOrCreateTag().putBoolean(KEY_MAID_BUILD, enabled);
    }

    /** 找到玩家手上的蓝图，优先主手 */
    public static ItemStack findHeld(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof BlueprintItem) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof BlueprintItem) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    /** 一张**空白**蓝图（还没录过东西的那张） */
    public static boolean isBlank(ItemStack stack) {
        return stack.getItem() instanceof BlueprintItem && !hasSchematic(stack);
    }

    /**
     * 找一张空白蓝图：主手 → 副手 → 快捷栏 → 主背包。
     * <p>
     * 返回的是背包里**真实的那一份**（改它的 NBT 就是在改背包）；找不到返回 null。
     * <p>
     * **只查不改**，两端都能调：客户端拿它做界面预检（开着界面时聊天栏不画，
     * 等服务端回一句"不行"等于什么都没发生），真正写入由服务端做。
     */
    @Nullable
    public static ItemStack findBlank(Player player) {
        if (isBlank(player.getMainHandItem())) {
            return player.getMainHandItem();
        }
        if (isBlank(player.getOffhandItem())) {
            return player.getOffhandItem();
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isBlank(inventory.getItem(i))) {
                return inventory.getItem(i);
            }
        }
        return null;
    }

    /** 背包（主手、副手、快捷栏、主背包）里有没有空白蓝图 */
    public static boolean hasBlank(Player player) {
        return findBlank(player) != null;
    }

    /**
     * 找一张空白蓝图并**把它放到手上**；找不到返回 {@link ItemStack#EMPTY}。
     * <p>
     * 为什么不再只认手上那张（见 {@link #findHeld}）："取到手上"这个动作的前提原本是
     * 手里先有一张空白蓝图，而玩家点它的时候多半正拿着终端——于是每次都得先翻背包、
     * 腾出手、再回到界面点一次。空白蓝图本来就在他的物品栏里，没必要让他自己先搬一趟。
     * <p>
     * 找到的位置不在手上时**与主手对调**（而不是就地写进去）：一来"取到手上"名副其实，
     * 二来图纸拿了就是要马上用（交给女仆、放到指挥台），对调之后直接就能用；被换下来的
     * 东西只是换了个格子，一样在他身上，不会丢。
     * <p>
     * <b>只在服务端调</b>：它会动物品栏，客户端那份是同步来的镜像，动它会和服务端对不上。
     */
    public static ItemStack findBlankToHand(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isBlank(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        if (isBlank(offHand)) {
            return offHand;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!isBlank(inventory.getItem(i))) {
                continue;
            }
            int hand = inventory.selected;
            ItemStack found = inventory.getItem(i);
            inventory.setItem(i, inventory.getItem(hand));
            inventory.setItem(hand, found);
            return found;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 抢在方块之前处理右键。
     * <p>
     * 原版是**方块优先**：拿蓝图点箱子时，箱子的 use 会先把界面打开并返回"已处理"，
     * 蓝图的 useOn 根本轮不到——于是容器就当不成选区角点了。
     * <p>
     * 可容器本身也是结构的一部分（箱笼、储物桶这些边角上的东西同样要录进蓝图），
     * 点它的时候必须是选点而不是开箱。Forge 的 onItemUseFirst 跑在方块处理之前，
     * 返回非 PASS 就能把这下右键完整接管。
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return select(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return select(context);
    }

    private static InteractionResult select(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        // **潜行＝取"点击面的相邻格"**（也就是你正对着的那一格空气）。
        // 不规则建筑的外围往往是空的，那一格没有方块可点；不给这个办法，
        // 想把范围框到建筑外面就得先放一块不相干的方块，录完还得拆掉。
        // 想重来改用面板里的「清空录制」。
        BlockPos target = player.isShiftKeyDown()
                ? clicked.relative(context.getClickedFace())
                : clicked;

        if (hasSchematic(stack)) {
            // 锚点本来就是"点击面的相邻格"，所以这里不看潜行与否
            BlockPos anchor = clicked.relative(context.getClickedFace());
            if (level.isClientSide) {
                ModNetwork.CHANNEL.sendToServer(new C2SSetAnchorPacket(anchor));
                player.displayClientMessage(
                        Component.translatable("message.blueprint.anchor_set", formatPos(anchor)), true);
            }
            return InteractionResult.SUCCESS;
        }

        if (!hasPos1(stack)) {
            if (level.isClientSide) {
                setPos1(stack, target);
                player.displayClientMessage(
                        Component.translatable("message.blueprint.pos1_set", formatPos(target)), true);
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos pos1 = getPos1(stack);
        if (pos1 != null && level.isClientSide) {
            // 交给服务端扫描，客户端同时把自己的选点状态清掉
            ModNetwork.CHANNEL.sendToServer(new C2SCapturePacket(pos1, target, getBlueprintName(stack)));
            clearSelection(stack);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                // 打开蓝图面板：预览、旋转、清空、导入导出都在里面。
                // 用 DistExecutor 包一层，服务端不会去加载客户端的界面类。
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BlueprintScreenOpener.open());
                return InteractionResultHolder.success(stack);
            }

            if (!hasSchematic(stack)) {
                player.displayClientMessage(Component.translatable("message.blueprint.no_schematic"), true);
                return InteractionResultHolder.success(stack);
            }

            // 蓝图本身不能一键放置，右键空气只用来调整朝向。
            // 只转不翻面：翻面是"这栋楼要不要照镜子"，比转个角度不常用得多，留给面板上的按钮。
            Rotation next = cycleRotation(stack);
            ModNetwork.CHANNEL.sendToServer(new C2SSetOrientationPacket(next, getMirror(stack)));
            player.displayClientMessage(Component.translatable("message.blueprint.rotated", angleText(next)), true);
            return InteractionResultHolder.success(stack);
        }

        return InteractionResultHolder.success(stack);
    }

    /**
     * 空白的和录好的用两个不同的名字，在物品栏里一眼就能区分。
     */
    @Override
    public String getDescriptionId(ItemStack stack) {
        return hasSchematic(stack) ? "item.blueprint.blueprint" : "item.blueprint.blueprint.empty";
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (hasSchematic(stack)) {
            String name = getBlueprintName(stack);
            tooltip.add(Component.translatable("tooltip.blueprint.name", name.isEmpty()
                    ? Component.translatable("tooltip.blueprint.unnamed").getString() : name));

            Vec3i size = getSize(stack);
            if (size != null) {
                tooltip.add(Component.translatable("tooltip.blueprint.size",
                        size.getX(), size.getY(), size.getZ()));
            }
            Rotation rotation = getRotation(stack);
            if (rotation != Rotation.NONE) {
                tooltip.add(Component.translatable("tooltip.blueprint.rotation", angleText(rotation)));
            }
            if (getMirror(stack) != Mirror.NONE) {
                tooltip.add(Component.translatable("tooltip.blueprint.mirror"));
            }
            if (hasAnchor(stack)) {
                tooltip.add(Component.translatable("tooltip.blueprint.anchor", formatPos(getAnchor(stack))));
                tooltip.add(Component.translatable(isCompleted(stack)
                        ? "tooltip.blueprint.completed" : "tooltip.blueprint.incomplete"));
                tooltip.add(Component.translatable("tooltip.blueprint.hint_maid_build"));
            } else {
                tooltip.add(Component.translatable("tooltip.blueprint.hint_anchor"));
            }
            tooltip.add(Component.translatable("tooltip.blueprint.hint_rotate"));
            tooltip.add(Component.translatable(isMaidBuildEnabled(stack)
                    ? "tooltip.blueprint.maid_on" : "tooltip.blueprint.maid_off"));
        } else {
            tooltip.add(Component.translatable("tooltip.blueprint.empty"));
            if (hasPos1(stack)) {
                tooltip.add(Component.translatable("tooltip.blueprint.pos1", formatPos(getPos1(stack))));
                tooltip.add(Component.translatable("tooltip.blueprint.hint_select2"));
            } else {
                tooltip.add(Component.translatable("tooltip.blueprint.hint_select"));
            }
            // 空气里怎么选点：不规则建筑的外围没有方块可点，这一步不说玩家想不到
            tooltip.add(Component.translatable("tooltip.blueprint.hint_select_outside"));
        }
    }

    private static String formatPos(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String angleText(Rotation rotation) {
        return (rotation.ordinal() * 90) + "°";
    }
}
