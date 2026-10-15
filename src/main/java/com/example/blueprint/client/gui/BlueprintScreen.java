package com.example.blueprint.client.gui;

import com.example.blueprint.client.BlueprintLayerView;
import com.example.blueprint.client.BlueprintTransfer;
import com.example.blueprint.client.CableBusOutline;
import com.example.blueprint.client.ClientSchematicCache;
import com.example.blueprint.client.SchematicPreview;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SClearBlueprintPacket;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetNamePacket;
import com.example.blueprint.network.packet.C2SSetOrientationPacket;
import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 蓝图面板：Shift + 右键空气打开。
 * <p>
 * 左上角改名字，左边是 3D 预览（可拖动旋转），中间是操作按钮，
 * 右边是 maidconstruction/blueprints/ 目录下的文件，点一下选中再导入。
 * <p>
 * 这里不缓存 ItemStack：服务端改完 NBT 会连槽位一起替换掉，
 * 抓着打开界面时那个旧引用不放的话，导入完得关掉重开才看得到变化。
 * 所以每次都从玩家手上现取。
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("deprecation") // renderSingleBlock 在 1.20.1 被标了过时，但没有等价的替代写法
public class BlueprintScreen extends Screen {

    private static final int WINDOW_WIDTH = 400;
    /**
     * 面板高度。1.7.1 加了「建筑清单」之后按钮列排到了 218，
     * 而状态那行写在 -11 处——230 的高度只剩 1px 间隙，挤在一起像要压上去。
     * 加高 12px：按钮列下面留出十几像素，预览和文件列表也各松一点。
     */
    private static final int WINDOW_HEIGHT = 242;
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
    /**
     * 「建筑清单」按钮。存下来是为了每帧重算它的可用状态——
     * 导入结构之后蓝图才拿到 id，而 {@code init()} 只在打开面板时跑一次，
     * 只在 init 里算的话按钮会一直是灰的，得关掉重开才亮。
     */
    private Button materialsButton;
    /** 已经发出导入请求、还在等服务端把结构发回来（见 {@link #refreshDynamicState()}） */
    private boolean importPending;
    /** 那次导入请求是什么时候发的：超过 {@link #IMPORT_TIMEOUT_MS} 就当作失败了 */
    private long importAskedAt;
    /** 导入回包等多久算失败（失败时服务端只在聊天栏说话，面板这边得自己收起"正在导入…"） */
    private static final long IMPORT_TIMEOUT_MS = 10_000L;

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
    /** 分层查看：只看某一层 / 看到某一层为止，换层拖预览框右边那条 */
    private final BlueprintLayerView layers = new BlueprintLayerView();
    /** 滚轮缩放：预览的放大倍数（1 = 原样）。画图与拾取都乘它，两边不会错位 */
    private float zoom = 1.0F;
    /** 正在拖分层那条：拖它的时候别再顺手转视角（两件事在同一块预览上，不分开就互相打架） */
    private boolean draggingLayer;
    private float yaw = 45.0F;
    private float pitch = 30.0F;
    private long lastRequestAt = 0;
    private Component status = Component.empty();

    public BlueprintScreen() {
        super(Component.translatable("gui.maidconstruction.title"));
    }

