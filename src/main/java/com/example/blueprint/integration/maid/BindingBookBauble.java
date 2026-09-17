package com.example.blueprint.integration.maid;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;

/**
 * 绑定书的饰品形态。
 * <p>
 * 这里一个回调都不需要：女仆是在准备取料时主动去饰品栏翻这本书，
 * 而不是等事件通知。注册它的唯一目的，是让饰品栏愿意接受这个物品
 * ——{@code BaubleItemHandler} 会拒绝未注册过的物品。
 */
public class BindingBookBauble implements IMaidBauble {
}
