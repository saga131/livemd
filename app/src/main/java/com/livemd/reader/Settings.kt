package com.livemd.reader

import android.content.Context

object Settings {
    private const val PREF = "settings"

    fun load(ctx: Context): CouchConfig? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val uri = p.getString("uri", "") ?: ""
        if (uri.isEmpty()) return null
        return CouchConfig(
            uri,
            p.getString("db", "") ?: "",
            p.getString("user", "") ?: "",
            p.getString("pass", "") ?: "",
            p.getString("passphrase", "") ?: ""
        )
    }

    fun has(ctx: Context): Boolean =
        (ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("uri", "") ?: "").isNotEmpty()

    fun save(ctx: Context, cfg: CouchConfig) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("uri", cfg.uri)
            .putString("db", cfg.dbName)
            .putString("user", cfg.username)
            .putString("pass", cfg.password)
            .putString("passphrase", cfg.passphrase)
            .apply()
    }

    /** 同步范围：all=true 全部；否则 folders 为顶层文件夹集合（"" 代表根目录散落的文件） */
    fun loadSyncFilter(ctx: Context): Pair<Boolean, Set<String>?> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Pair(p.getBoolean("syncAll", true), p.getStringSet("syncFolders", null))
    }

    fun saveSyncFilter(ctx: Context, all: Boolean, folders: Set<String>?) {
        val e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("syncAll", all)
        if (folders != null) e.putStringSet("syncFolders", folders) else e.remove("syncFolders")
        e.apply()
    }
}