    /**
     * 界面开着的时候女仆还要干活，别暂停世界（与学习池、建筑清单一致）。
     * <p>
     * 面板这边还多一条硬理由：<b>导入结构要服务端来回一趟</b>——请求发出去、服务端读文件、
     * 再把结构发回来。暂停世界的话服务端就停在那儿，回包只能等玩家关掉面板才到，
     * 表现就是"点了导入没反应，关掉重开才看见"。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
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
                Component.translatable("gui.maidconstruction.name_label"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setValue(BlueprintItem.getBlueprintName(getStack()));
        this.nameBox.setHint(Component.translatable("tooltip.maidconstruction.unnamed"));
        this.addRenderableWidget(this.nameBox);

        int buttonX = left + PREVIEW_WIDTH + 8;
        int y = top + HEADER_HEIGHT + 6;

        // 「旋转」与「镜像」并排：都是同一件"把它摆到哪个方向"的事，各占半行。
        // 面板高度已经排满，再占一整行就会顶到状态行上
        int halfWidth = (BUTTON_WIDTH - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.rotate"), b -> onRotate())
                .bounds(buttonX, y, halfWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.mirror"), b -> onMirror())
                .bounds(buttonX + halfWidth + 4, y, halfWidth, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.clear_anchor"), b -> onClearAnchor())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.clear_schematic"), b -> onClearSchematic())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        // 建筑清单：这座建筑要备多少料。摆在"清空录制"后面——上面三个都是改这张图的，
        // 从这里往下是"拿它干点什么"（看清单、导出、导入）
        this.materialsButton = Button.builder(Component.translatable("gui.maidconstruction.materials"),
                        b -> onMaterials())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build();
        this.addRenderableWidget(this.materialsButton);
        // 空图没什么好列的，按钮直接灰掉，别让玩家点进去看一句"还没有内容"；
        // 之后导入或录制完成时由 onSchematicArrived 把它点亮
        refreshDynamicState();
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.export_file"), b -> onExport())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.import_file"), b -> onImport())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.open_folder"), b -> onOpenFolder())
                .bounds(buttonX, y, BUTTON_WIDTH, 20).build());
        y += 30;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.maidconstruction.close"), b -> onClose())
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
        // 只有"等导入回包"这一趟需要每帧看一眼：数据到了要换状态，太久没到要收起那句话。
        // 平时一帧都不做——按钮的可用状态交给 init() 与"结构数据到达"的通知（见 onSchematicArrived）
        if (this.importPending) {
            refreshDynamicState();
        }
        graphics.fill(left + PREVIEW_WIDTH, top + HEADER_HEIGHT, left + PREVIEW_WIDTH + 1, top + WINDOW_HEIGHT, COLOR_DIVIDER);
        graphics.fill(this.fileListX - 6, top + HEADER_HEIGHT, this.fileListX - 5, top + WINDOW_HEIGHT, COLOR_DIVIDER);

        graphics.drawString(this.font, this.title, left + 6, top + 7, COLOR_TEXT, false);
        graphics.drawString(this.font, Component.translatable("gui.maidconstruction.name_label"),
                left + 48, top + 7, COLOR_LABEL, false);
        // 这一格只留 FILE_WIDTH：目录名一长就会顶到右边按钮栏上（原来那串全路径就是这么溢出去的）
        graphics.drawString(this.font, this.font.plainSubstrByWidth(
                        Component.translatable("gui.maidconstruction.file_hint").getString(), FILE_WIDTH - 4),
                this.fileListX, top + 7, COLOR_LABEL, false);

        drawPreview(graphics, left, top, mouseX, mouseY);
        drawFileList(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.status,
                left + PREVIEW_WIDTH + 8, top + WINDOW_HEIGHT - 11, COLOR_STATUS, false);
    }

    // ------------------------------------------------------------------
    // 3D 预览
    // ------------------------------------------------------------------

    /**
     * 结构数据到了：手上这张图刚刚拿到 id（录制完成，或者导入回包），把面板当场刷新一遍。
     * <p>
     * <b>由 {@code S2CSchematicDataPacket} 收到数据后直接叫过来</b>，不是每帧去问物品 NBT：
     * 那样一次要读复合标签、现造一个 {@code UUID}，面板开着时每秒六十次纯属白做；
     * 而且数据什么时候到只有服务端知道，到了通知一声最省事也最准。
     *
     * @param id 刚到的结构 id；和手上这张图对不上就不动（防串台）
     */
    public void onSchematicArrived(UUID id) {
        if (!id.equals(BlueprintItem.getSchematicId(getStack()))) {
            return;
        }
        refreshDynamicState();
    }

