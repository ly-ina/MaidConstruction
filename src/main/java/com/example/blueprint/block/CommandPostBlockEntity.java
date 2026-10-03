package com.example.blueprint.block;

import com.example.blueprint.client.CommandPostProjections;
import com.example.blueprint.registry.ModBlocks;
import com.example.blueprint.server.CommandPostAssignments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 指挥台的状态：谁绑定了它、正在托管哪张图纸、投影钉在哪、指派了哪些女仆。
 * <p>
 * 为什么全都存在方块实体里：它是**存档的一部分**。指挥台要能"放下去就一直记着"——
 * 主人下线、区块卸载、服务器重启，回来照着这台继续建（见 DEVELOPER §12
 * "投影放下去就在，除非在终端里取消"）。所以这里没有一条状态是只放内存的。
 * <p>
 * 同步方式用方块实体自己的更新包（{@code getUpdateTag} / {@code getUpdatePacket}），
 * 不另开网络包：状态很小（几个 UUID + 名字 + 一个坐标），而且**只发给看得见这块的人**，
 * 天然没有"给谁发、发多少"的问题。界面那边每帧读一次方块实体，收到就自动变了。
 * <p>
 * **进度不在这里**：那是施工侧的事（`BlueprintBuildController` 每个会话自己算），
 * 第 4 步接进来时再决定是"存一份快照"还是"每次问一遍"。
 */
public class CommandPostBlockEntity extends BlockEntity {

    private static final String KEY_OWNER = "Owner";
    private static final String KEY_OWNER_NAME = "OwnerName";
    private static final String KEY_SCHEMATIC = "Schematic";
    private static final String KEY_BLUEPRINT_NAME = "BlueprintName";
    private static final String KEY_ANCHOR = "Anchor";
    private static final String KEY_ROTATION = "Rotation";
    private static final String KEY_MIRROR = "Mirror";
    private static final String KEY_PAUSED = "Paused";
    private static final String KEY_COMPLETED = "Completed";
    private static final String KEY_STARTED = "Started";
    private static final String KEY_MAIDS = "Maids";
    private static final String KEY_MAID_ID = "id";
    private static final String KEY_MAID_NAME = "name";

    /** 绑定它的玩家；null = 还没绑定（此时只有主人自己绑得上去） */
    @Nullable
    private UUID owner;
    /** 绑定玩家的名字：界面上要显示"谁绑的"，而 UUID 认不出来是谁 */
    private String ownerName = "";

    /** 正在托管的图纸（蓝图库里的那份），null = 还没放投影 */
    @Nullable
    private UUID schematicId;
    /** 图纸名：同样只为显示，删掉文件之后也还认得出这台托的是什么 */
    private String blueprintName = "";
    /** 投影钉在哪一格（结构的锚点 = 最小角） */
    @Nullable
    private BlockPos anchor;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    /** 暂停：保住进度停下，撤单才是把投影一起取消 */
    private boolean paused;
    /** 这台托的那份建完了：建完就不再派她往工地跑（与蓝图上的完工标记同一条规矩） */
    private boolean completed;
    /**
     * 玩家下过「开始建造」的口令没有。
     * <p>
     * <b>默认 false</b>：放投影、指派女仆都只是准备，准备不该等于开工——他要先把她叫齐、
     * 把料备好、把别的事交代完。旧存档读出来也是 false，正好是"等口令"这个意思。
     */
    private boolean started;

    /** 指派给这台指挥台的女仆：uuid -> 名字快照（顺序就是指派的先后） */
    private final Map<UUID, String> maids = new LinkedHashMap<>();

    public CommandPostBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.COMMAND_POST_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public boolean isBoundTo(UUID player) {
        return owner != null && owner.equals(player);
    }

    public void bind(UUID player, String name) {
        this.owner = player;
        this.ownerName = name == null ? "" : name;
        markUpdated();
    }

    public void unbind() {
        this.owner = null;
        this.ownerName = "";
        markUpdated();
    }

    @Nullable
    public UUID getSchematicId() {
        return schematicId;
    }

    public String getBlueprintName() {
        return blueprintName;
    }

