package thru.taxi.traxi.ui

import thru.taxi.traxi.AppContainer
import thru.taxi.traxi.core.format.Bytes
import thru.taxi.traxi.core.format.LogFormat
import thru.taxi.traxi.core.format.RecordingAudit
import thru.taxi.traxi.core.protocol.FixType
import thru.taxi.traxi.core.protocol.FlashEraser
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
import thru.taxi.traxi.session.LiveActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    DEVICE("Device"), LIVE("Live"), DUMPS("Dumps"), CONFIG("Config"), LOG("Log")
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
                Tab.DEVICE -> DeviceTab(container, connection, onPairDevice)
                Tab.LIVE -> LiveTab(connection, telemetry, liveActivity)
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
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
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

    when (connection) {
        is ConnectionState.Connected -> {
            DeviceInfoCard(connection.info, session.isSimulated)
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
private fun DeviceInfoCard(info: DeviceInfo, simulated: Boolean) {
    SectionCard(if (simulated) "Simulated logger" else "Logger") {
        Text(info.displayModel, style = MaterialTheme.typography.titleLarge)
        Row2("Transport", info.transportDescription)
        Row2("Firmware", info.firmware)
        Row2("Log format", "0x%08X".format(info.logFormat.bits))
        Row2("Fields", info.logFormat.describe(), mono = true)
        Row2("Interval", "${info.timeIntervalSeconds} s")

        // Decoded, and called out when it is off. A logger that is not
        // recording is indistinguishable from one that is until the trip ends,
        // so this is the one status the user must not have to interpret.
        val status = Pmtk.LogStatus.parse(info.logStatus)
        if (status == null) {
            Row2("Status", info.logStatus)
        } else if (status.isLoggingEnabled) {
            Row2("Status", status.describe())
        } else {
            Text(
                "NOT RECORDING",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "The logger is powered on but not writing fixes. This is how it is " +
                    "left if a settings write is interrupted partway.",
                style = MaterialTheme.typography.bodySmall,
            )
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

        if (info.needsWeekRollover) {
            HorizontalDivider()
            Text(
                "This firmware predates the 2019 GPS week rollover, so its timestamps " +
                    "arrive 1024 weeks early. Traxi corrects them automatically.",
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

    // The heartbeat leads: whether data is flowing is the first thing to know,
    // ahead of what the data says. It also guards the section 12 failure -- a
    // stalled stream is visible here rather than looking like a frozen fix.
    LiveHeartbeat(activity)

    if (telemetry == null || !telemetry.hasAnything) return

    FixCard(telemetry)
    if (telemetry.hasPosition) PositionCard(telemetry)
    if (telemetry.satellites.isNotEmpty()) SkyCard(telemetry)
}

@Composable
private fun LiveHeartbeat(activity: LiveActivity) {
    // Advance a local clock so "X s ago" keeps counting up even when nothing
    // arrives -- that climbing number is exactly how a stall becomes visible.
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.nanoTime(); delay(500) }
    }

    // Blink the dot on each sentence.
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(activity.pulse) { blink = true; delay(150); blink = false }
    val alpha by animateFloatAsState(if (blink) 1f else 0.3f, label = "pulse")

    val hasData = activity.lastSentenceAtNanos != 0L
    // Clamp: a sentence can land just after the 'now' tick was sampled, which
    // would otherwise render a nonsensical "-0.0 s ago".
    val ageSec = if (hasData) ((now - activity.lastSentenceAtNanos) / 1e9).coerceAtLeast(0.0)
        else Double.NaN
    val stale = hasData && ageSec > 3.0
    val accent = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    SectionCard(if (stale) "Stalled" else "Receiving") {
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
                    !hasData -> "waiting for the first navigation sentence"
                    stale -> "no data for %.0f s".format(ageSec)
                    else -> "last update %.1f s ago".format(ageSec)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (stale) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (activity.sentencesPerSecond > 0) {
            Row2("Flow", "~%.0f sentences/sec".format(activity.sentencesPerSecond))
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
            // real bar, and the rate readout shows the link is flowing.
            Text(Bytes.describe(download.bytesDownloaded), style = MaterialTheme.typography.titleLarge)
            if (download.blockSizeBytes > 0) {
                val frac = (download.blockBytes.toFloat() / download.blockSizeBytes)
                    .coerceIn(0f, 1f)
                Row2(
                    "Sector ${download.sectorsRead + 1}",
                    "${download.blockBytes / 1024} / ${download.blockSizeBytes / 1024} KB",
                )
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (download.bytesPerSecond > 0) {
                Row2("Speed", "%.1f KB/s".format(download.bytesPerSecond / 1024))
            }
            Row2("Sectors done", download.sectorsRead.toString())
            if (download.resumedFrom > 0) Row2("Resumed from", Bytes.describe(download.resumedFrom))
            if (download.retries > 0) Row2("Retries", download.retries.toString())
            OutlinedButton(
                onClick = { container.session.cancelDownload() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop and keep what's downloaded") }
        }
    } else if (connection is ConnectionState.Connected) {
        SectionCard("Download") {
            val overUsb = (connection as? ConnectionState.Connected)
                ?.info?.transportDescription?.startsWith("usb") == true
            Text(
                if (overUsb) {
                    "Reads the whole flash, about 90 seconds over USB. It keeps running " +
                        "with the screen off, and can be resumed if interrupted."
                } else {
                    "Reads the whole flash. Over Bluetooth this logger manages about " +
                        "493 bytes a second, so a full read takes roughly three hours — " +
                        "use \"Fetch new\" below instead, or connect by cable. It keeps " +
                        "running with the screen off, and can be resumed."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { DownloadService.start(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download flash") }
            Text(
                "If you already have a dump, \"Fetch new\" below is far quicker — it " +
                    "reads only what the logger has recorded since, after checking the " +
                    "log has not wrapped.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            "${Bytes.describe(dump.sizeBytes)} · ${dump.sectorsComplete} sectors · $stamp" +
                if (dump.isPartial) " · partial" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    SectionCard("Parsed · ${summary.fileName}") {
        Text("${summary.fixes} fixes", style = MaterialTheme.typography.titleLarge)
        Row2("Checksum failures", summary.checksumFailures.toString())
        Row2("Sectors", summary.sectors.toString())
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

    SectionCard("Writing settings") {
        Text(
            "These change the device. Logging is disabled for the write and re-enabled " +
                "afterwards. Nothing here happens as a side effect of connecting.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SectionCard("Recording") {
        val status = Pmtk.LogStatus.parse(info.logStatus)
        Row2("Status", status?.describe() ?: info.logStatus)
        Text(
            "Pausing is not needed for a download — reading the flash does not stop " +
                "the logger. This is here so a device left switched off by an " +
                "interrupted write can be switched back on.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (status?.isLoggingEnabled != true) {
            Button(
                onClick = {
                    busy = true
                    container.session.setLogging(true) { busy = false }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resume logging") }
        } else {
            OutlinedButton(
                onClick = {
                    busy = true
                    container.session.setLogging(false) { busy = false }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pause logging") }
        }
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
        Text(
            "Dump, verify, erase, repeat is the only workflow that covers a long trip, " +
                "so this is a normal part of using the device — but it is irreversible, " +
                "and the flash is the only copy until you export.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({
                scope.launch {
                    val file = container.dumpRepository.writeShareable(
                        "traxi-transcript.txt",
                        lines.joinToString("\n"),
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
            TextButton({ container.session.transcript.clear(); lines = emptyList() }) {
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
