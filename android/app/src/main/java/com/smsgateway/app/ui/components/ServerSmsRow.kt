package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 服务端短信记录行组件。
 *
 * @param record 短信记录
 */
@Composable
fun ServerSmsRow(record: SmsRecord) {
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
    val (statusLabel, statusColor) = when (record.status?.uppercase()) {
        "RECEIVED" -> "已收下" to AppColor.Success
        "IGNORED" -> "被规则忽略" to AppColor.InkSecondary
        "PROCESSED" -> "已处理" to AppColor.Info
        else -> (record.status ?: "未知") to AppColor.InkMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.sender ?: "未知发送方",
                    style = AppTypography.bodyMedium,
                    color = AppColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(statusLabel, style = AppTypography.caption, color = statusColor)
            }
            Text(
                text = record.content ?: "",
                style = AppTypography.bodySmall,
                color = AppColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 验证码与收到时间并成一行：一行记录省下一行高度
            Row(verticalAlignment = Alignment.CenterVertically) {
                record.code?.takeIf { it.isNotBlank() }?.let {
                    Text("验证码 $it", style = AppTypography.bodySmall, color = AppColor.Ink)
                }
                Spacer(modifier = Modifier.weight(1f))
                record.receiveTime?.let {
                    Text(it.replace('T', ' ').take(19), style = AppTypography.caption, color = AppColor.InkMuted)
                }
            }
        }
    }
}
