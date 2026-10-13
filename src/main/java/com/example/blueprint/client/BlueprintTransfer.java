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
 * 文件名就是蓝图名（例如「城堡.maidbp」），不加时间戳之类的后缀，
 * 想留档就自己改名字。文件可以直接拷给别人，放进同一个目录就能导入。
 * <p>
 * 目录是 {@code <游戏目录>/maidconstruction/blueprints/}：模组 id 换成 {@code maidconstruction}
 * 之后（见 DEVELOPER §7.21），图纸也跟着搬了家——原来那个光秃秃的 {@code blueprints/}
 * 谁都能用，撞名只是时间问题。老目录第一次访问时会**自动搬一次**（见 {@link #migrateLegacy}）。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintTransfer {

    /**
     * 图纸文件的后缀。
     * <p>
     * 1.8.3 及以前是 {@code .blueprint}——modid 换了名字之后跟着改：文件后缀里留着旧名，
     * 翻目录的人只会更糊涂。
     */
    public static final String FILE_EXTENSION = ".maidbp";
    /**
     * 老后缀，列表里照样认（免得老文件在新列表里凭空消失），搬进新目录时会顺手改成新的。
     * <p>
     * 两个都在：{@code .blueprint} 是 1.8.3 及以前用的，{@code .blueprint.nbt} 是最早那个双后缀。
     * 长的写在前面：{@code .blueprint.nbt} 也以 {@code .nbt} 结尾，先比长的不容易配错。
     */
    private static final List<String> LEGACY_EXTENSIONS = List.of(".blueprint.nbt", ".blueprint");
    /** 单个文件再大就不往网络包里塞了 */
    public static final int MAX_FILE_BYTES = 1_000_000;

    /** 1.8.3 及以前的图纸目录：只在第一次访问时读一遍，把里面的图纸搬进新家 */
    private static final String LEGACY_DIRECTORY = "blueprints";
    /** 搬过了就不再去找老目录（每次开界面都去探一遍没必要） */
    private static boolean migrated;

    public static Path getExportDirectory() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("maidconstruction").resolve("blueprints");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        migrateLegacy(dir);
        return dir;
    }

    /**
     * 把老目录里的图纸搬进新家，只做一次。
     * <p>
     * 为什么不能只是改个目录名：老图纸会**一份都看不见**——那看起来就是"我的图纸丢了"。
     * 搬一次（顺手把后缀也换成新的）比留一个说不清的坑好。
     * <p>
     * 只认图纸后缀，他自己放进那个目录的别的东西一律不动；同名的**不搬**，以新目录里的为准，
     * 旧的留在原地由他处置——覆盖他的东西比多个重名更坏。
     */
    private static void migrateLegacy(Path dir) {
        if (migrated) {
            return;
        }
        migrated = true;
        Path old = Minecraft.getInstance().gameDirectory.toPath().resolve(LEGACY_DIRECTORY);
        if (!Files.isDirectory(old)) {
            return;
        }
        int moved = 0;
        try (Stream<Path> files = Files.list(old)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String extension = blueprintExtension(name);
                if (extension == null) {
                    continue;
                }
                Path target = dir.resolve(
                        name.substring(0, name.length() - extension.length()) + FILE_EXTENSION);
                if (Files.exists(target)) {
                    continue;
                }
                try {
                    Files.move(file, target);
                    moved++;
                } catch (IOException e) {
                    BlueprintMod.LOGGER.warn("老图纸没能搬进新目录：{}", file, e);
                }
            }
        } catch (IOException e) {
            BlueprintMod.LOGGER.warn("读不了老图纸目录 {}", old, e);
        }
        if (moved > 0) {
            BlueprintMod.LOGGER.info("把 {} 份老图纸从 {}/ 搬到了 {}", moved, LEGACY_DIRECTORY, dir);
        }
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
                        Component.translatable("message.maidconstruction.record.saved", file.getFileName().toString()),
                        true);
            }
        } catch (IOException e) {
            BlueprintMod.LOGGER.warn("图纸没能写进目录：{}", name, e);
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("message.maidconstruction.record.save_failed"), false);
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
        String extension = blueprintExtension(name);
        return extension == null ? name : name.substring(0, name.length() - extension.length());
    }

    private static boolean isBlueprintFile(Path path) {
        return blueprintExtension(path.getFileName().toString()) != null;
    }

    /** 它是图纸文件吗：是就返回那个后缀（新的或老的），不是则 null */
    private static String blueprintExtension(String fileName) {
        if (fileName.endsWith(FILE_EXTENSION)) {
            return FILE_EXTENSION;
        }
        for (String extension : LEGACY_EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }
}
