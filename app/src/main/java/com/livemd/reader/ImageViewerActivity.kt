package com.livemd.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/** 全屏图片查看器：双击/双指缩放，拖动查看细节，单击退出 */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra("file") ?: run { finish(); return }

        val zoom = ZoomableImageView(this)
        zoom.setBackgroundColor(0xFF000000.toInt())
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val root = FrameLayout(this)
        root.addView(zoom, params)
        setContentView(root)

        Thread {
            // 解码到不超过 2048px 的全清晰度（放大查看细节够用）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file, opts)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp == null) { finish(); return@runOnUiThread }
                zoom.setImageBitmap(bmp)
                zoom.onSingleTap = { finish() }
            }
        }.start()
    }
}
