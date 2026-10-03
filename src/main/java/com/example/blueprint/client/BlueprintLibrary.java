package com.example.blueprint.client;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.schematic.Schematic;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图纸库：把 {@code blueprints} 目录里的文件读成结构，供蓝图终端浏览。
 * <p>
 * 为什么要有个库：终端要把**目录里所有建筑**都摆出来（缩略图、名字、尺寸、材料），
 * 同一份文件会被反复用到好几帧。读盘与解析（gzip + palette 展开）在这里只做一次，
 * 界面只管拿结果。
 * <p>
 * 解析是**懒的**：列目录只拿文件名，谁真被画到（或点开详情）才读那一份——
 * 目录里躺着几十份图纸时，进界面那一刻不该把它们全读一遍。
 * <p>
 * 单个文件读不出来时**不把整页拖下水**：这条留在列表里并标成"读不出来"，
 * 其余的照常显示。图纸是玩家从各处拷来的，坏文件与更新版本的文件迟早会出现。
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintLibrary {

    /** 兜底重扫间隔：录完一张新图回到终端，不该还要手动刷新 */
    private static final long RESCAN_INTERVAL_MS = 5000L;

    /** 列表里的一份图纸 */
    public static final class Entry {

        private final Path path;
        private final String name;
        /** 建这一条时的文件标记（大小 + 修改时间）；属性读不出来时都是 0 */
        private final long size;
        private final long modified;
        /** 第几次建出来的这一条，见 {@link #cacheKey()} */
        private final long serial = nextSerial++;
        private boolean attempted;
        @Nullable
        private Schematic schematic;

        private Entry(Path path, String name) {
            this.path = path;
            this.name = name;

            long fileSize = 0L;
            long fileModified = 0L;
            try {
                fileSize = Files.size(path);
                fileModified = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                // 读不到属性就按"认不出来"处理：下次重扫会重新读一遍这一份，不会一直用旧结构
            }
            this.size = fileSize;
            this.modified = fileModified;
        }

        public Path path() {
            return path;
        }

        /** 显示名：文件名去掉扩展名 */
        public String name() {
            return name;
        }

        /**
         * 缩略图缓存的键：路径 + 建这一条的序号。
         * <p>
         * **不能只用路径**：图纸的惯例是**同名覆盖**（改一张图再导出去、从同伴那儿收到一个
         * 同名的新版），拿路径当键的话，缩略图会一直停在上一版——名字、尺寸都换新的了，图还是老的。
         * 序号而不是文件夹时间戳：属性读不出来时那两个数不会变，序号一定会变。
         */
        public String cacheKey() {
            return path + "#" + serial;
        }

        /**
         * 磁盘上的那一份跟当初读到的一样吗。
         * <p>
         * 只看**大小与修改时间**：重算一遍校验和（几百 KB × 目录里的份数）在每五秒一次的重扫里
         * 不划算，而这两项对"同名覆盖"足够——换图的人不会连大小都改回原样再存。
         * 读不到属性时**当作变了**：宁可多读一遍文件，也不能拿不准的时候继续显示旧结构。
         */
        private boolean changedOnDisk() {
            try {
                return Files.size(path) != size || Files.getLastModifiedTime(path).toMillis() != modified;
            } catch (IOException e) {
                return true;
            }
        }

        /** 结构；读不出来时返回 null。只尝试一次，失败不反复读盘 */
        @Nullable
        public Schematic schematic() {
            if (!attempted) {
                attempted = true;
                try {
                    schematic = BlueprintTransfer.decode(Files.readAllBytes(path));
                } catch (Exception e) {
                    schematic = null;
                    BlueprintMod.LOGGER.warn("图纸库里这份读不出来，先跳过：{}（{}）",
                            path.getFileName(), e.toString());
                }
            }
            return schematic;
        }

        /** 试过、但没读出来 */
        public boolean broken() {
            return attempted && schematic == null;
        }
    }

    private static List<Entry> entries = List.of();
    private static long scannedAt;
    /** 建条目的序号源：见 {@link Entry#cacheKey()} */
    private static long nextSerial = 1L;

    private BlueprintLibrary() {
    }

    /** 当前目录里的图纸；距上次扫描超过 {@link #RESCAN_INTERVAL_MS} 会先重扫一遍 */
    public static List<Entry> entries() {
        if (System.currentTimeMillis() - scannedAt > RESCAN_INTERVAL_MS) {
            refresh();
        }
        return entries;
    }

    /**
     * 重新列一遍目录。
     * <p>
     * **没变的那些要按路径留下来**：重扫只是看目录有没有变，
     * 若把旧条目整个换掉，每五秒就会把刚读出来的图纸丢掉重读一遍。
     * <p>
     * 但**路径一样不等于还是那一份**：图纸惯例是**同名覆盖**，内容换了就得重读，
     * 判据见 {@link Entry#changedOnDisk()}。
     */
    public static void refresh() {
        scannedAt = System.currentTimeMillis();

        Map<Path, Entry> previous = new LinkedHashMap<>();
        for (Entry entry : entries) {
            previous.put(entry.path(), entry);
        }

        List<Entry> fresh = new ArrayList<>();
        for (Path path : BlueprintTransfer.listFiles()) {
            Entry old = previous.get(path);
            fresh.add(old != null && !old.changedOnDisk()
                    ? old : new Entry(path, BlueprintTransfer.displayName(path)));
        }
        entries = fresh;
    }
}
