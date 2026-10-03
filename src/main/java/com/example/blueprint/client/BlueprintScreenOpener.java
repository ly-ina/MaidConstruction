package com.example.blueprint.client;

import com.example.blueprint.client.gui.BlueprintLibraryScreen;
import com.example.blueprint.client.gui.CommandPostScreen;
import net.minecraft.core.BlockPos;
import com.example.blueprint.client.gui.BlueprintMaterialsScreen;
import com.example.blueprint.client.gui.BlueprintScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 打开蓝图面板的客户端入口。
 * <p>
 * 单独放一个类是有原因的：BlueprintItem 在服务端也会加载，
 * 如果直接在里面 new BlueprintScreen，服务端会因为找不到客户端类而崩。
 * 这里配合 DistExecutor 用，保证只有客户端才会去加载它。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintScreenOpener {

    public static void open() {
        Minecraft.getInstance().setScreen(new BlueprintScreen());
    }

    /**
     * 打开蓝图终端（图纸库）。
     * <p>
     * 与蓝图面板分开是因为数据来源不同：面板围着"手上那张图"转（要问服务端拿结构），
     * 而图纸库只读客户端的 {@code blueprints} 目录——所以它连服务端都不用惊动。
     */
    public static void openLibrary() {
        Minecraft.getInstance().setScreen(new BlueprintLibraryScreen());
    }

    /**
     * 打开某台指挥台。
     * <p>
     * 界面自己每帧去读那块方块实体，所以这里不需要把状态传进来——服务端在右键那一刻
     * 已经补发过一份（见 {@code CommandPostBlock.use}）。
     */
    public static void openCommandPost(BlockPos pos) {
        Minecraft.getInstance().setScreen(new CommandPostScreen(pos));
    }

    /**
     * 结构数据刚到手（录制完成，或者导入回包）：正开着的蓝图界面当场刷新一次。
     * <p>
     * 走通知而不是让界面每帧去查物品 NBT：那样一次要读复合标签、现造一个 {@code UUID}，
     * 面板开着时每秒六十次纯属白做；而"数据什么时候到"只有服务端知道，到了叫一声最准。
     * <p>
     * 界面上什么都没开时这里就是个空转——大多数录制都发生在世界里，那才是常态。
     */
    public static void schematicArrived(UUID id) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof BlueprintScreen blueprint) {
            blueprint.onSchematicArrived(id);
        } else if (screen instanceof BlueprintMaterialsScreen materials) {
            materials.onSchematicArrived();
        }
    }
}
