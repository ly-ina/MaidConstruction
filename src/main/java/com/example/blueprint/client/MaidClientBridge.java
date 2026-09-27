package com.example.blueprint.client;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.item.BlueprintItem;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端要问女仆的那几件事，**全关在这一个类里**。
 * <p>
 * 这个类引用了女仆模组的 {@link EntityMaid}，所以它只能在确认装了女仆模组之后
 * 才被触碰（调用方必须先过 {@code MaidCompat.isLoaded()}）。
 * <p>
 * 这样安排的用意是：{@link ProjectionRenderer} 和 {@link BlockedSpotHighlighter}
 * 是每帧都要跑的订阅者，它们自己的字节码里一旦出现 {@code EntityMaid}，
 * 没装女仆模组的客户端就会在执行到那一刻崩掉。把引用集中到这里之后，
 * 那两个渲染器只剩下"装了吗？没装就到此为止"这一句可以安全执行的判断，
 * 真正的女仆代码根本不会被加载。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidClientBridge {

    /** 复用给 {@link #heldBlueprints} 的那只列表（只在渲染线程上用，不必并发安全） */
    private static final List<ItemStack> SCRATCH = new ArrayList<>();

    /**
     * 附近女仆手上拿着的蓝图（主手优先，其次副手）。
     * <p>
     * 给"挡路位置"高亮用：它只要图，不关心是谁拿的。
     * <p>
     * 返回的是**复用的那只列表**（每帧清空再装），不是新造的：
     * 这个方法是每帧都要跑的，每次 new 一个 ArrayList 就是在给渲染线程
     * 每分钟添三千个垃圾对象。只在渲染线程上用，所以不必考虑并发。
     */
    public static List<ItemStack> heldBlueprints(ClientLevel level) {
        SCRATCH.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
                continue;
            }
            ItemStack stack = heldBlueprint(maid);
            if (!stack.isEmpty()) {
                SCRATCH.add(stack);
            }
        }
        return SCRATCH;
    }

    /**
     * 离玩家最近那只女仆手上的蓝图（她正在建的那张）。
     * <p>
     * 给投影用：没拿蓝图时才去找女仆；有几只就取最近那只——
     * 同时叠两张半透明的图，谁也看不清。
     */
    public static ItemStack nearestBuildingBlueprint(Minecraft mc) {
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return ItemStack.EMPTY;
        }

        double radius = BlueprintConfig.maidProjectionRadius();
        ItemStack best = ItemStack.EMPTY;
        double bestDistance = radius * radius;
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(radius))) {
            ItemStack stack = buildingBlueprint(maid);
            if (stack == null) {
                continue;
            }
            double distance = maid.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stack;
            }
        }
        return best;
    }

    /**
     * 女仆手上那张**还没建完**的蓝图。
     * <p>
     * 只认主手和副手，不去翻她的背包：蓝图在她手上才说明她正打算建它。
     * 背包里躺着好几张时照顺序挑一张画出来，玩家会看到一座跟自己毫无关系的建筑浮在眼前。
     */
    @Nullable
    private static ItemStack buildingBlueprint(EntityMaid maid) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = maid.getItemInHand(hand);
            if (stack.getItem() instanceof BlueprintItem
                    && BlueprintItem.hasSchematic(stack)
                    && BlueprintItem.hasAnchor(stack)
                    && !BlueprintItem.isCompleted(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** 她手上那张蓝图（先主手，再副手）。高亮只要求"是蓝图"，不要求建没建完 */
    private static ItemStack heldBlueprint(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem();
        if (main.getItem() instanceof BlueprintItem) {
            return main;
        }
        ItemStack off = maid.getOffhandItem();
        return off.getItem() instanceof BlueprintItem ? off : ItemStack.EMPTY;
    }

    private MaidClientBridge() {
    }
}
