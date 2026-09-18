package com.example.blueprint.integration.maid;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 把"一个配方"翻译成学习池里存的那种记录（配方 id + 展示用的 3×3 摆法）。
 * <p>
 * 两条来源共用这一份翻译：AE2 合成终端给的 {@code getCurrentRecipe()}，
 * 以及最后兜底时按产物反查到的那个配方。两处要是各写一份，摆法迟早长成两个样子。
 */
public final class StudyRecipeCapture {

    private StudyRecipeCapture() {
    }

    /** 配方 → 池子里的记录（每格取材料表里列出的第一个当代表） */
    public static MaidStudyPool.Recipe toStudyRecipe(Recipe<CraftingContainer> recipe) {
        return new MaidStudyPool.Recipe(recipe.getId(), layout(recipe, ingredient -> {
            ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
        }));
    }

    /**
     * 按产物在配方表里反查：**只有唯一一条**能做出来的合成配方时才算数。
     * <p>
     * 有多条匹配时，无从判断主人刚才用的是哪一条——这正是"一个产物可能有好几个配方"
     * 那件事本身，也就该由主人来定优先级，而不是我们随便挑一条塞进池子。
     * 所以这里认不出来就返回 null，让上游记成"只见过产物，没见过做法"。
     */
    @Nullable
    public static MaidStudyPool.Recipe uniqueFor(ServerLevel level, ItemStack product) {
        RegistryAccess access = level.registryAccess();
        Recipe<CraftingContainer> found = null;
        for (Recipe<CraftingContainer> recipe : level.getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!recipe.getResultItem(access).is(product.getItem())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = recipe;
        }
        return found == null ? null : toStudyRecipe(found);
    }

    /**
     * 按**配方自己写的材料表**摊出一份 3×3 摆法。
     * <p>
     * 有序配方按它的宽高摆；无序配方（宽高跟材料数对不上）排成一行。
     * 给主人看"要哪些材料"和替她摆一遍手搓用的是同一份逻辑，差别只在每格放什么：
     * 记"她看过什么"取材料表里的第一个代表，手搓取**她真正要用的那件**
     * （吃 tag 的材料可能得用箱子里那种变体）。
     */
    public static List<ItemStack> layout(Recipe<CraftingContainer> recipe,
                                         Function<Ingredient, ItemStack> chooser) {
        ItemStack[] slots = new ItemStack[MaidStudyPool.GRID_SIZE];
        Arrays.fill(slots, ItemStack.EMPTY);
        List<Ingredient> ingredients = recipe.getIngredients();
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : MaidStudyPool.GRID_SIZE;
        for (int i = 0; i < ingredients.size() && i < MaidStudyPool.GRID_SIZE; i++) {
            ItemStack chosen = chooser.apply(ingredients.get(i));
            if (chosen == null || chosen.isEmpty()) {
                continue;
            }
            int index = width <= 0 ? i : (i / width) * 3 + (i % width);
            if (index < 0 || index >= MaidStudyPool.GRID_SIZE) {
                continue;
            }
            // 每格只留一个：合成要的是"这一格放什么"，不是放几个（多出来的由配方自己决定）
            slots[index] = chosen.copyWithCount(1);
        }
        return List.of(slots);
    }
}
