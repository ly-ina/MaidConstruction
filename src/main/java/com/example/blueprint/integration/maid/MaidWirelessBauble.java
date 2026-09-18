package com.example.blueprint.integration.maid;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;

/**
 * 无线女仆终端和女仆绑定卡的饰品形态。
 * <p>
 * 和 {@link BindingBookBauble} 一样，一个回调都不需要：女仆是在准备取料时
 * 主动去身上翻这两样东西，而不是等事件通知。注册的唯一目的，是让饰品栏
 * 愿意接受它们——{@code BaubleItemHandler} 会拒绝未注册过的物品。
 * <p>
 * 一个类管两件物品，是因为它们的行为完全一致（都是"放在那儿就行"），
 * 没有需要区分的地方。
 */
public class MaidWirelessBauble implements IMaidBauble {
}
