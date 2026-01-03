package jackpal.androidterm;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.util.TypedValue;
import android.widget.Toast;
import android.util.Log;

/**
 * Launcher activity that checks for Termux and provides installation/launch options.
 * This app is a Termux companion - it works WITH Termux, not standalone.
 */
public class TermuxCheckActivity extends Activity {
    private static final String TAG = "TermuxCheck";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String FDROID_TERMUX_URL = "https://f-droid.org/packages/com.termux/";
    private static final String GITHUB_TERMUX_URL = "https://github.com/termux/termux-app/releases";

    private LinearLayout mainLayout;
    private TextView statusText;
    private Button actionButton;
    private Button fdroidButton;
    private Button githubButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        createUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
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
        subtitle.setText("Termux Companion App");
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
        mainLayout.addView(actionButton);

        // F-Droid button (for installation)
        fdroidButton = new Button(this);
        fdroidButton.setText("Get from F-Droid");
        fdroidButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        fdroidButton.setLayoutParams(btnParams);
        fdroidButton.setOnClickListener(v -> openUrl(FDROID_TERMUX_URL));
        mainLayout.addView(fdroidButton);

        // GitHub button (for installation)
        githubButton = new Button(this);
        githubButton.setText("Get from GitHub");
        githubButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        githubButton.setLayoutParams(btnParams);
        githubButton.setOnClickListener(v -> openUrl(GITHUB_TERMUX_URL));
        mainLayout.addView(githubButton);

        // Info text
        TextView infoText = new TextView(this);
        infoText.setText("This app requires Termux to be installed.\nTermux provides the Linux environment\nfor running Claude Code CLI.");
        infoText.setTextColor(Color.parseColor("#666666"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, 48, 0, 0);
        mainLayout.addView(infoText);

        setContentView(mainLayout);
    }

    private void updateUI() {
        boolean termuxInstalled = isTermuxInstalled();
        Log.i(TAG, "Termux installed: " + termuxInstalled);

        if (termuxInstalled) {
            statusText.setText("Termux is installed!");
            statusText.setTextColor(Color.parseColor("#4CAF50"));
            actionButton.setText("Launch Termux");
            actionButton.setOnClickListener(v -> launchTermux());
            fdroidButton.setVisibility(View.GONE);
            githubButton.setVisibility(View.GONE);
        } else {
            statusText.setText("Termux is not installed");
            statusText.setTextColor(Color.parseColor("#ff6666"));
            actionButton.setText("Check Again");
            actionButton.setOnClickListener(v -> updateUI());
            fdroidButton.setVisibility(View.VISIBLE);
            githubButton.setVisibility(View.VISIBLE);
        }
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launchTermux() {
        try {
            // Launch Termux main activity
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, "com.termux.app.TermuxActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            // Show a toast with helpful info
            Toast.makeText(this,
                "To install Claude Code:\nnpm install -g @anthropic-ai/claude-code",
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Termux", e);
            Toast.makeText(this, "Failed to launch Termux: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL", e);
            Toast.makeText(this, "Failed to open browser", Toast.LENGTH_SHORT).show();
        }
    }
}
