package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 蓝图的导入导出，一律走文件，不碰剪贴板。
 * <p>
 * 文件名就是蓝图名（例如「城堡.blueprint」），不加时间戳之类的后缀，
 * 想留档就自己改名字。文件可以直接拷给别人，放进同一个目录就能导入。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintTransfer {

    public static final String FILE_EXTENSION = ".blueprint";
    /** 早期版本用过双后缀，列表里也认，免得老文件看不见 */
    private static final String LEGACY_EXTENSION = ".blueprint.nbt";
    /** 单个文件再大就不往网络包里塞了 */
    public static final int MAX_FILE_BYTES = 1_000_000;

    public static Path getExportDirectory() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("blueprints");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /** 与 {@link Schematic#encode} 是同一份实现：文件与网络共用一种格式，只留一处定义 */
    public static byte[] encode(Schematic schematic) throws IOException {
        return Schematic.encode(schematic);
    }

    public static Schematic decode(byte[] bytes) throws IOException {
        return Schematic.decode(bytes);
    }

    /** 文件名直接跟图纸名对齐，同名就覆盖 */
    public static Path writeToFile(String name, byte[] data) throws IOException {
        Path file = getExportDirectory().resolve(sanitize(name) + FILE_EXTENSION);
        Files.write(file, data);
        return file;
    }

    /**
     * 服务端扫好的一份图纸，落到目录里（录制走的就是这条）。
     * <p>
     * 为什么是"服务端扫、客户端写"：扫描只有服务端做得准（客户端的方块实体 NBT 常常是残的），
     * 而文件只能写在**玩家自己这台机器**的游戏目录里——所以数据要在网络上来回一趟。
     * <p>
     * 同名覆盖：与面板里的导出同一条规矩；想留旧的那份就自己改名字。
     */
    public static void saveFromServer(String name, byte[] data) {
        Player player = Minecraft.getInstance().player;
        try {
            Path file = writeToFile(name, data);
            BlueprintLibrary.refresh();
            if (player != null) {
                // 录制完就在世界里（录制态没有界面），这句只能走动作栏
                player.displayClientMessage(
                        Component.translatable("message.blueprint.record.saved", file.getFileName().toString()),
                        true);
            }
        } catch (IOException e) {
            BlueprintMod.LOGGER.warn("图纸没能写进目录：{}", name, e);
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("message.blueprint.record.save_failed"), false);
            }
        }
    }

    /** 列出可供导入的文件，按名字排序 */
    public static List<Path> listFiles() {
        try (Stream<Path> stream = Files.list(getExportDirectory())) {
            return stream.filter(Files::isRegularFile)
                    .filter(BlueprintTransfer::isBlueprintFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 去掉文件名里的非法字符，空名字给个兜底 */
    public static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "blueprint";
        }
        return name.trim().replaceAll("[^\\w\\u4e00-\\u9fa5 -]", "_");
    }

    /** 显示名：去掉扩展名 */
    public static String displayName(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(LEGACY_EXTENSION)) {
            return name.substring(0, name.length() - LEGACY_EXTENSION.length());
        }
        if (name.endsWith(FILE_EXTENSION)) {
            return name.substring(0, name.length() - FILE_EXTENSION.length());
        }
        return name;
    }

    private static boolean isBlueprintFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(FILE_EXTENSION) || name.endsWith(LEGACY_EXTENSION);
    }
}
