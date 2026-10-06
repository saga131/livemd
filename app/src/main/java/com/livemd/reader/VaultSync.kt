package com.livemd.reader

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder

data class SyncResult(val ok: Boolean, val notes: Int, val message: String)

/**
 * 从 CouchDB 拉取笔记并还原为本地文件。
 * LiveSync 的存储模型：每个文件 = 一条元数据文档（path/children/mtime…）+ 多条内容寻址分块（h:*）。
 * 分块 data 可能被 E2EE 加密；newnote 类型的分块内容是 base64，plain 类型是纯文本。
 *
 * 性能原则：只下载「已同步笔记引用到的」图片附件，其余几千个附件一律不碰。
 */
object VaultSync {

    private val NOTE_TYPES = setOf("notes", "newnote", "plain")
    private const val ENCRYPTED_META_PREFIX = "/\\:" // JS 源码 "/\\:"，即 斜杠+反斜杠+冒号
    private val TEXT_EXT = setOf("md", "markdown", "txt")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val lock = Any() // 手动同步与实时轮询可能并发，串行化保护本地文件写入

    // 两种图片引用：![[name.png|300]] 与 ![alt](path.png)
    private val IMAGE_REF = Regex(
        "!\\[\\[([^\\]|]+?)(?:\\|[^\\]]*)?\\]\\]|!\\[([^\\]]*)\\]\\(([^)]+)\\)"
    )

