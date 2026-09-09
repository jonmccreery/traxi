package thru.taxi.traxi.session

import thru.taxi.traxi.bt.AclLink
import thru.taxi.traxi.bt.BluetoothSppTransport
import thru.taxi.traxi.bt.Bonding
import thru.taxi.traxi.bt.CompanionPairing
import thru.taxi.traxi.core.format.Bytes
import thru.taxi.traxi.core.format.GpsRollover
import thru.taxi.traxi.core.format.LogFormat
import thru.taxi.traxi.core.format.MtkLogParser
import thru.taxi.traxi.core.format.Quality
import thru.taxi.traxi.core.format.RecordingAudit
import thru.taxi.traxi.core.protocol.EraseGate
import thru.taxi.traxi.core.protocol.FlashDownloader
import thru.taxi.traxi.core.protocol.FlashEraser
import thru.taxi.traxi.core.protocol.Pmtk
import thru.taxi.traxi.core.protocol.PmtkClient
import thru.taxi.traxi.core.protocol.RingTranscript
import thru.taxi.traxi.core.protocol.Telemetry
import thru.taxi.traxi.core.protocol.TelemetryAssembler
import thru.taxi.traxi.core.transport.SimulatedLoggerTransport
import thru.taxi.traxi.core.transport.Transport
import thru.taxi.traxi.usb.UsbSerialTransport
import thru.taxi.traxi.data.DumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Bytes filled in the sector currently in flight, 0..[blockSizeBytes]. */
    val blockBytes: Int = 0,
    val blockSizeBytes: Int = 0,
    /** Smoothed throughput, so a slow Bluetooth read visibly moves. */
    val bytesPerSecond: Double = 0.0,
    /**
     * Rough seconds until the transfer ends, or null when there is nothing
     * honest to estimate from. Built on the write pointer, which is a hint and
     * not a bound -- good enough for a time estimate, never for a percentage
     * or a stopping rule.
     */
    val etaSeconds: Int? = null,
    /**
     * Radio links rebuilt to get past a device that stopped answering.
     *
     * Surfaced rather than hidden. A transfer that needed six link resets is a
     * different event from one that needed none, and a recovery the user cannot
     * see is indistinguishable from the app being mysteriously slow.
     */
    val linkResets: Int = 0,
)

/**
 * A heartbeat for the Live tab: proof that the receiver is producing position
 * fixes right now, not a stale snapshot. Keyed on fixes rather than raw NMEA
 * sentences, because a fix is what gets logged and is what the user is looking
 * to confirm. [pulse] increments on each fix so the UI can blink;
 * [lastFixAtNanos] lets it show "last fix Xs ago" and turn stale;
 * [fixesPerSecond] is the fix rate.
 */
data class LiveActivity(
    val lastFixAtNanos: Long = 0L,
    val fixesPerSecond: Double = 0.0,
    val pulse: Long = 0L,
)

/**
 * Ground-truth "is it actually recording?", read from the write pointer rather
 * than the status bit. The pointer is bytes that have genuinely landed in flash
 * -- the same number a dump-and-compare checks -- so it cannot misreport the way
 * the status bit does right after a reconnect. Advancing means fixes are being
 * written; frozen across several probes means they are not.
 */
data class RecordingActivity(
    val bytesSinceConnect: Long = 0,
    /** When the pointer last increased; 0 until the first advance is seen. */
    val lastAdvanceAtNanos: Long = 0L,
    /** How many times the pointer has been sampled since connecting. */
    val probes: Int = 0,
    val confirmedEver: Boolean = false,
    /** When the pointer was last actually read, as distinct from last moved. */
    val lastProbeAtNanos: Long = 0L,
    /**
     * Whether the most recent read found the pointer had moved.
     *
     * The indicator is driven by this rather than by a clock, because over
     * Bluetooth the app now probes rarely -- and "we have not looked lately"
     * must never be allowed to render as "it is not recording". Only a sample
     * that looked and found nothing may raise that alarm.
     */
    val lastProbeAdvanced: Boolean = false,
    /**
     * How long the pointer was observed over, for the most recent sample.
     *
     * A no-movement verdict is only meaningful across a window longer than the
     * configured log interval: a logger writing every 60 s has genuinely not
     * moved 30 s after connecting, and is perfectly healthy.
     */
    val lastProbeGapNanos: Long = 0L,
)

/**
 * Whether bytes are arriving, as distinct from whether a link exists.
 *
 * These are not the same thing, and a ride proved it: the radio link stayed up
 * for 39 minutes while the app sat frozen on a stream that had stopped
 * delivering. Android reported no disconnect because there was none, so the
 * ACL-disconnect broadcast -- the app's only teardown signal -- could not fire,
 * and every counter in the UI simply stopped with no explanation offered.
 *
 * This is the missing signal, and it is deliberately only a *signal*. Nothing
 * here tears a session down: a quiet link recovers on its own often enough that
 * killing it automatically was a regression once already. The job is to make
 * the state visible, and let the person holding the phone decide.
 */
