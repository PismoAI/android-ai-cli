package jackpal.androidterm;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.util.zip.GZIPInputStream;

import org.tukaani.xz.XZInputStream;

/**
 * Manages the Node.js environment - simpler approach without proot
 * Just bundles node binary + npm directly
 */
public class NodeEnvironment {
    private static final String TAG = "NodeEnvironment";

    private final Context context;
    private final File baseDir;
    private final File binDir;
    private final File libDir;
    private final File nodeBinary;
    private final File homeDir;

    public interface SetupCallback {
        void onProgress(String message, int percent);
        void onComplete(boolean success, String error);
    }

    public NodeEnvironment(Context context) {
        this.context = context;
        this.baseDir = new File(context.getFilesDir(), "node");
        this.binDir = new File(baseDir, "bin");
        this.libDir = new File(baseDir, "lib");
        this.nodeBinary = new File(binDir, "node");
        this.homeDir = new File(baseDir, "home");
    }

    /**
     * Check if the Node environment is already set up
     */
    public boolean isSetupComplete() {
        File marker = new File(baseDir, ".setup_complete");
        return marker.exists() && nodeBinary.exists() && nodeBinary.canExecute();
    }

    /**
     * Get the command to launch a shell with node in PATH
     */
    public String[] getShellCommand() {
        if (!isSetupComplete()) {
            // Fall back to system shell if not set up
            return new String[]{"/system/bin/sh"};
        }

        // Use system shell but with node in PATH
        return new String[]{
            "/system/bin/sh",
            "-l"
        };
    }

    /**
     * Get environment variables for the shell
     */
    public String[] getEnvironment() {
        String nodePath = binDir.getAbsolutePath();
        String npmGlobalBin = new File(libDir, "node_modules/.bin").getAbsolutePath();

        return new String[]{
            "HOME=" + homeDir.getAbsolutePath(),
            "USER=user",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=" + nodePath + ":" + npmGlobalBin + ":/system/bin:/system/xbin",
            "NODE_PATH=" + new File(libDir, "node_modules").getAbsolutePath(),
            "NPM_CONFIG_PREFIX=" + libDir.getAbsolutePath(),
            "TMPDIR=" + new File(baseDir, "tmp").getAbsolutePath(),
            "npm_config_prefix=" + libDir.getAbsolutePath(),
        };
    }

    /**
     * Reset the environment (delete all files for fresh start)
     */
    public void reset() {
        Log.i(TAG, "Resetting Node environment...");
        new File(baseDir, ".setup_complete").delete();
        deleteRecursive(baseDir);
        Log.i(TAG, "Reset complete");
    }

