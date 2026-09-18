package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 女仆的**待做清单**：主人在学习池里下的单。
 * <p>
 * 存的是"做什么、还剩几个"（产物整份 NBT 一起存，带附魔的变体也分得清），
 * <b>不存配方</b>——配方每次现查她学习池里那条优先配方。理由跟池子那边一致：
 * 主人在界面上改一次优先级，就该立刻影响还没做完的单；把配方在下单时抄一份，
 * 等于多出一份要同步的旧数据。
 * <p>
 * 一样产物只有一张单，重复下单是**加数量**，不会排出一长串"做 1 个"。
 * 随存档走、也随 TLM 的 {@code TASK_DATA_SYNC} 同步到客户端，所以学习池界面
 * 直接把待做清单显示出来即可，不必另发数据包。
 */
public final class MaidCraftOrder implements TaskDataKey<List<MaidCraftOrder.Order>> {

    /** 注册给 TLM 的那把钥匙（见 {@code MaidExtension#registerTaskData}） */
    public static final MaidCraftOrder KEY = new MaidCraftOrder();

    /** 最多同时挂几张单（不同产物各算一张） */
    public static final int MAX_ORDERS = 16;
    /** 一张单最多做多少个：再多就该让女仆去干别的了 */
    public static final int MAX_COUNT = 256;
    private static final String ORDERS_TAG = "orders";
    private static final String PRODUCT_TAG = "product";
    private static final String COUNT_TAG = "count";
    private static final String WARNED_TAG = "warned";
    private static final String LINEAGE_TAG = "lineage";

    private MaidCraftOrder() {
    }

    /**
     * 一张单。
     *
     * @param product   做什么
     * @param remaining 还剩几个
     * @param warned    缺料这件事说过了没有（同一张单只烦主人一次）
     * @param lineage   **来路**：这张单是"为了让什么能做下去"才插进来的，从最外面那张根单
     *                  一路排到它的上一层（主人亲自下的单是空的）。挡循环配方靠它，
     *                  见 {@link #insertFirst}
     */
    public record Order(ItemStack product, int remaining, boolean warned, List<ItemStack> lineage) {

        public Order withRemaining(int remaining) {
            return new Order(product, remaining, warned, lineage);
        }

        public Order withWarned() {
            return new Order(product, remaining, true, lineage);
        }

        /** 最外面那张根单要做什么；主人亲自下的单返回 null */
        @Nullable
        public ItemStack root() {
            return lineage.isEmpty() ? null : lineage.get(0);
        }
    }

    // ------------------------------------------------------------------
    // 存档读写
    // ------------------------------------------------------------------

    @Override
    public ResourceLocation getKey() {
        return new ResourceLocation(BlueprintMod.MOD_ID, "craft_orders");
    }

