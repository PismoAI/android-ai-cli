package jackpal.androidterm;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts assets (proot, busybox, Alpine rootfs) with proper tar handling.
 *
 * Key fixes:
 * - Handles hardlinks (tar type '1'), not just symlinks
 * - Validates extraction properly
 * - Sets executable permissions
 */
public class AssetExtractor {
    private static final String TAG = "AssetExtractor";

    private final Context context;
    private final File filesDir;

    public AssetExtractor(Context context) {
        this.context = context;
        this.filesDir = context.getFilesDir();
    }

    /**
     * Get the base directory for our files
     */
    public File getBaseDir() {
        return filesDir;
    }

    /**
     * Get the proot binary path
     */
    public File getProotBinary() {
        return new File(filesDir, "proot");
    }

    /**
     * Get the busybox binary path (inside Alpine rootfs)
     */
    public File getBusyboxBinary() {
        return new File(getRootfsDir(), "bin/busybox");
    }

    /**
     * Get the Alpine rootfs directory
     */
    public File getRootfsDir() {
        return new File(filesDir, "rootfs");
    }

    /**
     * Check if all required assets are extracted
     * Note: We don't check for busybox - Alpine has it built-in at /bin/busybox
     */
    public boolean isExtracted() {
        File proot = getProotBinary();
        File rootfs = getRootfsDir();
        File sh = new File(rootfs, "bin/sh");

        // Only check proot and rootfs - Alpine has busybox built-in
        return proot.exists() && proot.canExecute() &&
               rootfs.exists() && sh.exists();
    }

    /**
     * Extract all assets
     */
    public void extractAll(ExtractCallback callback) throws IOException {
        // Extract proot binary
        callback.onProgress("Extracting proot...");
        extractBinary("proot", getProotBinary());

        // Extract Alpine rootfs (includes busybox at /bin/busybox)
        callback.onProgress("Extracting Alpine rootfs...");
        extractRootfs(callback);

        callback.onProgress("Setup complete!");
    }

    /**
     * Extract a binary file from assets
     */
    private void extractBinary(String assetName, File dest) throws IOException {
        AssetManager assets = context.getAssets();

        try (InputStream in = assets.open(assetName);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }

        // Make executable
        if (!dest.setExecutable(true, false)) {
            Log.w(TAG, "Failed to set executable: " + dest);
        }

        Log.i(TAG, "Extracted: " + dest + " (size: " + dest.length() + ")");
    }

    /**
     * Extract Alpine rootfs from tar.gz
     */
    private void extractRootfs(ExtractCallback callback) throws IOException {
        File rootfs = getRootfsDir();

        // Create rootfs directory
        if (!rootfs.exists()) {
            rootfs.mkdirs();
        }

        // Extract tar.gz
        extractTarGz("alpine-rootfs.tar.gz", rootfs, callback);
    }

    /**
     * Extract a tar.gz archive with proper hardlink support
     *
     * TAR entry types:
     * - '0' or 0: Regular file
     * - '1': Hard link (points to another file in the archive)
     * - '2': Symbolic link
     * - '5': Directory
     *
     * The bug was: isSymlink() only checks type '2', missing hardlinks (type '1')
     */
    private void extractTarGz(String assetName, File destDir, ExtractCallback callback) throws IOException {
        AssetManager assets = context.getAssets();

        InputStream assetStream;
        try {
            assetStream = assets.open(assetName);
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + assetName);
            return;
        }

        // Decompress gzip if needed
        InputStream tarStream;
        if (assetName.endsWith(".gz")) {
            tarStream = new java.util.zip.GZIPInputStream(assetStream);
        } else {
            tarStream = assetStream;
        }

        // Track files for hardlink resolution
        Map<String, File> extractedFiles = new HashMap<>();

        // Read tar entries
        TarInputStream tar = new TarInputStream(tarStream);
        TarEntry entry;
        int count = 0;

        while ((entry = tar.getNextEntry()) != null) {
            String name = entry.getName();

            // Skip empty names and absolute paths
            if (name == null || name.isEmpty()) continue;
            if (name.startsWith("/")) name = name.substring(1);
            if (name.isEmpty()) continue;

            // Remove leading ./ if present
            if (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty()) continue;

            File outFile = new File(destDir, name);

            // Security: prevent path traversal
            if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                Log.w(TAG, "Skipping path traversal attempt: " + name);
                continue;
            }

