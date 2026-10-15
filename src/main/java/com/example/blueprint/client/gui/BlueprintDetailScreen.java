package com.example.blueprint.client.gui;

import com.example.blueprint.build.BuildSession;
import com.example.blueprint.client.BlueprintLibrary;
import com.example.blueprint.client.BlueprintRecordSession;
import com.example.blueprint.client.BlueprintLayerView;
import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.ClientPostBinding;
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
import net.minecraft.core.GlobalPos;
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
    /** 全部方块（不抽稀）：分层要"先按层筛、再抽稀"，所以这份得留着 */
    private final List<Schematic.BlockEntry> allEntries;
    /** 分层查看：只看某一层 / 看到某一层为止（滚轮或 ↑↓ 换层，Shift 切"只看这层"） */
    private final BlueprintLayerView layers = new BlueprintLayerView();
    /** 当前实际要画的那一份，见 {@link #shown()} */
    private List<Schematic.BlockEntry> shown = List.of();
    private int shownVersion = -1;
    private final List<Row> rows = new ArrayList<>();
    /** 没有对应物品的方块数（水、火这类），单独提一句，免得玩家以为漏算了 */
    private int plainBlocks;

    private float yaw = 35.0F;
    private float pitch = 22.0F;
    private boolean dragging;
    /** 正在拖分层那条：拖它的时候别再顺手转视角（两件事都在同一块预览上，不分开就会互相打架） */
    private boolean draggingLayer;
    /** 滚轮缩放：预览的放大倍数（1 = 原样）。画图与拾取都乘它，两边不会错位 */
    private float zoom = 1.0F;
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
        this.allEntries = this.schematic == null ? List.of() : this.schematic.entries();
        // 层数与起点按这张图重算，"看到第几层"回到整栋
        this.layers.reset(this.schematic, this.allEntries);
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
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.detail.take"), b -> onTake())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        // 「投影」紧随"取到手上"：两件事都是"把这份图纸用起来"，一个进她/我的手上，一个进指挥台
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.detail.project"),
                        b -> onProject())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.library.open_folder"),
                        b -> Util.getPlatform().openFile(BlueprintTransfer.getExportDirectory().toFile()))
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.detail.delete"), b -> onDelete())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.detail.back"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen()))
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        // 调试入口：改图纸数据（左边照旧浏览、右边换成能搜的物品列表）。
        // 读不出来的图没有可编辑的数据，那就不给这个按钮
        if (this.schematic != null) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.edit"),
                            b -> Minecraft.getInstance().setScreen(
                                    new BlueprintEditScreen(this.entry.name(), this.schematic)))
                    .bounds(buttonX, y + 22, BUTTON_WIDTH, 20).build());
        }
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
            graphics.drawCenteredString(this.font, Component.translatable("gui.maidconstruction.detail.broken"),
                    left + WINDOW_WIDTH / 2, top + WINDOW_HEIGHT / 2, COLOR_WARN);
        } else {
            drawPreview(graphics, left, top, mouseX, mouseY);
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
        Component detail = Component.translatable("gui.maidconstruction.library.size",
                size.getX(), size.getY(), size.getZ(), this.schematic.countBlocks());
        graphics.drawString(this.font, detail,
                left + WINDOW_WIDTH - 8 - this.font.width(detail), top + 7, COLOR_LABEL, false);
    }

    private void drawPreview(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + 6;
        int y = top + HEADER_HEIGHT + 4;
        int height = WINDOW_HEIGHT - HEADER_HEIGHT - 26;
        graphics.fill(x, y, x + PREVIEW_WIDTH, y + height, 0x60000000);
        graphics.renderOutline(x, y, PREVIEW_WIDTH, height, COLOR_DIVIDER);

        // 画与拾取用同一个缩放：scaleFor 给基准，界面自己的缩放倍数乘上去
        float scale = SchematicPreview.scaleFor(this.schematic, PREVIEW_WIDTH - 6) * this.zoom;
        SchematicPreview.draw(graphics, this.schematic, shown(),
                x + PREVIEW_WIDTH / 2, y + height / 2, PREVIEW_WIDTH - 6,
                this.yaw, this.pitch, Rotation.NONE, Mirror.NONE, this.zoom);

        // 左上角：状态一行 + 小问号（说明收进问号里，悬浮才展开，免得横跨预览框）
        int helpX = x + 3;
        int helpY = y + 3;
        boolean overHelp = mouseX >= helpX - 1 && mouseX < helpX + 11
                && mouseY >= helpY - 1 && mouseY < helpY + 11;
        graphics.fill(helpX, helpY, helpX + 10, helpY + 10, overHelp ? 0xFF3A3A55 : 0xA0202030);
        graphics.renderOutline(helpX, helpY, 10, 10, 0x80808080);
        graphics.drawString(this.font, "?", helpX + 2, helpY + 1, 0xFFFFFF, false);
        graphics.drawString(this.font, this.layers.stateText(), x + 17, y + 5, 0xDDDDDD, true);
        // 拖动条：底＝第 1 层，顶＝整栋；左键拖/点换层，右键切"只看这层"
        this.layers.drawBar(graphics, barX(), barTop(), barBottom());

        // 悬浮方块名：用上面算好的那个 scale 去投，指哪算哪
        Schematic.BlockEntry hovered = SchematicPreview.pick(this.schematic, shown(),
                x + PREVIEW_WIDTH / 2, y + height / 2, scale,
                this.yaw, this.pitch, mouseX, mouseY);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 400.0D);
        if (overHelp) {
            SchematicPreview.drawHintBox(graphics, this.font, BlueprintLayerView.helpLines(),
                    mouseX, mouseY, this.width, this.height);
        } else if (hovered != null) {
            SchematicPreview.drawHintBox(graphics, this.font, List.of(this.layers.describe(hovered)),
                    mouseX, mouseY, this.width, this.height);
        }
        graphics.pose().popPose();
    }

    /**
     * 现在该画哪一份：整栋就是预先抽稀好的，分层时**先按层筛、再抽稀**。
     * <p>
     * 顺序不能反——一层本来就没剩几块，先抽稀再筛等于把那几块也丢了，看着就像"这层是空的"。
     * 结果按状态版本缓存：换层才算一次（上万条的图，放进每帧都跑的绘制里是白烧 CPU）。
     */
    private List<Schematic.BlockEntry> shown() {
        if (this.shownVersion == this.layers.version()) {
            return this.shown;
        }
        List<Schematic.BlockEntry> kept = this.layers.filter(this.allEntries);
        this.shown = kept.size() > SchematicPreview.MAX_BLOCKS
                ? SchematicPreview.sample(kept, SchematicPreview.MAX_BLOCKS) : kept;
        this.shownVersion = this.layers.version();
        return this.shown;
    }

    private void drawList(GuiGraphics graphics) {
        graphics.drawString(this.font,
                Component.translatable("gui.maidconstruction.detail.materials", this.rows.size(), countMaterials()),
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
                    Component.translatable("gui.maidconstruction.detail.position",
                            this.scrollRow + 1,
                            Math.min(this.rows.size(), this.scrollRow + visibleRows()),
                            this.rows.size()),
                    this.listX, this.listY + this.listHeight + 2, COLOR_LABEL, false);
        }
        if (this.plainBlocks > 0) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            Component.translatable("gui.maidconstruction.materials.plain", this.plainBlocks).getString(),
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
                + Component.translatable("gui.maidconstruction.detail.stack_pair", count / 64, count % 64).getString();
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
        // 先接分层条：它贴着预览框右边缘，落在预览里但压在最上面
        if (BlueprintLayerView.overBar(mouseX, mouseY, barX(), barTop(), barBottom())) {
            if (button == 1) {
                this.layers.toggleSlice();
            } else {
                this.draggingLayer = true;
                this.layers.dragTo(mouseY, barTop(), barBottom(), Screen.hasShiftDown());
            }
            return true;
        }
        // 右键点预览任意处＝切"看到这层为止 / 只看这一层"，不必非得点在条上
        if (button == 1 && isOverPreview(mouseX, mouseY)) {
            this.layers.toggleSlice();
            return true;
        }
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
        if (this.draggingLayer) {
            this.layers.dragTo(mouseY, barTop(), barBottom(), Screen.hasShiftDown());
            return true;
        }
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

    // 分层拖动条的三个换算：画与点共用（贴着预览框右边缘，上下各留余量）
    private int barX() {
        return (this.width - WINDOW_WIDTH) / 2 + 6 + PREVIEW_WIDTH - 12;
    }

    private int barTop() {
        return (this.height - WINDOW_HEIGHT) / 2 + HEADER_HEIGHT + 8;
    }

    private int barBottom() {
        return (this.height - WINDOW_HEIGHT) / 2 + HEADER_HEIGHT + 4
                + (WINDOW_HEIGHT - HEADER_HEIGHT - 26) - 6;
    }

    /** 指针在不在这块预览框里：转身子的拖动与它共用一处判据，免得矩形写两遍、错一次都不好查 */
    private boolean isOverPreview(double mouseX, double mouseY) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        return mouseX >= left + 6 && mouseX < left + 6 + PREVIEW_WIDTH
                && mouseY >= top + HEADER_HEIGHT + 4
                && mouseY < top + HEADER_HEIGHT + 4 + (WINDOW_HEIGHT - HEADER_HEIGHT - 26);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        this.draggingLayer = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 预览框上的滚轮＝缩放；指针在材料清单那边时才翻页——各管一摊，不用抢
        if (delta != 0.0D && isOverPreview(mouseX, mouseY)) {
            double factor = delta > 0.0D ? 1.1D : 1.0D / 1.1D;
            this.zoom = (float) Math.max(0.35D, Math.min(4.0D, this.zoom * factor));
            return true;
        }

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
            setStatus(Component.translatable("gui.maidconstruction.detail.take_hint"), COLOR_WARN);
            return;
        }
        try {
            byte[] data = Files.readAllBytes(this.entry.path());
            ModNetwork.CHANNEL.sendToServer(new C2SImportBlueprintPacket(data, this.entry.name()));
            setStatus(Component.translatable("gui.maidconstruction.detail.take_sent"), COLOR_STATUS);
        } catch (IOException e) {
            setStatus(Component.translatable("gui.maidconstruction.detail.broken"), COLOR_WARN);
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
        // 附近没有（走远了、人在别的维度、那台还没同步过来）就退回"我绑的那一台"：
        // 服务端本来就是按绑定认目标的，客户端这一步只是为了别在能成的时候先自己拦下
        if (target == null) {
            GlobalPos bound = ClientPostBinding.current();
            if (bound != null) {
                target = bound.pos();
            }
        }
        if (target == null) {
            setStatus(Component.translatable("gui.maidconstruction.detail.no_post"), COLOR_WARN);
            return;
        }
        try {
            byte[] data = Files.readAllBytes(this.entry.path());
            BlueprintRecordSession.startProjection(target, this.entry.name(), data);
            this.onClose();
        } catch (IOException e) {
            setStatus(Component.translatable("gui.maidconstruction.detail.broken"), COLOR_WARN);
        }
    }

    private void onDelete() {
        if (!this.deleteArmed) {
            this.deleteArmed = true;
            setStatus(Component.translatable("gui.maidconstruction.detail.delete_confirm"), COLOR_WARN);
            return;
        }
        try {
            Files.deleteIfExists(this.entry.path());
            // 目录变了得重新列一遍，不然回到列表还会看见它
            BlueprintLibrary.refresh();
            Minecraft.getInstance().setScreen(new BlueprintLibraryScreen());
        } catch (IOException e) {
            this.deleteArmed = false;
            setStatus(Component.translatable("gui.maidconstruction.detail.delete_failed"), COLOR_WARN);
        }
    }

    private void setStatus(Component text, int color) {
        this.status = text;
        this.statusColor = color;
    }
}
