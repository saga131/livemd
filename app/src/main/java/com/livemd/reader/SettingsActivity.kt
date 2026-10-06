package com.livemd.reader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var editUri: EditText
    private lateinit var editDb: EditText
    private lateinit var editUser: EditText
    private lateinit var editPass: EditText
    private lateinit var editPassphrase: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editUri = findViewById(R.id.editUri)
        editDb = findViewById(R.id.editDb)
        editUser = findViewById(R.id.editUser)
        editPass = findViewById(R.id.editPass)
        editPassphrase = findViewById(R.id.editPassphrase)

        Settings.load(this)?.let {
            editUri.setText(it.uri)
            editDb.setText(it.dbName)
            editUser.setText(it.username)
            editPass.setText(it.password)
            editPassphrase.setText(it.passphrase)
        }
        refreshScopeSummary()

        findViewById<Button>(R.id.btnScope).setOnClickListener {
            // 先保存当前表单，目录选择页要连服务器
            val cfg = collect() ?: return@setOnClickListener
            Settings.save(this, cfg)
            startActivity(android.content.Intent(this, FolderPickActivity::class.java))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val cfg = collect() ?: return@setOnClickListener
            Settings.save(this, cfg)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val cfg = collect() ?: return@setOnClickListener
            Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = try {
                    CouchClient(cfg).testConnection()
                } catch (e: Exception) {
                    "连接失败: ${e.message}"
                }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }
    }

    private fun refreshScopeSummary() {
        val (all, folders) = Settings.loadSyncFilter(this)
        val text = when {
            all || folders == null -> "同步范围：全部"
            else -> {
                val names = folders.filter { it.isNotEmpty() }.ifEmpty { listOf("根目录文件") }
                "同步范围：${folders.size} 个顶层目录（${names.joinToString("、")}）"
            }
        }
        findViewById<TextView>(R.id.syncScopeSummary).text = text
    }

    override fun onResume() {
        super.onResume()
        refreshScopeSummary()
    }

    private fun collect(): CouchConfig? {
        val uri = editUri.text.toString().trim()
        val db = editDb.text.toString().trim()
        if (uri.isEmpty() || db.isEmpty()) {
            Toast.makeText(this, "服务器地址和数据库名不能为空", Toast.LENGTH_SHORT).show()
            return null
        }
        return CouchConfig(uri, db, editUser.text.toString(), editPass.text.toString(), editPassphrase.text.toString())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
