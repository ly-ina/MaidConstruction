package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「学习模式」工作模式：她跟在主人身边，把主人**亲手做出来**的东西记进
 * {@link MaidStudyPool 学习池}。
 * <p>
 * 走的是和「蓝图施工」同一套骨架（{@code IMaidTask} + 自建的 tick 驱动），
 * 好处是行为完全可控、不依赖女仆的 Brain 日程——那边一旦被日程挡住就会一动不动且没有报错。
 * 跟随与录入都在 {@link MaidStudyTickHandler} 里做。
 */
public class MaidStudyTask implements IMaidTask {

    public static final ResourceLocation UID = new ResourceLocation(BlueprintMod.MOD_ID, "study");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 模式按钮上显示的东西：合成台最直观（不用新画贴图） */
    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.CRAFTING_TABLE);
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    /**
     * 刻意返回空列表：跟随与录入都在服务端 tick 里直接驱动
     * （与「蓝图施工」同样的理由，见 {@link MaidStudyTickHandler}）。
     */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return List.of();
    }

    /** 保持正常走动与受惊反应：她本来就得跟着主人跑 */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return true;
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return true;
    }

    @Override
    public MutableComponent getName() {
        return Component.translatable("task.blueprint.study");
    }

    @Override
    public List<String> getDescription(EntityMaid maid) {
        return List.of("task.blueprint.study.desc");
    }
}
