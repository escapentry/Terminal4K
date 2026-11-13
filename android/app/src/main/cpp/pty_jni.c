#define _XOPEN_SOURCE 600
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int find_su_binary(char *outPath, size_t outSize) {
    const char *candidates[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/apex/com.android.runtime/bin/su"
    };
    size_t count = sizeof(candidates) / sizeof(candidates[0]);
    for (size_t i = 0; i < count; i++) {
        if (access(candidates[i], X_OK) == 0) {
            snprintf(outPath, outSize, "%s", candidates[i]);
            return 1;
        }
    }
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_com_thallyace_terminal_pty_PtyNative_spawnShell(
    JNIEnv *env,
    jobject thiz,
    jstring jShellPath,
    jstring jCwd,
    jint rows,
    jint cols,
    jboolean interactiveFlag
) {
    (void)thiz;
    const char *shellPath = (*env)->GetStringUTFChars(env, jShellPath, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jCwd, NULL);

    int masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (masterFd < 0) {
        int err = errno;
        (*env)->ReleaseStringUTFChars(env, jShellPath, shellPath);
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jintArray errArr = (*env)->NewIntArray(env, 3);
        if (errArr != NULL) {
            jint vals[3] = { -1, -1, (jint)err };
            (*env)->SetIntArrayRegion(env, errArr, 0, 3, vals);
        }
        return errArr;
    }

    if (grantpt(masterFd) != 0 || unlockpt(masterFd) != 0) {
        int err = errno;
        close(masterFd);
        (*env)->ReleaseStringUTFChars(env, jShellPath, shellPath);
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jintArray errArr = (*env)->NewIntArray(env, 3);
        if (errArr != NULL) {
            jint vals[3] = { -1, -1, (jint)err };
            (*env)->SetIntArrayRegion(env, errArr, 0, 3, vals);
        }
        return errArr;
    }

    char *slaveName = ptsname(masterFd);
    if (slaveName == NULL) {
        int err = errno;
        close(masterFd);
        (*env)->ReleaseStringUTFChars(env, jShellPath, shellPath);
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jintArray errArr = (*env)->NewIntArray(env, 3);
        if (errArr != NULL) {
            jint vals[3] = { -1, -1, (jint)err };
            (*env)->SetIntArrayRegion(env, errArr, 0, 3, vals);
        }
        return errArr;
    }

    char slavePath[256];
    snprintf(slavePath, sizeof(slavePath), "%s", slaveName);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 1 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 9 ? cols : 80);
    ioctl(masterFd, TIOCSWINSZ, &ws);
    fcntl(masterFd, F_SETFD, FD_CLOEXEC);

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        close(masterFd);
        (*env)->ReleaseStringUTFChars(env, jShellPath, shellPath);
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jintArray errArr = (*env)->NewIntArray(env, 3);
        if (errArr != NULL) {
            jint vals[3] = { -1, -1, (jint)err };
            (*env)->SetIntArrayRegion(env, errArr, 0, 3, vals);
        }
        return errArr;
    }

    if (pid == 0) {
        close(masterFd);
        setsid();

        int slaveFd = open(slavePath, O_RDWR);
        if (slaveFd < 0) {
            _exit(126);
        }

#ifdef TIOCSCTTY
        ioctl(slaveFd, TIOCSCTTY, 0);
#endif

        struct termios tios;
        if (tcgetattr(slaveFd, &tios) == 0) {
            tios.c_iflag |= ICRNL;
#ifdef IUTF8
            tios.c_iflag |= IUTF8;
#endif
            tios.c_oflag |= OPOST | ONLCR;
            tios.c_lflag |= ECHO | ECHOE | ECHOK | ICANON | ISIG;
            tcsetattr(slaveFd, TCSANOW, &tios);
        }

        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > 2) {
            close(slaveFd);
        }

        if (cwd != NULL && cwd[0] != '\0') {
            chdir(cwd);
            setenv("HOME", cwd, 1);
            char envPath[512];
            snprintf(envPath, sizeof(envPath), "%s/.shrc", cwd);
            setenv("ENV", envPath, 1);
        }

        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("TERMINAL_PACKAGE", "com.thallyace.terminal", 1);

        if (interactiveFlag) {
            if (getuid() == 0) {
                setenv("PS1", "root@com.thallyace.terminal:$PWD# ", 1);
            } else {
                setenv("PS1", "com.thallyace.terminal:$PWD$ ", 1);
            }
            char *const args[] = { (char *)shellPath, "-i", NULL };
            execvp(shellPath, args);
        } else {
            setenv("PS1", "root@com.thallyace.terminal:$PWD# ", 1);
            char *const args[] = { (char *)shellPath, NULL };
            execvp(shellPath, args);
        }
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, jShellPath, shellPath);
    (*env)->ReleaseStringUTFChars(env, jCwd, cwd);

    jintArray result = (*env)->NewIntArray(env, 3);
    if (result == NULL) {
        close(masterFd);
        return NULL;
    }
    jint values[3] = { (jint)masterFd, (jint)pid, 0 };
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_readFd(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jbyteArray jBuffer
) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, jBuffer);
    if (len <= 0) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, jBuffer, NULL);
    if (buf == NULL) return -1;

    ssize_t n;
    do {
        n = read((int)fd, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);

    (*env)->ReleaseByteArrayElements(env, jBuffer, buf, 0);
    if (n < 0) return -1;
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_writeFd(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jbyteArray jData,
    jint length
) {
    (void)thiz;
    if (length <= 0) return 0;
    jbyte *buf = (*env)->GetByteArrayElements(env, jData, NULL);
    if (buf == NULL) return -1;

    ssize_t written = 0;
    while (written < length) {
        ssize_t n = write((int)fd, buf + written, (size_t)(length - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            (*env)->ReleaseByteArrayElements(env, jData, buf, JNI_ABORT);
            return -1;
        }
        written += n;
    }

    (*env)->ReleaseByteArrayElements(env, jData, buf, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_setWindowSize(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jint rows,
    jint cols
) {
    (void)env;
    (void)thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 1 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 9 ? cols : 80);
    return ioctl((int)fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_waitForChild(
    JNIEnv *env,
    jobject thiz,
    jint pid
) {
    (void)env;
    (void)thiz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t)pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) return 0;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_sendSignal(
    JNIEnv *env,
    jobject thiz,
    jint pid,
    jint signalNum
) {
    (void)env;
    (void)thiz;
    if (pid <= 0) return -1;
    if (kill(-(pid_t)pid, (int)signalNum) == 0) {
        return 0;
    }
    return kill((pid_t)pid, (int)signalNum) == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_com_thallyace_terminal_pty_PtyNative_closeFd(
    JNIEnv *env,
    jobject thiz,
    jint fd
) {
    (void)env;
    (void)thiz;
    if (fd >= 0) {
        close((int)fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_getUid(
    JNIEnv *env,
    jobject thiz
) {
    (void)env;
    (void)thiz;
    return (jint)getuid();
}

JNIEXPORT jstring JNICALL
Java_com_thallyace_terminal_pty_PtyNative_findSuPath(
    JNIEnv *env,
    jobject thiz
) {
    (void)thiz;
    char suPath[256];
    if (find_su_binary(suPath, sizeof(suPath))) {
        return (*env)->NewStringUTF(env, suPath);
    }
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_thallyace_terminal_pty_PtyNative_testSuRoot(
    JNIEnv *env,
    jobject thiz,
    jstring jSuPath
) {
    (void)thiz;
    const char *suPath = (*env)->GetStringUTFChars(env, jSuPath, NULL);
    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, jSuPath, suPath);
        return -1;
    }
    if (pid == 0) {
        int devNull = open("/dev/null", O_RDWR);
        if (devNull >= 0) {
            dup2(devNull, STDIN_FILENO);
            dup2(devNull, STDOUT_FILENO);
            dup2(devNull, STDERR_FILENO);
            if (devNull > 2) close(devNull);
        }
        char *const args[] = { (char *)suPath, "-c", "[ \"$(id -u)\" = \"0\" ]", NULL };
        execvp(suPath, args);
        _exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jSuPath, suPath);
    int status = 0;
    pid_t r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}
