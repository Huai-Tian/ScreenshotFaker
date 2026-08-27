package fake.screenshot.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 悬浮窗四角/顶部中线把手绘制（本地窗口与 root 托管窗口共用）。
 * 纯绘制、不消费事件。
 */
class CornerHandleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
    }
    private val cornerSize = 30f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = cornerSize

        canvas.drawLine(0f, 0f, size, 0f, paint)
        canvas.drawLine(0f, 0f, 0f, size, paint)
        canvas.drawLine(w - size, 0f, w, 0f, paint)
        canvas.drawLine(w, 0f, w, size, paint)
        canvas.drawLine(0f, h - size, 0f, h, paint)
        canvas.drawLine(0f, h, size, h, paint)
        canvas.drawLine(w - size, h, w, h, paint)
        canvas.drawLine(w, h - size, w, h, paint)

        val centerX = w / 2
        canvas.drawLine(centerX - size, 0f, centerX + size, 0f, paint)
    }
}
