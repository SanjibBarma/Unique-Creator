package com.example.uniquecreator;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uniquecreator.security.SecurityHelper;


public abstract class BaseActivity extends AppCompatActivity {

    private static final String TAG = "BaseActivity";

    // ★ Control security check frequency
    private static boolean hasCheckedInSession = false;

    // ★ Security check modes
    public enum SecurityCheckMode {
        ALWAYS,           // Check every time activity opens
        ONCE_PER_SESSION, // Check only first time in app session
        SKIP              // Skip check (for specific activities if needed)
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Perform security check based on mode
        SecurityCheckMode mode = getSecurityCheckMode();

        switch (mode) {
            case ALWAYS:
                performSecurityCheck();
                break;

            case ONCE_PER_SESSION:
                if (!hasCheckedInSession) {
                    performSecurityCheck();
                    hasCheckedInSession = true;
                }
                break;

            case SKIP:
                // Skip security check
                break;
        }
    }

    /**
     * Override this in child activities to set security check mode
     * Default: ONCE_PER_SESSION
     */
    protected SecurityCheckMode getSecurityCheckMode() {
        return SecurityCheckMode.ONCE_PER_SESSION;
    }

    /**
     * Perform security check
     */
    private void performSecurityCheck() {
        SecurityHelper securityHelper = SecurityHelper.getInstance(this);
        SecurityHelper.SecurityResult result = securityHelper.performSecurityCheck();

        if (!result.isSecure) {
            onSecurityCheckFailed(result);
        } else {
            onSecurityCheckPassed();
        }
    }

    /**
     * Called when security check fails
     * Override to customize behavior
     */
    protected void onSecurityCheckFailed(SecurityHelper.SecurityResult result) {
        // Show error message
        Toast.makeText(this,
                "🚫 " + result.message,
                Toast.LENGTH_LONG).show();

        // Close app after 2 seconds
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            finishAffinity(); // Close all activities
            System.exit(0);   // Kill process
        }, 2000);
    }

    /**
     * Called when security check passes
     * Override if you need custom logic
     */
    protected void onSecurityCheckPassed() {
        // Optional: Log success
        // android.util.Log.d(TAG, "✅ Security check passed");
    }

    /**
     * Reset session flag (call when app goes to background)
     */
    public static void resetSecuritySession() {
        hasCheckedInSession = false;
    }
}