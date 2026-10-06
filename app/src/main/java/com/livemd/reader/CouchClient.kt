package com.livemd.reader

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CouchConfig(
    val uri: String,
    val dbName: String,
    val username: String,
    val password: String,
    val passphrase: String
)

class CouchException(val code: Int, message: String) : Exception(message)

/** 极简 CouchDB REST 客户端，仅依赖 HttpURLConnection + org.json */
class CouchClient(private val cfg: CouchConfig) {

    private fun authHeader(): String? {
        if (cfg.username.isEmpty()) return null
        val raw = "${cfg.username}:${cfg.password}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun request(path: String, method: String = "GET", body: String? = null): ByteArray {
        val conn = URL(cfg.uri.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 300000 // 图片批量下载响应可达几十 MB，弱带宽下要给足时间
            authHeader()?.let { conn.setRequestProperty("Authorization", it) }
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(String(bytes, Charsets.UTF_8)).optString("error")
                } catch (e: Exception) {
                    ""
                }
                throw CouchException(code, "HTTP $code ${msg.ifEmpty { conn.responseMessage ?: "" }}")
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun requestJson(path: String, method: String = "GET", body: String? = null): JSONObject =
        JSONObject(String(request(path, method, body), Charsets.UTF_8))

    fun testConnection(): String {
        val hello = requestJson("/")
        val version = hello.optString("couchdb", "?")
        val db = requestJson("/" + cfg.dbName)
        val count = db.optInt("doc_count", -1)
        return "连接成功 · CouchDB $version · ${db.optString("db_name")} · $count 个文档"
    }

    /** LiveSync 的 PBKDF2 salt 存在 _local 同步参数文档里（base64 编码），E2EE 关闭时可能拿不到 */
    fun getSyncParamsSalt(): ByteArray? = try {
        val doc = requestJson("/${cfg.dbName}/_local/obsidian_livesync_sync_parameters")
        val saltB64 = doc.optString("pbkdf2salt", "")
        if (saltB64.isEmpty()) null else Base64.decode(saltB64, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /** 一次拉全库（小库可用），含全部分块文档 */
    fun getAllDocs(): JSONArray {
        val bytes = request("/${cfg.dbName}/_all_docs?include_docs=true", "POST", "{\"include_docs\":true}")
        return JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("rows") ?: JSONArray()
    }

    /**
     * Mango 查询：只取笔记元数据文档（服务端过滤，附件分块不下发）。
     * 无索引时 CouchDB 会全库扫描但仅返回匹配文档，量级可控。
     */
    fun findNoteMetas(): JSONArray {
        val body = "{\"selector\":{\"type\":{\"\$in\":[\"notes\",\"newnote\",\"plain\"]}},\"limit\":100000}"
        val bytes = request("/${cfg.dbName}/_find", "POST", body)
        return JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("docs") ?: JSONArray()
    }

    /** 轻量查询：只取路径字段（目录选择页用，响应体积约为全量的 1/4） */
    fun findNotePaths(): List<String> {
        val body = "{\"selector\":{\"type\":{\"\$in\":[\"notes\",\"newnote\",\"plain\"]}}," +
            "\"fields\":[\"path\"],\"limit\":100000}"
        val bytes = request("/${cfg.dbName}/_find", "POST", body)
        val docs = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("docs") ?: JSONArray()
        val out = ArrayList<String>(docs.length())
        for (i in 0 until docs.length()) {
            docs.optJSONObject(i)?.optString("path", "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    /** 服务端压缩衍生图（imgcache 服务按 rev 缓存）。未部署/失败返回 null，调用方回退分块直读 */
    fun fetchCompressedImage(db: String, path: String): ByteArray? = try {
        val enc = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        request("/imgcache/$db/$enc", "GET")
    } catch (e: Exception) {
        null
    }

    /** 给 type 字段建 Mango 索引（幂等）。没有索引时 _find 是全库扫描，2 万文档要十几秒 */
    fun ensureTypeIndex() {
        try {
            request(
                "/${cfg.dbName}/_index", "POST",
                "{\"index\":{\"fields\":[\"type\"]},\"name\":\"idx-type\"}"
            )
        } catch (e: Exception) {
            // 索引创建失败不影响主流程，查询仍可回退全扫描
        }
    }

    /** 批量取分块文档（每批 100 个 id，控制单响应体积，弱带宽更稳） */
    fun bulkGetChunks(ids: List<String>): HashMap<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        ids.chunked(100).forEach { chunkIds ->
            val arr = chunkIds.joinToString(",") { """{"id":"${it.replace("\"", "\\\"")}"}""" }
            val bytes = request("/${cfg.dbName}/_bulk_get", "POST", """{"docs":[$arr]}""")
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val results = obj.optJSONArray("results") ?: return@forEach
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val docs = r.optJSONArray("docs") ?: continue
                for (j in 0 until docs.length()) {
                    docs.optJSONObject(j)?.optJSONObject("ok")?.let {
                        out[it.optString("_id")] = it
                    }
                }
            }
        }
        return out
    }

    /** 实时更新用的轻量游标探测：库有没有新变更（只拿 id 不拿内容，几 KB） */
    fun peekChanges(since: String): Pair<Boolean, String> = try {
        val bytes = request("/${cfg.dbName}/_changes?feed=normal&limit=1&since=" +
            java.net.URLEncoder.encode(since, "UTF-8"), "GET")
        val obj = JSONObject(String(bytes, Charsets.UTF_8))
        val has = (obj.optJSONArray("results")?.length() ?: 0) > 0
        Pair(has, obj.optString("last_seq", since))
    } catch (e: Exception) {
        Pair(false, since)
    }

    /**
     * 实时更新：长轮询 _changes，库里有任何变更（其他设备写入）立即返回 true；
     * 超时无变更或网络异常返回 false，由调用方决定重试。
     * since 必须传当前 update_seq，否则等于 since=0，启动瞬间会把全库历史变更都当成"新变更"。
     */
    fun waitForChanges(timeoutMs: Long = 25000): Boolean = try {
        val current = JSONObject(String(request("/${cfg.dbName}", "GET"), Charsets.UTF_8))
            .optString("update_seq", "0")
        val bytes = request(
            "/${cfg.dbName}/_changes?feed=longpoll&timeout=$timeoutMs&limit=1&since=" +
                java.net.URLEncoder.encode(current, "UTF-8"), "GET"
        )
        val obj = JSONObject(String(bytes, Charsets.UTF_8))
        (obj.optJSONArray("results")?.length() ?: 0) > 0
    } catch (e: Exception) {
        false
    }
}
