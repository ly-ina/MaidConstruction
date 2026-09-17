package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.schematic.Schematic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 蓝图投影渲染。
 * <p>
 * 手持蓝图时把结构画成半透明的"幽灵方块"：
 * 已经建好的部分不画，被结构内部包裹的面直接剔除，
 * 只保留外表面，避免大方块量下的严重掉帧和半透明叠加。
 */
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ProjectionRenderer {

    /**
     * 半透明幽灵方块。
     * <p>
     * 直接用原版的半透明方块层：它已经配好方块图集、光照，以及"不写深度"的透明度设置，
     * 逐方块渲染时不会因为互相遮挡而闪烁，也不需要去碰 RenderStateShard 里那些受保护的常量。
     */
    private static final RenderType GHOST = RenderType.translucent();

    private static final int MAX_RENDER_BLOCKS = 20_000;
    private static final long RESCAN_INTERVAL_MS = 400;
    private static final long SEED = 42L;

    private record PendingBlock(int x, int y, int z, BlockState state) {
    }

    private static final List<PendingBlock> PENDING = new ArrayList<>();
    private static UUID cachedId;
    private static BlockPos cachedOrigin;
    private static long lastScan = 0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        ItemStack stack = BlueprintItem.findHeld(mc.player);
        if (stack.isEmpty()) {
            return;
        }

        // 只选了第一个角点时，画一个跟随视线的选区线框
        if (!BlueprintItem.hasSchematic(stack) && BlueprintItem.hasPos1(stack)) {
            renderSelectionBox(event, mc, BlueprintItem.getPos1(stack));
            return;
        }

        if (!BlueprintItem.hasSchematic(stack)) {
            return;
        }

        UUID id = BlueprintItem.getSchematicId(stack);
        if (id == null) {
            return;
        }
        Schematic schematic = ClientSchematicCache.get(id, BlueprintItem.getRotation(stack));
        if (schematic == null) {
            // 本地没有结构数据（例如物品 NBT 同步慢了一步），主动向服务端要一次
            requestSchematic(id);
            return;
        }

        BlockPos origin = BlueprintItem.hasAnchor(stack)
                ? BlueprintItem.getAnchor(stack)
                : resolveLookOrigin(mc);
        if (origin == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScan > RESCAN_INTERVAL_MS
                || !Objects.equals(id, cachedId)
                || !Objects.equals(origin, cachedOrigin)) {
            rebuild(mc.level, schematic, origin);
            cachedId = id;
            cachedOrigin = origin;
            lastScan = now;
        }

        if (PENDING.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        PoseStack pose = event.getPoseStack();
        Vec3 cameraPos = camera.getPosition();

        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        // PENDING 里存的是结构内的相对坐标，必须先把画笔挪到锚点，
        // 否则整座结构会被画在世界原点，玩家在施工现场什么都看不到
        pose.translate(origin.getX(), origin.getY(), origin.getZ());

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(GHOST);
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        RandomSource random = RandomSource.create(SEED);

        for (PendingBlock block : PENDING) {
            renderGhost(pose, consumer, dispatcher, random, schematic, block);
        }

        buffers.endBatch(GHOST);
        pose.popPose();
    }

    private static void renderGhost(PoseStack pose, VertexConsumer consumer, BlockRenderDispatcher dispatcher,
                                    RandomSource random, Schematic schematic, PendingBlock block) {
        BlockState state = block.state();
        BakedModel model = dispatcher.getBlockModel(state);

        pose.pushPose();
        // 轻微内缩，让相邻方块之间有细缝，观感更像"图纸"
        pose.translate(block.x() + 0.002D, block.y() + 0.002D, block.z() + 0.002D);
        pose.scale(0.996F, 0.996F, 0.996F);

        for (Direction direction : Direction.values()) {
            if (isCovered(schematic, block.x() + direction.getStepX(),
                    block.y() + direction.getStepY(),
                    block.z() + direction.getStepZ())) {
                continue;
            }
            for (BakedQuad quad : model.getQuads(state, direction, random, ModelData.EMPTY, null)) {
                consumer.putBulkData(pose.last(), quad, 0.45F, 0.68F, 1.0F, 0.30F,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
            }
        }
        // 不属于任何朝向的面（无 cullface 的方块）
        for (BakedQuad quad : model.getQuads(state, null, random, ModelData.EMPTY, null)) {
            consumer.putBulkData(pose.last(), quad, 0.45F, 0.68F, 1.0F, 0.30F,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
        }

        pose.popPose();
    }

    /**
     * 邻居在结构内部且是不透明方块时，这一面永远看不见，直接剔除。
     */
    private static boolean isCovered(Schematic schematic, int x, int y, int z) {
        if (!schematic.inBounds(x, y, z)) {
            return false;
        }
        BlockState neighbor = schematic.stateAt(x, y, z);
        return !neighbor.isAir() && neighbor.canOcclude();
    }

    /**
     * 重新计算需要投影的方块。
     * <p>
     * 这里刻意不去调用 {@link Schematic#entries()}：那个方法会为每一个方块新建
     * BlockPos 和 BlockEntry，大结构下每隔几百毫秒重建一次会产生海量垃圾对象。
     * 直接按下标遍历、复用同一个 MutableBlockPos 会好很多。
     */
    private static long lastRequestAt = 0;

    /**
     * 向服务端索取结构数据，两秒最多一次，避免每帧刷包。
     */
    private static void requestSchematic(UUID id) {
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < 2000L) {
            return;
        }
        lastRequestAt = now;
        ModNetwork.CHANNEL.sendToServer(new C2SRequestSchematicPacket(id));
    }

    private static void rebuild(Level level, Schematic schematic, BlockPos origin) {
        PENDING.clear();

        int baseX = origin.getX();
        int baseY = origin.getY();
        int baseZ = origin.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    if (schematic.isAir(x, y, z)) {
                        continue;
                    }
                    cursor.set(baseX + x, baseY + y, baseZ + z);
                    BlockState state = schematic.stateAt(x, y, z);
                    // 已经建好的不再投影
                    if (level.getBlockState(cursor).equals(state)) {
                        continue;
                    }
                    PENDING.add(new PendingBlock(x, y, z, state));
                    if (PENDING.size() >= MAX_RENDER_BLOCKS) {
                        return;
                    }
                }
            }
        }
    }

    private static void renderSelectionBox(RenderLevelStageEvent event, Minecraft mc, BlockPos pos1) {
        BlockPos look = resolveLookOrigin(mc);
        if (look == null || pos1 == null) {
            return;
        }

        AABB box = new AABB(Math.min(pos1.getX(), look.getX()),
                Math.min(pos1.getY(), look.getY()),
                Math.min(pos1.getZ(), look.getZ()),
                Math.max(pos1.getX(), look.getX()) + 1,
                Math.max(pos1.getY(), look.getY()) + 1,
                Math.max(pos1.getZ(), look.getZ()) + 1)
                .inflate(0.002D);

        PoseStack pose = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.LINES),
                box, 0.45F, 0.85F, 1.0F, 0.85F);
        buffers.endBatch(RenderType.LINES);

        pose.popPose();
    }

    @javax.annotation.Nullable
    private static BlockPos resolveLookOrigin(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) {
            return blockHit.getBlockPos().relative(blockHit.getDirection());
        }
        return null;
    }
}
