package com.example.blueprint.client.gui;

import com.example.blueprint.item.ManualItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 说明书界面：**左边目录、右边正文，点目录直接跳过去**。
 * <p>
 * 这就是把说明书从"原版成书"换过来的原因：书页是纯文本，原版的书界面不处理点击，
 * 所以只有页码、没有目录，想找一条得从头翻。现在目录在左边一列，点一下就跳。
 * <p>
 * 正文仍然来自语言文件（{@link ManualItem#PAGES}），界面只是**现读现翻**：
 * 目录里那一行的名字取正文第一行【…】里那段，所以加一章不用另外写标题。
 * 折行在这里做（中文按字量宽度，不按空格断），语言文件里那一行可以写成整段。
 */
@OnlyIn(Dist.CLIENT)
public class ManualScreen extends Screen {

    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 240;
    /** 页头（标题 + 第几章）与页脚（操作提示 + 关闭）各留一条 */
    private static final int HEADER = 26;
    private static final int FOOTER = 26;
    /** 左边目录那一列的宽度 */
    private static final int TOC_WIDTH = 132;
    private static final int ROW_HEIGHT = 11;
    private static final int LINE_HEIGHT = 12;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_DIM = 0x808080;
    private static final int COLOR_PICKED = 0x55FF55;
    private static final int COLOR_ROW = 0x30FFFFFF;

    /** 目录：一行一章 */
    private final List<Entry> entries = new ArrayList<>();
    private int selected;
    private int scroll;
    /** 当前这一章折好行的正文；换章或改窗口大小时重算 */
    private List<String> bodyLines = List.of();

    private record Entry(String key, String label) {
    }

    public ManualScreen() {
        super(Component.translatable("gui.blueprint.manual.title"));
        for (String key : ManualItem.PAGES) {
            entries.add(new Entry(key, labelOf(Component.translatable(key).getString())));
        }
    }

    /** 目录里那一行的名字：正文第一行去掉【】，没有标题行就退回第一行原文 */
    private static String labelOf(String body) {
        int end = body.indexOf('\n');
        String first = (end < 0 ? body : body.substring(0, end)).replace("【", "").replace("】", "").trim();
        return first.isEmpty() ? "?" : first;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.manual.close"),
                        b -> onClose())
                .bounds(left + WINDOW_WIDTH - 66, top + WINDOW_HEIGHT - 22, 60, 18).build());
        rewrap();
    }

    /** 把当前这一章折成一行行文本（宽度按正文那一栏算） */
    private void rewrap() {
        String body = Component.translatable(this.entries.get(this.selected).key()).getString();
        int width = WINDOW_WIDTH - TOC_WIDTH - 24;
        List<String> lines = new ArrayList<>();
        for (String raw : body.split("\n", -1)) {
            if (raw.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (line.length() > 0 && this.font.width(line.toString() + c) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(c);
            }
            lines.add(line.toString());
        }
        this.bodyLines = lines;
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    private int visibleLines() {
        return (WINDOW_HEIGHT - HEADER - FOOTER) / LINE_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, this.bodyLines.size() - visibleLines());
    }

    private void select(int index) {
        this.selected = Math.floorMod(index, this.entries.size());
        this.scroll = 0;
        rewrap();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.hLine(left + 2, left + WINDOW_WIDTH - 2, top + HEADER - 4, COLOR_DIVIDER);
        graphics.vLine(left + TOC_WIDTH + 6, top + HEADER,
                top + WINDOW_HEIGHT - FOOTER + 4, COLOR_DIVIDER);

        // 页头：书名 + 目录两个字，右边写"第几章"
        graphics.drawString(this.font, this.title, left + 8, top + 8, COLOR_TEXT, false);
        graphics.drawString(this.font, Component.translatable("gui.blueprint.manual.toc"),
                left + 18, top + HEADER + 2, COLOR_LABEL, false);
        Component page = Component.translatable("gui.blueprint.manual.chapter",
                this.selected + 1, this.entries.size());
        graphics.drawString(this.font, page,
                left + WINDOW_WIDTH - 8 - this.font.width(page), top + 8, COLOR_LABEL, false);
        // 页脚左边那行操作提示
        graphics.drawString(this.font, Component.translatable("gui.blueprint.manual.hint"),
                left + 8, top + WINDOW_HEIGHT - 17, COLOR_DIM, false);

        drawToc(graphics, left, top, mouseX, mouseY);
        drawBody(graphics, left, top);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawToc(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + 8;
        int y = top + HEADER + 14;
        for (int i = 0; i < this.entries.size(); i++) {
            boolean hovered = mouseX >= left + 4 && mouseX < left + TOC_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (i == this.selected || hovered) {
                graphics.fill(left + 4, y - 1, left + TOC_WIDTH, y + ROW_HEIGHT - 1,
                        i == this.selected ? COLOR_ROW : 0x18FFFFFF);
            }
            int color = i == this.selected ? COLOR_PICKED : (hovered ? COLOR_TEXT : COLOR_LABEL);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(this.entries.get(i).label(), TOC_WIDTH - 12),
                    x, y, color, false);
            y += ROW_HEIGHT;
        }
    }

    private void drawBody(GuiGraphics graphics, int left, int top) {
        int x = left + TOC_WIDTH + 14;
        int y = top + HEADER + 2;
        int end = Math.min(this.bodyLines.size(), this.scroll + visibleLines());
        for (int i = this.scroll; i < end; i++) {
            String line = this.bodyLines.get(i);
            // 标题那一行（【…】）加粗一点也认不出，用颜色区分就够了
            graphics.drawString(this.font, line, x, y, line.startsWith("【") ? COLOR_PICKED : COLOR_TEXT,
                    false);
            y += LINE_HEIGHT;
        }
        // 还有下文时，右下角写一句，免得玩家以为就这么多
        if (this.scroll < maxScroll()) {
            Component more = Component.translatable("gui.blueprint.manual.more",
                    this.scroll + visibleLines(), this.bodyLines.size());
            graphics.drawString(this.font, more,
                    left + WINDOW_WIDTH - 8 - this.font.width(more),
                    top + WINDOW_HEIGHT - FOOTER + 4, COLOR_DIM, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        int y = top + HEADER + 14;
        for (int i = 0; i < this.entries.size(); i++) {
            if (mouseX >= left + 4 && mouseX < left + TOC_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                select(i);
                return true;
            }
            y += ROW_HEIGHT;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = this.scroll - (int) Math.signum(delta);
        this.scroll = Math.max(0, Math.min(next, maxScroll()));
        return true;
    }

    /** ←/→ 翻章，↑/↓ 滚正文——目录能点，但键盘总得有条路 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                select(this.selected - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                select(this.selected + 1);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                this.scroll = Math.max(0, this.scroll - 1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                this.scroll = Math.min(maxScroll(), this.scroll + 1);
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
