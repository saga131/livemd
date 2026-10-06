package com.livemd.reader

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 选择同步范围：按顶层文件夹勾选（含其子文件夹），或全选。
 * 目录清单优先读本地缓存（每次同步自动更新），秒开；「刷新目录」才访问服务器。
 */
class FolderPickActivity : AppCompatActivity() {

    private data class FolderInfo(val key: String, val label: String, val count: Int)

    private lateinit var list: ListView
    private lateinit var progress: ProgressBar
    private lateinit var tip: TextView
    private lateinit var syncAllBox: CheckBox
    private val folders = ArrayList<FolderInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_pick)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "选择同步范围"

        list = findViewById(R.id.folderList)
        progress = findViewById(R.id.progress)
        tip = findViewById(R.id.tip)
        syncAllBox = findViewById(R.id.syncAllBox)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)

        val cfg = Settings.load(this) ?: run {
            Toast.makeText(this, "请先填写并保存服务器配置", Toast.LENGTH_LONG).show()
            finish(); return
        }

        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        syncAllBox.setOnCheckedChangeListener { _, checked ->
            list.isEnabled = !checked
            list.alpha = if (checked) 0.4f else 1f
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        // 已保存的选择回显
        val (all, saved) = Settings.loadSyncFilter(this)
        syncAllBox.isChecked = all
        list.isEnabled = !all
        list.alpha = if (all) 0.4f else 1f
        val savedSet: Set<String> = saved ?: emptySet()

        // 先用本地缓存秒开（清单在每次同步时自动更新）
        val inv = readInventory()
        if (inv != null) {
            showInventory(inv, savedSet)
        } else {
            tip.text = "本地还没有目录清单，点「刷新目录」从服务器获取"
        }
        btnRefresh.setOnClickListener { refreshFromServer(cfg) }
    }

    private fun readInventory(): JSONObject? = try {
        val s = getSharedPreferences("settings", MODE_PRIVATE).getString("folderInventory", null)
        if (s.isNullOrEmpty()) null else JSONObject(s)
    } catch (e: Exception) { null }

    private fun showInventory(inv: JSONObject, savedSet: Set<String>) {
        val foldersJson = inv.optJSONObject("folders") ?: JSONObject()
        folders.clear()
        val root = inv.optInt("root", 0)
        if (root > 0) folders.add(FolderInfo("", "（根目录散落的文件）", root))
        val names = foldersJson.keys().asSequence().toList().sorted()
        for (name in names) folders.add(FolderInfo(name, name, foldersJson.optInt(name)))
        tip.text = "共 ${inv.optInt("textTotal")} 篇笔记，勾选要同步到本机的部分（更新于 " +
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(inv.optLong("updated"))) + "）"
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_checked,
            folders.map { "${it.label} · ${it.count} 篇" })
        for (i in folders.indices) {
            if (folders[i].key in savedSet) list.setItemChecked(i, true)
        }
    }

    private fun refreshFromServer(cfg: CouchConfig) {
        progress.visibility = View.VISIBLE
        tip.text = "正在从服务器读取目录…"
        val appContext = applicationContext
        Thread {
            try {
                val client = CouchClient(cfg)
                client.ensureTypeIndex()
                val paths = client.findNotePaths()
                val foldersJson = JSONObject()
                var root = 0
                val topCount = LinkedHashMap<String, Int>()
                for (p in paths) {
                    if (p.contains('/')) {
                        val top = p.substringBefore('/')
                        topCount[top] = (topCount[top] ?: 0) + 1
                    } else root++
                }
                for ((k, v) in topCount) foldersJson.put(k, v)
                val inv = JSONObject()
                    .put("folders", foldersJson)
                    .put("root", root)
                    .put("textTotal", paths.size)
                    .put("updated", System.currentTimeMillis())
                appContext.getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("folderInventory", inv.toString()).apply()
                runOnUiThread {
                    progress.visibility = View.GONE
                    showInventory(inv, Settings.loadSyncFilter(this).second ?: emptySet())
                    Toast.makeText(this, "目录已更新", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    tip.text = "读取失败: ${e.message}"
                    Toast.makeText(appContext, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun save() {
        if (syncAllBox.isChecked) {
            Settings.saveSyncFilter(this, true, null)
        } else {
            val chosen = HashSet<String>()
            for (i in folders.indices) {
                if (list.isItemChecked(i)) chosen.add(folders[i].key)
            }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "至少勾选一个，或选「全部同步」", Toast.LENGTH_SHORT).show()
                return
            }
            Settings.saveSyncFilter(this, false, chosen)
        }
        Toast.makeText(this, "同步范围已保存，回主界面点「同步」生效", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
