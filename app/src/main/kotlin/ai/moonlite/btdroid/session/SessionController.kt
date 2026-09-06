package ai.moonlite.btdroid.session

import ai.moonlite.btdroid.bt.BluetoothSppTransport
import ai.moonlite.btdroid.bt.Bonding
import ai.moonlite.btdroid.bt.CompanionPairing
import ai.moonlite.btdroid.core.format.GpsRollover
import ai.moonlite.btdroid.core.format.LogFormat
import ai.moonlite.btdroid.core.format.MtkLogParser
import ai.moonlite.btdroid.core.format.Quality
import ai.moonlite.btdroid.core.protocol.EraseGate
import ai.moonlite.btdroid.core.protocol.FlashDownloader
import ai.moonlite.btdroid.core.protocol.FlashEraser
import ai.moonlite.btdroid.core.protocol.Pmtk
import ai.moonlite.btdroid.core.protocol.PmtkClient
import ai.moonlite.btdroid.core.protocol.RingTranscript
import ai.moonlite.btdroid.core.transport.SimulatedLoggerTransport
import ai.moonlite.btdroid.core.transport.Transport
import ai.moonlite.btdroid.usb.UsbSerialTransport
import ai.moonlite.btdroid.data.DumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the app knows about the attached logger, all of it read-only. */
data class DeviceInfo(
    val transportDescription: String,
    val firmware: String,
    val modelId: String,
    /** e.g. "BT-Q1000XT". Empty on firmware that omits it. */
    val modelName: String,
    val logFormat: LogFormat,
    val timeIntervalSeconds: Double,
    val logStatus: String,
    val needsWeekRollover: Boolean,
    /** Decoded flash identity, or null if the device answered unrecognisably. */
    val flash: Pmtk.FlashId?,
    /** Next write address in bytes. A progress hint, never a download bound. */
    val writePointer: Long?,
) {
    /** Best available name for the hardware. */
    val displayModel: String
        get() = modelName.ifBlank { "Model $modelId" }

    /** Fraction of flash written, if both figures are known. */
    val flashUsedFraction: Double?
        get() {
            val total = flash?.bytes ?: return null
            val used = writePointer ?: return null
            return if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else null
        }
}

data class DownloadState(
    val running: Boolean = false,
    val bytesDownloaded: Int = 0,
    val sectorsRead: Int = 0,
    val retries: Int = 0,
    val resumedFrom: Int = 0,
)

/** Result of parsing a dump file, for display. */
data class ParseSummary(
    val fileName: String,
    val fixes: Int,
    val checksumFailures: Int,
    val sectors: Int,
    val segments: Int,
    val firstFix: String,
    val lastFix: String,
    val waypoints: Int,
    val rejected: Map<Quality.Rejection, Int>,
)

/**
 * Progress of an erase, for a UI that must not look frozen while a whole-chip
 * flash erase runs for tens of seconds.
 */
data class EraseState(
    val running: Boolean = false,
    val phase: FlashEraser.Phase? = null,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val target: String) : ConnectionState
    data class Connected(val info: DeviceInfo) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * Owns the live device session: connect, interrogate, download, parse.
 *
 * A single instance lives in the Application so that a download survives
 * Activity recreation — a rotation or a trip through the recents list must not
 * cost 25 minutes of transfer.
 *
 * **Connecting never writes.** [connect] issues queries only, and there is no
 * code path from connection to configuration change. That is a safety rail
 * from the prep doc, and the round-trip test in :core asserts it holds.
 */
