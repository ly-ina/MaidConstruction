package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端缓存服务端下发的蓝图数据，供投影渲染使用。
 * <p>
 * 缓存两份：服务端下发的**原始**结构（按 id），以及按当前朝向变换后的副本
 * （按 id + 旋转 + 翻面）。原始那份不跟着朝向变，换朝向时不必重新向服务端要数据。
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

    /**
     * 换过朝向的副本，key 为 "uuid|旋转序号|翻面序号"。
     * <p>
     * 两半都得进 key：只写旋转的话，翻面与没翻面会撞在同一格里，
     * 玩家点一下翻面看到的还是老结构。
     */
    private static final Map<String, Schematic> TRANSFORMED = new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Schematic> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ClientSchematicCache() {
    }

    public static void put(UUID id, Schematic schematic) {
        CACHE.put(id, schematic);
        // 原始数据变了，之前算出来的朝向副本全部作废
        String prefix = id.toString();
        TRANSFORMED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static Schematic get(UUID id) {
        return CACHE.get(id);
    }

    /**
     * 取出按指定朝向变换后的结构：先翻面、后旋转。
     * <p>
     * 两者都是 NONE 时直接返回原始实例，不产生任何复制。
     */
    public static Schematic get(UUID id, Rotation rotation, Mirror mirror) {
        Schematic base = CACHE.get(id);
        if (base == null) {
            return null;
        }
        if (rotation == Rotation.NONE && mirror == Mirror.NONE) {
            return base;
        }
        return TRANSFORMED.computeIfAbsent(id + "|" + rotation.ordinal() + "|" + mirror.ordinal(),
                key -> base.mirror(mirror).rotate(rotation));
    }

    public static boolean has(UUID id) {
        return CACHE.containsKey(id);
    }
}
