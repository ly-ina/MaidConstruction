package com.example.blueprint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 客户端这边"我绑的是哪一台指挥台"（服务端推过来的那份，见 {@code S2CPostBindingPacket}）。
 * <p>
 * 与 {@code CommandPostProjections} 那张表的分工：那张记**附近有哪几台**（区块加载了才收得到），
 * 这张记**我绑的那一台**（在多远、在别的维度也知道）——提示里要显示的正是后者：
 * 绑定之后想看的通常就是"那台在哪，我好走回去"。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPostBinding {

    /** 绑的那一台在哪个维度（形如 {@code minecraft:overworld}）；null = 没绑 */
    @Nullable
    private static String dimension;
    /** 那一格；null = 没绑 */
    @Nullable
    private static BlockPos pos;

    private ClientPostBinding() {
    }

    /** 服务端推来的（解绑时两个都是 null） */
    public static void accept(@Nullable String dimension, @Nullable BlockPos pos) {
        ClientPostBinding.dimension = dimension;
        ClientPostBinding.pos = pos == null ? null : pos.immutable();
    }

    /**
     * 他绑的那台在哪（可能远在别处，甚至别的维度）；没绑返回 null。
     * <p>
     * 为什么界面要用它：附近没有我的指挥台时（走远了、或者人在别的维度），
     * 终端上的「投影」仍然该投到**我绑的那台**上——服务端就是按绑定认目标的。
     */
    @Nullable
    public static GlobalPos current() {
        if (pos == null || dimension == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : GlobalPos.of(ResourceKey.create(Registries.DIMENSION, id), pos);
    }

    /** 退出世界时清掉：那是"这个存档里那台"，换个存档就不作数了 */
    public static void clear() {
        accept(null, null);
    }

    /**
     * 终端提示里那一行：没绑过就写"还没绑定"，绑了就写坐标。
     * <p>
     * 同一维度只报坐标——玩家要的是"往哪边走"，多一段维度名只是噪音；跨维度才必须带上，
     * 不然那串数字会把人引到错误的坐标上。
     */
    public static Component describe() {
        if (pos == null) {
            return Component.translatable("tooltip.maidconstruction.terminal.unbound");
        }
        String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        if (inSameDimension()) {
            return Component.translatable("tooltip.maidconstruction.terminal.bound", coords);
        }
        return Component.translatable("tooltip.maidconstruction.terminal.bound_elsewhere",
                dimension == null ? "?" : dimension, coords);
    }

    private static boolean inSameDimension() {
        Level level = Minecraft.getInstance().level;
        return level != null && dimension != null
                && dimension.equals(level.dimension().location().toString());
    }
}