    /**
     * Set up the Node environment
     */
    public void setup(SetupCallback callback) {
        String currentStep = "initializing";
        try {
            // Clean up any previous install
            currentStep = "cleanup";
            callback.onProgress("Cleaning up...", 5);
            if (baseDir.exists()) {
                deleteRecursive(baseDir);
            }

            // Create directories
            currentStep = "creating directories";
            callback.onProgress("Creating directories...", 10);
            createDirectories();

            // Extract node binary
            currentStep = "extracting node";
            callback.onProgress("Extracting Node.js binary...", 20);
            extractNode();

            // Extract npm modules
            currentStep = "extracting npm";
            callback.onProgress("Extracting npm...", 50);
            extractNpm();

            // Create wrapper scripts
            currentStep = "creating wrappers";
            callback.onProgress("Creating wrapper scripts...", 80);
            createWrapperScripts();

            // Create profile
            currentStep = "creating profile";
            callback.onProgress("Creating shell profile...", 90);
            createProfile();

            // Mark setup complete
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
        File[] dirs = {baseDir, binDir, libDir, homeDir, new File(baseDir, "tmp")};
        for (File dir : dirs) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Failed to create directory: " + dir);
            }
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        }
        Log.i(TAG, "Directories created");
    }

    private void extractNode() throws IOException {
        Log.i(TAG, "Extracting node from assets/bin/node-aarch64.xz");

        InputStream in = context.getAssets().open("bin/node-aarch64.xz");
        XZInputStream xzIn = new XZInputStream(in);
        FileOutputStream out = new FileOutputStream(nodeBinary);

        byte[] buffer = new byte[32768];
        int len;
        long total = 0;
        while ((len = xzIn.read(buffer)) > 0) {
            out.write(buffer, 0, len);
            total += len;
        }

        xzIn.close();
        out.close();

        nodeBinary.setExecutable(true, false);
        nodeBinary.setReadable(true, false);

        Log.i(TAG, "Extracted node: " + total + " bytes, executable: " + nodeBinary.canExecute());

        // Verify it works
        try {
            ProcessBuilder pb = new ProcessBuilder(nodeBinary.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String version = reader.readLine();
            int exitCode = p.waitFor();
            Log.i(TAG, "Node version: " + version + " (exit code: " + exitCode + ")");
            if (exitCode != 0) {
                throw new IOException("Node binary failed to run, exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            throw new IOException("Node verification interrupted", e);
        }
    }

    private void extractNpm() throws IOException {
        Log.i(TAG, "Extracting npm from assets/bin/node_modules.tar.xz");

        InputStream in = context.getAssets().open("bin/node_modules.tar.xz");
        XZInputStream xzIn = new XZInputStream(in);
        TarInputStream tarIn = new TarInputStream(xzIn);

        int fileCount = 0;
        TarEntry entry;
        while ((entry = tarIn.getNextEntry()) != null) {
            String name = entry.getName();
            if (name == null || name.isEmpty()) continue;

            // Remove leading ./
            while (name.startsWith("./")) {
                name = name.substring(2);
            }
            if (name.isEmpty()) continue;

            // Security check
            if (name.contains("..")) {
                Log.w(TAG, "Skipping suspicious entry: " + name);
                continue;
            }

            File outFile = new File(libDir, name);

            if (entry.isDirectory()) {
                outFile.mkdirs();
                outFile.setReadable(true, false);
                outFile.setWritable(true, false);
                outFile.setExecutable(true, false);
            } else if (!entry.isSymlink()) {
                File parent = outFile.getParentFile();
                if (!parent.exists()) {
                    parent.mkdirs();
                }
                parent.setReadable(true, false);
                parent.setWritable(true, false);
                parent.setExecutable(true, false);

                FileOutputStream out = new FileOutputStream(outFile);
                tarIn.copyEntryContents(out);
                out.close();

                // Make scripts executable
                if (name.endsWith(".js") || name.contains("/bin/")) {
                    outFile.setExecutable(true, false);
                }
                outFile.setReadable(true, false);
                fileCount++;
            }
        }
        tarIn.close();

        Log.i(TAG, "Extracted " + fileCount + " files for npm");
    }

    private void createWrapperScripts() throws IOException {
        // Create npm wrapper that uses our node
        File npmScript = new File(binDir, "npm");
        FileWriter fw = new FileWriter(npmScript);
        fw.write("#!/system/bin/sh\n");
        fw.write("exec \"" + nodeBinary.getAbsolutePath() + "\" ");
        fw.write("\"" + new File(libDir, "node_modules/npm/bin/npm-cli.js").getAbsolutePath() + "\" ");
        fw.write("\"$@\"\n");
        fw.close();
        npmScript.setExecutable(true, false);
        Log.i(TAG, "Created npm wrapper");

        // Create npx wrapper
        File npxScript = new File(binDir, "npx");
        fw = new FileWriter(npxScript);
        fw.write("#!/system/bin/sh\n");
        fw.write("exec \"" + nodeBinary.getAbsolutePath() + "\" ");
        fw.write("\"" + new File(libDir, "node_modules/npm/bin/npx-cli.js").getAbsolutePath() + "\" ");
        fw.write("\"$@\"\n");
        fw.close();
        npxScript.setExecutable(true, false);
        Log.i(TAG, "Created npx wrapper");
    }

    private void createProfile() throws IOException {
        // Create .profile for the shell
        File profile = new File(homeDir, ".profile");
        FileWriter fw = new FileWriter(profile);
        fw.write("#!/system/bin/sh\n");
        fw.write("export HOME=\"" + homeDir.getAbsolutePath() + "\"\n");
        fw.write("export PATH=\"" + binDir.getAbsolutePath() + ":");
        fw.write(new File(libDir, "node_modules/.bin").getAbsolutePath() + ":$PATH\"\n");
        fw.write("export NODE_PATH=\"" + new File(libDir, "node_modules").getAbsolutePath() + "\"\n");
        fw.write("export NPM_CONFIG_PREFIX=\"" + libDir.getAbsolutePath() + "\"\n");
        fw.write("export TMPDIR=\"" + new File(baseDir, "tmp").getAbsolutePath() + "\"\n");
        fw.write("\n");
        fw.write("# Welcome message\n");
        fw.write("echo ''\n");
        fw.write("echo 'Android AI CLI - Node.js Environment'\n");
        fw.write("echo ''\n");
        fw.write("node --version && npm --version\n");
        fw.write("echo ''\n");
        fw.write("echo 'To install Claude Code: npm install -g @anthropic-ai/claude-code'\n");
        fw.write("echo ''\n");
        fw.close();
        Log.i(TAG, "Created profile");

        // Also create .bashrc for if bash gets installed
        File bashrc = new File(homeDir, ".bashrc");
        fw = new FileWriter(bashrc);
        fw.write("[ -f ~/.profile ] && . ~/.profile\n");
        fw.close();
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
        file.setWritable(true, false);
        file.delete();
    }

    // Simple TAR implementation
    private static class TarInputStream extends FilterInputStream {
        private TarEntry currentEntry;
        private long remaining;

        public TarInputStream(InputStream in) {
            super(in);
        }

        public TarEntry getNextEntry() throws IOException {
            while (remaining > 0) {
                remaining -= skip(remaining);
            }

            byte[] header = new byte[512];
            int read = 0;
            while (read < 512) {
                int r = in.read(header, read, 512 - read);
                if (r < 0) return null;
                read += r;
            }

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

        String getName() { return name; }
        String getLinkName() { return linkName; }
        boolean isSymlink() { return type == '2' || type == '1'; }
        boolean isDirectory() { return type == '5' || name.endsWith("/"); }

        TarEntry(byte[] header) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100 && header[i] != 0; i++) {
                sb.append((char) header[i]);
            }
            name = sb.toString();

            sb = new StringBuilder();
            for (int i = 157; i < 257 && header[i] != 0; i++) {
                sb.append((char) header[i]);
            }
            linkName = sb.toString();

            sb = new StringBuilder();
            for (int i = 124; i < 136 && header[i] != 0 && header[i] != ' '; i++) {
                sb.append((char) header[i]);
            }
            try {
                size = Long.parseLong(sb.toString().trim(), 8);
            } catch (NumberFormatException e) {
                size = 0;
            }

            type = header[156];
        }
    }
}