    @Override
    public CompoundTag writeSaveData(List<Order> value) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Order order : value) {
            CompoundTag entry = new CompoundTag();
            entry.put(PRODUCT_TAG, order.product().copyWithCount(1).save(new CompoundTag()));
            entry.putInt(COUNT_TAG, order.remaining());
            entry.putBoolean(WARNED_TAG, order.warned());
            ListTag lineage = new ListTag();
            for (ItemStack ancestor : order.lineage()) {
                lineage.add(ancestor.copyWithCount(1).save(new CompoundTag()));
            }
            entry.put(LINEAGE_TAG, lineage);
            list.add(entry);
        }
        tag.put(ORDERS_TAG, list);
        return tag;
    }

    @Override
    public List<Order> readSaveData(CompoundTag tag) {
        ListTag list = tag.getList(ORDERS_TAG, Tag.TAG_COMPOUND);
        List<Order> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack product = ItemStack.of(entry.getCompound(PRODUCT_TAG));
            int remaining = entry.getInt(COUNT_TAG);
            if (product.isEmpty() || remaining <= 0) {
                continue;
            }
            // 层数缺省是 0：早先存的单没有这个字段，那都是主人亲自下的
            // 来路缺省是空：早先存的单没有这个字段，那都是主人亲自下的
            ListTag lineageTag = entry.getList(LINEAGE_TAG, Tag.TAG_COMPOUND);
            List<ItemStack> lineage = new ArrayList<>(lineageTag.size());
            for (int j = 0; j < lineageTag.size(); j++) {
                ItemStack ancestor = ItemStack.of(lineageTag.getCompound(j));
                if (!ancestor.isEmpty()) {
                    lineage.add(ancestor.copyWithCount(1));
                }
            }
            result.add(new Order(product.copyWithCount(1), remaining,
                    entry.getBoolean(WARNED_TAG), List.copyOf(lineage)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 读写入口
    // ------------------------------------------------------------------

    public static List<Order> pending(EntityMaid maid) {
        List<Order> data = maid.getData(KEY);
        return data == null ? List.of() : List.copyOf(data);
    }

    /** 还没做完的总个数，界面上给主人看个总数 */
    public static int pendingAmount(EntityMaid maid) {
        int total = 0;
        for (Order order : pending(maid)) {
            total += order.remaining();
        }
        return total;
    }

    /** 排在最前面那张单；没有待做返回 null */
    public static Order first(EntityMaid maid) {
        List<Order> orders = pending(maid);
        return orders.isEmpty() ? null : orders.get(0);
    }

    /**
     * 下单。
     * <p>
     * 同一产物的单是**加数量**，不新排一张——不然连点几下"做 1 个"，
     * 清单会变成一长串一模一样的条目。
     *
     * @return 实际加进去的数量（0 表示池子满了、或者这张单已经到上限）
     */
    public static int order(EntityMaid maid, ItemStack product, int count) {
        if (product.isEmpty() || count <= 0) {
            return 0;
        }
        // 停用的产物不接新单：她记得怎么做，但主人按了"先别做"。已经挂着的单不在这管，
        // 照常做完（见 MaidStudyPool.setDisabled 的说明）——"已在合成的不用管"。
        if (MaidStudyPool.isDisabled(maid, product)) {
            return 0;
        }
        ItemStack normalized = product.copyWithCount(1);
        List<Order> current = new ArrayList<>(pending(maid));
        for (int i = 0; i < current.size(); i++) {
            Order order = current.get(i);
            if (!ItemStack.matches(order.product(), normalized)) {
                continue;
            }
            int room = MAX_COUNT - order.remaining();
            if (room <= 0) {
                return 0;
            }
            int added = Math.min(room, count);
            current.set(i, order.withRemaining(order.remaining() + added));
            maid.setAndSyncData(KEY, current);
            return added;
        }

        if (current.size() >= MAX_ORDERS) {
            return 0;
        }
        int added = Math.min(MAX_COUNT, count);
        current.add(new Order(normalized, added, false, List.of()));
        maid.setAndSyncData(KEY, current);
        return added;
    }

    /**
     * 把一张单插到队首——**嵌套合成就是这么做的**：缺零件就把"先做这个零件"插到最前面，
     * 等她做完，原来那张单自然又有料了。
     * <p>
     * 不在这里递归调用合成：递归得自己管调用栈、超产和失败回滚，而插队只用这一份顺序表，
     * 每一步都写在明面上——主人还看得见"她接下来要做什么"。
     * <p>
     * <b>挡循环配方靠"来路"，不靠层数上限。</b> 层数是把错的尺子：AE2 的处理器那类东西
     * 正常就套四五层，按层数卡会把它一起卡死。真正无解的是**绕回自己**——
     * 所以只要求"这个零件不能出现在它自己的来路里"，正当的深套一路放行、
     * 循环的当场断掉，而且不用维护任何魔法数字。
     *
     * @param lineage 来路：从根单到上一层（见 {@link Order#lineage()}）
     * @return 插进去了返回 true；绕回了来路、这号产物本来就有单、或者队列满了，都返回 false
     */
    public static boolean insertFirst(EntityMaid maid, ItemStack product, int count, List<ItemStack> lineage) {
        if (product.isEmpty() || count <= 0) {
            return false;
        }
        ItemStack normalized = product.copyWithCount(1);
        for (ItemStack ancestor : lineage) {
            if (ItemStack.matches(ancestor, normalized)) {
                return false;
            }
        }
        List<Order> current = new ArrayList<>(pending(maid));
        for (Order order : current) {
            // 已经有这号产物的单了：等她做完那张，这个零件也就有了。
            // 顺带挡住"同一拍里被两个东家各插一次"这种重复劳动
            if (ItemStack.matches(order.product(), normalized)) {
                return false;
            }
        }
        if (current.size() >= MAX_ORDERS) {
            return false;
        }
        // 来路已经由调用方接好了（它才知道自己是谁），这里原样存下来
        current.add(0, new Order(normalized, Math.min(MAX_COUNT, count), false, lineage));
        maid.setAndSyncData(KEY, current);
        return true;
    }

    /**
     * 改掉排在最前面那张单（做好一个就减一，缺料说过了就打标记）。
     * <p>
     * 减到 0 就整张撤掉——一张"还剩 0 个"的单没有任何意义，留着只会挡住后面的。
     */
    public static void updateFirst(EntityMaid maid, Order updated) {
        List<Order> current = new ArrayList<>(pending(maid));
        if (current.isEmpty()) {
            return;
        }
        if (updated.remaining() <= 0) {
            current.remove(0);
        } else {
            current.set(0, updated);
        }
        maid.setAndSyncData(KEY, current);
    }

    /** 撤掉全部待做 */
    public static void clearAll(EntityMaid maid) {
        if (!pending(maid).isEmpty()) {
            maid.setAndSyncData(KEY, List.of());
        }
    }
}
