package com.example.blueprint.client.gui;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.client.CommandPostProjections;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SCommandPostProjectionPacket;
import com.example.blueprint.network.packet.C2SCommandPostStartPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * 右键指挥台看到的界面：这台绑给了谁、正托管着哪张图纸、指派了哪些女仆。
 * <p>
 * 数据每帧从**方块实体**现取，不缓存、也不要通知：服务端在右键那一刻把状态发过来
 * （见 {@code CommandPostBlock.use}），方块实体一更新，这里下一帧就是新的。
 * 这样安排是因为状态很小、变化也不频繁，中间加一层缓存只会多出一处"显示和实际不一致"。
 * <p>
 * 这一版能做的动作是**放置投影 / 取消投影**（DEVELOPER §12 第 2 步）：放的是手上那张
 * 已经定过位的蓝图，位置取它的锚点，取消只撤投影。指派女仆、下单、暂停是第 3~4 步。
 * <p>
 * 能不能动由**客户端先判**（绑没绑我、手上有图没有），反馈就画在这个界面里——
 * 开着界面时聊天栏是不画的（DEVELOPER §7.17），服务端回话等于没说。
 */
@OnlyIn(Dist.CLIENT)
public class CommandPostScreen extends Screen {

    private static final int WINDOW_WIDTH = 300;
    /** 比只读那版高 22：页脚上面多了一行"放置投影 / 取消投影" */
    private static final int WINDOW_HEIGHT = 208;
    private static final int ROW_HEIGHT = 12;
    /** 女仆列表最多列这么多只，再多写一行"…" */
    private static final int MAX_MAIDS = 5;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_GOOD = 0x55FF55;
    private static final int COLOR_WARN = 0xFFAA00;

    private final BlockPos pos;
    /** 界面里的反馈（没绑定、手上没图…）：开着界面时聊天栏不画，只能自己写一行 */
    private Component status = Component.empty();
    /** 口令那个按钮：没开工写「开始建造」，开工了写「停下」，见 {@link #tick()} */
    private Button startButton;


    public CommandPostScreen(BlockPos pos) {
        super(Component.translatable("gui.blueprint.post.title"));
        this.pos = pos;
    }

    /** 界面开着的时候世界照常跑（与面板、清单、学习池一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        int y = top + WINDOW_HEIGHT - 24;

        // 投影那一行只留「取消投影」。**放**这件事属于图纸库：打开图纸库、点开一份、点「投影」，
        // 放的就落到这台指挥台上——下面那个按钮把自己那一格带进图纸库，就是"放到这一台"的意思
        int actionY = top + WINDOW_HEIGHT - 46;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.post.clear"), b -> onClear())
                .bounds(left + 6, actionY, 90, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.maids.title"),
                        b -> Minecraft.getInstance().setScreen(new MaidRosterScreen(this.pos)))
                .bounds(left + 100, actionY, 90, 18).build());
        // 口令：放好投影、指派好女仆都只是准备，这一下才是开工（见 C2SCommandPostStartPacket）
        this.startButton = Button.builder(Component.translatable("gui.blueprint.home.start"),
                        b -> onOrder())
                .bounds(left + 194, actionY, 100, 18).build();
        this.addRenderableWidget(this.startButton);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.library.title"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen(this.pos)))
                .bounds(left + 6, y, 96, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.post.close"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 66, y, 60, 18).build());
    }

    /**
     * 取消投影：只撤投影，不动已经指派的施工（那是第 4 步的"撤单"）。
     * <p>
     * "能不能动"在客户端先判一遍：开着界面时聊天栏是不画的（§7.17），
     * 等服务端回一句"不行"等于什么都没发生。服务端还会再验一次——这里只是把话说清楚。
     */
    private void onClear() {
        CommandPostBlockEntity post = post();
        Player player = Minecraft.getInstance().player;
        if (post == null || player == null) {
            return;
        }
        if (!post.isBoundTo(player.getUUID())) {
            setStatus(Component.translatable("gui.blueprint.post.need_bind"));
            return;
        }
        ModNetwork.CHANNEL.sendToServer(C2SCommandPostProjectionPacket.cancel(this.pos));
        setStatus(Component.translatable("gui.blueprint.post.cleared"));
    }

    private void setStatus(Component message) {
        this.status = message;
    }

