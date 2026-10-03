package com.taolesi.nas.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 珍珠白背景与主色调
val PearlCanvasBg = Color(0xFFF6F8FC)
val PearlCardBg = Color(0xEFFFFFFF)
val PearlCardBgSolid = Color(0xFFFFFFFF)

// 文本阶梯
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF334155)
val TextMuted = Color(0xFF64748B)
val TextSubtle = Color(0xFF94A3B8)

// 功能与品牌色
val AccentBlue = Color(0xFF0284C7)
val AccentCyan = Color(0xFF06B6D4)
val AccentEmerald = Color(0xFF10B981)
val AccentIndigo = Color(0xFF6366F1)
val AccentPurple = Color(0xFF8B5CF6)
val AccentAmber = Color(0xFFF59E0B)

// 珍珠白 3D 凸面渐变
val PearlCardBrush = Brush.linearGradient(
    listOf(
        Color(0xFAFFFFFF),
        Color(0xC8FFFFFF)
    )
)

// 进度条与高亮光晕
val StorageBarBrush = Brush.horizontalGradient(
    listOf(
        AccentBlue,
        AccentCyan
    )
)

// Q 弹 Spring 动效物理规范 (k=450, z=0.75)
val JellySpringSpec = spring<Float>(
    dampingRatio = 0.75f,
    stiffness = 450f
)
