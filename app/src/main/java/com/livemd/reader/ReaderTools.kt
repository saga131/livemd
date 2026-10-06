package com.livemd.reader

import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 阅读页工具：大纲跳转、文内查找（手机与平板双栏共用） */
object ReaderTools {

    /** 大纲：列出全文标题，点击滚动到对应位置 */
    fun showOutline(activity: AppCompatActivity, tv: TextView, scroll: View) {
        val text = tv.text as? Spannable ?: return run {
            Toast.makeText(activity, "先选择一篇笔记", Toast.LENGTH_SHORT).show()
        }
        val heads = text.getSpans(0, text.length, HeadingSpan::class.java)
            .sortedBy { text.getSpanStart(it) }
        if (heads.isEmpty()) {
            Toast.makeText(activity, "本文没有标题", Toast.LENGTH_SHORT).show()
            return
        }
        val items = heads.map { "　".repeat(it.level - 1) + it.title }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("大纲 · ${heads.size} 个标题")
            .setItems(items) { _, which ->
                scrollToOffset(tv, scroll, text.getSpanStart(heads[which]))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 文内查找：高亮全部命中，确认键在命中项之间循环跳转 */
    fun showFind(activity: AppCompatActivity, tv: TextView, scroll: View) {
        val editable = tv.text as? Spannable ?: return
        val input = EditText(activity)
        input.hint = "查找内容"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        input.maxLines = 1
        var lastQuery = ""
        var index = 0
        var matches = listOf<Int>()

        AlertDialog.Builder(activity)
            .setTitle("文内查找")
            .setView(input)
            .setPositiveButton("下一个") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                if (q != lastQuery) {
                    clearHighlights(editable)
                    matches = findMatches(editable, q)
                    lastQuery = q
                    index = 0
                    for (pos in matches) {
                        editable.setSpan(FindHighlightSpan(), pos, pos + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (matches.isEmpty()) {
                        Toast.makeText(activity, "未找到「$q」", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                } else {
                    if (matches.isEmpty()) return@setPositiveButton
                    index = (index + 1) % matches.size
                }
                scrollToOffset(tv, scroll, matches[index])
                Toast.makeText(activity, "${index + 1}/${matches.size}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun findMatches(text: Spannable, query: String): List<Int> {
        val lower = text.toString().lowercase()
        val q = query.lowercase()
        val out = ArrayList<Int>()
        var i = lower.indexOf(q)
        while (i >= 0) {
            out.add(i)
            i = lower.indexOf(q, i + q.length)
        }
        return out
    }

    private fun clearHighlights(text: Spannable) {
        for (s in text.getSpans(0, text.length, FindHighlightSpan::class.java)) {
            text.removeSpan(s)
        }
    }

    private fun scrollToOffset(tv: TextView, scroll: View, offset: Int) {
        tv.post {
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(offset.coerceIn(0, tv.text.length - 1).coerceAtLeast(0))
            (scroll as? ScrollView)?.smoothScrollTo(0, (layout.getLineTop(line) - 100).coerceAtLeast(0))
        }
    }
}
