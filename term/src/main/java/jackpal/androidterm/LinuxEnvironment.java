package jackpal.androidterm;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Linux environment (Alpine Linux via PRoot)
 */
public class LinuxEnvironment {
    private static final String TAG = "LinuxEnvironment";

    // Alpine Linux minirootfs URL (arm64)
    private static final String ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.0-aarch64.tar.gz";

    private final Context context;
    private final File baseDir;
    private final File rootfsDir;
    private final File binDir;
    private final File prootBinary;

    public interface SetupCallback {
        void onProgress(String message, int percent);
        void onComplete(boolean success, String error);
    }

    public LinuxEnvironment(Context context) {
        this.context = context;
        this.baseDir = new File(context.getFilesDir(), "linux");
        this.rootfsDir = new File(baseDir, "rootfs");
        this.binDir = new File(baseDir, "bin");
        this.prootBinary = new File(binDir, "proot");
    }

    /**
     * Check if the Linux environment is already set up
     */
    public boolean isSetupComplete() {
        File marker = new File(baseDir, ".setup_complete");
        return marker.exists() && rootfsDir.exists() && prootBinary.exists();
    }

    /**
     * Get the command to launch a shell in the proot environment
     */
    public String[] getShellCommand() {
        if (!isSetupComplete()) {
            // Fall back to system shell if not set up
            return new String[]{"/system/bin/sh"};
        }

        String prootPath = prootBinary.getAbsolutePath();
        String rootfsPath = rootfsDir.getAbsolutePath();

        return new String[]{
            prootPath,
            "--link2symlink",
            "-0",
            "-r", rootfsPath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/sdcard:/sdcard",
            "-b", context.getFilesDir().getAbsolutePath() + ":/android",
            "-w", "/root",
            "/bin/sh",
            "-l"
        };
    }

    /**
     * Get environment variables for the proot shell
     */
    public String[] getEnvironment() {
        return new String[]{
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR=" + new File(baseDir, "tmp").getAbsolutePath(),
            "PROOT_NO_SECCOMP=1"
        };
    }

    /**
     * Reset the Linux environment (delete all files for fresh start)
     */
    public void reset() {
        Log.i(TAG, "Resetting Linux environment...");
        // Delete setup complete marker
        new File(baseDir, ".setup_complete").delete();
        // Delete the entire linux directory
        deleteRecursive(baseDir);
        Log.i(TAG, "Reset complete");
    }

    /**
     * Set up the Linux environment (call from background thread)
     */
    public void setup(SetupCallback callback) {
        String currentStep = "initializing";
        try {
            // Reset any previous failed attempt
            currentStep = "resetting previous attempt";
            callback.onProgress("Cleaning up...", 2);
            reset();

            currentStep = "creating directories";
            callback.onProgress("Creating directories...", 5);
            createDirectories();
            Log.i(TAG, "Directories created successfully");

            currentStep = "extracting proot";
            callback.onProgress("Extracting proot binary...", 10);
            extractProot();
            Log.i(TAG, "Proot extracted successfully");

            currentStep = "downloading Alpine";
            callback.onProgress("Downloading Alpine Linux...", 20);
            downloadAlpine(callback);
            Log.i(TAG, "Alpine downloaded successfully");

            currentStep = "extracting Alpine";
            // Note: extraction is called inside downloadAlpine, but log for clarity
            Log.i(TAG, "Extraction should be complete");

            currentStep = "configuring DNS";
            callback.onProgress("Configuring DNS...", 80);
            configureDns();
            Log.i(TAG, "DNS configured");

            currentStep = "copying setup script";
            callback.onProgress("Copying setup script...", 85);
            copySetupScript();
            Log.i(TAG, "Setup script copied");

            currentStep = "creating profile";
            callback.onProgress("Creating profile...", 90);
            createProfile();
            Log.i(TAG, "Profile created");

            // Verify setup
            currentStep = "verifying setup";
            callback.onProgress("Verifying setup...", 95);
            verifySetup();
            Log.i(TAG, "Setup verified");

            // Mark setup as complete
            new File(baseDir, ".setup_complete").createNewFile();
            Log.i(TAG, "Setup complete!");

            callback.onProgress("Setup complete!", 100);
            callback.onComplete(true, null);

        } catch (Exception e) {
            String errorMsg = "Failed while " + currentStep + ": " +
                (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            Log.e(TAG, errorMsg, e);
            callback.onComplete(false, errorMsg);
        }
    }

    private void createDirectories() throws IOException {
        // Create all directories
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IOException("Failed to create base directory: " + baseDir);
        }
        if (!rootfsDir.exists() && !rootfsDir.mkdirs()) {
            throw new IOException("Failed to create rootfs directory: " + rootfsDir);
        }
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new IOException("Failed to create bin directory: " + binDir);
        }
        File tmpDir = new File(baseDir, "tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException("Failed to create tmp directory: " + tmpDir);
        }

