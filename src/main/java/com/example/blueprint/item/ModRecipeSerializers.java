package com.example.blueprint.item;

import com.example.blueprint.BlueprintMod;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组自己的配方类型。
 * <p>
 * 只有"产物必须由代码现造"的配方才需要注册一个类型（见 {@link GuideBookRecipe}）：
 * 产物里带 NBT 的配方，普通 JSON 写不出来。
 */
public final class ModRecipeSerializers {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, BlueprintMod.MOD_ID);

    /**
     * 说明书：一本书 + 一张纸。JSON 里就一句 {@code {"type": "blueprint:guide_book"}}，
     * 判据全在这个类里。
     */
    public static final RegistryObject<RecipeSerializer<?>> GUIDE_BOOK =
            SERIALIZERS.register("guide_book", () -> new SimpleCraftingRecipeSerializer<>(GuideBookRecipe::new));

    private ModRecipeSerializers() {
    }
}
