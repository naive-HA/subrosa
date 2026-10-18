package acab.naiveha.subrosa.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs

class SwipeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onPullDown: (() -> Unit)? = null
    var activeTabHeight: Int = 0

    private var startX = 0f
    private var startY = 0f
    private var isGestureHandled = false

    private fun cancelChildTouches(ev: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(ev)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                isGestureHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isGestureHandled) {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY

                    if (abs(dx) > 80f && abs(dx) > abs(dy) * 1.5f) {
                        isGestureHandled = true
                        cancelChildTouches(ev)
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else if (scrollY == 0 && dy > 550f && dy > abs(dx) * 1.5f) {
                        isGestureHandled = true
                        cancelChildTouches(ev)
                        onPullDown?.invoke()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                } else {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isGestureHandled) {
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun scrollTo(x: Int, y: Int) {
        val maxY = if (activeTabHeight > 0) maxOf(0, activeTabHeight - height) else 0
        val clampedY = y.coerceIn(0, maxY)
        super.scrollTo(x, clampedY)
    }
}
