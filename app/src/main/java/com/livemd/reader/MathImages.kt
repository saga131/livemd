package com.livemd.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

/** 公式占位 Span：渲染成功后替换为图片，失败替换为错误提示文本 */
class MathSpan(val latex: String, val block: Boolean)

/**
 * 公式与图片的同步渲染器：在后台线程把 Spannable 里的占位 Span 一次性替换成终态位图，
 * 然后才允许设置到 TextView —— 从根上避免异步替换导致的布局错乱/文字叠加/OBJ 残留。
 */
object MathImages {

    @Volatile private var cjkRegistered = false

    private fun registerCjk() {
        if (cjkRegistered) return
        val blocks = listOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.GENERAL_PUNCTUATION
        )
        for (b in blocks) {
            try { TeXFormula.registerExternalFont(b, "sans-serif") } catch (e: Exception) { }
        }
        cjkRegistered = true
    }

    /** 渲染全部公式占位（在后台线程调用）。失败的替换为红字提示 */
    fun renderAll(text: Spannable, activity: AppCompatActivity, maxW: Int) {
        registerCjk()
        val fg = ContextCompat.getColor(activity, R.color.mathFg)
        val density = activity.resources.displayMetrics.scaledDensity
        for (s in text.getSpans(0, text.length, MathSpan::class.java)) {
            val start = text.getSpanStart(s)
            val end = text.getSpanEnd(s)
            if (start < 0 || end <= start) continue
            val bmp = try { render(s.latex, s.block, fg, density, maxW) } catch (e: Exception) { null }
            text.removeSpan(s)
            if (bmp != null) {
                val d = BitmapDrawable(activity.resources, bmp)
                d.bounds = Rect(0, 0, bmp.width, bmp.height)
                text.setSpan(ImageSpan(d), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val msg = "[公式解析失败]"
                if (text is android.text.SpannableStringBuilder) {
                    text.replace(start, end, msg)
                    text.setSpan(ForegroundColorSpan(0xFFFF5252.toInt()), start, start + msg.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun render(latex: String, block: Boolean, fg: Int, density: Float, maxW: Int): Bitmap {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(if (block) 17f * density else 14f * density)
            .color(fg)
            .padding(if (block) (6f * density).toInt() else (1f * density).toInt())
            .align(JLatexMathDrawable.ALIGN_CENTER)
            .build()
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        // 过宽的公式等比缩到屏宽内（此前会被裁切）
        return if (bmp.width > maxW && maxW > 0) {
            val nh = (bmp.height * maxW / bmp.width).coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, maxW, nh, true)
            if (scaled != bmp) bmp.recycle()
            scaled
        } else bmp
    }
}

/** 图片 Span 的同步解码替换（与公式同理，终态布局一次成型） */
object NoteImages {

    fun decodeAll(text: Spannable, activity: AppCompatActivity, maxW: Int) {
        val dir = File(activity.filesDir, "attachments")
        for (s in text.getSpans(0, text.length, ImageRefSpan::class.java)) {
            val start = text.getSpanStart(s)
            val end = text.getSpanEnd(s)
            if (start < 0 || end <= start) continue
            val f = File(dir, s.ref.substringAfterLast('/'))
            if (!f.exists()) continue
            val bmp = decodeScaled(f, maxW) ?: continue
            val d = BitmapDrawable(activity.resources, bmp)
            d.bounds = Rect(0, 0, bmp.width, bmp.height)
            text.removeSpan(s)
            text.setSpan(ViewerImageSpan(d, f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun decodeScaled(f: File, targetW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        if (bmp.width > targetW) {
            val h = bmp.height * targetW / bmp.width
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, h, true)
            if (scaled != bmp) bmp.recycle()
            return scaled
        }
        return bmp
    }
}

/** 带文件引用的图片 Span：轻点后打开全屏查看器（与公式位图区分开） */
class ViewerImageSpan(drawable: android.graphics.drawable.Drawable, val file: File) :
    ImageSpan(drawable)
