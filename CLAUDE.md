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
