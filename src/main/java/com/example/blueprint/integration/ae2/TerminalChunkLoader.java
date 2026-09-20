package com.example.blueprint.integration.ae2;

import com.example.blueprint.BlueprintMod;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 让她在工地也连得上那台终端所连的网络：**把终端链着的那个无线访问点所在区块带起来**。
 * <p>
 * 为什么非要强制加载：AE2 的无线终端是按"访问点"解析网络的，而访问点常常在**基地**——
 * 女仆在几十上百格外的工地干活时，那一片区块没加载，网络就解析不到，
 * 于是取料取不到、回收也塞不回去，看起来就像终端坏了。
 * <p>
 * <b>取舍：施工优先。</b>
 * <ul>
 *   <li>加载拿不到（维度没加载、票据被拒）→ <b>照常施工</b>，只是这一段退到"背包 → 地上"，
 *       绝不因为她站着等一个加载而停手；</li>
 *   <li>票据**按女仆**分开记（owner 带她的 UUID）：两只女仆共用一个访问点时各持一张，
 *       谁收工只放掉自己那张，不会把另一个人的拽掉；</li>
 *   <li>什么时候放掉只跟"她不再干这活"挂钩（{@code reset}/{@code detach}），
 *       不跟性能预算挂钩——性能是第二位的事。</li>
 * </ul>
 * 用的是 Forge 的 {@code ForgeChunkManager} 票据，不是 {@code setChunkForced}：
 * 后者会写进存档、忘了撤销就永久加载。
 */
public final class TerminalChunkLoader {

    /** 已持有的票据：key = 女仆 UUID | 维度 | 区块X,区块Z */
    private static final Map<String, Held> HELD = new HashMap<>();

    private TerminalChunkLoader() {
    }

    private record Held(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }

    /**
     * 女仆要干活了：把她身上那台**插了绑定卡、且已链接**的终端所指的访问点区块带起来。
     * <p>
     * 没有绑定卡、没链接、或者压根没装 AE2 时什么都不做——这条路上不报错、不提示，
     * 因为"她没用终端"是完全正常的情况。
     */
    public static void hold(ServerLevel level, UUID maidId, Iterable<ItemStack> heldStacks) {
        if (!Ae2Compat.isLoaded()) {
            return;
        }
        for (ItemStack stack : heldStacks) {
            if (stack.isEmpty()) {
                continue;
            }
            try {
                GlobalPos linked = WirelessMaidLink.linkedPosition(stack);
                if (linked != null) {
                    holdOne(level, maidId, linked);
                }
            } catch (Throwable t) {
                // 单个物品读不出链接不该影响别人，也不该影响施工
                BlueprintMod.LOGGER.debug("读终端链接时出错，跳过这一个：{}", t.toString());
            }
        }
    }

    private static void holdOne(ServerLevel level, UUID maidId, GlobalPos linked) {
        MinecraftServer server = level.getServer();
        ServerLevel target = server.getLevel(linked.dimension());
        if (target == null) {
            // 那个维度现在根本没加载（比如基地在另一个维度）。照常施工，只是这一段连不上网络
            BlueprintMod.LOGGER.info("女仆 {} 的终端访问点所在维度 {} 没有加载，这一次不去强加载",
                    maidId, linked.dimension().location());
            return;
        }

        int chunkX = linked.pos().getX() >> 4;
        int chunkZ = linked.pos().getZ() >> 4;
        String key = key(maidId, linked.dimension(), chunkX, chunkZ);
        if (HELD.containsKey(key)) {
            return; // 已经持着了
        }

        // owner 用她的 UUID：Forge 的票据是按 owner 分开记的，所以两只女仆共用一台访问点时
        // 各持一张，谁收工只放掉自己那张
        boolean ok;
        try {
            ok = ForgeChunkManager.forceChunk(target, BlueprintMod.MOD_ID, maidId, chunkX, chunkZ, true, true);
        } catch (Throwable t) {
            BlueprintMod.LOGGER.warn("女仆 {} 请求加载访问点区块失败：{}", maidId, t.toString());
            return;
        }
        HELD.put(key, new Held(linked.dimension(), chunkX, chunkZ));
        BlueprintMod.LOGGER.info("女仆 {} 的终端访问点在 {} 的区块 {},{}{}",
                maidId, linked.dimension().location(), chunkX, chunkZ, ok ? "，已加载" : "，加载请求被拒（照常施工）");
    }

    /**
     * 她不再干这活了：把自己持的那些票还回去。
     * <p>
     * 只还**她自己**的（owner 带 UUID），别人的票不受影响。
     */
    public static void release(ServerLevel level, UUID maidId) {
        if (HELD.isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        String prefix = maidId + "|";
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Held> entry : HELD.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            Held held = entry.getValue();
            ServerLevel target = server.getLevel(held.dimension());
            if (target != null) {
                try {
                    ForgeChunkManager.forceChunk(target, BlueprintMod.MOD_ID, maidId,
                            held.chunkX(), held.chunkZ(), false, true);
                } catch (Throwable t) {
                    BlueprintMod.LOGGER.warn("女仆 {} 释放访问点区块时出错：{}", maidId, t.toString());
                }
            }
            done.add(entry.getKey());
        }
        done.forEach(HELD::remove);
        if (!done.isEmpty()) {
            BlueprintMod.LOGGER.info("女仆 {} 收工，已放掉 {} 个访问点区块", maidId, done.size());
        }
    }

    /** 服务器关掉时清账：票本身随服务器消失，这里只是别让这张表活过一局 */
    public static void forget() {
        HELD.clear();
    }

    private static String key(UUID maidId, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return maidId + "|" + dimension.location() + "|" + chunkX + "," + chunkZ;
    }
}