    /**
     * 重算按钮可用状态；正在等导入回包时，顺手把"正在导入…"换成结果。
     * <p>
     * 调用点只有三处：打开面板（{@code init()}）、结构数据到达（{@link #onSchematicArrived}）、
     * 以及**等导入那一趟**的每帧兜底（见 {@code render}）——都不是常驻开销。
     */
    private void refreshDynamicState() {
        ItemStack stack = getStack();
        if (this.materialsButton != null) {
            this.materialsButton.active = BlueprintItem.getSchematicId(stack) != null;
        }
        if (this.importPending) {
            Schematic schematic = getPreview();
            if (schematic != null) {
                // 数据到了：把"正在导入…"换成结果，别让它挂在那儿
                this.importPending = false;
                Vec3i size = schematic.getSize();
                setStatus(Component.translatable("message.maidconstruction.imported",
                        size.getX(), size.getY(), size.getZ()));
            } else if (System.currentTimeMillis() - this.importAskedAt > IMPORT_TIMEOUT_MS) {
                // 这么久还没回来，多半是导入失败（文件坏了、太大）。服务端已经往聊天栏说过
                // 原因了，这里把"正在导入…"收掉即可，别一直挂着让人以为还在转
                this.importPending = false;
                setStatus(Component.empty());
            }
        }
    }

