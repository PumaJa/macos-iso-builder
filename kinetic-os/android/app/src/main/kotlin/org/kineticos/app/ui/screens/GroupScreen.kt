package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

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
    }.onFailure { g.toast(ctx.getString(R.string.grp_server_err, it.message)) }
    fun enter(gr: Group) { active = gr; g.socket.joinGroup(gr.id); chat.clear() }

    LaunchedEffect(settings.authToken) { if (settings.authToken != null) refresh() }
    LaunchedEffect(Unit) { activity.deepLinkJoin.collect { c -> code = c; if (settings.authToken != null) runCatching { enter(g.api.joinGroup(c)) }.onFailure { g.toast(it.message ?: ctx.getString(R.string.grp_join_failed)) } } }
    LaunchedEffect(active?.id) {
        val gid = active?.id ?: return@LaunchedEffect
        g.socket.messages.collect { o ->
            if (o["groupId"]?.jsonPrimitive?.contentOrNull != gid) return@collect
            val from = o["displayName"]?.jsonPrimitive?.contentOrNull ?: "?"
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "chat" -> chat += ChatLine(from, o["text"]?.jsonPrimitive?.content ?: "", System.currentTimeMillis())
                "help_request" -> { chat += ChatLine(from, ctx.getString(R.string.grp_help_msg, o["message"]?.jsonPrimitive?.content ?: "", "%.5f, %.5f".format(o["lat"]?.jsonPrimitive?.double, o["lon"]?.jsonPrimitive?.double)), System.currentTimeMillis(), help = true); g.toast(ctx.getString(R.string.grp_help_toast, from)) }
                "voice" -> runCatching {
                    val f = File(ctx.cacheDir, "voice-${System.nanoTime()}.m4a"); f.writeBytes(Base64.decode(o["dataBase64"]!!.jsonPrimitive.content, Base64.DEFAULT))
                    chat += ChatLine(from, ctx.getString(R.string.grp_voice, (o["durationMs"]?.jsonPrimitive?.long ?: 0) / 1000.0), System.currentTimeMillis(), f)
                    MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start(); setOnCompletionListener { release() } }
                }
                "route_updated" -> g.toast(ctx.getString(R.string.grp_route_updated))
            }
            if (chat.size > 200) chat.removeAt(0)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle(stringResource(R.string.grp_title))
        StarkCompatCard()
        VargLinkCard()
        if (settings.serverUrl.isBlank() || settings.authToken == null) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.grp_server_needed), color = p.textDim)
            }
            return@Column
        }
        Text(stringResource(R.string.grp_link, when (val s = sockState) { SocketState.Open -> stringResource(R.string.grp_link_online); SocketState.Connecting -> stringResource(R.string.grp_link_connecting); is SocketState.Retry -> stringResource(R.string.grp_link_retry, s.inMs / 1000, s.reason); SocketState.Off -> stringResource(R.string.grp_link_off) }), color = if (sockState == SocketState.Open) K.Regen else K.Warn)
        Toggle(stringResource(R.string.grp_share_tel), settings.shareTelemetryInGroups, { v -> scope.launch { g.settingsRepo.update { it.copy(shareTelemetryInGroups = v) } } }, stringResource(R.string.grp_share_tel_sub))

        active?.let { gr ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(gr.name, style = labelStyle(16), color = K.Accent); Text(stringResource(R.string.grp_code_vis, gr.code, gr.visibility), color = p.textDim) }
                    BigButton(stringResource(R.string.grp_share)) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.grp_join_text, gr.code)), ctx.getString(R.string.grp_invite_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Spacer(Modifier.width(6.dp))
                    BigButton(stringResource(R.string.grp_leave), color = K.Danger) { g.socket.leaveGroup(gr.id); scope.launch { runCatching { g.api.leaveGroup(gr.id) }; active = null; refresh() } }
                }
                val here = members.values.filter { it.groupId == gr.id }
                Text(stringResource(R.string.grp_riding_now, here.size), color = p.text)
                here.forEach { m -> Text("• ${m.displayName}${m.vehicleName?.let { " ($it)" } ?: ""} – ${m.speedKmh?.let { "%.0f km/h".format(it) } ?: "—"}${m.socPct?.let { " · %.0f%%".format(it) } ?: ""}", color = p.textDim) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(stringResource(R.string.grp_invite_user), inviteUser, { inviteUser = it }, Modifier.weight(1f))
                    BigButton(stringResource(R.string.grp_invite)) { scope.launch { runCatching { g.api.invite(gr.id, inviteUser) }.onSuccess { g.toast(ctx.getString(R.string.grp_invited)); inviteUser = "" }.onFailure { g.toast(it.message ?: "") } } }
                }
                SectionTitle(stringResource(R.string.grp_chat))
                chat.takeLast(20).forEach { c -> Text("${c.from}: ${c.text}", color = if (c.help) K.Danger else p.text, modifier = if (c.voiceFile != null) Modifier else Modifier) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(stringResource(R.string.grp_message), text, { text = it.take(1000) }, Modifier.weight(1f))
                    BigButton(stringResource(R.string.grp_send)) { if (text.isNotBlank()) { g.socket.send(buildJsonObject { put("type", "chat"); put("groupId", gr.id); put("text", text) }); chat += ChatLine("me", text, System.currentTimeMillis()); text = "" } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigButton(stringResource(if (recorder == null) R.string.grp_rec_voice else R.string.grp_send_voice), Modifier.weight(1f), selected = recorder != null) {
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
                                chat += ChatLine("me", ctx.getString(R.string.grp_voice, d / 1000.0), System.currentTimeMillis(), f)
                            }
                        }
                    }
                    BigButton(stringResource(R.string.grp_help), Modifier.weight(1f), color = K.Danger) {
                        val f = g.gps.fix.value
                        if (f == null) g.toast(ctx.getString(R.string.grp_no_fix)) else g.socket.send(buildJsonObject { put("type", "help_request"); put("groupId", gr.id); put("lat", f.lat); put("lon", f.lon); put("message", "Need assistance") })
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.grp_my))
        groups.forEach { gr -> Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${gr.name} (${gr.code})", color = p.text, modifier = Modifier.weight(1f)); BigButton(stringResource(if (active?.id == gr.id) R.string.grp_active else R.string.grp_ride), selected = active?.id == gr.id) { enter(gr) } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.grp_code), code, { code = it.uppercase().take(8) }, Modifier.weight(1f))
            BigButton(stringResource(R.string.grp_join)) { scope.launch { runCatching { g.api.joinGroup(code) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.grp_new_name), newName, { newName = it.take(40) }, Modifier.weight(1f))
            BigButton(stringResource(R.string.grp_private)) { scope.launch { runCatching { g.api.createGroup(newName, false) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
            Spacer(Modifier.width(4.dp))
            BigButton(stringResource(R.string.grp_public)) { scope.launch { runCatching { g.api.createGroup(newName, true) }.onSuccess { enter(it); refresh() }.onFailure { g.toast(it.message ?: "") } } }
        }
        if (invitations.isNotEmpty()) { SectionTitle(stringResource(R.string.grp_invitations)); invitations.forEach { inv -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.grp_inv_from, inv.group.name, inv.from?.displayName ?: "?"), color = p.text, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.grp_accept)) { scope.launch { runCatching { g.api.acceptInvitation(inv.id) }.onSuccess { enter(it); refresh() } } } } } }
        SectionTitle(stringResource(R.string.grp_public_groups))
        publicGroups.forEach { gr -> Row(verticalAlignment = Alignment.CenterVertically) { Text(gr.name, color = p.text, modifier = Modifier.weight(1f)); BigButton(stringResource(R.string.grp_join)) { scope.launch { runCatching { g.api.joinGroup(gr.code) }.onSuccess { enter(it); refresh() } } } } }
        SectionTitle(stringResource(R.string.grp_friends))
        friends.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${f.displayName} (@${f.username})", color = p.text, modifier = Modifier.weight(1f)); Pill(stringResource(if (f.online) R.string.grp_online else R.string.grp_offline), if (f.online) K.Regen else p.textDim) } }
        requests.filter { it.status == "pending" && it.from?.username != settings.username }.forEach { r -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.grp_req_from, r.from?.displayName ?: ""), color = p.text, modifier = Modifier.weight(1f)); BigButton(stringResource(R.string.grp_accept)) { scope.launch { runCatching { g.api.acceptFriend(r.id) }; refresh() } } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(stringResource(R.string.grp_add_friend), friendUser, { friendUser = it }, Modifier.weight(1f))
            BigButton(stringResource(R.string.grp_add)) { scope.launch { runCatching { g.api.requestFriend(friendUser) }.onSuccess { g.toast(ctx.getString(R.string.grp_req_sent)); friendUser = "" }.onFailure { g.toast(it.message ?: "") } } }
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
        Text(stringResource(R.string.stark_title), style = labelStyle(12), color = K.Warn)
        Text(stringResource(R.string.stark_text1_v2), color = p.textDim, style = labelStyle(10))
        Text(stringResource(R.string.stark_text2), color = p.textDim, style = labelStyle(10))
        BigButton(stringResource(R.string.stark_open), Modifier.fillMaxWidth().padding(top = 6.dp)) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Stark%20Future")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=Stark%20Future")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/**
 * VargLink (independent third-party app, not by Stark) lets guests WITHOUT a Stark bike join a Stark owner's group ride
 * via invite link – in the VargLink app or in the browser. There is no public API, so Kinetic OS only opens the
 * official invite link / app; it does not imitate VargLink's protocol.
 */
@Composable
fun VargLinkCard() {
    val ctx = LocalContext.current; val p = LocalPalette.current
    var link by remember { mutableStateOf("") }
    val installed = remember { runCatching { ctx.packageManager.getPackageInfo("ru.varg.link", 0); true }.getOrDefault(false) }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.varglink_title), style = labelStyle(12), color = K.Accent)
        Text(stringResource(R.string.varglink_text), color = p.textDim, style = labelStyle(10))
        Field(stringResource(R.string.varglink_link_hint), link, { link = it.trim() }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(stringResource(R.string.varglink_join), Modifier.weight(1f), enabled = link.startsWith("https://") || link.startsWith("http://")) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (installed) i.setPackage("ru.varg.link")
                runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            BigButton(stringResource(if (installed) R.string.varglink_open else R.string.varglink_install), Modifier.weight(1f)) {
                val launch = ctx.packageManager.getLaunchIntentForPackage("ru.varg.link")
                if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=ru.varg.link")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=ru.varg.link")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        Text(stringResource(R.string.varglink_note), color = p.textDim, style = labelStyle(9))
    }
}
