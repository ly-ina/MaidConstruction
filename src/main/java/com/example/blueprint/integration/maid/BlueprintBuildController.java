package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintConfig;
import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockContainerProvider;
import com.example.blueprint.build.BuildSession;
import com.example.blueprint.build.StandSpotSearch;
import com.example.blueprint.build.ItemProvider;
import com.example.blueprint.build.Salvage;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.integration.ae2.TerminalChunkLoader;
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

    /**
     * 放置速度的**基准**（块/秒）。
     * <p>
     * 5 块/秒就是原先那个节奏（`PLACE_COOLDOWN = 4`，4 tick 一块）。
     * 在此之上**好感度每多 {@value #FAVORABILITY_PER_EXTRA_PLACE} 点，每秒多放 1 块**，
     * 见 {@link #placesPerSecond}。
     */
    private static final int BASE_PLACES_PER_SECOND = 5;
    /**
     * 每多少点好感度换来"每秒多放一块"。
     * <p>
     * 5 点是"给个念想"的节奏：好感度几十点的女仆能明显感觉到她更快了，
     * 又不至于一进游戏就贴着上限。
     */
    private static final int FAVORABILITY_PER_EXTRA_PLACE = 5;
    /**
     * 速度上限（块/秒）。防呆：好感度上千的存档不至于一 tick 刷出几百块把服务器噎住。
     * <p>
     * 20 正好是"一 tick 一块"；再往上就得一 tick 放多块，属于另一个量级的改动。
     */
    private static final int MAX_PLACES_PER_SECOND = 20;
    private static final double TICKS_PER_SECOND = 20.0D;
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
    /**
     * 走向站位时，**连着多少 tick 没靠近**才算走不过去（见 {@link #tickMoveToSpot}）。
     * <p>
     * 注意是"没靠近"而不是"花了多久"：从结构里往外走可能要绕一大圈
     * （穿过大厅、绕过外墙），死按时间的话她走到一半就被判"到不了"、就地开工——
     * 那正是"怎么也不肯离开、卡在原地建"的由来。3 秒足够分清"卡住了"和"在绕路"。
     */
    private static final int MOVE_PATIENCE_TICKS = 60;
    /** 让开时从她脚下往外找多少格、上下找几层：见 {@link StandSpotSearch} */
    /** 一次"让开"没走成之后，隔多久再请一次（她站在结构里这件事不会自己好，得反复请） */
    private static final int STEP_ASIDE_RETRY = 200;
    /**
     * 她被请出去却始终走不到结构外时，最多**换几个落脚点**再试（见 {@link #retryEscape}）。
     * <p>
     * 为什么不是"一次不成就就地开工"：她到不了那个点，常常是因为那一侧正对着墙、
     * 或者那格落脚点被机器占着——换一个方向往往就出去了。试满这么多次还不行，
     * 才认"她确实出不去"（外圈悬空、四面围死），那就只能就地开工（她那几格由 step 跳过）。
     */
    private static final int MAX_ESCAPE_TRIES = 3;
    /**
     * **开工前先让位的宽限期**（tick）。
     * <p>
     * 她人在投影里的时候，先专心往外走这么久，**一块都不放**；2 秒够她迈出墙外了。
     * 走不出去（四面围死、外圈悬空）才会带着一行日志就地开工。
     * <p>
     * 为什么要有这么一段"什么都不干"的时间，而不是边走边放：边走边放的话，
     * 她每放一块就被自己脚下那格绊一下（那几格被跳过），看起来就是
     * "她自己把自己当障碍物、又不动"——先把位置站对，再动工，干净。
     */
    private static final int STEP_OUT_GRACE_TICKS = 40;
    /**
     * "她在干什么"要连着报这么多**包**才改口（见 {@link #publishPhase}）。
     * <p>
     * 注意单位是包不是 tick：这个方法是在推送那一步（半秒一包）里调用的。
     * 一开始写成"20 tick"就变成了 20 包 = **十秒**才肯改口——
     * 开场那包要是"前往站位"，接下来十秒都在说她"前往站位"，哪怕她正在放方块。
     * 2 包 = 一秒。
     */
    private static final int PHASE_SETTLE_PACKETS = 2;
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
    /**
     * 「这一句说过了」的账：**按女仆本人（UUID）存，而且放在静态表里**。
     * <p>
     * 以前它挂在控制器实例上，而控制器在 {@code reset}/{@code detach} 之后会被丢掉重建，
     * 账本跟着一起没——同一批缺料于是每一轮又被当成"新情况"，玩家看到的就是无限复读。
     * 放到静态表里，控制器怎么换都不丢；换了缺的**种类**才会再说（指纹只看种类，不看数量）。
     */
    private static final Map<UUID, Map<String, String>> SAID_SHORTFALL = new HashMap<>();
    /** 每只女仆上一次开口的时间（3 秒节流用），同样不随控制器重建而丢 */
    private static final Map<UUID, Long> SAID_SHORTFALL_AT = new HashMap<>();
    /** 缺料提示之间的最小间隔（同一次尝试里好几条路都想说话时，只放第一条出去） */
    private static final long SHORTFALL_QUIET_MS = 3_000L;
    /** "找不到取料来源"那行诊断日志的最小间隔，见 {@code logNoProvider} */
    private static final long NO_PROVIDER_LOG_COOLDOWN_MS = 30_000L;
    /** 每只女仆上次写那行日志的时间 */
    private static final Map<UUID, Long> NO_PROVIDER_LOGGED_AT = new HashMap<>();
    /**
     * "让她出去"这条路每一句话的节流间隔。
     * <p>
     * 这些话说的是**同一件事**（她该出去、外面那格在哪儿），她走不到的时候每 tick
     * 都可能重来一遍，不节流就是刷屏；但**一句都不说也不行**——
     * 上一次的毛病正是"没有日志"，于是"她为什么不动"只能靠猜。
     */
    private static final long SPOT_LOG_COOLDOWN_MS = 5_000L;
    /** 每只女仆上次写"让位"相关日志的时间 */
    private static final Map<UUID, Long> SPOT_LOGGED_AT = new HashMap<>();
    /** 这一 tick 在驱动哪只女仆：账本按她存，见 {@link #SAID_SHORTFALL} */
    private UUID currentMaidId;
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
    /** 这一趟去站位时"离它最近到过多少"（平方距离）：不再变小就说明走不动了 */
    private double bestSpotDistance = Double.MAX_VALUE;
    /**
     * 距离下一次"请她让开"还要等多少 tick（见 {@link #stepAside}）。
     * <p>
     * 不搞"只让一次"了：她站在结构里这件事不会自己好，走不到就过一会儿再请一次。
     * 走得到的话第一次就出去了，这个冷却根本用不上。
     */
    private int stepAsideCooldown;
    /** 这一趟"请她出去"已经换过几个落脚点了（见 {@link #retryEscape}） */
    private int escapeTries;
    /** 她连着多少 tick 站在投影里（见 {@link #STEP_OUT_GRACE_TICKS}）；站出去就归零 */
    private int insideTicks;
    /** 宽限期过后硬挪过她没有（一个工地只硬挪一次，见 {@link #moveMaidTo}） */
    private boolean insideForced;
    /** 这一趟"就地开工"的说明写过没有（一个工地只写一次） */
    private boolean insideGaveUp;
    /**
     * 这一处工地的身份（同一张图 + 同锚点 + 朝向）。
     * <p>
     * 手工地重扫、会话重建都不会改它，**换工地才改**。客户端拿它判断
     * "这是同一处工地还是新开的一处"——同一处工地的进度只往前不往回，
     * 条才不会来回跳（见 {@code MaidBuildProgress}）。
     */
    private long siteId;
    /** 上一次真的报出去的"她在干什么"（迟滞用，见 {@link #publishPhase}） */
    private byte publishedPhase = -1;
    /** 正在酝酿的新状态，以及它已经连着多少包了 */
    private byte pendingPhase = -1;
    private int pendingPhasePackets;
    /**
     * 距上一包这段时间里，她**真的放下了方块**吗。
     * <p>
     * "她在干什么"按这个算，而不是按当前的 {@link #state}：状态是每 tick 判定的，
     * "去取料 → 回站位 → 放两块"几 tick 就能来回一趟，抓瞬时状态上报就会出现
     * "明明在放方块，条上却写着前往站位"。
     */
    private boolean placedSincePacket;
    /** 距上一包这段时间里她动过去取料 */
    private boolean fetchedSincePacket;
    /**
     * 这个控制器一共重建过几次施工会话。
     * <p>
     * 纯粹为了诊断：它一路涨就说明"工地身份"在反复变（换了工地、或者判定不稳），
     * 而每次重建都会把状态设回 MOVE_TO_SPOT——那正是进度条上字在跳、进度不动的样子。
     */
    private int sessionBuilds;
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
    /** 攒着"这一 tick 该放几块"的份额（见 {@link #takePlaceBudget}）：不足一块就留到下一 tick */
    private double placeBudget;
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
        // 账本按她本人记账（控制器会被丢掉重建，见 SAID_SHORTFALL）
        currentMaidId = maid.getUUID();

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

        // 把她终端链着的那个无线访问点所在区块带起来。
        // 不带起来的话，基地那一片没加载时网络解析不到，取料取不到、回收也塞不回去，
        // 看起来就像终端坏了。**加载拿不到也照常施工**（施工优先），只是退到背包/地上
        TerminalChunkLoader.hold(level, maid.getUUID(), collectHeldStacks(maid));

        // 每 30 秒（600 tick）把"她现在到底卡在哪一步"写一行日志。
        // 大结构上出问题时（站着不动、来回跑），光看现象猜不出来是哪个环节——
        // 这一行能直接看出是状态没切、还是进度不动、还是站位到不了、还是在等取料。
        // 末尾的"会话重建"是给"进度条上的字一直在跳"那类现象用的：
        // 它一直涨就说明工地身份在反复变，状态被一次次设回 MOVE_TO_SPOT
        if (++debugTicks >= 600) {
            debugTicks = 0;
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 状态={} 进度 {}/{} 锚点={} 图={} 站位={} 下一块={} 取料={} 冷却={} 站位计时={} 会话重建={}",
                    maid.getUUID(), state, session.done(), session.total(), activeAnchor,
                    activeId == null ? "无" : activeId.toString().substring(0, 8),
                    standSpot,
                    session.peekNextTarget(level, activeAnchor),
                    fetchProvider == null ? "无" : fetchProvider.getClass().getSimpleName(),
                    cooldown, spotSearchTicks, sessionBuilds);
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
        tickProgress(level, maid, stack);

        // **人在结构里，就先出去站好，再动手**（先让位、后开工）。
        // 在里头建，放的都是自己身边那几块：轻则被方块顶来顶去，重则闷在墙里出不来。
        // 判据是"在不在结构的水平范围里"，**不是**"她脚下那格要不要放方块"——
        // 结构内部的大厅是空气、一块都不挡，可她照样是在投影里施工。
        // <p>
        // 宽限期（{@link #STEP_OUT_GRACE_TICKS}）里**一块都不放**：先专心往外走。
        // 以前是"边走边放"，于是她每放一块都被自己脚下那格绊一下（那几格要跳过），
        // 看着就是"她把自己当障碍物、还不动"。站出去之后才开始放；实在站不出去
        // （四面围死、外圈悬空）才带着一行日志就地开工。
        // <p>
        // **只在她真的要动手（BUILD）这一刻岔她**：取料那一步不能被打断。
        // 尤其是无线终端那种不用走动的取料，它是"tickBuild 里把状态置成 FETCH、
        // 下一 tick 由 tickFetch 一进函数就取完"——中间只隔一 tick。
        // 这里要是连 FETCH 也拦（之前就是这么写的），那一下就永远轮不到：
        // 表现就是"她不去终端拿材料了"。
        if (state == State.BUILD && insideFootprint(maid)) {
            insideTicks++;
            if (insideTicks <= STEP_OUT_GRACE_TICKS) {
                // 宽限期内：专心往外走。这一 tick 就直接驱动她走，不设状态、不等下一 tick
                if (ensureStandSpot(level, maid)) {
                    state = State.MOVE_TO_SPOT;
                    tickMoveToSpot(level, maid);
                    return;
                }
            } else if (!insideForced && standSpot != null) {
                // 给了 2 秒她还是没出去：**直接把她落到那个落脚点上**，然后再开工。
                // 落脚点是现找的、结构外的、站得住的那一格，所以这一下是安全的
                insideForced = true;
                moveMaidTo(maid, standSpot);
                return;
            } else if (!insideGaveUp) {
                insideGaveUp = true;
                BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 在投影里待满 {} tick 还没站出去，就地开工"
                                + "（她自己占着的格子会被跳过）",
                        maid.getUUID(), insideTicks);
            }
        } else {
            insideTicks = 0;
            insideForced = false;
            insideGaveUp = false;
        }

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
                // **"家"要钉在她该站的那一格上，不是钉在锚点上**。
                //
                // 锚点是结构的**最小角**，按定义就落在结构的水平范围里。而待命的作息 AI
                // 是拿这个坐标当"家"的：钉在锚点上，就等于**一次次把她带回投影里面**——
                // 我们刚把她请出去，她转头又被带回来，玩家看到的就是
                // "怎么也不肯走到结构外开工"（1.6.6 之前就是这个毛病）。
                //
                // 站位（standSpot）本身永远在结构外（见 escapeSpot / nearestStandSpot），
                // 所以有站位就拿它当"家"；没站位（没人给她指定位置、她就地开工）时退回锚点。
                BlockPos home = standSpot != null ? standSpot : activeAnchor;
                schedule.setWorkPos(home);
                schedule.setIdlePos(home);
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
        // 她不再干这活了：把自己持的访问点区块票还回去（只还她自己的，不动别人的）
        if (maid.level() instanceof ServerLevel serverLevel) {
            TerminalChunkLoader.release(serverLevel, maid.getUUID());
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
        // 这里**不再清缺料的账**：reset 在"她这一 tick 没图/没定位/没结构"时都会走，
        // 清了的话，她在取料循环里每绕一圈都会把"说过了"重新变成"新情况"，就是无限复读的由来。
        // 账本按女仆存在静态表里（SAID_SHORTFALL），要再说只有两种可能：换了缺的种类，或者她说的是别的说法
        fetchProvider = null;
        returnProvider = null;
        returnFinished = false;
        returnTicks = 0;
        moveTarget = null;
        standSpot = null;
        spotSearchTicks = 0;
        bestSpotDistance = Double.MAX_VALUE;
        stepAsideCooldown = 0;
        escapeTries = 0;
        insideTicks = 0;
        insideForced = false;
        insideGaveUp = false;
        publishedPhase = -1;
        pendingPhase = -1;
        pendingPhasePackets = 0;
        placeBudget = 0;
        placedSincePacket = false;
        fetchedSincePacket = false;
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
        sendProgress(level, maid, stack);
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
    private void tickProgress(ServerLevel level, EntityMaid maid, ItemStack stack) {
        if (session == null) {
            return;
        }
        if (progressTimer > 0) {
            progressTimer--;
            return;
        }
        progressTimer = PROGRESS_INTERVAL;
        sendProgress(level, maid, stack);
    }

    /**
     * 只发给她**附近**的玩家。
     * <p>
     * 进度条本来就只在 16 格内显示（见 {@code BuildProgressHud}），报到半个维度之外是浪费；
     * 推送半径留得比显示距离大一圈，玩家走近时条已经在了，不会"走到跟前才突然蹦出来"。
     */
    private void sendProgress(ServerLevel level, EntityMaid maid, ItemStack stack) {
        if (session == null) {
            return;
        }
        int done = session.done();
        int total = session.total();

        // 她此刻在干什么。这个字段是给进度条上那行字用的：
        // 玩家最需要的不是"建到几成"，而是"她这会儿是在取料、在走路，还是在发呆"。
        // **按"这一段时间她干了什么"算，不是按当前 state**：这半秒里真的放下了方块，
        // 那就是在建——别让条上那行字去说她"前往站位"（她确实可能正一边走一边放）
        byte raw;
        if (placedSincePacket) {
            raw = S2CBuildProgressPacket.PHASE_BUILD;
        } else if (!shortfall.isEmpty() && fetchProvider == null) {
            // 缺料、又没处可去（没找到来源）：这就是"发呆"的实情
            raw = S2CBuildProgressPacket.PHASE_STUCK;
        } else if (fetchedSincePacket || state == State.FETCH) {
            raw = S2CBuildProgressPacket.PHASE_FETCH;
        } else if (state == State.MOVE_TO_SPOT) {
            raw = S2CBuildProgressPacket.PHASE_WALK;
        } else {
            raw = S2CBuildProgressPacket.PHASE_BUILD;
        }
        placedSincePacket = false;
        fetchedSincePacket = false;
        byte phase = publishPhase(raw);
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
                    new S2CBuildProgressPacket(maid.getId(), maid.getUUID(), siteId,
                            buildingName(stack), done, total, phase));
        }
    }

    /**
     * 她的放置速度（块/秒）：**基准 + 好感度每点 1 块**，封顶 {@link #MAX_PLACES_PER_SECOND}。
     */
    private static int placesPerSecond(EntityMaid maid) {
        int favorability = Math.max(0, maid.getFavorability());
        return Math.min(MAX_PLACES_PER_SECOND,
                BASE_PLACES_PER_SECOND + favorability / FAVORABILITY_PER_EXTRA_PLACE);
    }

    /**
     * 这一 tick 允许放几块：把"每秒 N 块"折成每 tick 的份额攒起来，攒够一块才放。
     * <p>
     * 为什么不用"冷却几 tick"那种写法：冷却只能是整数 tick，速度就被锁死在
     * 20/1、20/2、20/3……（20 块/秒、10、6.7）这几个档上，"好感度每点加一块"根本落不下去。
     * 攒份额的话 7 块/秒、13 块/秒都是准的。
     */
    private int takePlaceBudget(EntityMaid maid) {
        placeBudget += placesPerSecond(maid) / TICKS_PER_SECOND;
        int allowed = (int) placeBudget;
        if (allowed > 0) {
            placeBudget -= allowed;
        }
        return allowed;
    }

    /**
     * 建筑名：我们自己的蓝图用玩家起的名字（蓝图面板里那个"名称"）；
     * 机械动力那张、或者还没起名的，给空串——由客户端翻成"未命名建筑"，
     * 因为服务端翻的话，玩家切成英文看到的还是中文。
     */
    private static String buildingName(ItemStack stack) {
        return stack.getItem() instanceof BlueprintItem ? BlueprintItem.getBlueprintName(stack) : "";
    }

    /**
     * "她此刻在干什么"的迟滞：只有连续 {@value #PHASE_SETTLE_TICKS} tick 都是新状态才改口。
     * <p>
     * 状态机是每 tick 判定的："走去取料 → 回站位 → 放两块"几 tick 就能来回一趟。
     * 把瞬时状态直接推出去，条上那行字就在几种说法之间乱跳，
     * 看着就像上面有两条记录在抢——而它其实一直是同一个人。
     */
    private byte publishPhase(byte raw) {
        if (publishedPhase < 0) {
            publishedPhase = raw; // 第一次开口：有什么说什么
            pendingPhase = raw;
            return publishedPhase;
        }
        if (raw == publishedPhase) {
            pendingPhase = raw;
            pendingPhasePackets = 0;
            return publishedPhase;
        }
        if (raw != pendingPhase) {
            pendingPhase = raw;
            pendingPhasePackets = 1;
            return publishedPhase;
        }
        if (++pendingPhasePackets >= PHASE_SETTLE_PACKETS) {
            publishedPhase = raw;
            pendingPhasePackets = 0;
        }
        return publishedPhase;
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
        double distance = dx * dx + dz * dz;
        if (distance <= ARRIVE_DISTANCE_SQR && Math.abs(maid.getY() - y) <= ARRIVE_DY) {
            spotSearchTicks = 0;
            bestSpotDistance = Double.MAX_VALUE;
            escapeTries = 0; // 走到位了：这一趟"请她出去"算成了，下次从头数
            state = State.BUILD;
            return;
        }

        // 耐心按**有没有在靠近**算，不按"找了多久"算：大结构里她要绕一大圈才走得出去，
        // 死按时间判的话，她走到一半就被判"到不了"、就地开工——那正是
        // "怎么也不肯离开建造范围、卡在原地建"的由来。只要还在靠近就继续走
        if (distance < bestSpotDistance - 0.25D) {
            bestSpotDistance = distance;
            spotSearchTicks = 0;
        } else if (++spotSearchTicks > MOVE_PATIENCE_TICKS) {
            // 连着这么久都没再靠近过：多半是寻路到不了
            // （她停下的地方离站位就差那么两三格，寻路却再不肯往前）。
            // 但**她还站在结构里**的话，"就地开工"正是玩家看到的
            // "她不肯走到结构外就开工"——所以先换个落脚点再请一次（见 retryEscape），
            // 换到头了才认命
            spotSearchTicks = 0;
            bestSpotDistance = Double.MAX_VALUE;
            if (retryEscape(level, maid)) {
                return;
            }
            BlueprintMod.LOGGER.info("女仆 {} 连着 {} tick 没靠近站位 {}，就地开工",
                    maid.getUUID(), MOVE_PATIENCE_TICKS, standSpot);
            giveUpSpot(maid);
            return;
        }

        // 她还在投影里就是"让位"那一趟：重发路径不受节流限制（见 moveTowards 的 urgent）
        if (!moveTowards(level, maid, x, y, z, insideFootprint(maid))) {
            // 走不过去：**连站位一起放弃**，站着不动照样能建。
            // 先试"换个落脚点"（她还在结构里的话，见 retryEscape）；
            // 真放弃了，standSpot 必须置空，理由和上面那条出口一样（那里写着"置空很关键"）：
            // tickBuild 开头有一条"离站位 8 格以上就回岗位"，留着它的话她刚进 BUILD
            // 就被踢回 MOVE_TO_SPOT，两处每 tick 互踢、谁也走不掉
            spotSearchTicks = 0;
            bestSpotDistance = Double.MAX_VALUE;
            if (retryEscape(level, maid)) {
                return;
            }
            giveUpSpot(maid);
        }
    }

    /**
     * 放弃站位：就地开工。
     * <p>
     * 她在结构里的话多说一句日志——"她怎么就是不肯出去"这件事，光看屏幕分不出来
     * 是"没找过落脚点"还是"找了三个都走不到"，这行能直接定死。
     */
    private void giveUpSpot(EntityMaid maid) {
        if (insideFootprint(maid)) {
            BlueprintMod.LOGGER.info("女仆 {} 换了 {} 个落脚点都走不出结构，就地开工（她自己那几格会被跳过）",
                    maid.getUUID(), escapeTries);
            escapeTries = 0;
        }
        standSpot = null;
        state = State.BUILD;
    }

    /**
     * 她正站在"马上要放方块"的那几格上：**先让她让开**，别把自己砌进墙里。
     * <p>
     * 让到结构外圈去（外圈在结构外两格，站上去压不着任何一块）。
     * 找不到落脚点（外圈悬空、站不住人）就作罢——被挡下的那几格仍在重试队列里，
     * 等她去取料、被别的事挪开，自然就放上了。
     *
     * @return true 表示已经安排她往站位走了，这一 tick 就别再干别的
     */
    private boolean stepAside(ServerLevel level, EntityMaid maid) {
        if (stepAsideCooldown > 0) {
            stepAsideCooldown--;
            return false;
        }
        // 先找"离她最近的结构**外**落脚点"（通常迈几步就出去了），
        // 找不到再退到结构外圈上最近的那个
        BlockPos spot = escapeSpot(level, maid);
        if (spot == null) {
            spot = nearestStandSpot(level, maid);
        }
        if (spot == null) {
            stepAsideCooldown = STEP_ASIDE_RETRY;
            return false;
        }
        if (spotLogAllowed(maid)) {
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 站在投影里（她自己 {}），先让开去 {}",
                    maid.getUUID(), maid.blockPosition(), spot);
        }
        standSpot = spot;
        spotSearchTicks = 0;
        bestSpotDistance = Double.MAX_VALUE; // 换了个目标：耐心从"离它最近过多少"重新算
        stepAsideCooldown = STEP_ASIDE_RETRY; // 万一走不到，过一会儿再请一次
        state = State.MOVE_TO_SPOT;
        return true;
    }

    /**
     * 到不了站位时的补救：**换一个落脚点再请她出去**。
     * <p>
     * 不让"走不到"直接变成"就地开工"：她到不了，往往是因为那一侧正对着墙、
     * 或者那格落脚点被机器占着——换一格就出去了。而"她站在投影里一块块往外码"
     * 是玩家看得见的现象，所以要换到头才认。
     *
     * @return true 表示已经换了新目标，这一 tick 接着走
     */
    private boolean retryEscape(ServerLevel level, EntityMaid maid) {
        if (!insideFootprint(maid) || escapeTries >= MAX_ESCAPE_TRIES) {
            return false;
        }
        escapeTries++;
        stepAsideCooldown = 0; // 这一趟本来就是"走不到"，别让冷却挡住补救
        return stepAside(level, maid);
    }

    /**
     * 保证她手上有"一个结构外的站位"：没有就现找一个。
     * <p>
     * 和 {@link #stepAside} 的分工：这里**不看冷却**（宽限期里她每一 tick 都该在往外走），
     * 而且**找不到落脚点时会说话**。上一次的毛病正是"没有日志"——
     * 这条路上每个岔口都得说，不然"她为什么不动"又只能靠猜。
     *
     * @return true 表示她有一个结构外的站位可走
     */
    private boolean ensureStandSpot(ServerLevel level, EntityMaid maid) {
        if (standSpot != null) {
            return true;
        }
        BlockPos spot = escapeSpot(level, maid);
        if (spot == null) {
            spot = nearestStandSpot(level, maid);
        }
        if (spot == null) {
            logSpotProblem(level, maid);
            return false;
        }
        standSpot = spot;
        spotSearchTicks = 0;
        bestSpotDistance = Double.MAX_VALUE;
        escapeTries = 0;
        if (spotLogAllowed(maid)) {
            BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 站在投影里（她自己 {}），先出去站到 {}",
                    maid.getUUID(), maid.blockPosition(), spot);
        }
        return true;
    }

    /**
     * 她在投影里、又找不到结构外的落脚点：**必须说话**。
     * <p>
     * 这一行是"没有日志、她也不动"唯一的抓手：把她的位置、锚点、结构尺寸，
     * 以及"她自己脚下那格到底站不站得住"全写出来——一眼能分清是判据不对，
     * 还是外面真没地方站（悬空、被机器填满）。
     */
    private void logSpotProblem(ServerLevel level, EntityMaid maid) {
        if (!spotLogAllowed(maid)) {
            return;
        }
        BlockPos feet = maid.blockPosition();
        BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 在投影里（她自己 {}，锚点 {}，尺寸 {}），"
                        + "但结构外没找到落脚点（她脚下那格站得住={}）",
                maid.getUUID(), feet, activeAnchor,
                session == null ? "无" : session.size(), canStandAt(level, feet));
    }

    /**
     * 直接把她挪到那个落脚点上。
     * <p>
     * 为什么动这个手：宽限期给了她 2 秒，寻路还是没把她带出去（大结构里绕不出去、
     * 被墙围死、或者 TLM 自己的 AI 把我们的路径冲掉）。她自己挡着的格子会被跳过，
     * 于是"她站在投影里不放、墙里空一块"——比"把她挪出去两三格"难看得多。
     * 所以这里**宁可硬挪**：挪的目标是她自己找到的那个结构外落脚点，一定站得住人。
     * <p>
     * 一个工地只硬挪一次（{@link #insideForced}）：挪完她还不领情（又被 AI 拽回去），
     * 那就带着日志就地开工，不再反复折腾她。
     */
    private void moveMaidTo(EntityMaid maid, BlockPos spot) {
        maid.getNavigation().stop();
        moveTarget = null;
        BlueprintMod.LOGGER.info("[蓝图施工] 女仆 {} 在投影里待满 {} tick 还没走出去，直接把她挪到 {}",
                maid.getUUID(), insideTicks, spot);
        maid.teleportTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
    }

    /** "让位"这几句日志的节流：同一只女仆 {@link #SPOT_LOG_COOLDOWN_MS} 毫秒最多一行 */
    private static boolean spotLogAllowed(EntityMaid maid) {
        long now = System.currentTimeMillis();
        Long last = SPOT_LOGGED_AT.get(maid.getUUID());
        if (last != null && now - last < SPOT_LOG_COOLDOWN_MS) {
            return false;
        }
        SPOT_LOGGED_AT.put(maid.getUUID(), now);
        return true;
    }

    /**
     * 离她最近的一个"压不着蓝图"的落脚点（让开用）。
     * <p>
     * 和 {@link #nearestStandSpot}（结构外圈上离她最近的那个）的分工：
     * 这里是**从她脚下往外一圈圈找**，判据只有一条——**站那儿不会占住蓝图要放的格子**
     * （脚、头顶两格都算，见 {@link #blocksPlanned}）。
     * <p>
     * 特意**不拿"在不在结构的 X/Z 大框里"当判据**：结构内部的空档（大厅、走道、天井）
     * 本来就站得，用大框去框会把它们全判成"在结构里"，于是明明迈两步就有地方站，
     * 却算成"找不到落脚点"，只能让她原地开工。外圈那个（可能在对角几十格开外）只当兜底。
     */
    @Nullable
    private BlockPos escapeSpot(ServerLevel level, EntityMaid maid) {
        if (session == null || activeAnchor == null) {
            return null;
        }
        // **顺序**（从近到远、同圈先看同层）交给 StandSpotSearch，那里是纯逻辑、有单测；
        // 这里只回答"这一格行不行"：不在结构的水平范围里（站那儿不会接着在投影里建），而且站得住
        return StandSpotSearch.firstAcceptable(
                (int) Math.floor(maid.getX()),
                (int) Math.floor(maid.getY()),
                (int) Math.floor(maid.getZ()),
                // 排除**刚才到不了的那一格**：走不到再试一次，要是还指同一格，
                // 那就只是把同样的失败重演一遍（换落脚点的意义就在这儿，见 retryEscape）
                candidate -> !candidate.equals(standSpot)
                        && !insideFootprint(candidate) && canStandAt(level, candidate));
    }

    /**
     * 结构外圈上**离她最近**的那个落脚点。
     * <p>
     * 让开时用这个，而不是 {@link #resolveStandSpot}（它取外圈的第一个格子）：
     * 大结构上"第一个格子"可能在对角几十格开外，而躲开自己脚下这一格，
     * 本来就只需要挪出去两三步。
     */
    @Nullable
    private BlockPos nearestStandSpot(ServerLevel level, EntityMaid maid) {
        if (session == null || activeAnchor == null) {
            return null;
        }
        int minX = activeAnchor.getX() - STAND_MARGIN;
        int maxX = activeAnchor.getX() + session.size().getX() - 1 + STAND_MARGIN;
        int minZ = activeAnchor.getZ() - STAND_MARGIN;
        int maxZ = activeAnchor.getZ() + session.size().getZ() - 1 + STAND_MARGIN;

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos column : outerRing(minX, maxX, minZ, maxZ)) {
            // 以**她自己那一层**为基准找地面，不用锚点的高度：锚点是结构的最低角，
            // 结构有地下室、台基、护坡时它跟地面的高度能差十几格，那这根柱子上就"没有地面"，
            // 整圈都被判成没地方站——她于是永远待在投影里（"没有日志、她也不动"）
            BlockPos spot = findGroundSpot(level, column, (int) Math.floor(maid.getY()));
            if (spot == null) {
                continue;
            }
            double distance = maid.distanceToSqr(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = spot;
            }
        }
        return best;
    }

    /**
     * 这一格在不在结构的**水平范围**里。
     * <p>
     * 判"要不要请她出去"就认它。**不是**"这一格要不要放方块"——
     * 结构内部的空气（大厅、房间）当然不需要放方块，可她站在那儿同样是在投影里施工：
     * 放的都是自己身边那几块，轻则被方块顶来顶去，重则闷在墙里出不来。
     * 这两件事我上一版混为一谈了，于是"站在大厅里"被当成"没挡路"，她自然就不肯挪窝。
     */
    private boolean insideFootprint(BlockPos pos) {
        if (session == null || activeAnchor == null) {
            return false;
        }
        Vec3i size = session.size();
        return pos.getX() >= activeAnchor.getX() && pos.getX() < activeAnchor.getX() + size.getX()
                && pos.getZ() >= activeAnchor.getZ() && pos.getZ() < activeAnchor.getZ() + size.getZ();
    }

    /** 她**此刻**在不在结构的水平范围里 */
    private boolean insideFootprint(EntityMaid maid) {
        return insideFootprint(new BlockPos((int) Math.floor(maid.getX()), 0,
                (int) Math.floor(maid.getZ())));
    }

    /** 同上，但按传进来的锚点和尺寸算（换工地那一步要在 activeAnchor 更新前用它） */
    private static boolean insideFootprint(EntityMaid maid, BlockPos anchor, Vec3i size) {
        int x = (int) Math.floor(maid.getX());
        int z = (int) Math.floor(maid.getZ());
        return x >= anchor.getX() && x < anchor.getX() + size.getX()
                && z >= anchor.getZ() && z < anchor.getZ() + size.getZ();
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

        // 回收可以在配置里关掉：那时传 null，被顶掉的方块照老样子直接消失。
        // 还要把她自己的碰撞箱一起传进去：落在她身上的格子一个都不放，
        // 否则就是把女仆砌进自己正在建的墙里（见 BuildSession#step）
        // 这一 tick 允许放几块（按好感度折算，见 takePlaceBudget）。份额还没攒够就先歇一 tick
        int allowed = takePlaceBudget(maid);
        if (allowed <= 0) {
            cooldown = 1;
            return;
        }
        BuildSession.StepResult result = session.step(level, activeAnchor, new MaidItemSource(maid), allowed,
                BlueprintConfig.salvageEnabled() ? new MaidSalvage(maid) : null,
                maid.getBoundingBox());

        // 自己挡着要放的格子：先出结构。主判据在 tick 里那道
        // "人在结构范围里就先出去"，这里保一道——防这一趟刚好走到她那两格而主判据那会儿没触发。
        // 注意顺序：它在"没材料 → 去取料"之前，所以只影响"放"这件事，不挡取料
        if (result.selfBlocked() > 0 && stepAside(level, maid)) {
            return;
        }

        if (result.placed() > 0) {
            placedSincePacket = true;
            // 真的动工了，说明这处工地还没完工
            if (MaidBlueprint.isCompleted(level, stack)) {
                MaidBlueprint.setCompleted(level, stack, false);
            }
            // 挥手 + 这个方块的放置音效
            maid.swing(InteractionHand.MAIN_HAND);
            playPlaceSound(level, result.lastPlaced());
            // 节奏交给份额了（见 takePlaceBudget）：下一 tick 再看攒够没有
            cooldown = 1;
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
            // 带着终端却接不上网络：这句话比"我找不到建造要用的材料"准得多——
            // 材料就在网络里，玩家该去看的是无线访问点，而不是去翻箱子
            if (Ae2Compat.carriesTerminal(collectHeldStacks(maid))
                    && findWirelessProvider(level, maid) == null) {
                notifyTerminalOffline(level, maid);
            } else {
                notifyMissingMaterials(level, maid);
            }
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
        fetchedSincePacket = true; // 这一段时间她在忙取料：条上那行字照这个来说
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
        return moveTowards(level, maid, x, y, z, false);
    }

    /**
     * @param urgent 这一趟是"请她让位"（她还站在投影里）：**路径一没就立刻重发**，不看节流。
     *   <p>
     *   常规节流是 {@link #REPATH_INTERVAL}（20 tick）一次，为的是别和各路 AI 拉扯得原地打转；
     *   可让位只有 {@link #STEP_OUT_GRACE_TICKS}（40 tick）的预算——按常规节流，
     *   这 2 秒里最多发得出去两次，TLM 的 AI 一清路径她就一次都没起步，
     *   最后只能靠硬挪（玩家看到的就是"她直接被挪过去了"）。这一段密着重发。
     */
    private boolean moveTowards(ServerLevel level, EntityMaid maid, double x, double y, double z, boolean urgent) {
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
        if (repathCooldown > 0 && !urgent) {
            repathCooldown--;
        } else if (maid.getNavigation().isDone() || maid.getNavigation().getPath() == null) {
            // urgent 时只是**不受节流限制**：仍然只在"手上没路径"时才真去算一条，
            // 免得每 tick 都跑一遍 A*（那才是"原地打转"和卡顿的来源）
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
            return bound;
        }
        logNoProvider(maid);
        return null;
    }

    /**
     * 三条取料来源都没找到时，把"为什么"写进日志。
     * <p>
     * 玩家只会听到一句"我找不到建造要用的材料"，而这句话背后至少有五六种原因：
     * 她身上压根没带终端 / 整合包没装 AE2 / 终端没连上网络 / 网络所在区块没加载 /
     * 附近箱子是空的 / 绑定书没绑。全都不写出来，玩家只能干瞪眼。
     * <p>
     * **最有用的一条是"她身上有什么"**：一看就知道终端到底有没有被她带着
     * （在手上、在背包、当饰品，这三种都算"带着"）。
     */
    private void logNoProvider(EntityMaid maid) {
        // 节流：找不到来源时会走冷却重试（5 秒一轮），不节流就是每 5 秒一行、一直刷下去
        long now = System.currentTimeMillis();
        Long last = NO_PROVIDER_LOGGED_AT.get(maid.getUUID());
        if (last != null && now - last < NO_PROVIDER_LOG_COOLDOWN_MS) {
            return;
        }
        NO_PROVIDER_LOGGED_AT.put(maid.getUUID(), now);

        StringBuilder held = new StringBuilder();
        for (ItemStack stack : collectHeldStacks(maid)) {
            if (!stack.isEmpty()) {
                held.append(stack.getHoverName().getString()).append(' ');
            }
        }
        BlueprintMod.LOGGER.warn("女仆 {} 找不到取料来源：AE2 已装={}，缺口 {} 种，"
                        + "需求前几样={}，她身上和背包里=[{}]",
                maid.getUUID(), Ae2Compat.isLoaded(), shortfall.size(),
                shortfall.keySet().stream().limit(3)
                        .map(item -> item.getDescription().getString())
                        .collect(java.util.stream.Collectors.joining("、")),
                held.toString().trim());
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
        // 工地身份：同一张图 + 同锚点 + 朝向。重扫（会话重建）不改它，**换工地才改**——
        // 客户端拿它决定"这是同一处工地还是新开的一处"，同一处只往前不往回
        siteId = ((long) Objects.hash(id, anchor) << 32) | (Objects.hash(rotation) & 0xFFFFFFFFL);
        // 写一行放置速度：它的依据是她的好感度，玩家想核对"为什么她放得这么快/这么慢"时，
        // 这行里三个数（速度、基准、好感度）一目了然
        BlueprintMod.LOGGER.info("女仆 {} 的放置速度：{} 块/秒（基准 {} + 好感度 {} ÷ {}，上限 {}）",
                maid.getUUID(), placesPerSecond(maid), BASE_PLACES_PER_SECOND, maid.getFavorability(),
                FAVORABILITY_PER_EXTRA_PLACE, MAX_PLACES_PER_SECOND);
        // **换了工地**才重新记账：上处说过"这块我拆不动"，不代表这处也免开尊口。
        // 同一处工地反复重建会话（重扫、进度重建）**不算换工地**——
        // 以前这里无条件清账，于是每隔几秒的重扫都把"说过了"重置一遍，
        // 同一块基岩就被她一遍遍地念叨
        if (!Objects.equals(id, activeId)
                || !Objects.equals(anchor, activeAnchor)
                || rotation != activeRotation) {
            toolWarned.clear();
            // 缺料那几句**不在这里销账**：她身上可能同时有两张图、或者一轮里结构数据抖一下，
            // 那都不该让"同一批缺料"重新说一遍。换了缺的种类它自己会再说（指纹按种类算）
        }
        // **站哪都行**：施工本来就没有距离限制（见类注释），她已经到工地附近就就地开工，
        // 不必再去找一个"合适"的站位——大结构外圈那一圈，走过去又远、路上还容易卡，
        // 表现就是"一直在找站位"。只有她离得还远时，才给她一个落脚方向
        // 但**站在结构的水平范围里**不算"就在工地附近"：那是在投影里施工，
        // 放的都是自己身边那几块。判据就是"在不在结构的水平范围里"，
        // 不看她脚下那格要不要放方块——结构内部的大厅同样是结构里
        boolean alreadyNearby =
                maid.distanceToSqr(anchor.getX() + 0.5D, maid.getY(), anchor.getZ() + 0.5D) <= 256.0D
                        && !insideFootprint(maid, anchor, schematic.getSize());
        BlockPos newSpot = null;
        if (!alreadyNearby) {
            // 优先"离她最近的结构外落脚点"（迈两步就出去了），其次结构外圈上最近的那个。
            // 两个都找不到就**不给站位**：宁可让她就地建（她那几格由 step 跳过），
            // 也不要指一个结构里面的位置给她——那正是"把自己埋了"的老路
            newSpot = escapeSpot(level, maid);
            if (newSpot == null) {
                newSpot = nearestStandSpot(level, maid);
            }
        }
        // 耐心只在**站位真的变了**才清零。
        // 之前无条件清零：会话只要被重建（哪怕重建出来是同一个站位），
        // "找 40 tick 就就地开工"那道兜底就永远攒不满——
        // 她于是永远停在 MOVE_TO_SPOT，进度条上也永远是"前往站位"
        if (!Objects.equals(newSpot, standSpot)) {
            spotSearchTicks = 0;
        }
        standSpot = newSpot;
        bestSpotDistance = Double.MAX_VALUE;
        escapeTries = 0; // 新会话：换落脚点的次数从头数
        insideTicks = 0;
        insideForced = false;
        insideGaveUp = false;
        sessionBuilds++;
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
    /**
     * 她带着无线终端、可网络解析不出来（访问点被拆、没电、或者所在区块没加载）时说一句。
     */
    private void notifyTerminalOffline(ServerLevel level, EntityMaid maid) {
        // 附近没人就先不说，也别记成"已说"——否则玩家赶回来反而听不到（同 notifyMissingMaterials）
        if (level.getNearestPlayer(maid, 16.0D) == null) {
            return;
        }
        if (!shouldReportShortfall("no-network", shortfall)) {
            return;
        }
        notify(level, maid, "message.blueprint.maid_terminal_offline");
    }

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
        if (currentMaidId == null) {
            return true; // 认不出是谁就别记账（正常驱动下一定认得）
        }
        Map<String, String> said =
                SAID_SHORTFALL.computeIfAbsent(currentMaidId, id -> new HashMap<>());
        String signature = shortfallSignature(list);
        if (signature.equals(said.get(kind))) {
            return false; // 同一批缺料（同一批**种类**）说过了
        }
        long now = System.currentTimeMillis();
        Long last = SAID_SHORTFALL_AT.get(currentMaidId);
        if (last != null && now - last < SHORTFALL_QUIET_MS) {
            return false; // 同一次尝试里另一条路刚说过，这次先让给它（且不记账）
        }
        said.put(kind, signature);
        SAID_SHORTFALL_AT.put(currentMaidId, now);
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
