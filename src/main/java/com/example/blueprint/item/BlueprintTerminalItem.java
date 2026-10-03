package com.example.blueprint.item;

import com.example.blueprint.block.CommandPostBlockEntity;
import com.example.blueprint.client.BlueprintScreenOpener;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SBindCommandPostPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 蓝图终端：把 {@code blueprints} 目录里的建筑当图纸库翻。
 * <p>
 * 与蓝图本身分开的理由：蓝图是"手上的那一张图"（录制、定位、交给女仆都围着它转），
 * 而终端看的是**目录**——浏览、比较、把某一份取到手上。两件事的生命周期不一样，
 * 硬塞进同一个物品只会让蓝图面板越来越长。
 * <p>
 * 界面只在客户端开（和蓝图面板同一条路：{@link DistExecutor} 挡在服务端之外），
 * 所以没装服务端也能翻自己的图纸目录。
 * <p>
 * <b>不做发光</b>：物品没有"照亮度"这回事（它不在世界里当方块，vanilla 与 Forge 都没这个口子），
 * 而附魔光效是给附魔物品用的——挂在一台普通设备上看着像被附魔过，不如不挂。
 * 它的"待机感"来自贴图本身（屏幕那块是画成亮着的，见 tools/make_command_post_textures.py）。
 */
public class BlueprintTerminalItem extends Item {

    public BlueprintTerminalItem(Properties properties) {
        super(properties);
    }

    /**
     * 拿终端右键**指挥台**：绑定（潜行 = 解绑）。
     * <p>
     * 为什么用 {@code onItemUseFirst} 而不是 {@code useOn}：它跑在方块自己处理之前，
     * 于是绑定这一下不会顺带把指挥台的界面也打开（与蓝图抢右键是同一个道理）。
     * <p>
     * 两端都返回 {@code SUCCESS} 且**判定条件完全一样**：只要点到的是指挥台就吃掉这一下。
     * 让客户端按"有没有绑过"决定放不放会给"一边拦下一边放过去"，表现就是界面时开时不开——
     * 看状态、给反馈这些事交给服务端回话（`message.blueprint.post.*`）。
     * <p>
     * 想开界面就空手右键：方块自己的 {@code use} 一直在那儿，终端不越权。
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof CommandPostBlockEntity)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            ModNetwork.CHANNEL.sendToServer(new C2SBindCommandPostPacket(pos, player.isShiftKeyDown()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // 用 DistExecutor 包一层，服务端不会去加载客户端的界面类
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BlueprintScreenOpener.openLibrary());
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.blueprint.terminal"));
    }
}
