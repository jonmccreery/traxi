package thru.taxi.traxi.ui

import thru.taxi.traxi.AppContainer
import thru.taxi.traxi.core.format.Bytes
import thru.taxi.traxi.core.format.LogFormat
import thru.taxi.traxi.core.format.RecordingAudit
import thru.taxi.traxi.core.format.MarkProof
import thru.taxi.traxi.core.format.RecordingSince
import thru.taxi.traxi.core.protocol.FixType
import thru.taxi.traxi.core.protocol.FlashEraser
import thru.taxi.traxi.bt.Bonding
import thru.taxi.traxi.core.protocol.Pmtk
import thru.taxi.traxi.core.protocol.SatelliteView
import thru.taxi.traxi.core.protocol.Telemetry
import thru.taxi.traxi.data.DumpRepository
import thru.taxi.traxi.service.DownloadService
import thru.taxi.traxi.session.ConnectionState
import thru.taxi.traxi.session.DeviceInfo
import thru.taxi.traxi.session.ParseSummary
import thru.taxi.traxi.ui.theme.MonoStyle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import thru.taxi.traxi.session.LinkHealth
import thru.taxi.traxi.session.LiveActivity
import thru.taxi.traxi.session.RecordingActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    DEVICE("Device"), LIVE("Live"), DUMPS("Dumps"), CONFIG("Config"), LOG("Log")
}

/**
 * How recently a fix must have arrived for the receiver to count as fixed.
 *
 * Fixes land at ~1 Hz, so fifteen seconds is many missed epochs -- long enough
 * not to flicker on a single dropped sentence, short enough that walking indoors
 * is reflected before the recording indicator draws a conclusion from it.
 */
private const val FIX_RECENT_NANOS = 15_000_000_000L

/**
 * Age of a reading, in words.
 *
 * The recording indicator states how old its fact is rather than implying it is
 * live. Over Bluetooth the write pointer is sampled rarely -- asking this logger
 * too often takes the link down -- so "checked 6 minutes ago" is the honest
 * shape of the answer, and pretending to a live feed is what the previous
 * clock-decayed rule got wrong.
 */
private fun describeAgo(seconds: Double): String = when {
    seconds < 45 -> "just now"
    seconds < 90 -> "a minute ago"
    seconds < 3600 -> "${(seconds / 60).toInt()} minutes ago"
    else -> "over an hour ago"
}

/**
 * A span, for the gaps between sessions.
 *
 * [describeAgo] tops out at "over an hour ago", which is right for the age of a
 * probe and useless for a span that is routinely a working day. These are the
 * two numbers the user compares against each other, so they are spelled out
 * rather than rounded into a phrase.
 */
private fun describeDuration(seconds: Long): String {
    if (seconds < 60) return "$seconds s"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    if (hours < 24) return if (remainder == 0L) "$hours h" else "$hours h $remainder min"
    val days = hours / 24
    val leftoverHours = hours % 24
    return if (leftoverHours == 0L) "$days d" else "$days d $leftoverHours h"
}

/**
 * The trailhead proof: make the logger record something, and watch it land.
 *
 * Deliberately a ceremony rather than a readout. Everything else on this screen
 * is the app telling the user what it believes; this is the user doing a
 * physical thing to their own device and being shown the consequence, which is
 * the only form of assurance that survives being disbelieved.
 *
 * Two queries, both behind taps. See [MarkProof] for why this must never poll.
 */
