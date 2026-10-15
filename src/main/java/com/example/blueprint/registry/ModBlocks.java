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
                    .sound(SoundType.WOOD)
                    // 模型不是整块（台子 + 斜台面 + 屏，还探出 0.3 像素）：**必须声明不遮挡**，
                    // 否则它一旦贴着别的方块，邻居那侧的遮挡判据就会把面剔掉/剔错，
                    // 表现正是"贴着方块就透视"
                    .noOcclusion()));

    public static final RegistryObject<BlockEntityType<CommandPostBlockEntity>> COMMAND_POST_BE =
            BLOCK_ENTITIES.register("command_post",
                    () -> BlockEntityType.Builder
                            .of(CommandPostBlockEntity::new, COMMAND_POST.get())
                            .build(null));

    private ModBlocks() {
    }
}
