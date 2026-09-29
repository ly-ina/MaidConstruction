package com.example.blueprint.client.gui;

import com.example.blueprint.build.BlockMaterialResolver;
import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 建筑清单：这张蓝图要用到哪些材料、各要多少。
 * <p>
 * 为什么要有它：蓝图面板只画得出那座建筑长什么样、一共多少块，**看不出要备多少料**——
 * 想提前把材料凑齐，只能对着结构一块块数。这里按方块类型汇总一遍，凑料时照着拿就行。
 * <p>
 * 数量按{@link BlockMaterialResolver}算，也就是女仆取料时用的同一套判定：
 * 大多数方块就是它自己，AE2 的线缆方块则是"线缆本体 + 贴上去的那些部件"各算一样。
 * 判断走的是同一段代码，所以这里列出来的东西和她实际会去拿的东西是一致的——
 * 自己另写一套"方块 -> 物品"的换算，迟早会和建造那边对不上。
 * <p>
 * 数据不另发网络包：结构在面板里预览时就已经下发到客户端缓存了
 * （{@link ClientSchematicCache}），这里只管去缓存里取；没取到就催一次，
 * 和面板预览是同一套流程（见 {@link BlueprintScreen#getPreview()}）。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintMaterialsScreen extends Screen {

    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 240;
    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 26;
    private static final int ROW_HEIGHT = 18;
    /** 一屏放得下几行（算出来给翻页用，别在绘制里现算） */
    private static final int VISIBLE_ROWS = (WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 6) / ROW_HEIGHT;
    /** 催结构数据的间隔，和面板那边保持一致 */
    private static final long REQUEST_INTERVAL_MS = 2000L;
    /** 装了多少个之后才值得写一句"几组" */
    private static final int STACK_SIZE = 64;

    // 配色沿用蓝图面板那一套（两个界面挨着用，颜色不一致会显得是两个人做的）
    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_ROW = 0xBBBBBB;
    private static final int COLOR_COUNT = 0xFFFF55;
    private static final int COLOR_ROW_HOVER = 0x30FFFFFF;

    /** 从哪个界面点进来的：关掉时原路返回，不直接回游戏 */
    private final Screen parent;
    private final UUID schematicId;
    private final Rotation rotation;

    private Schematic schematic;
    /** 收到"结构数据到了"的通知：手上那份可能过期了，下一帧重新取一次 */
    private boolean stale;
    private List<Line> lines = List.of();
    /** 没有对应物品的方块数（水、火这类），单独提一句，免得玩家以为漏算了 */
    private int plainBlocks;
    private int totalMaterials;

    private int scrollRow;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private long lastRequestAt;

    /** 一行：一样材料、它的图标与总数 */
    private record Line(ItemStack stack, int count) {
    }

    /**
     * @param parent     返回时回到哪个界面（蓝图面板）
     * @param schematicId 结构数据的 id，取自手上那张蓝图
     * @param rotation   当前朝向：清单本身和朝向无关，但和面板预览取的是同一份数据
     */
    public BlueprintMaterialsScreen(Screen parent, UUID schematicId, Rotation rotation) {
        super(Component.translatable("gui.blueprint.materials.title"));
        this.parent = parent;
        this.schematicId = schematicId;
        this.rotation = rotation;
    }

    /** 界面开着的时候女仆还要干活，别暂停世界（与面板、学习池一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        this.listX = left + 6;
        this.listY = top + HEADER_HEIGHT + 2;
        this.listWidth = WINDOW_WIDTH - 12;
        this.listHeight = WINDOW_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 6;

        this.addRenderableWidget(Button
                .builder(Component.translatable("gui.blueprint.materials.back"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 76, top + WINDOW_HEIGHT - 22, 70, 18)
                .build());
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /**
     * 取当前这张蓝图的结构数据；本地没有就催一次服务端。
     * <p>
     * <b>数据已经在手上就不再翻缓存</b>：这份清单只在打开时取一次，
     * 之后除非收到"结构到了"的通知（见 {@link #onSchematicArrived()}），
     * 否则每帧只是读一个字段——不必为了画同一份数据每秒查六十次哈希表。
     */
    private Schematic resolve() {
        if (this.schematic != null && !this.stale) {
            return this.schematic;
        }
        Schematic cached = ClientSchematicCache.get(schematicId, rotation);
        if (cached == null) {
            long now = System.currentTimeMillis();
            if (now - lastRequestAt > REQUEST_INTERVAL_MS) {
                lastRequestAt = now;
                ModNetwork.CHANNEL.sendToServer(new C2SRequestSchematicPacket(schematicId));
            }
            // 拿不到就先接着画手上那份（刷新失败不该把已有内容抹掉）
            return this.schematic;
        }
        this.stale = false;
        if (cached != this.schematic) {
            this.schematic = cached;
            rebuild(cached);
        }
        return this.schematic;
    }

    /**
     * 结构数据到了（多半是刚导入完就点进来看清单）：手上这份可能已经不是最新的了。
     * <p>
     * 只置一个标记，下一帧绘制时才真去取——数据已经在缓存里，取一次就够，
     * 不会出现"清空再重建"那种闪一下的效果。
     */
    public void onSchematicArrived() {
        this.stale = true;
    }

    /** 把结构里每个非空气方块换成材料，再按材料汇总 */
    private void rebuild(Schematic source) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        int plain = 0;
        for (Schematic.BlockEntry entry : source.entries()) {
            List<Item> materials = BlockMaterialResolver.materialsOf(entry);
            if (materials.isEmpty()) {
                // 水、火这类没有物品形态的方块：不算材料，但也不能装作没有
                plain++;
                continue;
            }
            for (Item item : materials) {
                counts.merge(item, 1, Integer::sum);
            }
        }

        List<Line> built = new ArrayList<>(counts.size());
        int total = 0;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            built.add(new Line(entry.getKey().getDefaultInstance(), entry.getValue()));
            total += entry.getValue();
        }
        // 多的排前面：凑料时先盯数量大的，看完就可以关掉了
        built.sort(Comparator.comparingInt(Line::count).reversed()
                .thenComparing(line -> line.stack().getHoverName().getString()));

        this.lines = built;
        this.plainBlocks = plain;
        this.totalMaterials = total;
        this.scrollRow = 0;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 和面板一样：不铺 renderBackground（那会盖出土方块背景），只要一层压暗
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.fill(left + 4, top + HEADER_HEIGHT, left + WINDOW_WIDTH - 4, top + HEADER_HEIGHT + 1,
                COLOR_DIVIDER);
        graphics.fill(left + 4, top + WINDOW_HEIGHT - FOOTER_HEIGHT, left + WINDOW_WIDTH - 4,
                top + WINDOW_HEIGHT - FOOTER_HEIGHT + 1, COLOR_DIVIDER);

        graphics.drawString(this.font, this.title, left + 8, top + 7, COLOR_TEXT, false);

        Schematic current = resolve();
        if (current == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blueprint.materials.loading"),
                    left + WINDOW_WIDTH / 2, top + WINDOW_HEIGHT / 2, COLOR_LABEL);
        } else if (lines.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blueprint.materials.empty"),
                    left + WINDOW_WIDTH / 2, top + WINDOW_HEIGHT / 2, COLOR_LABEL);
        } else {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.materials.summary", lines.size(), totalMaterials),
                    left + 96, top + 7, COLOR_LABEL, false);
            drawPageIndicator(graphics, left, top);
            drawList(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!lines.isEmpty()) {
            drawFooterNote(graphics, left, top);
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        int maxScroll = Math.max(0, lines.size() - VISIBLE_ROWS);
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scrollRow + i;
            if (index >= lines.size()) {
                break;
            }
            Line line = lines.get(index);
            int y = listY + i * ROW_HEIGHT;
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, COLOR_ROW_HOVER);
            }

            graphics.renderItem(line.stack(), listX + 2, y + 1);

            String count = String.valueOf(line.count());
            // 满一组之后补一句"几组（余几个）"：64 个以上光看数字没概念，凑料时按组拿更快。
            // **余数必须写出来**：只写"1 组"的话，75 个看着像 64 个，差的那 11 个正好漏掉
            String stacks = "";
            if (line.count() >= STACK_SIZE) {
                int full = line.count() / STACK_SIZE;
                int rest = line.count() % STACK_SIZE;
                stacks = rest == 0
                        ? Component.translatable("gui.blueprint.materials.stacks", full).getString()
                        : Component.translatable("gui.blueprint.materials.stacks_partial", full, rest)
                                .getString();
            }
            int countX = listX + listWidth - 6 - this.font.width(count);
            int nameWidth = countX - 6 - (stacks.isEmpty() ? 0 : this.font.width(stacks) + 4)
                    - (listX + 22);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(line.stack().getHoverName().getString(),
                            Math.max(24, nameWidth)),
                    listX + 22, y + 5, COLOR_ROW, false);
            if (!stacks.isEmpty()) {
                graphics.drawString(this.font, stacks, countX - 6 - this.font.width(stacks), y + 5,
                        COLOR_LABEL, false);
            }
            graphics.drawString(this.font, count, countX, y + 5, COLOR_COUNT, false);

            // 悬停提示压在最后画：先画会被后面的行盖住
            if (hovered) {
                graphics.renderTooltip(this.font, line.stack(), mouseX, mouseY);
            }
        }
    }

    /**
     * 页数写在标题栏右侧——和面板、学习池同一个位置，不用在列表旁边再挤一行。
     * 一屏放得下时不写：没有翻页可言，写上去只是噪音。
     */
    private void drawPageIndicator(GuiGraphics graphics, int left, int top) {
        if (lines.size() <= VISIBLE_ROWS) {
            return;
        }
        int pages = (lines.size() + VISIBLE_ROWS - 1) / VISIBLE_ROWS;
        Component text = Component.translatable("gui.blueprint.materials.scroll",
                scrollRow / VISIBLE_ROWS + 1, pages);
        graphics.drawString(this.font, text,
                left + WINDOW_WIDTH - 8 - this.font.width(text), top + 7, COLOR_LABEL, false);
    }

    /**
     * 底部那行：有多少处方块压根没有物品形态（水、火这类）。
     * 一句话说明"剩下的几个为什么没列出来"，否则玩家会以为清单漏算了。
     */
    private void drawFooterNote(GuiGraphics graphics, int left, int top) {
        if (plainBlocks <= 0) {
            return;
        }
        // 右边那段留给"返回"按钮
        int width = WINDOW_WIDTH - 8 - 84;
        graphics.drawString(this.font, this.font.plainSubstrByWidth(
                        Component.translatable("gui.blueprint.materials.plain", plainBlocks).getString(), width),
                left + 8, top + WINDOW_HEIGHT - 17, COLOR_LABEL, false);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverList(mouseX, mouseY)) {
            // **别把符号吃掉**：写成 max(1, signum * n) 的话上下拨都算"+1"，
            // 往下滚也只是把列表往回推一行，看上去就是"滚轮不翻页"。
            // 方向沿用面板那边（文件列表）的算法：delta 为正时往上翻，为负往下翻
            int step = (int) Math.signum(delta) * (hasShiftDown() ? 3 : 1);
            scrollRow = Mth.clamp(scrollRow - step, 0, Math.max(0, lines.size() - VISIBLE_ROWS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int maxScroll = Math.max(0, lines.size() - VISIBLE_ROWS);
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> {
                scrollRow = Mth.clamp(scrollRow + 1, 0, maxScroll);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scrollRow = Mth.clamp(scrollRow - 1, 0, maxScroll);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                scrollRow = Mth.clamp(scrollRow + VISIBLE_ROWS, 0, maxScroll);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                scrollRow = Mth.clamp(scrollRow - VISIBLE_ROWS, 0, maxScroll);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private boolean isOverList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX < listX + listWidth
                && mouseY >= listY && mouseY < listY + listHeight;
    }

    /**
     * 关闭时**原路返回蓝图面板**，不是直接回游戏：玩家多半还要接着改朝向、导出，
     * 看完清单被丢回游戏里就得再开一次。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
