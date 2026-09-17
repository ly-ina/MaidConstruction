package com.example.blueprint.integration.ae2;

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

    private Ae2TerminalRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
