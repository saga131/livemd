package com.livemd.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var search: SearchView
    private lateinit var adapter: NoteAdapter

    // 平板双栏：readerView 非空表示当前是双栏布局，点击笔记在右侧面板打开
    private var readerView: TextView? = null
    private var readerContainer: View? = null
    private var readerTitle: TextView? = null
    private var readerEmpty: View? = null
    private var selectedPath: String? = null

    private val allNotes = ArrayList<NoteItem>()
    private val shownNotes = ArrayList<NoteItem>()
    private var syncing = false
    private var watcher: Thread? = null
    @Volatile private var watcherStop = false
    private var restored = false // 续读只在冷启动恢复一次
    private var paneOpenTime = 0L // 双栏当前笔记的打开时刻（续读停留门槛）

    data class NoteItem(val path: String) {
        val name = path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".markdown").removeSuffix(".txt")
        val dir = path.substringBeforeLast('/', "").ifEmpty { "根目录" }
    }

    private inner class NoteAdapter : BaseAdapter() {
        override fun getCount() = shownNotes.size
        override fun getItem(position: Int) = shownNotes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.list_item_note, parent, false)
            val item = shownNotes[position]
            v.findViewById<TextView>(R.id.noteTitle).text = item.name
            v.findViewById<TextView>(R.id.noteDir).text = item.dir
            v.setBackgroundColor(
                if (item.path == selectedPath) 0x1F7C3AED.toInt() else Color.TRANSPARENT
            )
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        readerView = findViewById(R.id.readerView)
        readerContainer = findViewById(R.id.readerContainer)
        readerTitle = findViewById(R.id.readerTitle)
        readerEmpty = findViewById(R.id.readerEmpty)

        adapter = NoteAdapter()
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = adapter.getItem(pos) ?: return@setOnItemClickListener
            openNote(item.path)
        }

        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                applyFilter(newText ?: "")
                return true
            }
        })

        reloadNotes()
        if (!Settings.has(this)) {
            Toast.makeText(this, "请先在菜单「设置」中配置服务器", Toast.LENGTH_LONG).show()
        } else {
            doSync(manual = true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // 单栏（手机）没有内嵌阅读面板，大纲/文内查找只对平板双栏有意义
        val hasPane = readerView != null
        menu.findItem(R.id.action_outline).isVisible = hasPane
        menu.findItem(R.id.action_find).isVisible = hasPane
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sync -> { doSync(manual = true); true }
        R.id.action_search -> {
            search.visibility = if (search.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (search.visibility == View.GONE) {
                search.setQuery("", true)
            } else {
                search.requestFocus()
            }
            true
        }
        R.id.action_outline, R.id.action_find -> {
            val rv = readerView
            val sc = readerContainer
            if (rv == null || sc == null || rv.text.isEmpty()) {
                Toast.makeText(this, "先选择一篇笔记", Toast.LENGTH_SHORT).show()
            } else if (item.itemId == R.id.action_outline) {
                ReaderTools.showOutline(this, rv, sc)
            } else {
                ReaderTools.showFind(this, rv, sc)
            }
            true
        }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        reloadNotes()
        startWatcher()
    }

    override fun onPause() {
        savePanePosition()
        stopWatcher()
        super.onPause()
    }

    private fun notesDir() = File(filesDir, "notes")

    /** 点击笔记：平板在右侧面板打开，手机跳转阅读页 */
    private fun openNote(path: String) {
        val rv = readerView ?: run {
            startActivity(Intent(this, ReaderActivity::class.java).putExtra("path", path))
            return
        }
        selectedPath = path
        paneOpenTime = System.currentTimeMillis()
        adapter.notifyDataSetChanged()
        readerTitle?.text = path.substringAfterLast('/')
            .removeSuffix(".md").removeSuffix(".markdown").removeSuffix(".txt")
        readerTitle?.visibility = View.VISIBLE
        readerEmpty?.visibility = View.GONE
        rv.text = "加载中…"
        readerContainer?.visibility = View.VISIBLE
        val appContext = applicationContext
        Thread {
            val f = File(notesDir(), path)
            val text = if (f.exists()) {
                try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "读取失败: ${e.message}" }
            } else {
                "文件不存在，请先同步。"
            }
            val rendered = MarkdownRenderer.render(text, this, imageExists = ::hasAttachment)
            val dm = resources.displayMetrics
            val pad = rv.paddingLeft + rv.paddingRight + (8 * dm.density).toInt()
            val maxW = (rv.width.takeIf { it > 0 } ?: dm.widthPixels) - pad
            MathImages.renderAll(rendered, this, maxW)
            NoteImages.decodeAll(rendered, this, maxW)
            runOnUiThread {
                if (selectedPath == path) {
                    rv.text = rendered
                    restorePanePosition(path, rv, readerContainer, useAnchor = true)
                    // 轻点图片 → 全屏查看器（平板双栏）
                    var downX = 0f; var downY = 0f; var downAt = 0L
                    rv.setOnTouchListener { _, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                downX = event.x; downY = event.y
                                downAt = System.currentTimeMillis()
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                val moved = maxOf(kotlin.math.abs(event.x - downX), kotlin.math.abs(event.y - downY))
                                if (moved < 20 && System.currentTimeMillis() - downAt < 300) {
                                    val off = rv.getOffsetForPosition(event.x, event.y)
                                    val sp = rv.text as? Spannable
                                    val hit = sp?.getSpans(off, off, ViewerImageSpan::class.java)
                                        ?.firstOrNull { sp.getSpanStart(it) <= off && off <= sp.getSpanEnd(it) }
                                    hit?.let {
                                        startActivity(
                                            android.content.Intent(this@MainActivity, ImageViewerActivity::class.java)
                                                .putExtra("file", it.file.absolutePath)
                                        )
                                        return@setOnTouchListener true
                                    }
                                }
                            }
                        }
                        false
                    }
                }
            }
        }.start()
    }

    /** 续读：平板双栏恢复上次读到的位置（两段式：比例粗定位 → 图片加载完字符锚点精定位） */
    private fun restorePanePosition(path: String, rv: TextView, sc: View?, useAnchor: Boolean) {
        sc ?: return
        val prefs = getSharedPreferences("reading", MODE_PRIVATE)
        val off = prefs.getInt("off:$path", -1)
        val ratio = prefs.getFloat("pos:$path", -1f)
        if (off < 0 && ratio < 0) return
        var tries = 0
        fun jump() {
            val layout = rv.layout
            if (layout == null) {
                if (tries++ < 20) rv.postDelayed({ jump() }, 100)
                return
            }
            if (useAnchor && off in 1 until rv.text.length) {
                val line = layout.getLineForOffset(off)
                sc.scrollTo(0, (layout.getLineTop(line) - 20).coerceAtLeast(0))
            } else if (ratio >= 0) {
                val max = (rv.height - sc.height).coerceAtLeast(0)
                sc.scrollTo(0, (ratio * max).toInt())
            }
        }
        jump()
    }

    private fun savePanePosition() {
        val rv = readerView ?: return
        val sc = readerContainer ?: return
        val p = selectedPath ?: return
        val layout = rv.layout ?: return
        val max = rv.height - sc.height
        if (max <= 0) return
        val line = layout.getLineForVertical(sc.scrollY.coerceAtLeast(0))
        val ratio = (sc.scrollY.toFloat() / max).coerceIn(0f, 1f)
        val e = getSharedPreferences("reading", MODE_PRIVATE).edit()
            .putInt("off:$p", layout.getLineStart(line))
            .putFloat("pos:$p", ratio)
        // 停留满 15 秒才算"真的在读"（paneOpenTime 在 openNote 时刷新）
        if (System.currentTimeMillis() - paneOpenTime >= 15_000) {
            e.putString("lastPath", p).putLong("lastRead", System.currentTimeMillis())
        }
        e.apply()
    }

    /** 续读：冷启动时自动打开上次阅读的笔记（只恢复一次，之后由用户自由导航） */
    private fun attemptRestore() {
        if (restored) return
        val prefs = getSharedPreferences("reading", MODE_PRIVATE)
        val last = prefs.getString("lastPath", null) ?: return
        if (lastReadStale(prefs)) return
        if (allNotes.none { it.path == last }) return
        restored = true
        Toast.makeText(this, "已恢复上次阅读位置", Toast.LENGTH_SHORT).show()
        openNote(last)
    }

    /** 超过 3 天没读就不再自动跳转，避免莫名的跳转感 */
    private fun lastReadStale(prefs: android.content.SharedPreferences): Boolean {
        val t = prefs.getLong("lastRead", 0)
        return t > 0 && System.currentTimeMillis() - t > 3L * 24 * 3600 * 1000
    }

    private fun hasAttachment(name: String): Boolean =
        File(File(filesDir, "attachments"), name.substringAfterLast('/')).exists()

    private fun reloadNotes() {
        val dir = notesDir()
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("md", "markdown", "txt") }
            .map { it.relativeTo(dir).path.replace('\\', '/') }
            .toMutableList()
        files.sort()
        allNotes.clear()
        allNotes.addAll(files.map { NoteItem(it) })
        applyFilter(search.query?.toString() ?: "")
        attemptRestore()
    }

    /** 搜索：标题/路径匹配优先，再全文匹配缓存内容 */
    private fun applyFilter(query: String) {
        shownNotes.clear()
        val q = query.trim()
        if (q.isEmpty()) {
            shownNotes.addAll(allNotes)
        } else {
            val dir = notesDir()
            val titleHits = ArrayList<NoteItem>()
            val contentHits = ArrayList<NoteItem>()
            for (n in allNotes) {
                if (n.path.contains(q, ignoreCase = true)) {
                    titleHits.add(n)
                    continue
                }
                try {
                    val f = File(dir, n.path)
                    if (f.exists() && f.readText(Charsets.UTF_8).contains(q, ignoreCase = true)) {
                        contentHits.add(n)
                    }
                } catch (e: Exception) {
                    // 单个文件读失败不影响整体搜索
                }
            }
            shownNotes.addAll(titleHits)
            shownNotes.addAll(contentHits)
        }
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    @SuppressLint("SimpleDateFormat")
    private fun updateStatus() {
        val last = getSharedPreferences("settings", MODE_PRIVATE).getLong("lastSync", 0)
        val lastStr = if (last > 0) " · 上次同步 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(last)) else ""
        val tag = if (watcher != null && watcher!!.isAlive) " · 实时已开启" else ""
        status.text = "${shownNotes.size} 篇笔记$lastStr$tag"
    }

    private fun doSync(manual: Boolean) {
        if (syncing) return
        val cfg = Settings.load(this) ?: run {
            if (manual) startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        syncing = true
        if (manual) progress.visibility = View.VISIBLE
        val appContext = applicationContext
        Thread {
            val result = try {
                VaultSync.pull(appContext, cfg)
            } catch (e: Exception) {
                SyncResult(false, 0, "同步失败: ${e.message}")
            }
            if (result.ok) {
                appContext.getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putLong("lastSync", System.currentTimeMillis()).apply()
            }
            runOnUiThread {
                syncing = false
                progress.visibility = View.GONE
                reloadNotes()
                if (manual) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** 实时更新：前台期间长轮询服务器变更，有变更自动拉取刷新 */
    private fun startWatcher() {
        val cfg = Settings.load(this) ?: return
        if (watcher?.isAlive == true) return
        watcherStop = false
        val appContext = applicationContext
        watcher = Thread {
            while (!watcherStop) {
                try {
                    val client = CouchClient(cfg)
                    val changed = client.waitForChanges(25000)
                    if (watcherStop) break
                    if (changed) {
                        // 有其他设备的写入，静默拉取刷新
                        try {
                            VaultSync.pull(appContext, cfg)
                            appContext.getSharedPreferences("settings", MODE_PRIVATE).edit()
                                .putLong("lastSync", System.currentTimeMillis()).apply()
                            runOnUiThread { reloadNotes() }
                        } catch (e: Exception) {
                            // 拉取失败下一轮重试
                        }
                    }
                } catch (e: Exception) {
                    try { Thread.sleep(5000) } catch (x: Exception) { break }
                }
            }
        }.also { it.start() }
        updateStatus()
    }

    private fun stopWatcher() {
        watcherStop = true
        watcher?.interrupt()
        watcher = null
        updateStatus()
    }
}
