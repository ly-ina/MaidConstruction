package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一座结构画成半透明的"图纸"：终端里的缩略图、详情页的大图、蓝图面板的预览都用它。
 * <p>
 * 为什么抽出来：这三处画的是同一样东西，各自内联一份的话，以后只有一处被改到，
 * 就会出现"同一个建筑在面板里和在终端里不一样"——这种偏差没人会去核对，只会被当成 bug 报上来。
 * <p>
 * 画不出模型的方块（AE2 的 ME 线缆就是）退回线框：它的模型要靠方块实体喂渲染状态，
 * 而预览那个位置根本没有方块实体，{@code ModelData.EMPTY} 下一个面都算不出来。
 */
@OnlyIn(Dist.CLIENT)
public final class SchematicPreview {

    /** 一张图最多画这么多方块，超了按比例抽稀 */
    public static final int MAX_BLOCKS = 1500;
    /** 画不出模型的方块退回整格线框时的形状 */
    private static final AABB UNIT_CUBE = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D).inflate(-0.004D);

    private SchematicPreview() {
    }

    /**
     * 抽稀出一份"要画的方块"清单。
     * <p>
     * 结果要**由调用方缓存**：抽样本身要遍历整座建筑，放在每帧都会跑的绘制路径里，
     * 等于每帧白遍历一遍（蓝图面板当年就是这么被吃掉的）。
     */
    public static List<Schematic.BlockEntry> sample(Schematic schematic, int maxBlocks) {
        return sample(schematic.entries(), maxBlocks);
    }

    /**
     * 同一套抽稀，但吃的是**已经筛过的**清单——分层查看要用它：先按层筛掉不看的，再抽稀，
     * 顺序反过来的话，某一层本来就没剩几块，再抽一次就成了几个孤零零的方块，等于没看。
     */
    public static List<Schematic.BlockEntry> sample(List<Schematic.BlockEntry> all, int maxBlocks) {
        if (all.size() <= maxBlocks) {
            return all;
        }
        int step = (int) Math.ceil(all.size() / (double) maxBlocks);
        List<Schematic.BlockEntry> sampled = new ArrayList<>(maxBlocks + 1);
        for (int i = 0; i < all.size(); i += step) {
            sampled.add(all.get(i));
        }
        return sampled;
    }

    /**
     * 画在以 (cx, cy) 为中心、可用宽度为 {@code width} 的方框里。
     * <p>
     * 视角由 {@code yaw} / {@code pitch} 给（界面上拖动它们），朝向由 {@code rotation} / {@code mirror} 给——
     * 后者只给线缆部件的线框用：结构的坐标在拿到这张图时就已经按朝向变换好了。
     */
    public static void draw(GuiGraphics graphics, Schematic schematic, List<Schematic.BlockEntry> entries,
                            int cx, int cy, int width, float yaw, float pitch,
                            Rotation rotation, Mirror mirror, float zoom) {
        Vec3i size = schematic.getSize();
        if (scaleFor(schematic, width) <= 0.0F) {
            return;
        }
        float scale = scaleFor(schematic, width) * zoom;

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        applyPreviewTransform(pose, schematic, scale, cx, cy, yaw, pitch);

        List<Schematic.BlockEntry> unrenderable = null;
        for (Schematic.BlockEntry entry : entries) {
            if (canRender(entry.state(), dispatcher)) {
                pose.pushPose();
                pose.translate(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());
                dispatcher.renderSingleBlock(entry.state(), pose, buffers,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            } else {
                if (unrenderable == null) {
                    unrenderable = new ArrayList<>();
                }
                unrenderable.add(entry);
            }
        }

        if (unrenderable != null) {
            VertexConsumer lines = buffers.getBuffer(RenderType.LINES);
            for (Schematic.BlockEntry entry : unrenderable) {
                List<CableBusOutline.Outline> outlines = CableBusOutline.outlinesOf(schematic,
                        entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(), rotation, mirror);

                pose.pushPose();
                pose.translate(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());
                if (outlines == null) {
                    LevelRenderer.renderLineBox(pose, lines, UNIT_CUBE, 0.45F, 0.68F, 1.0F, 0.9F);
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

        pose.popPose();
    }

    /**
     * 画布宽度换算出的缩放；结构是空的返回 0。
     * <p>
     * 单拎出来是因为**画图与拾取必须用同一个数**：两处各算一遍的话，迟早一处改了、另一处没跟上，
     * 表现就是"越放大偏得越多"。界面自己的缩放倍数不在这里乘，由调用方乘完再一起用。
     */
    /**
     * 预览里的小提示框：**自己画，不用原版 {@code renderTooltip}**。
     * <p>
     * 原版那套在这层深度里画出来只有底框、字是空的（预览是带变换画在 z=200 的，提示得挪到它前面才看得见，
     * 而挪过去之后原版 tooltip 的字就画不出来了）。自己画就是几个 fill + drawString，颜色与深度都在手里。
     * <p>
     * 位置跟着鼠标，但会把框拉回屏幕内——贴边时提示被切掉一半，等于没提示。
     */
    public static void drawHintBox(GuiGraphics graphics, Font font, List<Component> lines,
                                   int mouseX, int mouseY, int screenWidth, int screenHeight) {
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int x = Math.min(mouseX + 8, screenWidth - width - 8);
        int y = Math.min(mouseY + 8, screenHeight - lines.size() * 10 - 8);
        graphics.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 10 + 1, 0xE0100010);
        graphics.renderOutline(x - 4, y - 4, width + 8, lines.size() * 10 + 6, 0x805000FF);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), x, y + i * 10, 0xFFFFFF, true);
        }
    }

    /**
     * 预览那套变换：**画、拾取、描边三处共用**。
     * <p>
     * 单拎出来是有原因的：这三件事必须落在同一个像素坐标系里。谁要是自己抄一份、又只改了其中一处，
     * 表现就是"看着在这儿、点着在那儿、框还画偏了"——而那种偏差很难一眼看出是哪一处先改的。
     */
    private static void applyPreviewTransform(PoseStack pose, Schematic schematic, float scale,
                                              int cx, int cy, float yaw, float pitch) {
        Vec3i size = schematic.getSize();
        // z 推到 200：界面上的绘制是二维的，不推到相机前面会被别的元素挡住
        pose.translate(cx, cy, 200.0D);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-size.getX() / 2.0D, -size.getY() / 2.0D, -size.getZ() / 2.0D);
    }

    /**
     * 给选中的方块描一圈高亮框（编辑图纸时用）。
     * <p>
     * 走的是与画方块同一套变换，所以框严丝合缝贴在方块表面上；框比方块**略大一圈**
     * （inflate 0.004），否则它与方块表面重合，深度上会被自己盖住、看着一闪一闪。
     */
    public static void drawHighlights(GuiGraphics graphics, Schematic schematic, List<BlockPos> positions,
                                      int cx, int cy, float scale, float yaw, float pitch) {
        if (positions.isEmpty() || scale <= 0.0F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        applyPreviewTransform(pose, schematic, scale, cx, cy, yaw, pitch);
        VertexConsumer lines = buffers.getBuffer(RenderType.LINES);
        for (BlockPos pos : positions) {
            pose.pushPose();
            pose.translate(pos.getX(), pos.getY(), pos.getZ());
            LevelRenderer.renderLineBox(pose, lines, UNIT_CUBE.inflate(0.004D), 1.0F, 0.85F, 0.2F, 1.0F);
            pose.popPose();
        }
        buffers.endBatch(RenderType.LINES);
        pose.popPose();
    }

    public static float scaleFor(Schematic schematic, int width) {
        Vec3i size = schematic.getSize();
        float maxDim = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        return maxDim <= 0.0F ? 0.0F : (width - 8) / (maxDim * 1.7F);
    }

    /**
     * 指针底下是哪一块。
     * <p>
     * 判据是**把方块的八个角都投回屏幕**，看指针落没落在它投影出来的那个框里——不是"离中心最近"：
     * 中心判据在斜视时会明显偏，玩家一眼就看得出"指着这块、显示的却是旁边那块"。
     * 都在框里时取**最靠前**的那块（画在最上面的那块）。
     * <p>
     * 缩放由调用方给（就是它画图用的那个值），不在这儿另算：见 {@link #scaleFor}。
     */
    @Nullable
    public static Schematic.BlockEntry pick(Schematic schematic, List<Schematic.BlockEntry> entries,
                                            int cx, int cy, float scale, float yaw, float pitch,
                                            double mouseX, double mouseY) {
        if (scale <= 0.0F) {
            return null;
        }
        PoseStack pose = new PoseStack();
        applyPreviewTransform(pose, schematic, scale, cx, cy, yaw, pitch);

        Schematic.BlockEntry best = null;
        double bestDepth = Double.NEGATIVE_INFINITY;
        Vector3f point = new Vector3f();
        for (Schematic.BlockEntry entry : entries) {
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double depth = Double.NEGATIVE_INFINITY;
            // 方块占的是 [x, x+1] 这一格：八个角投完取屏幕上的外接框，指针落在框里就算指着它
            for (int corner = 0; corner < 8; corner++) {
                point.set(entry.pos().getX() + ((corner & 1) == 0 ? 0.0F : 1.0F),
                        entry.pos().getY() + ((corner & 2) == 0 ? 0.0F : 1.0F),
                        entry.pos().getZ() + ((corner & 4) == 0 ? 0.0F : 1.0F));
                pose.last().pose().transformPosition(point);
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
                depth = Math.max(depth, point.z);
            }
            if (mouseX < minX || mouseX > maxX || mouseY < minY || mouseY > maxY) {
                continue;
            }
            // 都在指针底下时取最靠前的：z 越大离眼睛越近（draw 里把 z 推到 200 就是这个道理）
            if (depth > bestDepth) {
                bestDepth = depth;
                best = entry;
            }
        }
        return best;
    }

    /** 拿得到一个四边形就算画得出，一个都没有就是画不出（线缆那类要靠方块实体喂数据） */
    private static boolean canRender(BlockState state, BlockRenderDispatcher dispatcher) {
        BakedModel model = dispatcher.getBlockModel(state);
        RandomSource random = RandomSource.create();
        if (!model.getQuads(state, null, random, ModelData.EMPTY, null).isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (!model.getQuads(state, direction, random, ModelData.EMPTY, null).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
