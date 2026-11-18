package com.offlinechat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The offline mesh network for one room.
 *
 * Packet types (all encrypted with the room key before going on air):
 *  hello : "I am <name>, here is my public key"  (relayed, every 45 s)
 *  msg   : a chat message (group, or private & end-to-end encrypted)  (relayed)
 *  ack   : "I received message X"  (relayed back to the sender)
 *  inv   : "These are the message ids I have"  (direct neighbour only)
 *  want  : "Please send me these ones I'm missing"  (direct neighbour only)
 *
 * inv/want is "store and forward": a phone carries messages and hands them to
 * anyone it meets later, even if the original sender is gone.
 */
class MeshEngine(private val ctx: Context, val roomCode: String) {

    companion object {
        private const val TAG = "MeshEngine"
        private const val TTL = 6              // max hops
        private const val CHUNK = 30_000       // Nearby BYTES payloads must stay under 32 KB
        private const val HELLO_EVERY_MS = 45_000L
    }

    private val crypto = RoomCrypto(roomCode)
    val roomId = crypto.roomId
    private val me = Repo.identity
    private val client = Nearby.getConnectionsClient(ctx.applicationContext)
    private val serviceId = "com.offlinechat.room.$roomId"
    private val strategy = Strategy.P2P_CLUSTER
    private val main = Handler(Looper.getMainLooper())

