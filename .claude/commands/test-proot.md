Run proot tests:
chmod +x ./proot
./proot --version
file ./proot
./proot -r ./rootfs /bin/sh -c "cat /etc/os-release"
Report pass/fail for each.
