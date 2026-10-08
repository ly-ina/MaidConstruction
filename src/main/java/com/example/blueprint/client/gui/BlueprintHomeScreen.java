package com.example.blueprint.client.gui;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.client.CommandPostProjections;
import com.example.blueprint.client.MaidRoster;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SCommandPostProjectionPacket;
import com.example.blueprint.network.packet.C2SCommandPostStartPacket;
import com.example.blueprint.network.packet.S2CMaidListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 蓝图终端的**主页**：一块监视板 + 开工那一个按钮。
 * <p>
 * 为什么终端要有主页：终端管的是"一整台工地"——投影在哪儿、进度到哪一步、谁在干活、谁在待命。
 * 图纸库回答的是另一个问题（"我有哪些图纸"），它是找图的地方，不适合承担"现在工地怎么样"。
 * 所以右键终端先进这里，要翻图纸再点「图纸库」。
 * <p>
 * 这一版能做的动作只有一个：**「开始建造」**。放好投影、指派好女仆都只是准备，
 * 准备不该等于开工——口令按下之前她是待命的（见 {@code C2SCommandPostStartPacket}）。
 * 按钮在开工后变成「停下」，口令可以收回（投影与指派都留着）。
 * <p>
 * 状态不缓存：指挥台那份状态每帧从**客户端方块实体**读（它整份随更新包同步过来），
 * 名册那边一秒问服务端一次。中间再加一层缓存只会多出一处"显示和实际不一致"。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintHomeScreen extends Screen {

    private static final int WINDOW_WIDTH = 340;
    private static final int WINDOW_HEIGHT = 236;
    private static final int ROW_HEIGHT = 12;
    /** 女仆监视列表最多列这么多行，再多写一行"…"（名册界面才是完整的那个） */
    private static final int MAX_MAIDS = 8;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_GOOD = 0x55FF55;
    private static final int COLOR_WARN = 0xFFAA00;

    /** 从某台指挥台的界面进来时带着的那一格；从终端进来是 null（那时取"最近一台属于我的"） */
    @Nullable
    private final BlockPos targetPost;
    /** 界面里的反馈（远处那台没绑定、还没放投影…）：开着界面时聊天栏不画（§7.17），只能自己写一行 */
    private Component status = Component.empty();
    /** 开工那个按钮：没开工写「开始建造」，开工了写「停下」 */
    private Button startButton;

    public BlueprintHomeScreen(@Nullable BlockPos targetPost) {
        super(Component.translatable("gui.blueprint.home.title"));
        this.targetPost = targetPost;
    }

    /** 界面开着的时候世界照常跑（与面板、清单、指挥台一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        // 口令那一行：一个按钮，两种意思（还没开工 / 已经在建）
        int orderY = top + WINDOW_HEIGHT - 48;
        this.startButton = Button.builder(Component.translatable("gui.blueprint.home.start"),
                        b -> onOrder())
                .bounds(left + 6, orderY, 110, 18).build();
        this.addRenderableWidget(this.startButton);
        // 取消投影也放在这儿：终端本来就是"托管投影"的那个入口（放它的是图纸库，
        // 撤它的是这里），不必为了撤销专门跑回指挥台那边按一次
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.post.clear"),
                        b -> onClearProjection())
                .bounds(left + 120, orderY, 90, 18).build());

        // 页脚：图纸库 | 女仆（指派） | 关闭。这一行是"去哪儿"的入口，与上面那个"做什么"分开
        int y = top + WINDOW_HEIGHT - 24;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.title"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen(this.targetPost)))
                .bounds(left + 6, y, 96, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.maids.title"),
                        b -> Minecraft.getInstance().setScreen(new MaidRosterScreen(this.targetPost)))
                .bounds(left + 106, y, 90, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.post.close"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 66, y, 60, 18).build());
    }

    /**
     * 每 tick 把按钮调成"此刻该有的样子"。
     * <p>
     * 按钮的文字与可不可按都跟着指挥台的状态走，而这个状态是**别人改的**（方块实体同步过来、
     * 或者施工那边完工置位），不是这个界面自己算的——所以不能只在 {@code init} 里定一次。
     */
    @Override
    public void tick() {
        if (this.startButton == null) {
            return;
        }
        CommandPostBlockEntity post = post();
        boolean started = post != null && post.isStarted();
        this.startButton.setMessage(Component.translatable(
                started ? "gui.blueprint.home.stop" : "gui.blueprint.home.start"));
        // 没投影就没有"开工"的对象；那台不是我的也按不动（服务端还会再验一次）
        this.startButton.active = post != null && post.getSchematicId() != null && isMine(post);
    }

    /**
     * 下口令 / 收回口令。
     * <p>
     * 客户端先判一遍（开着界面时聊天栏不画，等服务端回一句"不行"等于什么都没发生），
     * 服务端还会再验一次——这里只是把话说清楚。
     */
    private void onOrder() {
        CommandPostBlockEntity post = post();
        Player player = Minecraft.getInstance().player;
        if (post == null || player == null) {
            return;
        }
        if (!isMine(post)) {
            setStatus(Component.translatable("gui.blueprint.post.need_bind"));
            return;
        }
        if (post.getSchematicId() == null) {
            setStatus(Component.translatable("gui.blueprint.post.no_blueprint"));
            return;
        }
        boolean start = !post.isStarted();
        ModNetwork.CHANNEL.sendToServer(new C2SCommandPostStartPacket(this.postPos(), start));
        setStatus(Component.translatable(start
                ? "message.blueprint.post.started" : "message.blueprint.post.stopped"));
    }

    private void setStatus(Component message) {
        this.status = message;
    }

    /**
     * 取消投影 = **撤单**：投影消失、挂在这台上的女仆一并撤下来。
     * <p>
     * 这台的状态跟着方块实体同步过来，所以这里只把请求发出去——界面下一 tick 自己就变成
     * "还没放投影"了，不需要本地先改一份。与指挥台界面上那个按钮是同一条包、同一套校验。
     */
    private void onClearProjection() {
        BlockPos pos = postPos();
        CommandPostBlockEntity post = post();
        if (post == null || pos == null) {
            return;
        }
        if (!isMine(post)) {
            setStatus(Component.translatable("gui.blueprint.post.need_bind"));
            return;
        }
        if (post.getSchematicId() == null) {
            setStatus(Component.translatable("gui.blueprint.post.no_blueprint"));
            return;
        }
        ModNetwork.CHANNEL.sendToServer(C2SCommandPostProjectionPacket.cancel(pos));
        setStatus(Component.translatable("gui.blueprint.post.cleared"));
    }

    /** 这台的状态归不归我管：绑定它的那个玩家才能下口令（与放置投影同一条规矩） */
    private boolean isMine(CommandPostBlockEntity post) {
        Player player = Minecraft.getInstance().player;
        return player != null && post.isBoundTo(player.getUUID());
    }

    /**
     * 这块界面说的是哪台指挥台：从指挥台界面进来就用它带的那一格，
     * 从终端进来就取**最近一台属于我的**（与指派女仆、放投影同一个规矩，三处不会各挑各的）。
     */
    @Nullable
    private BlockPos postPos() {
        if (this.targetPost != null) {
            return this.targetPost;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        return CommandPostProjections.nearestMine(player.blockPosition(), player.getUUID());
    }

    /** 客户端那份方块实体；远端那台没同步过来时是 null */
    @Nullable
    private CommandPostBlockEntity post() {
        return CommandPostProjections.blockEntityAt(postPos());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 名册自带节流（一秒最多问一次），每帧叫它没关系；"谁在待命、谁在干活"就靠它
        MaidRoster.request();

        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.hLine(left + 2, left + WINDOW_WIDTH - 2, top + 22, COLOR_DIVIDER);
        graphics.drawString(this.font, this.title, left + 8, top + 7, COLOR_TEXT, false);
        if (!this.status.getString().isEmpty()) {
            graphics.drawString(this.font, this.status,
                    left + WINDOW_WIDTH - 8 - this.font.width(this.status), top + 7, COLOR_WARN, false);
        }

        int y = drawPost(graphics, left, top + 30);
        drawMaids(graphics, left, y + 8, top);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 指挥台那一栏：这是哪台、托着哪张图、现在哪一步。返回画完之后的 y */
    private int drawPost(GuiGraphics graphics, int left, int y) {
        int x = left + 8;
        BlockPos pos = postPos();
        CommandPostBlockEntity post = post();

        Component label = Component.translatable("gui.blueprint.home.post");
        graphics.drawString(this.font, label, x, y, COLOR_LABEL, false);
        if (post == null) {
            // 没同步过来（走远了 / 区块卸了 / 根本还没放）；坐标还说得出是哪一台
            graphics.drawString(this.font, pos == null
                            ? Component.translatable("gui.blueprint.home.no_post")
                            : Component.translatable("gui.blueprint.post.missing"),
                    x + 8, y + ROW_HEIGHT, COLOR_WARN, false);
            return y + ROW_HEIGHT * 2;
        }

        // 坐标跟在"指挥台"后面**同一行**：必须按标签的宽度让位。
        // 写死一个缩进（8 像素）是不行的——"指挥台"三个字有二十多个像素宽，直接把它压掉一半
        graphics.drawString(this.font, pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
                x + this.font.width(label) + 6, y, COLOR_TEXT, false);
        y += ROW_HEIGHT;

        // 投影：名字 + 状态。状态是这一栏的重点——"在待命"和"正在建"要一眼分得开
        Component state = CommandPostProjections.stateText(post);
        int stateX = left + WINDOW_WIDTH - 8 - this.font.width(state);
        if (post.getSchematicId() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.no_blueprint"),
                    x + 8, y, COLOR_LABEL, false);
        } else {
            // 图纸名可以很长：截到状态左边为止，别让名字把状态挤没（两边都是要紧信息）
            String name = post.getBlueprintName().isEmpty() ? "?" : post.getBlueprintName();
            int room = Math.max(40, stateX - (x + 8) - 6);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            Component.translatable("gui.blueprint.post.blueprint", name).getString(), room),
                    x + 8, y, COLOR_TEXT, false);
        }
        graphics.drawString(this.font, state, stateX, y,
                CommandPostProjections.stateColor(post), false);
        y += ROW_HEIGHT;

        if (post.getOwner() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.unbound"),
                    x + 8, y, COLOR_WARN, false);
        } else if (!isMine(post)) {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.post.bound", post.getOwnerName()),
                    x + 8, y, COLOR_WARN, false);
        }
        return y + ROW_HEIGHT;
    }

    /** 女仆那一栏：一只一行，写着她在干嘛。返回画完之后的 y */
    private int drawMaids(GuiGraphics graphics, int left, int y, int top) {
        int x = left + 8;
        List<S2CMaidListPacket.Entry> roster = MaidRoster.entries();

        graphics.drawString(this.font, Component.translatable("gui.blueprint.post.maids"),
                x, y, COLOR_LABEL, false);
        // 汇总写在右边：一眼看出"有几只在干活"
        Component summary = Component.translatable("gui.blueprint.home.summary",
                MaidRoster.count(S2CMaidListPacket.Status.BUILDING),
                MaidRoster.count(S2CMaidListPacket.Status.STANDBY),
                MaidRoster.count(S2CMaidListPacket.Status.FREE));
        graphics.drawString(this.font, summary,
                left + WINDOW_WIDTH - 8 - this.font.width(summary), y, COLOR_LABEL, false);
        y += ROW_HEIGHT;

        if (roster.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.maids.empty"),
                    x + 8, y, COLOR_LABEL, false);
            return y + ROW_HEIGHT;
        }

        // 只画到按钮上方：列表再长也不压到口令那一行
        int limit = top + WINDOW_HEIGHT - 56;
        int shown = 0;
        for (S2CMaidListPacket.Entry maid : roster) {
            if (shown >= MAX_MAIDS || y + ROW_HEIGHT > limit) {
                graphics.drawString(this.font, Component.translatable("gui.blueprint.home.more",
                        roster.size() - shown), x + 8, y, COLOR_LABEL, false);
                return y + ROW_HEIGHT;
            }
            graphics.drawString(this.font, this.font.plainSubstrByWidth(maid.name(), 120),
                    x + 8, y, COLOR_TEXT, false);
            graphics.drawString(this.font, MaidRoster.statusText(maid.status()),
                    x + 136, y, MaidRoster.statusColor(maid.status()), false);
            y += ROW_HEIGHT;
            shown++;
        }
        return y;
    }
}
