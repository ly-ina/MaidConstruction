package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层查看：只看建筑的某一层，或者看到某一层为止。换层靠预览框右边那条**拖动条**。
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
 * <b>为什么不设键位</b>：早先试过"↑↓ 一层、PgUp/PgDn 十层、Home/End 到两头"，看着周全，实际很难用——
 * 楼一高，几百层要么滚几百下、要么只能跳两端，中间那段全靠蒙。拖动条是**连续**的：想看哪层直接拖到
 * 哪个位置，条的长短本身还把"这楼有多高、我现在大概在几分之几"画出来了。
 * <p>
 * 两种看法各有用：**累积**（显示到第 N 层）看得出结构怎么一层层长上去，从上往下看就是那层的平面；
 * **切片**（只看第 N 层）则完全去掉下面的遮挡。前者是默认，拖动条上右键切到后者。
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintLayerView {

    /** 拖动条的宽度（与两处界面的绘制、命中判定共用，写在一处免得两边差几像素） */
    public static final int BAR_WIDTH = 6;

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
     * 拖动条拖到/点到某个高度：把 y 换算成层号。
     * <p>
     * 条上**底＝第 1 层、顶＝整栋**，与"往上盖"的直觉一致；按住 Shift 拖则是切片模式（只看那一层）。
     * 换算按比例取整，所以无论楼多高，拖到哪儿就是哪儿——这正是键位做不到的事。
     */
    public void dragTo(double mouseY, int barTop, int barBottom, boolean sliceKey) {
        double ratio = barBottom <= barTop ? 1.0D
                : (barBottom - mouseY) / (double) (barBottom - barTop);
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        to((int) Math.round(ratio * (layers - 1)) + 1, sliceKey);
    }

    /** 右键点拖动条：在"看到这层为止"与"只看这一层"之间切 */
    public void toggleSlice() {
        slice = !slice;
        version++;
    }

    /**
     * 直接跳到第 N 层（N 会被夹到 1..layers）。
     * <p>
     * {@code sliceKey}（Shift）与当前模式不同时，只切模式、不动层号——一次操作只改一件事，
     * 玩家才看得清是哪一下起了效。
     */
    private void to(int layer, boolean sliceKey) {
        if (sliceKey != slice) {
            slice = sliceKey;
            version++;
            return;
        }
        int next = Math.max(1, Math.min(layers, layer));
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

    // ------------------------------------------------------------------
    // 画那条拖动条 + 命中判定：两处界面共用的口径，别各写一份
    // ------------------------------------------------------------------

    /** 指针在不在拖动条上（左右各放宽 4 像素：6 像素宽的细条直接点很难点中） */
    public static boolean overBar(double mouseX, double mouseY, int barX, int barTop, int barBottom) {
        return mouseX >= barX - 4 && mouseX <= barX + BAR_WIDTH + 4
                && mouseY >= barTop - 2 && mouseY <= barBottom + 2;
    }

    /** 画拖动条：槽 + 把手。把手的位置就是当前层，一眼能看出"在整栋的几分之几" */
    public void drawBar(GuiGraphics graphics, int barX, int barTop, int barBottom) {
        graphics.fill(barX, barTop, barX + BAR_WIDTH, barBottom, 0x80000000);
        graphics.renderOutline(barX - 1, barTop - 1, BAR_WIDTH + 2, barBottom - barTop + 2, 0x60FFFFFF);

        double ratio = layers <= 1 ? 1.0D : (through - 1) / (double) (layers - 1);
        int knobY = (int) Math.round(barBottom - ratio * (barBottom - barTop));
        // 切片模式下把手换成亮青色：与"整层拆出来看"这件事一眼对上
        int knobColor = slice ? 0xFF55FFFF : 0xFFFFCC55;
        graphics.fill(barX - 2, knobY - 2, barX + BAR_WIDTH + 2, knobY + 2, knobColor);
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

    /**
     * 预览左上角那个问号里写的东西（鼠标悬浮才展开）。
     * <p>
     * 为什么收进问号：这几行常驻会横跨预览框、压到右边的按钮上（中文尤其长），而且它是"看过一次就记住"
     * 的东西——常驻只是占地方。
     */
    public static List<Component> helpLines() {
        return List.of(
                Component.translatable("gui.maidconstruction.help.layers"),
                Component.translatable("gui.maidconstruction.help.slice"),
                Component.translatable("gui.maidconstruction.help.zoom"));
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
