package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockHarvest;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.integration.maid.MaidCompat;
import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 把"这块放不下"的位置画出来：**挡路的**用红框、**缺支撑的**用黄框。
 * <p>
 * 判定和服务端 {@code BuildSession.deferred} 用的是同一套规则，所以画出来的就是她
 * 真正卡住的那几处：
 * <ul>
 *   <li><b>缺支撑</b>（黄）：那位置是空的，但目标方块在那儿立不住（火把下方被挖空之类）；</li>
 *   <li><b>搬不走的挡路</b>（红）：位置上那个方块她动不了——不可破坏的（基岩、屏障），
 *       或者要她没有的工具（挖掘等级高过下界合金）。</li>
 * </ul>
 * 能拆掉的方块**不画**：那种她自己会清掉，画出来只会让屏幕一片红。
 * <p>
 * 数据全在客户端现算（结构来自 {@link ClientSchematicCache}，世界状态客户端本来就有），
 * 所以**不需要新协议、不需要服务端配合**。施工期间一直显示。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlockedSpotHighlighter {

    /** 只画玩家附近这么大范围里的：太远看不到，算了也是白算 */
    private static final double RANGE = 48.0D;
    /** 一帧最多画几个框：大结构上挡路的位置可能很多，全画会掉帧 */
    private static final int MAX_BOXES = 256;

    /**
     * 结构 → 非空气方块清单的缓存。
     * <p>
     * {@code entries()} 每次调用都要**遍历整座结构**并新建一份列表：九千多元素的列表
     * 每帧重建一次，帧率就是这么被吃掉的。同一张图的内容不会变，缓存住就行。
     */
    private static final java.util.Map<String, java.util.List<Schematic.BlockEntry>> ENTRY_CACHE =
            new java.util.HashMap<>();

    private BlockedSpotHighlighter() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        // 女仆模组没装就什么都不画。**这个方法自己的字节码里只要出现一次
        // EntityMaid 的名字**，没装女仆的客户端执行到那儿就是一次
        // NoClassDefFoundError（投影那个渲染器 1.6.3 就是这么崩的，这里同样有一句）。
        // 女仆相关的代码现在全关在 MaidClientBridge 里，只有确认装了才会被加载
        java.util.List<ItemStack> held = MaidCompat.isLoaded()
                ? MaidClientBridge.heldBlueprints((net.minecraft.client.multiplayer.ClientLevel) level)
                : java.util.List.of();

        int drawn = 0;
        for (ItemStack stack : held) {
            if (drawn >= MAX_BOXES) {
                break;
            }
            if (stack.isEmpty()) {
                continue;
            }
            UUID id = BlueprintItem.getSchematicId(stack);
            BlockPos anchor = BlueprintItem.getAnchor(stack);
            if (id == null || anchor == null) {
                continue;
            }
            Schematic schematic = ClientSchematicCache.get(id,
                    BlueprintItem.getRotation(stack), BlueprintItem.getMirror(stack));
            if (schematic == null) {
                continue;
            }
            // 缓存键必须带上朝向：条目里存的是**变换后**那份结构的坐标，
            // 只按 id 缓的话，转个向或翻个面，画出来的还是上一个朝向的位置
            java.util.List<Schematic.BlockEntry> entries = ENTRY_CACHE.computeIfAbsent(
                    id + "|" + BlueprintItem.getRotation(stack).ordinal()
                            + "|" + BlueprintItem.getMirror(stack).ordinal(),
                    key -> java.util.List.copyOf(schematic.entries()));
            for (Schematic.BlockEntry entry : entries) {
                if (drawn >= MAX_BOXES) {
                    break;
                }
                BlockPos world = anchor.offset(entry.pos());
                if (world.distToCenterSqr(camera.x, camera.y, camera.z) > RANGE * RANGE) {
                    continue;
                }
                BlockState target = entry.state();
                if (level.getBlockState(world).equals(target)) {
                    continue; // 已经到位了，不是问题
                }

                float red;
                float green;
                if (!target.canSurvive(level, world)) {
                    red = 1.0F;      // 黄框：缺支撑
                    green = 0.85F;
                } else {
                    BlockState here = level.getBlockState(world);
                    if (here.isAir()) {
                        continue;    // 空的、又能立住：只是还没轮到，不算问题
                    }
                    if (BlockHarvest.canHarvest(level, world, here, BlockHarvest.toolFor(here))) {
                        continue;    // 她拆得掉：会自己清，别画
                    }
                    red = 1.0F;      // 红框：搬不走的挡路
                    green = 0.15F;
                }
                LevelRenderer.renderLineBox(pose, lines,
                        new AABB(world).inflate(0.002D), red, green, 0.1F, 1.0F);
                drawn++;
            }
        }

        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
