package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BindingBookItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把绑定书里记下的坐标用线框高亮出来。
 * <p>
 * 绑定书本来就是给远处仓库用的，光靠工具提示里那串坐标数字很难找到地方；
 * 高亮一下，站在基地里也能一眼看见自己绑的是哪个箱子。
 * <p>
 * 纯客户端行为：数据就在物品 NBT 里，不需要服务端参与，
 * 所以也不存在网络延迟。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BoundBlockHighlighter {

    /** 高亮持续时长 */
    private static final long DURATION_MS = 10_000L;
    /** 线框稍微往外撑一点，免得和方块表面重叠在一起闪 */
    private static final double INFLATE = 0.002D;
    /** 呼吸一次的周期 */
    private static final long PULSE_PERIOD_MS = 1_000L;

    private static BlockPos target;
    private static ResourceLocation dimension;
    private static long expireAt;

    private BoundBlockHighlighter() {
    }

    /**
     * 开始高亮。
     * <p>
     * 顺手把"能不能画出来"提前讲清楚，免得玩家对着一个永远不会出现的线框发愣。
     */
    public static void highlight(Player player, ItemStack stack) {
        BlockPos pos = BindingBookItem.getBoundPos(stack);
        if (pos == null) {
            return;
        }
        ResourceLocation boundDimension = BindingBookItem.getBoundDimension(stack);
        target = pos;
        dimension = boundDimension;
        expireAt = System.currentTimeMillis() + DURATION_MS;

        Level level = player.level();
        if (boundDimension != null && !boundDimension.equals(level.dimension().location())) {
            player.displayClientMessage(
                    Component.translatable("message.blueprint.highlight_other_dimension"), true);
        } else if (!level.isLoaded(pos)) {
            player.displayClientMessage(
                    Component.translatable("message.blueprint.highlight_not_loaded"), true);
        } else {
            player.displayClientMessage(Component.translatable("message.blueprint.highlight_started",
                    BindingBookItem.getBoundBlockName(stack)), true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (target == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now > expireAt) {
            target = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        // 换了维度就别画了，否则会拿 A 世界的坐标在 B 世界框住一格
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            return;
        }
        if (!level.isLoaded(target)) {
            return;
        }

        // 让线框缓慢呼吸，比一个静止的框更容易被眼睛抓到
        float phase = (now % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
        float alpha = 0.45F + 0.35F * (float) Math.sin(phase * Math.PI * 2.0D);

        PoseStack pose = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        AABB box = new AABB(target).inflate(INFLATE);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.LINES),
                box, 0.35F, 0.95F, 1.0F, alpha);
        buffers.endBatch(RenderType.LINES);

        pose.popPose();
    }
}
