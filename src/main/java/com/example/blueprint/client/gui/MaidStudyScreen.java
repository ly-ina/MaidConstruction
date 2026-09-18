package com.example.blueprint.client.gui;

import com.example.blueprint.integration.maid.MaidCraftOrder;
import com.example.blueprint.integration.maid.MaidStudyPool;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SForgetStudyPacket;
import com.example.blueprint.network.packet.C2SMaidCraftOrderPacket;
import com.example.blueprint.network.packet.C2SSetStudyPriorityPacket;
import com.example.blueprint.network.packet.C2SToggleStudyUsePacket;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 女仆的**学习池**界面：蹲下 + 空手右键自己的女仆打开。
 * <p>
 * 左边一格一样**产物**（不放配方：一样产物可能有好多配方，平铺出来全是重复的图标）；
 * 配方多于一个的产物**描一圈金框**并标上数量，提醒"这个有好几种做法"。
 * 右边是选中那样产物会做的配方，**第 1 条就是她手搓时会照做的那个**；
 * <b>点一条配方就把它排到最前</b>（这就是主人设置优先级的方式），
 * 选中的产物再点一下则往后轮换一个，省得每次都去点右边的小字。
 * <p>
 * 上面那个搜索框按**名字或物品 id** 过滤产物，池子一大就靠它找人。
 * <p>
 * 数据不用请求：池子是 TLM 的 {@code TaskDataKey}，女仆身上那份已经同步到客户端了，
 * 这里直接读。改优先级要发一个 C2S 包，服务端改完会顺着同步链路把新顺序推回来。
 */
@OnlyIn(Dist.CLIENT)
public class MaidStudyScreen extends Screen {

    private static final int WINDOW_WIDTH = 360;
    private static final int WINDOW_HEIGHT = 232;
    private static final int HEADER_HEIGHT = 22;

    private static final int GRID_X = 10;
    private static final int GRID_Y = HEADER_HEIGHT + 8;
    private static final int COLUMNS = 8;
    private static final int ROWS = 6;
    private static final int CELL = 18;

    /** 右边配方面板 */
    private static final int PANEL_X = GRID_X + COLUMNS * CELL + 8;
    private static final int PANEL_WIDTH = WINDOW_WIDTH - PANEL_X - 10;
    private static final int RECIPE_ROW_HEIGHT = 12;
    /** 右边最多列几条配方，再多的靠"点产物轮换" */
    private static final int MAX_RECIPE_ROWS = 7;
    /** 用途页一行列几个图标：面板宽只有 88，18 一格正好 4 列 */
    private static final int USES_COLUMNS = 4;
    /** 用途页的图标格子 */
    private static final int USES_CELL = 18;
    /** 用途页一屏几行（剩下的地方留给"点一下跳过去"那句提示） */
    private static final int USES_ROWS = 5;
    private static final int MINI_CELL = 18;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_ROW = 0xBBBBBB;
    private static final int COLOR_PRIORITY = 0xFFFF55;
    private static final int COLOR_SELECTED = 0x55FFFF;
    /** 停用：灰（被按了暂停的东西） */
    private static final int COLOR_DISABLED = 0xFF808080;
    /** 按下去会**停用**：红 */
    private static final int COLOR_WILL_DISABLE = 0xFFFF5555;
    /** 按下去会**启用**：绿 */
    private static final int COLOR_WILL_ENABLE = 0xFF55FF55;

    private final int maidEntityId;
    /**
     * 选中的产物**在池子里的下标**（不是过滤后列表里的位置）。
     * <p>
     * 这一点必须钉死：设优先级要把它发给服务端，服务端是按池子下标去改的；
     * 要是这里存"过滤后的第几个"，搜一下再点，改中的就是另一样东西了。
     */
    private int selected = -1;
    /** 产物列表滚到第几行（按过滤后的列表算） */
    private int scrollRow = 0;
    /** 用途列表滚到第几行 */
    private int usesScrollRow = 0;
    /**
     * 用途列表的缓存：**配方表整表遍历一遍不便宜**，而绘制、点击、滚轮每帧都要问一次，
     * 不缓存就是一帧三遍全表扫描。键是"查的是哪个产物"，换了产物才重算。
     */
    private ItemStack usesKey = ItemStack.EMPTY;
    private List<ItemStack> usesValue = List.of();

