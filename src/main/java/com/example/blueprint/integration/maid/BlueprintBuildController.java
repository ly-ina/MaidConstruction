package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockContainerProvider;
import com.example.blueprint.build.BuildSession;
import com.example.blueprint.build.ItemProvider;
import com.example.blueprint.build.Salvage;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.item.BindingBookItem;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.S2CBuildProgressPacket;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一个女仆的施工状态机。
 * <p>
 * 流程很简单：走到结构旁边站定 -&gt; 站在原地逐块施工（带挥手和音效）-&gt;
 * 材料不够时去容器取一趟再回来 -&gt; 建完播报一次。
 * <p>
 * 施工范围不设限制，女仆站定后不再移动，所以既不需要垫脚也不需要搭桥。
 * 由 {@link MaidBuildTickHandler} 每 tick 驱动，不依赖车万女仆的 Brain。
 */
public class BlueprintBuildController {

    private enum State { MOVE_TO_SPOT, BUILD, FETCH }

    private static final int PLACE_COOLDOWN = 4;
    private static final int FETCH_COOLDOWN = 20;
    private static final int NO_SOURCE_COOLDOWN = 100;
    /**
     * 走到站位多近算到岗（只算水平，垂直另算）。
     * <p>
     * 特意定得比寻路的收敛精度宽松：寻路常常在离目标两三格的地方就停下。
     * 死守"两格内"的话，取料回来、或者走到大结构外圈的站位时，
     * 她永远算"没到岗"——BUILD 一进去又被判成"离岗"打回 MOVE_TO_SPOT，
     * 两个状态每 tick 互踢，看上去就是站在那儿一圈一圈地找路、死活不放方块。
     * 施工本来就没有距离限制（见类注释），站得宽松点没有任何坏处。
     */
    private static final double ARRIVE_DISTANCE_SQR = 12.25D;
    /** 到岗的垂直容差：站在同一层附近就行，不必踩在同一格高度 */
    private static final double ARRIVE_DY = 2.5D;
    /** 找站位最多找多久（tick）：找太久就放弃站位、就地开工，见 {@link #tickMoveToSpot} */
    private static final int MOVE_PATIENCE_TICKS = 150;
    /** 站位离结构最外围一圈往外留几格 */
    private static final int STAND_MARGIN = 2;
    /** 偏离站位超过这么远就算被别的 AI 拽走了，得回岗位 */
    private static final double LEAVE_SPOT_DISTANCE_SQR = 64.0D;
    private static final double CONTAINER_DISTANCE_SQR = 9.0D;
    /** 导航失败时的放弃门槛：只要没跑偏出这个范围，就就地把料取了 */
    private static final double ABORT_DISTANCE_SQR = 256.0D;
    /** 找落脚点时只看水平四个方向，上下两个方向站不住人 */
    private static final Direction[] HORIZONTAL_SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    /** 两次重新寻路之间至少隔这么多 tick，免得各路 AI 互相拉扯把女仆拽成原地打转 */
    private static final int REPATH_INTERVAL = 20;
    /**
     * 完工还料的总时长上限（约 30 秒）。
     * <p>
     * 还料卡住的话必须有个了结：收工那一步在还料之后，
     * 一直还不上就会让女仆永远停在"施工中"，待命状态也还不了原。
     */
    private static final int RETURN_TIMEOUT = 600;
    /**
     * 施工前的待命状态记在女仆自己的持久数据里。
     * <p>
     * 不用控制器字段来记，是因为女仆会随着区块卸载重载拿到一个新的控制器实例，
     * 字段跟着丢，完工后就再也还不回去了。
     */
    private static final String HOME_MODE_TAG = "BlueprintHomeModeBefore";
    private static final int CONTAINER_SEARCH_RADIUS = 10;
    private static final int CONTAINER_SEARCH_HEIGHT = 4;
    /** 连续走这么多 tick 还没到就认为过不去（约 6 秒） */
    private static final int MOVE_TIMEOUT = 120;
    /** 施工期间每隔多久重扫一遍（约 5 秒），用来发现中途被拆掉的方块 */
    private static final int RESCAN_INTERVAL = 100;
    /** 容器开合动画持续多少 tick */
    private static final int CONTAINER_ANIMATION_TICKS = 30;
    /** 同一类提示的最小间隔，别把聊天栏刷满 */
    private static final long MESSAGE_COOLDOWN_MS = 30_000L;
    /** 缺少材料时最多列出几种，免得聊天栏被刷屏 */
    private static final int MAX_REPORTED_MATERIALS = 5;
    /** 施工进度多久推一次（约半秒）。进度条是给人看的，半秒跳一下已经足够顺滑 */
    private static final int PROGRESS_INTERVAL = 10;
    /**
     * 进度只推给**附近**的玩家，距离由配置给（{@code progress.radius}）。
     * <p>
     * 推送半径比显示距离大一圈（{@value #PROGRESS_RADIUS_MARGIN} 格）：玩家走近时条已经在了，
     * 不会"走到跟前才突然蹦出来"。
     */
    private static final double PROGRESS_RADIUS_MARGIN = 8.0D;

    private BuildSession session;
    /** 整座结构一共要多少材料。还料时用它判断"哪些是这次工程带来的" */
    private Map<Item, Integer> bill = Map.of();
    /** 这一趟实际要补的材料：已建好的部分不算，取料按这个来 */
    private Map<Item, Integer> pendingBill = Map.of();
    /** 再扣掉背包已有的之后，真正还要从容器里拿的量。用它判断值不值得跑一趟 */
    private Map<Item, Integer> shortfall = Map.of();
    /**
     * 每种说法各自记的"上次说过什么内容"（说法 → 内容指纹）。
     * <p>
     * 用它挡重复：缺料有四条路径会开口（找不到来源、容器里没有、背包塞不下、绑定书仓库空了），
     * 各报各的就会一直弹——她每几秒重试一次，而缺料往往要玩家跑一趟仓库才好。
     * <p>
     * <b>账必须分开记</b>，这一点以前是错的：那时只有**一个**字段，四种说法共用一格，
     * 于是"容器里没有"刚把指纹写下去，"找不到来源"又把它覆盖掉，轮到"容器里没有"
     * 再说时又成了"新内容"——两句话来回覆盖，屏幕上就变成无限复读。
     * 玩家看到的正是"她一直在说同一件事"。
     */
    private final Map<String, String> lastShortfall = new HashMap<>();

    /** 缺料提示之间的最小间隔（约 3 秒），详见 {@link #shouldReportShortfall} */
    private static final long SHORTFALL_QUIET_MS = 3_000L;
    /** 上一次说缺料是什么时候 */
    private long lastShortfallAt = 0L;
    private UUID activeId;
    private BlockPos activeAnchor;
    private Rotation activeRotation = Rotation.NONE;
    private State state = State.MOVE_TO_SPOT;
    /** 女仆的施工站位，站定后不再挪窝 */
    private BlockPos standSpot;
    /**
     * 找这个站位已经找了多少 tick（见 {@link #MOVE_PATIENCE_TICKS}）。
     * 不叫 moveTicks：那个名字已经被"走向某个目标"那套逻辑用了，两处语义不同，别混。
     */
    private int spotSearchTicks = 0;
    /** 诊断日志的节拍：每 100 tick（约 5 秒）写一行"她卡在哪"，见 {@code tick} */
    private int debugTicks = 0;
    /** 这一趟要去取料的来源。可能是身边的箱子，也可能是绑定书指定的远程仓库 */
    private ItemProvider fetchProvider;
    /** 完工后正在使用的还料目标 */
    private ItemProvider returnProvider;
    /** 完工后的还料流程是否已经走完，避免每 tick 重复尝试 */
    private boolean returnFinished;
    /** 还料已经耗掉多少 tick，用来兜底超时 */
    private int returnTicks;
    private BlockPos moveTarget;
    private int moveTicks = 0;
    private int repathCooldown = 0;
    private int cooldown = 0;
    private int rescanTimer = RESCAN_INTERVAL;
    /** 离下一次推施工进度还有多少 tick */
    private int progressTimer = 0;
    private long lastMessageAt = 0;
    /** 正在播放开合动画的容器，以及剩余时间 */
    private BlockPos openContainerPos;
    private int openContainerTimer = 0;
    /**
     * 已经说过"挖不动"的方块种类。
     * <p>
     * 按种类封口而不是按次数：重扫会让同一面墙被反复"顶掉再放"，
     * 每一次都报就成了复读；而换了一种更硬的方块，该说的还是得说。
     * 换蓝图、收工时清空（见 {@link #refreshSession}、{@link #reset}），
     * 下一处工地重新说。
     */
    private final Set<ResourceLocation> toolWarned = new HashSet<>();