        // Set full permissions on all directories
        baseDir.setReadable(true, false);
        baseDir.setWritable(true, false);
        baseDir.setExecutable(true, false);

        rootfsDir.setReadable(true, false);
        rootfsDir.setWritable(true, false);
        rootfsDir.setExecutable(true, false);

        binDir.setReadable(true, false);
        binDir.setWritable(true, false);
        binDir.setExecutable(true, false);

        tmpDir.setReadable(true, false);
        tmpDir.setWritable(true, false);
        tmpDir.setExecutable(true, false);

        // Verify we can write to rootfs
        File testFile = new File(rootfsDir, ".write_test");
        if (!testFile.createNewFile()) {
            throw new IOException("Cannot write to rootfs directory: " + rootfsDir);
        }
        testFile.delete();

        Log.i(TAG, "All directories created and verified writable");
    }

    private void extractProot() throws IOException {
        String arch = Build.SUPPORTED_ABIS[0];
        String assetName;

        if (arch.contains("arm64") || arch.contains("aarch64")) {
            assetName = "bin/proot-aarch64";
        } else if (arch.contains("arm")) {
            assetName = "bin/proot-aarch64"; // Use aarch64 for now
        } else {
            throw new IOException("Unsupported architecture: " + arch);
        }

        InputStream in = context.getAssets().open(assetName);
        FileOutputStream out = new FileOutputStream(prootBinary);

        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }

        in.close();
        out.close();

        prootBinary.setExecutable(true, false);
    }

    private void downloadAlpine(SetupCallback callback) throws IOException {
        File tarFile = new File(baseDir, "alpine.tar.gz");

        Log.i(TAG, "Starting download from " + ALPINE_URL);

        // Download with larger buffer and less frequent updates
        URL url = new URL(ALPINE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "Android AI CLI");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP error: " + responseCode);
        }

        int fileSize = conn.getContentLength();
        Log.i(TAG, "Download size: " + fileSize + " bytes");

        InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
        FileOutputStream fos = new FileOutputStream(tarFile);
        BufferedOutputStream out = new BufferedOutputStream(fos, 65536);

        byte[] buffer = new byte[32768]; // Larger buffer
        int len;
        long downloaded = 0;
        int lastPercent = 20;

        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
            downloaded += len;

            // Update progress less frequently (every ~100KB)
            if (fileSize > 0) {
                int percent = 20 + (int) ((downloaded * 50) / fileSize);
                if (percent > lastPercent) {
                    lastPercent = percent;
                    callback.onProgress("Downloading... " + (downloaded / 1024) + "KB", Math.min(percent, 70));
                }
            }
        }

        out.flush();
        out.close();
        in.close();
        conn.disconnect();

        Log.i(TAG, "Download complete: " + downloaded + " bytes");

        // Verify download
        if (tarFile.length() < 1000000) { // Alpine rootfs should be > 1MB
            throw new IOException("Download incomplete: only " + tarFile.length() + " bytes");
        }

        // Extract
        callback.onProgress("Extracting Alpine Linux...", 75);
        extractTarGz(tarFile, rootfsDir);

        // Clean up
        tarFile.delete();
    }

    private void extractTarGz(File tarGzFile, File destDir) throws IOException {
        Log.i(TAG, "Starting extraction of " + tarGzFile.getAbsolutePath());

        // Always use Java extraction - system tar creates symlinks that cause EROFS errors on Android
        // The Java extraction handles symlinks properly by copying files instead
        Log.i(TAG, "Using Java-based extraction (avoids Android symlink issues)");
        extractTarGzFallback(tarGzFile, destDir);

        // ALWAYS ensure critical symlinks exist
        createCriticalSymlinks(destDir);
    }

    private void extractTarGzFallback(File tarGzFile, File destDir) throws IOException {
        Log.i(TAG, "Starting Java-based extraction to " + destDir.getAbsolutePath());

        // Ensure destDir exists and is writable
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        destDir.setReadable(true, false);
        destDir.setWritable(true, false);
        destDir.setExecutable(true, false);

        // Manual extraction using Java with larger buffer
        FileInputStream fis = new FileInputStream(tarGzFile);
        BufferedInputStream bis = new BufferedInputStream(fis, 65536);
        GZIPInputStream gzIn = new GZIPInputStream(bis, 65536);
        TarInputStream tarIn = new TarInputStream(gzIn);

        int fileCount = 0;
        int dirCount = 0;
        int symlinkCount = 0;
        List<String[]> symlinks = new ArrayList<>(); // Store symlinks to create later
        TarEntry entry;

        try {
            while ((entry = tarIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;

                // Remove leading ./ from path
                while (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (name.isEmpty()) continue;

                // Security: prevent path traversal
                if (name.contains("..")) {
                    Log.w(TAG, "Skipping suspicious entry: " + name);
                    continue;
                }

                File outFile = new File(destDir, name);

                if (entry.isDirectory()) {
                    if (!outFile.exists()) {
                        outFile.mkdirs();
                    }
                    // Always set permissions on directory
                    outFile.setReadable(true, false);
                    outFile.setWritable(true, false);
                    outFile.setExecutable(true, false);
                    dirCount++;
                } else if (entry.isSymlink()) {
                    // Store symlink for later processing (with cleaned name)
                    symlinks.add(new String[]{name, entry.getLinkName()});
                    symlinkCount++;
                } else {
                    // Ensure parent directory exists with proper permissions
                    File parentDir = outFile.getParentFile();
                    if (!parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    // ALWAYS set parent writable before creating file
                    parentDir.setReadable(true, false);
                    parentDir.setWritable(true, false);
                    parentDir.setExecutable(true, false);

                    // Delete existing file if present
                    if (outFile.exists()) {
                        outFile.setWritable(true, false);
                        outFile.delete();
                    }

                    // Extract file
                    try {
                        FileOutputStream out = new FileOutputStream(outFile);
                        try {
                            tarIn.copyEntryContents(out);
                        } finally {
                            out.close();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to extract " + name + ": " + e.getMessage());
                        throw new IOException("Cannot extract " + name + ": " + e.getMessage());
                    }

                    // Set permissions
                    outFile.setReadable(true, false);
                    outFile.setWritable(true, false);
                    if (name.startsWith("bin/") ||
                        name.startsWith("sbin/") ||
                        name.startsWith("usr/bin/") ||
                        name.startsWith("usr/sbin/") ||
                        name.endsWith(".sh")) {
                        outFile.setExecutable(true, false);
                    }
                    fileCount++;
                }

                if ((fileCount + dirCount + symlinkCount) % 200 == 0) {
                    Log.d(TAG, "Extracted " + fileCount + " files, " + dirCount + " dirs, " + symlinkCount + " symlinks...");
                }
            }
            Log.i(TAG, "Extraction complete: " + fileCount + " files, " + dirCount + " dirs, " + symlinkCount + " symlinks");
        } finally {
            tarIn.close();
        }

        // Process symlinks
        Log.i(TAG, "Creating " + symlinks.size() + " symlinks...");
        for (String[] link : symlinks) {
            createSymlink(destDir, link[0], link[1]);
        }
        Log.i(TAG, "Symlinks created");
    }

    private void createCriticalSymlinks(File destDir) throws IOException {
        File binDir = new File(destDir, "bin");
        File busybox = new File(binDir, "busybox");
        File sh = new File(binDir, "sh");

        Log.i(TAG, "Checking critical symlinks...");
        Log.i(TAG, "busybox exists: " + busybox.exists() + ", size: " + busybox.length());
        Log.i(TAG, "sh exists: " + sh.exists());

        if (!busybox.exists()) {
            throw new IOException("busybox not found in extracted rootfs!");
        }

        // Make busybox executable
        busybox.setExecutable(true, false);

        // If /bin/sh doesn't exist, we need to create it
        if (!sh.exists()) {
            Log.i(TAG, "Creating /bin/sh -> busybox using busybox --install");

            // Try busybox --install first
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    busybox.getAbsolutePath(), "--install", "-s", binDir.getAbsolutePath()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, "busybox install: " + line);
                }

                int exitCode = p.waitFor();
                Log.i(TAG, "busybox --install exit code: " + exitCode);

                if (sh.exists()) {
                    Log.i(TAG, "busybox --install succeeded, /bin/sh created");
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "busybox --install failed: " + e.getMessage());
            }

            // Fallback: copy busybox to sh
            Log.i(TAG, "Falling back to copying busybox to sh");
            // forceDelete is called inside copyFile - handles broken symlinks
            copyFile(busybox, sh);
            sh.setExecutable(true, false);

            if (sh.exists()) {
                Log.i(TAG, "Created /bin/sh by copying busybox");
            } else {
                throw new IOException("Failed to create /bin/sh");
            }
        }

        // Also ensure other critical symlinks exist
        String[] criticalLinks = {"ash", "ls", "cat", "cp", "mv", "rm", "mkdir", "chmod", "chown", "ln", "env", "which", "pwd", "echo", "test", "true", "false"};
        for (String cmd : criticalLinks) {
            File cmdFile = new File(binDir, cmd);
            if (!cmdFile.exists() || !cmdFile.canExecute()) {
                try {
                    // forceDelete is called inside copyFile - handles broken symlinks
                    copyFile(busybox, cmdFile);
                    cmdFile.setExecutable(true, false);
                } catch (Exception e) {
                    Log.w(TAG, "Could not create " + cmd + ": " + e.getMessage());
                }
            }
        }

        Log.i(TAG, "Critical symlinks verified/created");
    }

    private void createSymlink(File destDir, String linkPath, String target) {
        File linkFile = new File(destDir, linkPath);
        File parentDir = linkFile.getParentFile();

        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Try to create symlink using Java NIO (Android 8+)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Files.createSymbolicLink(linkFile.toPath(), Paths.get(target));
                Log.d(TAG, "Created symlink: " + linkPath + " -> " + target);
                return;
            } catch (Exception e) {
                Log.w(TAG, "NIO symlink failed for " + linkPath + ": " + e.getMessage());
            }
        }

        // Try using ln -s command
        try {
            ProcessBuilder pb = new ProcessBuilder("ln", "-sf", target, linkFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                Log.d(TAG, "Created symlink via ln: " + linkPath + " -> " + target);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "ln symlink failed for " + linkPath + ": " + e.getMessage());
        }

        // Fallback: if target is a file and exists, copy it
        File targetFile = new File(destDir, target);
        if (!targetFile.isAbsolute()) {
            targetFile = new File(linkFile.getParentFile(), target);
        }

        if (targetFile.exists() && targetFile.isFile()) {
            try {
                copyFile(targetFile, linkFile);
                linkFile.setExecutable(targetFile.canExecute(), false);
                Log.d(TAG, "Copied instead of symlink: " + linkPath + " <- " + target);
                return;
            } catch (IOException e) {
                Log.w(TAG, "Copy fallback failed for " + linkPath + ": " + e.getMessage());
            }
        }

        // Last resort: create a shell script wrapper for common binaries
        if (linkPath.startsWith("bin/") || linkPath.startsWith("usr/bin/") ||
            linkPath.startsWith("sbin/") || linkPath.startsWith("usr/sbin/")) {
            try {
                FileWriter fw = new FileWriter(linkFile);
                fw.write("#!/bin/sh\nexec " + target + " \"$@\"\n");
                fw.close();
                linkFile.setExecutable(true, false);
                Log.d(TAG, "Created wrapper script: " + linkPath + " -> " + target);
            } catch (IOException e) {
                Log.w(TAG, "Wrapper script failed for " + linkPath + ": " + e.getMessage());
            }
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        // Aggressively delete destination first - handles symlinks, broken symlinks, etc.
        forceDelete(dst);

        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        in.close();
        out.close();
    }

    /**
     * Forcefully delete a file, handling symlinks, broken symlinks, and permission issues
     */
    private void forceDelete(File file) {
        String path = file.getAbsolutePath();

        // Method 1: Java NIO Files.deleteIfExists (handles symlinks on Android 8+)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Files.deleteIfExists(file.toPath());
                if (!file.exists() && !Files.isSymbolicLink(file.toPath())) {
                    return; // Success
                }
            } catch (Exception e) {
                Log.d(TAG, "NIO delete failed for " + path + ": " + e.getMessage());
            }
        }

        // Method 2: Standard Java delete
        try {
            file.setWritable(true, false);
            if (file.delete()) {
                return; // Success
            }
        } catch (Exception e) {
            Log.d(TAG, "Java delete failed for " + path + ": " + e.getMessage());
        }

        // Method 3: Shell rm -f command
        try {
            ProcessBuilder pb = new ProcessBuilder("rm", "-f", path);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            if (!file.exists()) {
                return; // Success
            }
        } catch (Exception e) {
            Log.d(TAG, "rm -f failed for " + path + ": " + e.getMessage());
        }

        // Method 4: Shell unlink command
        try {
            ProcessBuilder pb = new ProcessBuilder("unlink", path);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            Log.d(TAG, "unlink failed for " + path + ": " + e.getMessage());
        }

        // Log if still exists
        if (file.exists() || (Build.VERSION.SDK_INT >= 26 && Files.isSymbolicLink(file.toPath()))) {
            Log.w(TAG, "Could not delete " + path + " - file may still exist");
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        // Make writable before deleting
        file.setWritable(true, false);
        file.delete();
    }

    private void configureDns() throws IOException {
        File etcDir = new File(rootfsDir, "etc");
        etcDir.mkdirs();

        FileWriter fw = new FileWriter(new File(etcDir, "resolv.conf"));
        fw.write("nameserver 8.8.8.8\n");
        fw.write("nameserver 8.8.4.4\n");
        fw.close();
        Log.i(TAG, "Created resolv.conf");
    }

    private void copySetupScript() throws IOException {
        File setupScript = new File(rootfsDir, "root/setup.sh");
        setupScript.getParentFile().mkdirs();

        InputStream in = context.getAssets().open("scripts/setup-alpine.sh");
        FileOutputStream out = new FileOutputStream(setupScript);

        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }

        in.close();
        out.close();
        setupScript.setExecutable(true, false);
        Log.i(TAG, "Copied setup.sh to " + setupScript.getAbsolutePath());
    }

    private void createProfile() throws IOException {
        File profileDir = new File(rootfsDir, "root");
        profileDir.mkdirs();

        FileWriter fw = new FileWriter(new File(profileDir, ".profile"));
        fw.write("#!/bin/sh\n");
        fw.write("# Set PATH first - required for proot on Android\n");
        fw.write("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        fw.write("export HOME=/root\n");
        fw.write("\n");
        fw.write("# First-time setup runs automatically\n");
        fw.write("if [ ! -f /root/.setup_done ]; then\n");
        fw.write("    echo ''\n");
        fw.write("    echo '=== First-time setup ===' \n");
        fw.write("    echo 'Installing packages, this may take a few minutes...'\n");
        fw.write("    echo ''\n");
        fw.write("    if /root/setup.sh; then\n");
        fw.write("        touch /root/.setup_done\n");
        fw.write("        echo ''\n");
        fw.write("        echo 'Setup complete! Starting bash...'\n");
        fw.write("        exec /bin/bash --login\n");
        fw.write("    else\n");
        fw.write("        echo 'Setup failed! You can retry by running: /root/setup.sh'\n");
        fw.write("    fi\n");
        fw.write("elif [ -x /bin/bash ]; then\n");
        fw.write("    # Bash is installed, switch to it\n");
        fw.write("    exec /bin/bash --login\n");
        fw.write("else\n");
        fw.write("    # Fallback: source bashrc if it exists\n");
        fw.write("    [ -f /root/.bashrc ] && . /root/.bashrc\n");
        fw.write("fi\n");
        fw.close();
        Log.i(TAG, "Created .profile");
    }

    private void verifySetup() throws IOException {
        // Check that critical files exist
        File[] requiredFiles = {
            prootBinary,
            new File(rootfsDir, "bin/sh"),
            new File(rootfsDir, "etc/resolv.conf"),
            new File(rootfsDir, "root/setup.sh"),
            new File(rootfsDir, "root/.profile")
        };

        for (File f : requiredFiles) {
            if (!f.exists()) {
                throw new IOException("Missing required file: " + f.getAbsolutePath());
            }
            Log.d(TAG, "Verified: " + f.getAbsolutePath());
        }

        // Make sure proot is executable
        if (!prootBinary.canExecute()) {
            prootBinary.setExecutable(true, false);
        }

        Log.i(TAG, "All required files verified");
    }

    // Simple TAR implementation for fallback
    private static class TarInputStream extends FilterInputStream {
        private TarEntry currentEntry;
        private long remaining;

        public TarInputStream(InputStream in) {
            super(in);
        }

        public TarEntry getNextEntry() throws IOException {
            // Skip remaining bytes of current entry
            while (remaining > 0) {
                remaining -= skip(remaining);
            }

            // Read header
            byte[] header = new byte[512];
            int read = 0;
            while (read < 512) {
                int r = in.read(header, read, 512 - read);
                if (r < 0) return null;
                read += r;
            }

            // Check for end of archive
            boolean allZero = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) return null;

            currentEntry = new TarEntry(header);
            remaining = currentEntry.size;

            return currentEntry;
        }

        public void copyEntryContents(OutputStream out) throws IOException {
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            // Skip padding
            long padding = (512 - (currentEntry.size % 512)) % 512;
            while (padding > 0) {
                padding -= in.skip(padding);
            }
        }
    }

    private static class TarEntry {
        String name;
        String linkName;
        long size;
        byte type;

        String getName() {
            return name;
        }

        String getLinkName() {
            return linkName;
        }

        boolean isSymlink() {
            return type == '2' || type == '1'; // '2' = symlink, '1' = hardlink
        }

        TarEntry(byte[] header) {
            // Name: bytes 0-99
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100 && header[i] != 0; i++) {
                sb.append((char) header[i]);
            }
            name = sb.toString();

            // Link name: bytes 157-256 (for symlinks)
            sb = new StringBuilder();
            for (int i = 157; i < 257 && header[i] != 0; i++) {
                sb.append((char) header[i]);
            }
            linkName = sb.toString();

            // Size: bytes 124-135 (octal)
            sb = new StringBuilder();
            for (int i = 124; i < 136 && header[i] != 0 && header[i] != ' '; i++) {
                sb.append((char) header[i]);
            }
            try {
                size = Long.parseLong(sb.toString().trim(), 8);
            } catch (NumberFormatException e) {
                size = 0;
            }

            // Type: byte 156
            type = header[156];
        }

        boolean isDirectory() {
            return type == '5' || name.endsWith("/");
        }
    }
}
