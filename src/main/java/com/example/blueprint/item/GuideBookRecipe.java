package com.example.blueprint.item;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 把《女仆建筑说明书》重新做出来：**一本书 + 一张纸**，随便怎么摆。
 * <p>
 * 为什么必须是一个"特殊配方"（{@link CustomRecipe}）而不是普通的手写 JSON 配方：
 * 说明书的正文存在物品 NBT 里（页、书名、作者），而**普通配方的产物只能写
 * "什么物品、几个"**，写不出 NBT。拿 JSON 配方产出的会是一本空白成书——
 * 打得开、没有字，比做不出来更让人困惑。特殊配方的产物由代码现造，
 * 才能把 {@link ManualBook#create()} 那本书原样给出去。
 * <p>
 * 配方不进配方书（{@code CustomRecipe} 天生 {@code isSpecial}）：它是个"补领"通道，
 * 新玩家第一次进世界就会拿到一本，不需要靠配方书去发现。
 */
public class GuideBookRecipe extends CustomRecipe {

    public GuideBookRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        int books = 0;
        int papers = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.BOOK)) {
                books++;
                continue;
            }
            if (stack.is(Items.PAPER)) {
                papers++;
                continue;
            }
            return false; // 多一样别的东西就不算
        }
        return books == 1 && papers == 1;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
        return ManualBook.create();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.GUIDE_BOOK.get();
    }
}
