package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「工业模式」：主人在学习池界面里下的单，由她照单开工。
 * <p>
 * 为什么单独开一个模式，而不是让学习模式顺手做掉：这两件事的**条件不一样**。
 * 学习要她跟在主人身边看演示，做单要她去仓库取料、守着箱子干活；
 * 而且下单这件事本身就意味着"现在开始干活"——所以下单即上工（见
 * {@code C2SMaidCraftOrderPacket}），她不必先被主人手动拨到某个模式。
 * <p>
 * 跟「蓝图施工」一样，{@code createBrainTasks} 返回空列表、由
 * {@link MaidCraftTickHandler} 在服务端 tick 里直接驱动：这样她**会在原地踏实干活**，
 * 不会被 Brain 的随机溜达或跟人逻辑拽走（她要走去仓库取料，导航目标不能被反复重设）。
 * <p>
 * <b>只在工作时间干活</b>（见 {@link #isWorkingTime}）：作息是主人设的
 * （白天／夜晚／全天），到了她的休息时段就该歇着——单子排着队不会跑，等她上班再做。
 */
public class MaidIndustryTask implements IMaidTask {

    public static final ResourceLocation UID = new ResourceLocation(BlueprintMod.MOD_ID, "industry");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 模式按钮上显示的东西：高炉最像"开工做东西"（不必新画贴图） */
    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.BLAST_FURNACE);
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    /**
     * 刻意返回空列表：做单不经过车万女仆的 Brain，而是由 {@link MaidCraftTickHandler}
     * 在服务端 tick 里直接驱动（与「蓝图施工」同样的理由）。
     */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return List.of();
    }

    /**
     * 开工时不要四处乱走：她有明确的去处（有料的那个箱子），
     * 随机溜达会把 {@code moveTo} 设好的导航目标顶掉——表现就是她一直在原地转圈。
     */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /** 同理：被吓到就跑开，等于把这一趟取料作废（与「蓝图施工」一致） */
    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    @Override
    public MutableComponent getName() {
        return Component.translatable("task.blueprint.industry");
    }

    @Override
    public List<String> getDescription(EntityMaid maid) {
        return List.of("task.blueprint.industry.desc");
    }

    // ------------------------------------------------------------------
    // 模式切换与作息
    // ------------------------------------------------------------------

    /** 她现在是不是在工业模式（认的是 uid，不认实例，省得两份实例对不上） */
    static boolean isIndustry(EntityMaid maid) {
        try {
            return maid.getTask() != null && UID.equals(maid.getTask().getUid());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 下单前她在哪个模式：这趟活干完要还回去（见 {@link #release}）。
     * <p>
     * 只是**临时状态**、不写进存档：它描述的是"这一次上工的来去"，
     * 跨存档留着没有意义（重启之后清单还在，她会继续做，做完了就停在工业模式——
     * 主人一眼看得出来，随手切一下即可，不值得为它多存一份数据）。
     */
    private static final Map<UUID, ResourceLocation> RETURN_TO = new HashMap<>();

    /**
     * 叫她上工：记下她**现在**的模式，然后切到工业模式。
     * <p>
     * 已经在工业模式就什么都不做——尤其**不覆盖**已经记下的"干完回哪儿去"：
     * 连着下好几单时，要还的是**第一单之前**那个模式，不是"上一单时的工业模式"。
     */
    public static void employ(EntityMaid maid) {
        if (isIndustry(maid)) {
            return;
        }
        IMaidTask previous = maid.getTask();
        if (previous != null && !UID.equals(previous.getUid())) {
            RETURN_TO.put(maid.getUUID(), previous.getUid());
        }
        TaskManager.findTask(UID).ifPresent(maid::setTask);
    }

    /**
     * 活干完了（待做清单空了）：把她**还回下单之前的那个模式**。
     * <p>
     * 没记过就什么都不做：那说明她是主人自己拨到工业模式的，她的模式该由主人说了算，
     * 不该被这里顺手改掉。
     */
    public static void release(EntityMaid maid) {
        ResourceLocation back = RETURN_TO.remove(maid.getUUID());
        if (back != null) {
            TaskManager.findTask(back).ifPresent(maid::setTask);
        }
    }

    /**
     * 她的作息现在是不是"上班时间"。
     * <p>
     * 用的是 TLM 自己那套：作息表（白天／夜晚／全天）在当下这个时刻对应哪个活动，
     * 是 {@code Activity.WORK} 就是工作时间。不自己按时刻算时段——那样等于把
     * TLM 的三张作息表在模组里抄一遍，它改了我们就错。
     * <p>
     * 查不出来时**放行**（返回 true）：宁可她在休息时段里多干一点，也好过
     * "下了单她一动不动、还不报错"——那种最难查。
     */
    public static boolean isWorkingTime(EntityMaid maid) {
        try {
            return Activity.WORK.equals(maid.getScheduleDetail());
        } catch (Throwable t) {
            return true;
        }
    }
}
