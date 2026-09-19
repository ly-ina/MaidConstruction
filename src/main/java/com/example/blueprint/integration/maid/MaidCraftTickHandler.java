package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.build.BlockContainerProvider;
import com.example.blueprint.build.ItemProvider;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.item.BindingBookItem;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「工业模式」的服务端执行：女仆照**主人在学习池里点名的那条做法**手搓，材料从容器里取。
 * <p>
 * 开工的条件有两条，缺一条都不动（见 {@link #onLevelTick}）：她在**工业模式**、
 * 且此刻是她的**工作时间**。下单会自动把她切到工业模式，主人不必手动拨模式；
 * 不在工作时间她照旧歇着，单子排着队不会跑。
 * <p>
 * 一段流程分几步走，每 {@value #ACTION_INTERVAL_TICKS} tick 只推进一小步：
 * 认配方 → 找来源 → （要走路就先走过去）→ 搬料 → 料齐了摆一遍手搓。
 * 一步一步来是有必要的：女仆赶路要时间，搬料可能一趟搬不完，
 * 全塞在一个 tick 里做就只能成功一次。
 * <p>
 * <b>配方每次都现查</b>（她在池子里点名的那条做法），不在下单时抄一份：
 * 主人在界面上改一次用哪条，就该立刻作用于还没做完的单。
 * <p>
 * 素材与"缺材料"提示都照着施工那边的口径来：先确认全都够再动手，
 * 缺料只提醒一次（同一张单不烦主人第二遍）。
 */
public class MaidCraftTickHandler {

    /** 每这么多 tick 推进一步：搬运与合成都不必每 tick 一次，给赶路留时间 */
    private static final int ACTION_INTERVAL_TICKS = 8;
    /** 离来源 3 格以内才算够得着（跟施工那边一个量级） */
    private static final double REACH_SQR = 9.0D;
    private static final double WALK_SPEED = 1.0D;
    /** 吃 tag 的材料（"任意木板"）最多问这么多个候选，免得一个大 tag 把一圈容器问穿 */
    private static final int MAX_CANDIDATE_CHECKS = 24;
    /** 一趟最多搬几种材料：3×3 的配方最多也就九种 */
    private static final int MAX_KINDS_PER_TRIP = 9;
    /** 同一张单的提示冷却 */
    private static final long WARN_COOLDOWN_MS = 15_000L;
    /** 缺料提示里最多列几种 */
    private static final int MAX_REPORTED = 4;

    /** 每个女仆的步进节拍 */
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    /** 每个女仆上次提示的时间 */
    private static final Map<UUID, Long> LAST_WARN = new HashMap<>();

    /**
     * 主人下的单做完了、还没当面告诉他：等她手头没活了就跑过去说一句。
     * <p>
     * 这只是**临时状态**（值为"做好了的那几样"），不写进她的存档：跑过去说一声这件事
     * 过期就没意义了——存档里留个"还没汇报"的尾巴，下次进游戏她突然跑来报一句反而怪。
     */
    private static final Map<UUID, List<ItemStack>> REPORTS = new HashMap<>();
    /** 报告等了多少 tick（主人一直不在身边就作废，不留一笔永远报不掉的账） */
    private static final Map<UUID, Integer> REPORT_AGE = new HashMap<>();
    /** 报告最多等这么久（约五分钟，够她走完一段路） */
    private static final int REPORT_TIMEOUT_TICKS = 6000;
    /** 走到这个距离以内就算"到跟前了"，可以开口 */
    private static final double REPORT_DISTANCE_SQR = 4.0D * 4.0D;

    private MaidCraftTickHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof EntityMaid maid) || !MaidIndustryTask.isIndustry(maid)) {
                continue;
            }
            if (MaidCraftOrder.first(maid) != null) {
                // 有活：只在**上班时间**干。不在就让她等着——单子排着队不会跑，
                // 到点了自己会开工
                if (MaidIndustryTask.isWorkingTime(maid)) {
                    tickMaid(level, maid);
                }
                continue;
            }
            // 没活了，两件事：
            // 1）她可能还有一句"做好了"没当面说（做单时她跑不回来）；先把这句说完
            // 2）说完了（或者没人听、等过期了）就把模式**还回下单之前的那个**
            // 顺序不能反：一还回去她就不归这一段管了，那句"做好了"就永远没机会说
            tickReport(level, maid);
            if (!REPORTS.containsKey(maid.getUUID())) {
                COOLDOWN.remove(maid.getUUID()); // 下次下单不用再等上好几秒
                MaidIndustryTask.release(maid);
            }
        }
    }

    private static void tickMaid(ServerLevel level, EntityMaid maid) {
        UUID uuid = maid.getUUID();
        int remaining = COOLDOWN.getOrDefault(uuid, 0);
        if (remaining > 0) {
            COOLDOWN.put(uuid, remaining - 1);
            return;
        }
        COOLDOWN.put(uuid, ACTION_INTERVAL_TICKS);
        step(level, maid);
    }

    // ------------------------------------------------------------------
    // 一步
    // ------------------------------------------------------------------

    private static void step(ServerLevel level, EntityMaid maid) {
        MaidCraftOrder.Order order = MaidCraftOrder.first(maid);
        if (order == null) {
            return;
        }
        Recipe<CraftingContainer> recipe = resolve(level, maid, order.product());
        if (recipe == null) {
            // 池子里没有"怎么做"（或者那条配方 id 已经不在了）：这张单做不了。
            // 撤掉它而不是留着卡住——不然排在后面的单永远轮不到
            warn(level, maid, true, "message.blueprint.craft.no_recipe",
                    order.product().getHoverName());
            MaidCraftOrder.updateFirst(maid, order.withRemaining(0));
            return;
        }

        MaidItemSource self = new MaidItemSource(maid);
        IItemHandler backpack = self.getBackpack();
        if (backpack == null) {
            warn(level, maid, true, "message.blueprint.maid_no_backpack");
            return;
        }

        // 找一个"有这些东西"的来源：探针清单把每个材料位的**所有候选**都列上，
        // 这样"任意木板"这种 tag 材料才能匹配到箱子里真有的那种
        ItemProvider provider = findSource(level, maid, probeBill(recipe));
        // 真清单：每个材料位挑一件真拿得到的（先看她背包，再看来源里有什么）
        Map<Item, Integer> bill = realBill(recipe, self, provider);

        Map<Item, Integer> shortfall = ItemProvider.missingAmounts(backpack, bill);
        if (shortfall.isEmpty()) {
            craftOne(level, maid, recipe, bill, self, backpack);
            return;
        }

        if (provider == null) {
            // 先看这几样缺的材料里有没有"她自己会做"的：有就先去做零件（嵌套合成）
            if (tryNest(level, maid, order, shortfall)) {
                return;
            }
            // 分两种说：她身上压根没有绑定书（那就不是"仓库里没有"，是书没给她），
            // 还是书在、只是里面确实没有这几样
            if (BlueprintBuildController.findBindingBook(maid).isEmpty()) {
                warn(level, maid, false, "message.blueprint.craft.no_book");
            } else {
                warnWholeChain(level, maid, self);
            }
            return;
        }
        // 要走到容器跟前才够得着（身上的无线终端那种来源不用走动）
        if (provider.requiresTravel() && !near(maid, provider.interactPos())) {
            BlockPos pos = provider.interactPos();
            maid.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, WALK_SPEED);
            return;
        }
        maid.swing(InteractionHand.MAIN_HAND);
        if (provider.transferInto(backpack, shortfall, MAX_KINDS_PER_TRIP) == 0) {
            // 白跑一趟有两种原因，得分清楚（跟施工那边同一个讲究）：
            // 容器里有但背包塞不下，或者这来源里确实没有还缺的那几样。
            // 提示写错会让主人顺着错的线索去翻箱子，而问题其实在她背包
            boolean hasWanted = provider.hasAny(shortfall);
            if (!hasWanted && tryNest(level, maid, order, shortfall)) {
                return;
            }
            if (hasWanted) {
                // 背包塞不下说的是"这一趟搬运"的事，照排头那张单说就够准
                warn(level, maid, false, "message.blueprint.craft.backpack_full",
                        order.product().getHoverName());
            } else {
                warnWholeChain(level, maid, self);
            }
        }
    }

    /** 料齐了：摆一遍、合成、把产物和余料收进背包、单子减一 */
    private static void craftOne(ServerLevel level, EntityMaid maid, Recipe<CraftingContainer> recipe,
                                 Map<Item, Integer> bill, MaidItemSource self, IItemHandler backpack) {
        // 先按她**手上真有**的材料摆出来，再让配方自己认一遍。
        // 先摆后扣：摆不出/认不出就什么都不扣，不存在"扣了料才失败"要回滚的情形
        CraftingGrid grid = new CraftingGrid(3, 3);
        List<ItemStack> layout = StudyRecipeCapture.layout(recipe,
                ingredient -> firstOwned(ingredient, self));
        for (int i = 0; i < layout.size(); i++) {
            grid.place(i, layout.get(i));
        }
        if (!recipe.matches(grid, level)) {
            MaidCraftOrder.Order stuck = MaidCraftOrder.first(maid);
            warn(level, maid, true, "message.blueprint.craft.make_failed",
                    stuck == null ? ItemStack.EMPTY.getHoverName() : stuck.product().getHoverName());
            return;
        }

        // 确认全都够再动手（跟施工那边同一个规矩：中途失败会白扣掉前面那些）
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            if (self.available(entry.getKey()) < entry.getValue()) {
                return;
            }
        }
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            self.consume(entry.getKey(), entry.getValue());
        }

        ItemStack result = recipe.assemble(grid, level.registryAccess());
        NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
        List<ItemStack> produced = new ArrayList<>(remaining.size() + 1);
        produced.add(result);
        give(level, maid, backpack, result);
        for (ItemStack stack : remaining) {
            give(level, maid, backpack, stack);
            produced.add(stack);
        }
        // 她带着无线女仆终端的话，把刚做好的直接送进终端连的网络，别在她背包里堆着
        storeIntoTerminal(level, maid, backpack, produced);

        MaidCraftOrder.Order order = MaidCraftOrder.first(maid);
        if (order != null) {
            boolean finished = order.remaining() <= 1;
            MaidCraftOrder.updateFirst(maid, order.withRemaining(order.remaining() - 1));
            if (finished && order.lineage().isEmpty()) {
                // 主人亲自下的单做完了：记一笔"待汇报"，等她手头没活了跑去当面说
                // （零件单不报：那是她自己给自己排的活，主人不需要知道木棍做完了）
                rememberReport(maid, order.product());
            }
        }
        maid.swing(InteractionHand.MAIN_HAND);
        // 做好一个，高兴一下（跟学会东西是同一个音）
        maid.tryPlayMaidPickupSound();
    }

    // ------------------------------------------------------------------
    // 配方与材料
    // ------------------------------------------------------------------

    /**
     * 她"会做"的这号产物该照哪个配方做：拿池子里那条**优先配方**的 id 回配方表取回来。
     * <p>
     * 只认合成配方；id 为空（当初没认出来）或者配方已经被数据包删了就返回 null。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static Recipe<CraftingContainer> resolve(ServerLevel level, EntityMaid maid, ItemStack product) {
        for (MaidStudyPool.Learned learned : MaidStudyPool.known(maid)) {
            if (!ItemStack.matches(learned.product(), product)) {
                continue;
            }
            // 照主人**点名的那条**做法做（选择法：没选中的做法她不用，所以不需要再顺位）
            MaidStudyPool.Recipe chosen = learned.chosen();
            if (chosen == null || chosen.id() == null) {
                return null;
            }
            return level.getRecipeManager().byKey(chosen.id())
                    .filter(found -> found.getType() == RecipeType.CRAFTING)
                    .map(found -> (Recipe<CraftingContainer>) found)
                    .orElse(null);
        }
        return null;
    }

    /** 探针清单：每个材料位的所有候选都列上，用来判断"哪个来源值得跑一趟" */
    private static Map<Item, Integer> probeBill(Recipe<CraftingContainer> recipe) {
        Map<Item, Integer> probe = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            int checked = 0;
            for (ItemStack candidate : ingredient.getItems()) {
                if (checked++ >= MAX_CANDIDATE_CHECKS) {
                    break;
                }
                probe.putIfAbsent(candidate.getItem(), 1);
            }
        }
        return probe;
    }

    /** 真清单：每个材料位挑一件具体物品，重复的材料合并计数 */
    private static Map<Item, Integer> realBill(Recipe<CraftingContainer> recipe, MaidItemSource self,
                                               @Nullable ItemProvider provider) {
        Map<Item, Integer> bill = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            Item item = choose(ingredient, self, provider);
            if (item != null) {
                bill.merge(item, 1, Integer::sum);
            }
        }
        return bill;
    }

    /**
     * 一个材料位挑哪件物品：她自己有就先用她的（省一趟），
     * 否则在来源里逐个候选问过去，实在都没有才取材料表第一个——
     * 那种情况多半会卡在"缺材料"上，提示里会说要什么。
     */
    @Nullable
    private static Item choose(Ingredient ingredient, MaidItemSource self, @Nullable ItemProvider provider) {
        ItemStack[] candidates = ingredient.getItems();
        if (candidates.length == 0) {
            return null;
        }
        for (ItemStack candidate : candidates) {
            if (self.available(candidate.getItem()) > 0) {
                return candidate.getItem();
            }
        }
        if (provider != null) {
            int checked = 0;
            for (ItemStack candidate : candidates) {
                if (checked++ >= MAX_CANDIDATE_CHECKS) {
                    break;
                }
                if (provider.hasAny(Map.of(candidate.getItem(), 1))) {
                    return candidate.getItem();
                }
            }
        }
        return candidates[0].getItem();
    }

    /** 她背包里现在就有的那件（摆格用它，摆出来的正是她手上这些） */
    private static ItemStack firstOwned(Ingredient ingredient, MaidItemSource self) {
        for (ItemStack candidate : ingredient.getItems()) {
            if (self.available(candidate.getItem()) > 0) {
                return candidate.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 嵌套合成：缺的那几样里，挑一样**她自己会做**的先做出来。
     * <p>
     * 做法是往队首插一张"先做这个零件"的单，而不是在这里递归调用合成。
     * 零件做完，原来那张单自然就有料了；零件自己缺料的话，它那一步会再往队首插一层——
     * **能套多深就套多深**，只有"绕回自己"会被 {@code insertFirst} 挡下来。
     *
     * @return 插进去了返回 true（这一步就到这儿，下一拍她去做零件）
     */
    private static boolean tryNest(ServerLevel level, EntityMaid maid, MaidCraftOrder.Order order,
                                   Map<Item, Integer> shortfall) {
        ItemStack looped = ItemStack.EMPTY; // 撞上循环的那个零件（用来写提示）
        for (Map.Entry<Item, Integer> entry : shortfall.entrySet()) {
            ItemStack part = new ItemStack(entry.getKey());
            Recipe<CraftingContainer> recipe = resolve(level, maid, part);
            if (recipe == null) {
                // 这个零件她不会做，换下一样看看（也许另一样她会）
                continue;
            }
            // 一次出几个，就决定了要做几次（合成一次出一组的那种）
            int perCraft = Math.max(1, recipe.getResultItem(level.registryAccess()).getCount());
            int crafts = (entry.getValue() + perCraft - 1) / perCraft;
            // 把"来路"接上去：这张零件单的来路 = 原单的来路 + 原单自己
            List<ItemStack> lineage = new ArrayList<>(order.lineage());
            lineage.add(order.product().copyWithCount(1));
            // 绕回了来路 = 死循环（A 要用 B、B 又要用回 A）。这里自己判一次，
            // 因为 insertFirst 只回一个 boolean，分不清"绕回去了"还是"这号产物已经有单在排"——
            // 而这两件事对主人来说完全不一样：前者没救，后者等一会儿就好
            if (containsItem(lineage, part)) {
                if (looped.isEmpty()) {
                    looped = part;
                }
                continue; // 换下一样缺的接着试：一样绕回去不代表别的也绕
            }
            // 插进去了才算数：没插进去（已经有单在排、队满）就换下一样缺的接着试，
            // 一样被挡住不该把整条嵌套都否掉
            if (MaidCraftOrder.insertFirst(maid, part, crafts, List.copyOf(lineage))) {
                return true;
            }
        }
        if (!looped.isEmpty()) {
            // 说清是"绕回来了"，别落进"还缺材料"那一句：那会把主人支去再塞点材料，
            // 而这条路塞多少材料都走不通
            warn(level, maid, false, "message.blueprint.craft.recipe_loop",
                    order.product().getHoverName(), looped.getHoverName());
            return true; // 这一步有交代了，别再叠一句"还缺材料"
        }
        return false;
    }

    /** 这套"来路"里出现过这样东西没有（比法跟 {@code insertFirst} 里一致） */
    private static boolean containsItem(List<ItemStack> lineage, ItemStack part) {
        for (ItemStack ancestor : lineage) {
            if (ItemStack.matches(ancestor, part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 报**根单**还缺哪些材料——按主人要的那样东西的**直接用料**算，不往零件方向摊。
     * <p>
     * 另外两种口径都不对：
     * <ul>
     *   <li>只报排头：嵌套时排头永远是零件单，主人看到的是"做木棍 还缺材料：木板 ×2"，
     *       不知道整件事要什么；料只够做零件时，做完零件又冒一条，像是永远填不满。</li>
     *   <li>把零件的用料也摊进来：报出来是"木板 ×4"——可多出来的那 2 个是**她自己做零件**
     *       要用的，主人只知道"木剑要 2 木板 1 木棍"，照这个备料就行；零件的料，
     *       她到了容器跟前自己会拿，摊进来只会吓人。</li>
     * </ul>
     * 所以这里只算**根单**（主人亲自下的那张，来路为空）：直接用料 × 剩余数量 − 背包已有。
     */
    private static void warnWholeChain(ServerLevel level, EntityMaid maid, MaidItemSource self) {
        MaidCraftOrder.Order root = rootOrder(maid);
        if (root == null) {
            return;
        }
        Map<Item, Integer> missing = rootShortfall(level, maid, self, root);
        if (missing.isEmpty()) {
            return;
        }
        warn(level, maid, false, "message.blueprint.craft.missing",
                root.product().getHoverName(), describe(missing));
    }

    /** 主人亲自下的那张单（来路为空）。队列里没有就返回 null */
    @Nullable
    private static MaidCraftOrder.Order rootOrder(EntityMaid maid) {
        for (MaidCraftOrder.Order order : MaidCraftOrder.pending(maid)) {
            if (order.lineage().isEmpty()) {
                return order;
            }
        }
        return null;
    }

    /**
     * 根单的直接用料缺口：用料 × 剩余数量 − 背包已有。
     * <p>
     * 材料变体按"她背包里有什么"挑（{@code realBill} 的 provider 传 null），
     * 所以这是个**估算**，够主人知道"去凑哪些、大概多少"就行。
     */
    private static Map<Item, Integer> rootShortfall(ServerLevel level, EntityMaid maid,
                                                    MaidItemSource self, MaidCraftOrder.Order root) {
        Recipe<CraftingContainer> recipe = resolve(level, maid, root.product());
        if (recipe == null) {
            return Map.of();
        }
        int times = Math.max(1, root.remaining());
        Map<Item, Integer> shortfall = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : realBill(recipe, self, null).entrySet()) {
            int missing = entry.getValue() * times - self.available(entry.getKey());
            if (missing > 0) {
                shortfall.put(entry.getKey(), missing);
            }
        }
        return shortfall;
    }

    /**
     * 找料从哪来：就近的容器 → 身上的无线终端 → 绑定书指定的仓库。
     * <p>
     * 前两支借施工那边现成的实现（{@link BlueprintBuildController}）：哪些方块算容器、
     * 无线终端怎么认、要不要扫饰品栏，那些细节那边都磨过了，重写必然走样。
     * 绑定书那一支自己写：那边那支会顺带播报"隔着维度""仓库空了"，用的是施工口径的话术，
     * 跟手搓的提示不是一回事。
     */
    @Nullable
    private static ItemProvider findSource(ServerLevel level, EntityMaid maid, Map<Item, Integer> bill) {
        ItemProvider nearby = BlueprintBuildController.findNearbyContainer(level, maid, bill);
        if (nearby != null) {
            return nearby;
        }
        ItemProvider wireless = BlueprintBuildController.findWirelessProvider(level, maid);
        if (wireless != null) {
            return wireless;
        }
        return boundProvider(level, maid, bill);
    }

    @Nullable
    private static ItemProvider boundProvider(ServerLevel level, EntityMaid maid, Map<Item, Integer> bill) {
        ItemStack book = BlueprintBuildController.findBindingBook(maid);
        if (book.isEmpty()) {
            return null;
        }
        ResourceLocation dimension = BindingBookItem.getBoundDimension(book);
        if (dimension != null && !dimension.equals(level.dimension().location())) {
            return null;
        }
        BlockPos pos = BindingBookItem.getBoundPos(book);
        if (pos == null) {
            return null;
        }
        // 同一个坐标可能是普通箱子，也可能接着 ME 网络，所以两种都试（先问 AE2）
        ItemProvider ae2 = Ae2Compat.createProvider(level, pos);
        ItemProvider provider = ae2 != null ? ae2 : new BlockContainerProvider(level, pos);
        return provider.hasAny(bill) ? provider : null;
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 跑过去把"做完了"当面说一句。
     * <p>
     * 条件有三条，缺一条都先不开口：**手头没别的活了**（他问的是"做完了吗"，
     * 不是"做到哪了"）、**主人得在身边**（不在就走过去）、**没等太久**（人等不到了就作废）。
     * 走到跟前才说话这件事是刻意的：在聊天栏里飘一句和在眼前被拍一下，感觉完全不同。
     */
    private static void tickReport(ServerLevel level, EntityMaid maid) {
        UUID uuid = maid.getUUID();
        List<ItemStack> finished = REPORTS.get(uuid);
        if (finished == null || finished.isEmpty()) {
            return;
        }
        if (MaidCraftOrder.first(maid) != null) {
            return;
        }
        int age = REPORT_AGE.merge(uuid, 1, Integer::sum);
        if (age > REPORT_TIMEOUT_TICKS) {
            forgetReport(uuid);
            return;
        }
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof ServerPlayer) || owner.level() != maid.level() || !owner.isAlive()) {
            return;
        }
        if (maid.distanceToSqr(owner) > REPORT_DISTANCE_SQR) {
            // 主动跑过去。"跟人"那条逻辑此刻也在发同一个目标（没单时她才跟人），不冲突
            maid.getNavigation().moveTo(owner, WALK_SPEED);
            return;
        }
        maid.getNavigation().stop();
        maid.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        MaidSpeech.say(maid, "message.blueprint.craft.finished", describeProducts(finished));
        // 报告的同时高兴一下：她也是跑过来的
        maid.tryPlayMaidPickupSound();
        forgetReport(uuid);
    }

    private static void rememberReport(EntityMaid maid, ItemStack product) {
        REPORTS.computeIfAbsent(maid.getUUID(), uuid -> new ArrayList<>(4))
                .add(product.copyWithCount(1));
        REPORT_AGE.put(maid.getUUID(), 0);
    }

    private static void forgetReport(UUID uuid) {
        REPORTS.remove(uuid);
        REPORT_AGE.remove(uuid);
    }

    /** 做好了的那几样，念成一串（太多了就省略号收尾） */
    private static Component describeProducts(List<ItemStack> products) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < products.size(); i++) {
            if (i >= MAX_REPORTED) {
                list.append("…");
                break;
            }
            if (i > 0) {
                list.append("、");
            }
            list.append(products.get(i).getHoverName());
        }
        return list;
    }

    private static boolean near(EntityMaid maid, BlockPos pos) {
        return maid.blockPosition().distSqr(pos) <= REACH_SQR;
    }

    /**
     * 产物去向：她身上带着**无线女仆终端**时，把刚做好的东西送进终端连的 ME 网络，
     * 而不是留在背包里占地。
     * <p>
     * 做法是"先进背包、再从这儿扫回网络"，而不是直接往网络里插：网络可能满、
     * 终端可能刚好失效，走背包这一步能保证**东西不会凭空消失**——
     * 扫不动就留在她背包里，主人还能从她身上拿。搬运与兜底都复用
     * {@link ItemProvider#acceptInto} 那套（施工时归还余料用的就是它）。
     * <p>
     * 只扫刚做出来的这几样（成品 + 余料）。她背包里原有的**同类**东西会一并被扫进去，
     * 但那本来就是从这网络里取出来的，回去不亏。
     */
    private static void storeIntoTerminal(ServerLevel level, EntityMaid maid, IItemHandler backpack,
                                          List<ItemStack> produced) {
        Map<Item, Integer> filter = new HashMap<>();
        for (ItemStack stack : produced) {
            if (!stack.isEmpty()) {
                filter.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        if (filter.isEmpty()) {
            return;
        }
        // 没有终端 / 没绑网络 / 不在覆盖范围内，都会拿到 null，那就照旧留在背包里
        ItemProvider terminal = BlueprintBuildController.findWirelessProvider(level, maid);
        if (terminal == null) {
            return;
        }
        if (terminal.acceptInto(backpack, filter) > 0) {
            maid.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 产物与余料都进她背包；塞不下就掉在脚边——宁可满地都是，也不能凭空蒸发 */
    private static void give(ServerLevel level, EntityMaid maid, IItemHandler backpack, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = ItemHandlerHelper.insertItemStacked(backpack, stack, false);
        if (!leftover.isEmpty()) {
            Block.popResource(level, maid.blockPosition(), leftover);
        }
    }

    /** 缺料提示里的材料清单 */
    private static Component describe(Map<Item, Integer> shortfall) {
        MutableComponent list = Component.empty();
        int shown = 0;
        for (Map.Entry<Item, Integer> entry : shortfall.entrySet()) {
            if (shown >= MAX_REPORTED) {
                list.append("…");
                break;
            }
            if (shown > 0) {
                list.append("、");
            }
            list.append(new ItemStack(entry.getKey()).getHoverName())
                    .append(" ×" + entry.getValue());
            shown++;
        }
        return list;
    }

    /**
     * 提醒主人一句，同一张单只提一次（{@code force} 用于"这张单要撤掉"这类必须说的事）。
     */
    private static void warn(ServerLevel level, EntityMaid maid, boolean force, String key, Object... args) {
        MaidCraftOrder.Order order = MaidCraftOrder.first(maid);
        if (!force) {
            if (order != null && order.warned()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = LAST_WARN.get(maid.getUUID());
            if (last != null && now - last < WARN_COOLDOWN_MS) {
                return;
            }
            LAST_WARN.put(maid.getUUID(), now);
        }
        if (order != null) {
            MaidCraftOrder.updateFirst(maid, order.withWarned());
        }
        // 用她的口吻说（"<名字>：……"）：卡住的是她，汇报的也该是她
        MaidSpeech.say(maid, key, args);
        BlueprintMod.LOGGER.info("女仆 {} 手搓卡住：{}", maid.getUUID(), key);
    }
}