    private void drawPreview(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        Schematic schematic = getPreview();
        int cx = left + PREVIEW_WIDTH / 2;
        int cy = top + HEADER_HEIGHT + (WINDOW_HEIGHT - HEADER_HEIGHT) / 2;

        if (schematic == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.maidconstruction.no_preview"), cx, cy, 0x888888);
            return;
        }

        Vec3i size = schematic.getSize();
        // 分层：先按层筛，再算抽稀步长——步长要按"筛完之后还剩多少块"算，
        // 否则只看一层时那一层会被抽成几个孤块，等于什么都没看见
        List<Schematic.BlockEntry> shown = layers.filter(previewEntries);
        int step = Math.max(1, (int) Math.ceil(shown.size() / (double) MAX_PREVIEW_BLOCKS));

        // 抽稀出**真正要画**的那一份，绘制与悬浮拾取共用同一个清单：巨构上抽稀必触发，
        // 各算各的就会出现"指到了一块根本没画出来的方块"——拾取的口径必须与眼睛看到的一致
        List<Schematic.BlockEntry> drawn = new ArrayList<>(Math.min(shown.size(), MAX_PREVIEW_BLOCKS + 1));
        for (int i = 0; i < shown.size(); i += step) {
            drawn.add(shown.get(i));
        }
        // 缩放统一由渲染器那份换算给（拾取用的是同一个数），再乘上界面自己的缩放倍数
        float scale = SchematicPreview.scaleFor(schematic, PREVIEW_WIDTH - 20) * zoom;

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

        List<Schematic.BlockEntry> unrenderable = null;
        for (Schematic.BlockEntry entry : drawn) {
            if (canRender(entry.state(), dispatcher)) {
                pose.pushPose();
                pose.translate(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());
                dispatcher.renderSingleBlock(entry.state(), pose, buffers,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            } else {
                // 模型算不出几何体的方块（AE2 的 ME 线缆就是），退回一格线框，
                // 免得它在预览里凭空消失，看上去像是根本没录进蓝图
                if (unrenderable == null) {
                    unrenderable = new ArrayList<>();
                }
                unrenderable.add(entry);
            }
        }

        if (unrenderable != null) {
            VertexConsumer lines = buffers.getBuffer(RenderType.LINES);
            for (Schematic.BlockEntry entry : unrenderable) {
                int x = entry.pos().getX();
                int y = entry.pos().getY();
                int z = entry.pos().getZ();
                // 线缆方块画成"芯 + 连接臂 + 部件标记"，其余画不出模型的退回整格
                List<CableBusOutline.Outline> outlines = CableBusOutline.outlinesOf(schematic, x, y, z,
                        BlueprintItem.getRotation(getStack()), BlueprintItem.getMirror(getStack()));

                pose.pushPose();
                pose.translate(x, y, z);
                if (outlines == null) {
                    LevelRenderer.renderLineBox(pose, lines, PREVIEW_CUBE, 0.45F, 0.68F, 1.0F, 0.9F);
                } else {
                    for (CableBusOutline.Outline outline : outlines) {
                        LevelRenderer.renderLineBox(pose, lines, outline.box(),
                                outline.red(), outline.green(), outline.blue(), 0.9F);
                    }
                }
                pose.popPose();
            }
            buffers.endBatch(RenderType.LINES);
        }

        buffers.endBatch();
        pose.popPose();

        // 左上角：状态一行 + 一个小问号。说明收进问号里——常驻那几行会横跨预览框、压到右边的按钮上
        int helpX = left + 4;
        int helpY = top + HEADER_HEIGHT + 3;
        boolean overHelp = mouseX >= helpX - 1 && mouseX < helpX + 11
                && mouseY >= helpY - 1 && mouseY < helpY + 11;
        graphics.fill(helpX, helpY, helpX + 10, helpY + 10, overHelp ? 0xFF3A3A55 : 0xA0202030);
        graphics.renderOutline(helpX, helpY, 10, 10, 0x80808080);
        graphics.drawString(this.font, "?", helpX + 2, helpY + 1, 0xFFFFFF, false);
        graphics.drawString(this.font, layers.stateText(),
                left + 18, top + HEADER_HEIGHT + 5, 0xDDDDDD, true);
        // 拖动条：底＝第 1 层，顶＝整栋；左键拖/点换层，右键切"只看这层"
        layers.drawBar(graphics, barX(), barTop(), barBottom());

        graphics.drawCenteredString(this.font,
                Component.literal(size.getX() + "×" + size.getY() + "×" + size.getZ()),
                cx, top + WINDOW_HEIGHT - 13, 0x999999);

        // 悬浮方块名：用画图时同一个缩放（scale）去投，指哪算哪
        Schematic.BlockEntry hovered = SchematicPreview.pick(schematic, drawn,
                cx, cy, scale, yaw, pitch, mouseX, mouseY);
        // 提示自己画（见 drawHintBox 的说明），并往前挪到 z=400：预览画在 z=200，不往前就会被方块压住
        pose.pushPose();
        pose.translate(0.0D, 0.0D, 400.0D);
        if (overHelp) {
            SchematicPreview.drawHintBox(graphics, this.font, BlueprintLayerView.helpLines(),
                    mouseX, mouseY, this.width, this.height);
        } else if (hovered != null) {
            SchematicPreview.drawHintBox(graphics, this.font, List.of(layers.describe(hovered)),
                    mouseX, mouseY, this.width, this.height);
        }
        pose.popPose();
    }

    /** 预览里给"模型画不出来"的方块画线框用的单位立方体 */
    private static final AABB PREVIEW_CUBE =
            new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D).inflate(-0.004D);

    /** 方块模型能不能算出几何体。按状态缓存，省得每帧重算 */
    private static final Map<BlockState, Boolean> RENDERABLE = new HashMap<>();

