package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.nahida.touchmap.model.KeyShape
import com.nahida.touchmap.model.VirtualKey

/**
 * 映射目标标记（游戏层可视化）：
 * 编辑模式下，在目标坐标位置显示与虚拟按键同形状的虚线框，
 * 让用户一眼看到「虚拟按键 -> 游戏按键」的对应关系（两层结构）。
 * 橙色 = 游戏层目标，蓝色 = 虚拟层按键。
 */
class TargetMarkerView(
    context: Context,
    private val key: VirtualKey
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x40FF9800.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF9800")
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#CCFF9800")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 11f * context.resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFF9800")
    }
    private val shape = key.shape

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) / 2f - 6f

        when (shape) {
            KeyShape.CIRCLE -> {
                canvas.drawCircle(cx, cy, half, fillPaint)
                canvas.drawCircle(cx, cy, half, strokePaint)
            }
            KeyShape.RECTANGLE -> {
                val rw = width / 2f - 6f
                val rh = height / 2f - 6f
                val rect = RectF(cx - rw, cy - rh, cx + rw, cy + rh)
                canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
                canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
            }
        }

        // 中心十字
        canvas.drawLine(cx - half * 0.4f, cy, cx + half * 0.4f, cy, crossPaint)
        canvas.drawLine(cx, cy - half * 0.4f, cx, cy + half * 0.4f, crossPaint)

        // 「目标」标签（底部小圆角标签，标明这是游戏层映射目标）
        val label = "目标·${key.label}"
        val tw = labelPaint.measureText(label) + 12f
        val th = labelPaint.textSize + 6f
        val lx = cx
        val ly = height + th / 2f + 4f
        canvas.drawRoundRect(RectF(lx - tw / 2f, ly - th / 2f, lx + tw / 2f, ly + th / 2f), th / 2f, th / 2f, labelBgPaint)
        canvas.drawText(label, lx, ly + labelPaint.textSize / 3f, labelPaint)
    }
}
