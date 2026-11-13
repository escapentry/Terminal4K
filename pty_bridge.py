#!/usr/bin/env python3
import base64
import errno
import fcntl
import json
import os
import pty
import select
import signal
import struct
import sys
import termios


def set_winsize(fd: int, rows: int, cols: int) -> None:
    rows = max(2, min(500, int(rows)))
    cols = max(10, min(500, int(cols)))
    winsize = struct.pack("HHHH", rows, cols, 0, 0)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)


def emit(payload: dict) -> None:
    line = json.dumps(payload, separators=(",", ":")) + "\n"
    os.write(sys.stdout.fileno(), line.encode("utf-8"))


def main() -> None:
    cols = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    rows = int(sys.argv[2]) if len(sys.argv) > 2 else 24
    cwd = sys.argv[3] if len(sys.argv) > 3 else os.getcwd()
    rc_path = sys.argv[4] if len(sys.argv) > 4 else ""

    master_fd, slave_fd = pty.openpty()
    set_winsize(master_fd, rows, cols)

    pid = os.fork()
    if pid == 0:
        os.close(master_fd)
        os.setsid()
        try:
            fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)
        except OSError:
            pass

        os.dup2(slave_fd, 0)
        os.dup2(slave_fd, 1)
        os.dup2(slave_fd, 2)
        if slave_fd > 2:
            os.close(slave_fd)

        try:
            os.chdir(cwd)
        except OSError:
            pass

        env = os.environ.copy()
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = env.get("LANG", "C.UTF-8")
        env["LC_ALL"] = env.get("LC_ALL", "C.UTF-8")
        env["TERMINAL_PACKAGE"] = "com.android.terminal"

        shell = "/bin/bash" if os.path.exists("/bin/bash") else "/bin/sh"
        if shell.endswith("bash") and rc_path and os.path.exists(rc_path):
            args = ["bash", "--rcfile", rc_path, "-i"]
        else:
            args = [os.path.basename(shell), "-i"]

        os.execvpe(shell, args, env)

    os.close(slave_fd)
    emit({"type": "ready", "pid": pid})

    stdin_fd = sys.stdin.fileno()
    stdin_buf = b""
    stdin_open = True
    exit_code = None

    while True:
        watch_fds = [master_fd]
        if stdin_open:
            watch_fds.append(stdin_fd)

        try:
            readable, _, _ = select.select(watch_fds, [], [], 0.25)
        except InterruptedError:
            continue

        if master_fd in readable:
            try:
                data = os.read(master_fd, 16384)
                if not data:
                    break
                emit({
                    "type": "output",
                    "data": base64.b64encode(data).decode("ascii"),
                })
            except OSError as exc:
                if exc.errno in (errno.EIO, errno.EBADF):
                    break
                raise

        if stdin_fd in readable:
            try:
                chunk = os.read(stdin_fd, 16384)
            except OSError:
                chunk = b""

            if not chunk:
                stdin_open = False
            else:
                stdin_buf += chunk
                while b"\n" in stdin_buf:
                    raw_line, stdin_buf = stdin_buf.split(b"\n", 1)
                    line = raw_line.decode("utf-8", errors="ignore").strip()
                    if not line:
                        continue
                    try:
                        msg = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    msg_type = msg.get("type")
                    if msg_type == "input":
                        raw_b64 = msg.get("data", "")
                        if raw_b64:
                            os.write(master_fd, base64.b64decode(raw_b64))
                    elif msg_type == "resize":
                        new_cols = int(msg.get("cols", cols))
                        new_rows = int(msg.get("rows", rows))
                        cols, rows = new_cols, new_rows
                        set_winsize(master_fd, rows, cols)
                        try:
                            os.killpg(os.getpgid(pid), signal.SIGWINCH)
                        except OSError:
                            pass
                    elif msg_type == "signal":
                        sig_num = int(msg.get("sig", signal.SIGTERM))
                        try:
                            os.killpg(os.getpgid(pid), sig_num)
                        except OSError:
                            try:
                                os.kill(pid, sig_num)
                            except OSError:
                                pass

        # Check if child has exited
        if exit_code is None:
            try:
                waited_pid, status = os.waitpid(pid, os.WNOHANG)
                if waited_pid == pid:
                    if os.WIFEXITED(status):
                        exit_code = os.WEXITSTATUS(status)
                    elif os.WIFSIGNALED(status):
                        exit_code = 128 + os.WTERMSIG(status)
                    else:
                        exit_code = 0
                    # Drain any remaining buffered PTY output
                    while True:
                        r, _, _ = select.select([master_fd], [], [], 0.02)
                        if not r:
                            break
                        try:
                            tail = os.read(master_fd, 16384)
                            if not tail:
                                break
                            emit({
                                "type": "output",
                                "data": base64.b64encode(tail).decode("ascii"),
                            })
                        except OSError:
                            break
                    break
            except ChildProcessError:
                exit_code = 0
                break

    if exit_code is None:
        try:
            _, status = os.waitpid(pid, 0)
            if os.WIFEXITED(status):
                exit_code = os.WEXITSTATUS(status)
            elif os.WIFSIGNALED(status):
                exit_code = 128 + os.WTERMSIG(status)
            else:
                exit_code = 0
        except ChildProcessError:
            exit_code = 0

    try:
        os.close(master_fd)
    except OSError:
        pass

    emit({"type": "exit", "code": exit_code})


if __name__ == "__main__":
    main()
