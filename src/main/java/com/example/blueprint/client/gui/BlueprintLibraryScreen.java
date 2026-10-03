package com.example.blueprint.client.gui;

import com.example.blueprint.client.BlueprintLibrary;
import com.example.blueprint.client.BlueprintRecordSession;
import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.SchematicPreview;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 蓝图终端的主界面：把图纸目录里的建筑一份份摆出来。
 * <p>
 * 每一格都真的画一遍那座建筑（缩略图），而不是只列文件名——图纸光看名字分不清是"那座塔"
 * 还是"塔的底座"，一眼扫过去能认出来才是这个界面存在的意义。
 * <p>
 * 为了这个"一眼"，代价必须压住：只画**当前屏上看得见的那几格**，每格再按
 * {@link #THUMB_BLOCKS} 抽稀。抽稀结果按文件缓存，不然每帧都要把每座建筑遍历一遍。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintLibraryScreen extends Screen {

    private static final int WINDOW_WIDTH = 380;
    /** 比只放一行按钮时高 22：底下多了一行"名字 + 录制"（女仆名单已经挪进独立界面，见 MaidRosterScreen） */
    private static final int WINDOW_HEIGHT = 272;
    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 28;
    private static final int COLUMNS = 3;
    private static final int CARD_WIDTH = 118;
    private static final int CARD_HEIGHT = 76;
    private static final int CARD_GAP = 6;
    /** 缩略图最多画这么多方块：六格同屏，每格都得省着用 */
    private static final int THUMB_BLOCKS = 220;
    /** 缩略图的固定视角：斜上方看，既不正面也不像俯视图 */
    private static final float THUMB_YAW = 35.0F;
    private static final float THUMB_PITCH = 22.0F;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_CARD = 0xB0101014;
    private static final int COLOR_CARD_SELECTED = 0xC02A2A18;

    /**
     * 缩略图的抽稀结果，按**条目的键**（路径 + 换过几次）缓存：
     * 同一份图纸不必每帧重新遍历；同名覆盖之后键就变了，不会取到上一版抽稀出来的旧点位。
     */
    private static final Map<String, List<Schematic.BlockEntry>> THUMBNAILS = new HashMap<>();

    /** 从指挥台界面进来时带着的那一格，见构造器 */
    @Nullable
    private final BlockPos targetPost;
    private List<BlueprintLibrary.Entry> entries = List.of();
    private int scrollRow;
    /** 图纸名：录制时用它当文件名，也是列表里显示的名字 */
    private EditBox nameBox;
    /** 页头那行反馈（名字没填之类），没有时右上角照常显示张数 */
    private Component status = Component.empty();

    /** 从终端进来：没指定指挥台，点「投影」时找最近那台属于自己的 */
    public BlueprintLibraryScreen() {
        this(null);
    }

    /**
     * @param targetPost 从某台指挥台的界面进来时带着的那一格：点「投影」就放到它身上；
     *                   从终端进来传 null，那时找最近一台属于自己的指挥台
     */
    public BlueprintLibraryScreen(@Nullable BlockPos targetPost) {
        super(Component.translatable("gui.blueprint.library.title"));
        this.targetPost = targetPost;
    }

    /** 界面开着的时候世界照常跑（与面板、清单、学习池一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        BlueprintLibrary.refresh();
        this.entries = BlueprintLibrary.entries();
        this.scrollRow = Math.min(this.scrollRow, maxScrollRow());

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        int y = top + WINDOW_HEIGHT - 22;

        // 录制那一排：名字 + 录制。摆在页脚上面一行，不去挤页脚那三个按钮
        // （它们的横向位置是按"刷新 | 打开文件夹 | 关闭"配好的）
        int toolsY = top + WINDOW_HEIGHT - 44;
        this.nameBox = new EditBox(this.font, left + 6, toolsY + 1, 240, 16,
                Component.translatable("gui.blueprint.library.name_hint"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setHint(Component.translatable("gui.blueprint.library.name_hint"));
        this.addRenderableWidget(this.nameBox);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.record"),
                        b -> onRecord())
                .bounds(left + 250, toolsY, 120, 18).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.refresh"),
                        b -> refresh())
                .bounds(left + 6, y, 60, 18).build());
        // 女仆名册单开一个界面（那里画得下立绘）：这里只留一个入口，名单不再挤在这一行
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.maids.title"), b -> openRoster())
                .bounds(left + 160, y, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.open_folder"),
                        b -> Util.getPlatform().openFile(BlueprintTransfer.getExportDirectory().toFile()))
                .bounds(left + 70, y, 76, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.close"),
                        b -> onClose())
                .bounds(left + WINDOW_WIDTH - 66, y, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 目录每五秒会自己重扫一次（见 BlueprintLibrary#entries），这里把结果接上：
        // 图纸都是在游戏外面替换的（同名覆盖），界面开着的时候就该自己换过来，不必手点刷新
        this.entries = BlueprintLibrary.entries();
        this.scrollRow = Math.min(this.scrollRow, maxScrollRow());

        // 与蓝图面板同一层压暗：不调 renderBackground，它会铺一层世界没加载时的土方块纹理
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.hLine(left + 2, left + WINDOW_WIDTH - 2, top + HEADER_HEIGHT, COLOR_DIVIDER);
        graphics.drawString(this.font, this.title, left + 8, top + 7, COLOR_TEXT, false);
        // 页头右上角平时显示张数，有反馈信息时让给它——这一行是界面上唯一稳定可见的空位
        boolean hasStatus = !this.status.getString().isEmpty();
        Component header = hasStatus
                ? this.status : Component.translatable("gui.blueprint.library.count", this.entries.size());
        graphics.drawString(this.font, header, left + WINDOW_WIDTH - 8 - this.font.width(header),
                top + 7, hasStatus ? COLOR_TEXT : COLOR_LABEL, false);

        if (this.entries.isEmpty()) {
            int cy = top + WINDOW_HEIGHT / 2;
            drawCentered(graphics, Component.translatable("gui.blueprint.library.empty"), cy - 6, COLOR_LABEL);
            drawCentered(graphics, Component.translatable("gui.blueprint.library.empty_hint"), cy + 8, COLOR_LABEL);
        } else {
            drawCards(graphics, left, top, mouseX, mouseY);
            drawPageIndicator(graphics, left, top);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------
    // 卡片
    // ------------------------------------------------------------------

    private void drawCards(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int startRow = this.scrollRow;
        for (int row = 0; row < visibleRows(); row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int index = (startRow + row) * COLUMNS + col;
                if (index >= this.entries.size()) {
                    return;
                }
                int x = left + 6 + col * (CARD_WIDTH + CARD_GAP);
                int y = top + HEADER_HEIGHT + 4 + row * (CARD_HEIGHT + CARD_GAP);
                drawCard(graphics, index, x, y, mouseX, mouseY);
            }
        }
    }

    private void drawCard(GuiGraphics graphics, int index, int x, int y, int mouseX, int mouseY) {
        BlueprintLibrary.Entry entry = this.entries.get(index);
        boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;

        graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered ? COLOR_CARD_SELECTED : COLOR_CARD);
        graphics.renderOutline(x, y, CARD_WIDTH, CARD_HEIGHT, hovered ? COLOR_TEXT : COLOR_DIVIDER);

        Schematic schematic = entry.schematic();
        if (schematic != null) {
            List<Schematic.BlockEntry> thumbnail = THUMBNAILS.computeIfAbsent(entry.cacheKey(),
                    key -> SchematicPreview.sample(schematic, THUMB_BLOCKS));
            SchematicPreview.draw(graphics, schematic, thumbnail,
                    x + CARD_WIDTH / 2, y + CARD_HEIGHT / 2 - 4, CARD_WIDTH - 6,
                    THUMB_YAW, THUMB_PITCH, Rotation.NONE, Mirror.NONE);
        }

        // 名字压在下沿那条暗条上：缩略图可能延伸到整格，先垫一层底色才看得清字
        int labelY = y + CARD_HEIGHT - 24;
        graphics.fill(x + 1, labelY, x + CARD_WIDTH - 1, y + CARD_HEIGHT - 1, 0xC0000000);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(entry.name(), CARD_WIDTH - 8),
                x + 4, labelY + 2, COLOR_TEXT, false);
        graphics.drawString(this.font, describe(entry, schematic), x + 4, labelY + 11, COLOR_LABEL, false);
    }

    /** 卡片底下那一行：尺寸与方块数；读不出来就照实说 */
    private Component describe(BlueprintLibrary.Entry entry, Schematic schematic) {
        if (schematic == null) {
            return Component.translatable(entry.broken()
                    ? "gui.blueprint.library.broken" : "gui.blueprint.library.loading");
        }
        Vec3i size = schematic.getSize();
        return Component.translatable("gui.blueprint.library.size",
                size.getX(), size.getY(), size.getZ(), schematic.countBlocks());
    }

    private void drawCentered(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawCenteredString(this.font, text, this.width / 2, y, color);
    }

    /** 页数写在标题栏右侧，和蓝图面板、清单同一个位置 */
    private void drawPageIndicator(GuiGraphics graphics, int left, int top) {
        int pages = totalRows();
        if (pages <= visibleRows()) {
            return;
        }
        Component text = Component.translatable("gui.blueprint.library.scroll",
                this.scrollRow + 1, pages - visibleRows() + 1);
        graphics.drawString(this.font, text,
                left + 8, top + WINDOW_HEIGHT - 15, COLOR_LABEL, false);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        int index = indexAt(left, top, mouseX, mouseY);
        if (index >= 0) {
            // 把"这次是从哪台指挥台进来的"一路带进详情页：那里的「投影」就放到它身上
            this.minecraft.setScreen(new BlueprintDetailScreen(this.entries.get(index), this.targetPost));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        // 与文件列表一致：向上拨往回一页
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(),
                this.scrollRow + (delta > 0.0D ? -1 : 1)));
        return true;
    }

    private int indexAt(int left, int top, double mouseX, double mouseY) {
        for (int row = 0; row < visibleRows(); row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int x = left + 6 + col * (CARD_WIDTH + CARD_GAP);
                int y = top + HEADER_HEIGHT + 4 + row * (CARD_HEIGHT + CARD_GAP);
                if (mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT) {
                    int index = (this.scrollRow + row) * COLUMNS + col;
                    return index < this.entries.size() ? index : -1;
                }
            }
        }
        return -1;
    }

    private int visibleRows() {
        return Math.max(1, (WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 8) / (CARD_HEIGHT + CARD_GAP));
    }

    private int totalRows() {
        return Math.max(1, (this.entries.size() + COLUMNS - 1) / COLUMNS);
    }

    private int maxScrollRow() {
        return Math.max(0, totalRows() - visibleRows());
    }

    /** 重新列一遍目录。新录的、刚从别处拷进来的图纸，点一下就出现 */
    private void refresh() {
        BlueprintLibrary.refresh();
        this.entries = BlueprintLibrary.entries();
        this.scrollRow = 0;
        // 手动刷新等于"我重新看一遍"，上一条反馈到这儿就算过去了
        this.status = Component.empty();
    }

    /**
     * 开始录制：**名字就用这个输入框**（它同时是文件名与列表里显示的名字），进世界之后
     * 右键点两个角点，E 完成——扫出来的结构直接写进 {@code blueprints} 目录，不经过蓝图。
     * <p>
     * 界面得先关掉：框选要一边看着建筑一边点，界面开着就看不全了。
     * 录制态（能飞、能穿墙）由服务端管进出，见 {@code RecordMode}。
     * <p>
     * 名字为空先拦住：那会落到 {@code blueprint.blueprint} 这种毫无意义的文件名上，
     * 与其生成出来再让玩家去改，不如在这里说一句。
     */
    private void onRecord() {
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            setStatus(Component.translatable("gui.blueprint.library.need_name"));
            return;
        }
        BlueprintRecordSession.start(name);
        this.onClose();
    }

    /** 打开女仆名册：带着"从哪台指挥台进来的"，点名字就是指派到那一台 */
    private void openRoster() {
        Minecraft.getInstance().setScreen(new MaidRosterScreen(this.targetPost));
    }

    private void setStatus(Component message) {
        this.status = message;
    }

    @Override
    public void onClose() {
        super.onClose();
    }
}
