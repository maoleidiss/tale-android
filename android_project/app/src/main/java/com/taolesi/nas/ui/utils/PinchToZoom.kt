package com.taolesi.nas.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 手势滑动缩放动态调节网格列数 (Pinch-to-zoom columns)
 * 移植自魔改相册算法：双指捏合/张开实时调节 1~5 栏布局，手感平滑阻尼，防误触。
 *
 * @param currentColumns 当前栏数 (1: 单列大图, 2: 双列瀑布流, 3: 三列网格, 4: 四列紧凑, 5: 五列微缩)
 * @param onColumnsChange 栏数切换回调
 * @param minColumns 最小栏数 (默认 1)
 * @param maxColumns 最大栏数 (默认 5)
 */
fun Modifier.pinchToZoomColumns(
    currentColumns: Int,
    onColumnsChange: (Int) -> Unit,
    minColumns: Int = 1,
    maxColumns: Int = 5
): Modifier = composed {
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(currentColumns) {
        zoomAccumulator = 1f
    }

    this.pointerInput(currentColumns) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val changes = event.changes.filter { it.pressed }

                if (changes.size >= 2) {
                    val change1 = changes[0]
                    val change2 = changes[1]

                    val prevDistance = (change1.previousPosition - change2.previousPosition).getDistance()
                    val currDistance = (change1.position - change2.position).getDistance()

                    if (prevDistance > 0) {
                        val zoom = currDistance / prevDistance

                        // 灵敏度阈值：检测到捏合手势即拦截纵向滚动
                        if (abs(1f - zoom) > 0.001f) {
                            changes.forEach { it.consume() }
                            zoomAccumulator *= zoom

                            // 双指张开放大 -> 减少列数 (照片变大)
                            if (zoomAccumulator > 1.30f) {
                                if (currentColumns > minColumns) {
                                    onColumnsChange(currentColumns - 1)
                                    zoomAccumulator = 1f
                                } else {
                                    zoomAccumulator = 1.15f
                                }
                            }
                            // 双指捏合缩小 -> 增加列数 (照片变小，更密集)
                            else if (zoomAccumulator < 0.70f) {
                                if (currentColumns < maxColumns) {
                                    onColumnsChange(currentColumns + 1)
                                    zoomAccumulator = 1f
                                } else {
                                    zoomAccumulator = 0.85f
                                }
                            }
                        }
                    }
                } else {
                    zoomAccumulator = 1f
                }
            }
        }
    }
}
