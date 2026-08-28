package com.photomonster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── カラーパレット ─────────────────────────────────────────────────────────

/** ダークモード用カラースキーム */
private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF82AAFF),   // ライトブルー
    onPrimary        = Color(0xFF00265E),
    primaryContainer = Color(0xFF003E8A),
    secondary        = Color(0xFF64FFDA),   // ティールアクセント
    onSecondary      = Color(0xFF003829),
    background       = Color(0xFF0D1117),   // GitHub ダーク風
    onBackground     = Color(0xFFE6EDF3),
    surface          = Color(0xFF161B22),
    onSurface        = Color(0xFFE6EDF3),
    surfaceVariant   = Color(0xFF21262D),
    error            = Color(0xFFFF6E6E),
)

/** ライトモード用カラースキーム */
private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF0969DA),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAEEFF),
    secondary        = Color(0xFF0D9488),
    onSecondary      = Color(0xFFFFFFFF),
    background       = Color(0xFFF6F8FA),
    onBackground     = Color(0xFF1F2328),
    surface          = Color(0xFFFFFFFF),
    onSurface        = Color(0xFF1F2328),
    surfaceVariant   = Color(0xFFEFF2F5),
    error            = Color(0xFFCF222E),
)

@Composable
fun PhotoMonsterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
