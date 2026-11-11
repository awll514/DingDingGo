package com.redmi.autoattend.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.glass.FrostedPanel
import com.redmi.autoattend.ui.theme.GlassDimens
import com.redmi.autoattend.ui.theme.GlassTheme
import kotlin.math.roundToInt

/**
 * 设置页的分组卡片。
 *
 * 每个分组都是轻量磨砂（不是 AGSL 全管线）——设置页有 6 个分组，
 * 全用 AGSL 会直接击穿性能红线。
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = GlassTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.textTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 9.dp),
        )
        FrostedPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = GlassDimens.radiusMedium,
            // 【上边距】之前是 vertical = 2.dp，导致第一行文字几乎贴着
            // 玻璃卡片的上边框，看起来像被裁掉了。
            //
            // 改成 top = 10.dp / bottom = 6.dp：
            // - top 比 bottom 大，因为第一行上方没有分隔线做视觉缓冲；
            // - 底部留 6dp，最后一行不会贴着下边框；
            // - 中间行之间由 SettingsDivider 自己带 padding，不受影响。
            contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp),
            // 按 Apple Liquid Glass 的 .regular 材质：
            // 玻璃透明度 20%~40%，靠模糊 + 描边保证可读，而不是靠厚填充。
            // 之前 0.62 的白色接近实心卡片，和"玻璃"的语义相反。
            tint = if (colors.isDark) {
                Color(0xFF0A1424).copy(alpha = 0.32f)
            } else {
                Color.White.copy(alpha = 0.40f)
            },
            borderAlpha = if (colors.isDark) 0.20f else 0.66f,
        ) {
            Column { content() }
        }
    }
}

/** 分组内的可点击行 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    danger: Boolean = false,
) {
    val colors = GlassTheme.colors

    // 【顺序必须是 padding 在前、clickable 在后】
    //
    // Modifier 链是按顺序包裹的：写在 clickable 之后的 padding 属于**内层**，
    // 也就是说可点击区域只覆盖内容，两侧 16dp 与上下 13dp 的留白点不动。
    // 真机上表现为「点这一行的边缘 / 空白处没反应」，
    // 设置页里「锁屏密码」这种重要入口因此很难点开。
    //
    // 正确顺序：先 padding 撑出整行高度，再 clickable 覆盖整行（含留白）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Apple 列表行：内边距 16dp，行高由内容决定但通常 48-56dp
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .then(if (onClick != null) Modifier.ClickableNoRipple(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                // Apple：正文 17pt Regular；中文用 15sp Medium 更贴合视觉重量
                color = if (danger) colors.error else colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    // Apple 的 secondary text：13pt，行高 18
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        if (value != null) {
            Text(
                text = value,
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Chevron(colors.textTertiary.copy(alpha = 0.7f))
        }
    }
}

/** 右向箭头 */
@Composable
fun Chevron(color: Color) {
    Text(
        text = "›",
        color = color,
        fontSize = 19.sp,
        fontWeight = FontWeight.Light,
    )
}

/** 分组内分隔线 */
@Composable
fun SettingsDivider() {
    val colors = GlassTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(1.dp)
            .background(
                if (colors.isDark) {
                    Color.White.copy(alpha = 0.05f)
                } else {
                    Color(0xFF2B3A4A).copy(alpha = 0.07f)
                }
            )
    )
}

/**
 * 玻璃开关。
 *
 * 不用 Material Switch——它有固定的 track/thumb 尺寸和颜色，和玻璃语境不搭。
 * 自己画：一个胶囊轨道 + 一个会 spring 移动的圆点。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors

    val trackColor = if (checked) {
        colors.accent.copy(alpha = if (colors.isDark) 0.32f else 0.42f)
    } else {
        if (colors.isDark) Color.White.copy(alpha = 0.10f)
        else Color(0xFF2B3A4A).copy(alpha = 0.10f)
    }

    // 【iOS 18 标准】不用 spring。
    //
    // spring 的过冲在开关这种"明确的两态切换"里显得毛躁：
    // 圆点会先冲过头再回弹，看起来像在抖动。
    // iOS 的开关是一条平滑减速曲线，落点稳。
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 3.dp,
        animationSpec = tween(
            durationMillis = 300,
            easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f),
        ),
        label = "thumb",
    )

    Box(
        modifier = modifier
            .width(46.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .ClickableNoRipple { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .padding(top = 3.dp)
                .width(20.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else Color.White.copy(alpha = 0.85f)),
        )
    }
}

/**
 * 星期选择器：7 个可点的方块，横排。
 *
 * @param selected Calendar.DAY_OF_WEEK 风格：周日=1 ... 周六=7
 */
@Composable
fun WeekdaySelector(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors
    // 按周一开头排列，更符合国内习惯
    val order = listOf(2, 3, 4, 5, 6, 7, 1)
    val labels = mapOf(
        1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        order.forEach { day ->
            val isOn = day in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isOn) {
                            colors.accent.copy(alpha = if (colors.isDark) 0.24f else 0.20f)
                        } else {
                            if (colors.isDark) Color.White.copy(alpha = 0.06f)
                            else Color(0xFF2B3A4A).copy(alpha = 0.05f)
                        }
                    )
                    .ClickableNoRipple { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labels[day] ?: "",
                    color = if (isOn) colors.accent else colors.textTertiary,
                    fontSize = 13.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 分段选择器 */
@Composable
fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GlassTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (colors.isDark) Color.White.copy(alpha = 0.06f)
                else Color(0xFF2B3A4A).copy(alpha = 0.05f)
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isOn = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isOn) {
                            colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.18f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .ClickableNoRipple { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isOn) colors.accent else colors.textTertiary,
                    fontSize = 12.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}