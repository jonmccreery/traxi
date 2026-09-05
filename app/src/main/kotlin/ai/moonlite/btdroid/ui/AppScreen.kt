package ai.moonlite.btdroid.ui

import ai.moonlite.btdroid.AppContainer
import ai.moonlite.btdroid.core.format.LogFormat
import ai.moonlite.btdroid.data.DumpRepository
import ai.moonlite.btdroid.service.DownloadService
import ai.moonlite.btdroid.session.ConnectionState
import ai.moonlite.btdroid.session.DeviceInfo
import ai.moonlite.btdroid.session.ParseSummary
import ai.moonlite.btdroid.ui.theme.MonoStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    DEVICE("Device"), DUMPS("Dumps"), CONFIG("Config"), LOG("Log")
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
                title = { Text("btdroid") },
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
                "btdroid needs Bluetooth access to reach the logger. It does not " +
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
                    Text(
                        "The logger must be powered on and already paired in Android's " +
                            "Bluetooth settings. Classic Bluetooth has no unpaired-connect path.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard("Connect") {
                Text(
                    "Pick the logger through Android's own device chooser. The choice " +
                        "is remembered, so later trips are one tap.",
                    style = MaterialTheme.typography.bodyMedium,
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
        Row2("Transport", info.transportDescription)
        Row2("Firmware", info.firmware)
        Row2("Model ID", info.modelId)
        Row2("Log format", "0x%08X".format(info.logFormat.bits))
        Row2("Fields", info.logFormat.describe(), mono = true)
        Row2("Interval", "${info.timeIntervalSeconds} s")
        Row2("Status", info.logStatus)

        if (info.needsWeekRollover) {
            HorizontalDivider()
            Text(
                "This firmware predates the 2019 GPS week rollover, so its timestamps " +
                    "arrive 1024 weeks early. btdroid corrects them automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------- dumps ----------------

@Composable
private fun DumpsTab(
    container: AppContainer,
    connection: ConnectionState,
    download: ai.moonlite.btdroid.session.DownloadState,
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
            // No percentage: the device's own record count and flash size are
            // both wrong, so there is no honest denominator. Report work done.
            Text("${download.bytesDownloaded / 1024} KB", style = MaterialTheme.typography.titleLarge)
            Row2("Sectors", download.sectorsRead.toString())
            if (download.resumedFrom > 0) Row2("Resumed from", "${download.resumedFrom / 1024} KB")
            if (download.retries > 0) Row2("Retries", download.retries.toString())
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { container.session.cancelDownload() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop and keep what's downloaded") }
        }
    } else if (connection is ConnectionState.Connected) {
        SectionCard("Download") {
            Text(
                "Reads the whole flash, about 20-25 minutes over Bluetooth. It keeps " +
                    "running with the screen off, and can be resumed if interrupted.",
                style = MaterialTheme.typography.bodyMedium,
            )
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
            "${dump.sizeBytes / 1024} KB · ${dump.sectorsComplete} sectors · $stamp" +
                if (dump.isPartial) " · partial" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ container.session.parse(dump.file) }) { Text("Parse") }
            TextButton({
                scope.launch { exportGpx(context, container, dump) }
            }) { Text("Export GPX") }
            if (dump.isPartial && connection is ConnectionState.Connected) {
                TextButton({ DownloadService.start(context, dump.file) }) { Text("Resume") }
            }
            TextButton({
                scope.launch { container.dumpRepository.delete(dump.file); onChanged() }
            }) { Text("Delete") }
        }
    }
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

private suspend fun exportGpx(
    context: android.content.Context,
    container: AppContainer,
    dump: DumpRepository.Dump,
) {
    val bytes = container.dumpRepository.read(dump.file)
    val parsed = ai.moonlite.btdroid.core.format.MtkLogParser.parse(
        bytes,
        ai.moonlite.btdroid.core.format.GpsRollover.AXN_130B,
    )
    val kept = ai.moonlite.btdroid.core.format.Quality.filter(parsed.fixes).kept

    val writer = java.io.StringWriter()
    ai.moonlite.btdroid.core.export.GpxWriter().write(kept, writer, dump.name)
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
    context.startActivity(android.content.Intent.createChooser(share, "Share GPX"))
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

    SectionCard("Erase") {
        Text(
            "btdroid cannot erase the logger. The flash is the only copy of your data, " +
                "the erase is irreversible, and this device is demonstrably unreliable " +
                "about its own state. The command is not implemented at all.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
                        "btdroid-transcript.txt",
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
