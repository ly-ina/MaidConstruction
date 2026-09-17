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
 * 终端的注册入口。
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

    private Ae2TerminalRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
