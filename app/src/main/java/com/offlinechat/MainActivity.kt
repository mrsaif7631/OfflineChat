package com.offlinechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF075E54)
private val LightGreen = Color(0xFF25D366)
private val MyBubble = Color(0xFFDCF8C6)
private val ChatBg = Color(0xFFECE5DD)
private val TickBlue = Color(0xFF34B7F1)

sealed interface Screen {
    data object Home : Screen
    data class Chat(val roomCode: String, val conv: String, val title: String) : Screen
    data class Members(val roomCode: String) : Screen
    data class Photo(val path: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = LightGreen)) {
                App()
            }
        }
    }
}

// ---------------- helpers ----------------

private fun meshPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= 33 -> listOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
    )
    Build.VERSION.SDK_INT >= 31 -> listOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun extraPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

private fun duration(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Avatar(name: String, size: Int = 42) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Green.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), color = Green, fontWeight = FontWeight.Bold, fontSize = (size / 2.3).sp)
    }
}

// ---------------- navigation ----------------

@Composable
private fun App() {
    if (Repo.myName.isBlank()) {
        SetupScreen()
        return
    }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val push: (Screen) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val top = stack.last()) {
        Screen.Home -> HomeScreen(push)
        is Screen.Chat -> ChatScreen(top, push, pop)
        is Screen.Members -> MembersScreen(top, push, pop)
        is Screen.Photo -> PhotoScreen(top, pop)
    }
}

// ---------------- setup ----------------

@Composable
private fun SetupScreen() {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Offline Chat", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Green)
        Text("Chat, photos and voice with nearby phones.\nNo internet. No SIM. No servers.", color = Color.Gray)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(30) },
            label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { Repo.setName(name.trim()) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Continue") }
    }
}

// ---------------- home: rooms ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val v = Repo.version
    val rooms = remember(v) { Repo.db.rooms() }
    var code by remember { mutableStateOf("") }
    var pendingCode by remember { mutableStateOf<String?>(null) }

    fun openRoom(c: String) {
        Repo.db.addRoom(c, Repo.roomIdOf(c))
        Repo.bump()
        MeshService.start(ctx, c)
        push(Screen.Chat(c, Repo.GROUP, c))
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val c = pendingCode
        pendingCode = null
        if (c == null) return@rememberLauncherForActivityResult
        if (meshPermissions().all { granted(ctx, it) }) openRoom(c)
        else toast(ctx, "Bluetooth / Nearby devices permission is needed to talk without internet")
    }

    fun join(c: String) {
        pendingCode = c
        permLauncher.launch((meshPermissions() + extraPermissions()).toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Chat", color = Color.White) },
                actions = {
                    if (Repo.activeRoomCode != null) {
                        TextButton(onClick = { MeshService.stop(ctx) }) { Text("Turn off", color = Color.White) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Green),
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Repo.myName, 52)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(Repo.myName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Your ID: ${Identity.pretty(Repo.identity.userId)}", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("Join or create a room", fontWeight = FontWeight.Bold)
                    Text(
                        "Everyone who types the same secret code joins the same room. Use 8+ characters.",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it },
                            label = { Text("Secret room code") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { join(code.trim()); code = "" },
                            enabled = code.trim().length >= 4,
                        ) { Text("Join") }
                    }
                }
                HorizontalDivider()
                if (rooms.isNotEmpty()) {
                    Text("Your rooms", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold, color = Green)
                }
            }
            items(rooms, key = { it.code }) { room ->
                val unread = remember(v, room.roomId) { Repo.db.unreadCount(room.roomId) }
                val live = Repo.activeRoomCode == room.code
                Row(
                    Modifier.fillMaxWidth().clickable { join(room.code) }.padding(16.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar("#")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(room.code, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (live) "● On · ${Repo.peers} phone(s) nearby" else "Off · tap to turn on",
                            fontSize = 12.sp, color = if (live) LightGreen else Color.Gray,
                        )
                    }
                    if (unread > 0) {
                        Box(
                            Modifier.clip(CircleShape).background(LightGreen).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("$unread", color = Color.White, fontSize = 12.sp) }
                    }
                    TextButton(onClick = {
                        if (live) MeshService.stop(ctx)
                        Repo.db.deleteRoom(room.code, room.roomId)
                        Repo.bump()
                    }) { Text("Delete", color = Color.Gray, fontSize = 12.sp) }
                }
            }
        }
    }
}

