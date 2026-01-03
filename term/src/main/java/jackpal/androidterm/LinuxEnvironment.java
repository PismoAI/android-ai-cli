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
     * NUCLEAR cleanup - the most aggressive possible cleanup for stuck files
     * Call this before setup() if previous attempts failed
     */
    public void nuclearCleanup() {
        Log.i(TAG, "=== NUCLEAR CLEANUP STARTING ===");
        String basePath = baseDir.getAbsolutePath();

        // Method 1: Try to chmod everything writable first
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "chmod -R 777 '" + basePath + "' 2>/dev/null || true");
            pb.start().waitFor();
            Log.i(TAG, "chmod -R 777 attempted");
        } catch (Exception e) {
            Log.w(TAG, "chmod failed: " + e.getMessage());
        }

        // Method 2: Delete all symlinks first (they cause EROFS)
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "find '" + basePath + "' -type l -delete 2>/dev/null || true");
            pb.start().waitFor();
            Log.i(TAG, "Deleted symlinks");
        } catch (Exception e) {
            Log.w(TAG, "symlink delete failed: " + e.getMessage());
        }

        // Method 3: Delete all regular files
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "find '" + basePath + "' -type f -delete 2>/dev/null || true");
            pb.start().waitFor();
            Log.i(TAG, "Deleted regular files");
        } catch (Exception e) {
            Log.w(TAG, "file delete failed: " + e.getMessage());
        }

        // Method 4: Remove all directories bottom-up
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "find '" + basePath + "' -type d -depth -delete 2>/dev/null || true");
            pb.start().waitFor();
            Log.i(TAG, "Deleted directories");
        } catch (Exception e) {
            Log.w(TAG, "dir delete failed: " + e.getMessage());
        }

        // Method 5: Nuclear rm -rf
        try {
            ProcessBuilder pb = new ProcessBuilder("rm", "-rf", basePath);
            pb.start().waitFor();
            Log.i(TAG, "rm -rf attempted");
        } catch (Exception e) {
            Log.w(TAG, "rm -rf failed: " + e.getMessage());
        }

        // Method 6: Try again with shell
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "rm -rf '" + basePath + "'");
            pb.start().waitFor();
        } catch (Exception e) {
            Log.w(TAG, "shell rm -rf failed: " + e.getMessage());
        }

        // Method 7: Java recursive delete as final fallback
        deleteRecursive(baseDir);

        // Method 8: If STILL exists, try to at least clear the rootfs
        File rootfs = new File(baseDir, "rootfs");
        if (rootfs.exists()) {
            Log.w(TAG, "baseDir still exists, trying to clear rootfs specifically");
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "chmod -R 777 '" + rootfs.getAbsolutePath() + "' 2>/dev/null; " +
                    "rm -rf '" + rootfs.getAbsolutePath() + "' 2>/dev/null");
                pb.start().waitFor();
            } catch (Exception e) {
                Log.w(TAG, "rootfs cleanup failed: " + e.getMessage());
            }
            deleteRecursive(rootfs);
        }

        // Final check
        if (baseDir.exists()) {
            Log.e(TAG, "WARNING: Could not fully clean " + basePath + " - files may remain");
            // List what's left
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "find '" + basePath + "' -ls 2>/dev/null | head -50");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.w(TAG, "Remaining: " + line);
                }
                p.waitFor();
            } catch (Exception e) {
                // ignore
            }
        } else {
            Log.i(TAG, "=== NUCLEAR CLEANUP SUCCESSFUL ===");
        }
    }

    /**
     * Diagnostic test to verify file operations work before extraction
     */
    private void runDiagnosticTest() throws IOException {
        Log.i(TAG, "=== DIAGNOSTIC TEST STARTING ===");

        // Test 1: Can we create the base directories?
        Log.i(TAG, "Test 1: Creating directories...");
        File testDir = new File(baseDir, "rootfs/bin");
        if (!testDir.exists() && !testDir.mkdirs()) {
            throw new IOException("DIAG: Cannot create directory: " + testDir);
        }
        testDir.setWritable(true, false);
        testDir.setReadable(true, false);
        testDir.setExecutable(true, false);
        Log.i(TAG, "Test 1 PASSED: Created " + testDir);

        // Test 2: Can we create a regular file?
        Log.i(TAG, "Test 2: Creating regular file...");
        File testFile = new File(testDir, "test_file_" + System.currentTimeMillis());
        FileOutputStream fos = new FileOutputStream(testFile);
        fos.write("test".getBytes());
        fos.close();
        Log.i(TAG, "Test 2 PASSED: Created " + testFile);
        testFile.delete();

        // Test 3: Can we create a file named "sh"?
        Log.i(TAG, "Test 3: Creating file named 'sh'...");
        File shFile = new File(testDir, "sh");
        // First ensure it doesn't exist
        forceDelete(shFile);
        // Now try to create it
        try {
            FileOutputStream shFos = new FileOutputStream(shFile);
            shFos.write("#!/bin/sh\necho test\n".getBytes());
            shFos.close();
            Log.i(TAG, "Test 3 PASSED: Created " + shFile);
            shFile.delete();
        } catch (IOException e) {
            Log.e(TAG, "Test 3 FAILED: " + e.getMessage());
            // Try with shell
            Log.i(TAG, "Test 3b: Trying with shell...");
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                    "echo 'test' > '" + shFile.getAbsolutePath() + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, "shell: " + line);
                }
                int exit = p.waitFor();
                Log.i(TAG, "Shell exit code: " + exit);
                if (shFile.exists()) {
                    Log.i(TAG, "Test 3b PASSED: Shell created file");
                    shFile.delete();
                } else {
                    throw new IOException("DIAG: Shell also cannot create 'sh' file");
                }
            } catch (Exception e2) {
                Log.e(TAG, "Test 3b FAILED: " + e2.getMessage());
                throw new IOException("DIAG: Cannot create file named 'sh': " + e.getMessage());
            }
        }

        // Test 4: Can we create a file named "busybox"?
        Log.i(TAG, "Test 4: Creating file named 'busybox'...");
        File bbFile = new File(testDir, "busybox");
        forceDelete(bbFile);
        FileOutputStream bbFos = new FileOutputStream(bbFile);
        bbFos.write("test busybox content".getBytes());
        bbFos.close();
        Log.i(TAG, "Test 4 PASSED: Created " + bbFile);
        bbFile.delete();

        // Clean up test directory
        testDir.delete();
        new File(baseDir, "rootfs").delete();

        Log.i(TAG, "=== DIAGNOSTIC TEST PASSED ===");
    }

    /**
     * Set up the Linux environment (call from background thread)
     */
    public void setup(SetupCallback callback) {
        String currentStep = "initializing";
        try {
            // NUCLEAR cleanup - aggressively remove any previous failed attempt
            currentStep = "nuclear cleanup";
            callback.onProgress("Nuclear cleanup...", 2);
            nuclearCleanup();

            // DIAGNOSTIC: Test if we can create files before proceeding
            currentStep = "diagnostic test";
            callback.onProgress("Testing file creation...", 3);
            runDiagnosticTest();

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

    private void extractBusybox(File busyboxFile) throws IOException {
        Log.i(TAG, "Extracting busybox from assets");

        // Ensure parent directory exists
        File parentDir = busyboxFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
            parentDir.setReadable(true, false);
            parentDir.setWritable(true, false);
            parentDir.setExecutable(true, false);
        }

        InputStream in = context.getAssets().open("bin/busybox-aarch64");
        FileOutputStream out = new FileOutputStream(busyboxFile);

        byte[] buffer = new byte[8192];
        int len;
        int total = 0;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
            total += len;
        }

        in.close();
        out.close();

        busyboxFile.setExecutable(true, false);
        busyboxFile.setReadable(true, false);
        Log.i(TAG, "Extracted busybox: " + total + " bytes");
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

        // AGGRESSIVE CLEANUP: Delete the entire destDir and recreate it
        // This ensures no leftover files or symlinks from previous attempts
        if (destDir.exists()) {
            Log.i(TAG, "Deleting existing destDir to ensure clean extraction");
            deleteRecursiveWithShell(destDir);
        }

        // Recreate directory
        if (!destDir.mkdirs()) {
            throw new IOException("Failed to create destDir: " + destDir);
        }
        destDir.setReadable(true, false);
        destDir.setWritable(true, false);
        destDir.setExecutable(true, false);

        // Verify we can write
        File testFile = new File(destDir, ".write_test_" + System.currentTimeMillis());
        try {
            if (!testFile.createNewFile()) {
                throw new IOException("Cannot create test file in destDir");
            }
            testFile.delete();
        } catch (Exception e) {
            throw new IOException("destDir is not writable: " + e.getMessage());
        }
        Log.i(TAG, "destDir is writable, proceeding with extraction");

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

                // Normalize the entry name for checking
                String normalizedName = name.startsWith("/") ? name.substring(1) : name;

                // SKIP ALL bin and sbin entries EXCEPT busybox
                // We'll create bin/ and sbin/ directories fresh with proper Android permissions
                boolean isBusybox = normalizedName.equals("bin/busybox");
                if ((normalizedName.equals("bin") || normalizedName.equals("bin/") ||
                    normalizedName.equals("sbin") || normalizedName.equals("sbin/") ||
                    normalizedName.startsWith("bin/") || normalizedName.startsWith("sbin/")) && !isBusybox) {
                    Log.d(TAG, "Skipping bin/sbin entry: " + name);
                    if (!entry.isDirectory()) {
                        symlinkCount++; // Count as skipped
                    }
                    continue;
                }

                // Special handling for busybox - extract it even if marked as symlink/hardlink
                if (isBusybox) {
                    Log.i(TAG, "Found busybox entry: " + name + " (type=" + (int)entry.type + ")");
                    File binDirForBusybox = new File(destDir, "bin");
                    if (!binDirForBusybox.exists()) {
                        binDirForBusybox.mkdirs();
                        binDirForBusybox.setReadable(true, false);
                        binDirForBusybox.setWritable(true, false);
                        binDirForBusybox.setExecutable(true, false);
                    }
                    // Force extract busybox as regular file
                    File busyboxFile = new File(destDir, "bin/busybox");
                    forceDelete(busyboxFile);
                    FileOutputStream out = new FileOutputStream(busyboxFile);
                    tarIn.copyEntryContents(out);
                    out.close();
                    busyboxFile.setExecutable(true, false);
                    busyboxFile.setReadable(true, false);
                    Log.i(TAG, "Extracted busybox: " + busyboxFile.length() + " bytes");
                    fileCount++;
                    continue;
                }

                // Log what we're extracting for debugging
                if (fileCount < 20 || fileCount % 100 == 0) {
                    Log.d(TAG, "Extracting: " + name + " (type=" + (entry.isDirectory() ? "dir" : entry.isSymlink() ? "symlink" : "file") + ")");
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

                    // Extract file - with aggressive pre-cleanup
                    try {
                        // ALWAYS force delete before creating - handles broken symlinks
                        forceDelete(outFile);

                        // Double-check parent is writable
                        if (!parentDir.canWrite()) {
                            Log.w(TAG, "Parent not writable, forcing: " + parentDir);
                            parentDir.setWritable(true, false);
                        }

                        FileOutputStream out = new FileOutputStream(outFile);
                        try {
                            tarIn.copyEntryContents(out);
                        } finally {
                            out.close();
                        }
                    } catch (IOException e) {
                        // Log detailed debug info
                        Log.e(TAG, "=== EXTRACTION FAILED ===");
                        Log.e(TAG, "File: " + name);
                        Log.e(TAG, "Full path: " + outFile.getAbsolutePath());
                        Log.e(TAG, "Parent exists: " + parentDir.exists());
                        Log.e(TAG, "Parent writable: " + parentDir.canWrite());
                        Log.e(TAG, "File exists: " + outFile.exists());
                        Log.e(TAG, "Error: " + e.getMessage());

                        // Try one more time with shell deletion
                        try {
                            ProcessBuilder pb = new ProcessBuilder("rm", "-f", outFile.getAbsolutePath());
                            pb.start().waitFor();
                            // Retry
                            FileOutputStream out = new FileOutputStream(outFile);
                            tarIn.copyEntryContents(out);
                            out.close();
                            Log.i(TAG, "Retry succeeded for: " + name);
                        } catch (Exception e2) {
                            Log.e(TAG, "Retry also failed: " + e2.getMessage());
                            throw new IOException(outFile.getAbsolutePath() + ": " + e.getMessage());
                        }
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

        // SKIP symlink creation entirely - Android has issues with symlinks
        // The createCriticalSymlinks() function will copy busybox to required locations
        Log.i(TAG, "Skipping " + symlinks.size() + " symlinks (will copy files instead)");
    }

    private void createCriticalSymlinks(File destDir) throws IOException {
        File binDir = new File(destDir, "bin");
        File sbinDir = new File(destDir, "sbin");
        File busybox = new File(binDir, "busybox");
        File sh = new File(binDir, "sh");

        Log.i(TAG, "=== Creating critical symlinks ===");

        // Create bin/ and sbin/ directories with full permissions
        if (!binDir.exists()) {
            binDir.mkdirs();
        }
        binDir.setReadable(true, false);
        binDir.setWritable(true, false);
        binDir.setExecutable(true, false);

        if (!sbinDir.exists()) {
            sbinDir.mkdirs();
        }
        sbinDir.setReadable(true, false);
        sbinDir.setWritable(true, false);
        sbinDir.setExecutable(true, false);

        Log.i(TAG, "binDir exists: " + binDir.exists() + ", writable: " + binDir.canWrite());

        // Extract busybox from assets if missing or empty
        if (!busybox.exists() || busybox.length() == 0) {
            Log.i(TAG, "Extracting busybox from assets...");
            extractBusybox(busybox);
        }
        busybox.setExecutable(true, false);
        busybox.setReadable(true, false);
        Log.i(TAG, "busybox ready: " + busybox.length() + " bytes");

        // Create /bin/sh by copying busybox (simple and reliable)
        if (!sh.exists()) {
            Log.i(TAG, "Creating /bin/sh by copying busybox...");
            forceDelete(sh);
            copyFile(busybox, sh);
            sh.setExecutable(true, false);
            sh.setReadable(true, false);
        }

        if (!sh.exists()) {
            throw new IOException("Failed to create /bin/sh");
        }

        Log.i(TAG, "Critical files created: busybox=" + busybox.exists() + ", sh=" + sh.exists());
    }

    private void createSymlink(File destDir, String linkPath, String target) {
        File linkFile = new File(destDir, linkPath);
        File parentDir = linkFile.getParentFile();

        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        parentDir.setWritable(true, false);

        // Delete any existing file/symlink first to prevent EROFS errors
        forceDelete(linkFile);

        // Try to create symlink using Java NIO (Android 8+)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Files.createSymbolicLink(linkFile.toPath(), Paths.get(target));
                Log.d(TAG, "Created symlink: " + linkPath + " -> " + target);
                return;
            } catch (Exception e) {
                Log.w(TAG, "NIO symlink failed for " + linkPath + ": " + e.getMessage());
                // Delete failed symlink attempt
                forceDelete(linkFile);
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
            // Delete failed symlink attempt
            forceDelete(linkFile);
        } catch (Exception e) {
            Log.w(TAG, "ln symlink failed for " + linkPath + ": " + e.getMessage());
            forceDelete(linkFile);
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

    /**
     * More aggressive deletion using shell commands - handles stubborn files/symlinks
     */
    private void deleteRecursiveWithShell(File dir) {
        String path = dir.getAbsolutePath();
        Log.i(TAG, "Aggressive delete of: " + path);

        // Try shell rm -rf first (most reliable)
        try {
            ProcessBuilder pb = new ProcessBuilder("rm", "-rf", path);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "rm -rf: " + line);
            }
            p.waitFor();
            if (!dir.exists()) {
                Log.i(TAG, "rm -rf succeeded");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "rm -rf failed: " + e.getMessage());
        }

        // Fallback to Java deletion
        deleteRecursive(dir);

        // If still exists, try individual file deletion with shell
        if (dir.exists()) {
            try {
                // Find and delete all files
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "find '" + path + "' -type f -exec rm -f {} \\; 2>/dev/null; " +
                    "find '" + path + "' -type l -exec rm -f {} \\; 2>/dev/null; " +
                    "rm -rf '" + path + "' 2>/dev/null"
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
            } catch (Exception e) {
                Log.w(TAG, "find/rm failed: " + e.getMessage());
            }
        }

        // Final Java attempt
        if (dir.exists()) {
            deleteRecursive(dir);
        }

        if (dir.exists()) {
            Log.w(TAG, "Warning: Could not fully delete " + path);
        }
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