class SessionController(
    private val context: android.content.Context,
    private val pairing: CompanionPairing,
    private val dumps: DumpRepository,
    private val scope: CoroutineScope,
) {
    val transcript = RingTranscript(capacity = 4000)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _summary = MutableStateFlow<ParseSummary?>(null)
    val summary: StateFlow<ParseSummary?> = _summary.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _erase = MutableStateFlow(EraseState())
    val erase: StateFlow<EraseState> = _erase.asStateFlow()

    /**
     * The dump this session may erase against, or null if there is not one.
     *
     * Populated only when a download completes **and** the resulting file is
     * then parsed cleanly, which is prep doc §0.2 clause 1: not "a download
     * happened", but an image on disk that has been read back and understood.
     * Cleared on disconnect and whenever a new download starts, so it can never
     * outlive the state it describes.
     */
    private val _eraseEvidence = MutableStateFlow<EraseGate.Evidence?>(null)
    val eraseEvidence: StateFlow<EraseGate.Evidence?> = _eraseEvidence.asStateFlow()

    /**
     * The download half of the evidence, waiting for its parse.
     *
     * Held separately so that parsing some *other* dump from the list -- an old
     * one, or one from a previous session -- cannot be mistaken for verifying
     * the one just downloaded.
     */
    private data class PendingEvidence(
        val file: File,
        val coveredBytes: Int,
        val downloadComplete: Boolean,
        val damagedBytes: Int,
    )

    private var pendingEvidence: PendingEvidence? = null

    private var transport: Transport? = null
    private var client: PmtkClient? = null
    private var downloadJob: Job? = null
    private var cancelRequested = false
    private var connectedAddress: String? = null
    private var linkWatcher: android.content.BroadcastReceiver? = null

    /** Set when the session is a simulation, so the UI can say so plainly. */
    var isSimulated: Boolean = false
        private set

    fun clearMessage() { _message.value = null }

    // ---------------- connect ----------------

    /**
     * Connect over Bluetooth and read the device's identity and configuration.
     *
     * Queries only. Any failure leaves the transport closed rather than
     * half-open, so a retry starts from a known state.
     */
    fun connect(address: String) {
        if (_connection.value is ConnectionState.Connecting) return
        scope.launch {
            _connection.value = ConnectionState.Connecting(address)
            disconnectQuietly()
            try {
                val device = pairing.deviceFor(address)
                    ?: throw IllegalStateException("No Bluetooth device at $address")

                // Bond before opening a socket. Choosing a device in the
                // companion chooser associates it but does not pair it, and an
                // RFCOMM connect to an unpaired device fails with an opaque
                // socket error rather than saying "not paired".
                transcript.note("ensuring $address is bonded")
                when (val bond = Bonding.ensureBonded(context, device)) {
                    is Bonding.Result.Failed -> {
                        transcript.note("bonding failed: ${bond.reason}")
                        _connection.value = ConnectionState.Failed(bond.reason)
                        return@launch
                    }
                    Bonding.Result.Bonded -> transcript.note("bonded")
                    Bonding.Result.AlreadyBonded -> transcript.note("already bonded")
                }

                try {
                    val t = BluetoothSppTransport(device)
                    t.open()
                    openWith(t, simulated = false)
                } catch (first: Exception) {
                    // A connect failure on a device Android believes is bonded
                    // is the signature of a stale link key: this logger uses
                    // legacy pairing and forgets its key across a power cycle
                    // while the phone keeps hers. Nothing prompts for a PIN
                    // because nothing thinks pairing is needed. Clear the bond
                    // and pair again, which restores the PIN exchange.
                    if (!Bonding.looksStale(device)) throw first

                    transcript.note(
                        "connect failed while bonded; clearing a probably-stale " +
                            "link key and re-pairing"
                    )
                    if (!Bonding.removeBond(device)) {
                        throw IllegalStateException(
                            "The pairing with this logger has gone stale, and it could " +
                                "not be cleared automatically.\n\n" +
                                "Forget \"${runCatching { device.name }.getOrNull() ?: address}\" " +
                                "in Android's Bluetooth settings, then connect again and " +
                                "enter ${Bonding.KNOWN_PIN}."
                        )
                    }

                    // Give the stack time to settle into BOND_NONE.
                    kotlinx.coroutines.delay(1_500)
                    when (val again = Bonding.ensureBonded(context, device)) {
                        is Bonding.Result.Failed ->
                            throw IllegalStateException(again.reason)
                        else -> transcript.note("re-paired; retrying connect")
                    }

                    val retry = BluetoothSppTransport(device)
                    retry.open()
                    openWith(retry, simulated = false)
                }
            } catch (e: Exception) {
                disconnectQuietly()
                _connection.value = ConnectionState.Failed(explainConnectFailure(e, address))
            }
        }
    }

    /**
     * Connect over USB.
     *
     * Dramatically simpler than the Bluetooth path, and that is the point.
     * There is no bond, no PIN, no stale link key, and no discovery — the
     * device is either plugged in with permission granted or it is not. It is
     * also 65x faster: 64 KB/s against 493 B/s, so a full flash read takes 83
     * seconds rather than three hours.
     */
    fun connectUsb(
        usbManager: android.hardware.usb.UsbManager,
        device: android.hardware.usb.UsbDevice,
    ) {
        if (_connection.value is ConnectionState.Connecting) return
        scope.launch {
            _connection.value = ConnectionState.Connecting(
                device.productName ?: device.deviceName
            )
            disconnectQuietly()
            try {
                val t = UsbSerialTransport(usbManager, device, transcript::note)
                t.open()
                openWith(t, simulated = false)
            } catch (e: Exception) {
                disconnectQuietly()
                _connection.value = ConnectionState.Failed(
                    e.message ?: "Could not open the USB connection"
                )
            }
        }
    }

    /**
     * Connect to a simulated logger backed by [image].
     *
     * Not a developer nicety. The phone is the only computer available in the
     * field, so the app has to be operable and diagnosable with the logger
     * absent — re-parsing a partial dump, checking an export, showing someone
     * what the flow looks like.
     */
    fun connectSimulated(image: ByteArray) {
        scope.launch {
            _connection.value = ConnectionState.Connecting("simulator")
            disconnectQuietly()
            try {
                val t = SimulatedLoggerTransport(image, chunkSize = 0x800)
                t.open()
                openWith(t, simulated = true)
            } catch (e: Exception) {
                disconnectQuietly()
                _connection.value = ConnectionState.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun openWith(t: Transport, simulated: Boolean) {
        transport = t
        isSimulated = simulated
        val c = PmtkClient(t, transcript)
        client = c

        val firmware = c.queryFirmware()
        val format = c.queryLogFormat()
        val interval = c.queryTimeIntervalSeconds()
        // The rest are informational. A firmware that does not answer one of
        // them must not block a session that is otherwise perfectly usable.
        val status = runCatching { c.queryLogStatus() }.getOrDefault("unavailable")
        val flash = c.queryFlashId()
        val pointer = c.queryWritePointer()

        // Only Bluetooth has an ACL broadcast to watch. A USB unplug surfaces
        // as a failed transfer instead, which noticeIfLinkDied() picks up.
        if (!simulated && t.description.startsWith("bluetooth ")) {
            connectedAddress = t.description.substringAfterLast(' ')
            connectedAddress?.let { watchLink(it) }
        }

        _connection.value = ConnectionState.Connected(
            DeviceInfo(
                transportDescription = t.description,
                firmware = firmware.release,
                modelId = firmware.modelId,
                modelName = firmware.modelName,
                logFormat = format,
                timeIntervalSeconds = interval,
                logStatus = status,
                needsWeekRollover = firmware.needsWeekRollover,
                flash = flash,
                writePointer = pointer,
            )
        )
    }

    fun disconnect() {
        scope.launch {
            cancelDownload()
            disconnectQuietly()
            _connection.value = ConnectionState.Disconnected
            // "In the current session" means against *this* connection. A
            // logger that has been unplugged may come back with more data on
            // it, or be a different logger entirely.
            invalidateEraseEvidence()
        }
    }

    /**
     * Notice when the logger goes away on its own.
     *
     * Without this the app reports "Connected" indefinitely after the link
     * dies -- the state only ever changed when the user tapped Disconnect. A
     * radio link drops for ordinary reasons: walking out of range, the logger
     * switching off, its battery going flat. Showing a live connection that
     * does not exist is worse than showing none, because every subsequent
     * action fails for reasons that look unrelated to the real cause.
     *
     * ACL disconnect is a system broadcast, so it arrives promptly rather than
     * being discovered on the next failed read.
     */
    private fun watchLink(address: String) {
        stopWatchingLink()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val device: android.bluetooth.BluetoothDevice? =
                    intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (!address.equals(device?.address, ignoreCase = true)) return
                onLinkLost("The logger disconnected. It may be out of range, switched off, or flat.")
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED
            ),
        )
        linkWatcher = receiver
    }

    private fun stopWatchingLink() {
        linkWatcher?.let { runCatching { context.unregisterReceiver(it) } }
        linkWatcher = null
    }

    /** Tear the session down and say so, from wherever the loss was noticed. */
    private fun onLinkLost(reason: String) {
        if (_connection.value !is ConnectionState.Connected) return
        transcript.note("link lost: $reason")
        cancelRequested = true
        disconnectQuietly()
        _connection.value = ConnectionState.Failed(reason)
        _message.value = reason
    }

    /**
     * Called after any operation fails. A transport that is no longer open
     * means the session is gone, whatever the immediate error said.
     */
    private fun noticeIfLinkDied() {
        if (transport?.isOpen == false) {
            onLinkLost("The connection to the logger was lost.")
        }
    }

    private fun disconnectQuietly() {
        stopWatchingLink()
        connectedAddress = null
        runCatching { transport?.close() }
        transport = null
        client = null
    }

    /**
     * Turn a socket-level failure into something that points at the real cause.
     *
     * `read failed, socket might closed or timeout, read ret: -1` is what
     * Android reports for several genuinely different problems, and on its own
     * it sends people looking at the wrong one. The distinction that matters
     * here is between a device that cannot be reached and a device that is
     * reachable but does not speak Serial Port Profile — picking a set of
     * headphones out of the chooser produces exactly the same error text as a
     * logger that is switched off.
     */
    private fun explainConnectFailure(e: Exception, address: String): String {
        val detail = e.message ?: e.toString()
        val looksLikeNoSpp = detail.contains("read failed", ignoreCase = true) ||
            detail.contains("socket might closed", ignoreCase = true)

        return if (looksLikeNoSpp) {
            "Could not open a serial connection to $address.\n\n" +
                "Either the logger is switched off or out of range, or this device " +
                "is not the logger — most Bluetooth accessories do not offer the " +
                "Serial Port Profile, and they fail in exactly this way.\n\n" +
                "Check the logger is powered on, then pick it by name in the chooser."
        } else {
            detail
        }
    }

    // ---------------- download ----------------

    /**
     * Download the whole flash to a file, resuming [resumeFile] if given.
     *
     * Bytes are written to disk when the transfer ends for any reason —
     * completion, cancellation, or error — because a partial dump is worth
     * strictly more than no dump, and is resumable.
     */
    /**
     * Extend an existing dump with whatever the logger has recorded since.
     *
     * The reason this exists: the Bluetooth link runs at 493 B/s, so re-reading
     * the whole 5.4 MB chip takes about three hours, while a five-day trip adds
     * only ~490 KB. Fetching the difference is the difference between a
     * seventeen-minute wait and an afternoon.
     *
     * Writes a **new** dump rather than modifying [source]. The existing file is
     * the only copy of that data until the new one is safely on disk, and a
     * download that fails partway must not be able to damage it.
     */
    fun startIncrementalDownload(source: File, onFinished: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        if (downloadJob?.isActive == true) return

        cancelRequested = false
        invalidateEraseEvidence()
        downloadJob = scope.launch {
            try {
                val previous = dumps.read(source)
                _download.value = DownloadState(
                    running = true,
                    bytesDownloaded = previous.size,
                    resumedFrom = previous.size,
                )

                val target = dumps.newDumpFile()
                val result = FlashDownloader(c).downloadIncremental(
                    previous = previous,
                    onProgress = { p ->
                        _download.value = _download.value.copy(
                            bytesDownloaded = p.bytesDownloaded,
                            sectorsRead = p.sectorsRead,
                            retries = p.retries,
                        )
                    },
                    shouldContinue = { !cancelRequested },
                )

                // Never keep an empty image. It is not resumable, it parses to
                // a meaningless "0 fixes" summary presented as a result, and it
                // clutters the list that the real dumps live in.
                if (result.image.isEmpty()) {
                    _message.value =
                        "Nothing was read — ${result.failure ?: "the logger did not respond"}"
                    return@launch
                }

                dumps.save(target, result.image, partial = !result.isComplete)
                pendingEvidence = PendingEvidence(
                    file = target,
                    coveredBytes = result.image.size,
                    downloadComplete = result.isComplete,
                    damagedBytes = result.damagedBytes,
                )
                val gained = result.image.size - previous.size
                _message.value = when {
                    result.failure != null ->
                        "Interrupted — ${result.image.size / 1024} KB saved and resumable"
                    gained > 0 -> "Added ${gained / 1024} KB of new tracking to ${target.name}"
                    else -> "Already up to date — nothing new on the logger"
                }
                if (result.failure != null) noticeIfLinkDied()
                parse(target)
            } catch (e: Exception) {
                transcript.note("incremental download failed: ${e.message}")
                _message.value = "Update failed: ${e.message}"
                noticeIfLinkDied()
            } finally {
                _download.value = _download.value.copy(running = false)
                onFinished()
            }
        }
    }

    fun startDownload(resumeFile: File? = null, onFinished: () -> Unit = {}) {
        val c = client ?: run {
            _message.value = "Not connected"
            return
        }
        if (downloadJob?.isActive == true) return

        cancelRequested = false
        // Any previous verdict describes a dump that is no longer the newest
        // thing we know about the device. Drop it before the first byte lands.
        invalidateEraseEvidence()
        downloadJob = scope.launch {
            var target = resumeFile
            var existing = ByteArray(0)
            var resumeFrom = 0

            try {
                if (target != null) {
                    existing = dumps.read(target)
                    resumeFrom = dumps.resumeOffset(target)
                    existing = existing.copyOf(resumeFrom)
                } else {
                    target = dumps.newDumpFile()
                }

                _download.value = DownloadState(
                    running = true,
                    bytesDownloaded = resumeFrom,
                    resumedFrom = resumeFrom,
                )

                val downloader = FlashDownloader(c)
                val result = downloader.download(
                    existing = existing,
                    resumeFrom = resumeFrom,
                    onProgress = { p ->
                        _download.value = _download.value.copy(
                            bytesDownloaded = p.bytesDownloaded,
                            sectorsRead = p.sectorsRead,
                            retries = p.retries,
                        )
                    },
                    shouldContinue = { !cancelRequested },
                )

                if (result.image.isEmpty()) {
                    _message.value =
                        "Nothing was read — ${result.failure ?: "the logger did not respond"}"
                    return@launch
                }

                val partial = !result.isComplete
                // Save whenever there are bytes, including after a failure. The
                // bytes are the whole point; the error is only how it ended.
                dumps.save(target, result.image, partial = partial)
                pendingEvidence = PendingEvidence(
                    file = target,
                    coveredBytes = result.image.size,
                    downloadComplete = result.isComplete,
                    damagedBytes = result.damagedBytes,
                )
                val kb = result.image.size / 1024
                _message.value = when {
                    result.isDamaged ->
                        "Downloaded $kb KB but ${result.damagedBytes} bytes never arrived — " +
                            "this copy has holes and is NOT a verified dump"
                    result.failure != null ->
                        "Interrupted after $kb KB — saved and resumable (${result.failure})"
                    partial -> "Stopped early — $kb KB saved and resumable"
                    else -> "Downloaded $kb KB to ${target.name}"
                }
                if (result.failure != null) noticeIfLinkDied()
                parse(target)
            } catch (e: Exception) {
                transcript.note("download failed: ${e.message}")
                _message.value = "Download failed: ${e.message}"
                noticeIfLinkDied()
            } finally {
                _download.value = _download.value.copy(running = false)
                onFinished()
            }
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    // ---------------- parse ----------------

    /** Parse a dump already on disk. Works with no device attached. */
    fun parse(file: File) {
        scope.launch {
            try {
                val bytes = dumps.read(file)
                val result = MtkLogParser.parse(bytes, GpsRollover.AXN_130B)
                val filtered = Quality.filter(result.fixes)
                val segments = Quality.segment(filtered.kept)

                _summary.value = ParseSummary(
                    fileName = file.name,
                    fixes = result.fixes.size,
                    checksumFailures = result.stats.checksumFailures,
                    sectors = result.stats.sectorsWithData,
                    segments = segments.size,
                    firstFix = result.fixes.firstOrNull()?.instant?.toString() ?: "—",
                    lastFix = result.fixes.lastOrNull()?.instant?.toString() ?: "—",
                    waypoints = result.fixes.count { it.isWaypoint },
                    rejected = filtered.rejected,
                )

                // Clause 1 completes here, and only for the file this session
                // actually downloaded. Parsing an older dump from the list must
                // never license erasing what is on the device now.
                val pending = pendingEvidence
                if (pending != null && pending.file == file) {
                    _eraseEvidence.value = EraseGate.Evidence(
                        fileName = file.name,
                        coveredBytes = pending.coveredBytes,
                        downloadComplete = pending.downloadComplete,
                        damagedBytes = pending.damagedBytes,
                        fixes = result.fixes.size,
                        checksumFailures = result.stats.checksumFailures,
                    )
                }
            } catch (e: Exception) {
                _message.value = "Parse failed: ${e.message}"
                // A dump that would not parse is not verified, whatever the
                // downloader thought of it.
                if (pendingEvidence?.file == file) invalidateEraseEvidence()
            }
        }
    }

    // ---------------- erase ----------------

    private fun invalidateEraseEvidence() {
        pendingEvidence = null
        _eraseEvidence.value = null
    }

    /**
     * Reasons the device may not be erased right now, re-checked against a
     * **freshly read** write pointer.
     *
     * Suspends because of that read. The UI calls this to render the gate, and
     * again implicitly inside [eraseFlash] -- the displayed list is a courtesy,
     * the one inside the action is the decision.
     */
    suspend fun eraseBlockers(): List<EraseGate.Blocker> {
        val c = client
            ?: return listOf(
                EraseGate.Blocker(
                    "Not connected to a logger.",
                    "Connect over USB or Bluetooth first.",
                )
            )
        // Must return *before* touching the device. This is called from a
        // LaunchedEffect that re-fires when the evidence changes, and starting
        // a download clears the evidence -- so without this guard, rendering
        // the gate would inject a PMTK182,2,8 query into the middle of an
        // active block read and corrupt the transfer.
        if (downloadJob?.isActive == true || _erase.value.running) {
            return listOf(
                EraseGate.Blocker(
                    "The logger is busy.",
                    "Wait for the current transfer to finish.",
                )
            )
        }
        val pointer = runCatching { c.queryWritePointer() }.getOrNull()
        return EraseGate.blockers(_eraseEvidence.value, pointer)
    }

    /**
     * Erase the logger, if and only if the gate permits it.
     *
     * **This is deliberately not called from anywhere but a user's explicit
     * tap** — prep doc §0.2 clause 4. Chaining it onto a download-complete
     * callback would be convenient and is exactly the shape of automation that
     * turns one mistaken tap into lost data.
     *
     * The gate is re-evaluated here against a write pointer read at this
     * moment, not against whatever the UI last rendered. Between the screen
     * being drawn and the button being pressed the logger keeps recording, and
     * the check that matters is the one closest to the irreversible act.
     *
     * The pre-erase dump is never touched, per clause 7. It is the only copy of
     * that data until the user exports it.
     */
    fun eraseFlash(typedConfirmation: String, onDone: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        if (_erase.value.running || downloadJob?.isActive == true) return

        scope.launch {
            _erase.value = EraseState(running = true)
            try {
                val evidence = _eraseEvidence.value
                val pointer = runCatching { c.queryWritePointer() }.getOrNull()

                if (!EraseGate.permits(evidence, pointer, typedConfirmation)) {
                    val blockers = EraseGate.blockers(evidence, pointer)
                    _message.value = when {
                        blockers.isNotEmpty() ->
                            "Erase refused — ${blockers.first().reason} ${blockers.first().remedy}"
                        else ->
                            "Erase refused — the confirmation did not match. " +
                                "Type the fix count from the parse summary."
                    }
                    transcript.note("erase refused: ${blockers.size} blocker(s)")
                    return@launch
                }

                val result = FlashEraser(c).erase { phase ->
                    _erase.value = _erase.value.copy(phase = phase)
                }

                _message.value = when {
                    result.isClean && result.loggingRestored ->
                        "Flash erased and verified empty. " +
                            "${evidence?.fileName} is still on this phone — export it before it matters."
                    result.isClean ->
                        "Flash erased and verified, but logging could NOT be re-enabled. " +
                            "Turn the logger off and on before relying on it."
                    result.erased && !result.verified ->
                        "The logger acknowledged the erase but sector 0 still holds data. " +
                            "Treat the flash as NOT erased — ${result.failure}"
                    else ->
                        "Erase failed — ${result.failure ?: "the logger did not respond"}"
                }

                // Whatever happened, what we know about the device is now stale.
                invalidateEraseEvidence()
                runCatching { refreshConfig() }
                if (!result.isClean) noticeIfLinkDied()
            } catch (e: Exception) {
                transcript.note("erase failed: ${e.message}")
                _message.value = "Erase failed: ${e.message}"
                invalidateEraseEvidence()
                noticeIfLinkDied()
            } finally {
                _erase.value = EraseState(running = false)
                onDone()
            }
        }
    }

    // ---------------- config writes ----------------

    /**
     * Write the log interval.
     *
     * Logging is disabled first and restored afterwards, and the whole sequence
     * is spelled out here rather than hidden inside the client, so that a
     * reviewer can see exactly what the device is asked to do.
     */
    fun writeTimeInterval(seconds: Double, onDone: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        scope.launch {
            try {
                val tenths = (seconds * 10).toInt()
                require(tenths in 1..packedMaxTenths) { "interval out of range" }
                c.writeLoggingEnabled(false)
                c.writeConfig(Pmtk.ConfigField.TIME_INTERVAL, tenths.toString())
                c.writeLoggingEnabled(true)
                _message.value = "Log interval set to ${seconds}s"
                refreshConfig()
            } catch (e: Exception) {
                _message.value = "Interval write failed: ${e.message}"
            } finally {
                onDone()
            }
        }
    }

    /**
     * Write the log format register.
     *
     * The only way to obtain NSAT/HDOP/VDOP, which are absent from every
     * existing dump. Costs flash-hours: the record grows 42 to 48 bytes.
     */
    fun writeLogFormat(format: LogFormat, onDone: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        scope.launch {
            try {
                c.writeLoggingEnabled(false)
                c.writeConfig(
                    Pmtk.ConfigField.LOG_FORMAT,
                    "%08X".format(format.bits),
                )
                c.writeLoggingEnabled(true)
                _message.value = "Log format set to ${format.describe()}"
                refreshConfig()
            } catch (e: Exception) {
                _message.value = "Format write failed: ${e.message}"
            } finally {
                onDone()
            }
        }
    }

    private suspend fun refreshConfig() {
        val c = client ?: return
        val current = _connection.value as? ConnectionState.Connected ?: return
        val format = runCatching { c.queryLogFormat() }.getOrNull() ?: return
        val interval = runCatching { c.queryTimeIntervalSeconds() }.getOrNull() ?: return
        _connection.value = ConnectionState.Connected(
            current.info.copy(logFormat = format, timeIntervalSeconds = interval)
        )
    }

    private val packedMaxTenths = 65_535
}
