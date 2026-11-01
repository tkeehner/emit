package com.example.emit.drafts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Prime (AI assistant) reply drafts. Phase 1: local table only, empty by default, no network.
 * Later phases will fill this from the AWS relay (E2E encrypted). A draft is only ever
 * sent when the user taps APPROVE in the UI.
 */
data class PrimeDraft(val id: Long, val threadId: Long, val address: String, val body: String, val created: Long, val status: String)

class PrimeDraftStore(context: Context) : SQLiteOpenHelper(context, "prime_drafts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE drafts(_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT,
            body TEXT, created INTEGER, status TEXT DEFAULT 'pending')""")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun pending(threadId: Long? = null): List<PrimeDraft> {
        // 'awaiting' = PC asked for approval; still approvable in the thread.
        val (sel, args) = if (threadId == null) "status IN ('pending','awaiting')" to null
            else "status IN ('pending','awaiting') AND thread_id=?" to arrayOf(threadId.toString())
        return readableDatabase.query("drafts", null, sel, args, null, null, "created ASC").use { c ->
            buildList { while (c.moveToNext()) add(PrimeDraft(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5))) }
        }
    }
    fun deleteForThread(threadId: Long) {
        writableDatabase.delete("drafts", "thread_id=? AND status IN ('pending','awaiting')", arrayOf(threadId.toString()))
    }
    fun setStatus(id: Long, status: String) {
        writableDatabase.update("drafts", ContentValues().apply { put("status", status) }, "_id=?", arrayOf(id.toString()))
    }
    fun create(threadId: Long, address: String, body: String): PrimeDraft {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("drafts", null, ContentValues().apply {
            put("thread_id", threadId); put("address", address); put("body", body); put("created", now); put("status", "pending") })
        return PrimeDraft(id, threadId, address, body, now, "pending")
    }
    fun get(id: Long): PrimeDraft? = readableDatabase.query("drafts", null, "_id=?", arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToFirst()) PrimeDraft(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5)) else null }
    fun recent(limit: Int = 100): List<PrimeDraft> = readableDatabase.query("drafts", null, null, null, null, null, "created DESC", limit.toString()).use { c ->
        buildList { while (c.moveToNext()) add(PrimeDraft(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5))) } }
    /** Atomic status transition; returns false if the draft wasn't in one of [from]. */
    fun transition(id: Long, from: Set<String>, to: String): Boolean = writableDatabase.update("drafts",
        ContentValues().apply { put("status", to) }, "_id=? AND status IN (${from.joinToString(",") { "'$it'" }})", arrayOf(id.toString())) == 1
}
