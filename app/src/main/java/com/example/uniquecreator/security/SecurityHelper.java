package com.example.uniquecreator.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.security.MessageDigest;

public class SecurityHelper {

    private static final String TAG = "SecurityHelper";
    private static SecurityHelper instance;
    private final Context context;

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
     * ★ MAIN SECURITY CHECK - Multi-layer validation
     */
    public SecurityResult performSecurityCheck() {
        // Layer 1: Root Detection
        if (isDeviceRooted()) {
            return new SecurityResult(false, "রুটেড ডিভাইসে অ্যাপ চলবে না");
        }

        // Layer 2: Emulator Detection
        if (isEmulator()) {
            return new SecurityResult(false, "এমুলেটরে অ্যাপ চলবে না");
        }

        // Layer 3: USB Debugging Detection
        if (isUsbDebuggingEnabled()) {
            return new SecurityResult(false, "USB Debugging বন্ধ করুন");
        }

        // Layer 4: Signature Verification
        if (!verifyAppSignature()) {
            return new SecurityResult(false, "অ্যাপটি অননুমোদিত সংস্করণ");
        }

        // ★ Layer 5: APK Integrity
        if (IntegrityChecker.detectRepackaging(context)) {
            return new SecurityResult(false, "অ্যাপটি পরিবর্তিত হয়েছে");
        }

        // ★ Layer 6: DEX Integrity
        if (!IntegrityChecker.checkDexIntegrity(context)) {
            return new SecurityResult(false, "অ্যাপ ফাইল ক্ষতিগ্রস্ত");
        }

        // ★ Layer 7: Hooking Framework Detection
        if (IntegrityChecker.detectHookingFrameworks()) {
            return new SecurityResult(false, "হ্যাকিং টুল শনাক্ত হয়েছে");
        }

        // ★ Layer 8: Memory Tampering
        if (IntegrityChecker.detectMemoryTampering()) {
            return new SecurityResult(false, "ডিবাগার শনাক্ত হয়েছে");
        }

        // ★ Layer 9: File Integrity
        if (!IntegrityChecker.checkFileIntegrity(context)) {
            return new SecurityResult(false, "অ্যাপ ফাইল পরিবর্তিত");
        }

        // ★ Layer 10: Native Library Check
        if (!IntegrityChecker.checkNativeLibraries(context)) {
            return new SecurityResult(false, "লাইব্রেরি ফাইল অনুপস্থিত");
        }

        return new SecurityResult(true, "নিরাপত্তা যাচাই সফল");
    }

    /**
     * Root Detection
     */
    private boolean isDeviceRooted() {
        // Skip in debug mode
        if (isDebugBuild()) {
            return false;
        }

        // Check 1: Build tags
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }

        // Check 2: Su binary
        String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su"
        };

        for (String path : paths) {
            if (new File(path).exists()) {
                Log.w(TAG, "Root file found: " + path);
                return true;
            }
        }

        // Check 3: Magisk
        if (new File("/system/app/Magisk").exists()) {
            return true;
        }

        return false;
    }

    /**
     * Emulator Detection
     */
    private boolean isEmulator() {
        // Skip in debug mode
        if (isDebugBuild()) {
            return false;
        }

        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
                "google_sdk".equals(Build.PRODUCT) ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu");
    }

    /**
     * USB Debugging Detection
     */
    private boolean isUsbDebuggingEnabled() {
        // Skip in debug mode
        if (isDebugBuild()) {
            return false;
        }

        try {
            int adbEnabled = android.provider.Settings.Secure.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.ADB_ENABLED,
                    0
            );
            return adbEnabled == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ★ Signature Verification (Enhanced)
     */
    private boolean verifyAppSignature() {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNATURES
            );

            Signature[] signatures = packageInfo.signatures;
            if (signatures == null || signatures.length == 0) {
                return false;
            }

            String currentHash = getSignatureHash(signatures[0]);
            boolean isValid = SecurityConfig.isLegitimateApp(context, currentHash);

            if (!isValid) {
                Log.e(TAG, "Invalid signature! Expected vs Current:");
                Log.e(TAG, "Current: " + currentHash);
            }

            return isValid;

        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }

    /**
     * Get signature hash
     */
    public String getAppSignatureHash() {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNATURES
            );

            Signature[] signatures = packageInfo.signatures;
            if (signatures != null && signatures.length > 0) {
                return getSignatureHash(signatures[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get signature hash", e);
        }
        return "";
    }

    private String getSignatureHash(Signature signature) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(signature.toByteArray());
        byte[] digest = md.digest();

        StringBuilder hashBuilder = new StringBuilder();
        for (byte b : digest) {
            hashBuilder.append(String.format("%02X", b));
        }
        return hashBuilder.toString();
    }

    /**
     * Check if debug build
     */
    private boolean isDebugBuild() {
        return (context.getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * Security check result
     */
    public static class SecurityResult {
        public final boolean isSecure;
        public final String message;

        public SecurityResult(boolean isSecure, String message) {
            this.isSecure = isSecure;
            this.message = message;
        }
    }
}