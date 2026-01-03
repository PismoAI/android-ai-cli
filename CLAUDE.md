# Android AI CLI

## Goal
Android terminal app that runs Claude Code CLI directly - no proot, no Linux layer.

## NEW APPROACH (v2)
Skip proot/Alpine entirely. Bundle Node.js binary + npm directly in the APK.

## Current State
- ✅ Terminal UI works
- ✅ GitHub Actions builds APKs
- ✅ Node.js 25.2.1 aarch64 binary bundled (from Termux packages)
- ✅ npm bundled (from Termux packages)
- 🔨 Awaiting GitHub Actions build

## Architecture
```
APK Assets:
  bin/node-aarch64.xz     (9MB compressed, 46MB extracted)
  bin/node_modules.tar.xz (1.5MB compressed - contains npm)

On first launch:
  1. Extract node binary → /data/data/.../node/bin/node
  2. Extract npm → /data/data/.../node/lib/node_modules/npm
  3. Create wrapper scripts (npm, npx)
  4. Launch shell with node in PATH

No proot. No Alpine. Just native Android + Node.js.
```

## Key Files Changed
- `NodeEnvironment.java` - New class for node setup (replaces LinuxEnvironment approach)
- `NodeTermSession.java` - Terminal session with node in PATH
- `SetupActivity.java` - Uses NodeEnvironment instead of LinuxEnvironment
- `Term.java` - Uses NodeTermSession instead of ProotTermSession
- `term/build.gradle` - Added org.tukaani:xz dependency for decompression

## DO NOT
- Copy Termux GPL code
- Use hardcoded paths
- Use x86 binaries (need arm64)
- Forget chmod +x on binaries

## Build Commands
```bash
# Local build doesn't work on arm64 (NDK limitation)
# Use GitHub Actions instead

# Push changes to trigger build:
git add -A && git commit -m "message" && git push

# Download APK from GitHub Actions artifacts
```

## Testing Flow
1. Install APK on device
2. Launch app → SetupActivity shows
3. Extracts node/npm from assets (few seconds)
4. Launches terminal with node in PATH
5. Run: `node --version` → v25.2.1
6. Run: `npm --version`
7. Run: `npm install -g @anthropic-ai/claude-code`
8. Run: `claude`

## Environment Setup
When the shell starts, these are set:
```bash
PATH=/data/.../node/bin:/data/.../node/lib/node_modules/.bin:/system/bin
HOME=/data/.../node/home
NPM_CONFIG_PREFIX=/data/.../node/lib
NODE_PATH=/data/.../node/lib/node_modules
```

---

## UserLAnd Architecture Study (January 2026)

**WARNING: UserLAnd is GPL v3 licensed. DO NOT copy code. Learn patterns only.**

Repository: `github.com/CypherpunkArmory/UserLAnd`
License: GPL v3 (including their termux-app fork)

### Key Architecture Insights

#### 1. Project Structure
```
UserLAnd/
├── app/src/main/java/tech/ula/
│   ├── MainActivity.kt           # Main UI
│   ├── ServerService.kt          # Background service for sessions
│   ├── model/
│   │   ├── entities/             # Data classes (Session, Filesystem, App)
│   │   ├── repositories/         # Data access (AssetRepository)
│   │   └── remote/               # GitHub API client
│   └── utils/
│       ├── BusyboxExecutor.kt    # Executes commands via busybox/proot
│       ├── UlaFiles.kt           # File path management
│       ├── AssetDownloader.kt    # Downloads from GitHub
│       ├── FilesystemManager.kt  # Rootfs extraction
│       └── LocalServerManager.kt # Starts SSH/VNC servers
└── termux-app/                   # Terminal emulator (Apache 2.0 origin)
    ├── terminal-emulator/        # Core emulator
    ├── terminal-view/            # View component
    └── terminal-term/            # Terminal app
```

#### 2. How They Download Linux Filesystems

**Source:** `AssetRepository.kt`, `GithubApiClient.kt`, `AssetDownloader.kt`

- Uses GitHub Releases API to fetch assets
- URL pattern: `api.github.com/repos/CypherpunkArmory/UserLAnd-Assets-{distro}/releases/latest`
- Downloads: `{arch}-assets.tar.gz` and `{arch}-rootfs.tar.gz`
- Uses Android's `DownloadManager` for background downloads
- Extracts using `jarchivelib` library
- Caches version info in SharedPreferences

**Key Files:**
- `rootfs.tar.gz` - Full Linux filesystem
- `assets.tar.gz` - Support scripts (execInProot.sh, etc.)

#### 3. How They Run Proot

