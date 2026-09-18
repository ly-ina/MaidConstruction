package com.example.blueprint.integration.maid;

import com.example.blueprint.integration.ae2.Ae2Compat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「学习模式」的服务端驱动：**跟随**与**录入**。
 * <p>
 * 为什么不用女仆自己的 Brain：日程阶段、闲逛、受惊这些一叠加，女仆经常会一动不动且没有报错
 * （「蓝图施工」踩过这个坑，见 {@code MaidBuildTickHandler} 的说明）。这里在服务端 tick 里
 * 主动找"正在学习模式的女仆"，行为完全可控。
 * <p>
 * 录入只认**主人自己**动手做的东西：别人在旁边合成不会污染她的池子。
 */
public class MaidStudyTickHandler {

    /** 主人离她超过这个距离就跟上去 */
    private static final double FOLLOW_DISTANCE = 3.5D;
    private static final double FOLLOW_DISTANCE_SQR = FOLLOW_DISTANCE * FOLLOW_DISTANCE;
    /** 录入的观察半径：主人合成时她得在这附近（不然不算"看了一遍"） */
    private static final double STUDY_RADIUS = 8.0D;
    private static final double WALK_SPEED = 1.0D;

    /**
     * 学会之后**叫两声**：第一声立刻，第二声隔十几 tick。
     * <p>
     * 键是女仆的 UUID，值是"还差几 tick 叫第二声"。用 TLM 自己的
     * {@code tryPlayMaidPickupSound()}——那是"捡到东西高兴一下"的音，**跟着她自己的声音包走**，
     * 我们不必硬塞一个音效进去（玩家换了声音包也应该跟着变）。
     */
    private static final Map<UUID, Integer> SECOND_CHIRP = new HashMap<>();
    private static final int SECOND_CHIRP_DELAY = 12;

