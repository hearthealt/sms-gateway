package com.smsgateway.app.util

import com.smsgateway.app.model.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 远程指令的本地判定规则。
 *
 * 这里的两处边界都是「差一点就错、错了不会报错」的那种：
 * - 等于过期时刻算不算过期（设备与服务端的边界必须一致）
 * - 探测心跳允许执行哪些指令
 */
class CommandRulesTest {

    /** 与 [ServerTime.parseInstant] 同一套解释：ISO 本地时间 + 本机时区。 */
    private fun epochOf(iso: String): Long =
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun expiredExactlyAtExpiryInstant() {
        val iso = "2026-09-24T13:40:00"
        val at = epochOf(iso)

        // **到点即过期**，用的是 >= 而不是 >。差这一个等号会让一条「还有 0 秒有效」的
        // 指令被执行，而服务端那边的清理任务正是以 expires_at <= now 判它过期的 ——
        // 两边的边界不一致，就会留下「设备执行了、服务端认为它早已过期」的记录。
        assertTrue(CommandRules.isExpired(at, iso))
        assertTrue(CommandRules.isExpired(at + 1, iso))
        assertFalse(CommandRules.isExpired(at - 1, iso))
    }

    @Test
    fun unparsableExpiryIsTreatedAsNotExpired() {
        // 宁可执行一次管理员确实下发的指令，也不要因为一个格式变化把它静默丢掉 ——
        // 后者在现场的表现是「点了没反应」，而前者至少做了它该做的事。
        assertFalse(CommandRules.isExpired(System.currentTimeMillis(), null))
        assertFalse(CommandRules.isExpired(System.currentTimeMillis(), ""))
        assertFalse(CommandRules.isExpired(System.currentTimeMillis(), "上午十点"))
    }

    @Test
    fun probeAllowsOnlyStartGateway() {
        // 用户停网关的意图是「这台机器冻结住，短信留在本地」。从一个他看不见的后台
        // 任务里执行「清理已上传记录」「改本机号码」是在违背这个意图改本机状态。
        assertTrue(CommandRules.probeAllows(CommandType.START_GATEWAY))

        assertFalse(CommandRules.probeAllows(CommandType.STOP_GATEWAY))
        assertFalse(CommandRules.probeAllows(CommandType.SET_PHONE))
        assertFalse(CommandRules.probeAllows(CommandType.REUPLOAD))
        assertFalse(CommandRules.probeAllows(CommandType.CLEAR_UPLOADED))
        assertFalse(CommandRules.probeAllows(CommandType.RE_REGISTER))
        // 服务端将来加的新类型默认不在探测里放行 —— 白名单而不是黑名单。
        assertFalse(CommandRules.probeAllows("SOMETHING_NEW"))
    }

    @Test
    fun normalizeArgumentTrimsAndNullifiesBlank() {
        assertEquals("13800138000", CommandRules.normalizeArgument("  13800138000 "))
        // 空串归一成 null 而不是留着空串：空串在服务端是「传了参数但没内容」，
        // 会被当成校验错误，而设备真正的意思是「没有参数」。
        assertNull(CommandRules.normalizeArgument(""))
        assertNull(CommandRules.normalizeArgument("   "))
        assertNull(CommandRules.normalizeArgument(null))
    }
}
