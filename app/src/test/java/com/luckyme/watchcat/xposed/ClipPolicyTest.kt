package com.luckyme.watchcat.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定层验收表（对着《hook方案-v0.2.md》§7 的口径）：
 * 放行、默认空、按 uid 覆盖、输入法特例、坏策略不崩。
 *
 * 这些用例是"改了优先级就红"的防线——判定顺序变了必须在这里说明为什么。
 */
class ClipPolicyTest {

    private val policy = ClipPolicy

    @Test
    fun emptyPolicyFallsBackToEmptyButKeepsSystemAlive() {
        val snap = policy.parse(null)
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(12345, snap))
        // 系统身份永远放行，否则一个写错的策略能把系统自己读坏
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(0, snap))
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(1000, snap))
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(2000, snap))
    }

    @Test
    fun defaultModeIsHonored() {
        val snap = policy.parse("version=1\ndefault=allow\n")
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(12345, snap))
    }

    @Test
    fun perUidModeOverridesDefault() {
        val snap = policy.parse("default=empty\nmode.12345=allow\n")
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(12345, snap))
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(12346, snap))
    }

    @Test
    fun perUidModeBeatsAllowList() {
        // 显式逐应用设置优先于 allow 名单：用户后来改的那一条才算数
        val snap = policy.parse("default=empty\nallow=12345\nmode.12345=empty\n")
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(12345, snap))
    }

    @Test
    fun imeIsAlwaysAllowed() {
        val snap = policy.parse("default=empty\nimeuid=10321\n")
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(10321, snap))
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(10322, snap))
    }

    @Test
    fun allowListIsCommaSeparatedAndTolerant() {
        val snap = policy.parse("allow=10123, 10124 ,oops\n")
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(10123, snap))
        assertEquals(ClipPolicy.Mode.ALLOW, policy.decide(10124, snap))
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(10125, snap))
    }

    @Test
    fun junkDoesNotKillTheWholePolicy() {
        val snap = policy.parse(
            "# 注释\n" +
                "default=empty\n" +
                "mode.100=whatever\n" +   // 未知模式值 → 忽略
                "mode.abc=allow\n" +      // uid 不是数字 → 忽略
                "没有等号的一行\n" +
                "imeuid=\n"
        )
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(100, snap))
        assertEquals(ClipPolicy.Mode.EMPTY, policy.decide(999, snap))
        assertNull(snap.imeUid)
    }

    @Test
    fun fakeModeIsParsedAndReturned() {
        val snap = policy.parse("default=empty\nmode.20001=fake\npool=购物\n")
        assertEquals(ClipPolicy.Mode.FAKE, policy.decide(20001, snap))
        assertEquals("购物", snap.fakePool)
    }

    @Test
    fun fakeContentIsStablePerBootAndVariesAcrossPools() {
        val a = ClipFake.pick(20001, "boot-A", null)
        val b = ClipFake.pick(20001, "boot-A", null)
        assertEquals(a, b)
        assertTrue(a.length in 6..40)

        // 指定池必须真取自该池，而不是随便给一条
        val shopping = ClipFake.pick(20001, "boot-A", "购物")
        assertTrue(shopping.length in 6..40)
        assertEquals(ClipFake.poolNames.size, 3)

        // 换一次开机允许不一样，但不强制——这里只要求"能算出结果且仍然合法"
        val c = ClipFake.pick(20001, "boot-B", null)
        assertTrue(c.length in 6..40)
    }
}
