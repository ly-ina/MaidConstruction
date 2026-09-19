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
import java.util.Arrays;
import java.util.List;

/**
 * 女仆的**学习池**：她"会做"的东西，以及**每样东西她见过的那几个配方**。
 * <p>
 * <b>展示按产物，存储按配方。</b> 这两件事分开是有原因的：主人演示的是"怎么做"，
 * 而一样产物完全可能有**好几个配方**（换个材料、换个摆法各算一个配方）。
 * 只记产物的话，"她到底会哪一种做法"就丢了——主人想指定用哪个也没处可指。
 * <p>
 * 所以池子的结构是：一样产物一条记录，记录里挂着一串配方，
 * <b>表里第 0 个就是主人指定的优先配方</b>（{@link #promote} 把它挪到最前），
 * 女仆手搓时照它做。界面上只画产物；配方多于一个的产物会高亮出来，
 * 提醒主人"这个有好几种做法，挑一个吧"。
 * <p>
 * 每个配方存两样东西：
 * <ul>
 *   <li><b>配方 id</b>（数据包写的那个身份，{@code minecraft:torch}）——她是照着它做的；</li>
 *   <li><b>当时那 3×3 的摆法</b>——配方 id 可能因为数据包被换掉而查不到，
 *       摆法是她亲眼看见的东西，留着永远读得出来。</li>
 * </ul>
 * 存的是 TLM 的 {@code TaskDataKey}（存在她自己的数据里，随存档走、自动同步到客户端，
 * 所以界面直接读她身上的数据就行，不用另外下发）。
 */
public final class MaidStudyPool implements TaskDataKey<List<MaidStudyPool.Learned>> {

    /** 注册给 TLM 的那把钥匙，也是全局唯一的读写入口 */
    public static final MaidStudyPool KEY = new MaidStudyPool();

    /**
     * 同一样产物最多记几个配方。
     * <p>
     * 有这个上限是因为"摆法"是有限度的：主人如果一直用材料换着摆，
     * 一个产物能攒出几十条几乎一样的记录，界面就没法看了。够挑就行。
     */
    public static final int MAX_RECIPES_PER_PRODUCT = 8;

    /** 界面上那排小格用的是 3×3，2×2 的也摆进左上角，这样两类配方共用一套存储 */
    public static final int GRID_SIZE = 9;

    private static final String ITEMS_TAG = "items";
    private static final String PRODUCT_TAG = "product";
    private static final String RECIPES_TAG = "recipes";
    private static final String RECIPE_ID_TAG = "id";
    private static final String GRID_TAG = "grid";
    /** 主人点名的那条做法的下标 */
    private static final String SELECTED_TAG = "selected";

    private MaidStudyPool() {
    }

    // ------------------------------------------------------------------
    // 数据形状
    // ------------------------------------------------------------------

    /**
     * 她见过的一个配方。
     *
     * @param id   配方身份；为 null 表示当时没能反查到（少见，多数是模组自己处理的合成）
     * @param grid 演示时的 3×3 摆法（固定 9 格，空格用空栈占位）
     */
    public record Recipe(@Nullable ResourceLocation id, List<ItemStack> grid) {

        /** 界面上给主人看的一行字：优先认配方 id，没有 id 就退回按摆法描述 */
        public String displayId() {
            return id == null ? "" : id.toString();
        }
    }

    /**
     * 一样产物 + 她会做的几个配方。
     * <p>
     * <b>做法是"选"出来的，不是"排"出来的。</b> {@code selected} 就是主人点名的那一条，
     * 她手搓时只照它做；其余的做法只是备选，点一下就换过来，不存在"自动顺位"。
     * <p>
     * 早先那版是"把选中的挪到最前，第 0 个就是优先"——那等于每次改选择都重排一次列表，
     * 顺序一直在动，主人反而记不住自己选的到底是哪条。改成一个显式下标之后，
     * 列表顺序（学会的先后）稳定不动，选中的那条在界面上高亮出来。
     */
    public record Learned(ItemStack product, List<Recipe> recipes, int selected) {

        public boolean hasMultipleRecipes() {
            return recipes.size() > 1;
        }

        /**
         * 她真会照做的那条配方在表里的位置；一条配方都没有（老数据）返回 -1。
         * <p>
         * 下标越界就退回 0：老存档里没有 {@code selected} 这个字段，读回来正是 0；
         * 万一是坏数据写了个离谱的数，也不能让她空手站着。
         */
        public int chosenIndex() {
            if (recipes.isEmpty()) {
                return -1;
            }
            return selected >= 0 && selected < recipes.size() ? selected : 0;
        }

