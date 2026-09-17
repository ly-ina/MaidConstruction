package com.example.blueprint.item;

import com.example.blueprint.client.BlueprintScreenOpener;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SCapturePacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetRotationPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
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

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();

        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                clearSelection(stack);
                player.displayClientMessage(Component.translatable("message.blueprint.selection_cleared"), true);
            }
            return InteractionResult.SUCCESS;
        }

        if (hasSchematic(stack)) {
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
                setPos1(stack, clicked);
                player.displayClientMessage(
                        Component.translatable("message.blueprint.pos1_set", formatPos(clicked)), true);
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos pos1 = getPos1(stack);
        if (pos1 != null && level.isClientSide) {
            // 交给服务端扫描，客户端同时把自己的选点状态清掉
            ModNetwork.CHANNEL.sendToServer(new C2SCapturePacket(pos1, clicked, getBlueprintName(stack)));
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
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BlueprintScreenOpener.open(stack));
                return InteractionResultHolder.success(stack);
            }

            if (!hasSchematic(stack)) {
                player.displayClientMessage(Component.translatable("message.blueprint.no_schematic"), true);
                return InteractionResultHolder.success(stack);
            }

            // 蓝图本身不能一键放置，右键空气只用来调整朝向
            Rotation next = cycleRotation(stack);
            ModNetwork.CHANNEL.sendToServer(new C2SSetRotationPacket(next));
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
        }
    }

    private static String formatPos(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String angleText(Rotation rotation) {
        return (rotation.ordinal() * 90) + "°";
    }
}
