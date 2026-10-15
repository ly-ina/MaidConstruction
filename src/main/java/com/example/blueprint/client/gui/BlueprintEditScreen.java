package com.example.blueprint.client.gui;

import com.example.blueprint.client.BlueprintLibrary;
import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.SchematicPreview;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 图纸编辑（调试用）：左边照旧是那份蓝图的三维浏览，右边是一份能搜的物品列表。
 * <p>
 * <b>为什么是"调试"而不是常规功能</b>：它改的是**图纸数据**——改完拿去施工，建出来的东西就不一样了。
 * 蓝图本身只是数据（不碰世界里已经建成的东西），但它是你自己的文件，我们只在**另存为新图纸**时才落盘，
 * 原图一份不动。
 * <p>
 * <b>为什么能在客户端做</b>：图纸库里的每一份都是**你自己这台机器上的文件**（见 {@code BlueprintLibrary}），
 * 服务端那边只在你按下「投影」时才收到一份拷贝。所以这里改的是文件，不必走网络包。
 * <p>
 * 三种粒度都在界面上明说，别让人猜：**改一格**（只动点中的那一格）与**改一类**
 * （同一种方块状态一起动——含朝向、半砖上下那类属性，见 {@link Schematic#withBlock}）。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintEditScreen extends Screen {

    private static final int WINDOW_WIDTH = 420;
    private static final int WINDOW_HEIGHT = 250;
    private static final int HEADER_HEIGHT = 22;
    private static final int PREVIEW_WIDTH = 150;
    private static final int BUTTON_WIDTH = 76;
    private static final int CELL = 18;
    private static final int SEARCH_HEIGHT = 16;

    private final String sourceName;
    /** 编辑中的那份：每次改动都是**换一份新的**（Schematic 不可变），所以原图那一份自始至终没被碰过 */
    private Schematic schematic;

    /** 所有能当"替换目标"的物品：只收有方块形态的（替换得有个方块状态） */
    private final List<Item> allItems;
    private List<Item> shownItems = List.of();
    private int scrollRow;

    @Nullable
    private EditBox searchBox;
    /** 选中的格子：左键点一格就是一个；按住 Shift 点是"同一种方块"的全部（都描边高亮） */
    private List<BlockPos> picked = List.of();
    /** 这次选的是不是"同类"——替换/删除按它决定动一格还是动一类 */
    private boolean pickedSameKind;
    /** 右边点中的那个"要换成的物品" */
    @Nullable
    private Item chosen;
    /** 按了「替换成…」，等右边点一个物品 */
    private boolean replaceArmed;
    /** 删除与覆盖原图都要按两次，与「删除文件」同一条规矩 */
    private boolean deleteArmed;
    private boolean overwriteArmed;
    /** 撤回栈：结构不可变，压进去的就是"改动前的那一份"，只是多一个引用，不占额外内存 */
    private final Deque<Schematic> history = new ArrayDeque<>();

    private float yaw = 35.0F;
    private float pitch = 22.0F;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int columns;
    private Component message = Component.empty();

    public BlueprintEditScreen(String sourceName, Schematic schematic) {
        super(Component.translatable("gui.maidconstruction.edit"));
        this.sourceName = sourceName;
        this.schematic = schematic;
        List<Item> items = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            // 只收能放成方块的：替换需要一个方块状态，纯物品（煤炭、木棍）在这里没有意义
            if (item instanceof BlockItem && Block.byItem(item) != Blocks.AIR) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(item -> item.getDescription().getString()));
        this.allItems = items;
        this.shownItems = items;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        this.listX = left + PREVIEW_WIDTH + 12;
        this.listY = top + HEADER_HEIGHT + 22;
        this.listWidth = WINDOW_WIDTH - PREVIEW_WIDTH - BUTTON_WIDTH - 26;
        this.listHeight = WINDOW_HEIGHT - HEADER_HEIGHT - 40;
        this.columns = Math.max(1, this.listWidth / CELL);

        this.searchBox = new EditBox(this.font, this.listX, top + HEADER_HEIGHT + 4,
                this.listWidth, SEARCH_HEIGHT, Component.translatable("gui.maidconstruction.edit.search"));
        this.searchBox.setHint(Component.translatable("gui.maidconstruction.edit.search"));
        this.searchBox.setResponder(text -> {
            this.shownItems = filter(text);
            this.scrollRow = 0;
        });
        this.addRenderableWidget(this.searchBox);

        int buttonX = left + WINDOW_WIDTH - BUTTON_WIDTH - 6;
        int y = top + HEADER_HEIGHT + 6;
        // 「替换成…」：按下去之后到右边点一个方块。也可以在右边先点好、再按这个——两个顺序都行
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit.replace"),
                b -> armReplace()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit.delete"),
                b -> onDelete()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit.undo"),
                b -> onUndo()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit.save"),
                b -> onSave()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit.overwrite"),
                b -> onOverwrite()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.detail.back"),
                b -> this.onClose()).bounds(buttonX, y, BUTTON_WIDTH, 20).build());
    }

    private List<Item> filter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase();
        if (needle.isEmpty()) {
            return this.allItems;
        }
        List<Item> matched = new ArrayList<>();
        for (Item item : this.allItems) {
            if (item.getDescription().getString().toLowerCase().contains(needle)
                    || String.valueOf(ForgeRegistries.ITEMS.getKey(item)).contains(needle)) {
                matched.add(item);
            }
        }
        return matched;
    }

    /** "×1 / ×K"：这一次改的是一格还是一类，写在状态行里说清楚 */
    private String kindSuffix() {
        return this.pickedSameKind ? " ×K" : " ×1";
    }

    /** 图上所有"与这一块同状态"的格子；Shift 点选同类时就高亮这一批 */
    private List<BlockPos> sameKindPositions(BlockState state) {
        List<BlockPos> result = new ArrayList<>();
        for (Schematic.BlockEntry entry : this.schematic.entries()) {
            if (entry.state().equals(state)) {
                result.add(entry.pos());
            }
        }
        return result;
    }

    private void armReplace() {
        if (this.picked.isEmpty()) {
            this.message = Component.translatable("gui.maidconstruction.edit.need_pick");
            return;
        }
        if (this.chosen == null) {
            this.replaceArmed = true;
            this.message = Component.translatable("gui.maidconstruction.edit.need_replace");
            return;
        }
        applyReplace();
    }

    private void applyReplace() {
        Block block = Block.byItem(this.chosen);
        this.message = Component.literal(this.picked.get(0).toShortString())
                .append(" → ").append(block.getName()).append(kindSuffix());
        apply(block.defaultBlockState());
        this.replaceArmed = false;
    }

    private void onDelete() {
        if (this.picked.isEmpty()) {
            this.message = Component.translatable("gui.maidconstruction.edit.need_pick");
            return;
        }
        if (!this.deleteArmed) {
            this.deleteArmed = true;
            this.message = Component.translatable("gui.maidconstruction.edit.delete_confirm");
            return;
        }
        this.message = Component.literal(this.picked.get(0).toShortString())
                .append(" → ").append(Blocks.AIR.getName()).append(kindSuffix());
        apply(Blocks.AIR.defaultBlockState());
        this.deleteArmed = false;
    }

    private void onUndo() {
        this.deleteArmed = false;
        this.overwriteArmed = false;
        this.replaceArmed = false;
        if (this.history.isEmpty()) {
            this.message = Component.translatable("gui.maidconstruction.edit.undo_empty");
            return;
        }
        this.schematic = this.history.pop();
        this.picked = List.of();
        this.message = Component.translatable("gui.maidconstruction.edit.undone", this.history.size());
    }

    /** 覆盖原图：危险动作，按两次。覆盖的是**你自己那份文件**，所以提示里带上名字 */
    private void onOverwrite() {
        if (!this.overwriteArmed) {
            this.overwriteArmed = true;
            this.message = Component.translatable("gui.maidconstruction.edit.overwrite_confirm", this.sourceName);
            return;
        }
        this.overwriteArmed = false;
        try {
            BlueprintTransfer.writeToFile(this.sourceName, BlueprintTransfer.encode(this.schematic));
            BlueprintLibrary.refresh();
            this.message = Component.translatable("gui.maidconstruction.edit.overwritten", this.sourceName);
        } catch (IOException e) {
            this.message = Component.literal(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 把选中的那（几）格换掉，并把"改动前的那一份"压进撤回栈。
     * <p>
     * 只调一次 {@link Schematic#withBlock} 就够了：Shift 选的"同类"与它的 {@code wholeKind} 是同一条判据
     * （同一方块状态），自己遍历反而容易把"改一格"误伤成"改一类"。
     */
    private void apply(BlockState replacement) {
        Schematic before = this.schematic;
        BlockPos at = this.picked.get(0);
        this.schematic = this.schematic.withBlock(at.getX(), at.getY(), at.getZ(),
                replacement, this.pickedSameKind);
        if (this.schematic != before) {
            this.history.push(before);
        }
        this.picked = List.of();
        this.chosen = null;
    }

    /** 另存为新图纸：原图一份不动（名字后缀 -edit，落盘前后都在状态行里说清） */
    private void onSave() {
        String name = this.sourceName + "-edit";
        try {
            BlueprintTransfer.writeToFile(name, BlueprintTransfer.encode(this.schematic));
            BlueprintLibrary.refresh();
            this.message = Component.translatable("gui.maidconstruction.edit.saved", name);
        } catch (IOException e) {
            this.message = Component.literal(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, 0xC0101018);
        graphics.renderOutline(left, top, WINDOW_WIDTH, WINDOW_HEIGHT, 0xFF808080);
        graphics.drawString(this.font, this.getTitle(), left + 6, top + 7, 0xFFFFFF, false);
        // 状态行：改一格还是改一类、选中了哪一格、有没有在等右边点方块
        Component state = this.picked.isEmpty()
                ? Component.translatable("gui.maidconstruction.edit.nothing_picked")
                : (this.pickedSameKind
                        ? Component.translatable("gui.maidconstruction.edit.picked_kind", this.picked.size())
                        : Component.translatable("gui.maidconstruction.edit.picked_one",
                                this.picked.get(0).toShortString()));
        graphics.drawString(this.font, state, left + WINDOW_WIDTH / 2, top + 7, 0xFFCC55, false);

        drawPreview(graphics, left, top, mouseX, mouseY);
        drawItems(graphics, mouseX, mouseY);
        graphics.drawString(this.font, this.message, left + 6, top + WINDOW_HEIGHT - 12, 0x55FF55, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPreview(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + 6;
        int y = top + HEADER_HEIGHT + 4;
        int height = WINDOW_HEIGHT - HEADER_HEIGHT - 40;
        graphics.fill(x, y, x + PREVIEW_WIDTH, y + height, 0x60000000);
        graphics.renderOutline(x, y, PREVIEW_WIDTH, height, 0x60FFFFFF);

        List<Schematic.BlockEntry> entries = this.schematic.entries();
        float scale = SchematicPreview.scaleFor(this.schematic, PREVIEW_WIDTH - 6);
        SchematicPreview.draw(graphics, this.schematic, entries,
                x + PREVIEW_WIDTH / 2, y + height / 2, PREVIEW_WIDTH - 6,
                this.yaw, this.pitch, Rotation.NONE, Mirror.NONE, 1.0F);
        // 描边：同一套变换，所以框严丝合缝贴在方块上（Shift 点选的同类会一次描一大片）
        SchematicPreview.drawHighlights(graphics, this.schematic, this.picked,
                x + PREVIEW_WIDTH / 2, y + height / 2, scale, this.yaw, this.pitch);
        if (mouseX >= x && mouseX < x + PREVIEW_WIDTH && mouseY >= y && mouseY < y + height) {
            Schematic.BlockEntry hovered = SchematicPreview.pick(this.schematic, entries,
                    x + PREVIEW_WIDTH / 2, y + height / 2, scale, this.yaw, this.pitch, mouseX, mouseY);
            if (hovered != null) {
                graphics.pose().pushPose();
                graphics.pose().translate(0.0D, 0.0D, 400.0D);
                SchematicPreview.drawHintBox(graphics, this.font,
                        List.of(hovered.state().getBlock().getName(),
                                Component.literal(hovered.pos().toShortString())),
                        mouseX, mouseY, this.width, this.height);
                graphics.pose().popPose();
            }
        }
    }

    private void drawItems(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(this.listX - 2, this.listY - 2, this.listX + this.listWidth + 2,
                this.listY + this.listHeight + 2, 0x60000000);
        int rows = Math.max(1, this.listHeight / CELL);
        int maxRow = Math.max(0, (this.shownItems.size() + this.columns - 1) / this.columns - rows);
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxRow));

        graphics.enableScissor(this.listX, this.listY, this.listX + this.listWidth,
                this.listY + this.listHeight);
        Item hovered = null;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < this.columns; column++) {
                int index = (this.scrollRow + row) * this.columns + column;
                if (index >= this.shownItems.size()) {
                    break;
                }
                int cellX = this.listX + column * CELL;
                int cellY = this.listY + row * CELL;
                Item item = this.shownItems.get(index);
                if (item == this.chosen) {
                    graphics.fill(cellX, cellY, cellX + CELL, cellY + CELL, 0x80FFCC55);
                }
                graphics.renderItem(new ItemStack(item), cellX + 1, cellY + 1);
                if (mouseX >= cellX && mouseX < cellX + CELL && mouseY >= cellY && mouseY < cellY + CELL) {
                    hovered = item;
                }
            }
        }
        graphics.disableScissor();
        if (hovered != null) {
            SchematicPreview.drawHintBox(graphics, this.font,
                    List.of(hovered.getDescription()), (int) mouseX, (int) mouseY, this.width, this.height);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverPreview(mouseX, mouseY)) {
            // 按下去就选中：预览里"点一下"和"拖着转"是同一个动作，拖的时候顺手换选中也无妨
            Schematic.BlockEntry hovered = SchematicPreview.pick(this.schematic,
                    this.schematic.entries(), previewCenterX(), previewCenterY(),
                    SchematicPreview.scaleFor(this.schematic, PREVIEW_WIDTH - 6),
                    this.yaw, this.pitch, mouseX, mouseY);
            if (hovered != null) {
                // Shift 点＝选中"同一种方块"的全部（含朝向、半砖上下那类属性一起看），普通点＝只选这一格
                boolean sameKind = Screen.hasShiftDown();
                this.picked = sameKind ? sameKindPositions(hovered.state()) : List.of(hovered.pos());
                this.pickedSameKind = sameKind;
                this.deleteArmed = false;
                if (this.replaceArmed && this.chosen != null) {
                    applyReplace();
                }
            }
            this.dragging = true;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return true;
        }
        if (button == 0 && isOverItems(mouseX, mouseY)) {
            Item item = itemAt(mouseX, mouseY);
            if (item != null) {
                this.chosen = item;
                // 「替换成…」按过了、左边也选好了 —— 这一下就是"点要换成的方块"
                if (this.replaceArmed) {
                    if (this.picked == null) {
                        this.message = Component.translatable("gui.maidconstruction.edit.need_pick");
                    } else {
                        applyReplace();
                    }
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging) {
            this.yaw += (float) (mouseX - this.lastMouseX) * 1.5F;
            this.pitch = Math.max(-90.0F, Math.min(90.0F, this.pitch + (float) (mouseY - this.lastMouseY) * 1.5F));
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverItems(mouseX, mouseY) && delta != 0.0D) {
            int rows = Math.max(1, this.listHeight / CELL);
            int maxRow = Math.max(0, (this.shownItems.size() + this.columns - 1) / this.columns - rows);
            this.scrollRow = Math.max(0, Math.min(maxRow, this.scrollRow + (delta > 0.0D ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Nullable
    private Item itemAt(double mouseX, double mouseY) {
        int column = (int) ((mouseX - this.listX) / CELL);
        int row = (int) ((mouseY - this.listY) / CELL);
        if (column < 0 || column >= this.columns || row < 0) {
            return null;
        }
        int index = (this.scrollRow + row) * this.columns + column;
        return index >= 0 && index < this.shownItems.size() ? this.shownItems.get(index) : null;
    }

    private int previewCenterX() {
        return (this.width - WINDOW_WIDTH) / 2 + 6 + PREVIEW_WIDTH / 2;
    }

    private int previewCenterY() {
        return (this.height - WINDOW_HEIGHT) / 2 + HEADER_HEIGHT + 4
                + (WINDOW_HEIGHT - HEADER_HEIGHT - 40) / 2;
    }

    private boolean isOverPreview(double mouseX, double mouseY) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        return mouseX >= left + 6 && mouseX < left + 6 + PREVIEW_WIDTH
                && mouseY >= top + HEADER_HEIGHT + 4 && mouseY < top + WINDOW_HEIGHT - 30;
    }

    private boolean isOverItems(double mouseX, double mouseY) {
        return mouseX >= this.listX && mouseX < this.listX + this.listWidth
                && mouseY >= this.listY && mouseY < this.listY + this.listHeight;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
