# Android AI CLI

## Goal
Standalone Android terminal app using proot + Alpine Linux.

## Current Approach (v4) - Standalone Proot Terminal
Uses proot with Alpine Linux minirootfs for a fully standalone terminal.

## Current State
- ✅ AssetExtractor with proper hardlink handling in tar
- ✅ ProotShellSession with correct proot flags
- ✅ Launcher that extracts assets and starts terminal
- ✅ GitHub Actions downloads proot and Alpine rootfs

## Architecture
```
TermuxCheckActivity (launcher):
  1. Check if assets are extracted
  2. If not extracted:
     - Show "First-time setup required"
     - Extract proot, Alpine rootfs from assets
  3. If extracted:
     - Show "Ready!"
     - Launch Term activity with proot session

Term.java:
  - Receives proot configuration from intent
  - Creates ProotShellSession for proot mode
  - Falls back to ShellTermSession if not proot mode

ProotShellSession:
  - Runs shell inside proot with Alpine rootfs
  - Key flags: -0 --link2symlink
  - Key env: PROOT_NO_SECCOMP=1, explicit PATH

AssetExtractor:
  - Extracts proot binary
  - Extracts Alpine rootfs tar.gz
  - Handles hardlinks (tar type '1') - THE KEY FIX
```

## Key Files
- `TermuxCheckActivity.java` - Launcher, extracts assets
- `Term.java` - Terminal activity, handles proot sessions
- `ProotShellSession.java` - Shell session via proot
- `AssetExtractor.java` - Asset extraction with tar/hardlink support
- `.github/workflows/build.yml` - Downloads proot and Alpine

## Key Fixes Applied
1. **TarEntry hardlink handling**: Type '1' entries are hardlinks, not symlinks
2. **proot --link2symlink**: Required for Android's filesystem
3. **PROOT_NO_SECCOMP=1**: Required for proot on Android
4. **Explicit PATH**: Set full PATH inside Alpine

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
2. Launch app
3. First run: "Setup & Launch" extracts proot + Alpine
4. Subsequent runs: "Launch Terminal" opens directly
5. Terminal runs with Alpine Linux shell

## Assets (downloaded by GitHub Actions)
- `proot` - proot-static binary for aarch64
- `alpine-rootfs.tar.gz` - Alpine Linux minirootfs
