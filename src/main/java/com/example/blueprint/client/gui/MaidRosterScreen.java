package com.example.blueprint.client.gui;

import com.example.blueprint.client.CommandPostProjections;
import com.example.blueprint.client.MaidRoster;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SAssignMaidPacket;
import com.example.blueprint.network.packet.S2CMaidListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 女仆名册：一份**我的女仆**的名单，每只画成一张真 3D 立绘（她能展示长相才认得出是谁）。
 * <p>
 * 为什么单独一个界面而不是塞在指挥台里：指挥台那点地方连"名字 + 状态"都摆不下，
 * 更别说立绘；而这份名单本来就与"哪台指挥台"无关——从指挥台进来时点名字指那一台，
 * 从蓝图终端进来时指**最近一台属于我的**（远程指派）。
 * <p>
 * 立绘走原版的实体渲染（`EntityRenderDispatcher`）：她是**这个客户端世界里已经加载**的实体，
 * 所以直接拿它画就行，不需要认识女仆模组的类——没装女仆时名单根本是空的，
 * 也不会执行到任何女仆专属代码。
 */
@OnlyIn(Dist.CLIENT)
public class MaidRosterScreen extends Screen {

    private static final int WINDOW_WIDTH = 380;
    private static final int WINDOW_HEIGHT = 252;
    private static final int COLUMNS = 3;
    private static final int CARD_WIDTH = 118;
    private static final int CARD_HEIGHT = 96;
    private static final int CARD_GAP = 6;
    /** 立绘那条带子的高度 */
    private static final int PORTRAIT_PIXELS = 52;
    /**
     * 立绘的缩放。
     * <p>
     * 原版背包给玩家是 30：玩家 1.8 格高，正好把那条带子占满。女仆的模型比玩家还宽、还高
     * （帽子、扫帚都算在模型里），沿用 30 就是现在这样"人出框"——所以按同样的比例收小，
     * 留出余量给她的装束。
     */
    private static final int PORTRAIT_SIZE = 26;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_GOOD = 0x55FF55;
    private static final int COLOR_WARN = 0xFFAA00;
    private static final int COLOR_CARD = 0xB0101014;
    private static final int COLOR_CARD_HOVER = 0xC02A2A18;

    /** 从某台指挥台进来时的那一格；从终端进来是 null，见 {@link #target()} */
    @Nullable
    private final BlockPos targetPost;
    private Component status = Component.empty();
    /** 名单是异步到的，鼠标位置得自己记着——立绘要"看着鼠标转"，而渲染时没别的机会拿到它 */
    private double mouseX;
    private double mouseY;

    public MaidRosterScreen(@Nullable BlockPos targetPost) {
        super(Component.translatable("gui.blueprint.maids.title"));
        this.targetPost = targetPost;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        int y = top + WINDOW_HEIGHT - 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.post.close"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 66, y, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.maids.refresh"),
                        b -> MaidRoster.request())
                .bounds(left + 6, y, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        // 名册自带节流（一秒最多要一次），每帧叫它没关系
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

        List<S2CMaidListPacket.Entry> roster = MaidRoster.entries();
        if (roster.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.maids.empty"),
                    left + 8, top + 34, COLOR_LABEL, false);
        } else {
            for (int i = 0; i < roster.size(); i++) {
                drawCard(graphics, roster.get(i), i, left, top, mouseX, mouseY);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCard(GuiGraphics graphics, S2CMaidListPacket.Entry maid, int index,
                          int left, int top, int mouseX, int mouseY) {
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        int x = left + 6 + col * (CARD_WIDTH + CARD_GAP);
        int y = top + 26 + row * (CARD_HEIGHT + CARD_GAP);
        if (y + CARD_HEIGHT > top + WINDOW_HEIGHT - 24) {
            return; // 摆不下的不画（名册一般就几只，真多了还有下面的提示）
        }

        boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
        graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered ? COLOR_CARD_HOVER : COLOR_CARD);
        graphics.renderOutline(x, y, CARD_WIDTH, CARD_HEIGHT, hovered ? COLOR_TEXT : COLOR_DIVIDER);

        // 立绘：拿这个客户端世界里那只实体来画（区块没加载就只剩名字，也够认）
        BlockPos post = maid.post();
        Entity entity = entityOf(maid.id());
        if (entity instanceof LivingEntity living) {
            // 后两个参数是**从立绘中心指向鼠标的偏移**（原版背包就是这么算的）：
            // 直接传鼠标的绝对坐标既会让角度一下顶到极限，方向也正好反过来——
            // 表现就是"鼠标在右下，她却抬头看左上"
            int centerX = x + CARD_WIDTH / 2;
            int feetY = y + PORTRAIT_PIXELS;
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                    centerX, feetY, PORTRAIT_SIZE,
                    (float) centerX - (float) mouseX,
                    (float) (feetY - PORTRAIT_PIXELS / 2) - (float) mouseY, living);
        } else {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.maids.not_loaded"),
                    x + 6, y + PORTRAIT_PIXELS / 2, COLOR_LABEL, false);
        }

        int labelY = y + CARD_HEIGHT - 24;
        graphics.fill(x + 1, labelY, x + CARD_WIDTH - 1, y + CARD_HEIGHT - 1, 0xC0000000);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(maid.name(), CARD_WIDTH - 8),
                x + 4, labelY + 2, COLOR_TEXT, false);
        graphics.drawString(this.font, Component.translatable(post != null
                        ? "gui.blueprint.maids.assigned" : "gui.blueprint.maids.free"),
                x + 4, labelY + 11, post != null ? COLOR_GOOD : COLOR_LABEL, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<S2CMaidListPacket.Entry> roster = MaidRoster.entries();
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        for (int i = 0; i < roster.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = left + 6 + col * (CARD_WIDTH + CARD_GAP);
            int y = top + 26 + row * (CARD_HEIGHT + CARD_GAP);
            if (mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT) {
                toggle(roster.get(i));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 点一下：归这一台（或最近一台属于我的）／撤销 */
    private void toggle(S2CMaidListPacket.Entry maid) {
        BlockPos post = target();
        if (post == null) {
            setStatus(Component.translatable("gui.blueprint.detail.no_post"));
            return;
        }
        boolean assigned = post.equals(maid.post());
        ModNetwork.CHANNEL.sendToServer(new C2SAssignMaidPacket(post, maid.id(), !assigned));
        setStatus(Component.translatable(assigned
                ? "gui.blueprint.post.unassigned" : "gui.blueprint.post.assigned", maid.name()));
        MaidRoster.request();
    }

    /**
     * 按 UUID 找这个客户端世界里的实体。
     * <p>
     * 客户端没有"按 UUID 查"的入口（按 id 查那个是整数），所以直接在**要渲染的实体**里过一次：
     * 名单一般就几只，一帧几十上百个实体的比较可以忽略。
     */
    @Nullable
    private Entity entityOf(UUID maid) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(maid)) {
                return entity;
            }
        }
        return null;
    }

    /** 目标是哪台：带着就指它，否则取最近一台属于我的（终端那条"远程指派"） */
    @Nullable
    private BlockPos target() {
        if (this.targetPost != null) {
            return this.targetPost;
        }
        Player player = Minecraft.getInstance().player;
        return player == null ? null
                : CommandPostProjections.nearestMine(player.blockPosition(), player.getUUID());
    }

    private void setStatus(Component message) {
        this.status = message;
    }
}
