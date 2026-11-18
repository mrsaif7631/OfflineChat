package com.offlinechat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class Kind { TEXT, IMAGE, VOICE }

object Status {
    const val WAITING = 0    // no phone nearby yet; will be delivered when one appears
    const val SENT = 1       // handed to at least one nearby phone
    const val DELIVERED = 2  // a recipient confirmed it
}

data class Msg(
    val id: String,
    val roomId: String,
    val conv: String,          // "group" or the other person's userId
    val senderId: String,
    val senderName: String,
    val kind: Kind,
    val text: String,
    val mediaPath: String?,
    val durationMs: Int,
    val time: Long,
    val outgoing: Boolean,
    val status: Int,
    val isPrivate: Boolean,
    val unread: Boolean,
    val acks: Int = 0,
)

data class Member(val uid: String, val name: String, val pk: String, val lastSeen: Long) {
    val online get() = System.currentTimeMillis() - lastSeen < 2 * 60_000
}

data class Room(val code: String, val roomId: String, val lastUsed: Long)

/** Everything is stored only on this phone (SQLite). */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "offlinechat.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rooms(code TEXT PRIMARY KEY, room_id TEXT, last_used INTEGER)")
        db.execSQL(
            """CREATE TABLE messages(
                id TEXT PRIMARY KEY, room TEXT, conv TEXT, sender_id TEXT, sender_name TEXT,
                kind TEXT, body TEXT, media TEXT, duration INTEGER, time INTEGER,
                outgoing INTEGER, status INTEGER, private INTEGER, unread INTEGER, acks INTEGER DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_msg ON messages(room, conv, time)")
        db.execSQL(
            "CREATE TABLE members(room TEXT, uid TEXT, name TEXT, pk TEXT, last_seen INTEGER, PRIMARY KEY(room, uid))"
        )
        // Raw (still end-to-end encrypted where private) copies of messages, for store-and-forward.
        db.execSQL("CREATE TABLE packets(pid TEXT PRIMARY KEY, room TEXT, json TEXT, time INTEGER)")
        db.execSQL("CREATE INDEX idx_pkt ON packets(room, time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>()
            while (c.moveToNext()) out.add(map(c))
            out
        }

    // ---- rooms ----
    fun addRoom(code: String, roomId: String) {
        writableDatabase.insertWithOnConflict("rooms", null, ContentValues().apply {
            put("code", code); put("room_id", roomId); put("last_used", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun rooms(): List<Room> =
        query("SELECT code, room_id, last_used FROM rooms ORDER BY last_used DESC", arrayOf()) {
            Room(it.getString(0), it.getString(1), it.getLong(2))
        }

    fun deleteRoom(code: String, roomId: String) {
        val w = writableDatabase
        w.delete("rooms", "code=?", arrayOf(code))
        w.delete("messages", "room=?", arrayOf(roomId))
        w.delete("members", "room=?", arrayOf(roomId))
        w.delete("packets", "room=?", arrayOf(roomId))
    }

    // ---- messages ----
    /** Returns false if the message was already stored. */
    fun insertMessage(m: Msg): Boolean = writableDatabase.insertWithOnConflict("messages", null,
        ContentValues().apply {
            put("id", m.id); put("room", m.roomId); put("conv", m.conv)
            put("sender_id", m.senderId); put("sender_name", m.senderName)
            put("kind", m.kind.name); put("body", m.text); put("media", m.mediaPath)
            put("duration", m.durationMs); put("time", m.time)
            put("outgoing", if (m.outgoing) 1 else 0); put("status", m.status)
            put("private", if (m.isPrivate) 1 else 0); put("unread", if (m.unread) 1 else 0)
            put("acks", m.acks)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun messages(roomId: String, conv: String): List<Msg> = query(
        """SELECT id, room, conv, sender_id, sender_name, kind, body, media, duration, time,
           outgoing, status, private, unread, acks FROM messages WHERE room=? AND conv=? ORDER BY time""",
        arrayOf(roomId, conv)
    ) { c ->
        Msg(
            id = c.getString(0), roomId = c.getString(1), conv = c.getString(2),
            senderId = c.getString(3), senderName = c.getString(4),
            kind = runCatching { Kind.valueOf(c.getString(5)) }.getOrDefault(Kind.TEXT),
            text = c.getString(6) ?: "", mediaPath = c.getString(7), durationMs = c.getInt(8),
            time = c.getLong(9), outgoing = c.getInt(10) == 1, status = c.getInt(11),
            isPrivate = c.getInt(12) == 1, unread = c.getInt(13) == 1, acks = c.getInt(14),
        )
    }

    fun addAck(msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET acks = acks + 1, status = ${Status.DELIVERED} WHERE id=? AND outgoing=1",
            arrayOf(msgId)
        )
    }

    fun markSentIfWaiting(msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ${Status.SENT} WHERE id=? AND status = ${Status.WAITING}",
            arrayOf(msgId)
        )
    }

    fun markRead(roomId: String, conv: String) {
        writableDatabase.execSQL("UPDATE messages SET unread=0 WHERE room=? AND conv=?", arrayOf(roomId, conv))
    }

    fun unreadCount(roomId: String, conv: String? = null): Int {
        val sql = if (conv == null) "SELECT COUNT(*) FROM messages WHERE room=? AND unread=1"
        else "SELECT COUNT(*) FROM messages WHERE room=? AND conv=? AND unread=1"
        val args = if (conv == null) arrayOf(roomId) else arrayOf(roomId, conv)
        return query(sql, args) { it.getInt(0) }.firstOrNull() ?: 0
    }

    // ---- members ----
    fun upsertMember(roomId: String, m: Member) {
        writableDatabase.insertWithOnConflict("members", null, ContentValues().apply {
            put("room", roomId); put("uid", m.uid); put("name", m.name)
            put("pk", m.pk); put("last_seen", m.lastSeen)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun members(roomId: String): List<Member> = query(
        "SELECT uid, name, pk, last_seen FROM members WHERE room=? ORDER BY last_seen DESC", arrayOf(roomId)
    ) { Member(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)) }

    fun member(roomId: String, uid: String): Member? = query(
        "SELECT uid, name, pk, last_seen FROM members WHERE room=? AND uid=?", arrayOf(roomId, uid)
    ) { Member(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)) }.firstOrNull()

    // ---- packets (store-and-forward) ----
    fun hasPacket(pid: String): Boolean =
        query("SELECT 1 FROM packets WHERE pid=?", arrayOf(pid)) { true }.isNotEmpty()

    fun savePacket(pid: String, roomId: String, json: String, time: Long) {
        val w = writableDatabase
        w.insertWithOnConflict("packets", null, ContentValues().apply {
            put("pid", pid); put("room", roomId); put("json", json); put("time", time)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        // keep only the newest 500 per room
        w.execSQL(
            "DELETE FROM packets WHERE room=? AND pid NOT IN (SELECT pid FROM packets WHERE room=? ORDER BY time DESC LIMIT 500)",
            arrayOf(roomId, roomId)
        )
    }

    fun packetIds(roomId: String, limit: Int): List<String> = query(
        "SELECT pid FROM packets WHERE room=? ORDER BY time DESC LIMIT $limit", arrayOf(roomId)
    ) { it.getString(0) }

    fun packet(pid: String): String? =
        query("SELECT json FROM packets WHERE pid=?", arrayOf(pid)) { it.getString(0) }.firstOrNull()
}