    /** 数量框里起手放的数字，主人自己改 */
    private static final String DEFAULT_ORDER_COUNT = "1";

    private EditBox searchBox;
    private EditBox countBox;
    private Button orderButton;
    private Button cancelButton;
    private String filter = "";
    /** 右边的面板在看"她的配方"还是在看"这东西能用来做什么"（右键产物切换） */
    private boolean showUses = false;

    private int left;
    private int top;

    public MaidStudyScreen(int maidEntityId) {
        super(Component.translatable("gui.blueprint.study.title"));
        this.maidEntityId = maidEntityId;
    }

    @Override
    protected void init() {
        this.left = (this.width - WINDOW_WIDTH) / 2;
        this.top = (this.height - WINDOW_HEIGHT) / 2;

        // 搜索框放在标题右边、标题栏里（面板从 HEADER_HEIGHT 下面才开始，不会压到槽位）
        this.searchBox = new EditBox(this.font, left + 70, top + 4, 180, 14,
                Component.translatable("gui.blueprint.study.search"));
        this.searchBox.setMaxLength(48);
        this.searchBox.setHint(Component.translatable("gui.blueprint.study.search_hint"));
        this.searchBox.setValue(filter);
        this.searchBox.setResponder(this::onSearchChanged);
        this.addRenderableWidget(this.searchBox);
        // 打开就能直接打字；不想要的话按 Tab/Esc 放开焦点即可
        this.setInitialFocus(this.searchBox);

        // 下单控件放在产物格子下面（那一片是空的）。
        // 数量由主人自己敲：只收数字、最多三位，上限跟服务端那张单一致（256）
        int buttonY = top + 144;
        this.countBox = new EditBox(this.font, left + 10, buttonY - 1, 58, 18,
                Component.translatable("gui.blueprint.study.order_count"));
        this.countBox.setMaxLength(3);
        this.countBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,3}"));
        this.countBox.setValue(DEFAULT_ORDER_COUNT);
        this.countBox.setHint(Component.translatable("gui.blueprint.study.order_count"));
        this.addRenderableWidget(this.countBox);