data class LinkHealth(
    /** When the transport last returned bytes; 0 before the first read. */
    val lastBytesAtNanos: Long = 0L,
    /** Total reads that returned nothing since the last delivery. */
    val silentReads: Int = 0,
    /**
     * Set when the read loop has stopped for good, with the reason.
     *
     * The loop used to exit on a bare `break` -- no message, no state change,
     * nothing in the transcript. The session stayed "Connected" over a stream
     * that would never produce another byte.
     */
    val streamEnded: String? = null,
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
    /**
     * Whether this dump shows the logger silently stopping.
     *
     * Carried on the summary rather than computed in the UI because it is the
     * single most consequential thing a parse can discover: fixes that were
     * never recorded cannot be recovered by re-downloading, and nothing else in
     * the app can tell the user they lost a stretch of trip.
     */
    val recording: RecordingAudit.Report,
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
     * What the receiver is doing right now, decoded from the navigation
     * sentences the logger emits alongside its PMTK replies.
     *
     * Null until something has been decoded. Note that this describes the GPS
     * receiver, not the recorder: see [Telemetry] for why a perfect fix says
     * nothing about whether anything is being written to flash.
     */
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _liveActivity = MutableStateFlow(LiveActivity())
    val liveActivity: StateFlow<LiveActivity> = _liveActivity.asStateFlow()

    private val _recording = MutableStateFlow(RecordingActivity())
    val recording: StateFlow<RecordingActivity> = _recording.asStateFlow()

    private val _linkHealth = MutableStateFlow(LinkHealth())
    val linkHealth: StateFlow<LinkHealth> = _linkHealth.asStateFlow()

    // Write-pointer probe baseline. The pointer only ever grows while logging,
    // so a later sample above an earlier one is proof a fix was written.
    private var recordingBaseline: Long? = null
    private var lastProbedPointer: Long? = null

    /** When the current session connected, the first sample's starting edge. */
    private var connectedAtNanos: Long = 0L

    // Download throughput meter. Rate is smoothed because chunks arrive in
    // bursts -- ~30 ms apart over USB, several seconds apart over Bluetooth.
    //
    // Keyed on the in-flight block's fill, not on bytesDownloaded, because the
    // latter is a file *position*, not a wire counter. The fetch-new wrap probe
    // holds the position constant for a whole block, and the extend that
    // follows restarts it below the previous dump's size -- both of which read
    // as a dead or backwards link to a position-based meter, which is why fetch
    // showed no speed at all. blockBytes counts up within every block and
    // resets at each boundary or retry, so a drop below the last sample simply
    // starts a new count.
    private var rateLastBlockBytes = 0
    private var rateLastNanos = 0L
    private var rateEmaBps = 0.0

    /** Chunk arrivals seen since the meter was armed; gates the estimate. */
    private var rateSamples = 0

    // Whole-run progress, which is what the time estimate is actually built on.
    // The EMA above answers "is the link flowing right now" and is deliberately
    // twitchy; it is the wrong statistic for predicting a finish, for two
    // reasons. It only ever measures chunk-to-chunk *within* a block, so it
    // never sees the request round trip and flash read between blocks -- real
    // seconds that always inflate it. And RFCOMM hands over a backlog in a
    // clump at each block start, so it spikes exactly where a fresh sector
    // begins. Elapsed time against file position has neither problem: every
    // cost is inside it, including retries and rebuilt links.
    private var etaStartNanos = 0L
    private var etaStartBytes = 0

    // Where the running download is expected to end, for the ETA. The pointer
    // target is right for an unwrapped log (the read stops just past the write
    // frontier) and for every fetch-new; a wrapped log has no unwritten sectors
    // and runs to the end of flash, which is what the fallback covers.
    private var etaPointerTargetBytes: Int? = null
    private var etaFlashTargetBytes: Int? = null

    // Fix-arrival window for the Live heartbeat. A sliding count over wall time,
    // not an inter-arrival rate: RFCOMM delivers a whole second of sentences in
    // one burst, so the sub-millisecond gaps within a burst are meaningless and
    // an interval-based rate reads absurdly high (hundreds/sec).
    private val fixTimestamps = ArrayDeque<Long>()

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

    /**
     * Serialises everything that reads from or writes to the transport.
     *
     * Device operations were kept apart by convention rather than by
     * construction: each one checked [downloadJob] and [_erase] on the way in.
     * Two of them never did. `writeTimeInterval` and `writeLogFormat` had no
     * busy check at all, so a settings change during a transfer ran straight
     * into it, and two settings changes could run into each other.
     *
     * That is not a near-miss. [PmtkClient] holds a single `readBuffer`, one
     * `NmeaLineAssembler` and one queue of pending sentences, all shared by
     * every caller and none of them synchronised, and this scope runs on
     * `Dispatchers.Default`. Two coroutines in there at once read into the same
     * array and feed the same assembler, so both sides see a corrupted stream:
     * the transfer loses bytes and the config write loses its acknowledgement.
     *
     * The telemetry loop makes this lock load-bearing rather than merely
     * correct. It is the first thing in this app that reads while the user is
     * doing nothing, so the old assumption -- that nothing runs between user
     * actions -- is no longer true even in the ordinary case.
     *
     * Only top-level, user-initiated operations take this lock. Helpers they
     * call -- `refreshConfig`, `withLoggingPaused` -- must not, because the
     * mutex is not reentrant and the caller already holds it.
     */
    private val readLock = Mutex()

    private var transport: Transport? = null
    private var client: PmtkClient? = null
    private var downloadJob: Job? = null
    private var telemetryJob: Job? = null
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

                // **Only the socket open may be blamed on a stale link key.**
                //
                // This used to wrap the interrogation too, and that cost a
                // working pairing: on 2026-09-08 the logger accepted the
                // RFCOMM connection and streamed NMEA normally while refusing
                // to answer PMTK605 -- the wedged command path of §15 -- and
                // the query timeout landed here, was read as a bad link key,
                // and deleted a bond that was entirely healthy. Re-pairing then
                // failed nine times because the device, not the key, was the
                // problem, and recovering needed physical access to the logger.
                //
                // A live socket is proof the key was good. Past this point a
                // failure means the device is not answering, which this remedy
                // cannot fix and can only make worse.
                // Do not race Android's ACL reuse window. A socket opened while
                // the previous radio link is still up inherits that link, and
                // with it any wedged session state -- which is how a reconnect
                // four seconds after a disconnect got silence from a logger
                // that was streaming NMEA. See [AclLink].
                AclLink.awaitDown(context, device, note = transcript::note)

                val opened = try {
                    BluetoothSppTransport(device).also { it.open() }
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

                    BluetoothSppTransport(device).also { it.open() }
                }

                // Outside the stale-key handler on purpose: whatever happens in
                // here, the pairing stays untouched.
                openWith(opened, simulated = false)
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

    /**
     * Run a device operation with exclusive use of the transport.
     *
     * Every user-initiated operation goes through here so that none of them can
     * overlap the telemetry loop. Use `return@launchExclusive` inside the body.
     */
    private fun launchExclusive(block: suspend () -> Unit): Job =
        scope.launch { readLock.withLock { block() } }

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

        if (!simulated && t.description.startsWith("bluetooth ")) {
            connectedAddress = t.description.substringAfterLast(' ')
            connectedAddress?.let { watchLink(it) }
        } else if (!simulated && t.description.startsWith("usb ")) {
            watchUsbDetach()
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

        // Seed the recording probe from the connect-time pointer, so the first
        // sample can already show whether fixes have been written since.
        recordingBaseline = pointer
        lastProbedPointer = pointer
        connectedAtNanos = System.nanoTime()
        _recording.value = RecordingActivity()

        // Tell Android this session is user-visible work. Without it the app is
        // an ordinary background process between actions, and the cached-app
        // freezer suspends the read loop within minutes -- which stalls the
        // stream while leaving the radio link up, the exact failure that made a
        // whole ride's telemetry disappear. See [LinkService].
        //
        // A failure here is never fatal to the session, but it must not be
        // silent either: it means the protection against that freeze is not in
        // place, and the next stall would look like the same mystery again.
        runCatching { thru.taxi.traxi.service.LinkService.start(context) }
            .onFailure {
                transcript.note(
                    "WARNING: could not start the foreground link service (${it.message}). " +
                        "Android may suspend this app and stall the link."
                )
            }

        startTelemetry(c, firmware.needsWeekRollover)
    }

    /**
     * Begin decoding the navigation stream.
     *
     * Two sources feed the same assembler, because neither alone is enough.
     * The observer picks up sentences parsed during any other operation, which
     * covers a three-hour download for free and adds no reads. The loop covers
     * the rest of the time: nothing else touches the transport between user
     * actions, so without it the stream would sit in the transport's bounded
     * queue until it overflowed and was dropped.
     */
    /** Arm the throughput meter and the ETA targets for a starting download. */
    private fun resetRateMeter() {
        rateLastBlockBytes = 0
        rateLastNanos = System.nanoTime()
        rateEmaBps = 0.0
        rateSamples = 0
        etaStartNanos = 0L
        etaStartBytes = 0

        val info = (_connection.value as? ConnectionState.Connected)?.info
        val block = FlashDownloader.DEFAULT_BLOCK_SIZE
        // The freshest pointer wins: the telemetry loop keeps probing it while
        // idle, and the connect-time value goes stale as the logger records.
        // Two extra blocks for the unwritten-sector stopping rule.
        etaPointerTargetBytes = (lastProbedPointer ?: info?.writePointer)?.let {
            (((it.toInt() + block - 1) / block) + 2) * block
        }
        etaFlashTargetBytes = info?.flash?.bytes?.toInt()
    }

    /** Fold one progress callback into [_download], including a smoothed rate. */
    private fun applyDownloadProgress(p: FlashDownloader.Progress) {
        val now = System.nanoTime()

        // A transfer holds the read lock for its whole duration, so the
        // telemetry loop -- the only other thing that reports bytes arriving --
        // is starved until it finishes. Without this, a perfectly healthy
        // multi-hour download would leave the link-silence clock running from
        // before it started and the Live tab would report a dead link while
        // data poured in. These chunks *are* the evidence the link is alive.
        _linkHealth.value = _linkHealth.value.copy(
            lastBytesAtNanos = now,
            silentReads = 0,
        )
        val dt = (now - rateLastNanos) / 1e9
        val db = if (p.blockBytes >= rateLastBlockBytes) {
            p.blockBytes - rateLastBlockBytes
        } else {
            p.blockBytes
        }
        rateLastBlockBytes = p.blockBytes

        if (rateSamples == 0) {
            // The gap between "the transfer started" and the first chunk is not
            // a transfer rate. It is a request round trip, the device's own
            // flash read, and whatever wait the read lock imposed -- tens of
            // seconds over Bluetooth, against the two or three a chunk really
            // takes. Dividing the first chunk by all of that produced a rate an
            // order of magnitude too low, and because the EMA had nothing to
            // blend with it was adopted whole: the first sector of a download
            // advertised three hours where the second, by then converged,
            // showed twelve minutes. Start the clock at the first chunk instead
            // of measuring against a starting gun.
            rateLastNanos = now
            rateSamples = 1
            etaStartNanos = now
            etaStartBytes = p.bytesDownloaded
        } else if (dt > 0 && db > 0) {
            val inst = db / dt
            rateEmaBps = if (rateEmaBps == 0.0) inst else 0.4 * inst + 0.6 * rateEmaBps
            rateLastNanos = now
            rateSamples++
        }

        // A read that has passed the pointer target is the wrapped-log case and
        // will run to the end of flash instead. If it passes that too -- or
        // neither figure is known -- there is nothing left to estimate honestly.
        val target = etaPointerTargetBytes?.takeIf { p.bytesDownloaded < it }
            ?: etaFlashTargetBytes?.takeIf { p.bytesDownloaded < it }
        // Built on whole-run progress, not the twitchy EMA, so a burst at a
        // sector boundary cannot talk the estimate down and the between-block
        // overhead is counted rather than ignored. It settles as the run goes
        // on instead of swinging with every sector.
        //
        // Zero advance is a real state, not a divide-by-zero to dodge: during
        // the wrap probe the file position is deliberately pinned, and there is
        // genuinely nothing to estimate from until the extend begins.
        val elapsed = (now - etaStartNanos) / 1e9
        val advanced = p.bytesDownloaded - etaStartBytes
        val progressBps = if (elapsed > 0 && advanced > 0) advanced / elapsed else 0.0
        // Still withheld until a few arrivals have been seen: one interval over
        // a bursty link is not a throughput, and a wrong number shown
        // confidently is worse than none -- it is the figure someone decides
        // whether to wait on.
        val eta = if (target != null && progressBps > 0 && rateSamples >= MIN_RATE_SAMPLES) {
            ((target - p.bytesDownloaded) / progressBps).toInt()
        } else null

        _download.value = _download.value.copy(
            bytesDownloaded = p.bytesDownloaded,
            sectorsRead = p.sectorsRead,
            retries = p.retries,
            blockBytes = p.blockBytes,
            blockSizeBytes = p.blockSizeBytes,
            bytesPerSecond = rateEmaBps,
            etaSeconds = eta,
        )
    }

    /**
     * Fold one telemetry read into [_linkHealth].
     *
     * Bytes, not fixes. The Live heartbeat already reports fixes, but a fix
     * needs a receiver with a sky view, so its absence is ambiguous -- indoors
     * it means nothing is wrong. Bytes are unambiguous: this logger streams
     * NMEA continuously whenever it is powered and connected, so a link that
     * delivers no bytes at all is a link that has stopped working, wherever it
     * is.
     */
    private fun noteReadResult(bytes: Int) {
        val cur = _linkHealth.value
        _linkHealth.value = if (bytes > 0) {
            cur.copy(lastBytesAtNanos = System.nanoTime(), silentReads = 0)
        } else {
            // Seed the clock on the first read so a stream that never delivers
            // anything still shows an honest, climbing silence.
            cur.copy(
                lastBytesAtNanos = if (cur.lastBytesAtNanos == 0L) System.nanoTime()
                    else cur.lastBytesAtNanos,
                silentReads = cur.silentReads + 1,
            )
        }
    }

    /**
     * Record that the read loop has stopped, loudly.
     *
     * Deliberately does *not* disconnect. A dead read loop and a dead link are
     * different things, and this app has already learned once what tearing down
     * a recoverable connection costs. Say so, and leave the decision to the
     * person holding the phone.
     */
    private fun endStream(reason: String) {
        transcript.note("STREAM ENDED: $reason")
        _linkHealth.value = _linkHealth.value.copy(streamEnded = reason)
    }

    /** Record that a position fix just arrived, for the Live heartbeat. */
    private fun noteFixArrived() {
        val now = System.nanoTime()
        val rate: Double
        synchronized(fixTimestamps) {
            fixTimestamps.addLast(now)
            val cutoff = now - FIX_RATE_WINDOW_NANOS
            while (fixTimestamps.isNotEmpty() && fixTimestamps.first() < cutoff) {
                fixTimestamps.removeFirst()
            }
            rate = fixTimestamps.size * 1e9 / FIX_RATE_WINDOW_NANOS
        }
        _liveActivity.value = LiveActivity(
            lastFixAtNanos = now,
            fixesPerSecond = rate,
            pulse = _liveActivity.value.pulse + 1,
        )
    }

    /** Fold one write-pointer sample into the recording-confirmation state. */
    private fun noteWritePointer(ptr: Long, now: Long) {
        val base = recordingBaseline ?: ptr.also { recordingBaseline = it }
        val prev = lastProbedPointer
        val advanced = prev != null && ptr > prev
        lastProbedPointer = ptr
        val cur = _recording.value
        // The window this sample judges over runs from the previous look --
        // connect time for the first one, which openWith seeds.
        val since = if (cur.lastProbeAtNanos != 0L) cur.lastProbeAtNanos else connectedAtNanos
        _recording.value = cur.copy(
            bytesSinceConnect = (ptr - base).coerceAtLeast(0),
            lastAdvanceAtNanos = if (advanced) now else cur.lastAdvanceAtNanos,
            probes = cur.probes + 1,
            confirmedEver = cur.confirmedEver || advanced,
            lastProbeAtNanos = now,
            lastProbeAdvanced = advanced,
            lastProbeGapNanos = if (since != 0L) now - since else 0L,
        )
    }

    private fun startTelemetry(c: PmtkClient, needsRollover: Boolean) {
        val assembler = TelemetryAssembler(
            if (needsRollover) GpsRollover.AXN_130B else GpsRollover.NONE
        )
        synchronized(fixTimestamps) { fixTimestamps.clear() }
        _liveActivity.value = LiveActivity()
        _linkHealth.value = LinkHealth()
        c.onSentence = { sentence ->
            if (assembler.accept(sentence)) _telemetry.value = assembler.current
            // A fix is one GGA epoch (~1 Hz) that actually resolved a position.
            // Counting GGA rather than every sentence gives an honest fix rate,
            // and a GGA with quality 0 -- receiver alive but no fix -- correctly
            // does not count, so the heartbeat goes stale when fixes stop.
            if (sentence.type.takeLast(3) == "GGA") {
                val t = assembler.current
                if (t.hasPosition && (t.fixQuality ?: 0) > 0) noteFixArrived()
            }
        }

        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            // Start the clock a full interval back is wrong: probing immediately
            // on the first iteration hits the link at its least settled moment,
            // right after connect, while openWith's own flash/pointer queries may
            // still be echoing. Wait a full interval before the first probe.
            var lastProbeNanos = System.nanoTime()
            var bytesSincePump = 0
            // How often this link tolerates being asked. Over Bluetooth the
            // query is destabilising, so the steady rate is rare; the first
            // probe still comes quickly, because a recording indicator that
            // takes ten minutes to say anything is not an indicator.
            val steadyProbeNanos =
                (transport?.writePointerProbeIntervalMillis ?: 600_000L) * 1_000_000
            // Pre-flight: one early probe, timed off the log interval rather
            // than a flat constant, because the earliest an advance can
            // possibly be seen is one interval after the connect-time baseline.
            // Twice the interval clears the UI's own "long enough to judge"
            // threshold with room to spare, so the card resolves to a real
            // answer instead of sitting on "checking…" while the user wonders.
            val logIntervalNanos = (((_connection.value as? ConnectionState.Connected)
                ?.info?.timeIntervalSeconds ?: 10.0).coerceAtLeast(1.0) * 1e9).toLong()
            val firstProbeNanos =
                maxOf(logIntervalNanos * 2, MIN_FIRST_WRITE_PROBE_NANOS)
            var probeIntervalNanos = minOf(firstProbeNanos, steadyProbeNanos)
            while (isActive) {
                // A read while another operation holds the lock would take
                // bytes belonging to that operation.
                val read = try {
                    readLock.withLock { c.pump(TELEMETRY_READ_MILLIS) }
                } catch (e: Exception) {
                    transcript.note("telemetry read threw: ${e.message ?: e.toString()}")
                    endStream("the read loop stopped: ${e.message ?: e.toString()}")
                    break
                }
                // A negative read means the stream is finished, not merely
                // quiet. This used to be a bare `break`: the loop vanished
                // without a word and the session went on reporting "Connected"
                // over a stream that would never produce another byte. Whatever
                // else is wrong, the app must never again go silent about it.
                if (read < 0) {
                    endStream("the logger's data stream ended")
                    break
                }
                noteReadResult(read)
                if (read > 0) bytesSincePump += read

                // Periodically confirm fixes are actually landing in flash by
                // reading the write pointer. This is the ground-truth recording
                // signal: the status bit misreports right after a reconnect, but
                // the pointer only grows when a fix is written.
                //
                // Only probe a link that is actually talking. Firing the query
                // into a silent link buys nothing but nine seconds of doomed
                // retries every interval -- the wasteful "99 retries" spin from
                // a wedged socket -- and a silence this loop could detect is one
                // the Live heartbeat is *already* showing, stale and red. We do
                // NOT tear the session down on silence: this logger goes quiet in
                // RFCOMM bursts, at the edge of range, and (if the vibration
                // sensor is ever enabled) on its 10-minute sleep, and it comes
                // back on its own. A teardown here made those recoverable stalls
                // fatal and forced a fragile manual reconnect. A genuine dead
                // link still surfaces -- through the ACL-disconnect broadcast
                // (watchLink), which is the real signal, not a guess from silence.
                val now = System.nanoTime()
                if (now - lastProbeNanos >= probeIntervalNanos && bytesSincePump > 0) {
                    lastProbeNanos = now
                    bytesSincePump = 0
                    // After the first, settle to whatever this link tolerates.
                    probeIntervalNanos = steadyProbeNanos
                    val ptr = try {
                        readLock.withLock { c.queryWritePointer() }
                    } catch (e: Exception) {
                        null
                    }
                    if (ptr != null) {
                        noteWritePointer(ptr, System.nanoTime())
                    } else {
                        // An unanswered probe is how a Bluetooth link dies on
                        // this hardware: the query wedges the logger's radio
                        // firmware and the disconnect follows within a second.
                        // Recording it makes that sequence legible in the
                        // transcript instead of looking like a random drop.
                        transcript.note(
                            "write-pointer probe went unanswered; this logger's " +
                                "Bluetooth link often drops immediately after one"
                        )
                    }
                }

                // Release the lock briefly between reads so an operation
                // waiting for it starts promptly rather than queueing behind
                // another full read window.
                delay(TELEMETRY_IDLE_MILLIS)
            }
        }
    }

    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
        client?.onSentence = null
        _telemetry.value = null
        _liveActivity.value = LiveActivity()
        _recording.value = RecordingActivity()
        _linkHealth.value = LinkHealth()
        recordingBaseline = null
        lastProbedPointer = null
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

    /**
     * Notice an OTG cable being pulled.
     *
     * This used to be left to `noticeIfLinkDied()`, on the reasoning that a USB
     * unplug "surfaces as a failed transfer". It does -- but only if there *is*
     * a transfer. An idle session sat there reporting "Connected" to a device
     * that was no longer physically attached, and the first thing to fail was
     * whatever the user tried next, with an error that pointed nowhere near a
     * cable. Android broadcasts the detach, so there is no reason to wait.
     *
     * Matched on vendor and product id rather than on the device object, which
     * does not compare usefully across processes.
     */
    private fun watchUsbDetach() {
        stopWatchingLink()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val gone: android.hardware.usb.UsbDevice? =
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                if (gone != null && gone.vendorId != UsbSerialTransport.VENDOR_MEDIATEK) return
                onLinkLost("The logger was unplugged.")
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
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
        stopTelemetry()
        // The session is over, so the foreground notification must go with it.
        // LinkService also retires itself when the connection flow leaves
        // Connected; this covers the paths that close the transport first.
        runCatching { thru.taxi.traxi.service.LinkService.stop(context) }
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

        // The socket opened and the device then ignored us. That is the logger's
        // command path wedged (§15) -- not pairing, not range, not power. Saying
        // so matters: the pairing advice below is actively wrong here, and
        // following it costs a bond that has to be re-entered by hand.
        val looksWedged = detail.contains("no matching response", ignoreCase = true) ||
            detail.contains("no response", ignoreCase = true)

        return if (looksWedged) {
            "The logger accepted the connection but did not answer.\n\n" +
                "It is powered on and in range — the link opened. Its command " +
                "handling has stopped responding, which this logger does " +
                "occasionally after a long session.\n\n" +
                "Switch the logger off and on again, then connect. Your pairing " +
                "is fine and does not need redoing. Recording is unaffected, and " +
                "USB works regardless — it needs no pairing at all."
        } else if (looksLikeNoSpp) {
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
        downloadJob = launchExclusive {
            try {
                val previous = dumps.read(source)
                _download.value = DownloadState(
                    running = true,
                    bytesDownloaded = previous.size,
                    resumedFrom = previous.size,
                )
                resetRateMeter()

                val target = dumps.newDumpFile()
                val result = downloadWithLinkRecovery { active ->
                    FlashDownloader(active).downloadIncremental(
                        previous = previous,
                        onProgress = ::applyDownloadProgress,
                        shouldContinue = { !cancelRequested },
                    )
                }

                // Never keep an empty image. It is not resumable, it parses to
                // a meaningless "0 fixes" summary presented as a result, and it
                // clutters the list that the real dumps live in.
                if (result.image.isEmpty()) {
                    // A cancel during the wrap probe lands here too, and blaming
                    // the logger for a stop the user asked for is a lie.
                    _message.value = if (cancelRequested) {
                        "Cancelled — nothing had been fetched; ${source.name} is untouched"
                    } else {
                        "Nothing was read — ${result.failure ?: "the logger did not respond"}"
                    }
                    return@launchExclusive
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
                    // Cancelled mid-extend: "already up to date" would present a
                    // stop the user requested as a checked fact about the logger.
                    cancelRequested && !result.isComplete ->
                        "Cancelled — ${result.image.size / 1024} KB saved and resumable"
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
                // A link cycle stops telemetry against the client it discards;
                // bring it back on whichever client the transfer ended with.
                ensureTelemetryRunning()
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
        downloadJob = launchExclusive {
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
                resetRateMeter()

                val result = downloadWithLinkRecovery { active ->
                    FlashDownloader(active).download(
                        existing = existing,
                        resumeFrom = resumeFrom,
                        onProgress = ::applyDownloadProgress,
                        shouldContinue = { !cancelRequested },
                    )
                }

                if (result.image.isEmpty()) {
                    // Over Bluetooth the first block is minutes long, so a
                    // cancel before it completes is a real path, not a race.
                    _message.value = if (cancelRequested) {
                        "Cancelled — nothing had been read yet"
                    } else {
                        "Nothing was read — ${result.failure ?: "the logger did not respond"}"
                    }
                    return@launchExclusive
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
                // A link cycle stops telemetry against the client it discards;
                // bring it back on whichever client the transfer ended with.
                ensureTelemetryRunning()
                onFinished()
            }
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    /**
     * Rebuild the radio link mid-transfer, keeping the session otherwise intact.
     *
     * This is the cure for the §15 wedge, and it is only ever run against an
     * **observed** one: three consecutive block attempts that each returned zero
     * bytes. That distinction is the whole licence for doing it automatically.
     * Twice before, this app acted on a *guess* about link state -- tearing down
     * a merely-quiet connection, and deleting a bond because a query timed out
     * -- and both were regressions. Reacting to a measured silence with a
     * non-destructive remedy, during a transfer the user explicitly started, is
     * a different kind of act.
     *
     * Bluetooth only. USB shows none of this and has nothing to reset.
     *
     * Returns the new client, or null if the link could not be rebuilt -- in
     * which case the caller keeps the bytes it already has, which is the
     * pre-existing behaviour and still the right one.
     */
    private suspend fun cycleLink(): PmtkClient? {
        val address = connectedAddress ?: return null
        val device = runCatching { pairing.deviceFor(address) }.getOrNull() ?: return null

        transcript.note("cycling the radio link to clear a wedged read session")
        // Stop telemetry against the client that is about to be discarded; it
        // is restarted on the new one once the transfer ends.
        stopTelemetry()
        runCatching { transport?.close() }
        transport = null
        client = null

        // The whole point: a *new* ACL is what clears the wedge, and a socket
        // opened while the old link lingers would inherit it.
        AclLink.awaitDown(context, device, note = transcript::note)

        return try {
            val t = BluetoothSppTransport(device)
            t.open()
            transport = t
            val c = PmtkClient(t, transcript)
            client = c
            transcript.note("radio link rebuilt; resuming the transfer")
            c
        } catch (e: Exception) {
            transcript.note("could not rebuild the radio link: ${e.message}")
            null
        }
    }

    /**
     * Whether a link cycle is worth attempting for the transport in use.
     *
     * A simulated or USB session that stops answering has a real problem, and
     * reconnecting would only hide it.
     */
    private fun linkCycleApplies(): Boolean =
        !isSimulated && transport?.description?.startsWith("bluetooth ") == true

    /**
     * Run a transfer, rebuilding the link each time the device stops answering.
     *
     * The resume machinery this leans on was built for a different reason --
     * "resume, never restart", so that a dropped link could not cost 25 minutes
     * -- and turns out to be exactly what is needed here. Each recovered
     * segment picks up at the write frontier, so nothing is re-read and nothing
     * is lost.
     *
     * Bounded twice over: a cap on cycles, and a hard requirement that every
     * cycle *gain bytes*. A cycle that recovers nothing ends the transfer with
     * whatever is already in hand, so this cannot spin.
     */
    private suspend fun downloadWithLinkRecovery(
        firstPass: suspend (PmtkClient) -> FlashDownloader.Result,
    ): FlashDownloader.Result {
        val start = client ?: throw IllegalStateException("Not connected")
        var result = firstPass(start)
        var cycles = 0

        while (
            result.stoppedUnanswered &&
            !cancelRequested &&
            cycles < MAX_LINK_CYCLES &&
            linkCycleApplies()
        ) {
            val before = result.image.size
            val c = cycleLink() ?: break
            cycles++
            _download.value = _download.value.copy(linkResets = cycles)

            // The frontier is block-aligned: only whole blocks are ever written
            // to the image, so this is a legal resume offset by construction.
            val continued = FlashDownloader(c).download(
                existing = result.image,
                resumeFrom = result.image.size,
                onProgress = ::applyDownloadProgress,
                shouldContinue = { !cancelRequested },
            )

            // Carry the earlier segments' damage and retries forward; they
            // describe the same image and must not be forgotten by a later pass
            // that happened to be clean.
            result = continued.copy(
                retries = result.retries + continued.retries,
                damagedRanges = result.damagedRanges + continued.damagedRanges,
            )

            if (result.image.size <= before) {
                transcript.note(
                    "link cycle $cycles recovered no further bytes; " +
                        "stopping with ${Bytes.describe(result.image.size)}"
                )
                break
            }
            transcript.note(
                "link cycle $cycles recovered " +
                    Bytes.describe(result.image.size - before)
            )
        }

        if (result.stoppedUnanswered && cycles >= MAX_LINK_CYCLES) {
            transcript.note(
                "gave up after $MAX_LINK_CYCLES link cycles; the bytes so far are saved"
            )
        }
        return result
    }

    /** Restart telemetry after a transfer, on whichever client survived it. */
    private fun ensureTelemetryRunning() {
        if (telemetryJob?.isActive == true) return
        val c = client ?: return
        val info = (_connection.value as? ConnectionState.Connected)?.info ?: return
        startTelemetry(c, info.needsWeekRollover)
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
                    recording = RecordingAudit.of(result.stats, result.fixes),
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
        val pointer = runCatching { readLock.withLock { c.queryWritePointer() } }.getOrNull()
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

        launchExclusive {
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
                    return@launchExclusive
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
     * Wrap [PmtkClient.withLoggingPaused] and surface a restore failure.
     *
     * The guarantee itself lives in the client, where it is tested. This adds
     * only the user-facing half: telling them, in the strongest terms the app
     * has, that the device is not recording.
     */
    private suspend fun withLoggingPaused(c: PmtkClient, body: suspend () -> Unit) {
        c.withLoggingPaused(
            onRestoreFailed = { failure ->
                _message.value =
                    "The logger is NOT recording — re-enabling it failed ($failure). " +
                        "Use Resume logging on the Config tab, or power-cycle the device."
            },
            body = body,
        )
    }

    /**
     * Turn logging on or off explicitly.
     *
     * The recovery path when something has left the device not recording, and
     * the reason the log-status row in the UI is decoded rather than raw: a
     * state the user can see is a state they can fix.
     */
    fun setLogging(enabled: Boolean, onDone: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        if (downloadJob?.isActive == true || _erase.value.running) {
            _message.value = "The logger is busy"
            onDone()
            return
        }
        launchExclusive {
            try {
                c.writeLoggingEnabled(enabled)
                _message.value = if (enabled) "Logging resumed" else "Logging paused"
                refreshConfig()
            } catch (e: Exception) {
                _message.value = "Could not ${if (enabled) "resume" else "pause"} logging: ${e.message}"
                noticeIfLinkDied()
            } finally {
                onDone()
            }
        }
    }

    /**
     * Write the log interval.
     *
     * Logging is disabled first and restored afterwards, and the whole sequence
     * is spelled out here rather than hidden inside the client, so that a
     * reviewer can see exactly what the device is asked to do.
     */
    fun writeTimeInterval(seconds: Double, onDone: () -> Unit = {}) {
        val c = client ?: run { _message.value = "Not connected"; return }
        launchExclusive {
            try {
                val tenths = (seconds * 10).toInt()
                require(tenths in 1..packedMaxTenths) { "interval out of range" }
                withLoggingPaused(c) {
                    c.writeConfig(Pmtk.ConfigField.TIME_INTERVAL, tenths.toString())
                }
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
        launchExclusive {
            try {
                withLoggingPaused(c) {
                    c.writeConfig(
                        Pmtk.ConfigField.LOG_FORMAT,
                        "%08X".format(format.bits),
                    )
                }
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
        // Re-read the log status too. Without this the recording indicator kept
        // whatever it said at connect, so pausing or resuming logging changed
        // the device and not the one row in the UI that reports it.
        val status = runCatching { c.queryLogStatus() }.getOrDefault(current.info.logStatus)
        _connection.value = ConnectionState.Connected(
            current.info.copy(
                logFormat = format,
                timeIntervalSeconds = interval,
                logStatus = status,
            )
        )
    }

    private val packedMaxTenths = 65_535

    /**
     * How long one telemetry read may hold [readLock].
     *
     * This is the worst-case wait an operation can face before it starts, so it
     * is short. The navigation stream is roughly 1 Hz, so a quarter second
     * misses nothing that will not still be there next time round.
     */
    private val TELEMETRY_READ_MILLIS = 250L

    /** Lock-free gap between reads, so a waiting operation gets in promptly. */
    private val TELEMETRY_IDLE_MILLIS = 50L

    /**
     * Window for the Live tab's fix-rate readout. Four seconds gives a steady
     * number at the ~1 Hz fix cadence without lagging a real stall for long.
     */
    private val FIX_RATE_WINDOW_NANOS = 4_000_000_000L

    /**
     * Floor on the pre-flight write-pointer probe.
     *
     * The pre-flight itself is timed at twice the configured log interval --
     * the earliest point a healthy log is certain to have advanced past the
     * connect-time baseline. This floor only stops a very short interval from
     * turning that into a query within a second or two of connecting, when the
     * link is at its least settled.
     *
     * The steady rate afterwards is the link's own business; see
     * [Transport.writePointerProbeIntervalMillis]. The pre-flight is worth its
     * one probe regardless, because it is what turns the recording indicator
     * from "unknown" into a checked fact while the user is still holding the
     * phone and can act on it.
     */
    private val MIN_FIRST_WRITE_PROBE_NANOS = 10_000_000_000L

    /**
     * Chunk arrivals required before a time estimate is shown.
     *
     * Four gives three measured intervals -- roughly fifteen seconds over
     * Bluetooth -- which is enough for the EMA to shake off a single fast or
     * slow arrival. RFCOMM delivers in bursts, so one interval is never a
     * throughput.
     */
    private val MIN_RATE_SAMPLES = 4

    /**
     * Radio links a single transfer may rebuild before giving up.
     *
     * Sized for the worst case actually observed: on 2026-09-07 this logger
     * managed one 64 KB block before it stopped answering, and a full 5.4 MB
     * image is 84 blocks. A cycle costs ten to fifteen seconds against a block's
     * two and a half minutes, so even one per block is a rounding error on a
     * transfer that was already going to take hours -- and the requirement that
     * every cycle gain bytes is what actually bounds this.
     */
    private val MAX_LINK_CYCLES = 60
}
