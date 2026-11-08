package com.openproxy.client.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E1),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFF426277),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DE2C3),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF9BFFE2),
    secondary = Color(0xFFB0CCC2),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCDE8DF),
    tertiary = Color(0xFFA9CBE3),
    onTertiary = Color(0xFF0B3446),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE1E9E5),
    surface = Color(0xFF0B1120),
    onSurface = Color(0xFFE1E9E5),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenProxyTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
