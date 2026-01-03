# Android AI CLI

## Goal
Android app that serves as a Termux companion for running Claude Code CLI.

## Current Approach (v3) - Termux Companion
Instead of bundling our own Linux environment, we leverage Termux which already solved Linux-on-Android.

## Current State
- ✅ Simple launcher UI
- ✅ Detects if Termux is installed
- ✅ Provides install links (F-Droid, GitHub)
- ✅ Launches Termux when ready

## Architecture
```
TermuxCheckActivity (launcher):
  1. Check if com.termux package is installed
  2. If not installed:
     - Show "Termux is not installed" message
     - Buttons to F-Droid and GitHub releases
  3. If installed:
     - Show "Termux is installed!" message
     - "Launch Termux" button
     - Opens com.termux.app.TermuxActivity
```

## Key Files
- `TermuxCheckActivity.java` - Main launcher, checks for Termux
- `Term.java` - (Legacy) Terminal emulator, uses ShellTermSession
- `AndroidManifest.xml` - TermuxCheckActivity is the launcher

## DO NOT
- Copy Termux GPL code
- Try to replace Termux functionality
- Bundle our own Linux environment

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
3. If Termux not installed:
   - Click F-Droid or GitHub button
   - Install Termux
   - Return to app
4. If Termux installed:
   - Click "Launch Termux"
   - In Termux: `npm install -g @anthropic-ai/claude-code`
   - Run: `claude`

## Why This Approach?
1. **Termux is mature** - Years of development, proper Linux environment
2. **No duplication** - Don't reinvent what already works
3. **Smaller APK** - No bundled binaries or rootfs
4. **Always up to date** - Termux updates independently
5. **Community support** - Termux has active community and packages