    /**
     * 口令那个按钮跟着状态变字样。
     * <p>
     * 状态是**别人改的**（方块实体同步过来、或者施工那边完工置位、或者终端主页那边下的口令），
     * 所以不能只在 {@code init} 里定一次。
     */
    @Override
    public void tick() {
        if (this.startButton == null) {
            return;
        }
        CommandPostBlockEntity post = post();
        this.startButton.setMessage(Component.translatable(post != null && post.isStarted()
                ? "gui.blueprint.home.stop" : "gui.blueprint.home.start"));
        // 没投影就没有"开工"的对象（那台不是我的，服务端还会再拦一次）
        this.startButton.active = post != null && post.getSchematicId() != null;
    }

    /** 下口令 / 收回口令：客户端先判一遍，服务端还会再验一次（这里只是把话说清楚） */
    private void onOrder() {
        CommandPostBlockEntity post = post();
        Player player = Minecraft.getInstance().player;
        if (post == null || player == null) {
            return;
        }
        if (!post.isBoundTo(player.getUUID())) {
            setStatus(Component.translatable("gui.blueprint.post.need_bind"));
            return;
        }
        if (post.getSchematicId() == null) {
            setStatus(Component.translatable("gui.blueprint.post.no_blueprint"));
            return;
        }
        boolean start = !post.isStarted();
        ModNetwork.CHANNEL.sendToServer(new C2SCommandPostStartPacket(this.pos, start));
        setStatus(Component.translatable(start
                ? "message.blueprint.post.started" : "message.blueprint.post.stopped"));
    }

    /** 这块指挥台；没加载出来（例如刚被拆掉）时返回 null */
    @Nullable
    private CommandPostBlockEntity post() {
        Level level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(this.pos) instanceof CommandPostBlockEntity post)) {
            return null;
        }
        return post;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        CommandPostBlockEntity post = post();
        if (post == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.missing"),
                    left + 8, top + 34, COLOR_WARN, false);
        } else {
            drawState(graphics, post, left, top);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawState(GuiGraphics graphics, CommandPostBlockEntity post, int left, int top) {
        int x = left + 8;
        int y = top + 30;

        // 绑定：没有它就没有"指派女仆"这一步，所以放在最上面
        if (post.getOwner() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.unbound"),
                    x, y, COLOR_WARN, false);
        } else {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.post.bound", post.getOwnerName()),
                    x, y, COLOR_GOOD, false);
        }

        // 投影
        y += 20;
        graphics.drawString(this.font, Component.translatable("gui.blueprint.post.projection"),
                x, y, COLOR_LABEL, false);
        y += ROW_HEIGHT;
        if (post.getSchematicId() == null) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.no_blueprint"),
                    x + 8, y, COLOR_LABEL, false);
        } else {
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.post.blueprint",
                            post.getBlueprintName().isEmpty() ? "?" : post.getBlueprintName()),
                    x + 8, y, COLOR_TEXT, false);
            y += ROW_HEIGHT;
            BlockPos anchor = post.getAnchor();
            graphics.drawString(this.font,
                    Component.translatable("gui.blueprint.post.anchor",
                            anchor == null ? "-" : anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ()),
                    x + 8, y, COLOR_LABEL, false);
            // 下一步在哪：待命（等着开工口令）/ 正在建 / 已完工 / 已暂停。
            // 判据与终端主页共用同一处（CommandPostProjections#stateText），两页不会写出两个说法
            y += ROW_HEIGHT;
            graphics.drawString(this.font, CommandPostProjections.stateText(post),
                    x + 8, y, CommandPostProjections.stateColor(post), false);
        }

        // 女仆：这一栏只说"这台手底下有谁"；完整名册（带立绘、能指派）走下面那个「女仆」按钮单开
        y += 20;
        graphics.drawString(this.font, Component.translatable("gui.blueprint.post.maids"),
                x, y, COLOR_LABEL, false);
        y += ROW_HEIGHT;
        Map<UUID, String> maids = post.getMaids();
        if (maids.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.post.no_maids"),
                    x + 8, y, COLOR_LABEL, false);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String name : maids.values()) {
            if (names.length() > 0) {
                names.append(" · ");
            }
            names.append(name.isEmpty() ? "?" : name);
        }
        graphics.drawString(this.font, this.font.plainSubstrByWidth(names.toString(), WINDOW_WIDTH - 24),
                x + 8, y, COLOR_TEXT, false);
    }
}
