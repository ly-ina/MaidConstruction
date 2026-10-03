package com.example.blueprint.registry;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.block.CommandPostBlock;
import com.example.blueprint.block.CommandPostBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组**不依赖任何别的模组**的方块。
 * <p>
 * AE2 那两个终端另有注册入口（{@code Ae2TerminalRegistry}），因为它们只在装了 AE2 时才存在；
 * 这里的指挥台谁都能用——投影与指挥不该跟着 AE2 走，装了车万女仆才有的只是"指派女仆"那一步。
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BlueprintMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlueprintMod.MOD_ID);

    /**
     * 指挥台：托管投影、指派女仆、看进度（见 DEVELOPER §12）。
     * <p>
     * 硬度与女仆终端一致，木头声：外观就是木头台子，敲起来也该是木头。
     */
    public static final RegistryObject<Block> COMMAND_POST = BLOCKS.register("command_post",
            () -> new CommandPostBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.WOOD)));

    public static final RegistryObject<BlockEntityType<CommandPostBlockEntity>> COMMAND_POST_BE =
            BLOCK_ENTITIES.register("command_post",
                    () -> BlockEntityType.Builder
                            .of(CommandPostBlockEntity::new, COMMAND_POST.get())
                            .build(null));

    private ModBlocks() {
    }
}
