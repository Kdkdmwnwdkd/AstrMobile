package com.astrmobile.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 告警规则：什么算异常、多久算掉线、什么时候不该再吵人。
 *
 * 这些规则如果写在 Service 里就只能靠"装到手机上手动拔网线"验证，
 * 所以抽成纯函数，用假时钟跑。
 */
class AlertPolicyTest {

    private val policy = AlertPolicy(offlineThresholdMs = 120_000, repeatSuppressMs = 600_000)

    private fun running(astrbot: Boolean = true, napcat: Boolean = true) =
        ContainerState.Running(astrbotReady = astrbot, napcatReady = napcat)

    private fun raises(effects: List<AlertPolicy.Effect>) =
        effects.filterIsInstance<AlertPolicy.Effect.Raise>()

    private fun clears(effects: List<AlertPolicy.Effect>) =
        effects.filterIsInstance<AlertPolicy.Effect.Clear>()

    @Test
    fun `QQ 掉线不到两分钟不提醒`() {
        assertTrue(policy.update(running(napcat = false), now = 0).isEmpty())
        assertTrue(policy.update(running(napcat = false), now = 119_000).isEmpty())
    }

    @Test
    fun `QQ 掉线超过两分钟提醒一次`() {
        policy.update(running(napcat = false), now = 0)

        val first = raises(policy.update(running(napcat = false), now = 121_000))
        assertEquals(1, first.size)
        assertEquals(AlertPolicy.KEY_QQ_OFFLINE, first.first().key)
        assertTrue(first.first().critical)
    }

    @Test
    fun `掉线提醒十分钟内不重复`() {
        policy.update(running(napcat = false), now = 0)
        policy.update(running(napcat = false), now = 121_000)

        assertTrue(policy.update(running(napcat = false), now = 300_000).isEmpty())

        // 超过抑制窗口后允许再提醒一次
        assertEquals(1, raises(policy.update(running(napcat = false), now = 730_000)).size)
    }

    @Test
    fun `QQ 恢复后撤销掉线通知`() {
        policy.update(running(napcat = false), now = 0)
        policy.update(running(napcat = false), now = 121_000)

        val effects = policy.update(running(napcat = true), now = 200_000)

        assertEquals(listOf(AlertPolicy.KEY_QQ_OFFLINE), clears(effects).map { it.key })
        assertTrue(raises(effects).isEmpty())
    }

    @Test
    fun `运行失败会提醒并带上原因`() {
        val effects = raises(policy.update(ContainerState.Failed("apt 安装失败"), now = 0))

        assertEquals(1, effects.size)
        assertEquals(AlertPolicy.KEY_FAILED, effects.first().key)
        assertEquals("apt 安装失败", effects.first().body)
    }

    @Test
    fun `失败状态持续时不反复弹`() {
        policy.update(ContainerState.Failed("崩了"), now = 0)

        assertTrue(policy.update(ContainerState.Failed("崩了"), now = 30_000).isEmpty())
    }

    @Test
    fun `重启中只提醒一次不刷屏`() {
        val restarting = ContainerState.Restarting(
            attempt = 1, maxAttempts = 3, delaySeconds = 5, lastReason = "exit=1",
        )

        val first = raises(policy.update(restarting, now = 0))
        assertEquals(AlertPolicy.KEY_RESTARTING, first.first().key)
        assertTrue("重启不该用高优先级打扰", !first.first().critical)

        assertTrue(policy.update(restarting, now = 60_000).isEmpty())
    }

    @Test
    fun `从失败恢复到运行会撤销失败通知`() {
        policy.update(ContainerState.Failed("崩了"), now = 0)

        val effects = policy.update(running(), now = 60_000)

        assertEquals(listOf(AlertPolicy.KEY_FAILED), clears(effects).map { it.key })
    }

    @Test
    fun `失败时不再保留 QQ 掉线通知`() {
        policy.update(running(napcat = false), now = 0)
        policy.update(running(napcat = false), now = 121_000)

        val effects = policy.update(ContainerState.Failed("容器挂了"), now = 130_000)

        assertEquals(listOf(AlertPolicy.KEY_QQ_OFFLINE), clears(effects).map { it.key })
        assertEquals(AlertPolicy.KEY_FAILED, raises(effects).single().key)
    }

    @Test
    fun `正常启停不打扰`() {
        assertTrue(policy.update(ContainerState.Starting, now = 0).isEmpty())
        assertTrue(policy.update(ContainerState.Stopping, now = 1_000).isEmpty())
        assertTrue(policy.update(ContainerState.Stopped, now = 2_000).isEmpty())
        assertTrue(policy.update(ContainerState.NotInstalled, now = 3_000).isEmpty())
    }

    @Test
    fun `停止后撤销所有未处理的告警`() {
        policy.update(ContainerState.Failed("崩了"), now = 0)

        val effects = policy.update(ContainerState.Stopped, now = 10_000)

        assertEquals(listOf(AlertPolicy.KEY_FAILED), clears(effects).map { it.key })
    }
}
