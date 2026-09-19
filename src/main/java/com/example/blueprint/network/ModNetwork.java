package com.example.blueprint.network;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.network.packet.C2SCapturePacket;
import com.example.blueprint.network.packet.C2SClearBlueprintPacket;
import com.example.blueprint.network.packet.C2SImportBlueprintPacket;
import com.example.blueprint.network.packet.C2SMaidCraftOrderPacket;
import com.example.blueprint.network.packet.C2SRequestSchematicPacket;
import com.example.blueprint.network.packet.C2SSetAnchorPacket;
import com.example.blueprint.network.packet.C2SSetNamePacket;
import com.example.blueprint.network.packet.C2SSetRotationPacket;
import com.example.blueprint.network.packet.C2SForgetStudyPacket;
import com.example.blueprint.network.packet.C2SSelectStudyRecipePacket;
import com.example.blueprint.network.packet.S2CBuildProgressPacket;
import com.example.blueprint.network.packet.S2CSchematicDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {

    // 1.5.3 改了学习池的包（设优先级 → 选做法；停用包整个删掉），协议不兼容，升到 2；
    // 1.5.4 加了施工进度包（S2CBuildProgressPacket），再升到 3。
    // 按约定：老客户端连新服务端（或反过来）是不允许的，宁可连不上也不要在游戏里出怪事
    private static final String PROTOCOL_VERSION = "3";

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
        CHANNEL.registerMessage(id++, C2SSelectStudyRecipePacket.class,
                C2SSelectStudyRecipePacket::encode, C2SSelectStudyRecipePacket::decode,
                C2SSelectStudyRecipePacket::handle);
        CHANNEL.registerMessage(id++, C2SMaidCraftOrderPacket.class,
                C2SMaidCraftOrderPacket::encode, C2SMaidCraftOrderPacket::decode,
                C2SMaidCraftOrderPacket::handle);
        CHANNEL.registerMessage(id++, C2SForgetStudyPacket.class,
                C2SForgetStudyPacket::encode, C2SForgetStudyPacket::decode,
                C2SForgetStudyPacket::handle);
        // 施工进度：服务端推给女仆附近的玩家，客户端拿去画进度条
        CHANNEL.registerMessage(id++, S2CBuildProgressPacket.class,
                S2CBuildProgressPacket::encode, S2CBuildProgressPacket::decode,
                S2CBuildProgressPacket::handle);
    }
}
