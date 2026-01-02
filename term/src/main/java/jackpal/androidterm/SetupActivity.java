package jackpal.androidterm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Button;
import android.graphics.Color;
import android.view.Gravity;
import android.util.TypedValue;
import android.util.Log;

/**
 * First-launch setup activity that downloads and configures the Linux environment
 */
public class SetupActivity extends Activity {
    private static final String TAG = "SetupActivity";

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private Button retryButton;
    private Handler handler;
    private LinuxEnvironment linuxEnv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Request window features BEFORE super.onCreate()
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        try {
            handler = new Handler(Looper.getMainLooper());
            linuxEnv = new LinuxEnvironment(this);

            // Check if already set up
            if (linuxEnv.isSetupComplete()) {
                Log.i(TAG, "Setup already complete, launching terminal");
                launchTerminal();
                return;
            }

            Log.i(TAG, "Starting first-time setup");

            // Create UI programmatically
            createUI();

            // Start setup
            startSetup();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            showError("Startup error: " + e.getMessage());
        }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", (dialog, which) -> finish())
            .show();
    }

    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#1a1a2e"));
        layout.setPadding(48, 48, 48, 48);

        // Title
        TextView title = new TextView(this);
        title.setText("Android AI CLI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Setting up Linux environment...");
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 16, 0, 48);
        layout.addView(subtitle);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressParams.setMargins(0, 0, 0, 16);
        progressBar.setLayoutParams(progressParams);
        layout.addView(progressBar);

        // Percent text
        percentText = new TextView(this);
        percentText.setText("0%");
        percentText.setTextColor(Color.WHITE);
        percentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        percentText.setGravity(Gravity.CENTER);
        layout.addView(percentText);

        // Status text
        statusText = new TextView(this);
        statusText.setText("Initializing...");
        statusText.setTextColor(Color.parseColor("#aaaaaa"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 24, 0, 0);
        layout.addView(statusText);

        // Retry button (hidden initially)
        retryButton = new Button(this);
        retryButton.setText("Retry");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> startSetup());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(0, 32, 0, 0);
        retryButton.setLayoutParams(btnParams);
        layout.addView(retryButton);

        // Info text
        TextView infoText = new TextView(this);
        infoText.setText("This will download ~50MB and may take a few minutes.\nA full Linux environment with Node.js will be installed.");
        infoText.setTextColor(Color.parseColor("#666666"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, 48, 0, 0);
        layout.addView(infoText);

        setContentView(layout);
    }

    private void startSetup() {
        retryButton.setVisibility(View.GONE);
        progressBar.setProgress(0);
        percentText.setText("0%");
        statusText.setText("Starting setup...");

        new Thread(() -> {
            linuxEnv.setup(new LinuxEnvironment.SetupCallback() {
                @Override
                public void onProgress(String message, int percent) {
                    handler.post(() -> {
                        statusText.setText(message);
                        progressBar.setProgress(percent);
                        percentText.setText(percent + "%");
                    });
                }

                @Override
                public void onComplete(boolean success, String error) {
                    handler.post(() -> {
                        if (success) {
                            launchTerminal();
                        } else {
                            statusText.setText("Setup failed: " + error);
                            statusText.setTextColor(Color.parseColor("#ff6666"));
                            retryButton.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
        }).start();
    }

    private void launchTerminal() {
        Intent intent = new Intent(this, Term.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
