package com.dingdinggo.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dingdinggo.app.core.PunchSchedule
import com.dingdinggo.app.ui.WifiUiState
import com.dingdinggo.app.ui.components.GlassSwitch
import com.dingdinggo.app.ui.components.SettingsDivider
import com.dingdinggo.app.ui.components.SettingsGroup
import com.dingdinggo.app.ui.components.SettingsRow
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 设置页 · 公司 Wi-Fi 自动打卡分组。
 */
@Composable
internal fun WifiSettingsGroup(
    wifi: WifiUiState,
    needsPermission: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onLearn: ((Boolean, String) -> Unit) -> Unit,
    onClear: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = GlassTheme.colors

    SettingsGroup(
        title = "公司 Wi-Fi 自动打卡",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "连上公司 Wi-Fi 自动触发打卡",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "熄屏下接入公司网络立即唤醒打卡，与考勤机蓝牙双通道互补",
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
            GlassSwitch(
                checked = wifi.enabled,
                onCheckedChange = onToggle,
            )
        }

        if (wifi.enabled) {
            if (needsPermission) {
                SettingsDivider()
                SettingsRow(
                    title = "Wi-Fi 定位权限待授权",
                    subtitle = "点击前往授予位置权限，保障息屏后台识别公司 Wi-Fi",
                    onClick = onRequestPermission,
                )
            }

            SettingsDivider()
            val learnedText = when {
                wifi.targetSsid.isNotBlank() && wifi.targetBssid.isNotBlank() ->
                    "当前目标：${wifi.targetSsid}（${wifi.targetBssid}）"
                wifi.targetSsid.isNotBlank() ->
                    "当前目标：${wifi.targetSsid}"
                wifi.targetBssid.isNotBlank() ->
                    "当前目标 BSSID：${wifi.targetBssid}"
                else -> "尚未记录公司 Wi-Fi，请连上公司 Wi-Fi 后点击记录"
            }
            val onlineHint = if (wifi.connected) " · 当前已连接" else ""
            SettingsRow(
                title = if (wifi.targetLabel.isNotBlank()) "更新公司 Wi-Fi" else "记录当前连接的 Wi-Fi",
                subtitle = learnedText + onlineHint,
                onClick = {
                    onLearn { _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            if (wifi.targetLabel.isNotBlank()) {
                SettingsDivider()
                SettingsRow(
                    title = "清除已记录的 Wi-Fi",
                    subtitle = if (wifi.lastTriggerAt > 0L && wifi.lastTriggerText.isNotBlank()) {
                        "最近触发：" + PunchSchedule.formatTime(wifi.lastTriggerAt) + " · " + wifi.lastTriggerText
                    } else {
                        "停止把该 Wi-Fi 识别为公司到达触发源"
                    },
                    onClick = onClear,
                )
            }

            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "共用上方蓝牙配置的「提前窗口」。到达公司时在早到窗口或正常窗内自动打卡；" +
                        "若今天首次触发已超迟到红线，会先弹窗并发通知由你确认是否打迟到卡。",
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
