package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 蓝图的导入导出，一律走文件，不碰剪贴板。
 * <p>
 * 文件放在游戏目录的 blueprints/ 下，压缩过的 NBT，
 * 可以直接拷给别人，对方放进同一目录就能导入。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintTransfer {

    public static final String FILE_EXTENSION = ".blueprint.nbt";
    /** 单个文件再大就不往网络包里塞了 */
    public static final int MAX_FILE_BYTES = 1_000_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static Path getExportDirectory() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("blueprints");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    public static byte[] encode(Schematic schematic) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(schematic.write(new CompoundTag()), out);
        return out.toByteArray();
    }

    public static Schematic decode(byte[] bytes) throws IOException {
        return Schematic.read(NbtIo.readCompressed(new ByteArrayInputStream(bytes)));
    }

    /** 写成一个带时间戳的文件，不覆盖已有导出 */
    public static Path writeToFile(String name, byte[] data) throws IOException {
        String safe = (name == null || name.isEmpty()) ? "blueprint" : name;
        safe = safe.replaceAll("[^\\w\\u4e00-\\u9fa5-]", "_");
        Path file = getExportDirectory().resolve(safe + "_" + STAMP.format(LocalDateTime.now()) + FILE_EXTENSION);
        Files.write(file, data);
        return file;
    }

    /** 列出可供导入的文件，按名字排序 */
    public static List<Path> listFiles() {
        try (Stream<Path> stream = Files.list(getExportDirectory())) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(FILE_EXTENSION))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }
}
