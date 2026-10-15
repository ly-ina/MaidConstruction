package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层查看：只看建筑的某一层，或者看到某一层为止。
 * <p>
 * <b>为什么要有它</b>：一座建筑竖起来之后，外面那圈墙把里面挡得干干净净——材料清单能告诉你用了什么，
 * 却回答不了"这一层是怎么摆的"。把预览按 Y 切开，内部结构才看得见。
 * <p>
 * <b>层号从建筑底数起</b>（不是世界坐标）：玩家关心的是"从下往上第几层"。扫描时最小角已经归到 0，
 * 这里仍按第一块方块的 y 当起点，免得以后录进来一份带偏移的图时层号整体错位。
 * <p>
 * <b>只做过滤，不碰渲染</b>：列表过了这一道就交给 {@code SchematicPreview}。三处预览（蓝图面板、
 * 图纸详情页、终端缩略图）画的是同一份东西，分层也必须只有一种口径，否则会出现"同一个建筑在面板里
 * 是第 3 层、在终端里是别的"——那种偏差没人会去核对，只会被当成 bug 报上来。
 * <p>
 * 两种看法各有用：**累积**（显示到第 N 层）看得出结构怎么一层层长上去，从上往下看就是那层的平面；
 * **切片**（只看第 N 层）则完全去掉下面的遮挡。前者是默认，后者按住 Shift 滚轮切过去。
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintLayerView {

    /** 第一层对应的 y（按第一块方块推；空图给 0） */
    private int minY;
    /** 总层数：按结构的 Y 尺寸算，顶层是空腔也照样算——那样才停得上去看个究竟 */
    private int layers = 1;
    /** 显示到第几层（1..layers）；等于 layers 就是整栋 */
    private int through = 1;
    /** true = 只看这一层（切片） */
    private boolean slice;
    /** 过滤结果的缓存：建筑大时 entries 上万条，把它放在每帧都跑的绘制路径里等于白烧 CPU */
    private List<Schematic.BlockEntry> cached = List.of();
    /** 缓存对应的状态版本；每次状态真的变了才 +1 */
    private int version;
    private int cachedVersion = -1;

    /** 换了一张图：层数与起点重算，"看到第几层"回到整栋 */
    public void reset(Schematic schematic, List<Schematic.BlockEntry> entries) {
        this.layers = Math.max(1, schematic == null ? 1 : schematic.getHeight());
        this.minY = entries.isEmpty() ? 0 : entries.get(0).pos().getY();
        this.through = this.layers;
        this.slice = false;
        this.version++;
    }

    /** 状态变过没有：界面拿它决定要不要重算自己派生出来的那份数据（详情页那份抽稀过的清单） */
    public int version() {
        return version;
    }

    /**
     * 滚轮 / 方向键：换一层。
     * <p>
     * {@code sliceKey}（Shift）按住时切到"只看这一层"：它与当前模式不同就先只切模式、不动层号——
     * 一次操作只改一件事，玩家才看得清是哪一下起了效。
     */
    public void step(int delta, boolean sliceKey) {
        if (sliceKey != slice) {
            slice = sliceKey;
            version++;
            return;
        }
        int next = Math.max(1, Math.min(layers, through + delta));
        if (next != through) {
            through = next;
            version++;
        }
    }

    /** 按当前设置过滤出一份"该画的方块"；状态没变时直接给上一次的结果 */
    public List<Schematic.BlockEntry> filter(List<Schematic.BlockEntry> all) {
        if (cachedVersion == version) {
            return cached;
        }
        List<Schematic.BlockEntry> kept = new ArrayList<>();
        for (Schematic.BlockEntry entry : all) {
            int layer = entry.pos().getY() - minY + 1;
            if (slice ? layer != through : layer > through) {
                continue;
            }
            kept.add(entry);
        }
        cached = kept;
        cachedVersion = version;
        return cached;
    }

    /** 预览左上角第一行：现在看到的是哪一层 */
    public Component stateText() {
        if (slice) {
            return Component.translatable("gui.maidconstruction.layer.only", through, layers);
        }
        if (through >= layers) {
            return Component.translatable("gui.maidconstruction.layer.whole", layers);
        }
        return Component.translatable("gui.maidconstruction.layer.through", through, layers);
    }

    /** 预览左上角第二行：怎么操作 */
    public static Component keysText() {
        return Component.translatable("gui.maidconstruction.layer.keys");
    }

    /**
     * 悬浮到某一块时的提示：方块名 + 它在第几层。
     * <p>
     * 为什么带层号：分层看着的时候，"这是第几层的那块"比坐标有用得多；顺带也让"这块到底在楼上还是楼下"
     * 一眼可辨——那正是分层要解决的问题。
     */
    public Component describe(Schematic.BlockEntry entry) {
        return Component.translatable("gui.maidconstruction.preview.block",
                entry.state().getBlock().getName(), entry.pos().getY() - minY + 1);
    }
}
