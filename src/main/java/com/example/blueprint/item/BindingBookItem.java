package com.example.blueprint.item;

import com.example.blueprint.client.BoundBlockHighlighter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 绑定书：把女仆的取料目标固定到某个容器上。
 * <p>
 * 用法是玩家拿着它右键一个容器完成绑定，然后把书放进女仆的饰品栏。
 * 女仆取料时先在身边搜索，搜不到才照这本书跑到远处去取，
 * 所以它相当于"备用仓库"的地址簿，而不是替代就近取材。
 * <p>
 * 绑定的是「坐标 + 维度 + 方块名称」而不是容器本身：容器被拆掉重放也能继续用，
 * 换成同位置的另一个容器同样有效。
 */
public class BindingBookItem extends Item {

    private static final String KEY_BINDING = "Binding";
    private static final String KEY_X = "X";
    private static final String KEY_Y = "Y";
    private static final String KEY_Z = "Z";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_BLOCK = "Block";

    public BindingBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();

        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }

        if (context.getPlayer().isShiftKeyDown()) {
            if (hasBinding(stack)) {
                clearBinding(stack);
                if (!level.isClientSide) {
                    context.getPlayer().displayClientMessage(
                            Component.translatable("message.blueprint.binding_cleared"), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!level.isClientSide) {
            // 顺带把方块的注册 ID 记下来。光有坐标的话，提示里只能显示一串数字，
            // 玩家根本认不出自己绑的是哪个箱子
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
            setBinding(stack, pos, level.dimension().location(), blockId);
            context.getPlayer().displayClientMessage(
                    Component.translatable("message.blueprint.binding_set", describe(stack)), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Shift + 右键空气：把绑定的位置用线框高亮出来。
     * <p>
     * 同"解除绑定"用的是同一个 Shift，只是目标一个在方块上、一个在空气里，不会撞车。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 只有按住 Shift 且确实绑过东西才响应，普通右键空气什么都不做
        if (!player.isShiftKeyDown() || !hasBinding(stack)) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide) {
            // 纯客户端效果，不必惊动服务端。
            // 包一层 DistExecutor，服务端就不会去加载那个客户端类。
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> BoundBlockHighlighter.highlight(player, stack));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.blueprint.binding_book").withStyle(ChatFormatting.GRAY));

        if (hasBinding(stack)) {
            tooltip.add(Component.translatable("tooltip.blueprint.binding_book_bound", describe(stack))
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.blueprint.binding_book_hint_highlight")
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("tooltip.blueprint.binding_book_hint_clear")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.blueprint.binding_book_unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("tooltip.blueprint.binding_book_hint_bind")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.blueprint.binding_book_bauble")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------
    // 绑定数据的读写
    // ------------------------------------------------------------------

    public static boolean hasBinding(ItemStack stack) {
        return bindingTag(stack) != null;
    }

    @Nullable
    private static CompoundTag bindingTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_BINDING, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        return tag.getCompound(KEY_BINDING);
    }

    @Nullable
    public static BlockPos getBoundPos(ItemStack stack) {
        CompoundTag binding = bindingTag(stack);
        return binding == null
                ? null
                : new BlockPos(binding.getInt(KEY_X), binding.getInt(KEY_Y), binding.getInt(KEY_Z));
    }

    @Nullable
    public static ResourceLocation getBoundDimension(ItemStack stack) {
        CompoundTag binding = bindingTag(stack);
        if (binding == null) {
            return null;
        }
        String dimension = binding.getString(KEY_DIMENSION);
        return dimension.isEmpty() ? null : ResourceLocation.tryParse(dimension);
    }

    public static void setBinding(ItemStack stack, BlockPos pos, ResourceLocation dimension,
                                  @Nullable ResourceLocation blockId) {
        CompoundTag binding = new CompoundTag();
        binding.putInt(KEY_X, pos.getX());
        binding.putInt(KEY_Y, pos.getY());
        binding.putInt(KEY_Z, pos.getZ());
        binding.putString(KEY_DIMENSION, dimension.toString());
        if (blockId != null) {
            binding.putString(KEY_BLOCK, blockId.toString());
        }
        stack.getOrCreateTag().put(KEY_BINDING, binding);
    }

    public static void clearBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(KEY_BINDING);
        }
    }

    /**
     * 绑定坐标处的方块名称。
     * <p>
     * 存的是注册 ID 而不是名字字符串，显示的时候才去查注册表——
     * 这样无论客户端用什么语言都能正确翻译，而不是把服务端当时那句外语写死进去。
     */
    public static Component getBoundBlockName(ItemStack stack) {
        CompoundTag binding = bindingTag(stack);
        if (binding == null || !binding.contains(KEY_BLOCK, CompoundTag.TAG_STRING)) {
            return Component.translatable("tooltip.blueprint.binding_book_unknown_block");
        }
        ResourceLocation id = ResourceLocation.tryParse(binding.getString(KEY_BLOCK));
        if (id == null) {
            return Component.translatable("tooltip.blueprint.binding_book_unknown_block");
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        return block == null
                ? Component.translatable("tooltip.blueprint.binding_book_unknown_block")
                : block.getName();
    }

    /** 给玩家看的绑定描述，形如 {@code 箱子（minecraft:overworld 12, 64, -3）} */
    public static Component describe(ItemStack stack) {
        BlockPos pos = getBoundPos(stack);
        if (pos == null) {
            return Component.literal("-");
        }
        ResourceLocation dimension = getBoundDimension(stack);
        String location = (dimension == null ? "?" : dimension.toString())
                + " " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        return Component.translatable("tooltip.blueprint.binding_book_format",
                getBoundBlockName(stack), location);
    }
}
