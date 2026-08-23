package com.nahida.touchmap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.nahida.touchmap.model.KeyShape
import com.nahida.touchmap.model.VirtualKey

/**
 * 映射目标标记（游戏层可视化）：
 * 编辑模式下，在目标坐标位置显示与虚拟按键同形状的虚线框，
 * 让用户一眼看到「虚拟按键 -> 游戏按键」的对应关系（两层结构）。
 */
class TargetMarkerView(
    context: Context,
    key: VirtualKey
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x26FF9800.toInt()
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
        color = Color.parseColor("#66FF9800")
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
                val r = half * 0.9f
                val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
                canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
            }
        }

        // 中心十字
        canvas.drawLine(cx - half * 0.4f, cy, cx + half * 0.4f, cy, crossPaint)
        canvas.drawLine(cx, cy - half * 0.4f, cx, cy + half * 0.4f, crossPaint)
    }
}
