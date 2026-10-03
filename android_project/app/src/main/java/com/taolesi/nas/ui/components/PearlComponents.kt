package com.taolesi.nas.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.taolesi.nas.ui.theme.*

/**
 * 珍珠白 3D 立体磨砂玻璃卡片容器
 */
@Composable
fun PearlCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = Color(0x22475569),
                spotColor = Color(0x33475569)
            )
            .clip(shape)
            .background(PearlCardBrush)
            .border(1.2.dp, Color(0xF5FFFFFF), shape)
    ) {
        // 顶部漫反射高光层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x66FFFFFF), Color(0x00FFFFFF))
                    )
                )
        )
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

/**
 * 悬浮独立胶囊 Dock 项
 */
data class DockNavigationItem(
    val title: String,
    val icon: ImageVector
)

/**
 * 珍珠白 3D 立体悬浮胶囊 Dock (带 Q 弹 Spring 动效)
 */
@Composable
fun PearlFloatingDock(
    items: List<DockNavigationItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dockShape = RoundedCornerShape(34.dp)
    val view = androidx.compose.ui.platform.LocalView.current

    Box(
        modifier = modifier
            .width(342.dp)
            .height(66.dp)
            .shadow(
                elevation = 24.dp,
                shape = dockShape,
                ambientColor = Color(0x33334155),
                spotColor = Color(0x44334155)
            )
            .clip(dockShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xF5FFFFFF), Color(0xEBFFFFFF))
                )
            )
            .border(1.2.dp, Color(0xFAFFFFFF), dockShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                // Q 弹 Spring 物理缩放
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.88f else if (isSelected) 1.05f else 1.0f,
                    animationSpec = JellySpringSpec,
                    label = "dock_scale"
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(20.dp))
                        .then(
                            if (isSelected) {
                                Modifier
                                    .shadow(6.dp, RoundedCornerShape(20.dp), spotColor = Color(0x3364748B))
                                    .background(Color.White)
                                    .border(1.dp, Color(0xE0FFFFFF), RoundedCornerShape(20.dp))
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            if (!isSelected) {
                                try {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                } catch (_: Exception) {}
                            }
                            onItemSelected(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        tint = if (isSelected) AccentBlue else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(AccentBlue)
                        )
                    }
                }
            }
        }
    }
}