@Composable
private fun ProveRecordingCard(
    session: thru.taxi.traxi.session.SessionController,
    proof: thru.taxi.traxi.session.MarkProofState,
) {
    SectionCard("Prove it is recording") {
        val result = proof.result
        when {
            result != null -> {
                when (result) {
                    is MarkProof.Result.Proven -> {
                        Text(
                            "RECORDING — PROVEN",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "%,d record%s (%s) reached flash between the two readings."
                                .format(
                                    result.records,
                                    if (result.records == 1L) "" else "s",
                                    Bytes.describe(result.bytes),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Spelled out because each clause is a separate failure
                        // this project has actually had, and no other single
                        // check rules out all four.
                        Text(
                            "That rules out all of it at once: the slide switch is on LOG, " +
                                "logging is enabled in firmware, the receiver has a position, " +
                                "and bytes are reaching the flash. Safe to set off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The run doubles as a calibration of the logger's own
                        // beep, which is the only thing on the trail that works
                        // without the phone. One run cannot establish the
                        // implication on its own -- a beep that fires on every
                        // press is worth nothing -- so this says what it has
                        // shown and points at the run that would settle it.
                        Text(
                            "If the logger beeped when you pressed it, this run has tied that " +
                                "beep to a real write once. To trust it without the phone, run " +
                                "the check again with Pause logging on: if it still beeps, the " +
                                "beep only means the press registered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is MarkProof.Result.NothingWritten -> {
                        Text(
                            "NOTHING WAS WRITTEN",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "You asked the logger to record a point, it had a position to " +
                                "record, and the write pointer did not move. It is not " +
                                "recording. Do not set off on this.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Check the slide switch is on LOG rather than NAV — no command " +
                                "overrides the switch. If it is already on LOG, use Resume " +
                                "logging on the Config tab and run this again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // A failed run is the most informative calibration
                        // there is, and it costs nothing to point out: the beep
                        // just fired over a write that did not happen.
                        Text(
                            "If the logger beeped anyway, that beep only means the press " +
                                "registered — not that anything was recorded. Do not use it " +
                                "as a recording check on the trail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    is MarkProof.Result.Inconclusive -> {
                        Text(
                            "Not proven either way",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // Never styled as a fault. Most of these are a logger
                        // indoors, which is the healthiest possible reason for
                        // a pointer not to move.
                        Text(
                            "${result.reason.replaceFirstChar { it.uppercase() }}. " +
                                "This says nothing about whether the logger is recording.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Button(
                    onClick = { session.clearMarkProof() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Run it again") }
            }

            proof.awaitingPress -> {
                Text(
                    "Press the mark button on the logger now",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "It writes one point immediately rather than waiting for the next " +
                        "interval. Then tap below and the app will look for it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Note whether it beeps, and whether an LED flashes. This run will tell " +
                        "you what that beep is actually worth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { session.confirmMarkProof() },
                    enabled = !proof.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (proof.busy) "Checking…" else "I pressed it — check") }
                OutlinedButton(
                    onClick = { session.clearMarkProof() },
                    enabled = !proof.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }

            else -> {
                Text(
                    "The check below is the only one that settles every question at " +
                        "once, and it takes a couple of seconds instead of a couple of " +
                        "log intervals. Do it at the trailhead, before you set off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { session.beginMarkProof() },
                    enabled = !proof.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (proof.busy) "Reading the pointer…" else "Start the check") }
            }
        }
    }
}

/**
 * What the logger wrote while the app was not connected.
 *
 * Placed first on the Device tab, above everything the current session can
 * measure, because it is the only card here that covers the ride. Everything
 * below it describes the minutes since connecting, and the minutes since
 * connecting are never when the logger is doing its job.
 */
@Composable
private fun SinceLastSeenCard(verdict: RecordingSince.Verdict) {
    SectionCard("Since the app last looked") {
        when (verdict) {
            is RecordingSince.Verdict.NothingToCompare -> Text(
                "This is the first reading of this logger's write pointer. From the " +
                    "next connect on, this card reports how much the logger wrote " +
                    "while the app was away — which is the part of the day that " +
                    "nothing else here can see.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is RecordingSince.Verdict.Unusable -> Text(
                "No comparison this time: ${verdict.reason}.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Stated plainly and **not** in error colour, on purpose. A pointer
            // that has not moved is exactly correct for a logger that was
            // switched off, or indoors without a fix, and connecting twice in
            // an evening would otherwise light this red every time. The one
            // reading the user is asked to trust cannot also be the one that
            // cries wolf -- so this gives them the fact and the two readings of
            // it, benign one first, and lets them apply the thing only they
            // know: whether they were out using it.
            is RecordingSince.Verdict.WroteNothing -> {
                Text(
                    "Nothing was written",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "The write pointer is exactly where it was " +
                        "${describeDuration(verdict.elapsedSeconds)} ago. If the logger " +
                        "was switched off, or indoors without a fix, that is exactly " +
                        "right. If it was on and outdoors in that time, none of it " +
                        "was recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "If it should have been recording: check the slide switch is on " +
                        "LOG rather than NAV, then use Resume logging on the Config tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is RecordingSince.Verdict.Wrote -> {
                Text(
                    "%,d fixes recorded".format(verdict.fixes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${describeDuration(verdict.recordedSeconds)} of recording, " +
                        "${Bytes.describe(verdict.bytes)}, in the " +
                        "${describeDuration(verdict.elapsedSeconds)} since the app last " +
                        "read the pointer. This is the logger's own counter, measured " +
                        "across the whole gap — not a sample taken while connected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The gap counts every hour since the last connect, including
                // the ones the logger spent in a drawer, so recorded time is
                // *expected* to be less than elapsed time and a coverage
                // percentage here would be a fake verdict. Only the user knows
                // how long it was switched on, so only the user can close this
                // comparison -- the app's job is to hand them both numbers.
                Text(
                    "Compare that against how long the logger was actually switched " +
                        "on. The gap counts every hour since the last connect, including " +
                        "any it spent off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    container: AppContainer,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onPairDevice: () -> Unit,
) {
    val session = container.session
    val connection by session.connection.collectAsState()
    val download by session.download.collectAsState()
    val summary by session.summary.collectAsState()
    val message by session.message.collectAsState()
    val telemetry by session.telemetry.collectAsState()
    val liveActivity by session.liveActivity.collectAsState()
    val linkHealth by session.linkHealth.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            session.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Traxi") },
                actions = { ConnectionChip(connection, session.isSimulated) },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!permissionsGranted) {
                PermissionCard(onRequestPermissions)
            }

            when (tab) {
                Tab.DEVICE -> DeviceTab(container, connection, liveActivity, onPairDevice)
                Tab.LIVE -> LiveTab(connection, telemetry, liveActivity, linkHealth)
                Tab.DUMPS -> DumpsTab(container, connection, download, summary)
                Tab.CONFIG -> ConfigTab(container, connection)
                Tab.LOG -> LogTab(container)
            }
        }
    }
}

// ---------------- shared pieces ----------------

@Composable
private fun ConnectionChip(state: ConnectionState, simulated: Boolean) {
    val (label, colour) = when (state) {
        is ConnectionState.Connected ->
            (if (simulated) "Simulated" else "Connected") to MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting -> "Connecting" to MaterialTheme.colorScheme.secondary
        is ConnectionState.Failed -> "Failed" to MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> "Offline" to MaterialTheme.colorScheme.outline
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = colour),
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Row2(label: String, value: String, mono: Boolean = true) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Speaks only when the phone is still allowed to suspend this app.
 *
 * A foreground service keeps the process alive but does not, on this phone,
 * exempt it from battery optimisation -- and it was the optimiser that ended
 * every Bluetooth session on the 7 September ride at the ten-minute mark, by
 * suspending the read loop while leaving the radio link up. Silent when the
 * exemption is held, like every other warning in this app.
 */
@Composable
private fun BatteryExemptionCard() {
    val context = LocalContext.current
    // Polled rather than observed: the grant happens in a system screen, so the
    // card has to notice a change made outside the app. Two seconds is
    // imperceptible on return and costs nothing.
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            exempt = isIgnoringBatteryOptimizations(context)
            delay(2000)
        }
    }
    if (exempt) return

    SectionCard("Android may suspend this app") {
        Text(
            "Battery optimisation is still on for traxi. With it on, the phone " +
                "suspends the app a few minutes after the screen goes off, which " +
                "stalls the Bluetooth link without disconnecting it — the link looks " +
                "alive while nothing arrives.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "The logger keeps recording either way; this only affects what the phone " +
                "can see and download.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings
                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow traxi to run in the background") }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bluetooth permission needed", style = MaterialTheme.typography.titleMedium)
            Text(
                "Traxi needs Bluetooth access to reach the logger. It does not " +
                    "request location, and does not use Bluetooth to determine where you are.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onRequest) { Text("Grant") }
        }
    }
}

// ---------------- device ----------------

@Composable
private fun DeviceTab(
    container: AppContainer,
    connection: ConnectionState,
    liveActivity: LiveActivity,
    onPairDevice: () -> Unit,
) {
    val session = container.session
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val known = remember { container.pairing.associatedAddresses() }
    val bonded = remember { runCatching { container.pairing.bondedDevices() }.getOrDefault(emptyList()) }
    // Hoisted: the failure card needs it to decide whether Bluetooth or USB
    // advice is the relevant advice.
    val usbDevices = remember { container.usb.candidates() }

    val recording by session.recording.collectAsState()
    val sinceLastSeen by session.sinceLastSeen.collectAsState()
    val markProof by session.markProof.collectAsState()
    val staleAddress by session.stalePairingSuspected.collectAsState()

    when (connection) {
        is ConnectionState.Connected -> {
            sinceLastSeen?.let { SinceLastSeenCard(it) }
            DeviceInfoCard(connection.info, session.isSimulated, recording, liveActivity)
            if (!session.isSimulated) ProveRecordingCard(session, markProof)
            BatteryExemptionCard()
            OutlinedButton(
                onClick = { session.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect") }
        }

        is ConnectionState.Connecting -> SectionCard("Connecting") {
            Text(connection.target, style = MonoStyle)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        else -> {
            if (connection is ConnectionState.Failed) {
                SectionCard("Connection failed") {
                    Text(connection.message, style = MonoStyle)
                    // Advice has to match the transport that actually failed.
                    // Showing Bluetooth pairing guidance after a USB failure
                    // sends the reader somewhere the fault is not.
                    if (connection.message.contains("usb", ignoreCase = true) ||
                        usbDevices.isNotEmpty()
                    ) {
                        Text(
                            "Check the cable and that the logger is powered on. USB needs " +
                                "no pairing, so this is a cable, power or protocol problem " +
                                "rather than a permissions one.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "The logger must be powered on and already paired in Android's " +
                                "Bluetooth settings. Classic Bluetooth has no " +
                                "unpaired-connect path.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Offered, never done unnoticed. Clearing a pairing
                        // needs the user back at the device with the PIN, so it
                        // is their call -- and they know things the app cannot,
                        // like whether they just power-cycled it.
                        staleAddress?.let { address ->
                            Text(
                                "If the logger is powered on and in range, the pairing may " +
                                    "have gone stale — this logger forgets its key when it " +
                                    "loses power, while the phone keeps hers. Re-pairing " +
                                    "fixes that, and will ask for PIN ${Bonding.KNOWN_PIN}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = { session.clearPairingAndReconnect(address) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Clear pairing and re-pair") }
                        }
                    }
                }
            }

            // USB first when a cable is present: 64 KB/s against Bluetooth's
            // 493 B/s means a full read takes 83 s rather than three hours.
            if (usbDevices.isNotEmpty()) {
                SectionCard("Connected by cable") {
                    Text(
                        "USB is about 65× faster than Bluetooth on this logger — a full " +
                            "flash read takes under two minutes instead of three hours — " +
                            "and needs no pairing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    usbDevices.forEach { usbDevice ->
                        val label = usbDevice.productName ?: usbDevice.deviceName
                        Button(
                            onClick = {
                                container.usb.request(usbDevice) { granted ->
                                    if (granted) {
                                        container.usb.manager()?.let { m ->
                                            session.connectUsb(m, usbDevice)
                                        }
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "USB permission declined",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect over USB  ·  $label") }
                    }
                }
            }

            SectionCard("Connect") {
                Text(
                    "Pick the logger through Android's own device chooser. The choice " +
                        "is remembered, so later trips are one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The logger must be powered on to appear — it does not advertise " +
                        "otherwise. If asked for a PIN, it is " +
                        "${thru.taxi.traxi.bt.Bonding.KNOWN_PIN}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onPairDevice, Modifier.fillMaxWidth()) { Text("Pair a logger") }

                // Devices this app has been associated with, plus any bonded
                // device whose name looks like a logger. Everything else is a
                // speaker or a car, and burying the one useful entry among
                // eight of those is how you connect to the wrong thing.
                val likelyAddresses = remember(known, bonded) {
                    (known + container.pairing.likelyLoggers().map { it.address }).distinct()
                }
                val others = remember(likelyAddresses, bonded) {
                    bonded.map { it.address }.filterNot { it in likelyAddresses }
                }
                var showAll by rememberSaveable { mutableStateOf(false) }

                fun nameFor(address: String): String? = bonded
                    .firstOrNull { it.address.equals(address, ignoreCase = true) }
                    ?.let { runCatching { it.name }.getOrNull() }

                if (likelyAddresses.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Likely loggers", style = MaterialTheme.typography.labelSmall)
                    likelyAddresses.forEach { address ->
                        DeviceButton(nameFor(address), address) { session.connect(address) }
                    }
                }

                if (others.isNotEmpty()) {
                    HorizontalDivider()
                    TextButton({ showAll = !showAll }) {
                        Text(
                            if (showAll) "Hide other paired devices"
                            else "Show all paired devices (${others.size})"
                        )
                    }
                    if (showAll) {
                        Text(
                            "Most of these are speakers or headphones. They accept a " +
                                "connection and then fail the same way a dead logger " +
                                "does, because they do not speak Serial Port Profile.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        others.forEach { address ->
                            DeviceButton(nameFor(address), address) { session.connect(address) }
                        }
                    }
                }
            }

            SectionCard("Work without the logger") {
                Text(
                    "Run the app against a flash image already on this phone. Everything " +
                        "except the radio behaves identically, so a dump can be re-parsed " +
                        "and re-exported with the device in a drawer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val existing = container.dumpRepository.list().firstOrNull()
                            if (existing == null) {
                                android.widget.Toast.makeText(
                                    context,
                                    "No dump on this phone yet",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                session.connectSimulated(
                                    container.dumpRepository.read(existing.file)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Simulate from a saved dump") }
            }
        }
    }
}

@Composable
private fun DeviceButton(name: String?, address: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (name != null) "$name  ·  $address" else address,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceInfoCard(
    info: DeviceInfo,
    simulated: Boolean,
    recording: RecordingActivity,
    activity: LiveActivity,
) {
    SectionCard(if (simulated) "Simulated logger" else "Logger") {
        Text(info.displayModel, style = MaterialTheme.typography.titleLarge)
        Row2("Transport", info.transportDescription)
        Row2("Firmware", info.firmware)
        Row2("Log format", "0x%08X".format(info.logFormat.bits))
        Row2("Fields", info.logFormat.describe(), mono = true)
        Row2("Interval", "${info.timeIntervalSeconds} s")

        // The one status the user must not have to interpret: is it recording?
        // Decided by the write pointer actually advancing -- bytes landing in
        // flash -- not by the status bit, which this device misreports right
        // after a reconnect. The bit is shown below as a secondary detail.
        val reported = Pmtk.LogStatus.parse(info.logStatus)
        if (simulated) {
            Row2("Status", reported?.describe() ?: info.logStatus)
        } else {
            var now by remember { mutableStateOf(System.nanoTime()) }
            LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(1_000) } }

            // Driven by what the last sample saw, never by how long ago it was
            // taken. Over Bluetooth the pointer is now read rarely, because
            // asking destabilises the link, so a clock-driven rule spends most
            // of its life stale -- and decayed into shouting NOT RECORDING at a
            // logger that was recording perfectly. A false alarm here is worse
            // than no alarm: it is the one reading the user is asked to trust.
            val looked = recording.lastProbeAtNanos != 0L
            // A no-movement verdict only means something across a window longer
            // than the log interval; below that, a healthy logger has genuinely
            // written nothing yet.
            val conclusive = recording.lastProbeGapNanos >
                (info.timeIntervalSeconds * 1.5 * 1e9).toLong()
            val checkedAgo = (now - recording.lastProbeAtNanos) / 1e9
            // A frozen pointer means nothing without knowing whether the
            // receiver had anything to write. Indoors this logger holds no fix
            // for hours and correctly records nothing; calling that "not
            // recording" is a false alarm on the one reading that must never
            // cry wolf.
            val hasFix = activity.lastFixAtNanos != 0L &&
                (now - activity.lastFixAtNanos) < FIX_RECENT_NANOS
            when {
                // Says how long the wait is rather than leaving it open-ended:
                // the pre-flight probe lands about two log intervals after
                // connecting, and a silent "checking…" that might mean anything
                // is its own small alarm.
                !looked || (!recording.lastProbeAdvanced && !conclusive) ->
                    Row2(
                        "Recording",
                        "checking… (about %.0f s)".format(
                            (info.timeIntervalSeconds * 2).coerceAtLeast(10.0)),
                    )

                recording.lastProbeAdvanced -> {
                    Text(
                        "RECORDING",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${Bytes.describe(recording.bytesSinceConnect)} written to flash " +
                            "since connecting. This is the live write pointer, not a guess.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // The age of the fact, stated rather than implied. The
                    // reading is a checked observation, not a live feed, and
                    // pretending otherwise is what the old rule did wrong.
                    Text(
                        "Last checked ${describeAgo(checkedAgo)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Nothing was written, and nothing was available to write. Not
                // a fault, and deliberately not styled as one.
                !hasFix -> {
                    Row2("Recording", "no fix — nothing to record")
                    Text(
                        "The receiver has no position, so there is nothing for the " +
                            "logger to write. This is normal indoors and is not a " +
                            "fault; recording resumes on its own once it sees the sky.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        "NOT RECORDING",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "There is a good fix and the write pointer still has not " +
                            "moved — checked ${describeAgo(checkedAgo)}, after " +
                            "%.0f s of watching. Fixes are being lost.".format(
                                recording.lastProbeGapNanos / 1e9),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // The switch comes first because it is the likelier cause
                    // and the only one the app cannot fix. Measured 2026-09-08:
                    // in NAV the logger accepts an enable and reports 0x0102
                    // while writing nothing at all, so no software setting --
                    // and no reassuring status word -- overrides the slider.
                    Text(
                        "Check the slide switch on the logger is set to LOG, not NAV. " +
                            "In NAV it navigates but deliberately does not record, and " +
                            "the app cannot override that — it will even report " +
                            "\"logging\" while writing nothing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "If the switch is already on LOG, logging has been disabled in " +
                            "firmware: use Resume logging on the Config tab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // The device's own status bit, kept as detail and never as the
            // verdict. It is not merely unreliable, it is capable of stating
            // the opposite of the truth: with the switch in NAV it reports
            // 0x0102 "logging" while the write pointer never moves. Only the
            // pointer above says whether anything is being recorded.
            // The raw logging bit is **not drawn here**, and its absence is the
            // fix rather than an omission.
            //
            // This card answers one question in the present tense -- is it
            // recording -- and every other line in it is live: the pointer is
            // probed on a timer and states its own age. The status word is read
            // once at connect and after a config write, never again, so beside
            // them it reads as a live claim and is not one. On 2026-09-21 it sat
            // saying "NOT logging" over a climbing byte count for a whole
            // session, because it had been captured in the one second before the
            // receiver reacquired.
            //
            // Dating it was the first attempt and it was the wrong instinct:
            // an undated line is not ambiguous about time, it asserts *now*, and
            // annotating a false claim is not the same as removing it. Nor is
            // the value worth the space once dated -- §16.14 concludes it cannot
            // condemn a logger or clear one, so on the card whose whole job is
            // that verdict it can only compete with the reading that can.
            //
            // It stays on the Config tab, dated, where it belongs: a settings
            // readout on the screen you use to change settings. What survives
            // here is the part that is genuinely actionable and does not flicker
            // with fix state -- the standing faults below, and the NAV warning,
            // both of which require a measurement before they say anything.
            reported?.let {
                // Logging reported off, and **only** said out loud once a
                // measurement agrees. The guard is the same one the NAV
                // warning below uses, and it is here because an unguarded
                // version shipped and cried wolf on the hardware within two
                // days: it fired at 07:19:31 on 2026-09-21 against a logger
                // that was writing at full rate twenty seconds later.
                //
                // Every `0x0100` this project has ever recorded -- five of
                // them, 2026-09-07 through 2026-09-21 -- was answered while
                // GPRMC read void. On the last one the fix arrived one second
                // after the reading and the pointer climbed at 288 bytes a
                // minute with no host command at all, so nothing had been
                // disabled. The word goes clear when the receiver has nothing
                // to log, which is the state every cold connect is in.
                //
                // §15.2 said `0x0100` is ambiguous and always will be, and it
                // was right; §16.11 overturned that twice and was wrong twice.
                // The pointer outranks the word. See §16.14.
                if (!it.isLoggingEnabled && looked && conclusive &&
                    !recording.lastProbeAdvanced && hasFix
                ) {
                    Text(
                        "LOGGING IS SWITCHED OFF",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "The logger reports logging disabled, there is a good fix, and " +
                            "nothing is reaching flash. Use Resume logging on the Config tab.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "If the slide switch is in NAV, that reads the same way and no " +
                            "command will override it; move it to LOG.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A standing fault is reported without waiting for a probe,
                // and deliberately so: everything else in this card is an
                // inference we have to earn with a measurement, but this is the
                // device asserting a condition about itself. It also has to
                // outrank the branches above -- with the flash needing a format
                // the pointer is frozen, and on a cold indoor connect that
                // renders as the reassuring "no fix — nothing to record" while
                // recording is in fact over until someone intervenes.
                it.faults.forEach { fault ->
                    Text(
                        "The logger reports that $fault — recording will not " +
                            "resume by itself. See §13 in the engineering record.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // `looked && conclusive` is the whole guard, and leaving it out
                // was a bug: lastProbeAdvanced is false before any probe has
                // run, so connecting to a healthy logger in LOG mode fired this
                // in red immediately, on no evidence whatsoever. An accusation
                // needs a measurement behind it, not the absence of one.
                if (it.isLoggingEnabled && looked && conclusive &&
                    !recording.lastProbeAdvanced && hasFix
                ) {
                    Text(
                        "The logger reports logging, but nothing is reaching flash — " +
                            "the switch is almost certainly in NAV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        info.flash?.let { Row2("Flash", it.describe()) }
        info.writePointer?.let { Row2("Written", Bytes.describe(it)) }
        info.flashUsedFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${Bytes.describePercent(fraction)} of flash used. This is a hint only — " +
                    "in overlap mode a full log wraps and overwrites the oldest data, " +
                    "so a download always reads the whole chip.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

    }
}

// ---------------- dumps ----------------

// ---------------- live ----------------

/**
 * What the receiver is doing right now.
 *
 * All of this was already on the wire — the logger emits navigation sentences
 * continuously alongside its PMTK replies, and until now they were parsed,
 * checksummed and thrown away. Nothing here costs an extra request.
 */
@Composable
private fun LiveTab(
    connection: ConnectionState,
    telemetry: Telemetry?,
    activity: LiveActivity,
    linkHealth: LinkHealth,
) {
    if (connection !is ConnectionState.Connected) {
        SectionCard("Not connected") {
            Text(
                "Live telemetry comes from the logger itself, so it needs an open " +
                    "connection. Connect on the Device tab.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    // Before anything about fixes: is the link delivering bytes at all? A
    // stalled stream used to be invisible -- every counter simply stopped, and
    // the app went on claiming a healthy connection.
    LinkHealthCard(linkHealth)

    // The heartbeat leads: whether data is flowing is the first thing to know,
    // ahead of what the data says. It also guards the section 12 failure -- a
    // stalled stream is visible here rather than looking like a frozen fix.
    LiveHeartbeat(activity)

    if (telemetry == null || !telemetry.hasAnything) return

    FixCard(telemetry)
    if (telemetry.hasPosition) PositionCard(telemetry)
    if (telemetry.satellites.isNotEmpty()) SkyCard(telemetry)
}

/**
 * Says when the link has gone quiet, and refuses to imply it has not.
 *
 * Silence here is measured in *bytes*, not fixes. The logger streams NMEA
 * continuously whenever it is powered and connected, so no bytes at all means
 * the link has stopped working -- unlike a missing fix, which indoors means
 * only that the sky is not visible.
 *
 * Nothing on this card acts on its own. It reports, and offers the reconnect
 * that is known to fix a stall; tearing the session down automatically is a
 * mistake this app has already made once.
 */
@Composable
private fun LinkHealthCard(health: LinkHealth) {
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.nanoTime(); delay(500) }
    }

    val ended = health.streamEnded
    if (ended != null) {
        SectionCard("Stream ended") {
            Text(
                ended,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "The link is no longer delivering data. The logger keeps recording on " +
                    "its own regardless — nothing is being lost on the device. " +
                    "Disconnect and connect again to resume.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    if (health.lastBytesAtNanos == 0L) return
    val silentSec = ((now - health.lastBytesAtNanos) / 1e9).coerceAtLeast(0.0)
    // The stream is continuous, so a few seconds of nothing is already odd;
    // ten is unambiguous while staying clear of ordinary RFCOMM burstiness.
    if (silentSec < 10) return

    SectionCard("Link silent") {
        Text(
            "%.0f s since the last byte".format(silentSec),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "The connection is still open but nothing is arriving. The logger is " +
                "still recording — this is the phone's side of the link. If it does " +
                "not recover, disconnect and connect again.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LiveHeartbeat(activity: LiveActivity) {
    // Advance a local clock so "X s ago" keeps counting up even when no fix
    // arrives -- that climbing number is exactly how a stall becomes visible.
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.nanoTime(); delay(500) }
    }

    // Blink the dot on each fix.
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(activity.pulse) { blink = true; delay(150); blink = false }
    val alpha by animateFloatAsState(if (blink) 1f else 0.3f, label = "pulse")

    val hasFix = activity.lastFixAtNanos != 0L
    // Clamp: a fix can land just after the 'now' tick was sampled, which would
    // otherwise render a nonsensical "-0.0 s ago".
    val ageSec = if (hasFix) ((now - activity.lastFixAtNanos) / 1e9).coerceAtLeast(0.0)
        else Double.NaN
    // A fix is ~1 Hz, so a few seconds of silence means fixes have actually
    // stopped -- not just jitter.
    val stale = hasFix && ageSec > 4.0
    val accent = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    SectionCard(if (stale) "No fix" else "Fixing") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (stale) 1f else alpha))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !hasFix -> "waiting for the first fix"
                    stale -> "no fix for %.0f s".format(ageSec)
                    else -> "last fix %.1f s ago".format(ageSec)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (stale) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (activity.fixesPerSecond > 0) {
            Row2("Fixes", "~%.1f per second".format(activity.fixesPerSecond))
        }
    }
}

@Composable
private fun FixCard(t: Telemetry) {
    SectionCard("Fix") {
        val fix = t.fixType
        val headline = when {
            t.valid == false -> "No fix"
            fix == FixType.THREE_D -> "3D fix"
            fix == FixType.TWO_D -> "2D fix"
            fix == FixType.NONE -> "No fix"
            else -> "Unknown"
        }
        // A 2D fix is worth calling out rather than colouring green: it means
        // altitude is not being solved for, which on a mountain is the number
        // you probably came for.
        val good = fix == FixType.THREE_D && t.valid != false
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            color = if (good) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )

        t.satellitesUsed?.let {
            Row2("Satellites", "$it in the solution, ${t.satellites.size} in view")
        }
        if (t.satellites.isNotEmpty()) {
            Row2("Tracked", "${t.trackedCount} of ${t.satellites.size}")
        }
        t.hdop?.let { Row2("HDOP", "%.2f  (%s)".format(it, dopQuality(it))) }
        if (t.pdop != null || t.vdop != null) {
            Row2("PDOP / VDOP", "%s / %s".format(fmt(t.pdop, 2), fmt(t.vdop, 2)))
        }
        t.fixQuality?.let {
            Row2(
                "Quality", when (it) {
                    0 -> "0 — no fix"
                    1 -> "1 — GPS"
                    2 -> "2 — differential"
                    else -> it.toString()
                }
            )
        }

        // The one thing this screen must not be allowed to imply. A perfect fix
        // and a logger that stopped recording look identical from here; that is
        // exactly the section 12 failure, and it cost 1h40m of trail once.
        Text(
            "This is the receiver, not the recorder. A good fix does not mean the " +
                "logger is writing it down — the Device tab shows recording state.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PositionCard(t: Telemetry) {
    SectionCard("Position") {
        val lat = t.latitude!!
        val lon = t.longitude!!
        Row2("Latitude", "%.5f°  %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S"))
        Row2("Longitude", "%.5f°  %s".format(kotlin.math.abs(lon), if (lon >= 0) "E" else "W"))
        t.altitudeMeters?.let {
            Row2("Altitude", "%.1f m  ·  %.0f ft".format(it, it * 3.28084))
        }
        t.speedKnots?.let {
            Row2("Speed", "%.1f km/h  ·  %.1f kn".format(it * 1.852, it))
        }
        t.courseDeg?.let { Row2("Course", "%.0f°".format(it)) }
        t.utcMillis?.let { Row2("UTC", utcStamp(it)) }
        Text(
            "Decimal degrees, WGS 84 — the same datum the logged fixes use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Signal strength per satellite.
 *
 * Filled bars are satellites in the position solution; outlined ones are in
 * view but not contributing. A satellite in view with no SNR at all is drawn
 * empty rather than as zero, because "not tracked" and "tracked badly" are
 * different problems and only one of them improves by waiting.
 */
@Composable
private fun SkyCard(t: Telemetry) {
    SectionCard("Sky") {
        t.satellites.forEach { sat -> SatelliteBar(sat, sat.prn in t.usedPrns) }
        Text(
            "PRN, elevation and signal-to-noise in dB. Bars in colour are the ones " +
                "the fix is actually using.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SatelliteBar(sat: SatelliteView, inSolution: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "%2d".format(sat.prn),
            style = MonoStyle,
            modifier = Modifier.width(28.dp),
        )
        Text(
            sat.elevationDeg?.let { "%2d°".format(it) } ?: "  —",
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.extraSmall,
                )
        ) {
            val snr = sat.snrDb ?: 0
            // 50 dB is a strong signal on this receiver; anything above it just
            // fills the bar rather than overflowing it.
            val fraction = (snr / 50f).coerceIn(0f, 1f)
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(10.dp)
                        .background(
                            if (inSolution) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            MaterialTheme.shapes.extraSmall,
                        )
                )
            }
        }
        Text(
            sat.snrDb?.let { "%2d".format(it) } ?: " —",
            style = MonoStyle,
            color = if (sat.tracked) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).width(24.dp),
        )
    }
}

/** The conventional reading of horizontal dilution of precision. */
private fun dopQuality(hdop: Double): String = when {
    hdop < 1 -> "ideal"
    hdop < 2 -> "excellent"
    hdop < 5 -> "good"
    hdop < 10 -> "moderate"
    hdop < 20 -> "fair"
    else -> "poor"
}

private fun fmt(v: Double?, places: Int): String =
    v?.let { "%.${places}f".format(it) } ?: "—"

// Coarse on purpose: the underlying total is a hint, so seconds-level
// precision would claim an accuracy the estimate does not have.
private fun describeEta(seconds: Int): String = when {
    seconds < 60 -> "1 min"
    seconds < 3600 -> "${(seconds + 30) / 60} min"
    else -> "%d h %d min".format(seconds / 3600, (seconds % 3600) / 60)
}

private fun utcStamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

@Composable
private fun DumpsTab(
    container: AppContainer,
    connection: ConnectionState,
    download: thru.taxi.traxi.session.DownloadState,
    summary: ParseSummary?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dumps by remember { mutableStateOf<List<DumpRepository.Dump>>(emptyList()) }

    LaunchedEffect(download.running, summary) {
        dumps = container.dumpRepository.list()
    }

    if (download.running) {
        SectionCard("Downloading") {
            // No percentage against the whole flash: the device's own record
            // count and flash size are both wrong, so there is no honest total.
            // The block size, though, is known -- so the current sector gets a
            // real bar, and the rate readout shows the link is flowing. The
            // time-left row is the one licensed use of the write-pointer hint:
            // an estimate labelled as one, never a bound or a percent.
            Text(Bytes.describe(download.bytesDownloaded), style = MaterialTheme.typography.titleLarge)
            if (download.blockSizeBytes > 0) {
                val frac = (download.blockBytes.toFloat() / download.blockSizeBytes)
                    .coerceIn(0f, 1f)
                Row2(
                    "Sector ${download.sectorPosition + 1}",
                    "${download.blockBytes / 1024} / ${download.blockSizeBytes / 1024} KB",
                )
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (download.bytesPerSecond > 0) {
                Row2("Speed", "%.1f KB/s".format(download.bytesPerSecond / 1024))
            }
            download.etaSeconds?.let { Row2("Time left", "~${describeEta(it)}") }
            // Work, not position. On a fetch-new these differ: the untouched
            // prefix is spanned but never asked for, while the wrap probe is a
            // whole sector that does not advance the position at all.
            Row2("Sectors fetched", download.sectorsFetched.toString())
            if (download.resumedFrom > 0) Row2("Resumed from", Bytes.describe(download.resumedFrom))
            if (download.retries > 0) Row2("Retries", download.retries.toString())
            // Surfaced, not hidden. A transfer that had to rebuild the radio
            // link six times is a different event from one that did not, and a
            // silent recovery just looks like the app being mysteriously slow.
            if (download.linkResets > 0) {
                Row2("Link rebuilds", download.linkResets.toString())
                Text(
                    "The logger stopped answering and the link was rebuilt to get " +
                        "past it. Nothing is lost — each pass resumes at the frontier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { container.session.cancelDownload() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop and keep what's downloaded") }
        }
    } else if (connection is ConnectionState.Connected) {
        SectionCard("Download") {
            Button(
                onClick = { DownloadService.start(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download flash") }
        }
    }

    summary?.let { ParseSummaryCard(it) }

    SectionCard("Saved dumps") {
        if (dumps.isEmpty()) {
            Text("Nothing downloaded yet.", style = MaterialTheme.typography.bodyMedium)
        }
        dumps.forEach { dump ->
            DumpRow(container, dump, connection, onChanged = {
                scope.launch { dumps = container.dumpRepository.list() }
            })
        }
    }
}

@Composable
private fun DumpRow(
    container: AppContainer,
    dump: DumpRepository.Dump,
    connection: ConnectionState,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stamp = remember(dump.modifiedAt) {
        SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(dump.modifiedAt))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(dump.name, style = MonoStyle)
        Text(
            "${Bytes.describe(dump.sizeBytes)} · $stamp" +
                if (dump.isPartial) " · partial" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A dump with holes looks exactly like a good one -- that is the whole
        // hazard -- so it has to say so where the person choosing what to
        // export, extend or erase against will see it.
        if (dump.damagedBytes > 0) {
            Text(
                "${dump.damagedBytes} bytes never arrived and read as erased flash. " +
                    "Resume to re-read them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!dump.isPartial && connection is ConnectionState.Connected) {
                TextButton({ DownloadService.startIncremental(context, dump.file) }) {
                    Text("Fetch new")
                }
            }
            TextButton({ container.session.parse(dump.file) }) { Text("Parse") }
            var exporting by remember { mutableStateOf(false) }
            TextButton(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    scope.launch {
                        runCatching { exportGpx(context, container, dump) }
                        exporting = false
                    }
                },
            ) { Text(if (exporting) "Exporting…" else "Export GPX") }
            if (dump.isPartial && connection is ConnectionState.Connected) {
                TextButton({ DownloadService.start(context, dump.file) }) { Text("Resume") }
            }
            TextButton({
                scope.launch { container.dumpRepository.delete(dump.file); onChanged() }
            }) { Text("Delete") }
        }
    }
}

/**
 * Speaks only when recording was actually lost.
 *
 * A clean parse renders nothing here. Silence is the signal that all is well —
 * anything printed in this section means data does not exist, so the reader
 * never has to decide whether a recording note is the serious kind.
 */
@Composable
private fun RecordingAuditRows(report: RecordingAudit.Report) {
    if (!report.hasFault) return

    HorizontalDivider()
    Text(
        "RECORDING WAS LOST",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Row2("Stops never resumed", report.unansweredStops.toString())
    report.lastStopAt?.let { Row2("Stopped at", it.toString()) }
    report.explanation()?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Those fixes were never written and cannot be recovered by downloading " +
            "again. Check the logger is recording before setting off.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ParseSummaryCard(summary: ParseSummary) {
    // Collapse is view state, not session state. The summary also feeds the
    // erase gate's "Recorded first→last" display, so closing the card must not
    // clear the parse itself -- a tap folds it to its title, a tap reopens it,
    // and a fresh parse arrives expanded.
    var collapsed by remember(summary) { mutableStateOf(false) }
    SectionCard(
        "Parsed · ${summary.fileName}",
        Modifier.clickable { collapsed = !collapsed },
    ) {
        if (collapsed) {
            // The one thing that must not fold away: fixes that were never
            // recorded are lost data, and this card is where that is said.
            if (summary.recording.hasFault) {
                Text(
                    "RECORDING WAS LOST — tap for details",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@SectionCard
        }
        Text("${summary.fixes} fixes", style = MaterialTheme.typography.titleLarge)
        Row2("Checksum failures", summary.checksumFailures.toString())
        Row2("Sectors with data", summary.sectors.toString())
        Row2("Track segments", summary.segments.toString())
        Row2("Waypoints", summary.waypoints.toString())
        Row2("First fix", summary.firstFix)
        Row2("Last fix", summary.lastFix)

        // Recording gaps come before everything else that is merely
        // informational. A checksum failure can be fixed by downloading again;
        // fixes that were never recorded do not exist anywhere.
        RecordingAuditRows(summary.recording)

        if (summary.rejected.isNotEmpty()) {
            HorizontalDivider()
            Text("Excluded from export", style = MaterialTheme.typography.labelSmall)
            summary.rejected.forEach { (reason, count) ->
                Row2(reason.name.lowercase().replace('_', ' '), count.toString())
            }
        }
        if (summary.checksumFailures > 0) {
            Text(
                "Checksum failures usually mean a transfer problem rather than bad flash. " +
                    "The reference dump has none across 5.4 MB. Re-downloading is worth a try.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Export a dump as GPX and hand it to the share sheet.
 *
 * **Every expensive step runs off the main thread.** Parsing 5.5 MB, filtering
 * 126,000 fixes through spike detection, and building a ~12 MB string are each
 * seconds of work; doing them in the composition's scope -- which dispatches to
 * the main thread -- produced a reliable ANR. Only the startActivity call
 * belongs on the UI thread.
 */
private suspend fun exportGpx(
    context: android.content.Context,
    container: AppContainer,
    dump: DumpRepository.Dump,
) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
    val bytes = container.dumpRepository.read(dump.file)
    val parsed = thru.taxi.traxi.core.format.MtkLogParser.parse(
        bytes,
        thru.taxi.traxi.core.format.GpsRollover.AXN_130B,
    )
    val kept = thru.taxi.traxi.core.format.Quality.filter(parsed.fixes).kept

    val writer = java.io.StringWriter()
    thru.taxi.traxi.core.export.GpxWriter().write(kept, writer, dump.name)
    val file = container.dumpRepository.writeShareable(
        dump.name.removeSuffix(".bin") + ".gpx",
        writer.toString(),
    )

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        context.startActivity(android.content.Intent.createChooser(share, "Share GPX"))
    }
}

// ---------------- config ----------------

@Composable
private fun ConfigTab(container: AppContainer, connection: ConnectionState) {
    val info = (connection as? ConnectionState.Connected)?.info
    if (info == null) {
        SectionCard("Configuration") {
            Text(
                "Connect a logger to read or change its settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    var busy by remember { mutableStateOf(false) }
    var interval by remember(info.timeIntervalSeconds) {
        mutableStateOf(info.timeIntervalSeconds.toString())
    }

    SectionCard("Recording") {
        val status = Pmtk.LogStatus.parse(info.logStatus)
        var now by remember { mutableStateOf(System.nanoTime()) }
        LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(1_000) } }
        Row2("Status", status?.describe() ?: info.logStatus)
        Text(
            "Read ${describeAgo((now - info.logStatusAtNanos) / 1e9)}. This row is not " +
                "refreshed while connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Standing faults come first and in error colour, because they are the
        // one thing in this word that is unambiguous. A cleared logging bit is
        // a state the logger enters constantly and leaves on its own; a
        // need_format or a full flash is recording over until someone acts, and
        // §13 spent hours on exactly that without the app ever being able to
        // say so -- the bits were in every reply it ever read.
        status?.faults?.forEach { fault ->
            Text(
                "The logger reports that $fault. It will not start recording " +
                    "again on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // **Asymmetric, and that is the useful thing about it.** Settled on the
        // hardware 2026-09-19 (§16.11): the word follows the enable flag, not
        // the fix -- it went `256` -> `258` the instant PMTK182,4 was acked,
        // with GPRMC void either side, and the logger then sat enabled and
        // frozen for four more minutes waiting for the sky. So an armed logger
        // with no fix reports `0x0102`, and `0x0100` means genuinely disabled.
        //
        // Believe it when it says no. Do not believe it when it says yes: with
        // the switch in NAV it claims `0x0102` over a frozen pointer (§15.2),
        // and a software pause does not move it at all (§16.10).
        Text(
            "The device's own claim, read once when you connected, and it settles " +
                "nothing on its own. It goes to \"NOT logging\" whenever the receiver " +
                "has no fix — which is every cold connect — and back again a second " +
                "after the sky comes into view, with no command sent. It also reads " +
                "\"logging\" with the slide switch in NAV, over a pointer that never " +
                "moves. Only the Device tab's recording indicator, which pairs the " +
                "write pointer with the fix, can tell you anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Both actions, always. Gating the *recovery* control on
        // `isLoggingEnabled != true` made it unreachable on this unit: the bit
        // never clears, so "Resume logging" could never be drawn -- and it is
        // the control withLoggingPaused's own failure message tells the user to
        // go and press when the logger has been left switched off. A recovery
        // path that depends on an untrustworthy signal is not a recovery path.
        // Enabling logging that is already enabled costs one command and is
        // harmless; not being able to enable it at all is §12.
        Button(
            onClick = {
                busy = true
                container.session.setLogging(true) { busy = false }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Resume logging") }
        OutlinedButton(
            onClick = {
                busy = true
                container.session.setLogging(false) { busy = false }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Pause logging") }
    }

    // Flash-full behaviour. Never queried by this app until now, and §13
    // recorded that a format silently leaves it on STOP -- so on a device that
    // has been through the §13 recovery, the setting that ends recording for
    // good is both wrong by default and invisible. It is drawn as its own card
    // rather than a row because the STOP case is a standing fault, not a
    // preference.
    SectionCard("When the flash fills") {
        val method = info.recordMethod
        Row2("Current", method?.describe() ?: "unknown — the logger did not answer")

        when (method) {
            Pmtk.RecordMethod.STOP -> {
                Text(
                    "RECORDING WILL STOP WHEN FULL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "At this interval the logger holds about three weeks. In STOP mode " +
                        "it writes nothing after that and does not resume on its own. " +
                        "A flash format leaves this setting behind, which is why it can " +
                        "be set without anyone choosing it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Pmtk.RecordMethod.OVERLAP -> Text(
                "A full log wraps and overwrites its oldest records, so recording " +
                    "never stops on its own. Download the whole chip rather than " +
                    "stopping at the write pointer — after a wrap the oldest data " +
                    "lives past it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            null -> Text(
                "The logger did not answer this query, so this setting is unknown " +
                    "rather than assumed. Reconnect to read it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Both written explicitly, and neither applied automatically. The app
        // has never silently changed device configuration and §12 is why.
        Button(
            onClick = {
                busy = true
                container.session.writeRecordMethod(Pmtk.RecordMethod.OVERLAP) { busy = false }
            },
            enabled = !busy && method != Pmtk.RecordMethod.OVERLAP,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set to OVERLAP — keep recording when full") }
        OutlinedButton(
            onClick = {
                busy = true
                container.session.writeRecordMethod(Pmtk.RecordMethod.STOP) { busy = false }
            },
            enabled = !busy && method != Pmtk.RecordMethod.STOP,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set to STOP — preserve the oldest data") }
    }

    SectionCard("Log interval") {
        Row2("Current", "${info.timeIntervalSeconds} s")
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it },
            label = { Text("Seconds between fixes") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                interval.toDoubleOrNull()?.let {
                    busy = true
                    container.session.writeTimeInterval(it) { busy = false }
                }
            },
            enabled = !busy && interval.toDoubleOrNull() != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Write interval") }
    }

    SectionCard("Log format") {
        Row2("Current", "0x%08X".format(info.logFormat.bits))
        Text(info.logFormat.describe(), style = MonoStyle)

        HorizontalDivider()
        Text(
            "Adding NSAT, HDOP and VDOP is the only way to get per-fix satellite " +
                "geometry — no existing dump contains it. Records grow from 42 to 48 " +
                "bytes, so the logger holds about 14% fewer hours.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                busy = true
                container.session.writeLogFormat(LogFormat.WITH_SATELLITE_QUALITY) { busy = false }
            },
            enabled = !busy && info.logFormat.bits != LogFormat.WITH_SATELLITE_QUALITY.bits,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add satellite quality fields") }

        OutlinedButton(
            onClick = {
                busy = true
                container.session.writeLogFormat(LogFormat.OBSERVED) { busy = false }
            },
            enabled = !busy && info.logFormat.bits != LogFormat.OBSERVED.bits,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restore original format") }
    }

    EraseCard(container, info, busy)
}

/**
 * How long the chip will last once emptied, at the settings it is on **now**.
 *
 * Derived rather than written down. An earlier version of this card claimed
 * "about three weeks at five-second intervals", which was wrong twice over: the
 * interval is configurable and was 20 s until recently, and three weeks is the
 * capacity from empty, not what remains. A hardcoded number in a confirmation
 * dialogue is a number that goes stale silently.
 *
 * @return null when the device has not given us everything needed. The flash id
 *   or the interval can both be absent, and prep doc §1 is emphatic that an
 *   invented capacity is worse than none.
 */
private fun continuousLoggingDays(info: DeviceInfo): Double? {
    val capacity = info.flash?.bytes ?: return null
    val interval = info.timeIntervalSeconds.takeIf { it > 0 } ?: return null
    val recordBytes = info.logFormat.recordSizeWithChecksum().takeIf { it > 0 } ?: return null
    val bytesPerSecond = recordBytes / interval
    if (bytesPerSecond <= 0) return null
    return capacity / bytesPerSecond / 86_400.0
}

/**
 * The erase gate, rendered.
 *
 * The layout follows the decision rather than decorating it: when erase is not
 * permitted the card shows *why*, and what would open it, instead of a greyed
 * button with no explanation. A gate that only says no teaches people to look
 * for a way round it.
 */
@Composable
private fun EraseCard(container: AppContainer, info: DeviceInfo, parentBusy: Boolean) {
    val session = container.session
    val evidence by session.eraseEvidence.collectAsState()
    val eraseState by session.erase.collectAsState()
    val downloadState by session.download.collectAsState()
    val summary by session.summary.collectAsState()

    var blockers by remember { mutableStateOf<List<thru.taxi.traxi.core.protocol.EraseGate.Blocker>>(emptyList()) }
    // Deliberately not rememberSaveable. A typed confirmation for an
    // irreversible action should not survive the process being killed and
    // restored underneath the user.
    var typed by remember { mutableStateOf("") }

    // Re-check whenever the evidence changes or the device stops being busy.
    // The pointer is read from the device, so this cannot be a pure function of
    // UI state -- and it must not run mid-transfer, which eraseBlockers guards.
    LaunchedEffect(evidence, eraseState.running, downloadState.running) {
        blockers = session.eraseBlockers()
    }

    SectionCard("Erase") {
        if (blockers.isNotEmpty()) {
            Text(
                "Not available yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            for (b in blockers) {
                Text(b.reason, style = MaterialTheme.typography.bodyMedium)
                Text(
                    b.remedy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SectionCard
        }

        val ready = evidence ?: return@SectionCard
        val word = thru.taxi.traxi.core.protocol.EraseGate.confirmationWord(ready)

        Row2("Verified against", ready.fileName)
        Row2("Fixes held", ready.fixes.toString())

        // The span comes from the parse of this same file, so it describes the
        // data actually being destroyed rather than anything assumed about it.
        summary?.takeIf { it.fileName == ready.fileName }?.let {
            Row2("Recorded", "${it.firstFix} → ${it.lastFix}")
        }

        continuousLoggingDays(info)?.let { days ->
            Row2(
                "Frees",
                "%.0f days at %.1f s, %d-byte records".format(
                    days, info.timeIntervalSeconds, info.logFormat.recordSizeWithChecksum(),
                ),
            )
        }

        Text(
            "This will destroy ${ready.fixes} fixes on the logger. ${ready.fileName} stays " +
                "on this phone and is never deleted, but it is the only copy — export it " +
                "first if you have not already.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Type $word to confirm") },
            singleLine = true,
            enabled = !eraseState.running && !parentBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        if (eraseState.running) {
            Text(
                when (eraseState.phase) {
                    FlashEraser.Phase.DISABLING_LOGGING -> "Disabling logging…"
                    FlashEraser.Phase.ERASING -> "Erasing — this takes up to a minute…"
                    FlashEraser.Phase.VERIFYING -> "Reading sector 0 back to confirm…"
                    FlashEraser.Phase.RESTORING_LOGGING -> "Re-enabling logging…"
                    null -> "Working…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = { session.eraseFlash(typed) { typed = "" } },
            enabled = !eraseState.running && !parentBusy &&
                thru.taxi.traxi.core.protocol.EraseGate.isConfirmed(typed, ready),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Erase the logger") }
    }
}

// ---------------- transcript ----------------

@Composable
private fun LogTab(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(container.session.transcript.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            lines = container.session.transcript.snapshot()
            kotlinx.coroutines.delay(700)
        }
    }

    SectionCard("PMTK transcript") {
        Text(
            "Every sentence exchanged with the logger. When something goes wrong at a " +
                "trailhead this is the only diagnostic available, so it can be shared.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Mirrored to a file as it is written, so it survives a crash, a reboot or " +
                "an update — the view below shows this run, the shared file holds the " +
                "history (${Bytes.describe(container.transcriptFile.sizeBytes())}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({
                scope.launch {
                    // The file, not the in-memory ring: the whole point is to
                    // hand over what happened before the app last died.
                    val file = container.dumpRepository.writeShareable(
                        "traxi-transcript.txt",
                        container.transcriptFile.readAll(),
                    )
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.files", file,
                    )
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "Share transcript",
                        )
                    )
                }
            }) { Text("Share") }
            TextButton({
                container.session.transcript.clear()
                container.transcriptFile.clear()
                lines = emptyList()
            }) {
                Text("Clear")
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (lines.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
            }
            // Newest first: the interesting line is almost always the last one.
            lines.asReversed().take(300).forEach { line ->
                Text(
                    line,
                    style = MonoStyle.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
