package com.example.blueprint.client.gui;

import com.example.blueprint.build.BuildSession;
import com.example.blueprint.client.BlueprintLibrary;
import com.example.blueprint.client.BlueprintRecordSession;
import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.CommandPostProjections;
import com.example.blueprint.client.SchematicPreview;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 一份图纸的详情：左边看模型、中间看材料、右边是能对它做的事。
 * <p>
 * 材料清单走 {@link BuildSession#bill}——和女仆实际取料是同一份账，所以这里列出来的
 * 就是她真会去拿的东西（AE2 的线缆按上面的部件拆开算）。自己另写一套"方块 → 物品"的换算，
 * 迟早会和建造那边对不上。
 * <p>
 * 这个界面**只读文件、不碰手上那张蓝图**：想用某一份就点"取到手上"把它导进去。
 * 浏览和"我正在建的那张"互不干扰——翻图纸时顺手把手里那张改掉，是很容易挨骂的行为。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintDetailScreen extends Screen {

    private static final int WINDOW_WIDTH = 420;
    private static final int WINDOW_HEIGHT = 250;
    private static final int HEADER_HEIGHT = 22;
    private static final int PREVIEW_WIDTH = 150;
    private static final int BUTTON_WIDTH = 76;
    private static final int ROW_HEIGHT = 18;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_COUNT = 0xFFFF55;
    private static final int COLOR_STATUS = 0x55FF55;
    private static final int COLOR_WARN = 0xFF5555;

    private final BlueprintLibrary.Entry entry;
    /** 从某台指挥台的界面进来的话是它那一格；从终端进来是 null，见 {@link #onProject} */
    @Nullable
    private final BlockPos targetPost;
    @Nullable
    private final Schematic schematic;
    /** 抽稀过的方块清单：预览每帧都要用，别在绘制里重算 */
    private final List<Schematic.BlockEntry> preview;
    private final List<Row> rows = new ArrayList<>();
    /** 没有对应物品的方块数（水、火这类），单独提一句，免得玩家以为漏算了 */
    private int plainBlocks;

    private float yaw = 35.0F;
    private float pitch = 22.0F;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    private int scrollRow;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;

    private Component status = Component.empty();
    private int statusColor = COLOR_STATUS;
    /** 删除要按两次：第一次只是问一句，再点一次才真删 */
    private boolean deleteArmed;

    private record Row(ItemStack stack, int count) {
    }

    public BlueprintDetailScreen(BlueprintLibrary.Entry entry, @Nullable BlockPos targetPost) {
        super(Component.literal(entry.name()));
        this.entry = entry;
        this.targetPost = targetPost;
        this.schematic = entry.schematic();
        this.preview = this.schematic == null
                ? List.of() : SchematicPreview.sample(this.schematic, SchematicPreview.MAX_BLOCKS);
        if (this.schematic != null) {
            buildRows(this.schematic);
        }
    }

    /** 界面开着的时候世界照常跑（与面板、清单、学习池一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 材料清单：按数量从多到少排，同数量按名字，免得顺序每帧变 */
    private void buildRows(Schematic schematic) {
        Map<Item, Integer> bill = BuildSession.bill(schematic);
        for (Map.Entry<Item, Integer> material : bill.entrySet()) {
            this.rows.add(new Row(new ItemStack(material.getKey()), material.getValue()));
        }
        this.rows.sort(Comparator.comparingInt(Row::count).reversed()
                .thenComparing(row -> row.stack().getHoverName().getString()));

        int accounted = 0;
        for (Row row : this.rows) {
            accounted += row.count();
        }
        // 一个方块可能要好几样东西，所以"记进账的件数"可能比方块数还多——减成负数就当 0
        this.plainBlocks = Math.max(0, schematic.countBlocks() - accounted);
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        this.listX = left + PREVIEW_WIDTH + 12;
        this.listY = top + HEADER_HEIGHT + 12;
        this.listWidth = WINDOW_WIDTH - PREVIEW_WIDTH - BUTTON_WIDTH - 26;
        this.listHeight = WINDOW_HEIGHT - HEADER_HEIGHT - 34;
        this.scrollRow = Math.min(this.scrollRow, maxScrollRow());

        int buttonX = left + WINDOW_WIDTH - BUTTON_WIDTH - 6;
        int y = top + HEADER_HEIGHT + 6;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.detail.take"), b -> onTake())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        // 「投影」紧随"取到手上"：两件事都是"把这份图纸用起来"，一个进她/我的手上，一个进指挥台
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.detail.project"),
                        b -> onProject())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.open_folder"),
                        b -> Util.getPlatform().openFile(BlueprintTransfer.getExportDirectory().toFile()))
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.detail.delete"), b -> onDelete())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.detail.back"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen()))
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.hLine(left + 2, left + WINDOW_WIDTH - 2, top + HEADER_HEIGHT, COLOR_DIVIDER);
        drawTitle(graphics, left, top);

        if (this.schematic == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blueprint.detail.broken"),
                    left + WINDOW_WIDTH / 2, top + WINDOW_HEIGHT / 2, COLOR_WARN);
        } else {
            drawPreview(graphics, left, top);
            drawList(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!this.status.getString().isEmpty()) {
            graphics.drawString(this.font, this.status, left + 8, top + WINDOW_HEIGHT - 14, this.statusColor, false);
        }
    }

    private void drawTitle(GuiGraphics graphics, int left, int top) {
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(this.entry.name(), PREVIEW_WIDTH + this.listWidth - 16),
                left + 8, top + 7, COLOR_TEXT, false);
        if (this.schematic == null) {
            return;
        }
        Vec3i size = this.schematic.getSize();
        Component detail = Component.translatable("gui.blueprint.library.size",
                size.getX(), size.getY(), size.getZ(), this.schematic.countBlocks());
        graphics.drawString(this.font, detail,
                left + WINDOW_WIDTH - 8 - this.font.width(detail), top + 7, COLOR_LABEL, false);
    }

    private void drawPreview(GuiGraphics graphics, int left, int top) {
        int x = left + 6;
        int y = top + HEADER_HEIGHT + 4;
        int height = WINDOW_HEIGHT - HEADER_HEIGHT - 26;
        graphics.fill(x, y, x + PREVIEW_WIDTH, y + height, 0x60000000);
        graphics.renderOutline(x, y, PREVIEW_WIDTH, height, COLOR_DIVIDER);

        SchematicPreview.draw(graphics, this.schematic, this.preview,
                x + PREVIEW_WIDTH / 2, y + height / 2, PREVIEW_WIDTH - 6,
                this.yaw, this.pitch, Rotation.NONE, Mirror.NONE);
    }

    private void drawList(GuiGraphics graphics) {
        graphics.drawString(this.font,
                Component.translatable("gui.blueprint.detail.materials", this.rows.size(), countMaterials()),
                this.listX, this.listY - 10, COLOR_LABEL, false);

        graphics.enableScissor(this.listX, this.listY, this.listX + this.listWidth, this.listY + this.listHeight);
        for (int i = 0; i < visibleRows() && this.scrollRow + i < this.rows.size(); i++) {
            Row row = this.rows.get(this.scrollRow + i);
            int y = this.listY + i * ROW_HEIGHT + 2;
            graphics.renderItem(row.stack(), this.listX, y);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(row.stack().getHoverName().getString(), this.listWidth - 58),
                    this.listX + 20, y + 4, COLOR_TEXT, false);
            String count = countText(row.count());
            graphics.drawString(this.font, count,
                    this.listX + this.listWidth - 4 - this.font.width(count), y + 4, COLOR_COUNT, false);
        }
        graphics.disableScissor();

        if (this.rows.size() > visibleRows()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.detail.position",
                            this.scrollRow + 1,
                            Math.min(this.rows.size(), this.scrollRow + visibleRows()),
                            this.rows.size()),
                    this.listX, this.listY + this.listHeight + 2, COLOR_LABEL, false);
        }
        if (this.plainBlocks > 0) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            Component.translatable("gui.blueprint.materials.plain", this.plainBlocks).getString(),
                            this.listWidth),
                    this.listX, this.listY + this.listHeight + 12, COLOR_LABEL, false);
        }
    }

    /** 「×75」；满一组的再补一句「1组+11」（75 写作「1组+11」），凑料时不必自己换算 */
    private String countText(int count) {
        if (count < 64) {
            return "×" + count;
        }
        return "×" + count + "  "
                + Component.translatable("gui.blueprint.detail.stack_pair", count / 64, count % 64).getString();
    }

    private int countMaterials() {
        int total = 0;
        for (Row row : this.rows) {
            total += row.count();
        }
        return total;
    }

    private int visibleRows() {
        return Math.max(1, this.listHeight / ROW_HEIGHT);
    }

    private int maxScrollRow() {
        return Math.max(0, this.rows.size() - visibleRows());
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /** 拖动左半边的模型换视角；中间那份清单要能滚，所以按位置分开处理 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        if (mouseX >= left + 6 && mouseX < left + 6 + PREVIEW_WIDTH
                && mouseY >= top + HEADER_HEIGHT && mouseY < top + WINDOW_HEIGHT) {
            this.dragging = true;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return true;
        }

        // 先交给控件。**不能在这之前清 deleteArmed**："删要按两次"的流程是
        // 第一下 arm、第二下真删，而这一句会把第二下刚要用到的 arm 提前擦掉，
        // 于是第二下又被当成第一下——表现就是"点了没反应，永远删不掉"。
        // 点了别处才该撤销这次确认，所以清除放在控件没接手之后。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        this.deleteArmed = false;
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging) {
            this.yaw += (float) (mouseX - this.lastMouseX) * 1.5F;
            this.pitch = Math.max(-90.0F, Math.min(90.0F,
                    this.pitch + (float) (mouseY - this.lastMouseY) * 1.5F));
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
        if (delta == 0.0D || this.rows.size() <= visibleRows()) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(),
                this.scrollRow + (delta > 0.0D ? -1 : 1)));
        return true;
    }

    /**
     * 把这份图纸放进一张**空白蓝图**，并让它**落到手上**。
     * <p>
     * 走的是已有的导入包（客户端读文件、服务端写物品 NBT）。空白蓝图**不必先拿在手上**：
     * 服务端会在主手、副手、快捷栏、主背包里找第一张空白的（{@code BlueprintItem.findBlankToHand}），
     * 不在手上就与主手对调——否则每次都得先翻背包腾出手再回来点一次，纯属白跑。
     * <p>
     * 这里先判一遍是**为了把话说清楚**：开着界面时聊天栏是不画的（§7.17），
     * 等服务端回一句"没有空白蓝图"等于什么都没发生。
     */
    private void onTake() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!BlueprintItem.hasBlank(player)) {
            setStatus(Component.translatable("gui.blueprint.detail.take_hint"), COLOR_WARN);
            return;
        }
        try {
            byte[] data = Files.readAllBytes(this.entry.path());
            ModNetwork.CHANNEL.sendToServer(new C2SImportBlueprintPacket(data, this.entry.name()));
            setStatus(Component.translatable("gui.blueprint.detail.take_sent"), COLOR_STATUS);
        } catch (IOException e) {
            setStatus(Component.translatable("gui.blueprint.detail.broken"), COLOR_WARN);
        }
    }

    /**
     * 把这份图纸的投影托管到指挥台上：**关掉界面回到世界，位置与朝向当场摆**。
     * <p>
     * **不需要手上拿着蓝图**：选中的就是这份文件，字节先留在客户端会话里，
     * 等在世界里按 E 定下来时再和位置、朝向一起发给服务端（存成一份结构数据，
     * 指挥台记的是它的 id——渲染、施工、别人围观都照旧按 id 走）。
     * <p>
     * 托管到哪一台：从某台指挥台的界面进来的话就是它（一路带过来的那一格）；
     * 从终端进来的话取**最近一台属于我的**，省得先跑去右键它一次。
     * 一台都没有就在界面上说一句——开着界面时聊天栏不画（§7.17）。
     */
    private void onProject() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        BlockPos target = this.targetPost != null
                ? this.targetPost
                : CommandPostProjections.nearestMine(player.blockPosition(), player.getUUID());
        if (target == null) {
            setStatus(Component.translatable("gui.blueprint.detail.no_post"), COLOR_WARN);
            return;
        }
        try {
            byte[] data = Files.readAllBytes(this.entry.path());
            BlueprintRecordSession.startProjection(target, this.entry.name(), data);
            this.onClose();
        } catch (IOException e) {
            setStatus(Component.translatable("gui.blueprint.detail.broken"), COLOR_WARN);
        }
    }

    private void onDelete() {
        if (!this.deleteArmed) {
            this.deleteArmed = true;
            setStatus(Component.translatable("gui.blueprint.detail.delete_confirm"), COLOR_WARN);
            return;
        }
        try {
            Files.deleteIfExists(this.entry.path());
            // 目录变了得重新列一遍，不然回到列表还会看见它
            BlueprintLibrary.refresh();
            Minecraft.getInstance().setScreen(new BlueprintLibraryScreen());
        } catch (IOException e) {
            this.deleteArmed = false;
            setStatus(Component.translatable("gui.blueprint.detail.delete_failed"), COLOR_WARN);
        }
    }

    private void setStatus(Component text, int color) {
        this.status = text;
        this.statusColor = color;
    }
}
