package com.example.blueprint.client;

import com.example.blueprint.item.BlueprintItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 收到结构数据后，把 id / 名字 / 尺寸直接写进玩家手上的蓝图。
 * <p>
 * 为什么不等服务端同步物品 NBT：那条路要走容器槽位广播，跟自定义包不是同一个时机，
 * 面板很可能先拿到新结构、物品却还是旧的 id，于是预览一直不刷新。
 * 这里当场绑定，界面立刻就是对的。
 * <p>
 * 只在 id 真的不同时才动手，所以录制完的正常回流不会重复覆盖。
 */
@OnlyIn(Dist.CLIENT)
public class ClientBlueprintBinder {

    public static void bind(UUID id, String name, Vec3i size) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack stack = BlueprintItem.findHeld(player);
        if (stack.isEmpty() || id.equals(BlueprintItem.getSchematicId(stack))) {
            return;
        }

        BlueprintItem.setSchematic(stack, id, size, name);
        // 换了结构，旧的定位和朝向都不作数了
        BlueprintItem.clearSelection(stack);
        BlueprintItem.clearAnchor(stack);
        BlueprintItem.setRotation(stack, Rotation.NONE);
        BlueprintItem.setCompleted(stack, false);
    }
}
