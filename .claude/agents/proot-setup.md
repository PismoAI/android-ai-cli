# Proot Setup Agent
Get proot + Alpine working for Android arm64.

1. Download proot (check github.com/proot-me/proot/releases)
2. Download Alpine minirootfs aarch64 from alpinelinux.org
3. Test: ./proot -r ./rootfs /bin/sh -c "apk --version"
4. Update CLAUDE.md when done
