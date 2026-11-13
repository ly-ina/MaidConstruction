package com.example.blueprint.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "登录时扫一遍 → 她死掉 → 划掉"这条路上，表必须是**能改**的。
 * <p>
 * 2.1.1 报上来的崩溃就在这儿：{@code replace} 用 {@code List.copyOf} 存进去，
 * 之后 {@code forget} 对着它 removeIf 抛 {@code UnsupportedOperationException}，
 * 而调用点在 {@code LivingDeathEvent} 里——女仆一死服务器就崩。
 * <p>
 * 这里只碰表本身，维度传 {@code null}：这几条判据不看它，也就不必拉起 Minecraft 的注册表。
 */
class MaidOwnershipTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void reset() {
        MaidOwnership.clear();
    }

    @Test
    void forgetAfterReplaceDoesNotThrow() {
        UUID maid = UUID.randomUUID();
        MaidOwnership.replace(OWNER, List.of(maid("阿求", maid)));

        assertDoesNotThrow(() -> MaidOwnership.forget(UUID.randomUUID()));
        assertEquals(1, MaidOwnership.of(OWNER).size(), "划掉别人不该动到这一只");
    }

    @Test
    void forgetRemovesOnlyThatMaid() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MaidOwnership.replace(OWNER, List.of(maid("阿求", first), maid("小铃", second)));

        MaidOwnership.forget(first);

        List<MaidOwnership.Maid> left = MaidOwnership.of(OWNER);
        assertEquals(1, left.size());
        assertEquals(second, left.get(0).id());
        assertEquals("小铃", MaidOwnership.nameOf(second));
    }

    @Test
    void rememberAfterReplaceStillWorks() {
        UUID existing = UUID.randomUUID();
        MaidOwnership.replace(OWNER, List.of(maid("阿求", existing)));

        UUID fresh = UUID.randomUUID();
        assertDoesNotThrow(() -> MaidOwnership.remember(maid("小铃", fresh), OWNER));

        List<MaidOwnership.Maid> left = MaidOwnership.of(OWNER);
        assertEquals(2, left.size());
        assertTrue(left.stream().anyMatch(entry -> entry.id().equals(fresh)));
        assertEquals(OWNER, MaidOwnership.ownerOf(fresh));
    }

    /** 维度传 null：这几条判据都不看它 */
    private static MaidOwnership.Maid maid(String name, UUID id) {
        return new MaidOwnership.Maid(id, name, null);
    }
}