            if (entry.isDirectory()) {
                // Directory
                outFile.mkdirs();
            } else if (entry.isSymlink()) {
                // Symbolic link (type '2')
                String linkTarget = entry.getLinkName();
                createSymlink(outFile, linkTarget);
            } else if (entry.isHardlink()) {
                // Hard link (type '1') - THE KEY FIX
                String linkTarget = entry.getLinkName();
                File targetFile = extractedFiles.get(linkTarget);
                if (targetFile != null && targetFile.exists()) {
                    // Copy the target file (Android doesn't support real hardlinks)
                    copyFile(targetFile, outFile);
                    // Preserve executable permission
                    if (targetFile.canExecute()) {
                        outFile.setExecutable(true, false);
                    }
                } else {
                    // Target not yet extracted, create symlink as fallback
                    Log.w(TAG, "Hardlink target not found: " + linkTarget + " for " + name);
                    createSymlink(outFile, linkTarget);
                }
            } else {
                // Regular file
                outFile.getParentFile().mkdirs();

                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = tar.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }

                // Set permissions
                int mode = entry.getMode();
                if ((mode & 0111) != 0) {  // Any execute bit
                    outFile.setExecutable(true, false);
                }

                // Track for hardlink resolution
                extractedFiles.put(name, outFile);
            }

            count++;
            if (count % 100 == 0) {
                callback.onProgress("Extracted " + count + " files...");
            }
        }

        tar.close();
        Log.i(TAG, "Extracted " + count + " entries from " + assetName);
    }

    /**
     * Create a symbolic link
     */
    private void createSymlink(File link, String target) {
        try {
            // Ensure parent directory exists
            link.getParentFile().mkdirs();

            // Remove existing file if any
            if (link.exists()) {
                link.delete();
            }

            // Use ProcessBuilder to create symlink
            ProcessBuilder pb = new ProcessBuilder("ln", "-sf", target, link.getAbsolutePath());
            Process p = pb.start();
            p.waitFor();

        } catch (Exception e) {
            Log.w(TAG, "Failed to create symlink: " + link + " -> " + target, e);
        }
    }

    /**
     * Copy a file
     */
    private void copyFile(File src, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    /**
     * Callback interface for extraction progress
     */
    public interface ExtractCallback {
        void onProgress(String message);
    }

    /**
     * Simple tar input stream implementation
     */
    private static class TarInputStream extends FilterInputStream {
        private TarEntry currentEntry;
        private long remaining;

        public TarInputStream(InputStream in) {
            super(in);
        }

        public TarEntry getNextEntry() throws IOException {
            // Skip remaining bytes of current entry
            if (remaining > 0) {
                skip(remaining);
            }

            // Skip padding to 512-byte boundary
            if (currentEntry != null) {
                long size = currentEntry.getSize();
                int padding = (int) ((512 - (size % 512)) % 512);
                if (padding > 0) {
                    skip(padding);
                }
            }

            // Read 512-byte header
            byte[] header = new byte[512];
            int read = 0;
            while (read < 512) {
                int n = in.read(header, read, 512 - read);
                if (n < 0) return null;
                read += n;
            }

            // Check for empty header (end of archive)
            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) return null;

            currentEntry = new TarEntry(header);
            remaining = currentEntry.getSize();

            return currentEntry;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;

            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = 0;
            byte[] buffer = new byte[8192];
            while (skipped < n) {
                int toSkip = (int) Math.min(buffer.length, n - skipped);
                int read = in.read(buffer, 0, toSkip);
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
    }

    /**
     * Tar entry representation
     */
    private static class TarEntry {
        private final String name;
        private final int mode;
        private final long size;
        private final char type;
        private final String linkName;

        public TarEntry(byte[] header) {
            // Name: bytes 0-99
            this.name = extractString(header, 0, 100);

            // Mode: bytes 100-107 (octal)
            this.mode = extractOctal(header, 100, 8);

            // Size: bytes 124-135 (octal)
            this.size = extractOctalLong(header, 124, 12);

            // Type: byte 156
            this.type = (char) header[156];

            // Link name: bytes 157-256
            this.linkName = extractString(header, 157, 100);
        }

        public String getName() { return name; }
        public int getMode() { return mode; }
        public long getSize() { return size; }
        public String getLinkName() { return linkName; }

        public boolean isDirectory() {
            return type == '5' || name.endsWith("/");
        }

        public boolean isSymlink() {
            return type == '2';
        }

        public boolean isHardlink() {
            // THIS IS THE KEY FIX: type '1' is a hardlink!
            return type == '1';
        }

        public boolean isRegularFile() {
            return type == '0' || type == 0 || type == ' ';
        }

        private static String extractString(byte[] data, int offset, int length) {
            int end = offset;
            while (end < offset + length && data[end] != 0) {
                end++;
            }
            return new String(data, offset, end - offset);
        }

        private static int extractOctal(byte[] data, int offset, int length) {
            return (int) extractOctalLong(data, offset, length);
        }

        private static long extractOctalLong(byte[] data, int offset, int length) {
            String s = extractString(data, offset, length).trim();
            if (s.isEmpty()) return 0;
            try {
                return Long.parseLong(s, 8);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
