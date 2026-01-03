# Android AI CLI

## Goal
Android terminal app with proot + Alpine Linux so users can run Claude Code CLI.

## Current State
- ✅ Terminal UI works
- ✅ GitHub Actions builds APKs
- ✅ Proot binary works (v5.1.0, aarch64, statically linked)
- ✅ Alpine rootfs works with proot
- ✅ apk package manager works (installs bash, curl, etc.)
- ❌ Java bootstrap not wired up yet

## Phases
1. ✅ Proot binary for Android arm64 - DONE
2. ✅ Alpine rootfs works with proot - DONE
3. Java bootstrap downloads on first launch ⬅️ CURRENT
4. Terminal connects to proot shell
5. apk package manager works
6. Claude Code CLI runs

## DO NOT
- Copy Termux GPL code
- Use hardcoded paths
- Use x86 binaries (need arm64)
- Forget chmod +x on binaries

## Current Phase: 3
**Status:** Code fixed, ready for testing on device
**Fixed:** Added PATH export to .profile and setup-alpine.sh to ensure commands work in proot
**Next:** Build APK via GitHub Actions and test on device

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
