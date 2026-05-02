package com.example.uniquecreator.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class IntegrityChecker {

    private static final String TAG = "IntegrityChecker";

    /**
     * 1. Detect common hacking/tampering tools by package name
     */
    public static boolean detectHookingFrameworks() {
        String[] suspiciousApps = {
                "de.robv.android.xposed.installer",
                "com.saurik.substrate",
                "com.topjohnwu.magisk",
                "com.noshufou.android.su",
                "com.thirdparty.superuser",
                "eu.chainfire.supersu",
                "com.koushikdutta.superuser",
                "com.zacharee1.systemuituner",
                "com.formyhm.hideroot",
                "com.amphoras.hidemyroot"
        };

        for (String pkg : suspiciousApps) {
            if (new File("/data/data/" + pkg).exists() || new File("/system/app/" + pkg).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2. Memory Tampering & Debugger detection
     */
    public static boolean detectMemoryTampering() {
        // Check if debugger is connected via standard API
        if (android.os.Debug.isDebuggerConnected()) return true;

        // Check for TracerPid in /proc/self/status
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/status")
            );
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("TracerPid:")) {
                    int tracerPid = Integer.parseInt(line.split(":")[1].trim());
                    if (tracerPid != 0) return true;
                }
            }
            reader.close();
        } catch (Exception ignored) {}
        
        return false;
    }

    /**
     * 3. Check for unauthorized APK modifications (Repackaging)
     */
    public static boolean detectRepackaging(Context context) {
        try {
            String currentHash = SecurityHelper.getInstance(context).getAppSignatureHash();
            String expectedDebug = SecurityConfig.DEBUG_SIGNATURE_HASH.replace(":", "").replace(" ", "");
            String expectedRelease = SecurityConfig.RELEASE_SIGNATURE_HASH.replace(":", "").replace(" ", "");
            
            // Check against both debug and release hashes with case-insensitive comparison
            boolean isLegit = expectedRelease.equalsIgnoreCase(currentHash) ||
                             expectedDebug.equalsIgnoreCase(currentHash);
            
            return !isLegit;
        } catch (Exception e) {
            return true; // If check fails, assume tampered
        }
    }

    /**
     * 4. Verify DEX file hasn't been modified
     */
    public static boolean checkDexIntegrity(Context context) {
        try {
            // Simplified: In a real app, you'd check CRC or SHA-1 of classes.dex
            File dexFile = new File(context.getPackageCodePath());
            return dexFile.exists() && dexFile.length() > 1000;
        } catch (Exception e) {
            return false;
        }
    }
}