package com.example.blueprint.client.gui;

import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SClearBlueprintPacket;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetNamePacket;
import com.example.blueprint.network.packet.C2SSetRotationPacket;
import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 蓝图面板：Shift + 右键空气打开。
 * <p>
 * 左上角改名字，左边是 3D 预览（可拖动旋转），中间是操作按钮，
 * 右边是 blueprints/ 目录下的文件，点一下选中再导入。
 * <p>
 * 这里不缓存 ItemStack：服务端改完 NBT 会连槽位一起替换掉，
 * 抓着打开界面时那个旧引用不放的话，导入完得关掉重开才看得到变化。
 * 所以每次都从玩家手上现取。
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("deprecation") // renderSingleBlock 在 1.20.1 被标了过时，但没有等价的替代写法
public class BlueprintScreen extends Screen {

    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 230;
    private static final int PREVIEW_WIDTH = 150;
    private static final int FILE_WIDTH = 128;
    private static final int BUTTON_WIDTH = 100;
    /** 标题栏高度，下面才是内容区 */
    private static final int HEADER_HEIGHT = 22;
    private static final int FILE_ROW_HEIGHT = 12;
    /** 预览最多画这么多方块，超了就按比例抽稀 */
    private static final int MAX_PREVIEW_BLOCKS = 1500;

    private static final int COLOR_PANEL = 0xE8100014;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_LABEL = 0xAAAAAA;
    private static final int COLOR_ROW = 0xBBBBBB;
    private static final int COLOR_ROW_SELECTED = 0xFFFF55;
    private static final int COLOR_STATUS = 0x55FF55;

    private EditBox nameBox;

    private final List<Path> files = new ArrayList<>();
    private int selectedFile = -1;
    private int fileScroll = 0;
    private int fileListX;
    private int fileListY;
    private int fileListHeight;

    /** 记录预览对应的是哪份数据，变了才重建条目列表 */
    private Schematic previewSchematic;
    private UUID previewId;
    private List<Schematic.BlockEntry> previewEntries = List.of();
    private float yaw = 45.0F;
    private float pitch = 30.0F;
    private long lastRequestAt = 0;
    private Component status = Component.empty();

    public BlueprintScreen() {
        super(Component.translatable("gui.blueprint.title"));
    }

