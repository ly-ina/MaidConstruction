package com.example.blueprint;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * 模组的行为开关，落在 {@code config/blueprint-common.toml}。
 * <p>
 * 用 COMMON 而不是 SERVER：这些开关几乎都同时管着两边的表现——女仆干不干活是服务端的事，
 * 投影、进度条、队列界面是客户端的事，而"作息算不算数"这种还得让界面能提前知道，
 * 好把"她在休息"写出来。COMMON 两边各读本地那份，行为一致，也不会出现
 * "客户端要等服务端同步才知道该画什么"的空窗。
 * <p>
 * <b>所有取值都走本类的静态方法，不要在别处直接摸 {@link ForgeConfigSpec.ConfigValue}。</b>
 * 那样写出来的代码在配置还没加载时读一次就会抛异常（客户端进主界面前、
 * 或者配置文件被别的模组改坏时都会撞上），而这一层把"读不到"就地兜成默认值——
 * 配置读不出来最多是行为回到出厂设置，不该让游戏起不来。
 */
public final class BlueprintConfig {

    public static final ForgeConfigSpec SPEC;

    // 这几个值都是在**静态初始化**里由构造方法填的（Forge 推荐的 configure 写法），
    // 所以不能是 final：final 静态字段只允许在静态块里赋值，不能由构造方法代劳
    private static ForgeConfigSpec.BooleanValue BUILD_ON_SHIFT;
    private static ForgeConfigSpec.BooleanValue INDUSTRY_ON_SHIFT;
    private static ForgeConfigSpec.BooleanValue OWNER_ONLY_ORDERS;
    private static ForgeConfigSpec.BooleanValue SALVAGE_ENABLED;
    private static ForgeConfigSpec.BooleanValue MAID_PROJECTION;
    private static ForgeConfigSpec.IntValue MAID_PROJECTION_RADIUS;
    private static ForgeConfigSpec.IntValue PROGRESS_RADIUS;
    private static ForgeConfigSpec.ConfigValue<String> CREATIVE_ITEM_MODE;
    private static ForgeConfigSpec.IntValue STEP_OUT_GRACE_TICKS;
    private static ForgeConfigSpec.BooleanValue ALLOW_HARD_MOVE;
    private static ForgeConfigSpec.BooleanValue GIVE_GUIDE_BOOK;

    /**
     * 出厂设置。取不到配置时就用这些，别让"配置没加载"变成"功能坏掉"。
     * <p>
     * <b>施工的作息门禁默认关着</b>（{@code DEF_BUILD_ON_SHIFT = false}）：它一生效，
     * 不在上班时间她就不动手——那是"她坏了"和"她在等天亮"最容易混淆的一个开关，
     * 先按 1.5.5 的行为跑（昼夜不停建），想要作息的自己打开。
     */
    private static final boolean DEF_BUILD_ON_SHIFT = false;
    private static final boolean DEF_INDUSTRY_ON_SHIFT = true;
    private static final boolean DEF_OWNER_ONLY = true;
    private static final boolean DEF_SALVAGE = true;
    private static final boolean DEF_PROJECTION = true;
    private static final int DEF_PROJECTION_RADIUS = 32;
    private static final int DEF_PROGRESS_RADIUS = 16;
    private static final int DEF_STEP_OUT_GRACE_TICKS = 40;
    private static final boolean DEF_ALLOW_HARD_MOVE = true;
    private static final boolean DEF_GIVE_GUIDE_BOOK = true;
    private static final String DEF_CREATIVE_ITEM_MODE = "blocks";
    /** 创造女仆接口可以列出的范围（{@code blueprint-common.toml} 里那一项的取值） */
    private static final List<String> CREATIVE_ITEM_MODES = List.of("blocks", "all");

    static {
        Pair<BlueprintConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .configure(BlueprintConfig::new);
        SPEC = pair.getRight();
    }