        this.orderButton = Button.builder(Component.translatable("gui.blueprint.study.order"),
                        b -> sendOrder(selected, typedCount()))
                .bounds(left + 74, buttonY, 80, 18).build();
        this.addRenderableWidget(this.orderButton);
        this.cancelButton = Button.builder(Component.translatable("gui.blueprint.study.cancel_orders"),
                        b -> sendOrder(-1, 0))
                .bounds(left + 10, buttonY + 22, 144, 18).build();
        this.addRenderableWidget(this.cancelButton);
    }

    /**
     * 主人敲进去的数量：空着、或者写了个 0，都当成"没填"（返回 0）。
     * <p>
     * 上限就地夹到服务端那张单的上限，省得他敲个 999 之后看着数字不动又不知道为什么。
     */
    private int typedCount() {
        if (countBox == null) {
            return 0;
        }
        try {
            return Math.min(MaidCraftOrder.MAX_COUNT,
                    Math.max(0, Integer.parseInt(countBox.getValue().trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void onSearchChanged(String text) {
        this.filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        // 换了关键词就回到第一行，否则会停在一个已经不存在的位置上
        this.scrollRow = 0;
    }

    /** 界面开着的时候女仆还要干活，别暂停世界 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 数据（都从她身上现取，不缓存）
    // ------------------------------------------------------------------

    private EntityMaid maid() {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        return Minecraft.getInstance().level.getEntity(maidEntityId) instanceof EntityMaid maid ? maid : null;
    }

    private List<MaidStudyPool.Learned> pool() {
        EntityMaid maid = maid();
        return maid == null ? List.of() : MaidStudyPool.known(maid);
    }

    /**
     * 过滤后还剩哪些产物：返回的是**池子下标**（渲染与点击都靠它回查，发网络包也用它）。
     * <p>
     * 匹配名字或物品 id 都算——中文端搜"火把"、原版名搜 "torch"、模组物品直接敲
     * {@code ae2:...} 那串都能找到，省得记它到底翻译成了什么。
     */
    private List<Integer> matchingIndices(List<MaidStudyPool.Learned> pool) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (filter.isEmpty() || matches( pool.get(i), filter)) {
                matches.add(i);
            }
        }
        return matches;
    }

    private static boolean matches(MaidStudyPool.Learned learned, String filter) {
        ItemStack product = learned.product();
        if (product.getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(product.getItem());
        return id != null && id.toString().contains(filter);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 刻意不调 renderBackground：那会铺一层背景纹理（没进世界时是土方块），
        // 这里只要半透明压暗，保证面板上的字看得清（跟蓝图面板一个规矩）。
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.fill(left + 4, top + HEADER_HEIGHT, left + WINDOW_WIDTH - 4, top + HEADER_HEIGHT + 1,
                COLOR_DIVIDER);
        graphics.fill(left + PANEL_X - 4, top + HEADER_HEIGHT + 4, left + PANEL_X - 3,
                top + WINDOW_HEIGHT - 6, COLOR_DIVIDER);

        graphics.drawString(this.font, this.title, left + 8, top + 7, COLOR_TEXT, false);
        // 搜索框是 widget，得跟着一起画（放在这里，保证压在背景上面）
        super.render(graphics, mouseX, mouseY, partialTick);

        if (maid() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.maid_gone"),
                    left + 10, top + GRID_Y + 4, COLOR_LABEL, false);
            return;
        }

        List<MaidStudyPool.Learned> pool = pool();
        refreshOrderControls(pool);
        if (pool.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.empty"),
                    left + 10, top + GRID_Y + 4, COLOR_LABEL, false);
            return;
        }

        List<Integer> matches = matchingIndices(pool);

        // 选中项要是被过滤掉了、或者池子变小了，下标可能越界，渲染前先收一下
        if (selected >= pool.size()) {
            selected = pool.size() - 1;
        }
        int maxScroll = Math.max(0, (matches.size() + COLUMNS - 1) / COLUMNS - ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow));

        if (matches.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.no_match"),
                    left + 10, top + GRID_Y + 4, COLOR_LABEL, false);
        } else {
            drawProductGrid(graphics, pool, matches, mouseX, mouseY);
        }

        if (selected >= 0 && selected < pool.size()) {
            if (showUses) {
                drawUsesPanel(graphics, pool.get(selected), mouseX, mouseY);
            } else {
                drawRecipePanel(graphics, pool.get(selected), mouseX, mouseY);
            }
        } else {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.pick_hint"),
                    left + PANEL_X, top + GRID_Y + 4, COLOR_LABEL, false);
        }
        // 翻页提示挪到标题栏右边：网格底下那点地方要留给下单控件
        int totalRows = (matches.size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > ROWS) {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.study.scroll", scrollRow + 1,
                            totalRows - ROWS + 1),
                    left + 256, top + 7, COLOR_LABEL, false);
        }
        // 底下这两行都按窗口宽度截断：产物名一长（或换成英文更长）就会捅出面板外，
        // 操作指南那句尤其是——写全了永远超宽，所以既缩短文案、又在这里兜一道。
        Component queue = queueText();
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(queue.getString(), WINDOW_WIDTH - 20),
                left + 10, top + 206, COLOR_LABEL, false);
        Component footer = Component.translatable("gui.blueprint.study.footer");
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(footer.getString(), WINDOW_WIDTH - 16),
                left + 8, top + WINDOW_HEIGHT - 12, COLOR_LABEL, false);
    }

    /**
     * 下单那几个按钮的文字与亮灭。
     * <p>
     * 亮灭必须自己算（原版按钮默认永远是亮的）：没选中产物、或者那号产物没有可用的配方时，
     * "下单"该是灰的——点下去只能换来一句"她不会做"，白跑一趟。
     */
    private void refreshOrderControls(List<MaidStudyPool.Learned> pool) {
        if (orderButton != null) {
            MaidStudyPool.Learned picked = selected >= 0 && selected < pool.size()
                    ? pool.get(selected) : null;
            // preferred() 已经跳过停用的配方，所以"所有做法都被停用"也会落到这里变灰
            MaidStudyPool.Recipe preferred = picked == null ? null : picked.preferred();
            // 停用的产物不接新单；数量没填（或者填了 0）也变灰：协议里 0 是"撤单"，
            // 别让点一下变成撤掉全部
            orderButton.active = picked != null && !picked.disabled()
                    && preferred != null && preferred.id() != null && typedCount() > 0;
        }
        EntityMaid maid = maid();
        if (cancelButton != null) {
            cancelButton.active = maid != null && !MaidCraftOrder.pending(maid).isEmpty();
        }
    }

    /** 排队情况：排头那张单做什么、还剩几个、一共几个 */
    private Component queueText() {
        EntityMaid maid = maid();
        if (maid == null) {
            return Component.empty();
        }
        List<MaidCraftOrder.Order> orders = MaidCraftOrder.pending(maid);
        if (orders.isEmpty()) {
            return Component.translatable("gui.blueprint.study.queue_empty");
        }
        MaidCraftOrder.Order head = orders.get(0);
        ItemStack root = head.root();
        // 零件单得把"为了做 X"也说出来：否则主人只看见她在搓木棍，不知道在给谁备料
        if (root != null) {
            return Component.translatable("gui.blueprint.study.queue_nested",
                    head.product().getHoverName(), head.remaining(), root.getHoverName());
        }
        return Component.translatable("gui.blueprint.study.queue",
                head.product().getHoverName(), head.remaining(),
                MaidCraftOrder.pendingAmount(maid), orders.size());
    }

    /** 左边：一格一样产物；配方多于一个的描金框 + 角上一个数字 */
    private void drawProductGrid(GuiGraphics graphics, List<MaidStudyPool.Learned> pool,
                                 List<Integer> matches, int mouseX, int mouseY) {
        int visible = COLUMNS * ROWS;
        for (int i = 0; i < visible; i++) {
            int position = scrollRow * COLUMNS + i;
            if (position >= matches.size()) {
                break;
            }
            int index = matches.get(position);
            MaidStudyPool.Learned learned = pool.get(index);
            int x = left + GRID_X + (i % COLUMNS) * CELL;
            int y = top + GRID_Y + (i / COLUMNS) * CELL;
            boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
            boolean shift = hasShiftDown();
            // Shift 按下时把鼠标所在产物描出来，颜色就是"点下去会变成什么"：
            // 红 = 将被停用，绿 = 将被启用
            int actionColor = learned.disabled() ? COLOR_WILL_ENABLE : COLOR_WILL_DISABLE;
            if (hovered && shift) {
                graphics.fill(x, y, x + CELL, y + CELL, actionColor & 0x40FFFFFF);
            }
            graphics.renderItem(learned.product(), x, y);
            // 停用的产物压一层灰：一眼看出"这个她不做"
            if (learned.disabled()) {
                graphics.fill(x, y, x + CELL, y + CELL, 0x80000000);
            }

            if (index == selected) {
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_SELECTED);
            } else if (learned.disabled()) {
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_DISABLED);
            } else if (learned.hasMultipleRecipes()) {
                // 多配方：金框提示"这个能挑做法"
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_PRIORITY);
            }
            if (hovered && shift) {
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, actionColor);
            }
            if (learned.hasMultipleRecipes()) {
                graphics.drawString(this.font, String.valueOf(learned.recipes().size()),
                        x + 10, y + 9, COLOR_PRIORITY, true);
            }
        }
    }

    /** 右边：选中产物的配方清单 + 优先配方的 3×3 摆法 */
    private void drawRecipePanel(GuiGraphics graphics, MaidStudyPool.Learned learned,
                                 int mouseX, int mouseY) {
        int x = left + PANEL_X;
        int y = top + GRID_Y;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(learned.product().getHoverName().getString(),
                        PANEL_WIDTH - 4),
                x, y, COLOR_TEXT, false);

        Component count = Component.translatable("gui.blueprint.study.recipe_count",
                learned.recipes().size());
        graphics.drawString(this.font, count, x, y + 12, COLOR_LABEL, false);
        if (learned.hasMultipleRecipes()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.multi"),
                    x + this.font.width(count) + 6, y + 12, COLOR_PRIORITY, false);
        }

        if (learned.recipes().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.no_recipe"),
                    x, y + 26, COLOR_LABEL, false);
            return;
        }

        int rows = Math.min(learned.recipes().size(), MAX_RECIPE_ROWS);
        // "优先"标在**第一条启用的**那条上：被停用的那条她并不会照着做
        int preferredIndex = learned.preferredIndex();
        for (int i = 0; i < rows; i++) {
            MaidStudyPool.Recipe recipe = learned.recipes().get(i);
            int rowY = y + 26 + i * RECIPE_ROW_HEIGHT;
            boolean priority = i == preferredIndex;
            boolean off = recipe.disabled();
            String label = (i + 1) + ". " + describe(recipe) + (off ? " ✕" : "");
            int color = off ? COLOR_DISABLED : (priority ? COLOR_PRIORITY : COLOR_ROW);
            boolean hovered = mouseX >= x && mouseX < x + PANEL_WIDTH
                    && mouseY >= rowY && mouseY < rowY + RECIPE_ROW_HEIGHT;
            if (hovered) {
                graphics.fill(x - 1, rowY - 1, x + PANEL_WIDTH, rowY + RECIPE_ROW_HEIGHT - 1,
                        0x30FFFFFF);
            }
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(label, PANEL_WIDTH - (off ? 10 : priority ? 28 : 2)),
                    x, rowY, color, false);
            if (priority) {
                graphics.drawString(this.font, Component.translatable("gui.blueprint.study.priority"),
                        x + PANEL_WIDTH - this.font.width(
                                Component.translatable("gui.blueprint.study.priority")),
                        rowY, COLOR_PRIORITY, false);
            }
        }
        if (learned.recipes().size() > rows) {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.study.more_recipes",
                            learned.recipes().size() - rows),
                    x, y + 26 + rows * RECIPE_ROW_HEIGHT, COLOR_LABEL, false);
        }

        // 优先配方的摆法：她手搓时就是照这个摆的
        int gridY = top + WINDOW_HEIGHT - MINI_CELL * 3 - 20;
        graphics.drawString(this.font, Component.translatable("gui.blueprint.study.priority_layout"),
                x, gridY - 11, COLOR_LABEL, false);
        List<ItemStack> grid = learned.preferred() == null ? List.of() : learned.preferred().grid();
        for (int i = 0; i < Math.min(grid.size(), 9); i++) {
            ItemStack stack = grid.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int cellX = x + (i % 3) * MINI_CELL;
            int cellY = gridY + (i / 3) * MINI_CELL;
            graphics.renderItem(stack, cellX, cellY);
            if (mouseX >= cellX && mouseX < cellX + 16 && mouseY >= cellY && mouseY < cellY + 16) {
                graphics.renderTooltip(this.font, stack, mouseX, mouseY);
            }
        }
    }

    /**
     * 右边：这东西能**用来做什么**（右键产物切到这一页）。
     * <p>
     * 列的是"她会做的东西里，哪些用到它"（见 {@link #learnedUsesOf}）——
     * 不是全世界的配方，是**她学过的**。想让她往下做点什么，翻这一页比翻配方表快：
     * 每一条都点得过去、都能直接下单。
     */
    private void drawUsesPanel(GuiGraphics graphics, MaidStudyPool.Learned learned,
                               int mouseX, int mouseY) {
        int x = left + PANEL_X;
        int y = top + GRID_Y;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(learned.product().getHoverName().getString(),
                        PANEL_WIDTH - 4),
                x, y, COLOR_TEXT, false);

        List<ItemStack> uses = learnedUsesOf(learned.product());
        graphics.drawString(this.font,
                Component.translatable("gui.blueprint.study.uses_count", uses.size()),
                x, y + 12, COLOR_LABEL, false);
        if (uses.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.no_uses"),
                    x, y + 26, COLOR_LABEL, false);
            return;
        }

        // 用**图标**而不是名字：面板只有 88 宽，一行中文名字塞不下三个字就得截断，
        // 反而不如直接看图（而且看图标才知道"这东西长什么样"）。
        // 一格一样产物，跟左边那片格子一个规矩；多了就滚轮翻页。
        int totalRows = (uses.size() + USES_COLUMNS - 1) / USES_COLUMNS;
        usesScrollRow = Math.max(0, Math.min(Math.max(0, totalRows - USES_ROWS), usesScrollRow));

        int listY = y + 26;
        for (int i = 0; i < USES_COLUMNS * USES_ROWS; i++) {
            int position = usesScrollRow * USES_COLUMNS + i;
            if (position >= uses.size()) {
                break;
            }
            ItemStack result = uses.get(position);
            int cellX = x + (i % USES_COLUMNS) * USES_CELL;
            int cellY = listY + (i / USES_COLUMNS) * USES_CELL;
            graphics.renderItem(result, cellX, cellY);
            if (mouseX >= cellX && mouseX < cellX + 16 && mouseY >= cellY && mouseY < cellY + 16) {
                graphics.renderTooltip(this.font, result, mouseX, mouseY);
            }
        }
        if (totalRows > USES_ROWS) {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.study.scroll", usesScrollRow + 1,
                            totalRows - USES_ROWS + 1),
                    x, listY + USES_ROWS * USES_CELL + 2, COLOR_LABEL, false);
        }
        graphics.drawString(this.font, this.font.plainSubstrByWidth(
                        Component.translatable("gui.blueprint.study.uses_hint").getString(),
                        PANEL_WIDTH),
                x, listY + USES_ROWS * USES_CELL + 14, COLOR_LABEL, false);
    }

    /**
     * 这东西**能用来做什么**：只列她**已经学会的**、配方摆法里用到它的那些产物。
     * <p>
     * 为什么不扫配方表：那样列出来的是"全世界所有配方"，木板之类能有几百条，
     * 其中她会做的就那么几样，主人点过去多半撞上"她不会做"——对
     * "接下来让她做什么"这件事没有帮助，看着还以为丢了好多。
     * 反过来从池子查，列出来的**每一条都点得过去、都能直接下单**，这才是有用的清单。
     * <p>
     * 顺带也绕开了原来那个坑：扫全表必须自己设上限（不然爆表），设了就必然截断、
     * 必然"缺失严重"。这里查的是她已经学会的东西，**有多少条就给多少条，不必砍**。
     */
    private List<ItemStack> learnedUsesOf(ItemStack material) {
        if (material.isEmpty()) {
            return List.of();
        }
        if (ItemStack.matches(usesKey, material)) {
            return usesValue;
        }
        List<ItemStack> uses = new ArrayList<>();
        for (MaidStudyPool.Learned learned : pool()) {
            // 自己不算自己的用途
            if (ItemStack.matches(learned.product(), material)) {
                continue;
            }
            for (MaidStudyPool.Recipe recipe : learned.recipes()) {
                if (usesMaterial(recipe.grid(), material)) {
                    uses.add(learned.product());
                    break; // 同一样产物只列一次（它可能好几条配方都用到这个）
                }
            }
        }
        usesKey = material.copyWithCount(1);
        usesValue = List.copyOf(uses);
        return usesValue;
    }

    /**
     * 这套摆法里有没有用到这个材料。
     * <p>
     * 只比**物品种类**（{@code isSameItem}），不比数量也不比 NBT：
     * 池子里存的摆法每格本来就只有 1 个，而"半耐久的工具也能当材料"这类
     * NBT 差异不该让一条用途凭空消失。
     */
    private static boolean usesMaterial(List<ItemStack> grid, ItemStack material) {
        for (ItemStack slot : grid) {
            if (!slot.isEmpty() && ItemStack.isSameItem(slot, material)) {
                return true;
            }
        }
        return false;
    }

    /** 这个产物在她池子里的下标；她不会做返回 -1 */
    private static int poolIndexOf(List<MaidStudyPool.Learned> pool, ItemStack product) {
        for (int i = 0; i < pool.size(); i++) {
            if (ItemStack.matches(pool.get(i).product(), product)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 一条配方在界面上怎么念给自己听：优先念**材料**（主人认得"煤炭 + 木棍"），
     * 材料也没有（老数据）才退回去念配方 id。
     */
    private String describe(MaidStudyPool.Recipe recipe) {
        List<ItemStack> ingredients = ingredients(recipe.grid());
        if (!ingredients.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (ItemStack stack : ingredients) {
                if (builder.length() > 0) {
                    builder.append(" + ");
                }
                builder.append(stack.getHoverName().getString());
            }
            return builder.toString();
        }
        return recipe.displayId().isEmpty()
                ? Component.translatable("gui.blueprint.study.unknown_recipe").getString()
                : recipe.displayId();
    }

    /** 摆法里出现过的材料（去重、去空格），按"哪种材料"看而不是按摆了几个 */
    private static List<ItemStack> ingredients(List<ItemStack> grid) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : grid) {
            if (stack.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (ItemStack known : result) {
                if (ItemStack.isSameItemSameTags(known, stack)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(stack);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<MaidStudyPool.Learned> pool = pool();
        List<Integer> matches = matchingIndices(pool);
        int productIndex = productIndexAt(matches, mouseX, mouseY);

        // 右键产物 = 看它能用来做什么；左键才是"看她的配方、设优先级"。
        // Shift 按下时右键另有含义——**按暂停**，而且分两级：
        // 点产物是整样停用/启用，点右边某一条配方是只停用那一条做法
        // （产物管"做不做"，配方管"用哪个做法"，正好跟两列的层次对上）。
        if (button == 1) {
            if (productIndex >= 0) {
                if (hasShiftDown()) {
                    toggleUse(productIndex, -1);
                    return true;
                }
                selected = productIndex;
                showUses = true;
                usesScrollRow = 0; // 换了产物就从头看，别停在上一页的位置上
                return true;
            }
            if (hasShiftDown()) {
                int recipeIndex = recipeRowAt(pool, mouseX, mouseY);
                if (recipeIndex >= 0) {
                    toggleUse(selected, recipeIndex);
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (productIndex >= 0) {
            // Shift+左键 = 忘掉这个产物（连同它的配方）。不做二次确认：删了只是暂时不会做，
            // 主人再演示一次就补回来了，丢得起；要的就是"随手清掉记歪的东西"。
            if (hasShiftDown()) {
                sendForget(productIndex);
                return true;
            }
            showUses = false;
            if (productIndex == selected && pool.get(productIndex).hasMultipleRecipes()) {
                // 同一个产物再点一下：把下一条配方轮换到最前（多条时不用去点右边的小字）
                sendPriority(productIndex, 1);
            } else {
                selected = productIndex;
            }
            return true;
        }

        if (selected >= 0 && selected < pool.size()
                && mouseX >= left + PANEL_X && mouseX < left + PANEL_X + PANEL_WIDTH) {
            int rowY = top + GRID_Y + 26;
            if (showUses) {
                // 用途模式：点一个图标就跳到那个产物（列表本来就是从池子里查出来的，
                // 每一条都点得过去；这里再查一次下标是为了拿到它当下的位置）
                List<ItemStack> uses = learnedUsesOf(pool.get(selected).product());
                int index = useIndexAt(uses, mouseX, mouseY);
                if (index >= 0) {
                    int target = poolIndexOf(pool, uses.get(index));
                    if (target >= 0) {
                        selected = target;
                        showUses = false;
                        usesScrollRow = 0;
                    }
                    return true;
                }
            } else {
                int recipeRows = Math.min(pool.get(selected).recipes().size(), MAX_RECIPE_ROWS);
                for (int i = 0; i < recipeRows; i++) {
                    int y = rowY + i * RECIPE_ROW_HEIGHT;
                    if (mouseY >= y && mouseY < y + RECIPE_ROW_HEIGHT) {
                        sendPriority(selected, i);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 用途页、鼠标又在右边面板上：滚用途那一列，别去动左边的产物网格
        List<MaidStudyPool.Learned> pool = pool();
        if (showUses && selected >= 0 && selected < pool.size()
                && mouseX >= left + PANEL_X && mouseX < left + PANEL_X + PANEL_WIDTH) {
            int totalRows = (learnedUsesOf(pool.get(selected).product()).size() + USES_COLUMNS - 1)
                    / USES_COLUMNS;
            int maxScroll = Math.max(0, totalRows - USES_ROWS);
            usesScrollRow = Math.max(0,
                    Math.min(maxScroll, usesScrollRow + (delta < 0 ? 1 : -1)));
            return true;
        }
        int totalRows = (matchingIndices(pool).size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, totalRows - ROWS);
        int next = scrollRow + (delta < 0 ? 1 : -1);
        scrollRow = Math.max(0, Math.min(maxScroll, next));
        return true;
    }

    /**
     * 鼠标底下那个产物**在池子里的下标**；不在格子上返回 -1。
     * <p>
     * 之所以绕一层 {@code matches}：画面按过滤后的顺序排，而"第几个产物"这件事
     * 必须换算回池子下标，否则搜索之后点击与设优先级全会错位。
     */
    private int productIndexAt(List<Integer> matches, double mouseX, double mouseY) {
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            int position = scrollRow * COLUMNS + i;
            if (position >= matches.size()) {
                return -1;
            }
            int x = left + GRID_X + (i % COLUMNS) * CELL;
            int y = top + GRID_Y + (i / COLUMNS) * CELL;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return matches.get(position);
            }
        }
        return -1;
    }

    /**
     * 鼠标底下是第几条配方（右边配方面板里，**仅"她的配方"那一页**）；不在任何一行上返回 -1。
     * <p>
     * 用途那一页不参与：那一页列的是"配方表里谁拿它当材料"，不是她会做的做法，停不停用无关。
     */
    private int recipeRowAt(List<MaidStudyPool.Learned> pool, double mouseX, double mouseY) {
        if (showUses || selected < 0 || selected >= pool.size()) {
            return -1;
        }
        if (mouseX < left + PANEL_X || mouseX >= left + PANEL_X + PANEL_WIDTH) {
            return -1;
        }
        int rows = Math.min(pool.get(selected).recipes().size(), MAX_RECIPE_ROWS);
        int rowY = top + GRID_Y + 26;
        for (int i = 0; i < rows; i++) {
            int y = rowY + i * RECIPE_ROW_HEIGHT;
            if (mouseY >= y && mouseY < y + RECIPE_ROW_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 鼠标底下是第几条**用途**（右边用途页的图标格子上）；不在格子上返回 -1。
     * <p>
     * 返回的是**列表下标**（含滚动偏移），跟画的时候用的 {@code usesScrollRow} 同一套算法，
     * 否则滚过一页之后点中的会是另一样东西。
     */
    private int useIndexAt(List<ItemStack> uses, double mouseX, double mouseY) {
        int listY = top + GRID_Y + 26;
        for (int i = 0; i < USES_COLUMNS * USES_ROWS; i++) {
            int position = usesScrollRow * USES_COLUMNS + i;
            if (position >= uses.size()) {
                return -1;
            }
            int cellX = left + PANEL_X + (i % USES_COLUMNS) * USES_CELL;
            int cellY = listY + (i / USES_COLUMNS) * USES_CELL;
            if (mouseX >= cellX && mouseX < cellX + 16 && mouseY >= cellY && mouseY < cellY + 16) {
                return position;
            }
        }
        return -1;
    }

    /**
     * 按下/解除暂停键：{@code recipeIndex < 0} 是整样产物，否则是那号产物的第几条配方。
     * <p>
     * 发的是**目标状态**而不是"翻转一下"：界面这份池子是同步过来的，可能比服务端旧一拍，
     * 让服务端去翻就可能翻错方向；把"我现在看到的是什么"一并告诉它就没这个窗口。
     */
    private void toggleUse(int productIndex, int recipeIndex) {
        List<MaidStudyPool.Learned> pool = pool();
        if (productIndex < 0 || productIndex >= pool.size()) {
            return;
        }
        boolean currentlyDisabled;
        if (recipeIndex < 0) {
            currentlyDisabled = pool.get(productIndex).disabled();
        } else {
            List<MaidStudyPool.Recipe> recipes = pool.get(productIndex).recipes();
            if (recipeIndex >= recipes.size()) {
                return;
            }
            currentlyDisabled = recipes.get(recipeIndex).disabled();
        }
        ModNetwork.CHANNEL.sendToServer(new C2SToggleStudyUsePacket(
                maidEntityId, productIndex, recipeIndex, !currentlyDisabled));
    }

    /**
     * 下单 / 撤单。
     * <p>
     * {@code count <= 0} 表示撤掉全部待做（{@code productIndex} 那时没有意义），
     * 跟服务端那个包一个约定。
     */
    private void sendOrder(int productIndex, int count) {
        // 防一手：撤单走的是同一个包，别让"数量没填"被当成撤单发出去
        if (count <= 0 && productIndex >= 0) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(
                new C2SMaidCraftOrderPacket(maidEntityId, productIndex, count));
    }

    private void sendPriority(int productIndex, int recipeIndex) {
        if (recipeIndex <= 0) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(
                new C2SSetStudyPriorityPacket(maidEntityId, productIndex, recipeIndex));
    }

    /** 忘掉一样产物（连同它的配方）。删完池子会经同步刷新，先把选中清掉给个即时反馈 */
    private void sendForget(int productIndex) {
        ModNetwork.CHANNEL.sendToServer(new C2SForgetStudyPacket(maidEntityId, productIndex));
        selected = -1;
        showUses = false;
        usesScrollRow = 0;
    }
}
