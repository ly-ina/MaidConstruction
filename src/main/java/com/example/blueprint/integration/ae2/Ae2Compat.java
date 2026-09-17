package com.example.blueprint.integration.ae2;

import com.example.blueprint.build.BlockEntityRotationResolver;
import com.example.blueprint.build.BlockMaterialResolver;
import com.example.blueprint.build.ItemProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * AE2 联动的安全入口。
 * <p>
 * 这个类本身**不引用任何 AE2 的类型**，只有确认模组已加载之后
 * 才会去触碰 {@link Ae2ItemProvider}。这样即使玩家没装 AE2，
 * 类加载也不会失败——JVM 是懒加载的，方法体里提到的类
 * 要等到真正执行到那一行才会去解析。
 */
public final class Ae2Compat {

    public static final String MOD_ID = "ae2";

    private static Boolean loaded;
    private static boolean materialsRegistered;

    private Ae2Compat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /**
     * 如果给定位置接得上 ME 网络，返回一个取料源，否则返回 null。
     */
    @Nullable
    public static ItemProvider createProvider(Level level, BlockPos pos) {
        if (!isLoaded()) {
            return null;
        }
        return Ae2ItemProvider.create(level, pos);
    }

    /**
     * 把 AE2 的方块材料解析器挂进核心建造逻辑，模组初始化时调用一次即可。
     * <p>
     * 没装 AE2 时什么都不做——核心包从头到尾不会碰到 AE2 的类。
     */
    public static void registerMaterialResolver() {
        if (materialsRegistered || !isLoaded()) {
            return;
        }
        materialsRegistered = true;
        BlockMaterialResolver.register(new Ae2MaterialResolver());
    }

    /**
     * 把 AE2 的方块实体旋转器挂进结构旋转逻辑，模组初始化时调用一次即可。
     * <p>
     * 方块状态里的朝向有通用逻辑兜底，但线缆把部件朝向记在 NBT 键名里，
     * 这部分只有 AE2 自己知道怎么搬。
     */
    public static void registerBlockEntityRotation() {
        BlockEntityRotationResolver.register(new Ae2BlockEntityRotation());
    }
}
