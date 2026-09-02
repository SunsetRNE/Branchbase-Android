package com.branchbase.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primer.Blue500,
    secondary = Primer.Green500,
    background = Primer.BackgroundPrimary,
    surface = Primer.BackgroundSecondary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onBackground = Primer.TextPrimary,
    onSurface = Primer.TextPrimary,
    error = Primer.Red500,
)

@Composable
fun BranchbaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}