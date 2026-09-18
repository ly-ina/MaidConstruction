package com.example.blueprint.integration.ae2;

import com.example.blueprint.integration.maid.MaidStudyPool;
import com.example.blueprint.integration.maid.StudyRecipeCapture;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;

/**
 * 从 AE2 的**合成终端**里把"这次做的是什么配方"取出来。
 * <p>
 * 为什么单为它写一段：{@code ItemCraftedEvent} 明明带着合成容器（{@code getInventory()}），
 * 但 AE2 传进去的是 {@code craftingGrid.toContainer()}——那是 {@code InternalInventory}
 * 包装出来的适配器，**不是 {@code CraftingContainer}**，所以"把事件里那个容器当合成格读"
 * 的通用路子到它这里什么也读不到，最后只落得一条没有配方的记录（界面显示"认不出的配方"）。
 * <p>
 * 好在它自己留了口子：{@code CraftingTermMenu#getCurrentRecipe()} 是公开的——就是它
 * 当前匹配到的那个配方，**无线合成终端与便携合成终端都是它的子类**，一并覆盖。
 * <p>
 * 只有确认装了 AE2 才会碰到这个类（见 {@link Ae2Compat#captureCraftingRecipe}）。
 */
public final class Ae2CraftingCapture {

    private Ae2CraftingCapture() {
    }

    /** 当前匹配到的配方；不是 AE2 的合成终端、或者格子还没凑成配方时返回 null */
    @Nullable
    public static MaidStudyPool.Recipe capture(AbstractContainerMenu menu) {
        if (!(menu instanceof appeng.menu.me.items.CraftingTermMenu term)) {
            return null;
        }
        Recipe<CraftingContainer> recipe = term.getCurrentRecipe();
        return recipe == null ? null : StudyRecipeCapture.toStudyRecipe(recipe);
    }
}