// ---------------- chat (group and private) ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(s: Screen.Chat, push: (Screen) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val roomId = remember(s.roomCode) { Repo.roomIdOf(s.roomCode) }
    val isGroup = s.conv == Repo.GROUP
    val v = Repo.version
    val messages = remember(v, roomId, s.conv) { Repo.db.messages(roomId, s.conv) }
    val members = remember(v, roomId) { Repo.db.members(roomId) }
    val peer = members.find { it.uid == s.conv }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordSecs by remember { mutableIntStateOf(0) }

    LaunchedEffect(s.roomCode) {
        if (Repo.activeRoomCode != s.roomCode && meshPermissions().all { granted(ctx, it) }) {
            MeshService.start(ctx, s.roomCode)
        }
    }
    DisposableEffect(roomId, s.conv) {
        Repo.openConv = "$roomId/${s.conv}"
        Repo.db.markRead(roomId, s.conv)
        Repo.bump()
        onDispose {
            Repo.openConv = null
            recorder.cancel()
            VoicePlayer.stop()
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(recording) {
        recordSecs = 0
        while (recording) { delay(1000); recordSecs++ }
    }

    fun engine(): MeshEngine? = MeshService.engine?.takeIf { it.roomCode == s.roomCode }

    fun send(kind: Kind, text: String, media: ByteArray? = null, dur: Int = 0) {
        val e = engine()
        when {
            e == null -> toast(ctx, "Room is off. Go back and tap the room to turn it on.")
            !e.send(s.conv, kind, text, media, dur) -> toast(ctx, "Can't send yet: waiting for this person's key")
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { Media.loadImageForSend(ctx, uri) }
            if (bytes == null) toast(ctx, "Couldn't load that photo") else send(Kind.IMAGE, "", bytes)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recording = recorder.start() else toast(ctx, "Microphone permission is needed for voice notes")
    }

    fun toggleRecord() {
        if (!recording) {
            if (granted(ctx, Manifest.permission.RECORD_AUDIO)) recording = recorder.start()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            recording = false
            val r = recorder.stop()
            if (r == null) toast(ctx, "Voice note too short") else send(Kind.VOICE, "", r.first, r.second)
        }
    }

    val onlineCount = members.count { it.online }
    val subtitle = if (isGroup) {
        if (Repo.activeRoomCode != s.roomCode) "Room is off"
        else "${Repo.peers} nearby · ${onlineCount} online · ${members.size + 1} members"
    } else {
        "🔒 End-to-end encrypted · " + when {
            peer == null -> "unknown"
            peer.online -> "online"
            else -> "last seen ${clock(peer.lastSeen)}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = pop) { Text("←", color = Color.White, fontSize = 22.sp) } },
                title = {
                    Column {
                        Text(if (isGroup) s.title else (peer?.name ?: s.title), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                },
                actions = {
                    if (isGroup) TextButton(onClick = { push(Screen.Members(s.roomCode)) }) {
                        Text("Members", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Green),
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(ChatBg).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (messages.isEmpty()) item {
                    Text(
                        if (isGroup) "No messages yet. Say hi! 👋\nMessages wait on your phone and are delivered when someone comes in range."
                        else "Private chat. Only you and ${peer?.name ?: "them"} can read these messages, even if they pass through other phones.",
                        Modifier.fillMaxWidth().padding(24.dp), color = Color.DarkGray, fontSize = 13.sp,
                    )
                }
                items(messages, key = { it.id }) { m ->
                    Bubble(m, showSender = isGroup) { path -> push(Screen.Photo(path)) }
                }
            }

            if (recording) {
                Text(
                    "🔴 Recording ${duration(recordSecs * 1000)}… tap ■ to send",
                    Modifier.fillMaxWidth().background(Color.White).padding(12.dp), color = Color.Red,
                )
            }

            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("📷", fontSize = 22.sp) }
                TextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                if (input.isBlank()) {
                    Button(onClick = { toggleRecord() }, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(48.dp)) {
                        Text(if (recording) "■" else "🎤", fontSize = 18.sp)
                    }
                } else {
                    Button(onClick = { send(Kind.TEXT, input.trim()); input = "" }, shape = CircleShape,
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) {
                        Text("➤", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, showSender: Boolean, onPhoto: (String) -> Unit) {
    val mine = m.outgoing
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier.widthIn(max = 290.dp)
                .background(if (mine) MyBubble else Color.White, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (!mine && showSender) Text(m.senderName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Green)
            when (m.kind) {
                Kind.TEXT -> Text(m.text, fontSize = 15.sp)
                Kind.IMAGE -> {
                    val bmp by produceState<Bitmap?>(null, m.mediaPath) {
                        value = withContext(Dispatchers.IO) { m.mediaPath?.let { Media.decodeScaled(it, 600) } }
                    }
                    val b = bmp
                    if (b != null) {
                        Image(
                            bitmap = b.asImageBitmap(), contentDescription = "Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(230.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { m.mediaPath?.let(onPhoto) },
                        )
                    } else {
                        Text("📷 Photo", color = Color.Gray)
                    }
                }
                Kind.VOICE -> {
                    val playing = VoicePlayer.playingPath != null && VoicePlayer.playingPath == m.mediaPath
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { m.mediaPath?.let { VoicePlayer.toggle(it) } }) {
                            Text(if (playing) "⏸" else "▶", fontSize = 22.sp, color = Green)
                        }
                        Text("🎤 Voice · ${duration(m.durationMs)}", fontSize = 14.sp)
                    }
                }
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(m.time), fontSize = 10.sp, color = Color.Gray)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    when (m.status) {
                        Status.WAITING -> Text("🕓", fontSize = 10.sp)
                        Status.SENT -> Text("✓", fontSize = 12.sp, color = Color.Gray)
                        else -> Text(
                            if (m.isPrivate || m.acks <= 1) "✓✓" else "✓✓ ${m.acks}",
                            fontSize = 12.sp, color = TickBlue,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- members ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersScreen(s: Screen.Members, push: (Screen) -> Unit, pop: () -> Unit) {
    val roomId = remember(s.roomCode) { Repo.roomIdOf(s.roomCode) }
    val v = Repo.version
    val members = remember(v, roomId) { Repo.db.members(roomId) }

    // refresh "online" labels every 30 s
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = pop) { Text("←", color = Color.White, fontSize = 22.sp) } },
                title = { Text("Members (${members.size + 1})", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Green),
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                MemberRow(Repo.myName + " (you)", Repo.identity.userId, online = true, unread = 0, onClick = null)
                HorizontalDivider()
                Text(
                    "Tap a person for a private chat. Compare IDs face-to-face to be sure it's really them.",
                    Modifier.padding(16.dp), fontSize = 12.sp, color = Color.Gray,
                )
            }
            items(members, key = { it.uid }) { m ->
                val unread = remember(v, tick, m.uid) { Repo.db.unreadCount(roomId, m.uid) }
                MemberRow(m.name, m.uid, m.online, unread) { push(Screen.Chat(s.roomCode, m.uid, m.name)) }
            }
            if (members.isEmpty()) item {
                Text("Nobody seen yet. Members appear when their phone comes in range.", Modifier.padding(16.dp), color = Color.Gray)
            }
        }
    }
}

@Composable
private fun MemberRow(name: String, uid: String, online: Boolean, unread: Int, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(name)
            if (online) Box(Modifier.size(12.dp).clip(CircleShape).background(LightGreen).align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text("ID: ${Identity.pretty(uid)}", fontSize = 12.sp, color = Color.Gray)
        }
        if (unread > 0) {
            Box(Modifier.clip(CircleShape).background(LightGreen).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$unread", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ---------------- photo viewer ----------------

@Composable
private fun PhotoScreen(s: Screen.Photo, pop: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, s.path) {
        value = withContext(Dispatchers.IO) { Media.decodeScaled(s.path, 2000) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { pop() }, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else CircularProgressIndicator()
    }
}
