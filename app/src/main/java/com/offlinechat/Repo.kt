package com.offlinechat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class OfflineChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

/** App-wide state shared by the screens and the background service. */
object Repo {
    const val GROUP = "group"

    lateinit var db: Db
        private set
    lateinit var identity: Identity
        private set
    private lateinit var prefs: android.content.SharedPreferences
    private var ready = false

    var myName by mutableStateOf("")
        private set
    /** Bumped whenever anything changes; screens re-read the database when it changes. */
    var version by mutableIntStateOf(0)
        private set
    var peers by mutableIntStateOf(0)
        private set
    var activeRoomCode by mutableStateOf<String?>(null)

    /** "roomId/conv" of the chat currently on screen (no notification for that one). */
    @Volatile var openConv: String? = null

    private val roomIds = HashMap<String, String>()

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        db = Db(app)
        identity = Identity.load(app)
        myName = prefs.getString("name", "") ?: ""
        Notifier.createChannels(app)
    }

    fun setName(name: String) {
        prefs.edit().putString("name", name).apply()
        myName = name
    }

    fun roomIdOf(code: String): String = roomIds.getOrPut(code) { RoomCrypto(code).roomId }

    fun bump() { version++ }
    fun setPeers(n: Int) { peers = n; bump() }
    fun isOpen(roomId: String, conv: String) = openConv == "$roomId/$conv"

    fun saveMessage(m: Msg): Boolean = db.insertMessage(m).also { if (it) bump() }
    fun upsertMember(roomId: String, m: Member) { db.upsertMember(roomId, m); bump() }
    fun ackReceived(msgId: String) { db.addAck(msgId); bump() }
}
