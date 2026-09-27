package com.example.blueprint.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 女仆建筑说明书：一本**原版的成书**。
 * <p>
 * 为什么用原版成书而不是自己的界面：翻页、滚动、书的样子、抄写、"掉进岩浆里的书"……
 * 这些东西原版全都有，而且玩家一看就知道怎么翻。为一个"读一遍就收起来"的说明书
 * 养一套自己的 Screen 不划算（AE 那种长按 G 的悬浮框同理——那是它自己的一整套
 * 手册框架，我们这儿没有）。
 * <p>
 * <b>书页里存的是"要翻译成哪一条"，不是译好的句子</b>：页数据在 NBT 里是
 * {@code {"translate":"manual.blueprint.page1"}}，客户端翻到那一页时才拿它的语言文件去查。
 * 所以中文端读中文、英文端读英文——只存写死的中文，英文客户端翻开的就会是一本中文书。
 * <p>
 * 页数、每页的行宽都是原版那套规矩：一页大约 13 行、一行大约 13 个汉字。
 * 超出去的字**会被原版直接吐掉**（不是换个页接着写），所以正文得短。
 */
public final class ManualBook {

    /**
     * 书里的页，一条一页，顺序就是翻开后的顺序。
     * <p>
     * 这几条键的正文在 {@code assets/blueprint/lang/*.json} 里。
     * 改内容只动语言文件；加一页就往这个数组里加一条。
     */
    private static final String[] PAGES = {
            "manual.blueprint.page1",
            "manual.blueprint.page2",
            "manual.blueprint.page3",
            "manual.blueprint.page4",
            "manual.blueprint.page5",
            "manual.blueprint.page6",
            "manual.blueprint.page7",
            "manual.blueprint.page8",
            "manual.blueprint.page9",
            "manual.blueprint.page10",
            "manual.blueprint.page11",
            "manual.blueprint.page12",
    };

    /** 书名。写在 NBT 里，所以是**写死的**（原版不认书名里的翻译键） */
    public static final String TITLE = "女仆建筑说明书";
    /** 作者那一行，书物品的提示里会写 "by 女仆建筑" */
    private static final String AUTHOR = "女仆建筑";

    /**
     * 造一本说明书。
     * <p>
     * 每次调用都新建一个 {@link ItemStack}（而不是共用一份常量）：书是发给玩家、会被他
     * 丢掉或抄写的，共用同一份实例等于把大家的书绑在一起。
     */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = stack.getOrCreateTag();

        ListTag pages = new ListTag();
        for (String key : PAGES) {
            // 只放"翻译键"，见类注释。\n 之类的换行写在语言文件那一条里，不在这儿拼
            pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable(key))));
        }
        tag.put("pages", pages);
        tag.putString("title", TITLE);
        tag.putString("author", AUTHOR);
        // resolved=true：告诉客户端"这本书的页已经定稿了，别再走一遍原版的解析/清洗"。
        // 少了这一句，客户端会拿服务端那套规矩把页里的 translate 组件当"未知内容"处理掉，
        // 翻开来就是一页空白
        tag.putBoolean("resolved", true);
        return stack;
    }

    private ManualBook() {
    }
}
