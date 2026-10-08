package com.example.blueprint.item;

import com.example.blueprint.client.ManualScreenOpener;
import com.example.blueprint.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 女仆建筑说明书：右键打开**自己的界面**（左目录可点、右正文）。
 * <p>
 * 它曾经是一本**原版成书**——好处是白拿了原版的翻页、书的样子、"掉进岩浆里的书"那一套；
 * 坏处正是这一版要换掉它的原因：**书页是纯文本，原版的书界面不处理点击**，所以做不出
 * 可点击的目录。现在内容全部由 {@link #PAGES} 指向语言文件，界面现读现翻。
 * <p>
 * 这么一换还顺手解决了两件事：**改文案不用重发书**（原版成书的正文是合成那一刻复制进
 * 物品 NBT 的，改语言文件对已经发出去的书没用），以及**旧书不会过期**——
 * 说明书从此不带任何数据，`new ItemStack(MANUAL)` 就是一本完整的说明书。
 */
public class ManualItem extends Item {

    /**
     * 目录里的章，一条一章，顺序就是左侧列表的顺序。
     * <p>
     * 正文在 {@code assets/blueprint/lang/*.json} 里：改内容只动语言文件，
     * 加一章就往这个数组里加一条（列表里的名字取正文第一行【…】里那段，不必另写标题）。
     */
    public static final String[] PAGES = {
            "manual.blueprint.page1",
            "manual.blueprint.page2",
            "manual.blueprint.page3",
            "manual.blueprint.page4",
            "manual.blueprint.page5",
            "manual.blueprint.page6",
            "manual.blueprint.page7",
            "manual.blueprint.page8",
            "manual.blueprint.page9",
            "manual.blueprint.page10",
            "manual.blueprint.page11",
            "manual.blueprint.page12",
            "manual.blueprint.page13",
            "manual.blueprint.page14",
            "manual.blueprint.page15",
    };

    public ManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // DistExecutor 包一层：服务端不会去加载客户端的界面类（与蓝图终端同一条规矩）
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ManualScreenOpener::open);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.blueprint.manual"));
    }

    /** 一本说明书；它不存任何数据，所以直接给默认实例即可 */
    public static ItemStack create() {
        return new ItemStack(ModItems.MANUAL.get());
    }
}
