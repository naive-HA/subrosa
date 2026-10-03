package acab.naiveha.subrosa.ui.openpgp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.withSave

class CropSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startPoint: PointF? = null
    private var selectionRect: RectF? = null

    var imageDisplayBounds: RectF? = null
    var bitmapWidth: Int = 0
    var bitmapHeight: Int = 0

    private val fillPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startPoint = PointF(event.x, event.y)
                selectionRect = RectF(event.x, event.y, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                startPoint?.let { start ->
                    selectionRect = RectF(
                        min(start.x, event.x),
                        min(start.y, event.y),
                        max(start.x, event.x),
                        max(start.y, event.y)
                    )
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = selectionRect ?: return
        if (rect.width() < 4f || rect.height() < 4f) return

        canvas.withSave {
            clipOutRect(rect)
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }

        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
    }

    fun clearSelection() {
        selectionRect = null
        startPoint = null
        invalidate()
    }

    fun selectionInImageCoordinates(): Rect? {
        val displayBounds = imageDisplayBounds ?: return null
        val rect = selectionRect ?: return null
        if (rect.width() < 4f || rect.height() < 4f) return null
        if (bitmapWidth == 0 || bitmapHeight == 0) return null

        val clipped = RectF(rect).apply {
            left = left.coerceIn(displayBounds.left, displayBounds.right)
            top = top.coerceIn(displayBounds.top, displayBounds.bottom)
            right = right.coerceIn(displayBounds.left, displayBounds.right)
            bottom = bottom.coerceIn(displayBounds.top, displayBounds.bottom)
        }
        if (clipped.width() < 4f || clipped.height() < 4f) return null

        val scaleX = bitmapWidth / displayBounds.width()
        val scaleY = bitmapHeight / displayBounds.height()

        val left = ((clipped.left - displayBounds.left) * scaleX).toInt().coerceIn(0, bitmapWidth)
        val top = ((clipped.top - displayBounds.top) * scaleY).toInt().coerceIn(0, bitmapHeight)
        val right = ((clipped.right - displayBounds.left) * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bottom = ((clipped.bottom - displayBounds.top) * scaleY).toInt().coerceIn(0, bitmapHeight)

        if (right <= left || bottom <= top) return null

        return Rect(left, top, right, bottom)
    }
}
