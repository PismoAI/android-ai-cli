# Android AI CLI

## Goal
Android terminal app with proot + Alpine Linux so users can run Claude Code CLI.

## Current State
- ✅ Terminal UI works
- ✅ GitHub Actions builds APKs
- ✅ Proot binary works (v5.1.0, aarch64, statically linked)
- ✅ Alpine rootfs works with proot
- ✅ apk package manager works (installs bash, curl, etc.)
- 🔨 Java bootstrap - code updated, needs device testing

## Phases
1. ✅ Proot binary for Android arm64 - DONE
2. ✅ Alpine rootfs works with proot - DONE
3. 🔨 Java bootstrap downloads on first launch ⬅️ TESTING
4. ⬜ Terminal connects to proot shell
5. ⬜ apk package manager works
6. ⬜ Claude Code CLI runs

## DO NOT
- Copy Termux GPL code
- Use hardcoded paths
- Use x86 binaries (need arm64)
- Forget chmod +x on binaries
- Use system tar (creates problematic symlinks)

## Current Phase: 3
**Status:** Multiple EROFS fixes applied, build successful, awaiting device test
**Changes Made:**
1. Skip system tar entirely - use only Java extraction
2. Added forceDelete() with 4 fallback deletion strategies
3. Handles broken symlinks that cause EROFS on Android
4. Added colors.xml for v21 theme (fixed startup crash)
5. createSymlink now deletes existing files before creating symlinks
6. Clean up failed symlink attempts to prevent blocking

## Key Fixes Applied

### EROFS Read-Only Filesystem Error
- **Problem:** System tar creates symlinks that can't be overwritten on Android
- **Solution:** Skip system tar, use Java extraction only
- **Added forceDelete()** with multiple fallback methods:
  1. NIO Files.deleteIfExists
  2. Standard Java delete
  3. Shell `rm -f` command
  4. Shell `unlink` command

### Startup Crash (Missing Colors)
- **Problem:** values-v21/styles.xml referenced undefined colors
- **Solution:** Created colors.xml with primary, primary_dark, accent colors

## Verified (Phase 1)
- proot-aarch64 binary at: term/src/main/assets/bin/proot-aarch64
- Version: 5.1.0 with process_vm and seccomp_filter accelerators
- Type: ELF 64-bit aarch64, statically linked, Android NDK r20

## Verified (Phase 2)
- Alpine 3.19.0 minirootfs aarch64 works
- Shell works: `proot -r ./rootfs /bin/busybox ash`
- PATH must be set: `export PATH=/bin:/sbin:/usr/bin:/usr/sbin`
- apk update/install works (trigger scripts fail but packages work)
- Installed and verified: bash 5.2.21, curl 8.14.1

## Critical proot flags
```
PROOT_TMP_DIR=/path/to/tmp    # Required writable temp dir
PROOT_NO_SECCOMP=1            # May help on some Android versions
--link2symlink                 # Converts symlinks for Android
-0                            # Fake root (uid 0)
-r /path/to/rootfs            # Root filesystem
-b /proc -b /dev              # Bind mounts
```

## Build Commands
```bash
# Local build (requires Android SDK)
./gradlew assembleDebug

# GitHub Actions builds automatically on push
# Download APK from Actions artifacts
```

## Testing Flow
1. Install APK on device
2. Launch app → SetupActivity shows
3. Downloads Alpine (~5MB)
4. Extracts using Java (avoids symlink issues)
5. Creates critical files (busybox copies to sh, ash, etc.)
6. Launches terminal with proot shell
7. First-time setup script runs (installs bash, curl, nodejs)

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
