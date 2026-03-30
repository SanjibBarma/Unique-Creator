package com.example.uniquecreator.security;

public class SecurityConfig {

    // ★ তোমার ORIGINAL signature hash (Release keystore থেকে)
    // Generate করতে: SecurityHelper.getAppSignatureHash() call করো একবার
    private static final String RELEASE_SIGNATURE_HASH = "18465502A428CF2A6AFCAEA1903E375EAA5CB86D7FCDD1FD2B2A9DF7E2105E60";

    // ★ Debug signature (শুধু development এর জন্য)
    private static final String DEBUG_SIGNATURE_HASH = "4CDE5D3C5077B5B4B5928D59FCCB3BBA58738CF83DCB622C6EF0F6EA14FF26F8";

    // ★ Package name validation
    private static final String EXPECTED_PACKAGE_NAME = "com.copyrightfree.video";

    // ★ Native library validation (optional)
    private static final String[] EXPECTED_NATIVE_LIBS = {
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