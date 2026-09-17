package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.world.level.block.Rotation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端缓存服务端下发的蓝图数据，供投影渲染使用。
 * <p>
 * 这里刻意不标注 {@code @OnlyIn}：该类不引用任何客户端专属类型，
 * 保持中立可以避免服务端收到数据包时触发意外的类加载问题。
 */
public class ClientSchematicCache {

    private static final int MAX_ENTRIES = 24;

    private static final Map<UUID, Schematic> CACHE = new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Schematic> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** 旋转后的副本，key 为 "uuid|ordinal" */
    private static final Map<String, Schematic> ROTATED = new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Schematic> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ClientSchematicCache() {
    }

    public static void put(UUID id, Schematic schematic) {
        CACHE.put(id, schematic);
        // 原始数据变了，之前算出来的旋转副本全部作废
        String prefix = id.toString();
        ROTATED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static Schematic get(UUID id) {
        return CACHE.get(id);
    }

    /**
     * 取出按指定朝向旋转后的结构。NONE 直接返回原始实例，不产生任何复制。
     */
    public static Schematic get(UUID id, Rotation rotation) {
        Schematic base = CACHE.get(id);
        if (base == null) {
            return null;
        }
        if (rotation == Rotation.NONE) {
            return base;
        }
        return ROTATED.computeIfAbsent(id + "|" + rotation.ordinal(), key -> base.rotate(rotation));
    }

    public static boolean has(UUID id) {
        return CACHE.containsKey(id);
    }
}
