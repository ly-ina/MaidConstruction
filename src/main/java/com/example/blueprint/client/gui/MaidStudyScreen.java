package com.example.blueprint.client.gui;

import com.example.blueprint.integration.maid.MaidCraftOrder;
import com.example.blueprint.integration.maid.MaidIndustryTask;
import com.example.blueprint.integration.maid.MaidSpeech;
import com.example.blueprint.integration.maid.MaidStudyPool;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SForgetStudyPacket;
import com.example.blueprint.network.packet.C2SMaidCraftOrderPacket;
import com.example.blueprint.network.packet.C2SSelectStudyRecipePacket;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
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
    /** 摆法视图里一格材料/成品的大小 */
    private static final int LAYOUT_CELL = 18;
    /** 做法编号小格：一颗多大、间隔多少、一排最多几颗（6 颗 = 6*12+5*3 = 87，正好塞进 88 的面板） */
    private static final int CHIP_SIZE = 12;
    private static final int CHIP_GAP = 3;
    private static final int CHIP_PER_ROW = 6;
    /** 用途页一行列几个图标：面板宽只有 88，18 一格正好 4 列 */
    private static final int USES_COLUMNS = 4;
    /** 用途页的图标格子 */
    private static final int USES_CELL = 18;
    /** 用途页一屏几行（剩下的地方留给"点一下跳过去"那句提示） */
    private static final int USES_ROWS = 5;
    /** 左下"她现在的活"从哪一行开始（产物格子下面那片） */
    private static final int QUEUE_Y = 144;
    /** 左下角那个"?"的边长（画与命中都用它，免得两处算法走样） */
    private static final int HELP_SIZE = 11;
    /** 界面上飘的那句话显示多久 */
    private static final long FLASH_MS = 2200L;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_ROW = 0xBBBBBB;
    private static final int COLOR_PRIORITY = 0xFFFF55;
    private static final int COLOR_SELECTED = 0x55FFFF;
    /** Shift+左键按下去会**忘掉**这个产物：红 */
    private static final int COLOR_FORGET = 0xFFFF5555;

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
    /** 右边的面板在看"她的配方"还是在看"这东西能用来做什么"（右键切换） */
    private boolean showUses = false;
    /**
     * 用途页**在问谁**。
     * <p>
     * 多数时候就是 {@code selected} 那个产物，但不一定——右键摆法里的材料格，问的是那样材料。
     * 所以这里单独存一份，不再拿 {@code pool.get(selected).product()} 当答案：
     * 那样点了材料格会显示成"当前选中产物"的用途，牛头不对马嘴。
     */
    private ItemStack usesMaterial = ItemStack.EMPTY;
    /** 界面上飘着的那句话（见 {@link #flashAt}）。四个角是它占的地方，供别的提示让路 */
    private Component flash = null;
    private long flashUntil = 0L;
    private int flashLeft;
    private int flashTop;
    private int flashRight;
    private int flashBottom;
    private int flashX;
    private int flashY;

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

        // 下单控件放在**右边面板**底部，而且是"点了产物才出现"（见 refreshOrderControls）：
        // 没选东西时不该有一排按钮杵在那儿。
        // 数量由主人自己敲：只收数字、最多三位，上限跟服务端那张单一致（256）
        this.countBox = new EditBox(this.font, left + PANEL_X, top + WINDOW_HEIGHT - 40,
                PANEL_WIDTH, 16, Component.translatable("gui.blueprint.study.order_count"));
        this.countBox.setMaxLength(3);
        this.countBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,3}"));
        this.countBox.setValue(DEFAULT_ORDER_COUNT);
        this.countBox.setHint(Component.translatable("gui.blueprint.study.order_count"));
        this.addRenderableWidget(this.countBox);

        this.orderButton = Button.builder(Component.translatable("gui.blueprint.study.order"),
                        b -> sendOrder(selected, typedCount()))
                .bounds(left + PANEL_X, top + WINDOW_HEIGHT - 22, PANEL_WIDTH, 18).build();
        this.addRenderableWidget(this.orderButton);

        // 撤单管的是**整个队列**，跟左下那块"她现在的活"是一回事，就放它下面；
        // 没有待做的单时它不出现
        this.cancelButton = Button.builder(Component.translatable("gui.blueprint.study.cancel_orders"),
                        b -> sendOrder(-1, 0))
                .bounds(left + 10, top + QUEUE_Y + 38, 144, 18).build();
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
        List<MaidStudyPool.Learned> pool = pool();
        // 控件的显示与亮灭必须在 widgets 画出来**之前**定好：
        // visible=false 的既不画、也不响应点击（这就是"点了产物才出现"的实现）
        refreshOrderControls(pool);
        // 搜索框是 widget，得跟着一起画（放在这里，保证压在背景上面）
        super.render(graphics, mouseX, mouseY, partialTick);

        drawBody(graphics, pool, mouseX, mouseY);
        // 操作指南收进左下角那个"?"，鼠标停上去才展开（见 drawHelp）：
        // 摊在外面永远嫌长（换个语言更长），底下那点地方还得留给"她现在的活"
        drawHelp(graphics, mouseX, mouseY);
        // 飘着的话最后画：它是"刚点出来的反馈"，压在所有东西上面
        drawFlash(graphics);
    }

    /**
     * 面板主体。
     * <p>
     * 单独拆出来是为了让"没东西可显示"那两条分支能就地 return，而不至于把
     * 左下角那个"?"也一起跳过——画完主体统一再画它（见 {@code render}）。
     */
    private void drawBody(GuiGraphics graphics, List<MaidStudyPool.Learned> pool,
                          int mouseX, int mouseY) {
        if (maid() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.maid_gone"),
                    left + 10, top + GRID_Y + 4, COLOR_LABEL, false);
            return;
        }

        // 左下那块"她现在的活"跟池子里有没有东西无关，先画
        drawQueue(graphics);
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
                drawUsesPanel(graphics, mouseX, mouseY);
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
        // 悬停提示压在最后画（理由见 drawProductTip）
        drawProductTip(graphics, pool, matches, mouseX, mouseY);
    }

    /**
     * 左下角那个"?"：**鼠标停上去才展开**操作指南。
     * <p>
     * 为什么不再平铺一行：操作指南本身有好几条（左右键各是什么、材料格能点、
     * Shift 能忘掉……），摊在外面要么被窗口宽度截断、要么长到压住别的东西；
     * 收成一个"?"之后，想看的人停一下鼠标就有完整一份，不想看的人一点地方都不占。
     * <p>
     * 没用 {@code Button}：这是纯展示、点了不做任何事，画个方框自己判悬停最省事
     * （真做成按钮反而要挡一次点击，还得给它编个空动作）。
     */
    private void drawHelp(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left + 8;
        int y = top + WINDOW_HEIGHT - HELP_SIZE - 5;
        boolean hovered = mouseX >= x && mouseX < x + HELP_SIZE
                && mouseY >= y && mouseY < y + HELP_SIZE;
        graphics.fill(x, y, x + HELP_SIZE, y + HELP_SIZE, hovered ? 0xFF4A4A4A : 0xFF2A2A2A);
        graphics.renderOutline(x, y, HELP_SIZE, HELP_SIZE, hovered ? COLOR_SELECTED : COLOR_LABEL);
        graphics.drawString(this.font, "?", x + 3, y + 2, hovered ? COLOR_SELECTED : COLOR_LABEL, false);
        if (hovered && !flashOverlaps(x - 2, y - 2, x + HELP_SIZE + 2, y + HELP_SIZE + 2)) {
            // 行数记在 lang 里：一行一句，各自短，换语言也不会撑出去
            graphics.renderComponentTooltip(this.font, List.of(
                    Component.translatable("gui.blueprint.study.help.pick"),
                    Component.translatable("gui.blueprint.study.help.uses"),
                    Component.translatable("gui.blueprint.study.help.cell"),
                    Component.translatable("gui.blueprint.study.help.drill"),
                    Component.translatable("gui.blueprint.study.help.forget"),
                    Component.translatable("gui.blueprint.study.help.order"),
                    Component.translatable("gui.blueprint.study.help.search")), mouseX, mouseY);
        }
    }

    /**
     * 下单 / 撤单控件的**显示与亮灭**。
     * <p>
     * 两条规矩：
     * <ul>
     *   <li><b>点了产物才出现</b>：右边面板是"这样产物"的地方，主人还没挑东西时
     *       摆一排按钮既没意义又占地方。所以没选中产物时整块控件都收起来
     *       （{@code visible=false}：不画、也不吃点击）。</li>
     *   <li><b>亮灭得自己算</b>（原版按钮默认永远是亮的）：那号产物没记下做法、
     *       或者当初没认出配方 id 时"下单"该是灰的——点下去只能换来一句"她不会做"；
     *       数量没填（或填了 0）也变灰，协议里 0 是"撤单"，别让点一下变成撤掉全部。</li>
     * </ul>
     * 撤单那颗按钮属于左下那块"她现在的活"：管的是整个队列，没有待做的单时不出现。
     */
    private void refreshOrderControls(List<MaidStudyPool.Learned> pool) {
        EntityMaid maid = maid();
        boolean hasPending = maid != null && !MaidCraftOrder.pending(maid).isEmpty();
        if (cancelButton != null) {
            cancelButton.visible = hasPending;
            cancelButton.active = hasPending;
        }
        if (orderButton == null) {
            return;
        }
        MaidStudyPool.Learned picked = selected >= 0 && selected < pool.size()
                ? pool.get(selected) : null;
        // 照选中那条做法做（选择法：她只会用选中的这条）
        MaidStudyPool.Recipe chosen = picked == null ? null : picked.chosen();
        boolean show = picked != null;
        orderButton.visible = show;
        orderButton.active = show && chosen != null && chosen.id() != null && typedCount() > 0;
        if (countBox != null) {
            countBox.visible = show;
            if (!show && countBox.isFocused()) {
                // 藏起来的输入框不能再攥着焦点，否则打字全进了一个看不见的地方
                countBox.setFocused(false);
            }
        }
    }

    /**
     * 左下：女仆**现在在做的那一样**——产出物图标 + 还剩多少 + 整队总量。
     * <p>
     * 特意给它图标而不是一行字：主人一眼要认的是"她现在做的是哪样东西"，
     * 图标比名字快（名字跟在旁边，长了截断）。零件单还要把"为了做 X"说出来——
     * 否则主人只看见她在搓木棍，不知道是给谁备料。
     * <p>
     * 这块跟"取消全部待做"放在一起：它们都是**整个队列**的事，
     * 跟右边那颗只管"选中产物"的下单按钮不是一回事。
     */
    private void drawQueue(GuiGraphics graphics) {
        EntityMaid maid = maid();
        if (maid == null) {
            return;
        }
        int x = left + 10;
        int y = top + QUEUE_Y;
        List<MaidCraftOrder.Order> orders = MaidCraftOrder.pending(maid);
        if (orders.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.queue_empty"),
                    x, y + 4, COLOR_LABEL, false);
            return;
        }
        MaidCraftOrder.Order head = orders.get(0);
        graphics.renderItem(head.product(), x, y);
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(head.product().getHoverName().getString(),
                        PANEL_X - 42),
                x + 20, y + 4, COLOR_TEXT, false);

        ItemStack root = head.root();
        Component progress = root != null
                ? Component.translatable("gui.blueprint.study.queue_nested_progress",
                        head.remaining(), root.getHoverName())
                : Component.translatable("gui.blueprint.study.queue_progress",
                        head.remaining(), MaidCraftOrder.pendingAmount(maid), orders.size());
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(progress.getString(), PANEL_X - 20),
                x, y + 22, COLOR_LABEL, false);

        // 她在等上班时间：**面板上也写一行**。聊天栏那句话会滚掉，而主人盯着这块看的时候
        // 最想知道的就是"她怎么不动"——作息客户端也算得出来（世界时间 + 她自己的作息表）
        if (!MaidIndustryTask.isWorkingTime(maid)) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            Component.translatable("gui.blueprint.study.off_duty").getString(),
                            PANEL_X - 20),
                    x, y + 34, COLOR_LABEL, false);
        }
    }

    /**
     * 鼠标停在产物格上时把**名字**提示出来（格子只有图标，想知道是哪样东西得悬停）。
     * <p>
     * 特意单独走这一趟、画在最后：提示框是从鼠标往右下铺的，产物格子靠右时它会伸进
     * 右边面板的地界，而面板是后画的——就地画在网格那一趟里会被面板盖个正着。
     */
    private void drawProductTip(GuiGraphics graphics, List<MaidStudyPool.Learned> pool,
                                List<Integer> matches, int mouseX, int mouseY) {
        int visible = COLUMNS * ROWS;
        for (int i = 0; i < visible; i++) {
            int position = scrollRow * COLUMNS + i;
            if (position >= matches.size()) {
                return;
            }
            int x = left + GRID_X + (i % COLUMNS) * CELL;
            int y = top + GRID_Y + (i / COLUMNS) * CELL;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                if (!flashOverlaps(x - 1, y - 1, x + 17, y + 17)) {
                    graphics.renderTooltip(this.font,
                            pool.get(matches.get(position)).product(), mouseX, mouseY);
                }
                return;
            }
        }
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
            // Shift 按下时把鼠标所在产物描红：提示"Shift+左键就把它忘掉"
            if (hovered && shift) {
                graphics.fill(x, y, x + CELL, y + CELL, COLOR_FORGET & 0x40FFFFFF);
            }
            graphics.renderItem(learned.product(), x, y);

            if (index == selected) {
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_SELECTED);
            } else if (learned.hasMultipleRecipes()) {
                // 多配方：金框提示"这个能挑做法"
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_PRIORITY);
            }
            if (hovered && shift) {
                graphics.renderOutline(x - 1, y - 1, CELL, CELL, COLOR_FORGET);
            }
            if (learned.hasMultipleRecipes()) {
                graphics.drawString(this.font, String.valueOf(learned.recipes().size()),
                        x + 10, y + 9, COLOR_PRIORITY, true);
            }
        }
    }

    /**
     * 右边：选中产物的做法。**用摆法表示**——3×3 材料格 → 箭头 → 成品，就像 JEI 那样；
     * 不再写"材料A + 材料B"那行字。
     * <p>
     * 为什么非改不可：面板只有 88 宽，而一样东西的材料动辄四五种，文字必然被截成半句
     * （"煤炭 + 木棍 + …"），反而看不出做法。摆法视图还有文字给不了的好处：
     * 材料长什么样、一共几种、分别摆在哪一格，一眼就是全的（名字靠悬停提示）。
     * <p>
     * 多种做法时，最上面那排编号就是**选择器**：点一下就换成用那一条，选中的那颗高亮出来。
     * 列表顺序（学会的先后）不动——早先"点一下挪到最前"那版每次选择都重排列表，
     * 顺序一直在动，主人反而记不住自己选的到底是哪条。
     */
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

        List<MaidStudyPool.Recipe> recipes = learned.recipes();
        if (recipes.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.study.no_recipe"),
                    x, y + 26, COLOR_LABEL, false);
            return;
        }

        // 选中的那条：**列表顺序不动**，只把它在这里高亮出来（这就是"选择法"）
        int chosenIndex = learned.chosenIndex();
        int chipTop = y + 26;
        int hoveredChip = recipeChipAt(recipes.size(), mouseX, mouseY);

        for (int i = 0; i < recipes.size(); i++) {
            int chipX = x + (i % CHIP_PER_ROW) * (CHIP_SIZE + CHIP_GAP);
            int chipY = chipTop + (i / CHIP_PER_ROW) * (CHIP_SIZE + CHIP_GAP);
            boolean chosen = i == chosenIndex;
            if (chosen) {
                // 选中的填一层底 + 描框：一眼看出"她做的就是这条"
                graphics.fill(chipX - 1, chipY - 1, chipX + CHIP_SIZE + 1, chipY + CHIP_SIZE + 1,
                        0x60FFFF55);
                graphics.renderOutline(chipX - 2, chipY - 2, CHIP_SIZE + 4, CHIP_SIZE + 4,
                        COLOR_PRIORITY);
            } else if (i == hoveredChip) {
                graphics.fill(chipX - 1, chipY - 1, chipX + CHIP_SIZE + 1, chipY + CHIP_SIZE + 1,
                        0x40FFFFFF);
            }
            graphics.drawString(this.font, String.valueOf(i + 1), chipX + 3, chipY + 2,
                    chosen ? COLOR_PRIORITY : COLOR_ROW, false);
        }

        // 摆法画的永远是**选中**的那条：不再做悬停预览——点一下就换选择，
        // 再留一个"临时看一眼"的状态只会让人分不清她到底做的是哪条
        MaidStudyPool.Recipe recipe = recipes.get(chosenIndex);
        // 不给摆法加标题：JEI 也不加——材料格、箭头、成品摆在那儿本身就说明了一切，
        // 而面板只有 88 宽，省下这一行正好把摆法往上提一提
        int gridY = chipTop + chipsHeight(recipes.size()) + 6;
        int gridX = x + (PANEL_WIDTH - 3 * LAYOUT_CELL) / 2;
        List<ItemStack> grid = recipe.grid();
        for (int i = 0; i < Math.min(grid.size(), 9); i++) {
            ItemStack stack = grid.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int cellX = gridX + (i % 3) * LAYOUT_CELL;
            int cellY = gridY + (i / 3) * LAYOUT_CELL;
            boolean onCell = mouseX >= cellX && mouseX < cellX + 16
                    && mouseY >= cellY && mouseY < cellY + 16;
            // 指到材料格上才描框：青 = 她会做、点得过去；灰 = 没学过，点了只会得到一句提示。
            // 只在悬停那一格算（池子不设上限，9 格全算没必要——那是每帧 9 次全池扫描）
            if (onCell) {
                boolean known = poolIndexOf(pool(), stack) >= 0;
                graphics.renderOutline(cellX - 1, cellY - 1, LAYOUT_CELL, LAYOUT_CELL,
                        known ? COLOR_SELECTED : COLOR_LABEL);
            }
            graphics.renderItem(stack, cellX, cellY);
            if (onCell && !flashOverlaps(cellX - 1, cellY - 1, cellX + 17, cellY + 17)) {
                graphics.renderTooltip(this.font, stack, mouseX, mouseY);
            }
        }

        // 箭头：一小段竖线 + 一个三角，纯用方块拼出来，不指望字体里有箭头字符
        int arrowX = x + PANEL_WIDTH / 2 - 1;
        int arrowY = gridY + 3 * LAYOUT_CELL + 2;
        graphics.fill(arrowX, arrowY, arrowX + 2, arrowY + 4, COLOR_LABEL);
        graphics.fill(arrowX - 3, arrowY + 4, arrowX + 5, arrowY + 5, COLOR_LABEL);
        graphics.fill(arrowX - 2, arrowY + 5, arrowX + 4, arrowY + 6, COLOR_LABEL);
        graphics.fill(arrowX - 1, arrowY + 6, arrowX + 3, arrowY + 7, COLOR_LABEL);

        ItemStack out = recipeResult(learned, recipe);
        int outX = x + (PANEL_WIDTH - 16) / 2;
        int outY = arrowY + 11;
        if (!out.isEmpty()) {
            graphics.renderItem(out, outX, outY);
            if (mouseX >= outX && mouseX < outX + 16 && mouseY >= outY && mouseY < outY + 16
                    && !flashOverlaps(outX - 1, outY - 1, outX + 17, outY + 17)) {
                graphics.renderTooltip(this.font, out, mouseX, mouseY);
            }
        }

        // 底下那行字只在有多种做法时出现：教一句"点编号就能换"。
        // 只有一条做法就没什么可说的，干脆不占这行位置
        if (learned.hasMultipleRecipes()) {
            // 按面板宽度截断——英文那句比 88 还宽，不截就会捅出面板
            String text = this.font.plainSubstrByWidth(
                    Component.translatable("gui.blueprint.study.recipe_pick").getString(),
                    PANEL_WIDTH);
            graphics.drawString(this.font, text,
                    x + Math.max(0, (PANEL_WIDTH - this.font.width(text)) / 2), outY + 20,
                    COLOR_LABEL, false);
        }
    }

    /**
     * 把右边的面板切到"问这样东西能用来做什么"。
     * <p>
     * 四个入口都走这儿：右键产物格、右键摆法里的材料格、右键摆法底下的产物格、
     * 右键用途页里已经列出来的那些图标。问法完全一样，只是问的对象不同。
     */
    private void showUsesOf(ItemStack material) {
        usesMaterial = material.copyWithCount(1);
        showUses = true;
        usesScrollRow = 0; // 换了问的对象就从头看，别停在上一页的位置上
    }

    /**
     * 鼠标底下是摆法底下那个**产物格**吗；是就返回那样产物，不是返回空栈。
     * <p>
     * 排布必须与 {@link #drawRecipePanel} 里画成品时**同一套算法**（跟材料格一个规矩），
     * 否则点中的和看到的会错开一格。
     */
    private ItemStack recipeResultAt(List<MaidStudyPool.Learned> pool, double mouseX, double mouseY) {
        if (showUses || selected < 0 || selected >= pool.size()) {
            return ItemStack.EMPTY;
        }
        MaidStudyPool.Learned learned = pool.get(selected);
        if (learned.recipes().isEmpty()) {
            return ItemStack.EMPTY;
        }
        int arrowY = top + GRID_Y + 26 + chipsHeight(learned.recipes().size()) + 6
                + 3 * LAYOUT_CELL + 2;
        int outX = left + PANEL_X + (PANEL_WIDTH - 16) / 2;
        int outY = arrowY + 11;
        if (mouseX >= outX && mouseX < outX + 16 && mouseY >= outY && mouseY < outY + 16) {
            return recipeResult(learned, learned.recipes().get(learned.chosenIndex()));
        }
        return ItemStack.EMPTY;
    }

    /**
     * 让她在界面上"说一句"：飘在锚点旁边，过几秒自己消失。
     * <p>
     * <b>为什么不能发动作栏/聊天栏</b>：开着界面的时候，游戏那一层（血条、快捷栏、聊天、
     * 动作栏）整个不画——界面把屏幕占满了。所以在这儿发 {@code displayClientMessage}
     * 等于没说，这正是"没学会"那句提示一开始就丢了的缘故。要让她的话出现在界面里，
     * 只能在界面自己这一层画。
     *
     * @param anchorX 飘在哪儿：鼠标位置、或者按钮那一带（调用方挑最贴近刚点的东西的地方）
     */
    private void flashAt(Component text, double anchorX, double anchorY) {
        flash = text;
        int width = this.font.width(text);
        // 贴边时往里收一下，别飘出窗口去
        flashX = Math.min((int) anchorX + 8, left + WINDOW_WIDTH - width - 6);
        flashY = Math.min((int) anchorY + 8, top + WINDOW_HEIGHT - 16);
        // 占的地方先记下来：从这一帧起，别的悬停提示要绕着它走（见 flashOverlaps）
        flashLeft = flashX - 4;
        flashTop = flashY - 3;
        flashRight = flashX + width + 4;
        flashBottom = flashY + 11;
        flashUntil = System.currentTimeMillis() + FLASH_MS;
    }

    /** 她那句话现在还在不在屏幕上 */
    private boolean flashShowing() {
        return flash != null && System.currentTimeMillis() <= flashUntil;
    }

    /**
     * 这块地方会不会压到她那句话。
     * <p>
     * 悬停提示（物品名之类）画之前都先问一句，压到就让开——<b>她说的话不该被别的提示盖掉</b>。
     * 只让跟她那句话重叠的那一个提示消失，别处的悬停照常（不然为了 2 秒的话把提示全关了，
     * 反而更难用）。
     */
    private boolean flashOverlaps(int x1, int y1, int x2, int y2) {
        return flashShowing()
                && x1 < flashRight && x2 > flashLeft && y1 < flashBottom && y2 > flashTop;
    }

    /** 把飘着的那句话画出来（压在最后画，谁也盖不住它） */
    private void drawFlash(GuiGraphics graphics) {
        if (!flashShowing()) {
            return;
        }
        graphics.fill(flashLeft, flashTop, flashRight, flashBottom, 0xF0100014);
        graphics.renderOutline(flashLeft, flashTop, flashRight - flashLeft, flashBottom - flashTop,
                COLOR_SELECTED);
        graphics.drawString(this.font, flash, flashX, flashY, COLOR_TEXT, false);
    }

    /** 她的一句话（带名字）：界面里自己画的地方都走这儿，格式跟聊天里那套一致 */
    private Component herLine(String key, Object... args) {
        Component body = Component.translatable(key, args);
        EntityMaid maid = maid();
        return maid == null ? body : MaidSpeech.speak(maid, body);
    }

    /**
     * 点摆法里的**材料格**：她会做这个材料就跳过去看它的做法；没学过就直说一句。
     *
     * @return 真的点在某格材料上（不管跳没跳成）返回 true，让调用方别再往下判
     */
    private boolean clickIngredient(List<MaidStudyPool.Learned> pool, double mouseX, double mouseY) {
        ItemStack material = recipeSlotAt(pool, mouseX, mouseY);
        if (material.isEmpty()) {
            return false;
        }
        int target = poolIndexOf(pool, material);
        if (target < 0) {
            // 她没学过这东西。这话得**飘在界面上**说（理由见 flashAt）——之前发动作栏，
            // 而开着界面时动作栏是不画的，所以看着像"点了没反应"。
            // 这条判断客户端自己就能下（池子本来就同步在客户端），不用跑一趟服务端
            flashAt(herLine("message.blueprint.study.not_learned"), mouseX, mouseY);
            return true;
        }
        selected = target;
        showUses = false;
        usesScrollRow = 0; // 换了产物，用途页的位置也跟着回到头
        return true;
    }

    /**
     * 鼠标底下是摆法里的哪一格材料；不在格子上（或那格是空的）返回空栈。
     * <p>
     * 排布必须与 {@link #drawRecipePanel} 里画摆法时**同一套算法**，
     * 否则"点中的"和"看到的"会错开一格。
     */
    private ItemStack recipeSlotAt(List<MaidStudyPool.Learned> pool, double mouseX, double mouseY) {
        if (showUses || selected < 0 || selected >= pool.size()) {
            return ItemStack.EMPTY;
        }
        MaidStudyPool.Learned learned = pool.get(selected);
        if (learned.recipes().isEmpty()) {
            return ItemStack.EMPTY;
        }
        int chipTop = top + GRID_Y + 26;
        int gridY = chipTop + chipsHeight(learned.recipes().size()) + 6;
        int gridX = left + PANEL_X + (PANEL_WIDTH - 3 * LAYOUT_CELL) / 2;
        List<ItemStack> grid = learned.recipes().get(learned.chosenIndex()).grid();
        for (int i = 0; i < Math.min(grid.size(), 9); i++) {
            ItemStack stack = grid.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int cellX = gridX + (i % 3) * LAYOUT_CELL;
            int cellY = gridY + (i / 3) * LAYOUT_CELL;
            if (mouseX >= cellX && mouseX < cellX + 16 && mouseY >= cellY && mouseY < cellY + 16) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 鼠标底下是第几颗做法编号（返回 0 起的下标）；不在编号上返回 -1。
     * <p>
     * 排布必须与 {@link #drawRecipePanel} 里画编号时**同一套算法**，
     * 否则"点中的"和"看到的"会错开一格。
     */
    private int recipeChipAt(int count, double mouseX, double mouseY) {
        if (mouseX < left + PANEL_X || mouseX >= left + PANEL_X + PANEL_WIDTH) {
            return -1;
        }
        int chipTop = top + GRID_Y + 26;
        for (int i = 0; i < count; i++) {
            int chipX = left + PANEL_X + (i % CHIP_PER_ROW) * (CHIP_SIZE + CHIP_GAP);
            int chipY = chipTop + (i / CHIP_PER_ROW) * (CHIP_SIZE + CHIP_GAP);
            if (mouseX >= chipX && mouseX < chipX + CHIP_SIZE
                    && mouseY >= chipY && mouseY < chipY + CHIP_SIZE) {
                return i;
            }
        }
        return -1;
    }

    /** 编号排了几行占多高（做法多了会换行，下面的摆法要让位） */
    private static int chipsHeight(int count) {
        int rows = (count + CHIP_PER_ROW - 1) / CHIP_PER_ROW;
        return rows * (CHIP_SIZE + CHIP_GAP);
    }

    /**
     * 这条做法做出来的是什么。
     * <p>
     * 能按配方 id 查回来就用**配方自己的成品**——数量才对（木板那种一次出 4 个，
     * 拿产物去显示就成了 1 个）。查不到（老数据、当初没认出配方 id）才退回她的产物。
     */
    @SuppressWarnings("unchecked")
    private static ItemStack recipeResult(MaidStudyPool.Learned learned, MaidStudyPool.Recipe recipe) {
        Level level = Minecraft.getInstance().level;
        if (level != null && recipe.id() != null) {
            Recipe<CraftingContainer> found = level.getRecipeManager()
                    .byKey(recipe.id())
                    .filter(r -> r.getType() == RecipeType.CRAFTING)
                    .map(r -> (Recipe<CraftingContainer>) r)
                    .orElse(null);
            if (found != null) {
                return found.getResultItem(level.registryAccess());
            }
        }
        return learned.product();
    }

    /**
     * 右边：这东西能**用来做什么**（右键切到这一页）。
     * <p>
     * 列的是"她会做的东西里，哪些用到它"（见 {@link #learnedUsesOf}）——
     * 不是全世界的配方，是**她学过的**。想让她往下做点什么，翻这一页比翻配方表快：
     * 每一条都点得过去、都能直接下单。
     * <p>
     * 问的对象是 {@link #usesMaterial}：右键产物问的是那样产物，右键摆法里的材料格问的
     * 就是那格材料（"这东西还能拿来做什么"）。标题跟着换成被问的那样东西。
     */
    private void drawUsesPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left + PANEL_X;
        int y = top + GRID_Y;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(usesMaterial.getHoverName().getString(),
                        PANEL_WIDTH - 4),
                x, y, COLOR_TEXT, false);

        List<ItemStack> uses = learnedUsesOf(usesMaterial);
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
            if (mouseX >= cellX && mouseX < cellX + 16 && mouseY >= cellY && mouseY < cellY + 16
                    && !flashOverlaps(cellX - 1, cellY - 1, cellX + 17, cellY + 17)) {
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
                if (gridUses(recipe.grid(), material)) {
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
    private static boolean gridUses(List<ItemStack> grid, ItemStack material) {
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

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<MaidStudyPool.Learned> pool = pool();
        List<Integer> matches = matchingIndices(pool);
        int productIndex = productIndexAt(matches, mouseX, mouseY);

        // 右键产物 = 看它能用来做什么；左键才是"选做法、换看哪样"。
        // 右键不留 Shift 分支：以前那个"整样停用"已经删了。
        if (button == 1) {
            if (productIndex >= 0) {
                selected = productIndex;
                showUsesOf(pool.get(productIndex).product());
                return true;
            }
            // 右键摆法里的材料格 = 看这东西**还能拿来做什么**，跟右键产物是同一件事，
            // 只是问的对象从"产物"换成了"它的某样材料"（"这木头除了箱子还能做什么"）。
            // 左边选中的产物**不动**：主人只是顺手问一句，不该把左边看的东西也换掉
            ItemStack material = recipeSlotAt(pool, mouseX, mouseY);
            if (!material.isEmpty()) {
                showUsesOf(material);
                return true;
            }
            // 摆法底下那个**产物格**同理。它就是当前选中的产物，但那样东西可能被搜索过滤掉、
            // 或者滚到上面看不见了——从这里再问一次最顺手
            ItemStack out = recipeResultAt(pool, mouseX, mouseY);
            if (!out.isEmpty()) {
                showUsesOf(out);
                return true;
            }
            // 用途页里已经列出来的图标：右键 = 接着看**那样东西**能用来做什么。
            // 顺着产线一路问下去（木板 → 箱子 → ……），不用回左边去找它
            if (showUses) {
                List<ItemStack> uses = learnedUsesOf(usesMaterial);
                int index = useIndexAt(uses, mouseX, mouseY);
                if (index >= 0) {
                    showUsesOf(uses.get(index));
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
                sendForget(productIndex, mouseX, mouseY);
                return true;
            }
            showUses = false;
            if (productIndex == selected && pool.get(productIndex).hasMultipleRecipes()) {
                // 同一个产物再点一下：换用下一条做法（选择法里就是往后顺延一格）
                int count = pool.get(productIndex).recipes().size();
                sendSelect(productIndex, (pool.get(productIndex).chosenIndex() + 1) % count,
                        mouseX, mouseY);
            } else {
                selected = productIndex;
            }
            return true;
        }

        if (selected >= 0 && selected < pool.size()
                && mouseX >= left + PANEL_X && mouseX < left + PANEL_X + PANEL_WIDTH) {
            if (showUses) {
                // 用途模式：点一个图标就跳到那个产物（列表本来就是从池子里查出来的，
                // 每一条都点得过去；这里再查一次下标是为了拿到它当下的位置）
                List<ItemStack> uses = learnedUsesOf(usesMaterial);
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
                // 点材料格 = 跳过去看那样东西怎么做（她会做的话）
                if (clickIngredient(pool, mouseX, mouseY)) {
                    return true;
                }
                // 点做法编号 = 换用那一条（选中，高亮；列表顺序不动）
                int chip = recipeChipAt(pool.get(selected).recipes().size(), mouseX, mouseY);
                if (chip >= 0) {
                    sendSelect(selected, chip, mouseX, mouseY);
                    return true;
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
            int totalRows = (learnedUsesOf(usesMaterial).size() + USES_COLUMNS - 1)
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

    private void sendOrder(int productIndex, int count) {
        // 防一手：撤单走的是同一个包，别让"数量没填"被当成撤单发出去
        if (count <= 0 && productIndex >= 0) {
            return;
        }
        // 撤单那颗按钮在左下，反馈就飘它旁边；下单的反馈飘面板底下那排控件上方
        if (productIndex < 0) {
            ModNetwork.CHANNEL.sendToServer(new C2SMaidCraftOrderPacket(maidEntityId, -1, 0));
            flashAt(herLine("message.blueprint.craft.cleared"), left + 10, top + QUEUE_Y + 38);
            return;
        }
        // 排不排得上，客户端自己就能算（待做清单本来就同步在客户端，上限也是公开常量）。
        // 算出来排不上就**别发了**，直接飘一句：发过去只会被服务端静默拒掉，
        // 而主人开着界面，服务端说什么他都看不见
        if (!canFitInQueue(pool(), productIndex, count)) {
            flashAt(herLine("message.blueprint.craft.order_full"),
                    left + PANEL_X, top + WINDOW_HEIGHT - 46);
            return;
        }
        ModNetwork.CHANNEL.sendToServer(
                new C2SMaidCraftOrderPacket(maidEntityId, productIndex, count));
    }

    /**
     * 这一单加得进去吗。
     * <p>
     * 规矩要跟服务端 {@code MaidCraftOrder.order} **一模一样**——两边不一致就会出现
     * "界面说能下、服务端不收"（或者反过来），那种最难查。同一产物是加数量，
     * 所以只受单个上限约束；新产物才看张数上限。
     */
    private boolean canFitInQueue(List<MaidStudyPool.Learned> pool, int productIndex, int count) {
        EntityMaid maid = maid();
        if (maid == null || productIndex < 0 || productIndex >= pool.size()) {
            return false;
        }
        ItemStack product = pool.get(productIndex).product();
        List<MaidCraftOrder.Order> orders = MaidCraftOrder.pending(maid);
        for (MaidCraftOrder.Order order : orders) {
            if (ItemStack.matches(order.product(), product)) {
                return order.remaining() + count <= MaidCraftOrder.MAX_COUNT;
            }
        }
        return orders.size() < MaidCraftOrder.MAX_ORDERS;
    }

    /** 换用第 {@code recipeIndex} 条做法（选中，不重排列表） */
    private void sendSelect(int productIndex, int recipeIndex, double mouseX, double mouseY) {
        if (recipeIndex < 0) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(
                new C2SSelectStudyRecipePacket(maidEntityId, productIndex, recipeIndex));
        // 反馈在界面里飘：服务端那边不再发聊天了（开着界面时聊天栏根本不画）
        flashAt(herLine("message.blueprint.study.recipe_selected"), mouseX, mouseY);
    }

    /** 忘掉一样产物（连同它的配方）。删完池子会经同步刷新，先把选中清掉给个即时反馈 */
    private void sendForget(int productIndex, double mouseX, double mouseY) {
        List<MaidStudyPool.Learned> pool = pool();
        if (productIndex < 0 || productIndex >= pool.size()) {
            return;
        }
        ItemStack product = pool.get(productIndex).product();
        ModNetwork.CHANNEL.sendToServer(new C2SForgetStudyPacket(maidEntityId, productIndex));
        selected = -1;
        showUses = false;
        usesScrollRow = 0;
        flashAt(herLine("message.blueprint.study.forgotten", product.getHoverName()), mouseX, mouseY);
    }
}
