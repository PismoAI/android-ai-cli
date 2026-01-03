/*
 * Copyright (C) 2024 Android AI CLI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import jackpal.androidterm.util.TermSettings;

import java.io.*;

/**
 * A terminal session that runs with Node.js in PATH.
 * Uses NodeEnvironment to set up the environment variables.
 */
public class NodeTermSession extends GenericTermSession {
    private int mProcId;
    private Thread mWatcherThread;
    private Context mContext;

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

    public NodeTermSession(Context context, TermSettings settings) throws IOException {
        super(ParcelFileDescriptor.open(new File("/dev/ptmx"), ParcelFileDescriptor.MODE_READ_WRITE),
                settings, false);

        mContext = context;

        initializeSession();

        setTermOut(new ParcelFileDescriptor.AutoCloseOutputStream(mTermFd));
        setTermIn(new ParcelFileDescriptor.AutoCloseInputStream(mTermFd));

        mWatcherThread = new Thread() {
            @Override
            public void run() {
                Log.i(TermDebug.LOG_TAG, "waiting for node shell process: " + mProcId);
                int result = TermExec.waitFor(mProcId);
                Log.i(TermDebug.LOG_TAG, "Shell subprocess exited: " + result);
                mMsgHandler.sendMessage(mMsgHandler.obtainMessage(PROCESS_EXITED, result));
            }
        };
        mWatcherThread.setName("Node shell process watcher");
    }

    private void initializeSession() throws IOException {
        NodeEnvironment nodeEnv = new NodeEnvironment(mContext);

        String[] shellCmd = nodeEnv.getShellCommand();
        String[] envVars = nodeEnv.getEnvironment();

        // Create the subprocess
        mProcId = createSubprocess(shellCmd, envVars);
    }

    private int createSubprocess(String[] args, String[] env) throws IOException {
        if (args == null || args.length == 0) {
            throw new IOException("No shell command provided");
        }

        String arg0 = args[0];
        File file = new File(arg0);

        if (!file.exists()) {
            Log.e(TermDebug.LOG_TAG, "Shell binary " + arg0 + " not found!");
            throw new FileNotFoundException(arg0);
        }

        Log.i(TermDebug.LOG_TAG, "Starting shell: " + arg0);
        for (int i = 0; i < args.length; i++) {
            Log.d(TermDebug.LOG_TAG, "  arg[" + i + "]: " + args[i]);
        }
        for (String e : env) {
            Log.d(TermDebug.LOG_TAG, "  env: " + e);
        }

        return TermExec.createSubprocess(mTermFd, arg0, args, env);
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        super.initializeEmulator(columns, rows);
        mWatcherThread.start();
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
        TermExec.sendSignal(-mProcId, 1);
    }
}