    /** 现取玩家手上的蓝图，别缓存 */
    private ItemStack getStack() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : BlueprintItem.findHeld(player);
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        // 左上角：蓝图名称，导出时拿它当文件名
        this.nameBox = new EditBox(this.font, left + 76, top + 4, 132, 14,
                Component.translatable("gui.blueprint.name_label"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setValue(BlueprintItem.getBlueprintName(getStack()));
        this.nameBox.setHint(Component.translatable("tooltip.blueprint.unnamed"));
        this.addRenderableWidget(this.nameBox);

        int buttonX = left + PREVIEW_WIDTH + 8;
        int y = top + HEADER_HEIGHT + 6;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.rotate"), b -> onRotate())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.clear_anchor"), b -> onClearAnchor())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.clear_schematic"), b -> onClearSchematic())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.export_file"), b -> onExport())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.import_file"), b -> onImport())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.open_folder"), b -> onOpenFolder())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.close"), b -> onClose())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());

        this.fileListX = left + WINDOW_WIDTH - FILE_WIDTH - 8;
        this.fileListY = top + HEADER_HEIGHT + 2;
        this.fileListHeight = WINDOW_HEIGHT - HEADER_HEIGHT - 14;
        refreshFiles();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 刻意不调用 renderBackground：它会铺一层背景纹理（没进世界时就是土方块），
        // 这里只要一层半透明压暗，保证面板上的字看得清就行。
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, COLOR_PANEL);
        graphics.fill(left + PREVIEW_WIDTH, top + HEADER_HEIGHT, left + PREVIEW_WIDTH + 1, top + WINDOW_HEIGHT, COLOR_DIVIDER);
        graphics.fill(this.fileListX - 6, top + HEADER_HEIGHT, this.fileListX - 5, top + WINDOW_HEIGHT, COLOR_DIVIDER);

        graphics.drawString(this.font, this.title, left + 6, top + 7, COLOR_TEXT, false);
        graphics.drawString(this.font, Component.translatable("gui.blueprint.name_label"),
                left + 48, top + 7, COLOR_LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.blueprint.file_hint"),
                this.fileListX, top + 7, COLOR_LABEL, false);

        drawPreview(graphics, left, top);
        drawFileList(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.status,
                left + PREVIEW_WIDTH + 8, top + WINDOW_HEIGHT - 11, COLOR_STATUS, false);
    }

    // ------------------------------------------------------------------
    // 3D 预览
    // ------------------------------------------------------------------

    private void drawPreview(GuiGraphics graphics, int left, int top) {
        Schematic schematic = getPreview();
        int cx = left + PREVIEW_WIDTH / 2;
        int cy = top + HEADER_HEIGHT + (WINDOW_HEIGHT - HEADER_HEIGHT) / 2;

        if (schematic == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.blueprint.no_preview"), cx, cy, 0x888888);
            return;
        }

        Vec3i size = schematic.getSize();
        int step = Math.max(1, (int) Math.ceil(schematic.countBlocks() / (double) MAX_PREVIEW_BLOCKS));
        float maxDim = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        float scale = (PREVIEW_WIDTH - 28) / (maxDim * 1.7F);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 200.0D);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-size.getX() / 2.0D, -size.getY() / 2.0D, -size.getZ() / 2.0D);

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        int drawn = 0;
        for (Schematic.BlockEntry entry : previewEntries) {
            if (drawn++ % step != 0) {
                continue;
            }
            pose.pushPose();
            pose.translate(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());
            dispatcher.renderSingleBlock(entry.state(), pose, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        buffers.endBatch();
        pose.popPose();

        graphics.drawCenteredString(this.font,
                Component.literal(size.getX() + "×" + size.getY() + "×" + size.getZ()),
                cx, top + WINDOW_HEIGHT - 13, 0x999999);
    }

    @Nullable
    private Schematic getPreview() {
        ItemStack stack = getStack();
        UUID id = BlueprintItem.getSchematicId(stack);
        if (id == null) {
            previewSchematic = null;
            previewId = null;
            previewEntries = List.of();
            return null;
        }

        Schematic schematic = ClientSchematicCache.get(id, BlueprintItem.getRotation(stack));
        if (schematic == null) {
            // 本地还没这份数据，向服务端要一次
            long now = System.currentTimeMillis();
            if (now - lastRequestAt > 2000L) {
                lastRequestAt = now;
                ModNetwork.CHANNEL.sendToServer(new C2SRequestSchematicPacket(id));
            }
            previewSchematic = null;
            previewId = null;
            previewEntries = List.of();
            return null;
        }

        // 结构或朝向变了才重建条目列表，别每帧都分配一大堆对象
        if (schematic != previewSchematic || !id.equals(previewId)) {
            previewSchematic = schematic;
            previewId = id;
            previewEntries = schematic.entries();
        }
        return schematic;
    }

    // ------------------------------------------------------------------
    // 文件列表（自绘）
    // ------------------------------------------------------------------

    private void refreshFiles() {
        files.clear();
        files.addAll(BlueprintTransfer.listFiles());
        selectedFile = -1;
        fileScroll = 0;
    }

    private void drawFileList(GuiGraphics graphics) {
        graphics.enableScissor(fileListX, fileListY, fileListX + FILE_WIDTH, fileListY + fileListHeight);

        if (files.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.blueprint.no_files"),
                    fileListX + 2, fileListY + 4, 0x777777, false);
        }

        for (int i = 0; i < files.size(); i++) {
            int rowY = fileListY + i * FILE_ROW_HEIGHT - fileScroll;
            if (rowY + FILE_ROW_HEIGHT < fileListY || rowY > fileListY + fileListHeight) {
                continue;
            }
            String name = shortened(BlueprintTransfer.displayName(files.get(i)));
            graphics.drawString(this.font, name, fileListX + 2, rowY + 2,
                    i == selectedFile ? COLOR_ROW_SELECTED : COLOR_ROW, false);
        }

        graphics.disableScissor();
    }

    private String shortened(String name) {
        return name.length() > 20 ? name.substring(0, 19) + "…" : name;
    }

    private boolean isOverFileList(double mouseX, double mouseY) {
        return mouseX >= fileListX && mouseX < fileListX + FILE_WIDTH
                && mouseY >= fileListY && mouseY < fileListY + fileListHeight;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverFileList(mouseX, mouseY)) {
            int index = (int) ((mouseY - fileListY + fileScroll) / FILE_ROW_HEIGHT);
            if (index >= 0 && index < files.size()) {
                selectedFile = index;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverFileList(mouseX, mouseY)) {
            int max = Math.max(0, files.size() * FILE_ROW_HEIGHT - fileListHeight);
            fileScroll = Mth.clamp(fileScroll - (int) (delta * FILE_ROW_HEIGHT), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && isOverPreview(mouseX, mouseY)) {
            yaw += (float) dragX;
            pitch = Mth.clamp(pitch + (float) dragY, -80.0F, 80.0F);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private boolean isOverPreview(double mouseX, double mouseY) {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        return mouseX >= left && mouseX < left + PREVIEW_WIDTH
                && mouseY >= top + HEADER_HEIGHT && mouseY < top + WINDOW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 按钮动作
    // ------------------------------------------------------------------

    private void onRotate() {
        ItemStack stack = getStack();
        Rotation next = BlueprintItem.cycleRotation(stack);
        ModNetwork.CHANNEL.sendToServer(new C2SSetRotationPacket(next));
        setStatus(Component.translatable("gui.blueprint.rotated_to", (next.ordinal() * 90) + "°"));
    }

    private void onClearAnchor() {
        BlueprintItem.clearAnchor(getStack());
        ModNetwork.CHANNEL.sendToServer(new C2SSetAnchorPacket(true));
        setStatus(Component.translatable("message.blueprint.anchor_cleared"));
    }

    private void onClearSchematic() {
        BlueprintItem.clearSchematic(getStack());
        ModNetwork.CHANNEL.sendToServer(C2SClearBlueprintPacket.INSTANCE);
        // 内容都没了，面板留着也没意义
        onClose();
    }

    private void onExport() {
        Schematic schematic = getPreview();
        if (schematic == null) {
            setStatus(Component.translatable("gui.blueprint.nothing_to_export"));
            return;
        }
        try {
            // 文件名跟随左上角输入的名称
            String name = nameBox.getValue().trim();
            if (name.isEmpty()) {
                name = Component.translatable("tooltip.blueprint.unnamed").getString();
            }
            byte[] data = BlueprintTransfer.encode(schematic);
            Path file = BlueprintTransfer.writeToFile(name, data);
            setStatus(Component.translatable("gui.blueprint.exported", file.getFileName().toString()));
            refreshFiles();
        } catch (IOException e) {
            setStatus(Component.translatable("gui.blueprint.export_failed"));
        }
    }

    private void onImport() {
        if (selectedFile < 0 || selectedFile >= files.size()) {
            setStatus(Component.translatable("gui.blueprint.no_file_selected"));
            return;
        }
        Path selected = files.get(selectedFile);
        try {
            byte[] data = Files.readAllBytes(selected);
            if (data.length > BlueprintTransfer.MAX_FILE_BYTES) {
                setStatus(Component.translatable("message.blueprint.import_too_large"));
                return;
            }
            // 导入后蓝图名字跟随文件名，输入框当场同步
            String name = BlueprintTransfer.displayName(selected);
            nameBox.setValue(name);
            ModNetwork.CHANNEL.sendToServer(new C2SImportBlueprintPacket(data, name));
            setStatus(Component.translatable("gui.blueprint.importing"));
        } catch (IOException e) {
            setStatus(Component.translatable("message.blueprint.import_failed"));
        }
    }

    private void onOpenFolder() {
        Path dir = BlueprintTransfer.getExportDirectory();
        try {
            Util.getPlatform().openFile(dir.toFile());
            setStatus(Component.translatable("gui.blueprint.opened_folder", dir.toString()));
        } catch (Exception e) {
            setStatus(Component.translatable("gui.blueprint.open_folder_failed"));
        }
    }

    @Override
    public void onClose() {
        // 关闭时把名字同步给服务端，存进物品 NBT
        if (nameBox != null) {
            String name = nameBox.getValue().trim();
            if (!name.equals(BlueprintItem.getBlueprintName(getStack()))) {
                ModNetwork.CHANNEL.sendToServer(new C2SSetNamePacket(name));
            }
        }
        super.onClose();
    }

    /** 只在面板内显示，不往聊天栏推——面板里的操作不该再弹一遍提示 */
    private void setStatus(Component message) {
        this.status = message;
    }
}
