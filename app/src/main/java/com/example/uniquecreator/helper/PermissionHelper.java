package com.example.uniquecreator.helper;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.karumi.dexter.Dexter;
import com.karumi.dexter.MultiplePermissionsReport;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;

import java.util.List;

public class PermissionHelper {

    public static final int PERMISSION_REQUEST_CODE = 100;

    public interface PermissionCallback {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    // ★ FIX: Store callback for Android 9 fallback
    private static PermissionCallback pendingCallback;

    public static void requestPermissions(Activity activity, PermissionCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — READ_MEDIA_VIDEO and READ_MEDIA_IMAGES
            Dexter.withContext(activity)
                    .withPermissions(
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_IMAGES
                    )
                    .withListener(new MultiplePermissionsListener() {
                        @Override
                        public void onPermissionsChecked(MultiplePermissionsReport report) {
                            if (report.areAllPermissionsGranted()) {
                                callback.onPermissionGranted();
                            } else {
                                callback.onPermissionDenied();
                            }
                        }

                        @Override
                        public void onPermissionRationaleShouldBeShown(List<PermissionRequest> permissions, PermissionToken token) {
                            token.continuePermissionRequest();
                        }
                    }).check();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 — READ_EXTERNAL_STORAGE only (scoped storage)
            Dexter.withContext(activity)
                    .withPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    .withListener(new com.karumi.dexter.listener.single.PermissionListener() {
                        @Override
                        public void onPermissionGranted(com.karumi.dexter.listener.PermissionGrantedResponse response) {
                            callback.onPermissionGranted();
                        }

                        @Override
                        public void onPermissionDenied(com.karumi.dexter.listener.PermissionDeniedResponse response) {
                            callback.onPermissionDenied();
                        }

                        @Override
                        public void onPermissionRationaleShouldBeShown(PermissionRequest permission, PermissionToken token) {
                            token.continuePermissionRequest();
                        }
                    }).check();

        } else {
            // Android 6-9 — READ and WRITE EXTERNAL_STORAGE
            boolean readGranted = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (readGranted && writeGranted) {
                callback.onPermissionGranted();
            } else {
                // ★ FIX: Store callback for onRequestPermissionsResult
                pendingCallback = callback;
                ActivityCompat.requestPermissions(activity,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    /**
     * ★ FIX: Call this from MainActivity.onRequestPermissionsResult()
     * to properly handle Android 6-9 permission results
     */
    public static void handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && pendingCallback != null) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                pendingCallback.onPermissionGranted();
            } else {
                pendingCallback.onPermissionDenied();
            }
            pendingCallback = null; // Clear reference
        }
    }

    /**
     * Check if all required permissions are granted
     */
    public static boolean hasPermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }
}