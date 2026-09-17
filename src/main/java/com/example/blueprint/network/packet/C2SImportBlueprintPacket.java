package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 把一份蓝图文件的内容导进玩家手上的蓝图。
 * <p>
 * 传压缩后的字节数组而不是字符串——字符串在网络包里有 32K 的长度上限，
 * 稍大一点的结构就会被截断。
 */
public class C2SImportBlueprintPacket {

    private static final int MAX_BYTES = 1_000_000;

    private final byte[] data;
    private final String name;

    public C2SImportBlueprintPacket(byte[] data, String name) {
        this.data = data;
        this.name = name;
    }

    public static void encode(C2SImportBlueprintPacket msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.data);
        buf.writeUtf(msg.name, 128);
    }

    public static C2SImportBlueprintPacket decode(FriendlyByteBuf buf) {
        return new C2SImportBlueprintPacket(buf.readByteArray(), buf.readUtf(128));
    }

    public static void handle(C2SImportBlueprintPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = BlueprintItem.findHeld(player);
            if (stack.isEmpty()) {
                return;
            }
            if (msg.data.length > MAX_BYTES) {
                player.sendSystemMessage(Component.translatable("message.blueprint.import_too_large"));
                return;
            }

            try {
                Schematic schematic = Schematic.read(NbtIo.readCompressed(new ByteArrayInputStream(msg.data)));
                UUID id = SchematicStorage.get(player.serverLevel()).put(schematic);

                // 导入的是新结构，旧的定位和朝向都没有意义了
                BlueprintItem.setSchematic(stack, id, schematic.getSize(), msg.name);
                BlueprintItem.clearAnchor(stack);
                BlueprintItem.setRotation(stack, net.minecraft.world.level.block.Rotation.NONE);

                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CSchematicDataPacket(id, msg.name, schematic.write(new CompoundTag())));

                player.sendSystemMessage(Component.translatable("message.blueprint.imported",
                        schematic.getWidth(), schematic.getHeight(), schematic.getLength()));
            } catch (Exception e) {
                BlueprintMod.LOGGER.warn("蓝图导入失败", e);
                player.sendSystemMessage(Component.translatable("message.blueprint.import_failed"));
            }
        });
        context.setPacketHandled(true);
    }
}
