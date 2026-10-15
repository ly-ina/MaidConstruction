package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
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
                            Rotation rotation, Mirror mirror) {
        Vec3i size = schematic.getSize();
        float maxDim = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        if (maxDim <= 0.0F) {
            return;
        }
        float scale = (width - 8) / (maxDim * 1.7F);

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        // z 推到 200：界面上的绘制是二维的，不推到相机前面会被别的元素挡住
        pose.translate(cx, cy, 200.0D);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-size.getX() / 2.0D, -size.getY() / 2.0D, -size.getZ() / 2.0D);

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
     * 指针底下是哪一块：把每个方块的中心按 {@link #draw} 那套变换投到屏幕上，取离指针最近的那块
     * （都在阈值内时取**最靠前**的，也就是画在最上层的那块）。
     * <p>
     * 为什么用投影而不是真投一条射线：界面上这幅 3D 只是"看上去像"三维，其实是二维绘制
     * （变换全在 {@link #draw} 里那几行）——把中心点按同一套参数投过去，得到的就是玩家眼睛看到的
     * 那一块，而且不会出现"看到的和选中的对不上"。
     */
    @Nullable
    public static Schematic.BlockEntry pick(Schematic schematic, List<Schematic.BlockEntry> entries,
                                            int cx, int cy, int width, float yaw, float pitch,
                                            double mouseX, double mouseY) {
        Vec3i size = schematic.getSize();
        float maxDim = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        if (maxDim <= 0.0F) {
            return null;
        }
        float scale = (width - 8) / (maxDim * 1.7F);

        PoseStack pose = new PoseStack();
        pose.translate(cx, cy, 200.0D);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-size.getX() / 2.0D, -size.getY() / 2.0D, -size.getZ() / 2.0D);

        // 阈值跟着缩放走：图放得越大，方块在屏幕上越大，能容忍的偏差也该越大
        double limit = Math.max(6.0D, scale * 1.2D);
        Schematic.BlockEntry best = null;
        double bestDepth = Double.NEGATIVE_INFINITY;
        Vector3f point = new Vector3f();
        for (Schematic.BlockEntry entry : entries) {
            // 方块占的是 [x, x+1]，中心就是 +0.5：与 draw 里"先 translate 到 x 再画"等价
            point.set(entry.pos().getX() + 0.5F, entry.pos().getY() + 0.5F, entry.pos().getZ() + 0.5F);
            pose.last().pose().transformPosition(point);
            if (Math.abs(point.x - mouseX) > limit || Math.abs(point.y - mouseY) > limit) {
                continue;
            }
            // 都在指针底下时取最靠前的：z 越大离眼睛越近（draw 里把 z 推到 200 就是这个道理）
            if (point.z > bestDepth) {
                bestDepth = point.z;
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
