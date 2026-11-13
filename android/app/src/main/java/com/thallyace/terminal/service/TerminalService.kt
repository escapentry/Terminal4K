package com.thallyace.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.thallyace.terminal.MainActivity
import com.thallyace.terminal.R
import com.thallyace.terminal.model.RootAccessState
import com.thallyace.terminal.model.SessionInfo
import com.thallyace.terminal.model.SessionStatus
import com.thallyace.terminal.pty.PtyNative
import com.thallyace.terminal.terminal.TerminalBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class TerminalService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService
    }

    private val binder = LocalBinder()

    private val _sessionInfo = MutableStateFlow(SessionInfo())
    val sessionInfo = _sessionInfo.asStateFlow()

    private val _bufferRevision = MutableStateFlow(0L)
    val bufferRevision = _bufferRevision.asStateFlow()

    private val _bellEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bellEvents = _bellEvents.asSharedFlow()

    @Volatile
    private var masterFd: Int = -1

    @Volatile
    private var childPid: Int = -1

    @Volatile
    private var activeSessionId: Long = 0L

    @Volatile
    private var isElevatedSubshell: Boolean = false

    @Volatile
    private var rootRequestInFlight: Boolean = false

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "com.thallyace.terminal-pty-writer").apply { isDaemon = true }
    }

    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "com.thallyace.terminal-pty-control").apply { isDaemon = true }
    }

    val buffer = TerminalBuffer(
        initialCols = 80,
        initialRows = 24,
        maxScrollback = 5000,
        onResponse = { bytes -> writeInput(bytes) },
        onBell = { _bellEvents.tryEmit(Unit) },
        onUpdated = { _bufferRevision.update { it + 1L } }
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        ensureShellStarted()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            terminateSession()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        ensureShellStarted()
        return START_STICKY
    }

    private fun ensureShellStarted() {
        if (_sessionInfo.value.status == SessionStatus.CONNECTING && masterFd < 0) {
            startShellSession(buffer.cols, buffer.rows)
        }
    }

    fun startShellSession(
        cols: Int,
        rows: Int,
        shellOverride: String? = null,
        elevatedRoot: Boolean = false
    ) {
        synchronized(this) {
            if (_sessionInfo.value.status == SessionStatus.RUNNING && masterFd >= 0) {
                return
            }

            val uid = PtyNative.getUid()
            val detectedSu = PtyNative.findSuPath()
            val shellPath = shellOverride ?: resolveShellPath()
            val interactiveFlag = (shellOverride == null)
            val rootState = when {
                uid == 0 || elevatedRoot -> RootAccessState.ROOT_GRANTED
                detectedSu != null -> RootAccessState.USER_SU_AVAILABLE
                else -> RootAccessState.USER_NO_SU
            }

            val cwd = filesDir.absolutePath
            val deviceName = resolveDevicePromptName()
            ensureShellInitScript(cwd, deviceName)
            if (!elevatedRoot && buffer.cursorRow == 0 && buffer.cursorCol == 0) {
                buffer.clearAll()
            }
            val spawned = PtyNative.spawnShell(shellPath, cwd, deviceName, rows, cols, interactiveFlag)

            if (spawned == null || spawned.size < 2 || spawned[0] < 0) {
                val errNo = spawned?.getOrNull(2) ?: -1
                _sessionInfo.value = SessionInfo(
                    pid = -1,
                    status = SessionStatus.EXITED,
                    exitCode = 127,
                    startedAtMillis = System.currentTimeMillis(),
                    endedAtMillis = System.currentTimeMillis(),
                    cols = cols,
                    rows = rows,
                    shellPath = shellPath,
                    uid = uid,
                    suPath = detectedSu,
                    rootState = rootState,
                    finalShExited = false,
                    deviceName = deviceName
                )
                buffer.appendLocalLine("[Failed to start PTY shell: $shellPath (errno=$errNo)]")
                return
            }

            activeSessionId += 1L
            val sessionId = activeSessionId
            masterFd = spawned[0]
            childPid = spawned[1]
            isElevatedSubshell = elevatedRoot
            val effectiveUid = if (elevatedRoot) 0 else uid

            _sessionInfo.value = SessionInfo(
                pid = childPid,
                status = SessionStatus.RUNNING,
                exitCode = null,
                startedAtMillis = System.currentTimeMillis(),
                endedAtMillis = null,
                cols = cols,
                rows = rows,
                shellPath = shellPath,
                uid = effectiveUid,
                suPath = detectedSu,
                rootState = rootState,
                finalShExited = false,
                deviceName = deviceName
            )

            promoteToForeground()
            startReaderLoop(masterFd, childPid, sessionId)
        }
    }

    private fun resolveDevicePromptName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty()
        var model = Build.MODEL?.trim().orEmpty()
        if (model.isEmpty()) {
            model = Build.DEVICE?.trim().orEmpty()
        }
        if (manufacturer.isNotEmpty() && model.startsWith("$manufacturer ", ignoreCase = true)) {
            model = model.substring(manufacturer.length).trim()
        } else if (brand.isNotEmpty() && model.startsWith("$brand ", ignoreCase = true)) {
            model = model.substring(brand.length).trim()
        }
        val cleaned = model
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .ifEmpty { "android" }
        return cleaned.take(16)
    }

    private fun ensureShellInitScript(cwd: String, deviceName: String) {
        try {
            val rcFile = File(cwd, ".shrc")
            val script = """
                if [ "${'$'}(id -u 2>/dev/null || echo 1)" = "0" ]; then
                  PS1='$deviceName:# '
                else
                  PS1='$deviceName:$ '
                fi
                export PS1
                if [ -z "${'$'}TERMINAL_CLEARED" ]; then
                  export TERMINAL_CLEARED=1
                  clear 2>/dev/null || printf '\033[H\033[2J\033[3J'
                fi
                
            """.trimIndent()
            rcFile.writeText(script, StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun clearScreenAndPrompt() {
        buffer.clearAll()
        if (_sessionInfo.value.status == SessionStatus.RUNNING && masterFd >= 0) {
            writeInput("\r")
        }
    }

    fun requestRootShell() {
        if (rootRequestInFlight) return
        rootRequestInFlight = true
        controlExecutor.execute {
            try {
                val current = _sessionInfo.value
                if (current.rootState == RootAccessState.ROOT_GRANTED && current.status == SessionStatus.RUNNING) {
                    buffer.appendLocalLine("[Root shell is already active (uid=0)]")
                    return@execute
                }

                val suPath = PtyNative.findSuPath()
                if (suPath == null) {
                    _sessionInfo.update { it.copy(suPath = null, rootState = RootAccessState.USER_NO_SU) }
                    buffer.appendLocalLine("[Root unavailable: no su binary found]")
                    return@execute
                }

                _sessionInfo.update { it.copy(suPath = suPath, rootState = RootAccessState.CHECKING) }
                buffer.appendLocalLine("[Requesting root shell via $suPath...]")

                val rc = PtyNative.testSuRoot(suPath)
                if (rc == 0) {
                    synchronized(this@TerminalService) {
                        terminateSession()
                        _sessionInfo.value = _sessionInfo.value.copy(
                            status = SessionStatus.CONNECTING,
                            exitCode = null,
                            endedAtMillis = null
                        )
                        startShellSession(
                            cols = buffer.cols,
                            rows = buffer.rows,
                            shellOverride = suPath,
                            elevatedRoot = true
                        )
                    }
                } else {
                    val runningAsFallback = _sessionInfo.value.status == SessionStatus.RUNNING
                    _sessionInfo.update { it.copy(suPath = suPath, rootState = RootAccessState.ROOT_DENIED) }
                    val suffix = if (runningAsFallback) " — staying in user shell" else ""
                    buffer.appendLocalLine("[Root access denied by $suPath (exit $rc)$suffix]")
                }
            } finally {
                rootRequestInFlight = false
            }
        }
    }

    fun restartSession() {
        synchronized(this) {
            terminateSession()
            isElevatedSubshell = false
            buffer.clearAll()
            _sessionInfo.value = _sessionInfo.value.copy(
                status = SessionStatus.CONNECTING,
                exitCode = null,
                endedAtMillis = null,
                finalShExited = false
            )
            startShellSession(buffer.cols, buffer.rows)
        }
    }

    fun writeInput(text: String) {
        writeInput(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun writeInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (masterFd < 0 || _sessionInfo.value.status != SessionStatus.RUNNING) return
        val copy = bytes.copyOf()
        writeExecutor.execute {
            val activeFd = masterFd
            if (activeFd >= 0 && _sessionInfo.value.status == SessionStatus.RUNNING) {
                PtyNative.writeFd(activeFd, copy, copy.size)
            }
        }
    }

    fun resizeGrid(cols: Int, rows: Int) {
        if (cols < 10 || rows < 2) return
        buffer.resize(cols, rows)
        _sessionInfo.value = _sessionInfo.value.copy(cols = cols, rows = rows)
        val fd = masterFd
        if (fd >= 0 && _sessionInfo.value.status == SessionStatus.RUNNING) {
            PtyNative.setWindowSize(fd, rows, cols)
        }
    }

    fun sendSigint() {
        val pid = childPid
        if (pid > 0 && _sessionInfo.value.status == SessionStatus.RUNNING) {
            PtyNative.sendSignal(pid, 2) // SIGINT
        }
    }

    fun updateScrollbackLimit(lines: Int) {
        buffer.setMaxScrollback(lines)
    }

    private fun startReaderLoop(fd: Int, pid: Int, sessionId: Long) {
        val thread = Thread({
            val readBuf = ByteArray(8192)
            while (true) {
                val n = PtyNative.readFd(fd, readBuf)
                if (n <= 0) {
                    break
                }
                buffer.appendBytes(readBuf, n)
            }

            PtyNative.closeFd(fd)
            val exitCode = PtyNative.waitForChild(pid)

            var wasElevatedSubshell = false
            val activeMatch = synchronized(this@TerminalService) {
                if (masterFd == fd) {
                    masterFd = -1
                }
                if (childPid == pid) {
                    childPid = -1
                }
                if (activeSessionId == sessionId && _sessionInfo.value.status == SessionStatus.RUNNING) {
                    wasElevatedSubshell = isElevatedSubshell
                    true
                } else {
                    false
                }
            }

            if (activeMatch) {
                if (wasElevatedSubshell) {
                    synchronized(this@TerminalService) {
                        isElevatedSubshell = false
                        _sessionInfo.value = _sessionInfo.value.copy(
                            status = SessionStatus.CONNECTING,
                            exitCode = null,
                            endedAtMillis = null,
                            finalShExited = false
                        )
                        buffer.appendLocalLine("")
                        buffer.appendLocalLine("[Root shell exited (code $exitCode) — returning to user shell]")
                        startShellSession(buffer.cols, buffer.rows)
                    }
                } else {
                    _sessionInfo.update {
                        it.copy(
                            status = SessionStatus.EXITED,
                            exitCode = exitCode,
                            endedAtMillis = System.currentTimeMillis(),
                            finalShExited = true
                        )
                    }
                    buffer.appendLocalLine("")
                    buffer.appendLocalLine("[Process completed (code $exitCode)]")
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }, "com.thallyace.terminal-pty-reader-$sessionId")
        thread.isDaemon = true
        thread.start()
    }

    private fun terminateSession() {
        activeSessionId += 1L
        val pid = childPid
        val fd = masterFd
        if (pid > 0) {
            PtyNative.sendSignal(pid, 1) // SIGHUP
        }
        if (fd >= 0) {
            PtyNative.closeFd(fd)
            masterFd = -1
        }
        childPid = -1
    }

    private fun resolveShellPath(): String {
        val candidates = listOf("/system/bin/sh", "/system/bin/mksh", "/bin/sh")
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Safely continue if foreground promotion is restricted by system state
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val privLabel = if (_sessionInfo.value.rootState == RootAccessState.ROOT_GRANTED) {
            "Root shell active"
        } else {
            "Persistent session running"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$privLabel (com.thallyace.terminal)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Exit", stopPending)
            .build()
    }

    override fun onDestroy() {
        terminateSession()
        writeExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "terminal_service_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.thallyace.terminal.action.STOP_SERVICE"
    }
}
