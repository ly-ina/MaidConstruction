package com.example.blueprint.integration.maid;

import com.example.blueprint.integration.create.CreateSchematic;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 女仆手上那张图：可能是<b>我们的蓝图</b>，也可能是<b>机械动力的蓝图</b>。
 * <p>
 * 施工那条流程（取料、放置、回收、进度）只认"结构 + 锚点 + 朝向 + 建没建完"这四样，
 * 至于它们存在物品 NBT 里还是别处，它不关心。这里把那四样统一成一个问法，
 * 控制器里就不用到处写 {@code instanceof}。
 * <p>
 * 两条路只有一处差别，而且很关键：
 * <ul>
 *   <li>我们的蓝图：完工标记<b>写在物品上</b>，跟着物品走；</li>
 *   <li>机械动力的蓝图：<b>一个字节都不写</b>，完工记在我们自己的存档里
 *       （{@link SchematicStorage}），按"哪张图 + 建在哪"记账——
 *       那张图是主人的东西，我们只借来读。</li>
 * </ul>
 */
public final class MaidBlueprint {

    private MaidBlueprint() {
    }

    /** 女仆能拿它施工的图：我们的，或机械动力的 */
    public static boolean isBlueprint(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BlueprintItem || CreateSchematic.isSchematic(stack));
    }

    /** 这是机械动力的蓝图吗 */
    public static boolean isCreate(ItemStack stack) {
        return CreateSchematic.isSchematic(stack);
    }

    /**
     * 这张图她现在**挑得动**吗：是蓝图，而且机械动力那张不是"已确认读不出来"的。
     * <p>
     * 挑图要用这个而不是 {@link #isBlueprint}：读不出来的图永远标不上完工，
     * 会一直霸占着她，排在后头的蓝图（连我们自己的）就一辈子轮不到。
     */
    public static boolean usable(ItemStack stack) {
        return isBlueprint(stack) && (!isCreate(stack) || CreateSchematic.readable(stack));
    }

    /** 上一回读不出来是什么原因（说给玩家的一句话） */
    public static String problem() {
        return CreateSchematic.lastProblem();
    }

    /** 图里有没有结构。机械动力那张的内容在它自己的文件里，留给取结构那步去判 */
    public static boolean hasSchematic(ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.hasSchematic(stack);
        }
        return CreateSchematic.isSchematic(stack);
    }

    /** 定位了没有 */
    public static boolean hasAnchor(ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.hasAnchor(stack);
        }
        return CreateSchematic.isSchematic(stack) && CreateSchematic.anchor(stack) != null;
    }

    /** 结构的身份（换没换工地靠它比）。机械动力那张用"内容 + 锚点"派生，不进蓝图库 */
    @Nullable
    public static UUID id(ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.getSchematicId(stack);
        }
        return CreateSchematic.id(stack);
    }

    @Nullable
    public static BlockPos anchor(ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.getAnchor(stack);
        }
        return CreateSchematic.anchor(stack);
    }

    public static Rotation rotation(ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.getRotation(stack);
        }
        return CreateSchematic.rotation(stack);
    }

    /** 取这份结构本身。机械动力那张是现翻的（翻好按内容缓存，不是每 tick 重来） */
    @Nullable
    public static Schematic schematic(ServerLevel level, ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            UUID id = BlueprintItem.getSchematicId(stack);
            return id == null ? null : SchematicStorage.get(level).get(id);
        }
        return CreateSchematic.convert(level, stack);
    }

    public static boolean isCompleted(ServerLevel level, ItemStack stack) {
        if (stack.getItem() instanceof BlueprintItem) {
            return BlueprintItem.isCompleted(stack);
        }
        String signature = CreateSchematic.signature(stack);
        return signature != null && SchematicStorage.get(level).isCompleted(signature);
    }

    /** 标完工 / 取消完工。机械动力那张走存档记账，物品本身不动 */
    public static void setCompleted(ServerLevel level, ItemStack stack, boolean completed) {
        if (stack.getItem() instanceof BlueprintItem) {
            BlueprintItem.setCompleted(stack, completed);
            return;
        }
        String signature = CreateSchematic.signature(stack);
        if (signature != null) {
            SchematicStorage.get(level).setCompleted(signature, completed);
        }
    }
}
