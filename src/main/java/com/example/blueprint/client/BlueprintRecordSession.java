package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SCaptureToFilePacket;
import com.example.blueprint.network.packet.C2SCommandPostProjectionPacket;
import com.example.blueprint.network.packet.C2SRecordModePacket;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 录制态（客户端这一半）：在世界里框选一座建筑，**录成 {@code blueprints} 目录里的一份图纸**。
 * <p>
 * 从图纸库界面点「录制」进来，退出有三条路：
 * <ul>
 *   <li><b>E 完成</b>——两个角点都得点到，然后请服务端扫描（客户端读的方块实体 NBT 往往是残的），
 *       扫出来的结构由服务端发回来、由客户端写进目录（见 {@link C2SCaptureToFilePacket}）；</li>
 *   <li><b>R 重置</b>——清掉两个角点，重新框；</li>
 *   <li><b>Q 取消</b>——什么都不录，退出。</li>
 * </ul>
 * <p>
 * <b>录制不经过蓝图</b>：蓝图物品是"把某一份取到手上、交给女仆"的载体，而录制是往目录里录一份。
 * 让录制先写进手上一张纸、再让玩家去导出，等于凭空多一步，还要占住他手上的格子。
 * <p>
 * <b>为什么是"录制态"而不是界面</b>：框选要一边看着建筑一边点，界面一开就看不全了。
 * 所以这个类不画面板，只做三件事：抢按键、画选区的线框、在屏幕角上写几行提示。
 * <p>
 * <b>微调用方向键</b>：右键定下的两个角点往往差一格，站在地上又不好凑——所以留了键盘微调。
 * 两个角点按**先点的叫起点、后点的叫终点**分工，**Ctrl 把这一下从终点换成起点**，
 * Shift 一律反向：
 * <pre>
 *   ←             终点 X 加一        Ctrl+←        起点 X 加一
 *   →             终点 Z 加一        Ctrl+→        起点 Z 加一
 *   ↑             终点 Y 加一        Ctrl+↑        起点 Y 加一
 *   ↓             起点 Y 减一        Ctrl+↓        终点 Y 减一
 * </pre>
 * 三根轴各给各的键，而不是让方向键自己猜"你想调哪根"：录制时人贴着建筑看，
 * 屏幕上"左右"到底是世界的哪个方向，玩家自己心里有数，猜错了反而要点两次才知道偏了。
 * 高度分成上下界两条键（↑ 抬终点、↓ 压起点）：框的顶与底本来就要分开动；
 * 水平方向一般只差一格，一根轴一个键就够。只点了一个角时方向键照样能动它
 * ——先框起来、边看边调，比"先点齐两个角再调"顺手。
 * <p>
 * <b>抢键的边界</b>：只有"这个世界里没开着任何界面"时才抢。左键由那个**可取消**的事件吃掉
 * （录制时不该挖到东西）；{@code E}（开背包）与 {@code Q}（丢东西）没法靠取消事件——
 * {@code InputEvent.Key} 不可取消，所以录制期间干脆**把这两个键从控制里摘掉**，
 * 退出时原样还回去，见 {@link #captureVanillaKeys}。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlueprintMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintRecordSession {

    private static final Minecraft mc = Minecraft.getInstance();

    /** 屏幕左上角那几行提示的底色 */
    private static final int COLOR_BACKDROP = 0xA0000000;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_HINT = 0xAAAAAA;

    /** 在世界里摆东西的两种用途：键位与收尾不一样，架子（飞行、穿墙、摘键、提示）是同一套 */
    private enum Mode {
        /** 框两个角点，录成一份图纸文件 */
        RECORD,
        /** 选一个位置，把图纸库里的某一份托管到指挥台上 */
        PROJECT
    }

    private static Mode mode = Mode.RECORD;
    private static boolean active;

    /** PROJECT：投影要托管到哪台指挥台，以及托的是哪一份（名字 + 文件字节，按 E 才发出去） */
    @Nullable
    private static BlockPos projectPost;
    private static String projectName = "";
    @Nullable
    private static byte[] projectData;
    /** PROJECT：投影钉在哪一格（还没选就是 null）、朝向、以及那份结构本身（画幽灵与线框都用它） */
    @Nullable
    private static BlockPos projectAnchor;
    private static Rotation projectRotation = Rotation.NONE;
    private static Mirror projectMirror = Mirror.NONE;
    @Nullable
    private static Schematic projectSchematic;
    /**
     * 摆投影时给这份结构用的本地 id。
     * <p>
     * 结构已经在客户端手上（就是那份文件），不必再向服务端要一次；但渲染那条路是按 id 去
     * {@code ClientSchematicCache} 里取的，所以这里合成一个、把它放进去——
     * 于是**幽灵方块用的是现成那套渲染**，不必在这儿再写一份。
     */
    @Nullable
    private static UUID projectPreviewId;
    /** 录制期间被摘掉的两个原版键，退出时原样还回去（null = 没摘过） */
    @Nullable
    private static InputConstants.Key savedInventoryKey;
    @Nullable
    private static InputConstants.Key savedDropKey;
    @Nullable
    private static BlockPos corner1;
    @Nullable
    private static BlockPos corner2;
    /** 这份图纸的名字，来自图纸库界面那个输入框：既是文件名，也是列表里显示的名字 */
    private static String name = "";

    private BlueprintRecordSession() {
    }

    public static boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------
    // 进出
    // ------------------------------------------------------------------

    /** 进入录制态：框两个角点，按 E 录成一份图纸文件 */
    public static void start(String blueprintName) {
        mode = Mode.RECORD;
        name = blueprintName == null ? "" : blueprintName;
        corner1 = null;
        corner2 = null;
        active = true;
        captureVanillaKeys();
        ModNetwork.CHANNEL.sendToServer(new C2SRecordModePacket(true));
    }

    /**
     * 进入**投影定位态**：从图纸库里点了「投影」之后回到世界，右键选位置，`←/→` 转、`↑/↓` 翻，E 定下来。
     * <p>
     * 与录制共用同一套架子（能飞、能穿墙、摘掉原版那两个键、屏幕角上写提示），差别只在三处：
     * 右键选的是**一个位置**（结构的最小角），方向键留给**朝向**而不是挪格子，
     * R 是"清空位置"而不是"重来一遍"——投影要反复试的就是位置与朝向，清空比一格一格退回去快。
     *
     * @param postPos 投影托管到哪台指挥台
     * @param data    那份图纸的字节：按 E 时才发出去
     */
    public static void startProjection(BlockPos postPos, String blueprintName, byte[] data) {
        mode = Mode.PROJECT;
        projectPost = postPos;
        projectName = blueprintName == null ? "" : blueprintName;
        projectData = data;
        projectAnchor = null;
        projectRotation = Rotation.NONE;
        projectMirror = Mirror.NONE;
        // 结构在进来时解一次：它既要画幽灵也要量尺寸，每帧解一份几千方的结构不值
        try {
            projectSchematic = BlueprintTransfer.decode(data);
        } catch (Exception e) {
            projectSchematic = null;
        }
        if (projectSchematic != null) {
            projectPreviewId = UUID.randomUUID();
            ClientSchematicCache.put(projectPreviewId, projectSchematic);
        } else {
            projectPreviewId = null;
        }
        active = true;
        captureVanillaKeys();
        ModNetwork.CHANNEL.sendToServer(new C2SRecordModePacket(true));
    }

    /**
     * 正在摆的那一份投影；不在摆的时候返回 null。
     * <p>
     * 给 {@code ProjectionRenderer} 用的：它按 id 取结构、按锚点与朝向画幽灵，
     * 这里说的就是"我现在摆的这一份"，于是定位时看到的就是建出来会是什么样。
     * id 是本地合成的（结构就在手上），服务端那份要等按 E 才生成。
     */
    public record Preview(UUID id, Rotation rotation, Mirror mirror, BlockPos anchor) {
    }

    @Nullable
    public static Preview preview() {
        if (!active || mode != Mode.PROJECT || projectPreviewId == null || projectAnchor == null) {
            return null;
        }
        return new Preview(projectPreviewId, projectRotation, projectMirror, projectAnchor);
    }

    /**
     * 完成录制：服务端扫描，扫出来的结构**直接写进 {@code blueprints} 目录**。
     * <p>
     * 不经过蓝图：蓝图在这条路上只是"以后把某一份取到手上"的容器，
     * 而录制是"往目录里录一份"——先占住手上那张纸再让玩家去导出，中间那一步没有意义。
     * 落成文件之后结构也就跟物品 NBT 脱开了：拷给别人、删掉本地文件，都不牵连别处。
     */
    private static void finish() {
        if (mc.player == null) {
            return;
        }
        if (mode == Mode.PROJECT) {
            finishProjection();
            return;
        }
        if (corner1 == null || corner2 == null) {
            flash("gui.blueprint.record.need_two_corners");
            return;
        }

        // 与服务端那份扫描参数一致：两个角点原样发过去，顺序不重要（那边会自己规整）
        ModNetwork.CHANNEL.sendToServer(new C2SCaptureToFilePacket(corner1, corner2, name));
        stop();
    }

    /** 定下投影：位置、朝向、那份文件一起交给服务端——它存成结构数据，写进指挥台 */
    private static void finishProjection() {
        if (projectPost == null || projectData == null) {
            stop();
            return;
        }
        if (projectAnchor == null) {
            flash("gui.blueprint.project.need_anchor");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(C2SCommandPostProjectionPacket.place(
                projectPost, projectName, projectData, projectAnchor, projectRotation, projectMirror));
        stop();
    }

    /** R：录制态是"清掉两个角点重来"，投影定位态是"位置抹掉重新选"（朝向留着接着用） */
    private static void reset() {
        if (mode == Mode.PROJECT) {
            projectAnchor = null;
            return;
        }
        corner1 = null;
        corner2 = null;
    }

    /** 取消录制：退出录制态，什么也不录 */
    private static void cancel() {
        stop();
    }

    /** 退出录制态本身（完成与取消都走这里） */
    private static void stop() {
        if (!active) {
            return;
        }
        active = false;
        corner1 = null;
        corner2 = null;
        // 投影那份字节、结构、本地 id 都要放掉：留着等于让几十上百 KB 一直挂在那儿
        projectPost = null;
        projectData = null;
        projectAnchor = null;
        projectSchematic = null;
        projectPreviewId = null;
        releaseVanillaKeys();
        ModNetwork.CHANNEL.sendToServer(new C2SRecordModePacket(false));
    }

    /**
     * 录制期间把原版那两个键**摘掉**，退出时原样还回去。
     * <p>
     * 为什么不是"取消按键事件"：{@code InputEvent.Key} **不可取消**——对它调 {@code setCanceled()}
     * 会抛 {@code UnsupportedOperationException}，整段处理就此打断（日志里那行 non-cancelable 就是它）。
     * 而"抢在原版读按键之前把 click 收掉"也不可靠：原版读按键比任何一个 tick 阶段都早，
     * 玩家按 Q 照样把东西丢出去。摘键是**确定生效**的：键都没绑，原版那句
     * {@code keyDrop.consumeClick()} 永远返回 false。
     * <p>
     * 只动客户端（{@code KeyMapping} 本来就是客户端状态），退出、取消、断线都会还回去。
     * 万一游戏正好在这中间崩了，重开后去"控制"里把它绑回来即可——比"随手丢东西"轻得多。
     */
    private static void captureVanillaKeys() {
        savedInventoryKey = mc.options.keyInventory.getKey();
        savedDropKey = mc.options.keyDrop.getKey();
        mc.options.keyInventory.setKey(InputConstants.UNKNOWN);
        mc.options.keyDrop.setKey(InputConstants.UNKNOWN);
    }

    /** 把摘掉的两个键还回去；没摘过就什么也不做 */
    private static void releaseVanillaKeys() {
        if (savedInventoryKey != null) {
            mc.options.keyInventory.setKey(savedInventoryKey);
            savedInventoryKey = null;
        }
        if (savedDropKey != null) {
            mc.options.keyDrop.setKey(savedDropKey);
            savedDropKey = null;
        }
    }

    /** 屏幕上飘一句提示（录制态没有界面，只能自己画，见 {@link #onRenderGui}） */
    private static void flash(String key) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    // ------------------------------------------------------------------
    // 右键选点与键盘微调
    // ------------------------------------------------------------------

    /**
     * 右键点方块。录制态：第一个点存角一，第二个点存角二，之后每次改**离它更近的那个角**。
     * 投影定位态只要**一个位置**——结构的最小角就钉在这一格，再点一次就是把它挪过去。
     */
    private static void pick(BlockPos pos) {
        if (mode == Mode.PROJECT) {
            projectAnchor = pos;
            return;
        }
        if (corner1 == null) {
            corner1 = pos;
        } else if (corner2 == null) {
            corner2 = pos;
        } else if (corner1.distSqr(pos) <= corner2.distSqr(pos)) {
            corner1 = pos;
        } else {
            corner2 = pos;
        }
    }

    /**
     * 动一下某个角点。
     *
     * @param axis      0=Y，1=X，2=Z
     * @param end       true 动终点（后点的那个角），false 动起点（先点的那个）
     * @param direction 带方向的格数：+1 加一格，-1 减一格
     */
    private static void nudge(int axis, boolean end, int direction) {
        // 只点了一个角的时候，方向键照样能落在它身上：先框起来、边看边调比"先点齐再调"顺手
        boolean writeToEnd = end && corner2 != null;
        BlockPos target = writeToEnd ? corner2 : corner1;
        if (target == null) {
            return;
        }

        BlockPos moved = switch (axis) {
            case 0 -> target.offset(0, direction, 0);
            case 1 -> target.offset(direction, 0, 0);
            default -> target.offset(0, 0, direction);
        };
        if (writeToEnd) {
            corner2 = moved;
        } else {
            corner1 = moved;
        }
    }

    /**
     * 投影定位态的键：`←` / `→` 转 90°，`↑` / `↓` 翻面开关，`R` 清空位置，`E` 定下来，`Q` 取消。
     * <p>
     * 与录制那条的区别其实就在这儿：录制时方向键是"挪一格"，这里没有格子可挪——
     * 位置靠右键点，方向键留给**朝向**，那才是这一步唯一要反复试的东西。
     */
    private static void handleProjectionKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        switch (event.getKey()) {
            case GLFW.GLFW_KEY_LEFT -> projectRotation = stepRotation(projectRotation, false);
            case GLFW.GLFW_KEY_RIGHT -> projectRotation = stepRotation(projectRotation, true);
            case GLFW.GLFW_KEY_UP -> projectMirror = Mirror.LEFT_RIGHT;
            case GLFW.GLFW_KEY_DOWN -> projectMirror = Mirror.NONE;
            case GLFW.GLFW_KEY_R -> reset();
            case GLFW.GLFW_KEY_E -> finish();
            case GLFW.GLFW_KEY_Q -> cancel();
            default -> {
            }
        }
    }

    /** 顺着 {@code Rotation} 的顺序走一格：顺时针 / 逆时针各 90° */
    private static Rotation stepRotation(Rotation current, boolean clockwise) {
        Rotation[] values = Rotation.values();
        return values[Math.floorMod(current.ordinal() + (clockwise ? 1 : -1), values.length)];
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        // 左键也吃掉：录制时贴着建筑看，很容易顺手挖到一块
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
            return;
        }

        event.setCanceled(true);
        // 用准星当前指着的那一格（mc.hitResult 每帧都会更新），比自己做一遍射线省事，
        // 而且拿到的就是玩家屏幕上看着的那一块——远近跟平时的触手可及一致
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            // 两种模式取的不是同一格：录制要的是**角点所在的方块**；定位要的是**点中那个面外侧的一格**
            // （与蓝图右键定位同一套手感）——直接拿方块本身的话，结构会陷进地里一格
            pick(mode == Mode.PROJECT
                    ? hit.getBlockPos().relative(hit.getDirection())
                    : hit.getBlockPos());
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!active || mc.screen != null || event.getAction() == GLFW.GLFW_RELEASE) {
            return;
        }
        // E 与 Q 是原版的功能键，先把它那一下 click 收掉（兜底，主手段见 captureVanillaKeys）
        if (event.getKey() == GLFW.GLFW_KEY_E || event.getKey() == GLFW.GLFW_KEY_Q) {
            swallowVanillaClicks();
        }

        // 投影定位态的键与录制不一样（那边挪格子，这边转朝向），先分出去
        if (mode == Mode.PROJECT) {
            handleProjectionKey(event);
            return;
        }

        // Shift = 反方向（四个键统一）；Ctrl = 把这一下从"终点"换成"起点"
        int modifiers = event.getModifiers();
        int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        switch (event.getKey()) {
            // 水平两根轴默认动**终点**（Ctrl 动起点），高度则上下界分开：
            // ↑ 抬终点、↓ 压起点，Ctrl 互换、Shift 反向
            case GLFW.GLFW_KEY_LEFT -> nudge(1, !ctrl, step);
            case GLFW.GLFW_KEY_RIGHT -> nudge(2, !ctrl, step);
            case GLFW.GLFW_KEY_UP -> nudge(0, !ctrl, step);
            case GLFW.GLFW_KEY_DOWN -> nudge(0, ctrl, -step);
            default -> {
                if (event.getAction() != GLFW.GLFW_PRESS) {
                    return;
                }
                switch (event.getKey()) {
                    case GLFW.GLFW_KEY_E -> finish();
                    case GLFW.GLFW_KEY_R -> reset();
                    case GLFW.GLFW_KEY_Q -> cancel();
                    default -> {
                        return;
                    }
                }
            }
        }
        // 这里**不能** setCanceled：InputEvent.Key 是不可取消的事件，对非 cancelable 的事件调它
        // 会当场抛 UnsupportedOperationException，把整段处理打断——按键白按，日志里还多一条 ERROR。
        // 原版那两个键的后果改由 swallowVanillaClicks 在 tick 开头收走。
    }

    // ------------------------------------------------------------------
    // 每刻的兜底
    // ------------------------------------------------------------------

    /**
     * 兜底：把原版那两个键"这一下"的 click 收走（{@code E} 会开背包、{@code Q} 会把东西丢出去）。
     * <p>
     * 主手段其实是{@link #captureVanillaKeys 把这两个键摘掉}——那一条确定生效。这里再收一道，
     * 是为了防"别的模组或某个版本又把它们绑回来"：收 click 不挑时机、不依赖事件能否取消，多这一道没有坏处。
     * <p>
     * 顺带把坑说清：{@code InputEvent.Key} **不可取消**，对它调 {@code setCanceled()} 会抛
     * {@code UnsupportedOperationException}，整段处理就此打断（日志里那行 non-cancelable 就是它）。
     */
    private static void swallowVanillaClicks() {
        while (mc.options.keyInventory.consumeClick()) {
            // 连点留下的计数也一并清干净
        }
        while (mc.options.keyDrop.consumeClick()) {
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!active) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            // 抢在原版读取按键之前，见 swallowVanillaClicks
            swallowVanillaClicks();
            return;
        }
        if (mc.player == null || mc.level == null) {
            stop();
            return;
        }
        // 死了就退出（能力原样还回去，重生之后不该还飞着）
        if (mc.player.isDeadOrDying()) {
            stop();
            return;
        }
        // 兜底：万一 E 那一下还是把背包开了（按键拦截没生效），关掉并按"完成"处理。
        // 宁可多这一句，也不要玩家按了 E 只看到背包、不知道录制算成功没有
        if (mc.screen instanceof InventoryScreen) {
            mc.setScreen(null);
            finish();
            return;
        }
        // noPhysics 不是同步字段：服务端那份由包改，客户端这份得自己按着，
        // 否则本地预测会在墙前停下（看着像"穿不过去"）
        mc.player.noPhysics = true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 断线不再发包（发不出去），只把自己这边清掉；服务端在 PlayerLoggedOutEvent 里还原。
        // 但摘掉的那两个键**必须还回去**：那是玩家自己的控制设置，不能因为我们退得狼狈就留在我们手上
        active = false;
        corner1 = null;
        corner2 = null;
        releaseVanillaKeys();
    }

    // ------------------------------------------------------------------
    // 画：选区线框 + 左上角那几行
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        // 两种模式各画各的方框：录制是两个角点圈出来的那块，投影是"这份结构摆在这儿有多大"
        boolean projection = mode == Mode.PROJECT;
        if (projection ? projectAnchor == null : corner1 == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.LINES);
        LevelRenderer.renderLineBox(pose, lines, projection ? projectBox() : box(), 0.35F, 0.9F, 1.0F, 1.0F);
        buffers.endBatch(RenderType.LINES);

        pose.popPose();
    }

    /**
     * 投影的方框：按这份结构的尺寸与当前旋转算出来。
     * <p>
     * 转 90° 时宽与长互换——与真正放置时的换算一致（结构先按朝向变换、再摆上去），
     * 所以框看到多大，建出来就是多大。
     */
    private static AABB projectBox() {
        BlockPos at = projectAnchor;
        if (at == null || projectSchematic == null) {
            return new AABB(0, 0, 0, 1, 1, 1);
        }
        Vec3i size = projectSchematic.getSize();
        int width = size.getX();
        int length = size.getZ();
        if (projectRotation == Rotation.CLOCKWISE_90 || projectRotation == Rotation.COUNTERCLOCKWISE_90) {
            int swap = width;
            width = length;
            length = swap;
        }
        return new AABB(at.getX(), at.getY(), at.getZ(),
                at.getX() + width, at.getY() + size.getY(), at.getZ() + length);
    }

    /**
     * 两个角点圈出的方框；只点了一个角时就是那一格。
     * <p>
     * 角点的先后是玩家右键的顺序，两个角谁大谁小都有可能，所以这里自己取一遍上下界，
     * 不能拿"角一"当最小角用。
     */
    private static AABB box() {
        BlockPos a = corner1;
        BlockPos b = corner2 == null ? corner1 : corner2;
        if (a == null || b == null) {
            return new AABB(0, 0, 0, 1, 1, 1);
        }
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0D, Math.max(a.getY(), b.getY()) + 1.0D,
                Math.max(a.getZ(), b.getZ()) + 1.0D);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!active) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();

        int screenWidth = mc.getWindow().getGuiScaledWidth();

        if (mode == Mode.PROJECT) {
            drawProjectionHud(graphics, screenWidth);
            return;
        }

        String title = Component.translatable("gui.blueprint.record.title",
                name.isEmpty() ? Component.translatable("tooltip.blueprint.unnamed").getString() : name).getString();
        // 名字是玩家自己填的（最长 48 字），太长就把这一行截短——宁可少看几个字，
        // 也别让整块提示被屏幕右边缘切掉
        title = mc.font.plainSubstrByWidth(title, Math.max(40, screenWidth - 32));

        String hint = corner1 == null
                ? Component.translatable("gui.blueprint.record.pick_first").getString()
                : corner2 == null
                ? Component.translatable("gui.blueprint.record.pick_second").getString()
                : Component.translatable("gui.blueprint.record.size",
                        boxSize(1), boxSize(0), boxSize(2), boxVolume()).getString();

        // 按键提示分两行：一整行写全曾经长到被屏幕切掉，而按键是录制时最常看的一行，
        // 宁可多占一行高度，也不能让它读不全
        String nudge = Component.translatable("gui.blueprint.record.keys_nudge").getString();
        String action = Component.translatable("gui.blueprint.record.keys_action").getString();

        int width = Math.max(Math.max(mc.font.width(title), mc.font.width(hint)),
                Math.max(mc.font.width(nudge), mc.font.width(action))) + 8;
        width = Math.min(width, screenWidth - 8);
        graphics.fill(4, 4, 4 + width, 58, COLOR_BACKDROP);
        graphics.drawString(mc.font, title, 8, 8, COLOR_TEXT, false);
        graphics.drawString(mc.font, hint, 8, 20, COLOR_HINT, false);
        graphics.drawString(mc.font, nudge, 8, 32, COLOR_HINT, false);
        graphics.drawString(mc.font, action, 8, 44, COLOR_HINT, false);
    }

    /** 投影定位态的屏幕提示：托的是哪张图、位置选了没有、当前朝向、按键 */
    private static void drawProjectionHud(GuiGraphics graphics, int screenWidth) {
        String title = Component.translatable("gui.blueprint.project.title",
                projectName.isEmpty()
                        ? Component.translatable("tooltip.blueprint.unnamed").getString() : projectName).getString();
        title = mc.font.plainSubstrByWidth(title, Math.max(40, screenWidth - 32));

        String hint = projectAnchor == null
                ? Component.translatable("gui.blueprint.project.pick").getString()
                : Component.translatable("gui.blueprint.project.at",
                        projectAnchor.getX(), projectAnchor.getY(), projectAnchor.getZ()).getString();
        String facing = Component.translatable("gui.blueprint.project.facing",
                (projectRotation.ordinal() * 90) + "°",
                Component.translatable(projectMirror == Mirror.NONE
                                ? "gui.blueprint.project.mirror_off" : "gui.blueprint.project.mirror_on")
                        .getString()).getString();
        String keys = Component.translatable("gui.blueprint.project.keys").getString();

        int width = Math.max(Math.max(mc.font.width(title), mc.font.width(hint)),
                Math.max(mc.font.width(facing), mc.font.width(keys))) + 8;
        width = Math.min(width, screenWidth - 8);
        graphics.fill(4, 4, 4 + width, 58, COLOR_BACKDROP);
        graphics.drawString(mc.font, title, 8, 8, COLOR_TEXT, false);
        graphics.drawString(mc.font, hint, 8, 20, COLOR_HINT, false);
        graphics.drawString(mc.font, facing, 8, 32, COLOR_HINT, false);
        graphics.drawString(mc.font, keys, 8, 44, COLOR_HINT, false);
    }

    private static int boxSize(int axis) {
        BlockPos max = corner2 == null ? corner1 : corner2;
        int a = switch (axis) {
            case 0 -> corner1.getY();
            case 1 -> corner1.getX();
            default -> corner1.getZ();
        };
        int b = switch (axis) {
            case 0 -> max.getY();
            case 1 -> max.getX();
            default -> max.getZ();
        };
        return Math.abs(a - b) + 1;
    }

    private static int boxVolume() {
        return boxSize(1) * boxSize(0) * boxSize(2);
    }
}
