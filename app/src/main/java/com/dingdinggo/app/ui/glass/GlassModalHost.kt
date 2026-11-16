package com.dingdinggo.app.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.dingdinggo.app.ui.theme.GlassMotion
import com.dingdinggo.app.ui.theme.GlassTheme

/**
 * 窗口内液态玻璃弹窗宿主状态。
 *
 * 与独立 Android `Dialog` Window 不同，宿主挂载在 [com.dingdinggo.app.MainActivity] 根布局最顶层：
 * 1. 背景模糊层直接覆盖状态栏与导航栏全屏（消除状态栏未模糊造成的割裂感）；
 * 2. 弹窗主体可直接通过 `LocalLiquidBackdrop` 对完整页面场景做真实高斯模糊与边缘 `lens` 折射。
 */
@Stable
class GlassModalHostState {
    internal val entries = mutableStateListOf<ModalEntry>()

    internal fun attach(entry: ModalEntry) {
        if (entries.none { it === entry }) {
            entries.add(entry)
        }
    }

    internal fun detach(entry: ModalEntry) {
        entries.remove(entry)
    }
}

internal class ModalEntry(
    val content: @Composable () -> Unit,
)

val LocalGlassModalHost = staticCompositionLocalOf<GlassModalHostState?> { null }

/**
 * 将弹窗内容注册到同窗口顶层宿主；若外部无宿主（如独立 Preview），返回 false 以便回退系统 Dialog。
 */
@Composable
fun rememberAttachToGlassModalHost(content: @Composable () -> Unit): Boolean {
    val host = LocalGlassModalHost.current ?: return false
    val latestContent by rememberUpdatedState(content)
    val entry = remember { ModalEntry { latestContent() } }

    DisposableEffect(host) {
        host.attach(entry)
        onDispose { host.detach(entry) }
    }
    return true
}

/** 放在 MainActivity 根 Box 顶层的全屏液态玻璃弹窗容器（含状态栏全屏模糊背板）。 */
@Composable
fun GlassModalHostLayer(
    state: GlassModalHostState,
    modifier: Modifier = Modifier,
) {
    if (state.entries.isEmpty()) return
    Box(modifier = modifier.fillMaxSize()) {
        state.entries.forEach { entry ->
            key(entry) {
                entry.content()
            }
        }
    }
}

/**
 * 全屏液态玻璃弹窗背板：覆盖状态栏与导航栏，实时模糊底层页面并柔和退避。
 */
@Composable
fun GlassModalScrimBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlassTheme.colors
    val backdrop = LocalLiquidBackdrop.current
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, GlassMotion.easeOut(220))
    }

    BackHandler(onBack = onDismiss)

    val p = progress.value
    val tintColor = if (colors.isDark) {
        Color(0xFF040914).copy(alpha = 0.30f * p)
    } else {
        Color(0xFFEAF2FC).copy(alpha = 0.20f * p)
    }

    val blurModifier = if (backdrop != null && !GlassPerfGuard.shouldDegrade && p > 0.01f) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = {
                val radiusPx = with(density) { (18.dp * p).toPx() }
                if (radiusPx > 0.5f) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        effect(
                            RenderEffect
                                .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                                .asComposeRenderEffect(),
                        )
                    } else {
                        blur(radiusPx)
                    }
                }
            },
            onDrawSurface = {
                drawRect(tintColor)
            },
        )
    } else {
        Modifier.background(
            if (colors.isDark) Color(0xFF040914).copy(alpha = 0.52f * p)
            else Color(0xFF0A1424).copy(alpha = 0.14f * p),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(blurModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        content = content,
    )
}