**Source:** `BusyboxExecutor.kt`, `BusyboxWrapper` class

**Proot execution pattern:**
```kotlin
// Command wrapping:
listOf(busybox.absolutePath, "sh", "support/execInProot.sh") + command.split(" ")

// Environment variables:
env["LD_LIBRARY_PATH"] = supportDir.absolutePath
env["LIB_PATH"] = supportDir.absolutePath
env["ROOT_PATH"] = filesDir.absolutePath
env["ROOTFS_PATH"] = filesystemDir.absolutePath
env["PROOT_DEBUG_LEVEL"] = prootDebugLevel
env["EXTRA_BINDINGS"] = storageBindings
env["OS_VERSION"] = System.getProperty("os.version")
```

**Key insight:** They use a shell script (`execInProot.sh`) to wrap proot execution.
This script is downloaded as part of assets, not embedded in the app.

**Storage bindings:**
```kotlin
"-b ${emulatedUserDir.absolutePath}:/storage/internal"
"-b ${sdCardUserDir.absolutePath}:/storage/sdcard"
```

#### 4. How Terminal Connects to Shell

**Source:** `termux-app/terminal-emulator/src/main/jni/termux.c`

**They use PTY (pseudoterminal) via JNI:**

1. **JNI.createSubprocess()** - Native method in `termux.c`
   - Opens `/dev/ptmx` (master PTY)
   - Calls `grantpt()`, `unlockpt()`, `ptsname_r()`
   - Forks process
   - Child opens slave PTY (`/dev/pts/N`)
   - Redirects stdin/stdout/stderr to slave
   - Calls `execvp()` with shell command

2. **TerminalSession.java** - Java wrapper
   - Creates `ByteQueue` for I/O buffering
   - Spawns 3 threads:
     - `TermSessionInputReader` - Reads from PTY master → queue
     - `TermSessionOutputWriter` - Writes queue → PTY master
     - `TermSessionWaiter` - Waits for process exit

3. **Key pattern:**
   ```java
   mTerminalFileDescriptor = JNI.createSubprocess(shellPath, cwd, args, env, processId, rows, cols);
   FileDescriptor fd = wrapFileDescriptor(mTerminalFileDescriptor);
   new FileInputStream(fd);   // Read from process
   new FileOutputStream(fd);  // Write to process
   ```

**NOTE:** UserLAnd uses **SSH clients** for terminal access, not direct PTY:
- Starts dropbear SSH server inside proot
- Launches external SSH client app (ConnectBot) with `ssh://user@localhost:2022`
- This is why they have `LocalServerManager.kt` with SSH/VNC/XSDL

#### 5. Symlink Handling

**Source:** `UlaFiles.kt`

```kotlin
// They bundle proot/busybox as native libs (lib_*.so)
// Then symlink to support directory
libDir.listFiles()!!.forEach { libFile ->
    val name = libFileName.toSupportName()  // lib_proot.so -> proot
    val linkFile = File(supportDir, name)
    linkFile.delete()
    Os.symlink(libFile.path, linkFile.path)
}
```

**Clever trick:** Android automatically extracts `lib*.so` files from APK.
They name their binaries `lib_proot.so`, `lib_busybox.so` to leverage this.

### What We Can Learn (Without Copying)

1. **Use GitHub Releases for rootfs distribution**
   - No embedded large files
   - Easy version updates
   - Use `DownloadManager` for reliability

2. **PTY approach for terminal**
   - Must use JNI/native code for `/dev/ptmx`
   - Create ByteQueue buffers for async I/O
   - Need 3 threads: reader, writer, waiter

3. **Symlink strategy**
   - Bundle binaries as `lib_*.so` in jniLibs
   - Android extracts them automatically
   - Create symlinks at runtime to real names

4. **Environment setup for proot**
   - Set `LD_LIBRARY_PATH` for shared libs
   - Create storage bind mounts
   - Use wrapper script for flexibility

5. **Service architecture**
   - Use Android Service for long-running shell
   - `START_STICKY` for persistence
   - Foreground notification required

### Key Differences for Our Approach

| UserLAnd | Our App |
|----------|---------|
| Multiple distros (Debian, Ubuntu, etc.) | Alpine only |
| SSH/VNC client apps | Direct terminal |
| GitHub Releases for rootfs | Bundled or lightweight download |
| Complex asset management | Simple bootstrap |
| GPL v3 | Permissive license |

### Files to Study Further
- `support/execInProot.sh` - Not in repo, downloaded at runtime
- External repo: `CypherpunkArmory/UserLAnd-Assets-*`
