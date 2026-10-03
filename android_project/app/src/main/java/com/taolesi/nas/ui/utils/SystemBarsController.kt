package com.taolesi.nas.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android 系统状态栏（时间、电池、信号等 System Bars）动态收纳与沉浸式控制器
 */
object SystemBarsController {

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * 隐藏系统状态栏 (向上滑动浏览相片时全沉浸)
     */
    fun hideSystemBars(context: Context) {
        val activity = findActivity(context) ?: return
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    /**
     * 恢复系统状态栏 (向下滑动时恢复)
     */
    fun showSystemBars(context: Context) {
        val activity = findActivity(context) ?: return
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
}
