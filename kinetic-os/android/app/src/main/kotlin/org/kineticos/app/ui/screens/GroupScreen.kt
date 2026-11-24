package org.kineticos.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.kineticos.app.MainActivity
import org.kineticos.app.graph
import org.kineticos.app.net.*
import org.kineticos.app.ui.*
import java.io.File

data class ChatLine(val from: String, val text: String, val ts: Long, val voiceFile: File? = null, val help: Boolean = false)

@Composable
fun GroupScreen(activity: MainActivity) {
    val ctx = LocalContext.current; val g = ctx.graph; val p = LocalPalette.current
    val settings by g.settings.collectAsStateWithLifecycle()
    val sockState by g.socket.state.collectAsStateWithLifecycle()
    val members by g.socket.members.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var publicGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<Invitation>>(emptyList()) }
    var active by remember { mutableStateOf<Group?>(null) }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var code by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var inviteUser by remember { mutableStateOf("") }
    var friendUser by remember { mutableStateOf("") }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    suspend fun refresh() = runCatching {
        groups = g.api.groups(); publicGroups = g.api.publicGroups(); friends = g.api.friends(); requests = g.api.friendRequests(); invitations = g.api.invitations()
    }.onFailure { g.toast("Server: ${it.message}") }
    fun enter(gr: Group) { active = gr; g.socket.joinGroup(gr.id); chat.clear() }

    LaunchedEffect(settings.authToken) { if (settings.authToken != null) refresh() }
    LaunchedEffect(Unit) { activity.deepLinkJoin.collect { c -> code = c; if (settings.authToken != null) runCatching { enter(g.api.joinGroup(c)) }.onFailure { g.toast(it.message ?: "join failed") } } }
    LaunchedEffect(active?.id) {
        val gid = active?.id ?: return@LaunchedEffect
        g.socket.messages.collect { o ->
            if (o["groupId"]?.jsonPrimitive?.contentOrNull != gid) return@collect
            val from = o["displayName"]?.jsonPrimitive?.contentOrNull ?: "?"
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "chat" -> chat += ChatLine(from, o["text"]?.jsonPrimitive?.content ?: "", System.currentTimeMillis())
                "help_request" -> { chat += ChatLine(from, "🆘 HELP: " + (o["message"]?.jsonPrimitive?.content ?: "") + " @ %.5f, %.5f".format(o["lat"]?.jsonPrimitive?.double, o["lon"]?.jsonPrimitive?.double), System.currentTimeMillis(), help = true); g.toast("Help request from $from") }
                "voice" -> runCatching {
                    val f = File(ctx.cacheDir, "voice-${System.nanoTime()}.m4a"); f.writeBytes(Base64.decode(o["dataBase64"]!!.jsonPrimitive.content, Base64.DEFAULT))
                    chat += ChatLine(from, "🎤 voice %.0fs".format((o["durationMs"]?.jsonPrimitive?.long ?: 0) / 1000.0), System.currentTimeMillis(), f)
                    MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start(); setOnCompletionListener { release() } }
                }
                "route_updated" -> g.toast("Group route updated")
            }
            if (chat.size > 200) chat.removeAt(0)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Group ride")
        StarkCompatCard()
        if (settings.serverUrl.isBlank() || settings.authToken == null) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Kinetic group rides use your own self-hosted server (see server/README.md). Set the server and log in under Settings → Account.", color = p.textDim)
            }
            return@Column
        }
        Text("Server link: " + when (val s = sockState) { SocketState.Open -> "online"; SocketState.Connecting -> "connecting"; is SocketState.Retry -> "retry in ${s.inMs / 1000}s (${s.reason})"; SocketState.Off -> "off" }, color = if (sockState == SocketState.Open) K.Regen else K.Warn)
        Toggle("Share live telemetry (SoC, power) with group", settings.shareTelemetryInGroups, { v -> scope.launch { g.settingsRepo.update { it.copy(shareTelemetryInGroups = v) } } }, "Position is shared while you are in a group; telemetry only with this consent")

        active?.let { gr ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(gr.name, style = labelStyle(16), color = K.Accent); Text("Code ${gr.code} · ${gr.visibility}", color = p.textDim) }
                    BigButton("SHARE") { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my ride: kineticos://join/${gr.code} (code ${gr.code})"), "Invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Spacer(Modifier.width(6.dp))
                    BigButton("LEAVE", color = K.Danger) { g.socket.leaveGroup(gr.id); scope.launch { runCatching { g.api.leaveGroup(gr.id) }; active = null; refresh() } }
                }
                val here = members.values.filter { it.groupId == gr.id }
                Text("Riding now: ${here.size}", color = p.text)
                here.forEach { m -> Text("• ${m.displayName}${m.vehicleName?.let { " ($it)" } ?: ""} – ${m.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"}${m.socPct?.let { " · %.0f%%".format(it) } ?: ""}", color = p.textDim) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("Invite username", inviteUser, { inviteUser = it }, Modifier.weight(1f))
                    BigButton("INVITE") { scope.launch { runCatching { g.api.invite(gr.id, inviteUser) }.onSuccess { g.toast("Invited"); inviteUser = "" }.onFailure { g.toast(it.message ?: "") } } }
                }
                SectionTitle("Chat")
                chat.takeLast(20).forEach { c -> Text("${c.from}: ${c.text}", color = if (c.help) K.Danger else p.text, modifier = if (c.voiceFile != null) Modifier else Modifier) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("Message", text, { text = it.take(1000) }, Modifier.weight(1f))
                    BigButton("SEND") { if (text.isNotBlank()) { g.socket.send(buildJsonObject { put("type", "chat"); put("groupId", gr.id); put("text", text) }); chat += ChatLine("me", text, System.currentTimeMillis()); text = "" } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigButton(if (recorder == null) "🎤 RECORD VOICE" else "■ SEND VOICE", Modifier.weight(1f), selected = recorder != null) {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return@BigButton }
                        val r = recorder
                        if (r == null) {
                            val f = File(ctx.cacheDir, "out-${System.nanoTime()}.m4a"); recFile = f
                            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setAudioEncodingBitRate(32000); setAudioSamplingRate(16000); setMaxDuration(60_000); setOutputFile(f.absolutePath); prepare(); start()
                            }; recStart = System.currentTimeMillis()
                        } else {
                            runCatching { r.stop() }; r.release(); recorder = null
                            val f = recFile; val d = System.currentTimeMillis() - recStart
                            if (f != null && f.length() in 1..1_000_000) {
                                g.socket.send(buildJsonObject { put("type", "voice"); put("groupId", gr.id); put("mime", "audio/mp4"); put("durationMs", d); put("dataBase64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) })
                                chat += ChatLine("me", "🎤 voice %.0fs".format(d / 1000.0), System.currentTimeMillis(), f)
                            }
                        }
                    }
                    BigButton("🆘 HELP", Modifier.weight(1f), color = K.Danger) {
                        val f = g.gps.fix.value
                        if (f == null) g.toast("No GPS fix") else g.socket.send(buildJsonObject { put("type", "help_request"); put("groupId", gr.id); put("lat", f.lat); put("lon", f.lon); put("message", "Need assistance") })
                    }
                }
            }
        }
        SectionTitle("My groups")
        groups.forEach { gr -> Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${gr.name} (${gr.code})", color = p.text, modifier = Modifier.weight(1f)); BigButton(if (active?.id == gr.id) "ACTIVE" else "RIDE", selected = active?.id == gr.id) { enter(gr) } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Group code", code, { code = it.uppercase().take(8) }, Modifier.weight(1f))
            BigButton("JOIN") { scope.launch { runCatching { g.api.joinGroup(code) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("New group name", newName, { newName = it.take(40) }, Modifier.weight(1f))
            BigButton("PRIVATE") { scope.launch { runCatching { g.api.createGroup(newName, false) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
            Spacer(Modifier.width(4.dp))
            BigButton("PUBLIC") { scope.launch { runCatching { g.api.createGroup(newName, true) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
        }
        if (invitations.isNotEmpty()) { SectionTitle("Invitations"); invitations.forEach { inv -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${inv.group.name} from ${inv.from?.displayName ?: "?"}", color = p.text, modifier = Modifier.weight(1f))
            BigButton("ACCEPT") { scope.launch { runCatching { g.api.acceptInvitation(inv.id) }.onSuccess { enter(it); refresh() } } } } } }
        SectionTitle("Public groups")
        publicGroups.forEach { gr -> Row(verticalAlignment = Alignment.CenterVertically) { Text(gr.name, color = p.text, modifier = Modifier.weight(1f)); BigButton("JOIN") { scope.launch { runCatching { g.api.joinGroup(gr.code) }.onSuccess { enter(it); refresh() } } } } }
        SectionTitle("Friends")
        friends.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${f.displayName} (@${f.username})", color = p.text, modifier = Modifier.weight(1f)); Pill(if (f.online) "ONLINE" else "OFFLINE", if (f.online) K.Regen else p.textDim) } }
        requests.filter { it.status == "pending" && it.from?.username != settings.username }.forEach { r -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request from ${r.from?.displayName}", color = p.text, modifier = Modifier.weight(1f)); BigButton("ACCEPT") { scope.launch { runCatching { g.api.acceptFriend(r.id) }; refresh() } } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("Add friend (username)", friendUser, { friendUser = it }, Modifier.weight(1f))
            BigButton("ADD") { scope.launch { runCatching { g.api.requestFriend(friendUser) }.onSuccess { g.toast("Request sent"); friendUser = "" }.onFailure { g.toast(it.message ?: "") } } }
        }
    }
}

/**
 * Status of compatibility with the original Stark ecosystem – stated honestly (research 2026-11, see RESEARCH.md).
 */
@Composable
fun StarkCompatCard() {
    val ctx = LocalContext.current; val p = LocalPalette.current
    Card(Modifier.fillMaxWidth()) {
        Text("STARK VARG RIDERS", style = labelStyle(12), color = K.Warn)
        Text("No public or authorised API for Stark group rides, Stark accounts or Stark cloud could be found, and no app named \"VargLink\" could be verified. Kinetic OS therefore cannot show real Stark riders or appear in a Stark group ride, and does not attempt to emulate Stark devices or accounts.", color = p.textDim, style = labelStyle(10))
        Text("Riding together with Stark owners: invite them to a Kinetic group (works on any Android phone), or open the official Stark app.", color = p.textDim, style = labelStyle(10))
        BigButton("OPEN STARK APP / STORE", Modifier.fillMaxWidth().padding(top = 6.dp)) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Stark%20Future")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=Stark%20Future")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
