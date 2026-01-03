package jackpal.androidterm;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.util.TypedValue;
import android.widget.Toast;
import android.util.Log;

import java.io.File;

/**
 * Standalone terminal launcher.
 * Extracts proot + Alpine Linux and launches a terminal session.
 */
public class TermuxCheckActivity extends Activity {
    private static final String TAG = "StandaloneLauncher";

    private LinearLayout mainLayout;
    private TextView statusText;
    private Button actionButton;
    private TextView infoText;

    private AssetExtractor assetExtractor;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());
        assetExtractor = new AssetExtractor(this);

        createUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndLaunch();
    }

    private void createUI() {
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setGravity(Gravity.CENTER);
        mainLayout.setBackgroundColor(Color.parseColor("#1a1a2e"));
        mainLayout.setPadding(48, 48, 48, 48);

        // Title
        TextView title = new TextView(this);
        title.setText("Android AI CLI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        mainLayout.addView(title);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Standalone Terminal");
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 8, 0, 48);
        mainLayout.addView(subtitle);

        // Status text
        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 0, 0, 32);
        mainLayout.addView(statusText);

        // Main action button
        actionButton = new Button(this);
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(0, 16, 0, 16);
        actionButton.setLayoutParams(btnParams);
        actionButton.setText("Launch Terminal");
        actionButton.setOnClickListener(v -> launchTerminal());
        mainLayout.addView(actionButton);

        // Info text
        infoText = new TextView(this);
        infoText.setText("Standalone Linux terminal\nusing proot + Alpine Linux");
        infoText.setTextColor(Color.parseColor("#666666"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, 48, 0, 0);
        mainLayout.addView(infoText);

        setContentView(mainLayout);
    }

    private void checkAndLaunch() {
        Log.i(TAG, "Checking if assets are extracted...");

        if (assetExtractor.isExtracted()) {
            Log.i(TAG, "Assets already extracted, ready to launch");
            statusText.setText("Ready!");
            statusText.setTextColor(Color.parseColor("#4CAF50"));
            actionButton.setText("Launch Terminal");
            actionButton.setEnabled(true);
        } else {
            Log.i(TAG, "Assets not extracted, need to set up");
            statusText.setText("First-time setup required");
            statusText.setTextColor(Color.parseColor("#FFA500"));
            actionButton.setText("Setup & Launch");
            actionButton.setEnabled(true);
        }
    }

    private void launchTerminal() {
        if (assetExtractor.isExtracted()) {
            // Assets ready, launch terminal directly
            startTerminalActivity();
        } else {
            // Need to extract first
            extractAssets();
        }
    }

    private void extractAssets() {
        // Show progress dialog
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Setting up...");
        progress.setMessage("Extracting Linux environment...");
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progress.setCancelable(false);
        progress.show();

        // Run extraction in background
        new Thread(() -> {
            try {
                assetExtractor.extractAll(message -> {
                    mainHandler.post(() -> progress.setMessage(message));
                });

                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Setup complete!", Toast.LENGTH_SHORT).show();
                    startTerminalActivity();
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to extract assets", e);
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    statusText.setText("Setup failed: " + e.getMessage());
                    statusText.setTextColor(Color.parseColor("#ff6666"));
                });
            }
        }).start();
    }

    private void startTerminalActivity() {
        Log.i(TAG, "Starting terminal activity");

        // Pass the asset paths to the Term activity
        Intent intent = new Intent(this, Term.class);
        intent.putExtra("proot_binary", assetExtractor.getProotBinary().getAbsolutePath());
        intent.putExtra("rootfs_dir", assetExtractor.getRootfsDir().getAbsolutePath());
        intent.putExtra("busybox_binary", assetExtractor.getBusyboxBinary().getAbsolutePath());
        intent.putExtra("use_proot", true);
        startActivity(intent);

        // Don't finish - allow user to come back
    }
}
