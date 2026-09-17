package com.example.blueprint.integration.maid;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.registry.ModItems;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

/**
 * "照着蓝图施工"工作模式。
 * <p>
 * 只有女仆主手拿着一张已锚定、且允许女仆施工的蓝图时，行为才会真正启动；
 * 否则这个模式就是空转，不会干扰其他工作。
 */
public class BlueprintBuildTask implements IMaidTask {

    public static final ResourceLocation UID = new ResourceLocation(BlueprintMod.MOD_ID, "build");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModItems.BLUEPRINT.get());
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    /**
     * 刻意返回空列表：施工不经过车万女仆的 Brain，
     * 而是由 {@link MaidBuildTickHandler} 在服务端 tick 里直接驱动，行为更可控。
     */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return List.of();
    }

    /**
     * 建造时不要四处乱走，也不要被惊吓打断，否则会一直在原地打转。
     */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    @Override
    public MutableComponent getName() {
        return Component.translatable("task.blueprint.build");
    }

    @Override
    public List<String> getDescription(EntityMaid maid) {
        return List.of("task.blueprint.build.desc");
    }

    /**
     * 施工范围按蓝图锚点来算，不然女仆会跑到结构另一头去取料。
     */
    @Override
    public AABB searchDimension(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (stack.getItem() instanceof BlueprintItem && BlueprintItem.hasAnchor(stack)) {
            var anchor = BlueprintItem.getAnchor(stack);
            if (anchor != null) {
                float radius = this.searchRadius(maid);
                return new AABB(anchor).inflate(radius, VERTICAL_SEARCH_RANGE, radius);
            }
        }
        return IMaidTask.super.searchDimension(maid);
    }
}