    /**
     * 判断一个方块的模型在"没有方块实体数据"的前提下能不能画出几何体。
     * <p>
     * 投影和这里的面板预览都拿不到方块实体，只能传 {@code ModelData.EMPTY}。
     * 多数方块无所谓，但 AE2 的 ME 线缆模型一上来就去 ModelData 里取渲染状态，
     * 取不到直接返回空列表——一个面都画不出来。
     */
    private static boolean canRender(BlockState state, BlockRenderDispatcher dispatcher) {
        return RENDERABLE.computeIfAbsent(state, s -> {
            BakedModel model = dispatcher.getBlockModel(s);
            RandomSource random = RandomSource.create(42L);
            for (Direction direction : Direction.values()) {
                if (!model.getQuads(s, direction, random, ModelData.EMPTY, null).isEmpty()) {
                    return true;
                }
            }
            return !model.getQuads(s, null, random, ModelData.EMPTY, null).isEmpty();
        });
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

        Schematic schematic = ClientSchematicCache.get(id,
                BlueprintItem.getRotation(stack), BlueprintItem.getMirror(stack));
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
            // 换了图，层数与起点跟着重算，看到第几层回到整栋
            layers.reset(schematic, previewEntries);
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
            graphics.drawString(this.font, Component.translatable("gui.maidconstruction.no_files"),
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
        // 先接分层条：它贴着预览框右边缘，压在最上面
        if (BlueprintLayerView.overBar(mouseX, mouseY, barX(), barTop(), barBottom())) {
            if (button == 1) {
                layers.toggleSlice();
            } else {
                this.draggingLayer = true;
                layers.dragTo(mouseY, barTop(), barBottom());
            }
            return true;
        }
        // 右键点预览任意处＝切"看到这层为止 / 只看这一层"，不必非得点在条上
        if (button == 1 && isOverPreview(mouseX, mouseY)) {
            layers.toggleSlice();
            return true;
        }
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
        // 预览区上的滚轮＝缩放（换层归右边那条拖动条，别抢）
        if (isOverPreview(mouseX, mouseY) && delta != 0.0D) {
            this.zoom = Mth.clamp((float) (this.zoom * (delta > 0.0D ? 1.1D : 1.0D / 1.1D)), 0.35F, 4.0F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingLayer = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingLayer) {
            layers.dragTo(mouseY, barTop(), barBottom());
            return true;
        }
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

    // 分层拖动条的三个换算：画与点共用，免得"画在这、点在那"。贴着预览框右边缘，上下各留出余量
    private int barX() {
        return (this.width - WINDOW_WIDTH) / 2 + PREVIEW_WIDTH - 12;
    }

    private int barTop() {
        return (this.height - WINDOW_HEIGHT) / 2 + HEADER_HEIGHT + 12;
    }

    private int barBottom() {
        return (this.height - WINDOW_HEIGHT) / 2 + WINDOW_HEIGHT - 22;
    }

    // ------------------------------------------------------------------
    // 按钮动作
    // ------------------------------------------------------------------

    private void onRotate() {
        ItemStack stack = getStack();
        Rotation next = BlueprintItem.cycleRotation(stack);
        // 翻面状态照原样带上：服务端那边朝向是"旋转 + 翻面"两个数，漏一个就等于把它复位了
        ModNetwork.CHANNEL.sendToServer(new C2SSetOrientationPacket(next, BlueprintItem.getMirror(stack)));
        setStatus(Component.translatable("gui.maidconstruction.rotated_to", (next.ordinal() * 90) + "°"));
    }

    /**
     * 翻面：把结构照镜子反过来，位置和方块朝向一起翻。
     * <p>
     * 与旋转一样当场发一次朝向包存进物品 NBT，投影下一帧就换过来。
     * 只翻一根轴（左右）就够——翻面配四向旋转能凑出全部 8 种朝向，界面上因此是个开关。
     */
    private void onMirror() {
        ItemStack stack = getStack();
        BlueprintItem.toggleMirror(stack);
        ModNetwork.CHANNEL.sendToServer(
                new C2SSetOrientationPacket(BlueprintItem.getRotation(stack), BlueprintItem.getMirror(stack)));
        setStatus(Component.translatable(BlueprintItem.isMirrored(stack)
                ? "gui.maidconstruction.mirrored_on" : "gui.maidconstruction.mirrored_off"));
    }

    private void onClearAnchor() {
        BlueprintItem.clearAnchor(getStack());
        ModNetwork.CHANNEL.sendToServer(new C2SSetAnchorPacket(true));
        setStatus(Component.translatable("message.maidconstruction.anchor_cleared"));
    }

    private void onClearSchematic() {
        // 还没用上的选点也一起清掉：这个按钮就是"从头来过"。
        // （潜行右键现在是"取点击面外面的那一格"，不再兼任清除选点）
        BlueprintItem.clearSelection(getStack());
        BlueprintItem.clearSchematic(getStack());
        ModNetwork.CHANNEL.sendToServer(C2SClearBlueprintPacket.INSTANCE);
        // 内容都没了，面板留着也没意义
        onClose();
    }

    /**
     * 打开建筑清单。
     * <p>
     * 走之前**先把名字同步出去**：清单是另一个界面，从它返回时面板会重新 {@code init()}，
     * 名字框的值是从物品 NBT 重读的——刚敲进去还没同步的名字会被打回原样。
     */
    private void onMaterials() {
        ItemStack stack = getStack();
        UUID id = BlueprintItem.getSchematicId(stack);
        if (id == null) {
            setStatus(Component.translatable("gui.maidconstruction.no_preview"));
            return;
        }
        syncName();
        Minecraft.getInstance().setScreen(new BlueprintMaterialsScreen(this, id,
                BlueprintItem.getRotation(stack), BlueprintItem.getMirror(stack)));
    }

    /** 名字框和物品上的名字不一致时，同步给服务端存进 NBT */
    private void syncName() {
        if (nameBox == null) {
            return;
        }
        String name = nameBox.getValue().trim();
        if (!name.equals(BlueprintItem.getBlueprintName(getStack()))) {
            ModNetwork.CHANNEL.sendToServer(new C2SSetNamePacket(name));
        }
    }

    private void onExport() {
        Schematic schematic = getPreview();
        if (schematic == null) {
            setStatus(Component.translatable("gui.maidconstruction.nothing_to_export"));
            return;
        }
        try {
            // 文件名跟随左上角输入的名称
            String name = nameBox.getValue().trim();
            if (name.isEmpty()) {
                name = Component.translatable("tooltip.maidconstruction.unnamed").getString();
            }
            byte[] data = BlueprintTransfer.encode(schematic);
            Path file = BlueprintTransfer.writeToFile(name, data);
            setStatus(Component.translatable("gui.maidconstruction.exported", file.getFileName().toString()));
            refreshFiles();
        } catch (IOException e) {
            setStatus(Component.translatable("gui.maidconstruction.export_failed"));
        }
    }

    private void onImport() {
        if (selectedFile < 0 || selectedFile >= files.size()) {
            setStatus(Component.translatable("gui.maidconstruction.no_file_selected"));
            return;
        }
        Path selected = files.get(selectedFile);
        try {
            byte[] data = Files.readAllBytes(selected);
            if (data.length > BlueprintTransfer.MAX_FILE_BYTES) {
                setStatus(Component.translatable("message.maidconstruction.import_too_large"));
                return;
            }
            // 导入后蓝图名字跟随文件名，输入框当场同步
            String name = BlueprintTransfer.displayName(selected);
            nameBox.setValue(name);
            ModNetwork.CHANNEL.sendToServer(new C2SImportBlueprintPacket(data, name));
            // 记一笔：结构回来之后由 refreshDynamicState 把这句话换成导入结果
            this.importPending = true;
            this.importAskedAt = System.currentTimeMillis();
            setStatus(Component.translatable("gui.maidconstruction.importing"));
        } catch (IOException e) {
            setStatus(Component.translatable("message.maidconstruction.import_failed"));
        }
    }

    private void onOpenFolder() {
        Path dir = BlueprintTransfer.getExportDirectory();
        try {
            Util.getPlatform().openFile(dir.toFile());
            setStatus(Component.translatable("gui.maidconstruction.opened_folder", dir.toString()));
        } catch (Exception e) {
            setStatus(Component.translatable("gui.maidconstruction.open_folder_failed"));
        }
    }

    @Override
    public void onClose() {
        // 关闭时把名字同步给服务端，存进物品 NBT
        syncName();
        super.onClose();
    }

    /** 只在面板内显示，不往聊天栏推——面板里的操作不该再弹一遍提示 */
    private void setStatus(Component message) {
        this.status = message;
    }
}