    fun pull(context: Context, cfg: CouchConfig): SyncResult = synchronized(lock) {
        val client = CouchClient(cfg)
        client.ensureTypeIndex()
        val salt = client.getSyncParamsSalt()

        // 1. 只取元数据（服务端过滤，附件分块不下发）；图片单独建索引待引用解析
        val allMetas = client.findNoteMetas()
        val metas = ArrayList<JSONObject>()
        val imageIndex = HashMap<String, JSONObject>() // 小写全路径 / 小写文件名 → 附件文档
        for (i in 0 until allMetas.length()) {
            val doc = allMetas.optJSONObject(i) ?: continue
            if (doc.optBoolean("deleted", false)) continue
            val path = doc.optString("path", "")
            val ext = path.substringAfterLast('.').lowercase()
            when {
                ext in TEXT_EXT -> metas.add(doc)
                ext in IMAGE_EXT -> {
                    imageIndex[path.lowercase()] = doc
                    imageIndex[path.substringAfterLast('/').lowercase()] = doc
                }
            }
        }
        android.util.Log.d("LiveSync", "元数据查询完成: ${allMetas.length()} 条中取 ${metas.size} 篇文本笔记 / ${imageIndex.size / 2} 张图片索引")

        // 服务器目录清单缓存：目录选择页秒开（范围过滤前的全量统计）
        saveFolderInventory(context, metas)

        // 同步范围过滤（必须在下载分块之前！范围外的笔记连分块都不下载）
        val (syncAll, syncFolders) = Settings.loadSyncFilter(context)
        if (!syncAll && syncFolders != null) {
            val kept = metas.filter { m ->
                val p = m.optString("path", "")
                if (!p.contains('/')) "" in syncFolders
                else syncFolders.any { f -> f.isNotEmpty() && p.startsWith("$f/") }
            }
            android.util.Log.d("LiveSync", "同步范围过滤: ${metas.size} -> ${kept.size} 篇")
            metas.clear()
            metas.addAll(kept)
        }

        // 2. 只下载文本笔记引用到的分块
        val chunkIds = LinkedHashSet<String>()
        for (m in metas) {
            val children = m.optJSONArray("children") ?: continue
            for (i in 0 until children.length()) chunkIds.add(children.optString(i))
        }
        val byId = client.bulkGetChunks(chunkIds.toList())
        android.util.Log.d("LiveSync", "笔记分块下载完成: ${byId.size} 个")

        // 3. 先组装文本到内存（要知道内容才能解析图片引用）
        val notesDir = File(context.filesDir, "notes")
        val attachDir = File(context.filesDir, "attachments")
        notesDir.mkdirs(); attachDir.mkdirs()
        val contentMap = LinkedHashMap<String, String>() // safePath -> 内容
        val keep = HashSet<String>()
        var skipped = 0
        val errors = StringBuilder()

        for (m in metas) {
            try {
                if (m.optBoolean("deleted", false)) continue
                val path = resolvePath(m, cfg, salt) ?: continue
                val safe = sanitize(path)
                if (safe == null) { skipped++; continue }
                val bytes = assembleBytes(m, byId, cfg, salt)
                if (bytes == null) { skipped++; continue }
                contentMap[safe] = String(bytes, Charsets.UTF_8)
                keep.add(safe)
            } catch (e: Exception) {
                errors.append("· ").append(m.optString("path", m.optString("_id"))).append(": ")
                    .append(e.message).append('\n')
            }
        }

        // 4. 解析图片引用，只下载被引用的图片
        val imageRefs = HashSet<String>()
        for (content in contentMap.values) {
            for (mt in IMAGE_REF.findAll(content)) {
                val raw = mt.groupValues[1].ifEmpty { mt.groupValues[3] }
                if (raw.isBlank()) continue
                val ref = try {
                    URLDecoder.decode(raw.trim().substringAfterLast('/'), "UTF-8")
                } catch (e: Exception) { raw.trim().substringAfterLast('/') }
                imageRefs.add(ref)
            }
        }
        var imgCount = 0
        var imgSkipped = 0
        val keepAttach = HashSet<String>()
        val metaDir = File(context.filesDir, "attachmeta")
        metaDir.mkdirs()
        if (imageRefs.isNotEmpty() && imageIndex.isNotEmpty()) {
            val imgDocsToFetch = ArrayList<JSONObject>()
            for (ref in imageRefs) {
                val doc = imageIndex[ref.lowercase()] ?: continue
                try {
                    val path = resolvePath(doc, cfg, salt) ?: continue
                    val name = path.substringAfterLast('/')
                    val fp = fingerprint(doc)
                    val f = File(attachDir, name)
                    val side = File(metaDir, "$name.idx")
                    val hasFile = f.exists() && f.length() > 0
                    val sideMatch = fp.isNotEmpty() && side.exists() && side.readText() == fp
                    when {
                        // 指纹一致：children 即内容寻址哈希序列，等于远端内容没变
                        hasFile && sideMatch -> { keepAttach.add(name); imgSkipped++ }
                        // 旧版本下载的文件没有指纹记录：信任一次并补写（Obsidian 粘贴图文件名带时间戳，同名即同内容）
                        hasFile -> { side.writeText(fp); keepAttach.add(name); imgSkipped++ }
                        // 本地没有，或远端指纹已变 → 下载/更新
                        else -> imgDocsToFetch.add(doc)
                    }
                } catch (e: Exception) {
                    errors.append("· 图片索引: ").append(e.message).append('\n')
                }
            }
            val missing = imageRefs.size - imgDocsToFetch.size - imgSkipped
            android.util.Log.d("LiveSync", "图片: 引用${imageRefs.size}, 本地已有${imgSkipped}, 待下载${imgDocsToFetch.size}" +
                    (if (missing > 0) ", ${missing} 张引用未在库中找到" else ""))
            // 逐张下载：一次 _bulk_get 只取一张图的分块（约 2MB），写盘即释放。
            // 绝不能把所有图片分块攒在内存里——几百 MB 直接 OOM（OutOfMemoryError 是 Error，catch(Exception) 拦不住）。
            var done = 0
            var useCompression = true // 服务端压缩衍生图；一旦失败（未部署/E2EE）本轮回退分块直读
            var viaCompression = 0
            val t0 = android.os.SystemClock.elapsedRealtime()
            for (doc in imgDocsToFetch) {
                try {
                    val path = resolvePath(doc, cfg, salt) ?: continue
                    val name = path.substringAfterLast('/')
                    // 优先走服务端压缩端点（原图不动，流量省 80-90%）；失败回退分块直读
                    var bytes: ByteArray? = null
                    if (useCompression) {
                        bytes = client.fetchCompressedImage(cfg.dbName, path)
                        if (bytes == null) useCompression = false
                    }
                    if (bytes == null) {
                        val children = doc.optJSONArray("children") ?: continue
                        val ids = (0 until children.length()).map { children.optString(it) }
                        val byId = client.bulkGetChunks(ids)
                        bytes = assembleBytes(doc, byId, cfg, salt) ?: continue
                    } else {
                        viaCompression++
                    }
                    File(attachDir, name).writeBytes(bytes)
                    File(metaDir, "$name.idx").writeText(fingerprint(doc))
                    keepAttach.add(name)
                    imgCount++
                } catch (e: Exception) {
                    errors.append("· 图片 ").append(doc.optString("_id")).append(": ").append(e.message).append('\n')
                } finally {
                    done++
                    if (done % 20 == 0 || done == imgDocsToFetch.size) {
                        android.util.Log.d("LiveSync", "图片进度 $done/${imgDocsToFetch.size}，已写入 $imgCount（压缩通道 $viaCompression），耗时 ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000}s")
                    }
                }
            }
        }

        // 5. 写笔记文件
        for ((safe, content) in contentMap) {
            val f = File(notesDir, safe)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }

        // 6. 清理服务器上已删除的缓存
        notesDir.walkTopDown().filter { it.isFile }.forEach {
            val rel = it.relativeTo(notesDir).path.replace('\\', '/')
            if (rel !in keep) it.delete()
        }
        attachDir.walkTopDown().filter { it.isFile }.forEach {
            if (it.name !in keepAttach) it.delete()
        }
        metaDir.walkTopDown().filter { it.isFile }.forEach {
            if (it.name.removeSuffix(".idx") !in keepAttach) it.delete()
        }

        val imgMsg = if (imgCount > 0) " · $imgCount 张图片" else ""
        val msg = when {
            count2(contentMap) > 0 && errors.isEmpty() -> "同步完成 · ${contentMap.size} 篇笔记$imgMsg"
            contentMap.isNotEmpty() -> "同步 ${contentMap.size} 篇，部分失败：\n$errors"
            metas.isEmpty() -> "服务器上没有找到笔记文档（检查数据库名是否正确）"
            else -> "同步失败：\n$errors"
        }
        return SyncResult(contentMap.isNotEmpty(), contentMap.size, msg)
    }

