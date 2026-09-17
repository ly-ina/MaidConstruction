package com.example.blueprint.client.gui;

import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SClearBlueprintPacket;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetRotationPacket;
import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
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
import java.util.List;
import java.util.UUID;

/**
 * 蓝图面板：Shift + 右键空气打开。
 * <p>
 * 左边是结构的 3D 预览（可拖动旋转），中间是操作按钮，
 * 右边是 blueprints/ 目录下的文件，选中后即可导入。
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("deprecation") // renderSingleBlock 在 1.20.1 被标了过时，但没有等价的替代写法
public class BlueprintScreen extends Screen {

    private static final int WINDOW_WIDTH = 400;
    private static final int WINDOW_HEIGHT = 230;
    private static final int PREVIEW_WIDTH = 150;
    private static final int FILE_WIDTH = 128;
    private static final int BUTTON_WIDTH = 100;
    /** 预览最多画这么多方块，超了就按比例抽稀 */
    private static final int MAX_PREVIEW_BLOCKS = 1500;

    private final ItemStack stack;
    private FileList fileList;

    private Schematic previewSchematic;
    private List<Schematic.BlockEntry> previewEntries = List.of();
    private float yaw = 45.0F;
    private float pitch = 30.0F;
    private long lastRequestAt = 0;
    private Component status = Component.empty();

    public BlueprintScreen(ItemStack stack) {
        super(Component.translatable("gui.blueprint.title"));
        this.stack = stack;
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        int buttonX = left + PREVIEW_WIDTH + 8;
        int y = top + 24;

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
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.blueprint.close"), b -> onClose())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());

        int listX = left + WINDOW_WIDTH - FILE_WIDTH - 8;
        this.fileList = new FileList(FILE_WIDTH, top + 24, top + WINDOW_HEIGHT - 10);
        this.fileList.setLeftPos(listX);
        this.addRenderableWidget(this.fileList);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        // 面板底板
        graphics.fill(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT, 0xF0100010);
        graphics.fill(left + PREVIEW_WIDTH, top + 18, left + PREVIEW_WIDTH + 1, top + WINDOW_HEIGHT, 0xFF404040);
        graphics.fill(left + WINDOW_WIDTH - FILE_WIDTH - 12, top + 18,
                left + WINDOW_WIDTH - FILE_WIDTH - 11, top + WINDOW_HEIGHT, 0xFF404040);

        graphics.drawString(this.font, this.title, left + 6, top + 6, 0xFFFFFF, false);
        graphics.drawString(this.font, Component.translatable("gui.blueprint.file_hint"),
                left + WINDOW_WIDTH - FILE_WIDTH - 8, top + 6, 0x888888, false);

        drawPreview(graphics, left, top);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.status, left + PREVIEW_WIDTH + 8, top + WINDOW_HEIGHT - 12, 0x55FF55, false);
    }

    // ------------------------------------------------------------------
    // 3D 预览
    // ------------------------------------------------------------------

    private void drawPreview(GuiGraphics graphics, int left, int top) {
        Schematic schematic = getPreview();
        int cx = left + PREVIEW_WIDTH / 2;
        int cy = top + (WINDOW_HEIGHT + 18) / 2;

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
                cx, top + WINDOW_HEIGHT - 14, 0x999999);
    }

    @Nullable
    private Schematic getPreview() {
        UUID id = BlueprintItem.getSchematicId(stack);
        if (id == null) {
            previewSchematic = null;
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
            previewEntries = List.of();
            return null;
        }

        // 只在结构或朝向变化时重建列表，别每帧都分配一大堆对象
        if (schematic != previewSchematic) {
            previewSchematic = schematic;
            previewEntries = schematic.entries();
        }
        return schematic;
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
                && mouseY >= top && mouseY < top + WINDOW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 按钮动作
    // ------------------------------------------------------------------

    private void onRotate() {
        Rotation next = BlueprintItem.cycleRotation(stack);
        ModNetwork.CHANNEL.sendToServer(new C2SSetRotationPacket(next));
        setStatus(Component.translatable("gui.blueprint.rotated_to", (next.ordinal() * 90) + "°"));
    }

    private void onClearAnchor() {
        BlueprintItem.clearAnchor(stack);
        ModNetwork.CHANNEL.sendToServer(new C2SSetAnchorPacket(true));
        setStatus(Component.translatable("message.blueprint.anchor_cleared"));
    }

    private void onClearSchematic() {
        BlueprintItem.clearSchematic(stack);
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
            byte[] data = BlueprintTransfer.encode(schematic);
            Path file = BlueprintTransfer.writeToFile(BlueprintItem.getBlueprintName(stack), data);
            setStatus(Component.translatable("gui.blueprint.exported", file.getFileName().toString()));
            fileList.refresh();
        } catch (IOException e) {
            setStatus(Component.translatable("gui.blueprint.export_failed"));
        }
    }

    private void onImport() {
        Path selected = fileList.getSelectedPath();
        if (selected == null) {
            setStatus(Component.translatable("gui.blueprint.no_file_selected"));
            return;
        }
        try {
            byte[] data = Files.readAllBytes(selected);
            if (data.length > BlueprintTransfer.MAX_FILE_BYTES) {
                setStatus(Component.translatable("message.blueprint.import_too_large"));
                return;
            }
            String name = selected.getFileName().toString();
            if (name.endsWith(BlueprintTransfer.FILE_EXTENSION)) {
                name = name.substring(0, name.length() - BlueprintTransfer.FILE_EXTENSION.length());
            }
            ModNetwork.CHANNEL.sendToServer(new C2SImportBlueprintPacket(data, name));
            setStatus(Component.translatable("gui.blueprint.importing"));
        } catch (IOException e) {
            setStatus(Component.translatable("message.blueprint.import_failed"));
        }
    }

    private void setStatus(Component message) {
        this.status = message;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    // ------------------------------------------------------------------
    // 文件列表
    // ------------------------------------------------------------------

    private class FileList extends ObjectSelectionList<FileList.Entry> {

        FileList(int width, int top, int bottom) {
            super(Minecraft.getInstance(), width, bottom - top, top, bottom, 14);
            refresh();
        }

        void refresh() {
            this.clearEntries();
            for (Path path : BlueprintTransfer.listFiles()) {
                this.addEntry(new Entry(path));
            }
        }

        @Nullable
        Path getSelectedPath() {
            Entry entry = this.getSelected();
            return entry == null ? null : entry.path;
        }

        private class Entry extends ObjectSelectionList.Entry<Entry> {
            private final Path path;

            Entry(Path path) {
                this.path = path;
            }

            @Override
            public Component getNarration() {
                return Component.literal(path.getFileName().toString());
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                String text = path.getFileName().toString();
                if (text.length() > 18) {
                    text = text.substring(0, 17) + "…";
                }
                boolean selected = FileList.this.getSelected() == this;
                int color = selected ? 0xFFFF55 : (hovering ? 0xFFFFFF : 0xBBBBBB);
                graphics.drawString(BlueprintScreen.this.font, text, left + 2, top + 3, color, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                FileList.this.setSelected(this);
                return true;
            }
        }
    }
}
