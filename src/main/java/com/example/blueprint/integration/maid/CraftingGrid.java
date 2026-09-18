package com.example.blueprint.integration.maid;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 手搓用的合成格：一个自带的 3×3，够 {@code Recipe#assemble} 与
 * {@code Recipe#getRemainingItems} 用（剩下的桶、工具该退的会照原版规矩退）。
 * <p>
 * 为什么不借原版的 {@code TransientCraftingContainer}：它必须挂一个
 * {@code AbstractContainerMenu}，每次改格子都会回调那个菜单的 {@code slotsChanged}。
 * 我们只是替女仆"空手摆一遍"，没有菜单可挂，也不该去惊动谁的界面——
 * 传 null 会在 {@code setItem} 时炸，造个假菜单则是把假状态塞进真流程。
 */
public class CraftingGrid implements CraftingContainer {

    private final NonNullList<ItemStack> items;
    private final int width;
    private final int height;

    public CraftingGrid(int width, int height) {
        this.width = width;
        this.height = height;
        this.items = NonNullList.withSize(width * height, ItemStack.EMPTY);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public List<ItemStack> getItems() {
        return items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = net.minecraft.world.ContainerHelper.removeItem(items, slot, amount);
        if (!stack.isEmpty()) {
            setChanged();
        }
        return stack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return net.minecraft.world.ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        // 没有菜单要通知：这个格子只在手搓那一下存在
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public void fillStackedContents(StackedContents contents) {
        for (ItemStack stack : items) {
            contents.accountSimpleStack(stack);
        }
    }

    /** 摆一格（内部用，不走 {@link Container} 那套改动通知） */
    public void place(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
        }
    }
}
