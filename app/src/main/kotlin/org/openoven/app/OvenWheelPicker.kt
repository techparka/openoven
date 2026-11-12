package org.openoven.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drag slowly for single values, swipe quickly to accelerate and coast to a whole number. */
class OvenWheelPicker(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val rowHeight = 44f * density * resources.configuration.fontScale.coerceAtMost(1.5f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val normalTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
    private val selectedTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
    private val band = RectF()
    private val scroller = OverScroller(context).apply { setFriction(.035f) }
    private val configuration = ViewConfiguration.get(context)
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var lastAt = 0L
    private var distance = 0f
    private var gain = 1f
    private var dragging = false
    private var position = 0f
    private var listener: ((OvenWheelPicker, Int, Int) -> Unit)? = null

    var minValue: Int = 0
        set(value) {
            field = value
            if (maxValue >= value) this.value = this.value.coerceIn(value, maxValue)
        }
    var maxValue: Int = 100
        set(value) {
            require(value >= minValue)
            field = value
            this.value = this.value.coerceIn(minValue, value)
        }
    var unit: String = ""
    var value: Int
        get() = position.roundToInt().coerceIn(minValue, maxValue)
        set(value) {
            finish()
            update(value.coerceIn(minValue, maxValue).toFloat())
        }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumWidth = (112 * density).roundToInt()
        minimumHeight = (220 * density).roundToInt()
    }

    fun setOnValueChangedListener(listener: (OvenWheelPicker, Int, Int) -> Unit) {
        this.listener = listener
    }

    fun finish() {
        scroller.forceFinished(true)
        position = position.roundToInt().toFloat()
        invalidate()
    }

    private fun update(next: Float) {
        val old = value
        position = next.coerceIn(minValue.toFloat(), maxValue.toFloat())
        val selected = value
        invalidate()
        if (selected != old) {
            listener?.invoke(this, old, selected)
            if (isAccessibilityFocused) sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(minimumWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(maxOf(minimumHeight, (rowHeight * 5).roundToInt()), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.oven_neutral)
        band.set(4 * density, center - rowHeight / 2, width - 4 * density, center + rowHeight / 2)
        canvas.drawRoundRect(band, 12 * density, 12 * density, paint)
        val first = position.toInt() - 3
        for (number in first..first + 7) {
            if (number !in minValue..maxValue) continue
            val y = center + (number - position) * rowHeight
            val fade = (1f - abs(y - center) / (height / 2f)).coerceIn(0f, 1f)
            val chosen = number == value
            paint.color = ContextCompat.getColor(context, if (chosen) R.color.oven_accent else R.color.oven_muted)
            paint.alpha = (255 * fade * if (isEnabled) 1f else .5f).roundToInt()
            paint.textSize = if (chosen) selectedTextSize else normalTextSize
            val baseline = y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
            canvas.drawText(number.toString(), width / 2f, baseline, paint)
        }
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastY = event.y
                lastAt = event.eventTime
                distance = 0f
                gain = 1f
                dragging = true
                requestFocus()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                tracker?.addMovement(event)
                val delta = lastY - event.y
                val elapsed = (event.eventTime - lastAt).coerceAtLeast(8)
                distance += abs(delta)
                val speed = abs(delta) / density / elapsed
                gain = 1f + ((speed - .45f).coerceAtLeast(0f) * 1.2f).coerceAtMost(3f) *
                    (1f + (distance / density / 250f).coerceAtMost(.75f))
                update(position + delta / rowHeight * gain)
                lastY = event.y
                lastAt = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val velocity = (-(tracker?.yVelocity ?: 0f) * gain).coerceIn(-rowHeight * 40, rowHeight * 40)
                val recent = event.eventTime - lastAt < 90
                dragging = false
                tracker?.recycle()
                tracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (recent && abs(velocity) > configuration.scaledMinimumFlingVelocity && (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
                    scroller.fling(0, (position * rowHeight).roundToInt(), 0, velocity.roundToInt(),
                        0, 0, (minValue * rowHeight).roundToInt(), (maxValue * rowHeight).roundToInt())
                    postInvalidateOnAnimation()
                } else finish()
                if (distance < configuration.scaledTouchSlop) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracker?.recycle()
                tracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                finish()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            update(scroller.currY / rowHeight)
            if (scroller.isFinished) finish() else postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.action == MotionEvent.ACTION_SCROLL) {
            value += -event.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> value += 1
            KeyEvent.KEYCODE_DPAD_DOWN -> value -= 1
            KeyEvent.KEYCODE_MOVE_HOME -> value = minValue
            KeyEvent.KEYCODE_MOVE_END -> value = maxValue
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.NumberPicker"
        info.text = "$value $unit"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minValue.toFloat(), maxValue.toFloat(), value.toFloat()
        )
        info.isScrollable = isEnabled && minValue != maxValue
        if (isEnabled) {
            if (value < maxValue) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (value > minValue) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (android.os.Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { value += 1; return true }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { value -= 1; return true }
            }
            if (android.os.Build.VERSION.SDK_INT >= 24 &&
                action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
                val requested = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                if (requested.isFinite()) { value = requested.roundToInt(); return true }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        finish()
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }
}
