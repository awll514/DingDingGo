package com.redmi.autoattend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redmi.autoattend.ui.glass.FrostedPanel
import com.redmi.autoattend.ui.glass.GlassRadius
import com.redmi.autoattend.ui.theme.GlassTheme

/**
 * 锁屏密码输入组件。
 *
 * 安全设计（硬要求）：
 * 1. 用 PasswordVisualTransformation，屏幕上不出现明文；
 * 2. 只接受数字，和 shell 输密码的路径一致；
 * 3. 输满指定位数后自动回调，不需要额外点"确定"（小米数字锁屏也是这个行为）；
 * 4. 组件不持久化任何东西，回调后由调用方立刻写入 EncryptedSharedPreferences，
 *    明文只在这个 Composable 的一帧内存里存在。
 */
@Composable
fun PasswordInput(
    length: Int = 6,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    FrostedPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = GlassRadius.Medium,
        contentPadding = PaddingValues(vertical = 22.dp),
        tint = Color.White.copy(alpha = 0.08f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "输入 " + length.toString() + " 位数字密码",
                color = GlassTheme.colors.textTertiary,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(length) { i ->
                        val filled = i < value.length
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) GlassTheme.colors.accent
                                    else Color.White.copy(alpha = 0.16f)
                                ),
                        )
                    }
                }

                BasicTextField(
                    value = value,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(length)
                        value = digits
                        if (digits.length == length) {
                            onComplete(digits)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontSize = 1.sp,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            if (value.isNotEmpty() && value.length < length) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "还差 " + (length - value.length).toString() + " 位",
                    color = GlassTheme.colors.textTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** 单行数字输入（改密码、测试用） */
@Composable
fun GlassNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 6,
    masked: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = GlassTheme.colors.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        FrostedPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = GlassRadius.Small,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            tint = Color.White.copy(alpha = 0.07f),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(maxLength)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                ),
                textStyle = TextStyle(
                    color = GlassTheme.colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                ),
                cursorBrush = SolidColor(GlassTheme.colors.accent),
                visualTransformation = if (masked) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = "请输入",
                            color = GlassTheme.colors.textTertiary,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}