    private BlueprintConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("女仆的作息与施工").push("schedule");
        BUILD_ON_SHIFT = builder
                .comment("施工也按女仆的作息表来：不在上班时间她不动手，到点自己开工（会说一句为什么不动）。",
                        "默认关着——也就是昼夜不停地建，跟 1.5.5 的行为一致。",
                        "注意：打开之后如果她「不动了」，多半就是此刻不是她的工作时间，不是坏了。")
                .define("build_only_on_shift", DEF_BUILD_ON_SHIFT);
        INDUSTRY_ON_SHIFT = builder
                .comment("「工业模式」按作息表来：休息时段她停手，单子排队等着。",
                        "想让她 24 小时连轴转就关掉它。")
                .define("industry_only_on_shift", DEF_INDUSTRY_ON_SHIFT);
        builder.pop();

        builder.comment("下单与队列").push("orders");
        OWNER_ONLY_ORDERS = builder
                .comment("只有女仆的主人能给她下单、撤单。",
                        "多人服上建议开着：不然谁都能往她的队列里塞东西、或者把别人的活撤掉。",
                        "关掉则任何人都能操作（连服主也一样，不做额外例外）。")
                .define("owner_only", DEF_OWNER_ONLY);
        builder.pop();

        builder.comment("施工时被顶掉的方块").push("salvage");
        SALVAGE_ENABLED = builder
                .comment("把被顶掉的方块按战利品表拆下来，收进她的背包、",
                        "其次是饰品栏里的无线终端，都装不下才丢在地上。",
                        "关掉就回到老行为：原方块连同掉落物直接消失。")
                .define("enabled", DEF_SALVAGE);
        builder.pop();

        builder.comment("女仆手上那张未完工蓝图的投影").push("projection");
        MAID_PROJECTION = builder
                .comment("女仆拿着还没建完的蓝图时，附近的玩家能看见她那张图的投影。",
                        "你自己手里拿着蓝图时，永远优先显示你自己那张。")
                .define("show_for_maid", DEF_PROJECTION);
        MAID_PROJECTION_RADIUS = builder
                .comment("离女仆多近才显示她的投影（格）。")
                .defineInRange("radius", DEF_PROJECTION_RADIUS, 4, 128);
        builder.pop();

        builder.comment("创造女仆接口").push("creative_interface");
        CREATIVE_ITEM_MODE = builder
                .comment("挂进 ME 网络时，这个接口向网络申报哪些东西：",
                        "  blocks —— 只申报**可放置的方块**（默认）。建造本来就要的是方块，",
                        "            物品/装备/材料那一堆既用不上，又是这一栏里的大头；",
                        "            清单短了，终端打开和排序都快得多。",
                        "  all    —— 物品注册表里的每一样都申报（老行为）。想要在终端里",
                        "            直接取剑、取装备、取各种材料时用这个，代价是清单会很长。",
                        "注意：不管选哪个，女仆取料都不受影响——她是「要什么给什么」，不查这份清单。")
                .defineInList("item_mode", DEF_CREATIVE_ITEM_MODE, CREATIVE_ITEM_MODES);
        builder.pop();

        builder.comment("施工进度条").push("progress");
        PROGRESS_RADIUS = builder
                .comment("站在女仆多少格内显示进度条（同时也是服务端推送的距离）。")
                .defineInRange("radius", DEF_PROGRESS_RADIUS, 4, 64);
        builder.pop();

