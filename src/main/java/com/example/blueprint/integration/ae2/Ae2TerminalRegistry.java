package com.example.blueprint.integration.ae2;

import appeng.api.features.GridLinkables;
import appeng.api.upgrades.Upgrades;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.example.blueprint.BlueprintMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * AE2 方块（女仆终端、创造女仆接口）的注册入口。
 * <p>
 * 这些字段的初始化会引用 AE2 类型，所以整类**只能在确认 AE2 已加载后**才被触碰，
 * 调用方必须先过 {@link Ae2Compat#isLoaded()}。
 */
public final class Ae2TerminalRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BlueprintMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BlueprintMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlueprintMod.MOD_ID);

    public static final RegistryObject<Block> MAID_TERMINAL = BLOCKS.register("maid_terminal",
            () -> new MaidTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.METAL)));

    public static final RegistryObject<Item> MAID_TERMINAL_ITEM = ITEMS.register("maid_terminal",
            () -> new BlockItem(MAID_TERMINAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<MaidTerminalBlockEntity>> MAID_TERMINAL_BE =
            BLOCK_ENTITIES.register("maid_terminal",
                    () -> BlockEntityType.Builder
                            .of(MaidTerminalBlockEntity::new, MAID_TERMINAL.get())
                            .build(null));

    // 创造女仆接口：外观沿用终端那一套，但它的存储是"自己的"，
    // 不接网络也能给女仆供料，接上网络则整张网络都能取任意物品
    public static final RegistryObject<Block> CREATIVE_MAID_INTERFACE = BLOCKS.register("creative_maid_interface",
            () -> new CreativeMaidInterfaceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.QUARTZ)
                    .strength(2.0F, 6.0F)
                    // 它自带光源：模型那一层是全亮的，这里再把周围的亮度也点亮，
                    // 不然一块"发光的终端"立在暗处却不照亮任何东西，看着很假
                    .lightLevel(state -> 15)
                    .sound(SoundType.METAL)));

    public static final RegistryObject<Item> CREATIVE_MAID_INTERFACE_ITEM = ITEMS.register("creative_maid_interface",
            () -> new BlockItem(CREATIVE_MAID_INTERFACE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<CreativeMaidInterfaceBlockEntity>> CREATIVE_MAID_INTERFACE_BE =
            BLOCK_ENTITIES.register("creative_maid_interface",
                    () -> BlockEntityType.Builder
                            .of(CreativeMaidInterfaceBlockEntity::new, CREATIVE_MAID_INTERFACE.get())
                            .build(null));

    /**
     * 女仆绑定卡：一张 AE2 升级卡，插在无线女仆终端里才有意义。
     * <p>
     * 用 AE2 自己的工厂造，不要自己写一个 Item 子类——升级槽的过滤器只看
     * "这种卡在这个物品上最多能装几张"，而那个数字是靠 {@link Upgrades#add}
     * 登记出来的，跟物品类型毫无关系（见 {@link #registerUpgrades()}）。
     */
    public static final RegistryObject<Item> MAID_BINDING_CARD =
            ITEMS.register("maid_binding_card",
                    () -> Upgrades.createUpgradeCardItem(new Item.Properties().stacksTo(1)));

    /**
     * 无线女仆终端：女仆饰品形态的 ME 终端。
     * <p>
     * 不堆叠——一台终端只对应一个绑定，堆叠会让各自的 NBT 互相覆盖。
     */
    public static final RegistryObject<Item> WIRELESS_MAID_TERMINAL =
            ITEMS.register("wireless_maid_terminal",
                    () -> new WirelessMaidTerminalItem(new Item.Properties().stacksTo(1)));

    private Ae2TerminalRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    /**
     * 把两件物品接进 AE2 的机制里。
     * <p>
     * <b>必须在物品注册完成之后调用</b>（所以放在 commonSetup 而不是构造函数里）：
     * 这里要取 {@code RegistryObject.get()}，注册事件还没发的话取不到。
     * <p>
     * 两处登记缺一不可，而且**缺了的后果都是静默的**：
     * <ul>
     *   <li>{@code Upgrades.add}：卡照样能合成、能拿在手上，但往终端的升级槽里放会被
     *       默默拒绝——AE2 的 {@code allowInsert} 只比较"已装数量"和"最多可装数量"，
     *       后者没登记就是 0，它不会告诉你原因；</li>
     *   <li>{@code GridLinkables.register}：ME 无线访问点的链接槽位是按注册表放行的
     *       （{@code RestrictedInputSlot$PlacableItemType.GRID_LINKABLE_ITEM}），
     *       没登记的话终端根本放不进访问点，也就没法像官方终端那样链接。</li>
     * </ul>
     */
    public static void registerItemHooks() {
        // 第四个参数是这张卡在工具提示里显示的那句话，由 AE2 自己拼进卡片的
        // 说明列表（跟着卡片走，所以写在卡上而不是终端上）
        Upgrades.add(MAID_BINDING_CARD.get(), WIRELESS_MAID_TERMINAL.get(), 1,
                "tooltip.blueprint.maid_binding_card");

        // 直接复用官方那个链接处理器：它的 canLink 就是 instanceof WirelessTerminalItem
        // （我们的终端本来就是子类），link/unlink 读写的也是官方终端那套 NBT 键。
        // 这样"访问点里链接"和"终端自己解析链接"共用同一份数据，不会分叉
        GridLinkables.register(WIRELESS_MAID_TERMINAL.get(), WirelessTerminalItem.LINKABLE_HANDLER);
    }
}
