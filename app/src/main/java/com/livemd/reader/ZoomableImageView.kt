package com.livemd.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 可缩放图片 View：双击 1x/2.5x 切换、双指捏合缩放、放大后拖动平移（带边界钳制）。
 * 单击（onSingleTapConfirmed）由外部监听，用于退出查看器。
 */
@SuppressLint("AppCompatCustomView")
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrix = Matrix()
    private var fitScale = 1f
    private var maxScale = 5f
    private var bmpW = 0
    private var bmpH = 0

    var onSingleTap: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val cur = currentScale()
            val target = (cur * factor).coerceIn(fitScale * 0.8f, maxScale)
            matrix.postScale(target / cur, target / cur, detector.focusX, detector.focusY)
            clamp()
            imageMatrix = matrix
            return true
        }
    })

    private val gest = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = currentScale()
            if (cur > fitScale * 1.2f) {
                // 回到适配屏宽
                matrix.set(baseFitMatrix())
            } else {
                val target = fitScale * 2.5f
                matrix.postScale(target / cur, target / cur, e.x, e.y)
            }
            clamp()
            imageMatrix = matrix
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (currentScale() > fitScale * 1.01f) {
                matrix.postTranslate(-dx, -dy)
                clamp()
                imageMatrix = matrix
            }
            return true
        }
    })

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    /** 适配屏宽的基准矩阵（居中） */
    private fun baseFitMatrix(): Matrix {
        val m = Matrix()
        if (bmpW == 0 || width == 0) return m
        val s = min(width.toFloat() / bmpW, height.toFloat() / bmpH).coerceAtMost(
            max(width.toFloat() / bmpW, 1f)
        )
        m.setScale(s, s)
        m.postTranslate((width - bmpW * s) / 2f, (height - bmpH * s) / 2f)
        return m
    }

    private fun clamp() {
        if (bmpW == 0 || width == 0) return
        val v = FloatArray(9)
        matrix.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val contentW = bmpW * scale
        val contentH = bmpH * scale
        var nx = tx
        var ny = ty
        if (contentW <= width) nx = (width - contentW) / 2f
        else { nx = max(min(nx, 0f), width - contentW) }
        if (contentH <= height) ny = (height - contentH) / 2f
        else { ny = max(min(ny, 0f), height - contentH) }
        if (nx != tx || ny != ty) {
            val diff = Matrix()
            diff.setTranslate(nx - tx, ny - ty)
            matrix.postConcat(diff)
        }
    }

    override fun setImageBitmap(bmp: Bitmap?) {
        super.setImageBitmap(bmp)
        bmp?.let {
            bmpW = it.width
            bmpH = it.height
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bmpW > 0 && w > 0) {
            matrix.set(baseFitMatrix())
            fitScale = currentScale()
            maxScale = fitScale * 6f
            imageMatrix = matrix
        }
        scaleType = ScaleType.MATRIX
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gest.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
