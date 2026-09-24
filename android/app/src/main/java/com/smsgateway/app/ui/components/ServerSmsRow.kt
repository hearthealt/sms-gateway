package com.smsgateway.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatServerTime

/**
 * 服务端短信记录行组件。
 *
 * 与队列行、日志行是同一套骨架：16dp 内边距、[StatusBadge] 表状态、
 * [com.smsgateway.app.ui.utils.formatClockTime] 那套时间格式。这三种行原先各写各的
 * （内边距 16/16/12、时间有「今天只给时刻」也有完整的 `yyyy-MM-dd HH:mm:ss`），
 * 用户在同一份数据上看到两种时间写法，会以为是两个来源。
 *
 * @param record 短信记录
 * @param onCopyCode 点验证码芯片时回调（复制这一条）。
 */
@Composable
fun ServerSmsRow(record: SmsRecord, onCopyCode: (String) -> Unit = {}) {
    // 服务端的判定：这是本地数据看不到的信息
    //
    // **不判 DUPLICATE**：库里不会有这个状态 —— 重复到达不新插行，服务端撞上
    // uk_device_source_hash 之后只在原行上累加 duplicate_count，所以「重复」是那一行的
    // 一个属性、不是一种状态，它的 status 仍然是 RECEIVED 或 IGNORED。
    // 原先这里有一条「内容重复」的分支，永远走不到，已删。
    // 「重复了几次」归管理端表达（SmsList 的「采集」列显示 duplicateCount）：
    // 设备端这份记录连那个字段都没有，也不该由设备端去解释「被重放 / 双卡各收了一遍」。
    //
    // PROCESSED 在 SmsStatus 里只有声明、后端没有任何一处写它，同样到不了。留着它是
    // 为了将来真出现时不会把英文枚举名漏给用户（漏到 else 分支上就是这个后果）。
    val (statusLabel, statusType) = when (record.status?.uppercase()) {
        "RECEIVED" -> "已收下" to StatusBadgeType.Success
        "IGNORED" -> "被规则忽略" to StatusBadgeType.Default
        "PROCESSED" -> "已处理" to StatusBadgeType.Info
        else -> (record.status ?: "未知") to StatusBadgeType.Default
    }

    AppCard(
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = record.sender ?: "未知发送方",
                style = AppTypography.bodyLarge,
                color = AppColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            StatusBadge(text = statusLabel, type = statusType)
        }

        Text(
            text = record.content ?: "",
            style = AppTypography.bodyMedium,
            color = AppColor.Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        // 验证码与收到时间并成一行：一行记录省下一行高度
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 验证码**点一下复制这一条**（不是整页的码）。
            // 原先这一格不可点，而整页只有顶栏那个「复制今日验证码」——
            // 想拿走眼前这一条的人点下去会复制到几十条，然后一脸问号。
            record.code?.takeIf { it.isNotBlank() }?.let { code ->
                CodeChip(code = code, onCopy = { onCopyCode(code) })
            }
            Spacer(modifier = Modifier.weight(1f))
            formatServerTime(record.receiveTime)?.let {
                Text(it, style = AppTypography.caption, color = AppColor.InkMuted)
            }
        }
    }
}

/**
 * 验证码芯片。
 *
 * 验证码是这一页**唯一要拿去用的东西**，原先它只是一段普通正文字（`验证码 123456`，
 * bodySmall、无底色、点击区域约 22dp、也看不出能点）。三件事一起改：
 *
 * - **看起来像要拿走的东西**：InfoBg 底、等宽 h3（等宽是为了逐位核对，`0/O`、`1/l`
 *   在比例字体里分不清）、右侧一个复制图标。
 * - **点得到**：[minimumInteractiveComponentSize] 把触控目标撑到 48dp。
 *   这台手机常挂在工位上，点它的人多半是一只手、一眼扫。
 * - **读屏读得出**：复制图标带 contentDescription，否则 TalkBack 只会读出一个数字。
 *
 * 视觉上的芯片保持小尺寸（48dp 高的色块会比整行还高），所以外面那层 48dp 只负责
 * 接手势，背景与圆角画在内层。
 */
@Composable
private fun CodeChip(code: String, onCopy: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(AppColor.BadgeShape)
            .clickable(onClick = onCopy),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .background(color = AppColor.InfoBg, shape = AppColor.BadgeShape)
                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = code,
                style = AppTypography.mono(AppTypography.h3),
                color = AppColor.Info
            )
            Spacer(modifier = Modifier.width(AppSpacing.xxs))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制验证码",
                tint = AppColor.Info,
                modifier = Modifier.size(AppSize.iconXs)
            )
        }
    }
}
