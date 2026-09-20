package com.example.blueprint.schematic;

import com.example.blueprint.BlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 存放在主世界存档里的蓝图库。
 * <p>
 * 蓝图物品只保存一个 UUID，真正的结构数据留在这里，
 * 这样物品 NBT 不会膨胀，也方便多个蓝图共享同一份结构。
 */
public class SchematicStorage extends SavedData {

    private static final String NAME = BlueprintMod.MOD_ID + "_schematics";

    private final Map<UUID, Schematic> schematics = new LinkedHashMap<>();

    /**
     * 已经建完的"机械动力蓝图工地"（内容 + 锚点的指纹）。
     * <p>
     * 机械动力那张图我们只读不改（连 NBT 都不写），完工标记只能记在自己这儿。
     * 记进存档是必要的：重启之后她得记得这处已经建过，
     * 否则会跑过去、发现没活可干、又报一次"建好啦"。
     */
    private final Set<String> completed = new HashSet<>();

    public SchematicStorage() {
    }

    /**
     * 统一存到主世界，这样跨维度的蓝图也能正常解析。
     */
    public static SchematicStorage get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(SchematicStorage::load, SchematicStorage::new, NAME);
    }

    private static SchematicStorage load(CompoundTag tag) {
        SchematicStorage storage = new SchematicStorage();
        ListTag list = tag.getList("schematics", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag holder = list.getCompound(i);
            try {
                UUID id = holder.getUUID("id");
                storage.schematics.put(id, Schematic.read(holder.getCompound("data")));
            } catch (Exception e) {
                BlueprintMod.LOGGER.warn("跳过损坏的蓝图数据: {}", holder.getUUID("id"), e);
            }
        }
        ListTag completedTag = tag.getList("completed", Tag.TAG_STRING);
        for (int i = 0; i < completedTag.size(); i++) {
            storage.completed.add(completedTag.getString(i));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Schematic> entry : schematics.entrySet()) {
            CompoundTag holder = new CompoundTag();
            holder.putUUID("id", entry.getKey());
            holder.put("data", entry.getValue().write(new CompoundTag()));
            list.add(holder);
        }
        tag.put("schematics", list);

        ListTag completedTag = new ListTag();
        for (String signature : completed) {
            completedTag.add(StringTag.valueOf(signature));
        }
        tag.put("completed", completedTag);
        return tag;
    }

    public UUID put(Schematic schematic) {
        UUID id = UUID.randomUUID();
        schematics.put(id, schematic);
        setDirty();
        return id;
    }

    @Nullable
    public Schematic get(UUID id) {
        return schematics.get(id);
    }

    public boolean remove(UUID id) {
        if (schematics.remove(id) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public int size() {
        return schematics.size();
    }

    // ------------------------------------------------------------------
    // 机械动力蓝图的完工标记（图本身不写，记在这里）
    // ------------------------------------------------------------------

    public boolean isCompleted(String signature) {
        return completed.contains(signature);
    }

    public void setCompleted(String signature, boolean value) {
        if (value ? completed.add(signature) : completed.remove(signature)) {
            setDirty();
        }
    }
}