    @Nullable
    public BlockPos getAnchor() {
        return anchor;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public Mirror getMirror() {
        return mirror;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean value) {
        this.paused = value;
        markUpdated();
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * 下口令 / 收回口令（见 {@code C2SCommandPostStartPacket}）。
     * <p>
     * 开工时顺带把"完工"清掉：她上次建完、工地后来被拆了几块，主人再下一次口令的意思
     * 就是"再去过一遍"——不清的话她会一直显示已完成，且永远不再去补。
     */
    public void setStarted(boolean value) {
        this.started = value;
        if (value) {
            this.completed = false;
        }
        markUpdated();
    }

    /**
     * 放下投影：结构、朝向、位置全部来自**手上那张蓝图**（见 {@code C2SCommandPostProjectionPacket}）。
     * <p>
     * 不在这里校验"图存不存在"：图纸文件可能被删、被换，而指挥台要照旧记得自己托的是哪一份
     * （界面上写的就是这张图的名字）。真要结构数据是渲染那一步按 id 点播的事。
     */
    public void placeProjection(UUID id, String name, BlockPos anchor, Rotation rotation, Mirror mirror) {
        this.schematicId = id;
        this.blueprintName = name == null ? "" : name;
        this.anchor = anchor;
        this.rotation = rotation;
        this.mirror = mirror;
        // 换了图就当是新工地：上一份的"建完了"不能留给这一份用，口令也要重下
        this.completed = false;
        this.started = false;
        markUpdated();
    }

    /**
     * 取消投影 = **撤单**（DEVELOPER §12）：挂在这台上的女仆一并撤下来，投影随之消失。
     * 图纸名留着：界面上还看得出这台原来托的是哪张，只是不再画、也没人在建了。
     */
    public void clearProjection() {
        this.schematicId = null;
        this.anchor = null;
        this.completed = false;
        this.started = false;
        if (this.level != null && !this.level.isClientSide) {
            CommandPostAssignments.clearPost(CommandPostAssignments.at(this.level, this.worldPosition));
        }
        markUpdated();
    }

    /** 这台托的那份建完了没有：建完就不再派她去补（工地被拆掉几块也不去，与蓝图那条同一条规矩） */
    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean value) {
        this.completed = value;
        markUpdated();
    }

    /** 指派女仆。她原先效力别处的话，施工侧那张索引会以后写的为准（一只只效力一台） */
    public void assign(UUID maid, String name) {
        this.maids.put(maid, name == null ? "" : name);
        syncAssignment(maid, true);
        markUpdated();
    }

    public void unassign(UUID maid) {
        if (this.maids.remove(maid) != null) {
            syncAssignment(maid, false);
            markUpdated();
        }
    }

    /**
     * 把"谁效力哪台"同步进施工侧那张索引（{@code CommandPostAssignments}）。
     * <p>
     * 那份表只是**索引**：真相在这儿（存档里），它随方块实体加载重建、随卸载/被拆划掉。
     * 不另存一份的原因是——两份记录一旦对不上，女仆会照着一份过期的名单干活。
     */
    private void syncAssignment(UUID maid, boolean assigned) {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (assigned) {
            CommandPostAssignments.assign(maid, CommandPostAssignments.at(this.level, this.worldPosition));
        } else {
            CommandPostAssignments.unassign(maid);
        }
    }

    public Map<UUID, String> getMaids() {
        return Map.copyOf(this.maids);
    }

    /** 状态变了：存进存档、同步给看得见的人，并把 {@code loaded} 状态捋一遍 */
    public void markUpdated() {
        setChanged();
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        boolean loaded = this.schematicId != null;
        if (getBlockState().getValue(CommandPostBlock.LOADED) != loaded) {
            this.level.setBlock(this.worldPosition,
                    getBlockState().setValue(CommandPostBlock.LOADED, loaded), Block.UPDATE_ALL);
        }
    }

    /** 把这块的状态补发给某个玩家。右键那一刻调一次，见 {@code CommandPostBlock.use} */
    public void sendStateTo(ServerPlayer player) {
        player.connection.send(ClientboundBlockEntityDataPacket.create(this));
    }

    // ------------------------------------------------------------------
    // 存档与同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.owner != null) {
            tag.putUUID(KEY_OWNER, this.owner);
        }
        tag.putString(KEY_OWNER_NAME, this.ownerName);
        if (this.schematicId != null) {
            tag.putUUID(KEY_SCHEMATIC, this.schematicId);
        }
        tag.putString(KEY_BLUEPRINT_NAME, this.blueprintName);
        if (this.anchor != null) {
            tag.putIntArray(KEY_ANCHOR, new int[]{anchor.getX(), anchor.getY(), anchor.getZ()});
        }
        tag.putInt(KEY_ROTATION, this.rotation.ordinal());
        tag.putInt(KEY_MIRROR, this.mirror.ordinal());
        tag.putBoolean(KEY_PAUSED, this.paused);
        tag.putBoolean(KEY_COMPLETED, this.completed);
        tag.putBoolean(KEY_STARTED, this.started);

        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> maid : this.maids.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_MAID_ID, maid.getKey());
            entry.putString(KEY_MAID_NAME, maid.getValue());
            list.add(entry);
        }
        tag.put(KEY_MAIDS, list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.owner = tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : null;
        this.ownerName = tag.getString(KEY_OWNER_NAME);
        this.schematicId = tag.hasUUID(KEY_SCHEMATIC) ? tag.getUUID(KEY_SCHEMATIC) : null;
        this.blueprintName = tag.getString(KEY_BLUEPRINT_NAME);

        int[] raw = tag.getIntArray(KEY_ANCHOR);
        this.anchor = raw.length == 3 ? new BlockPos(raw[0], raw[1], raw[2]) : null;

        Rotation[] rotations = Rotation.values();
        Mirror[] mirrors = Mirror.values();
        this.rotation = rotations[Math.floorMod(tag.getInt(KEY_ROTATION), rotations.length)];
        this.mirror = mirrors[Math.floorMod(tag.getInt(KEY_MIRROR), mirrors.length)];
        this.paused = tag.getBoolean(KEY_PAUSED);
        this.completed = tag.getBoolean(KEY_COMPLETED);
        // 没有这个键（1.8.0 之前的存档）= false = 等她等口令，正是想要的行为
        this.started = tag.getBoolean(KEY_STARTED);

        this.maids.clear();
        ListTag list = tag.getList(KEY_MAIDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID(KEY_MAID_ID)) {
                this.maids.put(entry.getUUID(KEY_MAID_ID), entry.getString(KEY_MAID_NAME));
            }
        }

        syncClientProjection();
        syncAssignments();
    }

    /**
     * 方块实体加载时把"指派了谁"重新填进施工侧那张索引。
     * <p>
     * 那份表不进存档（真相在这儿），所以服务端重启、区块重新加载之后都得照这份记录重建一次——
     * 否则她会以为"没人指派我"，站在那儿发呆。
     */
    private void syncAssignments() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        GlobalPos self = CommandPostAssignments.at(this.level, this.worldPosition);
        for (UUID maid : this.maids.keySet()) {
            CommandPostAssignments.assign(maid, self);
        }
    }

    /**
     * 把"这台有没有投影"登记到客户端那张表里（见 {@code CommandPostProjections}）。
     * <p>
     * 挂在 {@link #load} 上是因为它一次覆盖两条路：区块加载时带来的整份 NBT、
     * 以及状态变更时发的更新包——两条最后都走到这里。走远了方块实体被卸掉，
     * {@link #setRemoved} 那边再把它划掉，于是"看得见才画"这件事是白来的。
     * <p>
     * 专用服务端上这一句什么也不做：{@code DistExecutor} 只认**物理端**，
     * 客户端那个类不会被加载（见 DEVELOPER §7.15 那类坑）。
     */
    private void syncClientProjection() {
        if (this.level == null || !this.level.isClientSide) {
            return;
        }
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CommandPostProjections.update(
                this.worldPosition,
                this.owner,
                this.schematicId == null || this.anchor == null ? null
                        : new CommandPostProjections.Projection(this.schematicId, this.blueprintName,
                                this.anchor, this.rotation, this.mirror)));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level == null) {
            return;
        }
        if (this.level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> CommandPostProjections.remove(this.worldPosition));
            return;
        }
        // 这台不在了（被拆、区块卸载）：挂在上面的指派先撤掉，别留一个谁也管不着的工地。
        // 区块重新加载时 load 会把它们再填回来（存档里那几行还在），所以这不是"解绑"
        CommandPostAssignments.clearPost(CommandPostAssignments.at(this.level, this.worldPosition));
    }

    @Override
    public CompoundTag getUpdateTag() {
        // 同步给客户端的就是整份状态：内容小，不必再挑字段
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
