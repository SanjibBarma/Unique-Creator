package com.example.uniquecreator.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import java.security.MessageDigest;

public class SecurityHelper {

    private static final String TAG = "SecurityHelper";
    private static SecurityHelper instance;
    private final Context context;

    static {
        System.loadLibrary("unique-security");
    }

    // Native Hardcore Security Methods
    private native boolean validateNativeEnvironment(boolean isDebug);
    private native boolean checkNativeIntegrity(Context context);

    private SecurityHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized SecurityHelper getInstance(Context context) {
        if (instance == null) {
            instance = new SecurityHelper(context);
        }
        return instance;
    }

    /**
     * ★ ADVANCED SECURITY PIPELINE
     */
    public SecurityResult performSecurityCheck() {
        
        // 1. Signature Validation FIRST (The Identity Lock)
        String currentHash = getAppSignatureHash();
        String expectedDebug = SecurityConfig.DEBUG_SIGNATURE_HASH.replace(":", "").replace(" ", "");
        String expectedRelease = SecurityConfig.RELEASE_SIGNATURE_HASH.replace(":", "").replace(" ", "");

        boolean isLegitDebug = isDebugBuild() && expectedDebug.equalsIgnoreCase(currentHash);
        boolean isLegitRelease = !isDebugBuild() && expectedRelease.equalsIgnoreCase(currentHash);

        if (!isLegitDebug && !isLegitRelease) {
            Log.e(TAG, "Mismatched Hash! Expected Debug: " + expectedDebug + " | Expected Release: " + expectedRelease + " | Got: " + currentHash);
            return new SecurityResult(false, "Unauthorized App Signature detected");
        }

        // 2. Native Environment Lock (Anti-Debugger, Anti-Frida Thread)
        // Pass isDebugBuild() to allow Android Studio debugger if signature matches
        if (!validateNativeEnvironment(isDebugBuild())) {
            return new SecurityResult(false, "System Integrity Compromised (Tool Detected)");
        }

        // 3. Static Integrity (Check if files were modified)
        if (IntegrityChecker.detectRepackaging(context) || 
            !IntegrityChecker.checkDexIntegrity(context)) {
            return new SecurityResult(false, "App file integrity failed (Repackaged)");
        }

        // 4. Dynamic Protection (Check for Hooking at runtime)
        if (IntegrityChecker.detectHookingFrameworks() || 
            IntegrityChecker.detectMemoryTampering()) {
            return new SecurityResult(false, "Hacking frameworks detected (Frida/Xposed/Root)");
        }

        // 5. Native Identity Check
        if (!checkNativeIntegrity(context)) {
            return new SecurityResult(false, "Internal Security Mismatch");
        }

        return new SecurityResult(true, "Security Verified Successfully");
    }

    /**
     * Helper to get SHA-256 hash
     */
    public String getAppSignatureHash() {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] signatures = packageInfo.signatures;
            
            if (signatures == null || signatures.length == 0) return "";

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(signatures[0].toByteArray());
            byte[] digest = md.digest();

            StringBuilder hashBuilder = new StringBuilder();
            for (byte b : digest) {
                hashBuilder.append(String.format("%02X", b));
            }
            return hashBuilder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isDebugBuild() {
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public static class SecurityResult {
        public final boolean isSecure;
        public final String message;
        public SecurityResult(boolean isSecure, String message) {
            this.isSecure = isSecure;
            this.message = message;
        }
    }
}