    private MaidStudyTickHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof EntityMaid maid && isStudyTask(maid)) {
                // 手上有单要做的时候不跟人：她可能正走去仓库取料，而"跟上去"每 tick
                // 都会重设一次导航目标，把她往主人那边拽——表现就是她原地打转、永远到不了仓库。
                // 附近的箱子几步就到，所以这个坑只在绑定的远程仓库上才看得出来
                if (MaidCraftOrder.first(maid) == null) {
                    followOwner(maid);
                }
            }
        }
        tickSecondChirp(level);
    }

    /** 到点的"第二声"。她要是已经不在了就静静作废，不必特意去播 */
    private static void tickSecondChirp(ServerLevel level) {
        if (SECOND_CHIRP.isEmpty()) {
            return;
        }
        SECOND_CHIRP.entrySet().removeIf(entry -> {
            if (entry.getValue() > 1) {
                entry.setValue(entry.getValue() - 1);
                return false;
            }
            if (level.getEntity(entry.getKey()) instanceof EntityMaid maid) {
                maid.tryPlayMaidPickupSound();
            }
            return true;
        });
    }

    /** 她是不是正在「学习模式」（手搓那边也用这个判据：单是下给学习模式的） */
    static boolean isStudyTask(EntityMaid maid) {
        try {
            return maid.getTask() != null && MaidStudyTask.UID.equals(maid.getTask().getUid());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跟上去：太远才动，近了就停（免得在她脚边反复抽搐） */
    private static void followOwner(EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (owner == null || !owner.isAlive() || owner.level() != maid.level()) {
            return;
        }
        if (maid.distanceToSqr(owner) <= FOLLOW_DISTANCE_SQR) {
            maid.getNavigation().stop();
            return;
        }
        maid.getNavigation().moveTo(owner, WALK_SPEED);
    }

    /**
     * 主人**亲手合成完成**的那一刻：附近正在学习模式的女仆把"产物 + 这次是怎么做的"记进池子。
     * <p>
     * {@code ItemCraftedEvent} 对工作台和背包里的 2×2 都会触发，所以"随手搓一个"也算演示。
     * <p>
     * <b>为什么这时候还读得到配方与摆法</b>：这个事件是在 {@code ResultSlot#onTake} 的
     * <b>第一步</b>（{@code checkTakeAchievements}）里发出来的，之后才轮到"逐格扣减材料"，
     * 所以此刻合成格还是满的——摆法和配方都得**当场**取，晚一步就只剩产物了。
     */
    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player crafter = event.getEntity();
        if (crafter == null || crafter.level().isClientSide) {
            return;
        }
        ItemStack product = event.getCrafting();
        if (product.isEmpty()) {
            return;
        }
        Level level = crafter.level();
        // 一次演示 = 一份产物 + 一个配方；同一次合成所有女仆共用这两份
        MaidStudyPool.Recipe recipe = recipeOf(crafter, event.getInventory(), level, product);
        for (EntityMaid maid : crafter.level().getEntitiesOfClass(EntityMaid.class,
                crafter.getBoundingBox().inflate(STUDY_RADIUS))) {
            if (!isStudyTask(maid) || maid.getOwner() != crafter) {
                continue;
            }
            if (MaidStudyPool.learn(maid, product, recipe)) {
                // 反馈是"她高兴一下"，不是往聊天栏塞一句话：
                // 第一声现在就叫，第二声交给 tick 里那本账。
                // 学到"同一样东西的新做法"也算新东西，所以这里也会叫
                maid.tryPlayMaidPickupSound();
                SECOND_CHIRP.put(maid.getUUID(), SECOND_CHIRP_DELAY);
            }
        }
    }

    /**
     * "这次是怎么做的"。三步，前面的够用就不走后面：
     * <ol>
     *   <li><b>先信事件自己带出来的那个合成容器</b>（{@code getInventory()}）——
     *       原版工作台、背包 2×2 给的都是货真价实的 {@code CraftingContainer}，
     *       容器大小还顺带告诉了我们配方是几乘几。原先我去猜 {@code containerMenu}
     *       是什么类型，那是绕远路：谁合成的，容器就在谁手里；</li>
     *   <li>模组自己的合成路径：AE2 的合成终端把网格包了一层适配器，外面看不出是合成格，
     *       只能反过来问界面"你现在匹配的是哪个配方"（见 {@link Ae2Compat#captureCraftingRecipe}）；</li>
     *   <li>最后的兜底：拿产物在配方表里反查，**只有唯一一条匹配**才算数。</li>
     * </ol>
     * 三步都问不出来就返回 null——池子那边会只记产物，不编造一条认不出的配方。
     */
    @Nullable
    private static MaidStudyPool.Recipe recipeOf(Player crafter, Container matrix, Level level,
                                                 ItemStack product) {
        if (matrix instanceof CraftingContainer container) {
            return new MaidStudyPool.Recipe(recipeIdOf(level, container), gridOf(container));
        }
        MaidStudyPool.Recipe fromTerminal = Ae2Compat.captureCraftingRecipe(crafter.containerMenu);
        if (fromTerminal != null) {
            return fromTerminal;
        }
        return level instanceof ServerLevel serverLevel
                ? StudyRecipeCapture.uniqueFor(serverLevel, product) : null;
    }

    /** 这次摆的是哪个配方（1.20.1 的 id 就在配方自己身上，RecipeHolder 那个包装是 1.20.2 才有的） */
    @Nullable
    private static ResourceLocation recipeIdOf(Level level, CraftingContainer container) {
        return level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, container, level)
                .map(Recipe::getId)
                .orElse(null);
    }

    /** 事件里那把格子的摆法，一律补成 3×3 存（2×2 的摆在左上角） */
    private static List<ItemStack> gridOf(CraftingContainer container) {
        ItemStack[] slots = new ItemStack[MaidStudyPool.GRID_SIZE];
        Arrays.fill(slots, ItemStack.EMPTY);
        int width = container.getWidth();
        int height = container.getHeight();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * 3 + col;
                if (index >= MaidStudyPool.GRID_SIZE) {
                    continue;
                }
                slots[index] = container.getItem(row * width + col);
            }
        }
        return List.of(slots);
    }
}
