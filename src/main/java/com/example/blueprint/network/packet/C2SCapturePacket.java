package com.example.blueprint.network.packet;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BlueprintItem;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.schematic.Schematic;
import com.example.blueprint.schematic.SchematicStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端选完两个角点后，请求服务端扫描世界并生成蓝图。
 * 扫描必须在服务端做，客户端读到的方块实体 NBT 往往是不完整的。
 */
public class C2SCapturePacket {

    private final BlockPos pos1;
    private final BlockPos pos2;
    private final String name;

    public C2SCapturePacket(BlockPos pos1, BlockPos pos2, String name) {
        this.pos1 = pos1.immutable();
        this.pos2 = pos2.immutable();
        this.name = name;
    }

    public static void encode(C2SCapturePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos1);
        buf.writeBlockPos(msg.pos2);
        buf.writeUtf(msg.name, 128);
    }

    public static C2SCapturePacket decode(FriendlyByteBuf buf) {
        return new C2SCapturePacket(buf.readBlockPos(), buf.readBlockPos(), buf.readUtf(128));
    }

    public static void handle(C2SCapturePacket msg, Supplier<NetworkEvent.Context> ctx) {
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

            if (BlueprintItem.hasSchematic(stack)) {
                player.sendSystemMessage(Component.translatable("message.blueprint.already_has_schematic"));
                return;
            }

            try {
                Schematic schematic = Schematic.capture(player.serverLevel(), msg.pos1, msg.pos2);
                UUID id = SchematicStorage.get(player.serverLevel()).put(schematic);

                BlueprintItem.setSchematic(stack, id, schematic.getSize(), msg.name);
                BlueprintItem.clearSelection(stack);

                ModNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2CSchematicDataPacket(id, msg.name, schematic.write(new CompoundTag())));

                player.sendSystemMessage(Component.translatable("message.blueprint.captured",
                        schematic.getWidth(), schematic.getHeight(), schematic.getLength(),
                        countBlocks(schematic)));
            } catch (IllegalStateException e) {
                player.sendSystemMessage(Component.translatable(e.getMessage() == null
                        ? "message.blueprint.capture_failed" : e.getMessage()));
            } catch (Exception e) {
                com.example.blueprint.BlueprintMod.LOGGER.error("蓝图扫描失败", e);
                player.sendSystemMessage(Component.translatable("message.blueprint.capture_failed"));
            }
        });
        context.setPacketHandled(true);
    }

    private static int countBlocks(Schematic schematic) {
        int count = 0;
        for (Schematic.BlockEntry ignored : schematic.entries()) {
            count++;
        }
        return count;
    }
}
