package com.example.uniquecreator.security;

public class SecurityConfig {

    // ★ তোমার ACTUAL signature hash (JADX থেকে প্রাপ্ত রিলিজ কী)
    public static final String RELEASE_SIGNATURE_HASH = "98:A0:49:53:98:27:F9:E4:13:3B:DB:64:72:39:F0:7F:1C:02:70:A7:DF:2A:82:E2:68:D2:F9:B0:44:1E:90:A8";

    // ★ Debug signature (তোমার পিসির ডিবাগ কী)
    public static final String DEBUG_SIGNATURE_HASH = "62:76:31:A7:B4:F5:72:09:E5:65:AF:2B:6E:01:00:E0:53:99:62:C3:A5:64:8C:C3:4F:C1:AC:0B:EC:7F:DD:49";

    // ★ Package name validation
    public static final String EXPECTED_PACKAGE_NAME = "com.example.uniquecreator";

    // ★ Native library validation
    private static final String[] EXPECTED_NATIVE_LIBS = {
            "libunique-security.so",
            "libavcodec.so",
            "libavformat.so",
            "libavutil.so"
    };

    /**
     * Check if current build is legitimate
     */
    public static boolean isLegitimateApp(android.content.Context context, String currentHash) {
        boolean isDebug = isDebugBuild(context);

        if (isDebug) {
            // Debug mode: accept both debug and release signatures
            return currentHash.equals(DEBUG_SIGNATURE_HASH) ||
                    currentHash.equals(RELEASE_SIGNATURE_HASH);
        } else {
            // Release mode: only accept release signature
            return currentHash.equals(RELEASE_SIGNATURE_HASH);
        }
    }

    public static String getExpectedPackageName() {
        return EXPECTED_PACKAGE_NAME;
    }

    public static String[] getExpectedNativeLibs() {
        return EXPECTED_NATIVE_LIBS;
    }

    private static boolean isDebugBuild(android.content.Context context) {
        return (context.getApplicationInfo().flags &
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}