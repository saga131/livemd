package com.livemd.reader

import android.os.Bundle
import android.view.MotionEvent
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var path: String
    private var openTime = 0L

    // 文内查找常驻底栏状态
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var matches = listOf<Int>()
    private var matchIdx = 0
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        path = intent.getStringExtra("path") ?: run { finish(); return }
        openTime = System.currentTimeMillis()
        title = path.substringAfterLast('/')
            .removeSuffix(".md").removeSuffix(".markdown").removeSuffix(".txt")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tv = findViewById(R.id.reader)
        scroll = findViewById(R.id.readerScroll)
        setupFindBar()

        Thread {
            val f = File(File(filesDir, "notes"), path)
            val text = if (f.exists()) {
                try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "读取失败: ${e.message}" }
            } else {
                "文件不存在，请回到主界面点「同步」。"
            }
            val rendered = MarkdownRenderer.render(text, this, imageExists = ::hasAttachment)
            // 后台一次性完成公式渲染与图片解码，终态布局才上屏（避免异步替换导致的叠加/OBJ）
            val dm = resources.displayMetrics
            val pad = tv.paddingLeft + tv.paddingRight + (8 * dm.density).toInt()
            val maxW = (tv.width.takeIf { it > 0 } ?: dm.widthPixels) - pad
            MathImages.renderAll(rendered, this, maxW)
            NoteImages.decodeAll(rendered, this, maxW)
            runOnUiThread {
                tv.text = rendered
                restorePosition(useAnchor = true)
            }
        }.start()

        // 轻点图片 → 打开全屏查看器（不干扰滚动/选择）
        var downX = 0f; var downY = 0f; var downAt = 0L
        tv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downAt = System.currentTimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val moved = maxOf(kotlin.math.abs(event.x - downX), kotlin.math.abs(event.y - downY))
                    if (moved < 20 && System.currentTimeMillis() - downAt < 300) {
                        val off = tv.getOffsetForPosition(event.x, event.y)
                        val sp = tv.text as? Spannable
                        val hit = sp?.getSpans(off, off, ViewerImageSpan::class.java)
                            ?.firstOrNull { sp.getSpanStart(it) <= off && off <= sp.getSpanEnd(it) }
                        hit?.let {
                            startActivity(
                                android.content.Intent(this, ImageViewerActivity::class.java)
                                    .putExtra("file", it.file.absolutePath)
                            )
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        // 底栏打开时，返回键先收起查找栏
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findBar.visibility == View.VISIBLE) hideFindBar() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun hasAttachment(name: String): Boolean =
        File(File(filesDir, "attachments"), name.substringAfterLast('/')).exists()

    /** 续读恢复。两段式：先按比例快速定位（图片未加载时的近似值），图片全部加载完后再按字符锚点精确跳转 */
    private fun restorePosition(useAnchor: Boolean) {
        val prefs = getSharedPreferences("reading", MODE_PRIVATE)
        val off = prefs.getInt("off:$path", -1)
        val ratio = prefs.getFloat("pos:$path", -1f)
        if (off < 0 && ratio < 0) return
        var tries = 0
        fun jump() {
            val layout = tv.layout
            if (layout == null) {
                if (tries++ < 20) tv.postDelayed({ jump() }, 100)
                return
            }
            if (useAnchor && off in 1 until tv.text.length) {
                val line = layout.getLineForOffset(off)
                scroll.smoothScrollTo(0, (layout.getLineTop(line) - 20).coerceAtLeast(0))
            } else if (ratio >= 0) {
                val max = (tv.height - scroll.height).coerceAtLeast(0)
                scroll.smoothScrollTo(0, (ratio * max).toInt())
            }
        }
        jump()
    }

    private fun savePosition() {
        if (!::tv.isInitialized || tv.layout == null || tv.height == 0 || tv.height <= scroll.height) return
        val layout = tv.layout!!
        val line = layout.getLineForVertical(scroll.scrollY.coerceAtLeast(0))
        val max = tv.height - scroll.height
        val ratio = (scroll.scrollY.toFloat() / max).coerceIn(0f, 1f)
        val e = getSharedPreferences("reading", MODE_PRIVATE).edit()
            .putInt("off:$path", layout.getLineStart(line))
            .putFloat("pos:$path", ratio)
        // 停留满 15 秒才算"真的在读"，快速翻看的笔记不顶替续读目标
        if (System.currentTimeMillis() - openTime >= 15_000) {
            e.putString("lastPath", path).putLong("lastRead", System.currentTimeMillis())
        }
        e.apply()
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    // ==================== 文内查找（常驻底栏） ====================

    private fun setupFindBar() {
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        val prev = findViewById<TextView>(R.id.findPrev)
        val next = findViewById<TextView>(R.id.findNext)
        val close = findViewById<TextView>(R.id.findClose)

        findInput.doAfterTextChanged {
            runFind(it?.toString() ?: "", jumpToFirst = true)
        }
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runFind(findInput.text.toString(), jumpToFirst = false) // 同词继续=下一个
                true
            } else false
        }
        prev.setOnClickListener { runFind(findInput.text.toString(), jumpToFirst = false, backward = true) }
        next.setOnClickListener { runFind(findInput.text.toString(), jumpToFirst = false) }
        close.setOnClickListener { hideFindBar() }
    }

    fun toggleFindBar() {
        if (findBar.visibility == View.VISIBLE) hideFindBar() else showFindBar()
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(findInput, 0)
    }

    private fun hideFindBar() {
        clearHighlights()
        matches = emptyList(); lastQuery = ""; matchIdx = 0
        findCount.visibility = View.GONE
        findInput.setText("")
        findBar.visibility = View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    private fun runFind(query: String, jumpToFirst: Boolean, backward: Boolean = false) {
        val text = tv.text as? Spannable ?: return
        val q = query.trim()
        if (q.isEmpty()) { clearHighlights(); matches = emptyList(); findCount.visibility = View.GONE; return }
        if (q != lastQuery) {
            clearHighlights()
            lastQuery = q
            matches = findMatches(text, q)
            matchIdx = 0
            for (pos in matches) {
                text.setSpan(FindHighlightSpan(), pos, pos + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            if (matches.isEmpty()) return
            matchIdx = if (backward) (matchIdx - 1 + matches.size) % matches.size
                       else (matchIdx + 1) % matches.size
        }
        if (matches.isEmpty()) {
            findCount.text = "无结果"
            findCount.visibility = View.VISIBLE
            return
        }
        findCount.text = "${matchIdx + 1}/${matches.size}"
        findCount.visibility = View.VISIBLE
        jumpToOffset(matches[matchIdx])
    }

    private fun findMatches(text: Spannable, query: String): List<Int> {
        val lower = text.toString().lowercase()
        val q = query.lowercase()
        val out = ArrayList<Int>()
        var i = lower.indexOf(q)
        while (i >= 0) { out.add(i); i = lower.indexOf(q, i + q.length) }
        return out
    }

    private fun clearHighlights() {
        val text = tv.text as? Spannable ?: return
        for (s in text.getSpans(0, text.length, FindHighlightSpan::class.java)) text.removeSpan(s)
    }

    private fun jumpToOffset(off: Int) {
        tv.post {
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(off.coerceIn(0, (tv.text.length - 1).coerceAtLeast(0)))
            scroll.smoothScrollTo(0, (layout.getLineTop(line) - 100).coerceAtLeast(0))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_outline -> { ReaderTools.showOutline(this, tv, scroll); true }
        R.id.action_find -> { toggleFindBar(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
