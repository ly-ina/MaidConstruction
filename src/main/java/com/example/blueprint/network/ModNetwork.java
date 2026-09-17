package com.example.blueprint.network;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.network.packet.C2SCapturePacket;
import com.example.blueprint.network.packet.C2SClearBlueprintPacket;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetNamePacket;
import com.example.blueprint.network.packet.C2SSetRotationPacket;
import com.example.blueprint.network.packet.S2CSchematicDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlueprintMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++, C2SCapturePacket.class,
                C2SCapturePacket::encode, C2SCapturePacket::decode, C2SCapturePacket::handle);
        CHANNEL.registerMessage(id++, C2SSetAnchorPacket.class,
                C2SSetAnchorPacket::encode, C2SSetAnchorPacket::decode, C2SSetAnchorPacket::handle);
        CHANNEL.registerMessage(id++, C2SSetRotationPacket.class,
                C2SSetRotationPacket::encode, C2SSetRotationPacket::decode, C2SSetRotationPacket::handle);
        CHANNEL.registerMessage(id++, C2SClearBlueprintPacket.class,
                C2SClearBlueprintPacket::encode, C2SClearBlueprintPacket::decode, C2SClearBlueprintPacket::handle);
        CHANNEL.registerMessage(id++, C2SRequestSchematicPacket.class,
                C2SRequestSchematicPacket::encode, C2SRequestSchematicPacket::decode, C2SRequestSchematicPacket::handle);
        CHANNEL.registerMessage(id++, C2SImportBlueprintPacket.class,
                C2SImportBlueprintPacket::encode, C2SImportBlueprintPacket::decode, C2SImportBlueprintPacket::handle);
        CHANNEL.registerMessage(id++, C2SSetNamePacket.class,
                C2SSetNamePacket::encode, C2SSetNamePacket::decode, C2SSetNamePacket::handle);
        CHANNEL.registerMessage(id++, S2CSchematicDataPacket.class,
                S2CSchematicDataPacket::encode, S2CSchematicDataPacket::decode, S2CSchematicDataPacket::handle);
    }
}
