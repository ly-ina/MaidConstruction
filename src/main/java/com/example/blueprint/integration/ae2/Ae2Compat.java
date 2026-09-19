package com.example.blueprint.integration.ae2;

import com.example.blueprint.build.BlockEntityRotationResolver;
import com.example.blueprint.build.BlockMaterialResolver;
import com.example.blueprint.build.ItemProvider;
import com.example.blueprint.integration.maid.MaidStudyPool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
     * 如果女仆身上带着一台绑好了的无线女仆终端，返回由它接入的取料源。
     * <p>
     * 这个来源不需要女仆走到任何地方，控制器会就地取料。
     *
     * @param candidates 候选物品：主手、副手、饰品栏、背包
     * @param userPos    使用者位置，用来判定有没有处在无线接入点的射程里
     * @return AE2 未加载、没有可用的终端、或者不在覆盖范围内时返回 null
     */
    @Nullable
    public static ItemProvider createWirelessProvider(Level level, Iterable<ItemStack> candidates, @Nullable Vec3 userPos) {
        if (!isLoaded()) {
            return null;
        }
        return Ae2WirelessProvider.create(level, candidates, userPos);
    }

    /**
     * 无线女仆终端这个物品；没装 AE2 时返回 {@code null}。
     * <p>
     * 返回 {@link Item} 而不是 {@code RegistryObject}，是为了让调用方
     * （女仆饰品注册）不必触碰 {@link Ae2TerminalRegistry}——那个类的静态字段
     * 会引用 AE2 的类型。
     */
    @Nullable
    public static Item wirelessTerminalItem() {
        // orElse 而不是 get：注册项被第三方弄没了的整合包里，get() 就是一句 NPE，
        // 而这里返回 null 只是"这件东西不提供"，调用方本来就是按可空处理的
        return isLoaded() ? Ae2TerminalRegistry.WIRELESS_MAID_TERMINAL.orElse(null) : null;
    }

    /** 女仆绑定卡这个物品；没装 AE2 时返回 {@code null}。理由同上。 */
    @Nullable
    public static Item maidBindingCardItem() {
        return isLoaded() ? Ae2TerminalRegistry.MAID_BINDING_CARD.orElse(null) : null;
    }

    /**
     * 如果玩家正开着 AE2 的合成终端，返回它当前匹配到的那个配方（学习池要记"她看到了什么做法"）。
     * <p>
     * 为什么别处那种"读事件里的合成容器"的办法对它不灵，见 {@link Ae2CraftingCapture}。
     *
     * @return 没装 AE2、不是合成终端、或者格子里还没凑成配方时返回 {@code null}
     */
    @Nullable
    public static MaidStudyPool.Recipe captureCraftingRecipe(AbstractContainerMenu menu) {
        if (!isLoaded() || menu == null) {
            return null;
        }
        return Ae2CraftingCapture.capture(menu);
    }

    /**
     * 把物品接进 AE2 的机制：升级卡关联、无线访问点的链接登记。
     * <p>
     * 必须在物品注册完成之后再调，所以调用点是 commonSetup 而不是构造函数。
     */
    public static void registerItemHooks() {
        if (!isLoaded()) {
            return;
        }
        Ae2TerminalRegistry.registerItemHooks();
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
