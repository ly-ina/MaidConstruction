package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockHarvest;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.schematic.Schematic;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
    private static final java.util.Map<java.util.UUID, java.util.List<Schematic.BlockEntry>> ENTRY_CACHE =
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

        int drawn = 0;
        for (Entity entity : ((net.minecraft.client.multiplayer.ClientLevel) level).entitiesForRendering()) {
            if (drawn >= MAX_BOXES) {
                break;
            }
            if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
                continue;
            }
            ItemStack stack = heldBlueprint(maid);
            if (stack.isEmpty()) {
                continue;
            }
            UUID id = BlueprintItem.getSchematicId(stack);
            BlockPos anchor = BlueprintItem.getAnchor(stack);
            if (id == null || anchor == null) {
                continue;
            }
            Schematic schematic = ClientSchematicCache.get(id, BlueprintItem.getRotation(stack));
            if (schematic == null) {
                continue;
            }
            java.util.List<Schematic.BlockEntry> entries =
                    ENTRY_CACHE.computeIfAbsent(id, key -> java.util.List.copyOf(schematic.entries()));
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

    /** 她手上那张蓝图（先主手，再副手） */
    private static ItemStack heldBlueprint(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem();
        if (main.getItem() instanceof BlueprintItem) {
            return main;
        }
        ItemStack off = maid.getOffhandItem();
        return off.getItem() instanceof BlueprintItem ? off : ItemStack.EMPTY;
    }
}