        builder.comment("开工前先站到投影外").push("step_out");
        STEP_OUT_GRACE_TICKS = builder
                .comment("她站在结构范围里的时候，先**专心往外走**这么多 tick，这一段时间一块都不放；",
                        "走出去之后才开始放。40 = 2 秒。",
                        "为什么要留这一段：在里头开工，放的都是她身边那几块，她占着的格子会被跳过",
                        "（墙里空一块），所以先让位再动工是划算的。",
                        "填 0 就是不给让位时间：她站在哪儿就在哪儿开工（1.6.6 之前的老行为）。")
                .defineInRange("grace_ticks", DEF_STEP_OUT_GRACE_TICKS, 0, 200);
        ALLOW_HARD_MOVE = builder
                .comment("让位时间用完了她还没走出去时，直接把她挪到那个落脚点上。",
                        "落脚点是现找的、结构外的、站得住的那一格，所以挪过去是安全的。",
                        "关掉的话她就就地开工：好处是绝不会看见瞬移，坏处是可能在自己砌的墙里留个洞。")
                .define("allow_hard_move", DEF_ALLOW_HARD_MOVE);
        builder.pop();

        builder.comment("说明书").push("guide_book");
        GIVE_GUIDE_BOOK = builder
                .comment("玩家第一次进这个存档时，发一本《女仆建筑说明书》（原版成书，能翻页）。",
                        "关掉也不会让人抓瞎：说明书可以用「一本书 + 一张纸」自己合成。")
                .define("give_on_first_join", DEF_GIVE_GUIDE_BOOK);
        builder.pop();
    }

    /** 施工是否按作息表来 */
    public static boolean buildOnlyOnShift() {
        return read(BUILD_ON_SHIFT, DEF_BUILD_ON_SHIFT);
    }

    /** 工业模式是否按作息表来 */
    public static boolean industryOnlyOnShift() {
        return read(INDUSTRY_ON_SHIFT, DEF_INDUSTRY_ON_SHIFT);
    }

    /** 是否只有主人能下单 / 撤单 */
    public static boolean ownerOnlyOrders() {
        return read(OWNER_ONLY_ORDERS, DEF_OWNER_ONLY);
    }

    /** 施工时是否回收被顶掉的方块 */
    public static boolean salvageEnabled() {
        return read(SALVAGE_ENABLED, DEF_SALVAGE);
    }

    /** 是否显示女仆手上蓝图的投影 */
    public static boolean maidProjection() {
        return read(MAID_PROJECTION, DEF_PROJECTION);
    }

    /** 女仆蓝图投影的显示距离（格） */
    public static int maidProjectionRadius() {
        return read(MAID_PROJECTION_RADIUS, DEF_PROJECTION_RADIUS);
    }

    /** 进度条的显示 / 推送距离（格） */
    public static int progressRadius() {
        return read(PROGRESS_RADIUS, DEF_PROGRESS_RADIUS);
    }

    /** 开工前"先站到投影外"的让位时间（tick，0 表示不让位） */
    public static int stepOutGraceTicks() {
        return read(STEP_OUT_GRACE_TICKS, DEF_STEP_OUT_GRACE_TICKS);
    }

    /** 让位时间用完她还没走出去时，允不允许直接把她挪到落脚点上 */
    public static boolean allowHardMove() {
        return read(ALLOW_HARD_MOVE, DEF_ALLOW_HARD_MOVE);
    }

    /** 新玩家第一次进这个存档时，发不发说明书 */
    public static boolean giveGuideBookOnFirstJoin() {
        return read(GIVE_GUIDE_BOOK, DEF_GIVE_GUIDE_BOOK);
    }

    /** 创造女仆接口只申报可放置的方块吗（false 就是申报全部物品） */
    public static boolean creativeInterfaceBlocksOnly() {
        return "blocks".equals(read(CREATIVE_ITEM_MODE, DEF_CREATIVE_ITEM_MODE));
    }

    /**
     * 读一个配置值，读不到就回默认。
     * <p>
     * 会读不到的情形真不少：配置还没加载、文件被改坏、或者别的模组在错误的阶段
     * 调到了我们的代码。这时候"回到出厂设置"永远优于"抛异常"。
     */
    private static boolean read(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private static int read(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private static String read(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String raw = value.get();
            return raw == null ? fallback : raw;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private BlueprintConfig() {
    }
}
