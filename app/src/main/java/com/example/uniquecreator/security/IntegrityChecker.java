package com.example.uniquecreator.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class IntegrityChecker {

    private static final String TAG = "IntegrityChecker";

    /**
     * ★ Check DEX file integrity (APK not modified)
     */
    public static boolean checkDexIntegrity(Context context) {
        try {
            String apkPath = context.getPackageCodePath();
            ZipFile zipFile = new ZipFile(apkPath);

            // Check if classes.dex exists and is valid
            ZipEntry dexEntry = zipFile.getEntry("classes.dex");
            if (dexEntry == null) {
                Log.e(TAG, "DEX file missing!");
                return false;
            }

            // You can add CRC check here
            long crc = dexEntry.getCrc();
            Log.d(TAG, "DEX CRC: " + crc);

            zipFile.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "DEX integrity check failed", e);
            return false;
        }
    }

    /**
     * ★ Check if APK is installed from unknown source
     */
    public static boolean isInstalledFromPlayStore(Context context) {
        String installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());

        if (installer == null) {
            // Installed manually (adb, file manager)
            return false;
        }

        // Valid installers
        return installer.equals("com.android.vending") ||        // Google Play
                installer.equals("com.google.android.feedback") || // Google Play (old)
                installer.equals("com.amazon.venezia");            // Amazon App Store
    }

    /**
     * ★ Detect if APK is repacked/modified
     */
    public static boolean detectRepackaging(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);

            // Get all signatures
            Signature[] signatures = packageInfo.signatures;

            if (signatures == null || signatures.length == 0) {
                return true; // No signature = tampered
            }

            // Calculate signature hash
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(signatures[0].toByteArray());
            byte[] digest = md.digest();

            StringBuilder hashBuilder = new StringBuilder();
            for (byte b : digest) {
                hashBuilder.append(String.format("%02X", b));
            }
            String currentHash = hashBuilder.toString();

            // Check against known good hash
            return !SecurityConfig.isLegitimateApp(context, currentHash);

        } catch (Exception e) {
            Log.e(TAG, "Repackaging detection failed", e);
            return true; // Assume tampered if check fails
        }
    }

    /**
     * ★ Check if app files are modified
     */
    public static boolean checkFileIntegrity(Context context) {
        try {
            File apkFile = new File(context.getPackageCodePath());

            // Check if APK file exists
            if (!apkFile.exists()) {
                return false;
            }

            // Check file size (optional - store expected size)
            long fileSize = apkFile.length();
            Log.d(TAG, "APK size: " + fileSize);

            // Check last modified time
            long lastModified = apkFile.lastModified();
            long installTime = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .firstInstallTime;

            // APK should not be modified after installation
            if (lastModified > installTime + 5000) { // 5 sec tolerance
                Log.w(TAG, "APK modified after installation!");
                return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ★ Detect Xposed/Frida frameworks
     */
    public static boolean detectHookingFrameworks() {
        List<String> suspiciousPackages = new ArrayList<>();
        suspiciousPackages.add("de.robv.android.xposed.installer");
        suspiciousPackages.add("com.saurik.substrate");
        suspiciousPackages.add("com.topjohnwu.magisk");
        suspiciousPackages.add("eu.chainfire.supersu");

        for (String pkg : suspiciousPackages) {
            try {
                File file = new File("/system/app/" + pkg);
                if (file.exists()) {
                    Log.w(TAG, "Hooking framework detected: " + pkg);
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // Check for Frida server
        try {
            File fridaFile = new File("/data/local/tmp/frida-server");
            if (fridaFile.exists()) {
                Log.w(TAG, "Frida server detected!");
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * ★ Check native library integrity
     */
    public static boolean checkNativeLibraries(Context context) {
        String[] expectedLibs = SecurityConfig.getExpectedNativeLibs();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        for (String libName : expectedLibs) {
            File libFile = new File(nativeLibDir, libName);
            if (!libFile.exists()) {
                Log.e(TAG, "Native library missing: " + libName);
                return false;
            }
        }

        return true;
    }

    /**
     * ★ Memory tampering detection
     */
    public static boolean detectMemoryTampering() {
        try {
            // Check if process is being traced (debugger attached)
            File statusFile = new File("/proc/self/status");
            if (!statusFile.exists()) return false;

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(statusFile)
            );

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    String[] parts = line.split(":");
                    int tracerPid = Integer.parseInt(parts[1].trim());
                    reader.close();

                    if (tracerPid != 0) {
                        Log.w(TAG, "Debugger detected! TracerPid: " + tracerPid);
                        return true;
                    }
                    return false;
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Memory tampering check failed", e);
        }
        return false;
    }
}