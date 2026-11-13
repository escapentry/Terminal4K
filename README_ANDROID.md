# Terminal (`com.thallyace.terminal`)

Native Android terminal emulator app configured for GitHub Actions compilation.

- **App Name**: `Terminal`
- **Package / Application ID**: `com.thallyace.terminal`
- **Single-Tab Persistent Service**: `TerminalService` (foreground service holding `/system/bin/sh` via POSIX PTY until `exit` is executed)
- **Adaptive Monospace Typography**: Automatically computes readable `sp` sizing per device screen width and density

## Compile on GitHub

1. Push the contents of this archive (`android/` and `.github/`) to a GitHub repository.
2. Open the **Actions** tab and select **Build Android APK** (triggers automatically on push, or click **Run workflow**).
3. Download the **Terminal-com.thallyace.terminal-debug** artifact and install the APK on your Android device.
