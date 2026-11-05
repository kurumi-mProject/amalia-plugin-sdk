@file:JvmName("AmaliaScaffoldKt")
@file:Suppress("UNUSED_PARAMETER")

package com.my.amali.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compile-only host ABI. contracts/build.gradle.kts emits each host file facade. */
@Composable
fun AmaliaScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Назад",
    actions: @Composable () -> Unit = {},
    compactHeader: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) { error("Compile-only host ABI") }

// GLASS_CARD_FACADE
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    contentPadding: Dp = 0.dp,
    elevated: Boolean = false,
    tint: Color = Color.Unspecified,
    castsShadow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) { error("Compile-only host ABI") }