        /** 她真会照做的那条配方；一条配方都没有返回 null */
        @Nullable
        public Recipe chosen() {
            int index = chosenIndex();
            return index < 0 ? null : recipes.get(index);
        }
    }

    // ------------------------------------------------------------------
    // 存档读写
    // ------------------------------------------------------------------

    @Override
    public ResourceLocation getKey() {
        return new ResourceLocation(BlueprintMod.MOD_ID, "study_pool");
    }

    @Override
    public CompoundTag writeSaveData(List<Learned> value) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Learned learned : value) {
            CompoundTag entry = new CompoundTag();
            entry.put(PRODUCT_TAG, learned.product().copyWithCount(1).save(new CompoundTag()));

            ListTag recipes = new ListTag();
            for (Recipe recipe : learned.recipes()) {
                CompoundTag recipeTag = new CompoundTag();
                if (recipe.id() != null) {
                    recipeTag.putString(RECIPE_ID_TAG, recipe.id().toString());
                }
                ListTag grid = new ListTag();
                for (ItemStack stack : recipe.grid()) {
                    // 空格子存成空 compound：ItemStack.of(空 tag) 拿回来的正是空栈
                    grid.add(stack.isEmpty() ? new CompoundTag() : stack.copyWithCount(1).save(new CompoundTag()));
                }
                recipeTag.put(GRID_TAG, grid);
                recipes.add(recipeTag);
            }
            entry.put(RECIPES_TAG, recipes);
            entry.putInt(SELECTED_TAG, learned.selected());
            list.add(entry);
        }
        tag.put(ITEMS_TAG, list);
        return tag;
    }

    @Override
    public List<Learned> readSaveData(CompoundTag tag) {
        ListTag list = tag.getList(ITEMS_TAG, Tag.TAG_COMPOUND);
        List<Learned> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            // 老格式（只存产物的那版）整个条目就是一件产物，没有 product/recipes 这两层
            ItemStack product = ItemStack.of(entry.contains(PRODUCT_TAG)
                    ? entry.getCompound(PRODUCT_TAG) : entry);
            if (product.isEmpty()) {
                continue;
            }
            List<Recipe> recipes = new ArrayList<>();
            ListTag recipeList = entry.getList(RECIPES_TAG, Tag.TAG_COMPOUND);
            for (int j = 0; j < recipeList.size(); j++) {
                recipes.add(readRecipe(recipeList.getCompound(j)));
            }
            // 老存档没有 selected：读回来是 0，正好等于"她一直照第 1 条做"的旧行为
            result.add(new Learned(product.copyWithCount(1), List.copyOf(recipes),
                    entry.getInt(SELECTED_TAG)));
        }
        return result;
    }

    private static Recipe readRecipe(CompoundTag tag) {
        ResourceLocation id = tag.contains(RECIPE_ID_TAG)
                ? ResourceLocation.tryParse(tag.getString(RECIPE_ID_TAG)) : null;
        ListTag gridTag = tag.getList(GRID_TAG, Tag.TAG_COMPOUND);
        List<ItemStack> grid = new ArrayList<>(GRID_SIZE);
        for (int i = 0; i < gridTag.size(); i++) {
            grid.add(ItemStack.of(gridTag.getCompound(i)));
        }
        return new Recipe(id, normalizeGrid(grid));
    }

    // ------------------------------------------------------------------
    // 读写入口
    // ------------------------------------------------------------------

    /** 她会做的东西。没学过就是空表（TLM 在没写过数据时返回 null） */
    public static List<Learned> known(EntityMaid maid) {
        List<Learned> data = maid.getData(KEY);
        return data == null ? List.of() : List.copyOf(data);
    }

    /** 她会做这个产物吗（不看配方，只看产物） */
    public static boolean knows(EntityMaid maid, ItemStack product) {
        if (product.isEmpty()) {
            return false;
        }
        for (Learned learned : known(maid)) {
            // 按 NBT 全比：同名但不同变体（附魔书之类）算两样东西
            if (ItemStack.matches(learned.product(), product)) {
                return true;
            }
        }
        return false;
    }

    /** 这个产物她见过几个配方 */
    public static int recipeCount(EntityMaid maid, ItemStack product) {
        for (Learned learned : known(maid)) {
            if (ItemStack.matches(learned.product(), product)) {
                return learned.recipes().size();
            }
        }
        return 0;
    }

    /**
     * 把一次演示记进她的池子：产物 + 这次用的配方。
     * <p>
     * 配方按**身份**去重（没有 id 就按摆法），所以同一个配方反复演示只算一次；
     * 换个材料、换个摆法就是新的一条，往后排（优先级最低）。
     * <p>
     * <b>认不出配方时传 null，只记产物。</b> 别塞一条"没有 id、摆法全空"的假配方进去：
     * 那玩意在界面上就是一行"认不出的配方"，占着位置还挡着主人重演示一遍。
     * 只记产物的话，界面会老老实实说"只见过产物，没见过做法"。
     *
     * @param recipe 这次用的配方；认不出来给 null
     * @return 学到**新东西**才返回 true（新产物、或老产物的新配方）——她叫不叫就看这个
     */
    public static boolean learn(EntityMaid maid, ItemStack product, @Nullable Recipe recipe) {
        if (product.isEmpty()) {
            return false;
        }
        ItemStack normalized = product.copyWithCount(1);
        // 再兜一道：id 没有、摆法也全是空的，等于什么都没看出来
        Recipe usable = recipe == null || isEmpty(recipe) ? null : recipe;

        List<Learned> current = new ArrayList<>(known(maid));
        for (int i = 0; i < current.size(); i++) {
            Learned learned = current.get(i);
            if (!ItemStack.matches(learned.product(), normalized)) {
                continue;
            }
            // 产物已经记过、这次又没看出新配方：不算新东西
            if (usable == null || hasRecipe(learned.recipes(), usable)
                    || learned.recipes().size() >= MAX_RECIPES_PER_PRODUCT) {
                return false;
            }
            List<Recipe> recipes = new ArrayList<>(learned.recipes());
            recipes.add(usable);
            // 学到"新做法"不动主人已有的选择：做法是加在列表后面的，下标不会错位
            current.set(i, new Learned(learned.product(), List.copyOf(recipes), learned.selected()));
            maid.setAndSyncData(KEY, current);
            return true;
        }

        current.add(new Learned(normalized, usable == null ? List.of() : List.of(usable), 0));
        maid.setAndSyncData(KEY, current);
        return true;
    }

    /** 这条配方是不是"什么都没看出来"（没 id，摆法也全空） */
    private static boolean isEmpty(Recipe recipe) {
        if (recipe.id() != null) {
            return false;
        }
        for (ItemStack stack : recipe.grid()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 点名一样产物该用**哪一条**做法（这就是"选择法"：不重排列表，只记一个下标）。
     * <p>
     * 早先那版是"把选中的挪到最前"——不用额外兜底状态是它的好处，代价是列表顺序
     * 一直在变，主人反而记不住自己选的到底是哪条。现在列表顺序固定（学会的先后），
     * 选中的下标单独存，界面上直接高亮那一条。
     *
     * @return 真的换了选择才返回 true（选的是同一条、或者下标越界都返回 false）
     */
    public static boolean select(EntityMaid maid, int productIndex, int recipeIndex) {
        List<Learned> current = new ArrayList<>(known(maid));
        if (productIndex < 0 || productIndex >= current.size()) {
            return false;
        }
        Learned learned = current.get(productIndex);
        if (recipeIndex < 0 || recipeIndex >= learned.recipes().size()
                || learned.chosenIndex() == recipeIndex) {
            return false;
        }
        current.set(productIndex,
                new Learned(learned.product(), learned.recipes(), recipeIndex));
        maid.setAndSyncData(KEY, current);
        return true;
    }

    /** 忘掉一样（连同它的所有配方）。真的忘掉了返回 true */
    public static boolean forget(EntityMaid maid, ItemStack product) {
        List<Learned> current = new ArrayList<>(known(maid));
        boolean removed = current.removeIf(learned -> ItemStack.matches(learned.product(), product));
        if (removed) {
            maid.setAndSyncData(KEY, current);
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 两个配方算不算同一个：都有 id 就按 id 比，否则按摆法比 */
    private static boolean hasRecipe(List<Recipe> recipes, Recipe candidate) {
        for (Recipe recipe : recipes) {
            if (recipe.id() != null && candidate.id() != null) {
                if (recipe.id().equals(candidate.id())) {
                    return true;
                }
            } else if (sameGrid(recipe.grid(), candidate.grid())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameGrid(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 一律补成 3×3（9 格）、每格只留 1 个：池子记的是"怎么摆"，不是留了多少材料 */
    private static List<ItemStack> normalizeGrid(List<ItemStack> grid) {
        ItemStack[] slots = new ItemStack[GRID_SIZE];
        Arrays.fill(slots, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(grid.size(), GRID_SIZE); i++) {
            ItemStack stack = grid.get(i);
            slots[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        }
        return List.of(slots);
    }
}