    private fun count2(m: Map<*, *>): Int = m.size

    /** 元数据的 path 可能被混淆/加密（f: 前缀或 HKDF 加密），普通情况直接可读 */
    private fun resolvePath(m: JSONObject, cfg: CouchConfig, salt: ByteArray?): String? {
        var path = m.optString("path", "")
        if (path.startsWith(ENCRYPTED_META_PREFIX)) {
            salt ?: throw Exception("元数据加密但缺少 pbkdf2salt")
            val json = Crypto.decryptChunk(path, cfg.passphrase, salt)
            path = JSONObject(json).optString("path", "")
        } else if (path.startsWith("%")) {
            path = Crypto.decryptChunk(path, cfg.passphrase, salt)
        }
        return path.ifEmpty { null }
    }

    /** 组装文件原始字节（图片附件也走这里） */
    private fun assembleBytes(
        m: JSONObject,
        byId: Map<String, JSONObject>,
        cfg: CouchConfig,
        salt: ByteArray?
    ): ByteArray? {
        val type = m.optString("type")
        if (type == "notes") { // 最老的格式：整篇内容存 data
            var data = m.optString("data", "")
            if (m.optBoolean("e_", false)) data = Crypto.decryptChunk(data, cfg.passphrase, salt)
            return data.toByteArray(Charsets.UTF_8)
        }
        val children = m.optJSONArray("children") ?: return null
        val bos = ByteArrayOutputStream()
        for (i in 0 until children.length()) {
            val c = byId[children.optString(i)] ?: continue
            var data = c.optString("data", "")
            if (c.optBoolean("e_", false)) data = Crypto.decryptChunk(data, cfg.passphrase, salt)
            if (c.optString("type") == "chunkpack") continue // 打包分块暂不支持，避免输出乱码
            if (type == "plain") bos.write(data.toByteArray(Charsets.UTF_8))
            else bos.write(Base64.decode(data, Base64.NO_WRAP)) // newnote：逐块 base64
        }
        return bos.toByteArray()
    }

    private fun sanitize(p: String): String? {
        val norm = p.replace('\\', '/')
        if (norm.isEmpty() || norm.startsWith("/") || norm.contains("..")) return null
        return norm
    }

    /** 文件指纹：children 分块 ID 本身就是内容寻址哈希，拼起来即为整个文件的特征，免算本地哈希 */
    private fun fingerprint(doc: JSONObject): String {
        val arr = doc.optJSONArray("children") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optString(i)).append(',')
        return sb.toString()
    }

    /** 目录清单缓存（范围过滤前的全量统计），目录选择页直接秒开 */
    private fun saveFolderInventory(context: Context, textMetas: List<JSONObject>) {
        try {
            val folders = JSONObject()
            var root = 0
            for (m in textMetas) {
                val p = m.optString("path", "")
                if (p.isEmpty()) continue
                if (p.contains('/')) {
                    val top = p.substringBefore('/')
                    folders.put(top, folders.optInt(top) + 1)
                } else root++
            }
            val inv = JSONObject()
                .put("folders", folders)
                .put("root", root)
                .put("textTotal", textMetas.size)
                .put("updated", System.currentTimeMillis())
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("folderInventory", inv.toString()).apply()
        } catch (e: Exception) {
            // 缓存失败不影响同步
        }
    }
}
