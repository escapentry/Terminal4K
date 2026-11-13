package com.android.terminal.pty

object PtyNative {
    init {
        System.loadLibrary("terminal_pty")
    }

    external fun spawnShell(shellPath: String, cwd: String, rows: Int, cols: Int): IntArray?

    external fun readFd(fd: Int, buffer: ByteArray): Int

    external fun writeFd(fd: Int, data: ByteArray, length: Int): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int): Int

    external fun waitForChild(pid: Int): Int

    external fun sendSignal(pid: Int, signalNum: Int): Int

    external fun closeFd(fd: Int)
}
