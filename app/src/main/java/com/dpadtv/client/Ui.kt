package com.dpadtv.client

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable

/**
 * 全站视觉规范：颜色、间距、圆角只在这里定义一次。
 *
 * 为什么单独抽出来：原先同一个「面板色」以 #1F2937 / #111827 / #1a2233 三个近似值
 * 散落在各处，改一次配色要全文搜索，还容易改漏。现在只动这一个文件。
 *
 * 控件组装仍留在 MainActivity —— 那里有 applyFocusStyle 负责焦点态与 padding 还原，
 * 两者职责分开：这里管「长什么样」，那里管「聚焦时怎么变」。
 */
object Ui {

    /* 背景分三层：页面底 / 卡片 / 浮起的控件 */
    val BG = 0xFF0B101A.toInt()
    val SURFACE = 0xFF141C2A.toInt()
    val SURFACE_HI = 0xFF1E293B.toInt()
    val STROKE = 0xFF2A3A52.toInt()

    /* 文字按重要程度分三级（再加一级极弱用于页脚） */
    val TEXT = 0xFFF1F5F9.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val TEXT_FAINT = 0xFF64748B.toInt()

    /* 强调色与语义色 */
    val ACCENT = 0xFF38BDF8.toInt()
    val ON_ACCENT = 0xFF04121B.toInt()
    val BRIGHT = 0xFFE0F2FE.toInt()
    val DANGER = 0xFFF87171.toInt()
    val OK = 0xFF4ADE80.toInt()

    /* 间距（dp） */
    const val PAD_H = 40
    const val PAD_V = 24
    const val GAP_S = 10
    const val GAP_M = 16

    /* 圆角（dp） */
    const val R_CARD = 12
    const val R_BTN = 10

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 圆角纯色背景；insetDp > 0 时四周留白，相邻控件之间自然有缝 */
    fun bg(
        ctx: Context,
        color: Int,
        radiusDp: Int = R_BTN,
        insetDp: Int = 0,
        strokeDp: Int = 0,
        strokeColor: Int = 0,
    ): Drawable {
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }
        return if (insetDp > 0) InsetDrawable(shape, dp(ctx, insetDp)) else shape
    }
}
