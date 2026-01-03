package jackpal.androidterm;

import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import jackpal.androidterm.compat.FileCompat;
import jackpal.androidterm.util.TermSettings;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A terminal session that runs a shell inside proot with Alpine Linux.
 *
 * Key configuration:
 * - Uses --link2symlink to handle symlinks properly
 * - Sets PROOT_NO_SECCOMP=1 to avoid seccomp issues on Android
 * - Sets explicit PATH for the Alpine environment
 */
public class ProotShellSession extends GenericTermSession {
    private static final String TAG = "ProotShellSession";

    private int mProcId;
    private Thread mWatcherThread;
    private String mInitialCommand;

    private final File mProotBinary;
    private final File mRootfsDir;
    private final File mBusyboxBinary;

    private static final int PROCESS_EXITED = 1;
    private Handler mMsgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (!isRunning()) {
                return;
            }
            if (msg.what == PROCESS_EXITED) {
                onProcessExit((Integer) msg.obj);
            }
        }
    };

    public ProotShellSession(TermSettings settings, String initialCommand,
                              File prootBinary, File rootfsDir, File busyboxBinary) throws IOException {
        super(ParcelFileDescriptor.open(new File("/dev/ptmx"), ParcelFileDescriptor.MODE_READ_WRITE),
                settings, false);

        this.mProotBinary = prootBinary;
        this.mRootfsDir = rootfsDir;
        this.mBusyboxBinary = busyboxBinary;
        this.mInitialCommand = initialCommand;

        initializeSession();

        setTermOut(new ParcelFileDescriptor.AutoCloseOutputStream(mTermFd));
        setTermIn(new ParcelFileDescriptor.AutoCloseInputStream(mTermFd));

        mWatcherThread = new Thread() {
            @Override
            public void run() {
                Log.i(TAG, "Waiting for proot process: " + mProcId);
                int result = TermExec.waitFor(mProcId);
                Log.i(TAG, "Proot process exited: " + result);
                mMsgHandler.sendMessage(mMsgHandler.obtainMessage(PROCESS_EXITED, result));
            }
        };
        mWatcherThread.setName("Proot watcher");
    }

    private void initializeSession() throws IOException {
        TermSettings settings = mSettings;

        // Build environment variables for proot
        List<String> envList = new ArrayList<>();

        // CRITICAL: Disable seccomp - required for proot on Android
        envList.add("PROOT_NO_SECCOMP=1");

        // Term type
        envList.add("TERM=" + settings.getTermType());

        // Home directory inside proot
        envList.add("HOME=/root");

        // User
        envList.add("USER=root");

        // CRITICAL: Explicit PATH inside Alpine
        envList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

        // Temp directories
        envList.add("TMPDIR=/tmp");

        String[] env = envList.toArray(new String[0]);

        // Build proot command
        // Key flags:
        // -0: Fake root (pretend to be UID 0)
        // -r: Root filesystem path
        // --link2symlink: Convert hardlinks to symlinks (CRITICAL for busybox)
        // -b: Bind mount points
        List<String> cmd = new ArrayList<>();
        cmd.add(mProotBinary.getAbsolutePath());
        cmd.add("-0");  // Fake root
        cmd.add("-r");
        cmd.add(mRootfsDir.getAbsolutePath());
        cmd.add("--link2symlink");  // CRITICAL: Handle symlinks properly

        // Bind mount /dev, /proc, /sys
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");

        // Bind the data directory for persistence
        File homeDir = new File(mRootfsDir.getParentFile(), "home");
        if (!homeDir.exists()) homeDir.mkdirs();
        cmd.add("-b");
        cmd.add(homeDir.getAbsolutePath() + ":/root");

        // Working directory
        cmd.add("-w");
        cmd.add("/root");

        // Shell to run
        cmd.add("/bin/sh");
        cmd.add("-l");  // Login shell

        String[] cmdArray = cmd.toArray(new String[0]);

        Log.i(TAG, "Starting proot: " + String.join(" ", cmdArray));
        Log.i(TAG, "Environment: " + String.join(", ", envList));

        mProcId = TermExec.createSubprocess(mTermFd, cmdArray[0], cmdArray, env);

        if (mProcId <= 0) {
            throw new IOException("Failed to start proot process");
        }

        Log.i(TAG, "Proot started with PID: " + mProcId);
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        super.initializeEmulator(columns, rows);

        mWatcherThread.start();

        // Send initial setup commands
        if (mInitialCommand != null && !mInitialCommand.isEmpty()) {
            write(mInitialCommand + '\r');
        }
    }

    private void onProcessExit(int result) {
        onProcessExit();
    }

    @Override
    public void finish() {
        hangupProcessGroup();
        super.finish();
    }

    /**
     * Send SIGHUP to the process group
     */
    void hangupProcessGroup() {
        if (mProcId > 0) {
            TermExec.sendSignal(-mProcId, 1);  // SIGHUP
        }
    }
}
