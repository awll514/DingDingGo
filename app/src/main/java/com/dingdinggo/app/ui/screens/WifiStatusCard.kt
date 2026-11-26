package com.dingdinggo.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.ui.WifiUiState
import com.dingdinggo.app.ui.glass.FrostedPanel
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 首页公司 Wi-Fi 到达触发状态卡。
 */
@Composable
internal fun WifiStatusCard(
    wifi: WifiUiState,
    needsPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
) {
    val colors = GlassTheme.colors
    FrostedPanel(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "公司 Wi-Fi 自动打卡",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                val statusText = when {
                    needsPermission -> "待授权"
                    wifi.targetLabel.isBlank() -> "未记录"
                    wifi.connected -> "已连公司 Wi-Fi"
                    else -> "监听中"
                }
                val statusColor = when {
                    needsPermission -> colors.warning
                    wifi.connected -> colors.accent
                    else -> colors.textSecondary
                }

                if (needsPermission) {
                    Text(
                        text = "去授权",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onRequestPermission)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                } else {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val detail = when {
                needsPermission -> "缺少定位权限（或始终允许定位），后台可能无法读取 Wi-Fi 名称"
                wifi.targetLabel.isBlank() ->
                    "请在「设置 → 公司 Wi-Fi 自动打卡」中连接公司网络后记录一次"
                wifi.connected -> "已接入「${wifi.targetLabel}」"
                else -> "目标网络：${wifi.targetLabel}"
            }
            Text(
                text = detail,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )

            if (wifi.lastTriggerAt > 0L && wifi.lastTriggerText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = PunchSchedule.formatTime(wifi.lastTriggerAt) + " · " + wifi.lastTriggerText,
                    color = colors.textTertiary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