    // ------------------------------------------------------------------
    // 每 tick 驱动
    // ------------------------------------------------------------------

    public void tick(ServerLevel level, EntityMaid maid) {
        tickOpenContainer(level);

        ItemStack stack = findBlueprint(maid);
        if (stack.isEmpty()) {
            // 没带蓝图就安静待着，不用刷屏提醒
            setWorkingHomeMode(maid, false);
            reset();
            return;
        }
        if (!BlueprintItem.isMaidBuildEnabled(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_forbidden");
            return;
        }
        if (!MaidBlueprint.hasSchematic(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_empty_blueprint");
            return;
        }
        if (!MaidBlueprint.hasAnchor(stack)) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_no_anchor");
            return;
        }

        refreshSession(level, maid, stack);
        if (session == null) {
            setWorkingHomeMode(maid, false);
            // 机械动力那张读不出来时把原因说清楚：光一句"找不到结构"，
            // 玩家没法动手改（文件名没写？文件没上传？还是文件坏了？）
            if (MaidBlueprint.isCreate(stack)) {
                notify(level, maid, "message.blueprint.maid_create_unreadable", MaidBlueprint.problem());
            } else {
                notify(level, maid, "message.blueprint.maid_no_schematic");
            }
            return;
        }
        if (session.isFinished()) {
            // 建完了。先把背包里剩下的建造材料还回容器，还干净了再收工，
            // 免得女仆带着一兜子石头跟着主人到处跑
            if (!returnFinished && tickReturn(level, maid)) {
                return;
            }
            // 交还待命状态，女仆自己就会回去找主人
            setWorkingHomeMode(maid, false);
            onCompleted(level, maid, stack);
            return;
        }

        // 蓝图自己已经标着"完工"，那就别再往工地跑了。
        // 少了这一条，工地上的方块只要被拆掉几块，session 就不再是 finished，
        // 女仆会颠颠地跑过去补，补完又不满、不满又去……看起来就是没完没了地来回跑
        if (MaidBlueprint.isCompleted(level, stack)) {
            setWorkingHomeMode(maid, false);
            return;
        }

        // 施工期间钉在岗位上，别往主人那边跑
        setWorkingHomeMode(maid, true);

        // 每 5 秒把"她现在到底卡在哪一步"写一行日志。
        // 大结构上出问题时（站着不动、来回跑），光看现象猜不出来是哪个环节——
        // 这一行能直接看出是状态没切、还是进度不动、还是站位到不了、还是在等取料
        if (++debugTicks >= 100) {
            debugTicks = 0;
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 状态={} 进度 {}/{} 站位={} 下一块={} 取料={} 冷却={} 站位计时={}",
                    maid.getUUID(), state, session.done(), session.total(), standSpot,
                    session.peekNextTarget(level, activeAnchor),
                    fetchProvider == null ? "无" : fetchProvider.getClass().getSimpleName(),
                    cooldown, spotSearchTicks);
        }
        // 这里不能清完工标记：重扫后 session 是刚重建的，还没走过一遍，
        // isFinished() 自然是 false，此时清标记会导致每次重扫都重新播报一遍"施工完毕"。
        // 只在真的放下方块时才清（见 tickBuild）。
        //
        // 施工期间定期重扫：session 的游标只往前走，已经放好的方块要是被人拆了，
        // 光靠顺序推进是发现不了的，得从头再扫一遍才能补上。
        // 这里以前每 RESCAN_INTERVAL tick 就重扫一次（重建会话、从头比对世界）。
        // 那个做法有两个毛病：**她放几块就被打断一次重头比对**，而且每几秒就要
        // 重新判定一遍"哪些放不下"——玩家看到的就是"隔一会儿又检测一次、又念一遍"。
        // 改成：**一趟从头放到尾**，放完了由 BuildSession 自己收尾（isFinished），
        // 缺料当场停在那一块上、放不下的进 deferred 到最后一起交代。
        // 工地中途被人拆了几块怎么办：重新定位或改朝向会清掉完工标记，她会从头再走一遍。
        if (session == null) {
            setWorkingHomeMode(maid, false);
            notify(level, maid, "message.blueprint.maid_no_schematic");
            return;
        }

        // 进度推给附近的玩家（客户端画进度条）。特意放在冷却判断**之前**：
        // 她在等冷却、在走路、去取料的路上，进度条都该照常显示——
        // 玩家想知道的是"建到哪了"，不是"她这一刻有没有在放方块"
        tickProgress(level, maid);

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (state == State.FETCH) {
            tickFetch(level, maid);
        } else if (state == State.MOVE_TO_SPOT) {
            tickMoveToSpot(level, maid);
        } else {
            tickBuild(level, maid, stack);
        }
    }

    /** 开工前那份作息坐标的备份：收工时原样还回去 */
    private static final String SCHEDULE_BACKUP_TAG = "BlueprintScheduleBackup";

    /**
     * 施工期间把她的**工作区钉到蓝图坐标上**，收工再原样还回去。
     * <p>
     * 车万女仆的待命模式是拿**作息坐标**当"家"的（{@link SchedulePos} 里的工作/待机/睡觉三个点，
     * 女仆本身并没有单独的"家"坐标）。所以：
     * <ul>
     *   <li>待命开着 → {@code MaidFollowOwnerTask} 不会把她拽回主人身边（她要"在家"）；</li>
     *   <li>再把工作区和待机点设成**蓝图锚点** → 她这个"家"正好就是工地。
     *       原来那份坐标（主人家里、别的基地）跟她这会儿干的活没关系，
     *       "跑远取料却被传送回去"就是它造成的；</li>
     *   <li>收工/换工作时把备份的三个坐标和"已配置"标记**原样还原**，
     *       玩家自己设的作息一个字节都不改。</li>
     * </ul>
     */
    private void setWorkingHomeMode(EntityMaid maid, boolean working) {
        CompoundTag data = maid.getPersistentData();
        SchedulePos schedule = maid.getSchedulePos();
        if (working) {
            if (schedule == null) {
                return;
            }
            if (!data.contains(HOME_MODE_TAG)) {
                data.putBoolean(HOME_MODE_TAG, maid.isHomeModeEnable());
                data.put(SCHEDULE_BACKUP_TAG, backupSchedule(schedule));
                BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 开工：记下待命状态 = {}，"
                                + "作息坐标 工作={} 待机={}（维度 {}）",
                        maid.getUUID(), data.getBoolean(HOME_MODE_TAG),
                        schedule.getWorkPos(), schedule.getIdlePos(), schedule.getDimension());
            }
            if (activeAnchor != null) {
                // 钉到蓝图坐标：她要待的地方就是工地
                schedule.setWorkPos(activeAnchor);
                schedule.setIdlePos(activeAnchor);
                schedule.setDimension(maid.level().dimension().location());
                schedule.setConfigured(true);
            }
            if (!maid.isHomeModeEnable()) {
                maid.setHomeModeEnable(true);
            }
            return;
        }