    private val connected = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val partial = HashMap<String, Array<ByteArray?>>()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 5000
    }
    private var running = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            sendHello()
            main.postDelayed(this, HELLO_EVERY_MS)
        }
    }

    // ================= lifecycle =================

    fun start() {
        running = true
        client.startAdvertising(
            me.userId, serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { Log.w(TAG, "advertising failed", it) }
        client.startDiscovery(
            serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { Log.w(TAG, "discovery failed", it) }
        main.postDelayed(heartbeat, 3_000)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        pending.clear()
        partial.clear()
        Repo.setPeers(0)
    }

    // ================= sending =================

    /** conv = Repo.GROUP for the room, or a member's userId for a private chat. */
    fun send(conv: String, kind: Kind, text: String, media: ByteArray? = null, durationMs: Int = 0): Boolean {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val isPrivate = conv != Repo.GROUP

        val content = JSONObject().put("text", text).put("dur", durationMs)
        if (media != null) content.put("media", CryptoUtil.b64(media))

        val body = if (!isPrivate) {
            content.toString()
        } else {
            val peer = Repo.db.member(roomId, conv) ?: return false
            val key = me.sharedKey(peer.pk) ?: return false
            CryptoUtil.b64(CryptoUtil.aesEncrypt(key, content.toString().toByteArray()))
        }

        val pkt = JSONObject()
            .put("type", "msg").put("pid", id).put("ttl", TTL)
            .put("uid", me.userId).put("name", Repo.myName).put("pk", me.publicKeyB64)
            .put("to", if (isPrivate) conv else "")
            .put("kind", kind.name).put("time", now).put("body", body)

        seen[id] = true
        Repo.db.savePacket(id, roomId, pkt.toString(), now)
        Repo.saveMessage(
            Msg(
                id = id, roomId = roomId, conv = conv, senderId = me.userId, senderName = Repo.myName,
                kind = kind, text = text, mediaPath = media?.let { Media.save(ctx, id, kind, it) },
                durationMs = durationMs, time = now, outgoing = true,
                status = if (connected.isEmpty()) Status.WAITING else Status.SENT,
                isPrivate = isPrivate, unread = false,
            )
        )
        sendJson(pkt, connected)
        return true
    }

    private fun sendHello(to: Collection<String> = connected) {
        val pid = UUID.randomUUID().toString()
        seen[pid] = true
        sendJson(
            JSONObject().put("type", "hello").put("pid", pid).put("ttl", TTL)
                .put("uid", me.userId).put("name", Repo.myName).put("pk", me.publicKeyB64),
            to
        )
    }

    private fun sendAck(msgId: String, toUid: String) {
        val pid = UUID.randomUUID().toString()
        seen[pid] = true
        sendJson(
            JSONObject().put("type", "ack").put("pid", pid).put("ttl", TTL)
                .put("msgId", msgId).put("uid", me.userId).put("to", toUid),
            connected
        )
    }

    private fun sendInventory(endpointId: String) {
        val ids = JSONArray(Repo.db.packetIds(roomId, 300))
        sendJson(JSONObject().put("type", "inv").put("ids", ids), listOf(endpointId))
    }

    private fun relay(from: String?, p: JSONObject) {
        val ttl = p.optInt("ttl", 0)
        if (ttl <= 0) return
        val copy = JSONObject(p.toString()).put("ttl", ttl - 1)
        sendJson(copy, connected.filter { it != from })
    }

    /** Encrypt with the room key, split into chunks if needed, and send. */
    private fun sendJson(p: JSONObject, targets: Collection<String>) {
        val list = targets.toList()
        if (list.isEmpty()) return
        val data = crypto.encrypt(p.toString().toByteArray())
        if (data.size < CHUNK) {
            client.sendPayload(list, Payload.fromBytes(byteArrayOf(0) + data))
            return
        }
        val transferId = CryptoUtil.randomBytes(8)
        val total = (data.size + CHUNK - 1) / CHUNK
        for (i in 0 until total) {
            val start = i * CHUNK
            val len = minOf(CHUNK, data.size - start)
            val bb = ByteBuffer.allocate(1 + 8 + 2 + 2 + len)
            bb.put(1.toByte())
            bb.put(transferId)
            bb.putShort(i.toShort())
            bb.putShort(total.toShort())
            bb.put(data, start, len)
            client.sendPayload(list, Payload.fromBytes(bb.array()))
        }
    }

    // ================= receiving =================

    private fun onFrame(from: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt()) {
            0 -> deliver(from, bytes.copyOfRange(1, bytes.size))
            1 -> {
                val bb = ByteBuffer.wrap(bytes)
                bb.get()
                val tid = ByteArray(8).also { bb.get(it) }
                val index = bb.short.toInt() and 0xFFFF
                val total = bb.short.toInt() and 0xFFFF
                val chunk = ByteArray(bb.remaining()).also { bb.get(it) }
                val key = from + ":" + CryptoUtil.hex(tid)
                val parts = partial.getOrPut(key) { arrayOfNulls(total) }
                if (index >= parts.size) return
                parts[index] = chunk
                if (parts.all { it != null }) {
                    partial.remove(key)
                    val out = ByteArrayOutputStream()
                    parts.forEach { out.write(it!!) }
                    deliver(from, out.toByteArray())
                }
            }
        }
    }

    private fun deliver(from: String, encrypted: ByteArray) {
        val plain = crypto.decrypt(encrypted) ?: return       // not our room -> ignore
        val p = try { JSONObject(String(plain)) } catch (e: Exception) { return }
        try {
            when (p.optString("type")) {
                "msg" -> onMsg(from, p)
                "ack" -> onAck(from, p)
                "hello" -> onHello(from, p)
                "inv" -> onInventory(from, p)
                "want" -> onWant(from, p)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad packet", e)
        }
    }

    private fun firstTime(pid: String): Boolean {
        if (pid.isEmpty() || seen.containsKey(pid)) return false
        seen[pid] = true
        return true
    }

    private fun verified(p: JSONObject): Boolean {
        val uid = p.optString("uid")
        return uid.isNotEmpty() && uid == Identity.fingerprint(p.optString("pk"))
    }

    private fun onMsg(from: String, p: JSONObject) {
        val pid = p.optString("pid")
        if (!firstTime(pid) || Repo.db.hasPacket(pid) || !verified(p)) return
        val now = System.currentTimeMillis()
        Repo.db.savePacket(pid, roomId, p.toString(), now)

        val uid = p.getString("uid")
        if (uid != me.userId) {
            val name = p.optString("name", "Unknown")
            val pk = p.getString("pk")
            Repo.upsertMember(roomId, Member(uid, name, pk, now))

            val to = p.optString("to")
            val contentStr: String? = when {
                to.isEmpty() -> p.optString("body")
                to == me.userId -> me.sharedKey(pk)?.let { key ->
                    CryptoUtil.aesDecrypt(key, CryptoUtil.unb64(p.optString("body")))?.let { String(it) }
                }
                else -> null   // private message for someone else: carry it, can't read it
            }

            if (contentStr != null) {
                val c = JSONObject(contentStr)
                val kind = runCatching { Kind.valueOf(p.optString("kind")) }.getOrDefault(Kind.TEXT)
                val media = c.optString("media").takeIf { it.isNotEmpty() }
                    ?.let { Media.save(ctx, pid, kind, CryptoUtil.unb64(it)) }
                val conv = if (to.isEmpty()) Repo.GROUP else uid
                val msg = Msg(
                    id = pid, roomId = roomId, conv = conv, senderId = uid, senderName = name,
                    kind = kind, text = c.optString("text"), mediaPath = media,
                    durationMs = c.optInt("dur"), time = p.optLong("time", now), outgoing = false,
                    status = Status.DELIVERED, isPrivate = to.isNotEmpty(),
                    unread = !Repo.isOpen(roomId, conv),
                )
                if (Repo.saveMessage(msg)) {
                    sendAck(pid, uid)
                    if (!Repo.isOpen(roomId, conv)) Notifier.showMessage(ctx, msg, roomCode)
                }
            }
        }
        relay(from, p)
    }

    private fun onAck(from: String, p: JSONObject) {
        if (!firstTime(p.optString("pid"))) return
        if (p.optString("to") == me.userId) Repo.ackReceived(p.optString("msgId"))
        else relay(from, p)
    }

    private fun onHello(from: String, p: JSONObject) {
        if (!firstTime(p.optString("pid")) || !verified(p)) return
        val uid = p.getString("uid")
        if (uid != me.userId) {
            Repo.upsertMember(roomId, Member(uid, p.optString("name", "Unknown"), p.getString("pk"), System.currentTimeMillis()))
        }
        relay(from, p)
    }

    private fun onInventory(from: String, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        val missing = JSONArray()
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            if (id.isNotEmpty() && !Repo.db.hasPacket(id)) missing.put(id)
        }
        if (missing.length() > 0) sendJson(JSONObject().put("type", "want").put("ids", missing), listOf(from))
    }

    private fun onWant(from: String, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            val json = Repo.db.packet(id) ?: continue
            val pkt = JSONObject(json).put("ttl", 3)
            sendJson(pkt, listOf(from))
            if (pkt.optString("uid") == me.userId) Repo.db.markSentIfWaiting(id)
        }
        Repo.bump()
    }

    // ================= Nearby callbacks =================

    private fun requestConnection(endpointId: String) {
        if (!running || endpointId in connected || endpointId in pending) return
        pending += endpointId
        client.requestConnection(me.userId, endpointId, lifecycle)
            .addOnFailureListener { pending -= endpointId }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            // Smaller id dials first; the other side dials too if nothing happened in 4 s.
            if (me.userId < info.endpointName) requestConnection(endpointId)
            else main.postDelayed({ requestConnection(endpointId) }, 4_000)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pending += endpointId
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending -= endpointId
            if (result.status.isSuccess) {
                connected += endpointId
                Repo.setPeers(connected.size)
                sendHello(listOf(endpointId))
                sendInventory(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            partial.keys.removeAll { it.startsWith("$endpointId:") }
            Repo.setPeers(connected.size)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { onFrame(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
