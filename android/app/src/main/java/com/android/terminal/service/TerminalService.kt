package com.android.terminal.service

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
import com.android.terminal.MainActivity
import com.android.terminal.R
import com.android.terminal.model.SessionInfo
import com.android.terminal.model.SessionStatus
import com.android.terminal.pty.PtyNative
import com.android.terminal.terminal.TerminalBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets

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
    private var readerThread: Thread? = null

    private val writeLock = Any()

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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            terminateSession()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()

        if (_sessionInfo.value.status == SessionStatus.CONNECTING) {
            startShellSession(buffer.cols, buffer.rows)
        }
        return START_STICKY
    }

    fun startShellSession(cols: Int, rows: Int) {
        synchronized(this) {
            if (_sessionInfo.value.status == SessionStatus.RUNNING && masterFd >= 0) {
                return
            }
            val shellPath = resolveShellPath()
            val cwd = filesDir.absolutePath
            val spawned = PtyNative.spawnShell(shellPath, cwd, rows, cols)

            if (spawned == null || spawned.size < 2 || spawned[0] < 0) {
                _sessionInfo.value = SessionInfo(
                    pid = -1,
                    status = SessionStatus.EXITED,
                    exitCode = 127,
                    startedAtMillis = System.currentTimeMillis(),
                    endedAtMillis = System.currentTimeMillis(),
                    cols = cols,
                    rows = rows,
                    shellPath = shellPath
                )
                buffer.appendLocalLine("[Failed to start PTY shell ($shellPath)]")
                return
            }

            masterFd = spawned[0]
            childPid = spawned[1]
            _sessionInfo.value = SessionInfo(
                pid = childPid,
                status = SessionStatus.RUNNING,
                exitCode = null,
                startedAtMillis = System.currentTimeMillis(),
                endedAtMillis = null,
                cols = cols,
                rows = rows,
                shellPath = shellPath
            )

            promoteToForeground()
            startReaderLoop(masterFd, childPid)
        }
    }

    fun restartSession() {
        terminateSession()
        buffer.clearAll()
        _sessionInfo.value = _sessionInfo.value.copy(
            status = SessionStatus.CONNECTING,
            exitCode = null,
            endedAtMillis = null
        )
        startShellSession(buffer.cols, buffer.rows)
    }

    fun writeInput(text: String) {
        writeInput(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun writeInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val fd = masterFd
        if (fd < 0 || _sessionInfo.value.status != SessionStatus.RUNNING) return
        Thread {
            synchronized(writeLock) {
                val activeFd = masterFd
                if (activeFd >= 0 && _sessionInfo.value.status == SessionStatus.RUNNING) {
                    PtyNative.writeFd(activeFd, bytes, bytes.size)
                }
            }
        }.start()
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

    private fun startReaderLoop(fd: Int, pid: Int) {
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
            if (masterFd == fd) {
                masterFd = -1
            }

            val exitCode = PtyNative.waitForChild(pid)
            if (childPid == pid) {
                childPid = -1
            }

            if (_sessionInfo.value.status == SessionStatus.RUNNING) {
                _sessionInfo.value = _sessionInfo.value.copy(
                    status = SessionStatus.EXITED,
                    exitCode = exitCode,
                    endedAtMillis = System.currentTimeMillis()
                )
                buffer.appendLocalLine("")
                buffer.appendLocalLine("[Process completed (code $exitCode) — press Enter to start new session]")
            }

            stopForegroundCompat()
            stopSelf()
        }, "com.android.terminal-pty-reader")
        readerThread = thread
        thread.start()
    }

    private fun terminateSession() {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Persistent session running (com.android.terminal)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Exit", stopPending)
            .build()
    }

    override fun onDestroy() {
        terminateSession()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "terminal_service_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.android.terminal.action.STOP_SERVICE"
    }
}
