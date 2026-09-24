package com.smsgateway.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.util.DiagnosticExporter

/**
 * 诊断包的预览。
 *
 * **先看得见，再决定要不要发出去。** 原先点一下就直接弹分享面板 —— 那意味着
 * 用户没有任何办法确认里面到底有什么。而这份文件会离开设备，能不能发、
 * 发之前要不要扫一眼，本来就该由人看着内容决定。
 *
 * 同时它也是「不分享、就想自己看看」的入口：内容可滚动、可长按选中复制。
 */
@Composable
fun DiagnosticPreviewDialog(
    result: DiagnosticExporter.Result,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = AppColor.Card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md)
            ) {
                Text("诊断包", style = AppTypography.h3, color = AppColor.Ink)
                Text(
                    text = result.fileName,
                    style = AppTypography.caption,
                    color = AppColor.InkMuted,
                    modifier = Modifier.padding(top = AppSpacing.xxs)
                )

                // 正文用等宽字体：这份文件是**按列对齐**的（队列那一段是 | 分隔的表），
                // 比例字体下会错位到读不出来。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.sm)
                        // 高度给个上限：内容是几百行，全铺开会把按钮挤出屏幕。
                        // 超出部分自己滚 —— 竖着滚行、横着滚长行（消息里的一行可能很长）。
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColor.Screen)
                ) {
                    SelectionContainer {
                        Text(
                            text = result.text,
                            style = AppTypography.caption.copy(fontFamily = FontFamily.Monospace),
                            color = AppColor.InkSecondary,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(AppSpacing.sm)
                        )
                    }
                }

                Text(
                    text = "长按可选中复制。文件已存在应用缓存里，「分享」把它交给别的应用。",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm, Alignment.End)
                ) {
                    AppTextButton(onClick = onDismiss) { Text("关闭") }
                    AppButton(onClick = onShare) { Text("分享") }
                }
            }
        }
    }
}