        if (schedule != null && data.contains(SCHEDULE_BACKUP_TAG)) {
            restoreSchedule(schedule, data.getCompound(SCHEDULE_BACKUP_TAG));
            data.remove(SCHEDULE_BACKUP_TAG);
        }
        if (data.contains(HOME_MODE_TAG)) {
            boolean original = data.getBoolean(HOME_MODE_TAG);
            data.remove(HOME_MODE_TAG);
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 收工：待命状态还原为 {}，作息坐标已还原",
                    maid.getUUID(), original);
            maid.setHomeModeEnable(original);
        }
    }

    /** 备份她那份作息坐标，收工时原样还回去 */
    private static CompoundTag backupSchedule(SchedulePos schedule) {
        CompoundTag backup = new CompoundTag();
        backup.put("work", NbtUtils.writeBlockPos(schedule.getWorkPos()));
        backup.put("idle", NbtUtils.writeBlockPos(schedule.getIdlePos()));
        backup.put("sleep", NbtUtils.writeBlockPos(schedule.getSleepPos()));
        ResourceLocation dimension = schedule.getDimension();
        backup.putString("dim", dimension == null ? "" : dimension.toString());
        backup.putBoolean("configured", schedule.isConfigured());
        return backup;
    }

    private static void restoreSchedule(SchedulePos schedule, CompoundTag backup) {
        schedule.setWorkPos(NbtUtils.readBlockPos(backup.getCompound("work")));
        schedule.setIdlePos(NbtUtils.readBlockPos(backup.getCompound("idle")));
        schedule.setSleepPos(NbtUtils.readBlockPos(backup.getCompound("sleep")));
        ResourceLocation dimension = ResourceLocation.tryParse(backup.getString("dim"));
        if (dimension != null) {
            schedule.setDimension(dimension);
        }
        schedule.setConfigured(backup.getBoolean("configured"));
    }

    private void reset() {
        session = null;
        bill = Map.of();
        pendingBill = Map.of();
        shortfall = Map.of();
        lastShortfall.clear();
        lastShortfallAt = 0L;
        fetchProvider = null;
        returnProvider = null;
        returnFinished = false;
        returnTicks = 0;
        moveTarget = null;
        standSpot = null;
        spotSearchTicks = 0;
        state = State.MOVE_TO_SPOT;
        cooldown = 0;
        rescanTimer = RESCAN_INTERVAL;
        progressTimer = 0;
        toolWarned.clear();
    }

    /**
     * 女仆不再由本控制器驱动时调用（中途换工作、实体被移除等），
     * 把施工期间改过的东西一律还原。
     * <p>
     * 少了这一步，玩家给女仆换个任务，她的待命状态就被永久改掉了，
     * 而且看起来毫无缘由。
     */
    public void detach(EntityMaid maid) {
        setWorkingHomeMode(maid, false);
        reset();
    }

    private void onCompleted(ServerLevel level, EntityMaid maid, ItemStack stack) {
        // 有几块到头来还是放不下（缺支撑、位置被占）：**必须说一声**。
        // 不然报的是"建好啦"，而墙上可能少着几个火把、半砖——玩家得自己一块块对。
        // 这里 remaining() 只会是那种"放不下"的：缺料是不会走到 finished 的
        // （见 BuildSession.step：缺料当场停在那一块上，不会扫到队尾）
        int leftover = session == null ? 0 : session.remaining();
        // 完工标记写在蓝图上，所以"施工完毕"只会播报一次
        if (!MaidBlueprint.isCompleted(level, stack)) {
            MaidBlueprint.setCompleted(level, stack, true);
            if (leftover > 0) {
                BlueprintMod.LOGGER.info("女仆 {} 完工，但有 {} 块放不下（缺支撑或位置被占）",
                        maid.getUUID(), leftover);
                notify(level, maid, "message.blueprint.maid_build_leftover", leftover);
                // 紧跟一句"挡路的是什么、在哪"。这里不走 notify：那条路上的 30 秒冷却
                // 会把第二句直接吞掉，而这两句本来是一起说的
                sayTo(level, maid, "message.blueprint.maid_blocked_list", describeBlocked(maid));
            } else {
                notify(level, maid, "message.blueprint.maid_build_done");
            }
        }
        // 最后推一次进度。之后靠客户端的时效自己消失——不需要再补一条"结束了"
        sendProgress(level, maid);
        // 建完就把蓝图收回背包。手腾出来之后，findBlueprint 自然会挑到
        // 背包里下一张还没建完的图，女仆接着干下一单，不需要玩家盯着换图。
        stashFinishedBlueprint(maid);
        // 建完就撒手，不再重复扫描工地。
        // 要重新施工的话，重新定位或改朝向会清掉完工标记，女仆就会重新开工。
    }

    /**
     * 把建完的蓝图从手上收回背包。
     * <p>
     * 手腾空之后，下一轮查找就会落到背包里那张还没建完的图上，
     * 女仆于是自己接着干下一单。
     */
    /**
     * 把施工进度推给附近的玩家（客户端拿去画进度条）。
     * <p>
     * 每 {@value #PROGRESS_INTERVAL} tick 推一次：进度条半秒跳一下已经够顺滑，
     * 每 tick 一条包在多人服上纯属白烧带宽。收工那一次由 {@link #onCompleted} 单独发。
     */
    private void tickProgress(ServerLevel level, EntityMaid maid) {
        if (session == null) {
            return;
        }
        if (progressTimer > 0) {
            progressTimer--;
            return;
        }
        progressTimer = PROGRESS_INTERVAL;
        sendProgress(level, maid);
    }

    /**
     * 只发给她**附近**的玩家。
     * <p>
     * 进度条本来就只在 16 格内显示（见 {@code BuildProgressHud}），报到半个维度之外是浪费；
     * 推送半径留得比显示距离大一圈，玩家走近时条已经在了，不会"走到跟前才突然蹦出来"。
     */
    private void sendProgress(ServerLevel level, EntityMaid maid) {
        if (session == null) {
            return;
        }
        int done = session.done();
        int total = session.total();

        // 她此刻在干什么。这个字段是给进度条上那行字用的：
        // 玩家最需要的不是"建到几成"，而是"她这会儿是在取料、在走路，还是在发呆"
        byte phase;
        if (state == State.FETCH) {
            phase = S2CBuildProgressPacket.PHASE_FETCH;
        } else if (state == State.MOVE_TO_SPOT) {
            phase = S2CBuildProgressPacket.PHASE_WALK;
        } else if (!shortfall.isEmpty() && fetchProvider == null) {
            // 缺料、又没处可去（没找到来源）：这就是"发呆"的实情
            phase = S2CBuildProgressPacket.PHASE_STUCK;
        } else {
            phase = S2CBuildProgressPacket.PHASE_BUILD;
        }
        double radius = BlueprintConfig.progressRadius() + PROGRESS_RADIUS_MARGIN;
        double radiusSqr = radius * radius;
        double x = maid.getX();
        double y = maid.getY();
        double z = maid.getZ();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) > radiusSqr) {
                continue;
            }
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CBuildProgressPacket(maid.getId(), done, total, phase));
        }
    }

    private void stashFinishedBlueprint(EntityMaid maid) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            return;
        }

        InteractionHand hand;
        if (MaidBlueprint.isBlueprint(maid.getMainHandItem())) {
            hand = InteractionHand.MAIN_HAND;
        } else if (MaidBlueprint.isBlueprint(maid.getOffhandItem())) {
            hand = InteractionHand.OFF_HAND;
        } else {
            // 已经在背包里了，不用动
            return;
        }

        ItemStack blueprint = maid.getItemInHand(hand);
        // 先模拟一次插入：背包塞不下就让它继续留在手上，绝不能把蓝图弄丢
        ItemStack probe = ItemHandlerHelper.insertItemStacked(backpack, blueprint.copy(), true);
        if (!probe.isEmpty()) {
            return;
        }

        ItemStack toStore = blueprint.copy();
        maid.setItemInHand(hand, ItemStack.EMPTY);
        ItemHandlerHelper.insertItemStacked(backpack, toStore, false);
    }

    /**
     * 从头重建施工进度。
     * <p>
     * 因为建造是幂等的（已经和目标状态一致的方块会被跳过），
     * 重建后会很快掠过已建好的部分，落到真正缺块的地方。
     * 只换 session，不动站位和其他状态，免得女仆来回跑。
     */
    private void rescan(ServerLevel level) {
        if (activeId == null) {
            return;
        }
        Schematic base = SchematicStorage.get(level).get(activeId);
        if (base == null) {
            return;
        }
        Schematic schematic = base.rotate(activeRotation);
        // 带上工地现状：新会话的游标直接推到"第一块还没到位"的地方，
        // 进度条不会因为这次重扫掉回 0（见 BuildSession#fastForward）
        session = new BuildSession(schematic, level, activeAnchor);
        bill = BuildSession.bill(schematic);
    }

    // ------------------------------------------------------------------
    // 站位
    // ------------------------------------------------------------------

    private void tickMoveToSpot(ServerLevel level, EntityMaid maid) {
        if (standSpot == null) {
            state = State.BUILD;
            return;
        }

        double x = standSpot.getX() + 0.5D;
        double y = standSpot.getY();
        double z = standSpot.getZ() + 0.5D;

        double dx = maid.getX() - x;
        double dz = maid.getZ() - z;
        if (dx * dx + dz * dz <= ARRIVE_DISTANCE_SQR && Math.abs(maid.getY() - y) <= ARRIVE_DY) {
            spotSearchTicks = 0;
            state = State.BUILD;
            return;
        }

        // 找了太久还没到岗：多半是这个站位寻路到不了（取料回来时尤其常见——
        // 她停下的地方离站位就差那么两三格，寻路却再不肯往前）。
        // 施工本来就没有距离限制，站着不动照样能建，
        // 所以干脆放弃站位就地开工，好过在这儿一圈圈地转
        if (++spotSearchTicks > MOVE_PATIENCE_TICKS) {
            BlueprintMod.LOGGER.info("女仆 {} 找了 {} tick 还没走到站位 {}，就地开工",
                    maid.getUUID(), spotSearchTicks, standSpot);
            spotSearchTicks = 0;
            // 置空很关键：tickBuild 里还有一条"离站位 8 格以上就回岗位"，
            // 不清掉的话她刚开工又会被踢回来，还是互踢
            standSpot = null;
            state = State.BUILD;
            return;
        }

        if (!moveTowards(level, maid, x, y, z)) {
            // 到不了站位也无所谓，站着不动照样能建
            spotSearchTicks = 0;
            state = State.BUILD;
        }
    }

    /**
     * 在结构最外围一圈之外一点找个落脚点。
     * <p>
     * 要求很简单：脚能着地就行（脚下实心、身体没被埋）。
     * 站在外圈之外，建造时才不会被自己正在放的方块埋住。
     */
    private BlockPos resolveStandSpot(ServerLevel level, BlockPos anchor, Vec3i size) {
        int minX = anchor.getX() - STAND_MARGIN;
        int maxX = anchor.getX() + size.getX() - 1 + STAND_MARGIN;
        int minZ = anchor.getZ() - STAND_MARGIN;
        int maxZ = anchor.getZ() + size.getZ() - 1 + STAND_MARGIN;

        for (BlockPos column : outerRing(minX, maxX, minZ, maxZ)) {
            BlockPos spot = findGroundSpot(level, column, anchor.getY());
            if (spot != null) {
                return spot;
            }
        }
        // 外圈全是悬空的（比如把建筑挂在半空），退回中心
        return new BlockPos(anchor.getX() + size.getX() / 2, anchor.getY(), anchor.getZ() + size.getZ() / 2);
    }

    /** 外圈上的柱子，y 先统一填 0，具体高度后面再找 */
    private List<BlockPos> outerRing(int minX, int maxX, int minZ, int maxZ) {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            ring.add(new BlockPos(x, 0, minZ));
            ring.add(new BlockPos(x, 0, maxZ));
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            ring.add(new BlockPos(minX, 0, z));
            ring.add(new BlockPos(maxX, 0, z));
        }
        return ring;
    }

    /** 在这根柱子上找脚能着地的高度，优先和结构底部齐平 */
    @Nullable
    private BlockPos findGroundSpot(ServerLevel level, BlockPos column, int baseY) {
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos candidate = new BlockPos(column.getX(), baseY + dy, column.getZ());
            if (canStandAt(level, candidate)) {
                return candidate;
            }
        }
        for (int dy = -1; dy >= -4; dy--) {
            BlockPos candidate = new BlockPos(column.getX(), baseY + dy, column.getZ());
            if (canStandAt(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        // 脚着地，且身体这一格没被埋
        return level.getBlockState(pos.below()).canOcclude()
                && !level.getBlockState(pos).canOcclude();
    }

    // ------------------------------------------------------------------
    // 施工
    // ------------------------------------------------------------------

    private void tickBuild(ServerLevel level, EntityMaid maid, ItemStack stack) {
        // 被别的 AI 拽离岗位了，先回去
        if (standSpot != null
                && maid.distanceToSqr(standSpot.getX() + 0.5D, standSpot.getY(), standSpot.getZ() + 0.5D)
                > LEAVE_SPOT_DISTANCE_SQR) {
            state = State.MOVE_TO_SPOT;
            return;
        }

        BlockPos target = session.peekNextTarget(level, activeAnchor);

        // 面朝正在建的位置，看起来像盯着活儿在干
        if (target != null) {
            maid.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        }

        // 回收可以在配置里关掉：那时传 null，被顶掉的方块照老样子直接消失
        BuildSession.StepResult result = session.step(level, activeAnchor, new MaidItemSource(maid), 1,
                BlueprintConfig.salvageEnabled() ? new MaidSalvage(maid) : null);

        if (result.placed() > 0) {
            // 真的动工了，说明这处工地还没完工
            if (MaidBlueprint.isCompleted(level, stack)) {
                MaidBlueprint.setCompleted(level, stack, false);
            }
            // 挥手 + 这个方块的放置音效
            maid.swing(InteractionHand.MAIN_HAND);
            playPlaceSound(level, result.lastPlaced());
            cooldown = PLACE_COOLDOWN;
            return;
        }
        if (result.finished()) {
            return;
        }

        // 一块都没放下去，基本就是没材料了。出发前重算一遍"还差多少"——
        // 已经建好的部分不该再要，否则每缺一次料都会把整座建筑的量重搬一遍
        pendingBill = session.remainingBill(level, activeAnchor);
        if (pendingBill.isEmpty()) {
            // 什么也不缺却一块也放不下，多半是别的原因卡住了（比如支撑还没就位）。
            // 这种时候去取料只会白跑一趟
            cooldown = NO_SOURCE_COOLDOWN;
            return;
        }

        // 再扣掉背包里已经有的。这一步不能省：决定"值不值得跑一趟"的是还差多少，
        // 不是清单上有多少——背包里躺着的那部分不该再算进去，
        // 否则容器里只有这些已有的东西时，女仆会白跑一趟，还报告说背包满了
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        shortfall = backpack == null
                ? pendingBill
                : ItemProvider.missingAmounts(backpack, pendingBill);
        if (shortfall.isEmpty()) {
            // 眼下什么都不缺了。**这里不再销账**：
            // 销了的话，她取到一点料、清单短暂变空、再缺同一批，就会**再说一遍**——
            // 玩家看到的就是同一句"背包不下还缺的材料"反复刷屏。
            // 账留到**换工地**时才清（见 refreshSession），也就是"一处工地同一批缺料只说一次"
            cooldown = NO_SOURCE_COOLDOWN;
            return;
        }

        ItemProvider provider = findProvider(level, maid);
        if (provider == null) {
            cooldown = NO_SOURCE_COOLDOWN;
            notifyMissingMaterials(level, maid);
            return;
        }
        fetchProvider = provider;
        state = State.FETCH;
        BlueprintMod.LOGGER.info("女仆 {} 出发去 {} 取料（来源：{}，缺口 {} 种）",
                maid.getUUID(), provider.interactPos(), provider.getClass().getSimpleName(),
                pendingBill.size());
    }

    // ------------------------------------------------------------------
    // 被顶掉的方块上拆下来的东西
    // ------------------------------------------------------------------

    /**
     * 拆下来的东西去哪：**身上的无线终端 → 她自己的背包 → 脚边地上**。
     * <p>
     * 顺序是有讲究的：回收物**先回仓库**（终端等于仓库）。背包很小，
     * 先塞背包的话几趟就满了，而背包一满**取料也进不来**——
     * 就是"缺料 → 背包放不下 → 取不到料"那个死循环。
     * 终端收不下（没链接网络、网络满、物品被禁入）才落到背包；
     * 两边都不收，就丢在她脚边：满地是东西总比凭空消失强。
     * <p>
     * 每次施工现造一个这种薄对象（控制器是每 tick 拿到女仆的，它得记住是哪一只），
     * 代价可以忽略。
     */
    private final class MaidSalvage implements Salvage {

        private final EntityMaid maid;

        private MaidSalvage(EntityMaid maid) {
            this.maid = maid;
        }

        @Override
        public void collect(ServerLevel level, BlockPos pos, ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            // 1）**先给终端**（等于直接放回仓库）。
            //    背包很小，施工时拆下来的东西几趟就把它塞满；一塞满，
            //    取料也进不来——于是"缺料 → 背包放不下 → 取不到料"的死循环。
            //    回收物本来就该回仓库，没道理占着她的背包
            ItemStack rest = stack;
            ItemProvider terminal = findWirelessProvider(level, maid);
            if (terminal != null) {
                int accepted = terminal.deposit(rest);
                if (accepted > 0) {
                    rest = rest.copyWithCount(rest.getCount() - accepted);
                }
            } else {
                BlueprintMod.LOGGER.debug("女仆 {} 身上没有可用的无线终端，拆下来的东西先放背包", maid.getUUID());
            }
            if (rest.isEmpty()) {
                return;
            }

            // 2）终端收不下（没链接网络、网络满了、物品被禁入）才落到背包。
            //    insertItemStacked 会把塞不下的原样还回来，那部分正好是下一站要接着收的
            IItemHandler backpack = new MaidItemSource(maid).getBackpack();
            if (backpack != null) {
                rest = ItemHandlerHelper.insertItemStacked(backpack, rest, false);
            }
            if (rest.isEmpty()) {
                return;
            }

            // 3）两边都收不下：丢在地上。绝不"收下再扔掉"——那就是物品蒸发
            Block.popResource(level, pos, rest);
            BlueprintMod.LOGGER.info("女仆 {} 拆下来的 {} 背包和终端都收不下，丢在 {}",
                    maid.getUUID(), rest.getHoverName().getString(), pos);
        }

        @Override
        public void cannotHarvest(ServerLevel level, BlockPos pos, BlockState state) {
            reportUnremovable(level, pos, state, "need_tool", "message.blueprint.maid_need_tool");
        }

        @Override
        public void unbreakable(ServerLevel level, BlockPos pos, BlockState state) {
            reportUnremovable(level, pos, state, "unbreakable", "message.blueprint.maid_unbreakable");
        }

        /**
         * "这块我动不了，先留着"——两种情形共用这一套账，只是话说得不一样。
         * <p>
         * 同一**种**方块只说一次：重扫会让同一面墙被反复"顶掉再放"，
         * 每一下都报就是复读；换一种动不了的方块时，该说的还是要说。
         */
        private void reportUnremovable(ServerLevel level, BlockPos pos, BlockState state,
                                       String reason, String translationKey) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (id == null || !toolWarned.add(id)) {
                return;
            }
            // 附近没人就先不说，也**别记成已经说过**——否则玩家赶回来时反而听不到。
            // 这一条和 notifyMissingMaterials 是同一个道理
            if (level.getNearestPlayer(maid, 16.0D) == null) {
                toolWarned.remove(id);
                return;
            }
            BlueprintMod.LOGGER.warn("女仆 {} 动不了 {}（{}）：位置留在原地不动",
                    maid.getUUID(), id, reason);
            sayTo(level, maid, translationKey, state.getBlock().getName());
        }
    }

    private void tickFetch(ServerLevel level, EntityMaid maid) {
        if (fetchProvider == null) {
            state = State.MOVE_TO_SPOT;
            return;
        }

        // 不需要走动的来源（女仆身上带着的无线终端）就地取料。
        // 照着它的 interactPos 寻路会把她往地图另一头、甚至别的维度带
        if (!fetchProvider.requiresTravel()) {
            pullFromProvider(level, maid, fetchProvider);
            fetchProvider = null;
            state = State.MOVE_TO_SPOT;
            cooldown = FETCH_COOLDOWN;
            return;
        }

        BlockPos container = fetchProvider.interactPos();
        double cx = container.getX() + 0.5D;
        double cz = container.getZ() + 0.5D;

        // 到没到只看水平距离：容器常常比女仆高一格或低一格
        // （放在台子上、或者摆在地板下），把垂直差算进去，
        // "明明就站在箱子边上"会被判成还没走到
        double horizontalSqr = maid.distanceToSqr(cx, maid.getY() + 0.5D, cz);

        if (horizontalSqr > CONTAINER_DISTANCE_SQR) {
            if (moveTowardsContainer(level, maid, container)) {
                return;
            }
            // 导航走不过去。但取料靠的是直接访问容器的物品栏，
            // 并不真的要求女仆站到跟前，所以只要没跑偏太远，
            // 就就地把料取了，免得她在障碍物外面反复折返
            if (horizontalSqr > ABORT_DISTANCE_SQR) {
                BlueprintMod.LOGGER.warn("女仆 {} 够不到取料目标 {}，相距约 {} 格，放弃这一趟",
                        maid.getUUID(), container, (int) Math.sqrt(horizontalSqr));
                fetchProvider = null;
                state = State.MOVE_TO_SPOT;
                cooldown = FETCH_COOLDOWN;
                return;
            }
        }

        // 到地方了就把导航停掉，否则她还会继续往箱子里挤，看着像在箱子上蹦跶
        maid.getNavigation().stop();

        pullFromProvider(level, maid, fetchProvider);
        fetchProvider = null;
        // 取完料回站位接着干
        state = State.MOVE_TO_SPOT;
        cooldown = FETCH_COOLDOWN;
    }

    /**
     * 让女仆走向目标，返回 true 表示还在路上，false 表示走不过去。
     * <p>
     * 重新寻路做了节流：女仆的其他 AI（待命、跟随之类）会时不时把 navigation 清掉，
     * 一发现路径没了就立刻重算的话，她会不停地在原地重新起步，
     * 看起来就是在打转而不是在赶路。
     */
    private boolean moveTowards(ServerLevel level, EntityMaid maid, double x, double y, double z) {
        BlockPos target = BlockPos.containing(x, y, z);
        if (!target.equals(moveTarget)) {
            moveTarget = target;
            moveTicks = 0;
            repathCooldown = 0;
        }
        if (++moveTicks > MOVE_TIMEOUT) {
            moveTicks = 0;
            moveTarget = null;
            repathCooldown = 0;
            return false;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (maid.getNavigation().isDone() || maid.getNavigation().getPath() == null) {
            repathCooldown = REPATH_INTERVAL;
            maid.getNavigation().moveTo(x, y, z, 1.0D);
        }
        return true;
    }

    /**
     * 走向容器旁边的落脚点，而不是容器本身。
     * <p>
     * 导航目标要是直接设成容器方块，那一格正站着箱子，女仆根本迈不进去，
     * 于是她会贴着箱子来回蹭、甚至往上跳——看起来特别傻。
     * 瞄准旁边的空位，她才会规规矩矩走到旁边站好。
     *
     * @return true 表示还在路上，false 表示走不过去
     */
    private boolean moveTowardsContainer(ServerLevel level, EntityMaid maid, BlockPos container) {
        BlockPos stand = resolveStandNear(level, container);
        if (stand == null) {
            // 四周实在没有落脚的地方，只能退而求其次瞄准容器自己
            return moveTowards(level, maid,
                    container.getX() + 0.5D, container.getY() + 0.5D, container.getZ() + 0.5D);
        }
        // 落脚点的 y 用方块底部，和施工站位那边保持一致
        return moveTowards(level, maid, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
    }

    /**
     * 在容器四周找一个能站人的格子。
     * <p>
     * 只查水平四个方向，正上方和正下方都站不住人。
     * 先看同层，再看上一层——容器镶在台子里时，站到旁边地面上更自然。
     */
    @Nullable
    private BlockPos resolveStandNear(ServerLevel level, BlockPos container) {
        for (Direction direction : HORIZONTAL_SIDES) {
            BlockPos side = container.relative(direction);
            if (canStandAt(level, side)) {
                return side;
            }
            BlockPos above = side.above();
            if (canStandAt(level, above)) {
                return above;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 取料
    // ------------------------------------------------------------------

    /**
     * 找一个能取到料的来源。
     * <p>
     * 优先就近取材：身边半径内随便哪个容器有材料就够，省得女仆满基地跑。
     * 附近实在没有，才去翻绑定书——那是玩家特意指定的远程仓库，
     * 通常离得很远，所以只当兜底。
     */
    @Nullable
    private ItemProvider findProvider(ServerLevel level, EntityMaid maid) {
        ItemProvider nearby = findNearbyContainer(level, maid, shortfall);
        if (nearby != null) {
            BlueprintMod.LOGGER.info("女仆 {} 就近取材：{}", maid.getUUID(), nearby.interactPos());
            return nearby;
        }
        // 无线终端排在绑定书前面：它接的是整张网络，而且不用走动
        ItemProvider wireless = findWirelessProvider(level, maid);
        if (wireless != null) {
            BlueprintMod.LOGGER.info("女仆 {} 改用身上的无线女仆终端取料", maid.getUUID());
            return wireless;
        }
        ItemProvider bound = findBoundProvider(level, maid);
        if (bound != null) {
            BlueprintMod.LOGGER.info("女仆 {} 改用绑定书指定的目标：{}", maid.getUUID(), bound.interactPos());
        }
        return bound;
    }

    /**
     * 女仆身上带着的无线女仆终端所连的网络。
     * <p>
     * 认物品类型这件事交给 {@link Ae2Compat} 去做——它本身不引用 AE2 的类型，
     * 没装 AE2 时这里直接返回 null，不会把 AE2 的类拽进加载器。
     */
    @Nullable
    static ItemProvider findWirelessProvider(ServerLevel level, EntityMaid maid) {
        return Ae2Compat.createWirelessProvider(level, collectHeldStacks(maid), maid.position());
    }

    /**
     * 扫一圈身边的普通容器，返回最近的那个装着所需材料的。
     * <p>
     * 清单（{@code bill}）当参数传进来而不是读实例上的 {@code shortfall}：
     * 手搓那边也要找容器，但它的清单是"这一次合成要的材料"，跟施工那份完全是两回事。
     */
    @Nullable
    static ItemProvider findNearbyContainer(ServerLevel level, EntityMaid maid, Map<Item, Integer> bill) {
        BlockPos center = maid.blockPosition();
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - CONTAINER_SEARCH_HEIGHT);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + CONTAINER_SEARCH_HEIGHT);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CONTAINER_SEARCH_RADIUS, 0, -CONTAINER_SEARCH_RADIUS).atY(minY),
                center.offset(CONTAINER_SEARCH_RADIUS, 0, CONTAINER_SEARCH_RADIUS).atY(maxY))) {

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (handler == null || !hasWantedItem(handler, bill)) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best == null ? null : new BlockContainerProvider(level, best);
    }

    /**
     * 照绑定书上的地址去远程取料。
     */
    @Nullable
    private ItemProvider findBoundProvider(ServerLevel level, EntityMaid maid) {
        ItemStack book = findBindingBook(maid);
        if (book.isEmpty()) {
            return null;
        }

        ResourceLocation dimension = BindingBookItem.getBoundDimension(book);
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            // 隔着维度走不过去。多半是玩家忘了，提示一句总比杵着不动强
            notify(level, maid, "message.blueprint.maid_bound_other_dimension");
            return null;
        }

        ItemProvider provider = createBoundProvider(level, maid);
        if (provider == null) {
            return null;
        }
        if (provider.hasAny(shortfall)) {
            return provider;
        }

        // 坐标还在，但里面已经没有需要的材料了。
        // 走 sayTo 而不是 notify：缺料提示自己有闸门，不该再被 30 秒冷却挤掉
        if (shouldReportShortfall("bound_empty", shortfall)) {
            sayTo(level, maid, "message.blueprint.maid_bound_empty", describeShortfall());
        }
        return null;
    }

    /**
     * 在绑定书指的位置造一个来源。
     * <p>
     * 同一个坐标可能是普通箱子，也可能接着 ME 网络，所以两种都试：
     * 先问 AE2（没装 AE2 时会直接返回 null），不行再按普通容器处理。
     * <p>
     * 这里刻意不判断里面有没有料——取料和还料都要用这个坐标，
     * 区别只是调用方要不要再检查一次内容，所以"造"和"挑"分开。
     */
    @Nullable
    private ItemProvider createBoundProvider(ServerLevel level, EntityMaid maid) {
        ItemStack book = findBindingBook(maid);
        if (book.isEmpty()) {
            return null;
        }

        BlockPos pos = BindingBookItem.getBoundPos(book);
        if (pos == null) {
            return null;
        }

        ResourceLocation dimension = BindingBookItem.getBoundDimension(book);
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            return null;
        }

        ItemProvider ae2 = Ae2Compat.createProvider(level, pos);
        return ae2 != null ? ae2 : new BlockContainerProvider(level, pos);
    }

    static boolean hasWantedItem(IItemHandler handler, Map<Item, Integer> bill) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && bill.containsKey(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把材料从来源搬进女仆背包。
     * <p>
     * 具体怎么搬交给 {@link ItemProvider} 自己决定：普通容器是逐槽抽取，
     * ME 网络得"先模拟再提取"，两者差得很远，主流程不该掺和进去。
     */
    private void pullFromProvider(ServerLevel level, EntityMaid maid, ItemProvider provider) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            notify(level, maid, "message.blueprint.maid_no_backpack");
            return;
        }

        BlockPos pos = provider.interactPos();
        // 不用走动的来源没有"面前那个容器"，别去 0,0,0 找方块实体放动画
        if (provider.requiresTravel() && level.getBlockEntity(pos) != null) {
            // 开合动画走原版 blockEvent 通道，只有箱子这类容器会响应；
            // ME 网络的机器对 id=1 没反应，调用它也无害。
            setContainerOpen(level, pos, true);
            openContainerPos = pos;
            openContainerTimer = CONTAINER_ANIMATION_TICKS;
        }
        maid.swing(InteractionHand.MAIN_HAND);

        // 上限按背包实际空位来，不再写死一个种类数：
        // 背包越大（装了升级）越该一趟搬够，否则材料种类一多，
        // 每趟只能带回固定几样，剩下的永远凑不齐
        int moved = provider.transferInto(backpack, pendingBill, Math.max(1, countEmptySlots(backpack)));
        if (moved == 0) {
            // 空手而归有两种原因，得分清楚：背包塞不下，或者这容器里压根没有还缺的那几样。
            // 提示写错会让玩家顺着错的线索去翻背包，而问题其实在别处
            boolean hasWanted = provider.hasAny(shortfall);
            BlueprintMod.LOGGER.warn("女仆 {} 在 {} 没取到材料（{}还缺的 {} 种，背包空余 {} 格）",
                    maid.getUUID(), pos, hasWanted ? "容器里有" : "容器里没有",
                    shortfall.size(), countEmptySlots(backpack));
            String kind = hasWanted ? "backpack_full" : "source_empty";
            if (shouldReportShortfall(kind, shortfall)) {
                sayTo(level, maid, hasWanted
                        ? "message.blueprint.maid_backpack_full"
                        : "message.blueprint.maid_source_empty", describeShortfall());
            }
        } else {
            BlueprintMod.LOGGER.info("女仆 {} 从 {} 取到 {} 种材料", maid.getUUID(), pos, moved);
        }
    }

    /** 排查用：背包还剩几个空格，"取不到料"时一眼就能看出是不是塞满了 */
    private static int countEmptySlots(IItemHandler handler) {
        int empty = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    // ------------------------------------------------------------------
    // 完工后还料
    // ------------------------------------------------------------------

    /**
     * 把背包里剩下的建造材料还回容器。
     *
     * @return true 表示还没还完，下一 tick 接着来；false 表示不用还了
     */
    private boolean tickReturn(ServerLevel level, EntityMaid maid) {
        if (leftoverMaterials(maid).isEmpty()) {
            returnProvider = null;
            returnTicks = 0;
            returnFinished = true;
            return false;
        }

        // 兜底超时：还料要是卡住了（容器始终塞不下、或者来回走不到），
        // 也必须有个了结。收工那一步排在还料之后，一直还不上，
        // 女仆就会永远停在"施工中"，待命状态也还不回来
        if (++returnTicks > RETURN_TIMEOUT) {
            notify(level, maid, "message.blueprint.maid_return_failed");
            returnProvider = null;
            returnTicks = 0;
            returnFinished = true;
            return false;
        }

        if (returnProvider == null) {
            returnProvider = findReturnTarget(level, maid);
            if (returnProvider == null) {
                notify(level, maid, "message.blueprint.maid_return_no_target");
                returnFinished = true;
                return false;
            }
        }

        // 不需要走动的来源（女仆身上的无线终端）就地还料，理由同 tickFetch。
        // 容器坐标取她脚下的位置，只是为了让日志里那行字不至于是个 0,0,0
        BlockPos container = returnProvider.requiresTravel()
                ? returnProvider.interactPos()
                : maid.blockPosition();

        if (returnProvider.requiresTravel()) {
            double cx = container.getX() + 0.5D;
            double cz = container.getZ() + 0.5D;
            double horizontalSqr = maid.distanceToSqr(cx, maid.getY() + 0.5D, cz);

            if (horizontalSqr > CONTAINER_DISTANCE_SQR) {
                if (moveTowardsContainer(level, maid, container)) {
                    return true;
                }
                if (horizontalSqr > ABORT_DISTANCE_SQR) {
                    notify(level, maid, "message.blueprint.maid_return_unreachable");
                    returnProvider = null;
                    returnFinished = true;
                    return false;
                }
            }

            // 站定之后停掉导航，别让她继续往容器里钻
            maid.getNavigation().stop();
        }

        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            returnFinished = true;
            return false;
        }

        int moved = returnProvider.acceptInto(backpack, bill);
        BlueprintMod.LOGGER.info("女仆 {} 向 {} 归还了 {} 种剩余材料", maid.getUUID(), container, moved);
        maid.swing(InteractionHand.MAIN_HAND);
        returnProvider = null;

        if (moved == 0) {
            // 一种都塞不回去（多半是容器满了），再试只会原地打转
            notify(level, maid, "message.blueprint.maid_return_failed");
            returnFinished = true;
            return false;
        }
        // 可能还有剩的，下一 tick 再来一趟
        return true;
    }

    /** 背包里属于建造清单的那部分材料，女仆自己的杂物不算 */
    private Map<Item, Integer> leftoverMaterials(EntityMaid maid) {
        IItemHandler backpack = new MaidItemSource(maid).getBackpack();
        if (backpack == null) {
            return Map.of();
        }
        Map<Item, Integer> leftover = new HashMap<>();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (!stack.isEmpty() && bill.containsKey(stack.getItem())) {
                leftover.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return leftover;
    }

    /**
     * 挑一个还料的地方：优先绑定书指定的容器——那本来就是玩家安排的仓库，
     * 退而求其次才用身边的箱子。
     */
    @Nullable
    private ItemProvider findReturnTarget(ServerLevel level, EntityMaid maid) {
        // 身上带着无线终端就先还回网络：不用走动，也就不会出现"附近找不到能收的容器"。
        // 她本来就是从这儿取的料，还回来天经地义
        ItemProvider wireless = findWirelessProvider(level, maid);
        if (wireless != null) {
            BlueprintMod.LOGGER.info("女仆 {} 把剩余材料还回身上的无线终端所连的网络", maid.getUUID());
            return wireless;
        }
        ItemProvider bound = createBoundProvider(level, maid);
        return bound != null ? bound : findNearbyAcceptor(level, maid);
    }

    /** 身边最近的一个标准容器，不管里面装的是什么 */
    @Nullable
    private ItemProvider findNearbyAcceptor(ServerLevel level, EntityMaid maid) {
        BlockPos center = maid.blockPosition();
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - CONTAINER_SEARCH_HEIGHT);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + CONTAINER_SEARCH_HEIGHT);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CONTAINER_SEARCH_RADIUS, 0, -CONTAINER_SEARCH_RADIUS).atY(minY),
                center.offset(CONTAINER_SEARCH_RADIUS, 0, CONTAINER_SEARCH_RADIUS).atY(maxY))) {

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null
                    || !blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) {
                continue;
            }
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best == null ? null : new BlockContainerProvider(level, best);
    }

    // ------------------------------------------------------------------
    // 动画与音效
    // ------------------------------------------------------------------

    /**
     * 让容器播放开合动画。
     * <p>
     * 走原版的 blockEvent 通道：箱子、陷阱箱、末影箱、桶这类容器
     * 的方块实体都会响应 id=1 的事件来开合盖子，而且事件会自动同步给客户端。
     */
    private void setContainerOpen(ServerLevel level, BlockPos pos, boolean open) {
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, open ? 1 : 0);
    }

    private void tickOpenContainer(ServerLevel level) {
        if (openContainerTimer > 0 && --openContainerTimer <= 0 && openContainerPos != null) {
            setContainerOpen(level, openContainerPos, false);
            openContainerPos = null;
        }
    }

    /** 播放方块放置音效，音量压低一点，免得连续施工时太吵 */
    private void playPlaceSound(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null) {
            return;
        }
        SoundType soundType = level.getBlockState(pos).getSoundType();
        level.playSound(null, pos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                soundType.getVolume() * 0.6F, soundType.getPitch());
    }

    // ------------------------------------------------------------------
    // 状态维护
    // ------------------------------------------------------------------

    private void refreshSession(ServerLevel level, EntityMaid maid, ItemStack stack) {
        UUID id = MaidBlueprint.id(stack);
        BlockPos anchor = MaidBlueprint.anchor(stack);
        Rotation rotation = MaidBlueprint.rotation(stack);

        if (id == null || anchor == null) {
            return;
        }
        if (session != null && Objects.equals(id, activeId)
                && Objects.equals(anchor, activeAnchor)
                && rotation == activeRotation) {
            return;
        }

        // 我们的蓝图从蓝图库取；机械动力那张是现翻的（翻好按内容缓存，不是每 tick 重来）
        Schematic base = MaidBlueprint.schematic(level, stack);
        if (base == null) {
            return;
        }

        // 女仆按蓝图当前朝向施工
        Schematic schematic = base.rotate(rotation);

        session = new BuildSession(schematic, level, anchor);
        bill = BuildSession.bill(schematic);
        activeId = id;
        activeAnchor = anchor;
        activeRotation = rotation;
        // **换了工地**才重新记账：上处说过"这块我拆不动"，不代表这处也免开尊口。
        // 同一处工地反复重建会话（重扫、进度重建）**不算换工地**——
        // 以前这里无条件清账，于是每隔几秒的重扫都把"说过了"重置一遍，
        // 同一块基岩就被她一遍遍地念叨
        if (!Objects.equals(id, activeId)
                || !Objects.equals(anchor, activeAnchor)
                || rotation != activeRotation) {
            toolWarned.clear();
            // 缺料那几句也一起销账：换了工地、换了图，该说的重新说
            lastShortfall.clear();
            lastShortfallAt = 0L;
        }
        standSpot = resolveStandSpot(level, anchor, schematic.getSize());
        spotSearchTicks = 0; // 换了工地，找站位的耐心重新算
        fetchProvider = null;
        returnProvider = null;
        returnFinished = false;
        moveTarget = null;
        cooldown = 0;
        state = State.MOVE_TO_SPOT;
    }

    /**
     * 女仆身上的蓝图：先主手，再副手，最后翻一遍背包。
     * <p>
     * 优先挑**还没建完**的那张。多张蓝图同时带在身上时（建完的会被自动收进背包），
     * 要是还抱着已完工的图不放，女仆就会对着它一直发呆，不会去干下一张。
     * 万一身上全是完工的，就随便返回一张，好让上层把"施工完毕"播报出去。
     */
    private static ItemStack findBlueprint(EntityMaid maid) {
        // 完工标记（机械动力那张记在存档里）要问世界，所以这里得有 ServerLevel
        ServerLevel level = maid.level() instanceof ServerLevel server ? server : null;
        ItemStack finished = null;

        ItemStack mainHand = maid.getMainHandItem();
        if (MaidBlueprint.usable(mainHand)) {
            if (level == null || !MaidBlueprint.isCompleted(level, mainHand)) {
                return mainHand;
            }
            finished = mainHand;
        }

        ItemStack offHand = maid.getOffhandItem();
        if (MaidBlueprint.usable(offHand)) {
            if (level == null || !MaidBlueprint.isCompleted(level, offHand)) {
                return offHand;
            }
            if (finished == null) {
                finished = offHand;
            }
        }

        IItemHandler backpack = maid.getMaidInv();
        if (backpack != null) {
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (!MaidBlueprint.usable(stack)) {
                    continue;
                }
                if (level == null || !MaidBlueprint.isCompleted(level, stack)) {
                    return stack;
                }
                if (finished == null) {
                    finished = stack;
                }
            }
        }
        return finished == null ? ItemStack.EMPTY : finished;
    }

    /**
     * 找女仆身上的绑定书。
     * <p>
     * 顺序是主手、副手、饰品栏、背包。饰品栏排在背包前面，
     * 因为那才是这本书该待的地方，塞进背包只是权宜之计。
     */
    static ItemStack findBindingBook(EntityMaid maid) {
        for (ItemStack stack : collectHeldStacks(maid)) {
            if (stack.getItem() instanceof BindingBookItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 女仆身上所有可能装着东西的地方，按"更该待的地方排前面"的顺序：
     * 主手、副手、饰品栏、背包。
     * <p>
     * 绑定书和无线终端都按这个顺序找。饰品栏排在背包前面，因为那才是
     * 这两样东西该待的地方，塞进背包只是权宜之计。
     */
    private static List<ItemStack> collectHeldStacks(EntityMaid maid) {
        List<ItemStack> stacks = new ArrayList<>(8);
        stacks.add(maid.getMainHandItem());
        stacks.add(maid.getOffhandItem());

        // 饰品栏本身就是个 ItemStackHandler，可以直接按槽位遍历
        BaubleItemHandler baubles = maid.getMaidBauble();
        if (baubles != null) {
            for (int i = 0; i < baubles.getSlots(); i++) {
                stacks.add(baubles.getStackInSlot(i));
            }
        }

        IItemHandler backpack = maid.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (backpack != null) {
            for (int i = 0; i < backpack.getSlots(); i++) {
                stacks.add(backpack.getStackInSlot(i));
            }
        }
        return stacks;
    }

    private void notify(ServerLevel level, EntityMaid maid, String translationKey, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastMessageAt < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessageAt = now;

        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby != null) {
            nearby.sendSystemMessage(MaidSpeech.speak(maid, Component.translatable(translationKey, args)));
        }
    }

    /**
     * 找不到材料时，把缺哪些、各缺多少讲清楚。
     * <p>
     * 光说一句"找不到材料"，玩家还得自己拿着蓝图去对账。物品名用
     * {@link Component} 传而不是先转成字符串——那样会在服务端就固定成某种语言，
     * 客户端换成别的语言也翻不动了。
     */
    private void notifyMissingMaterials(ServerLevel level, EntityMaid maid) {
        // 附近没人就先不说，也别记成"已播报"——否则玩家赶回来时反而听不到
        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby == null) {
            return;
        }

        // 同一批缺料只说一次（闸门见 shouldReportShortfall）；也不走 notify——
        // 那条路径的 30 秒冷却会把这条重要提示挤掉
        if (!shouldReportShortfall("no_source", shortfall)) {
            return;
        }

        if (shortfall.isEmpty()) {
            sayTo(level, maid, "message.blueprint.maid_no_material");
            return;
        }

        BlueprintMod.LOGGER.info("女仆 {} 缺少建造材料：{}", maid.getUUID(), shortfall);
        sayTo(level, maid, "message.blueprint.maid_missing_materials", describeShortfall());
    }

    /**
     * 缺料这一类提示的总闸门：**同一种说法、同一批缺料，只说一次**。
     * <p>
     * 两条判据：
     * <ol>
     *   <li><b>内容指纹按说法分开记</b>。这是关键——四种说法共用一格的话，
     *       它们会互相覆盖对方的指纹，"没变"的东西每轮都变成"新内容"，
     *       于是无限复读（见 {@link #lastShortfall} 的注释）。</li>
     *   <li><b>两次开口之间留 {@value #SHORTFALL_QUIET_MS} 毫秒</b>。同一次尝试里
     *       可能有好几条路都想说话（绑定书仓库空了 + 到处都找不到），
     *       留个间隔就只会放第一条出去，不会一口气刷两行。</li>
     * </ol>
     * 指纹用**物品 id** 拼，不用显示名：显示名会跟着语言变，切成另一种语言就成"新内容"了。
     * <p>
     * 被间隔挡下的这条**不记账**：记了就等于把这件事永久封口，
     * 等安静下来它反而再也没机会开口。
     */
    private boolean shouldReportShortfall(String kind, Map<Item, Integer> list) {
        String signature = shortfallSignature(list);
        if (signature.equals(lastShortfall.get(kind))) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastShortfallAt < SHORTFALL_QUIET_MS) {
            return false;
        }
        lastShortfall.put(kind, signature);
        lastShortfallAt = now;
        // 留一行日志：以后再说"她怎么老是重复同一句"，一眼就能看出是哪条路、什么内容
        BlueprintMod.LOGGER.info("缺料提示（{}）：{}", kind, signature);
        return true;
    }

    /**
     * 缺料清单的内容指纹：**只看缺哪几样，不看各缺多少**，跟语言无关；排过序，同一批料每次都拼成一样。
     * <p>
     * 数量**必须**排除掉：她一边施工一边取料，每样缺的数量几乎每一趟都在变
     * （红砖块 3712 → 3700 → …），把数量算进指纹，同一批缺料每一趟都成了"新内容"，
     * 于是同一句话反复刷屏——"只提示一遍"就是这么被破坏的。
     * 换了缺的**种类**才算新情况，那时候本来就该再说一次。
     */
    private static String shortfallSignature(Map<Item, Integer> list) {
        List<String> parts = new ArrayList<>(list.size());
        for (Item item : list.keySet()) {
            parts.add(String.valueOf(ForgeRegistries.ITEMS.getKey(item)));
        }
        parts.sort(null);
        return String.join(";", parts);
    }

    /**
     * 直接说给附近的人听，**不走 {@link #notify} 的冷却**。
     * <p>
     * 缺料提示自己有一套"内容不变就不重复"的闸门，再叠一层 30 秒冷却只会把重要提示挤掉：
     * 同一次里前一句别的提示刚说完，这句就发不出去了。
     */
    private void sayTo(ServerLevel level, EntityMaid maid, String translationKey, Object... args) {
        Player nearby = level.getNearestPlayer(maid, 16.0D);
        if (nearby == null) {
            return;
        }
        nearby.sendSystemMessage(MaidSpeech.speak(maid,
                Component.translatable(translationKey, args)));
    }

    /**
     * 把"还缺哪些材料、各缺多少"拼成一行字（{@code shortfall} 为空时返回空组件）。
     * <p>
     * <b>凡是"缺料"的提示都要带上它</b>：光说一句"这里没有她缺的材料"，
     * 玩家只能拿着蓝图自己对账——而真正会卡住的往往是**一样意想不到的东西**
     * （AE2 线缆方块要的是贴在面上的部件，不是那个方块本身；一个位置可能要两样）。
     * 不把名字说出来，玩家根本不知道去哪儿找。
     * <p>
     * 名字用 {@link Component} 传而不是先转成字符串——那样会在服务端就固定成某种语言。
     */
    private MutableComponent describeShortfall() {
        MutableComponent list = Component.empty();
        int shown = 0;
        for (Map.Entry<Item, Integer> entry : shortfall.entrySet()) {
            if (shown++ >= MAX_REPORTED_MATERIALS) {
                break;
            }
            if (shown > 1) {
                list.append(", ");
            }
            list.append(entry.getKey().getDescription())
                    .append(" x")
                    .append(String.valueOf(entry.getValue()));
        }
        if (shortfall.size() > MAX_REPORTED_MATERIALS) {
            list.append(Component.translatable("message.blueprint.maid_missing_more",
                    shortfall.size() - MAX_REPORTED_MATERIALS));
        }
        return list;
    }

    /**
     * "还有哪几块放不下、被什么占着、在哪"。
     * <p>
     * 光说一句"还有 3 块放不下"，玩家得自己满工地找；把**方块名和坐标**说出来，
     * 就能直接过去处理（挖掉、补支撑）。位置上是空气的说明是**缺支撑**，
     * 不是被占——那两种情况修法完全不同，所以分开说。
     */
    private Component describeBlocked(EntityMaid maid) {
        List<Schematic.BlockEntry> leftovers = session == null ? List.of() : session.leftovers();
        MutableComponent list = Component.empty();
        int shown = 0;
        for (Schematic.BlockEntry entry : leftovers) {
            if (shown >= MAX_BLOCKED_REPORTED) {
                break;
            }
            BlockPos world = activeAnchor.offset(entry.pos());
            BlockState here = maid.level().getBlockState(world);
            if (shown > 0) {
                list.append(Component.literal("、"));
            }
            list.append(here.isAir()
                    ? Component.translatable("message.blueprint.blocked_no_support")
                    : here.getBlock().getName());
            list.append(Component.literal(" (" + world.getX() + ", " + world.getY() + ", " + world.getZ() + ")"));
            shown++;
        }
        if (leftovers.size() > shown) {
            // 只说前几个，剩下的报个数——坐标一串太长，聊天框会刷满
            list.append(Component.literal("、" + (leftovers.size() - shown) + "…"));
        }
        return list;
    }

    /** 报坐标时最多列几处（其余只报个数） */
    private static final int MAX_BLOCKED_REPORTED = 3;
}
