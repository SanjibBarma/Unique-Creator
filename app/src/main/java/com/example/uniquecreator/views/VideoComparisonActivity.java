package com.example.uniquecreator.views;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uniquecreator.BaseActivity;
import com.example.uniquecreator.R;
import com.example.uniquecreator.helper.PermissionHelper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public class VideoComparisonActivity extends BaseActivity {

    private static final String TAG = "VideoComparison";

    private Uri originalVideoUri;
    private Uri processedVideoUri;

    private ImageView imgOriginal, imgProcessed;
    private TextView tvOriginalInfo, tvProcessedInfo;
    private TextView tvHashOriginal, tvHashProcessed;
    private TextView tvSimilarity, tvVerdict;
    private Button btnSelectOriginal, btnSelectProcessed, btnCompare;
    private ProgressBar progressBar;
    private View resultLayout;

    // ★ FIX #1: ActivityResultLauncher replaces deprecated startActivityForResult
    private ActivityResultLauncher<Intent> originalVideoLauncher;
    private ActivityResultLauncher<Intent> processedVideoLauncher;

    // ★ FIX: Track which picker is active (no longer needed with separate launchers)
    private Bitmap originalThumbnail, processedThumbnail;

    @Override
    protected SecurityCheckMode getSecurityCheckMode() {
        return SecurityCheckMode.ALWAYS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_comparison);

        // ★ FIX #1: Register launchers BEFORE any UI interaction
        registerActivityResultLaunchers();

        initViews();
        setupListeners();
        setupBackPressHandler();

        if (!PermissionHelper.hasPermissions(this)) {
            showPermissionWarning();
        }
    }

    private void showPermissionWarning() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Permission প্রয়োজন")
                .setMessage("ভিডিও তুলনা করতে Storage Permission দিতে হবে।")
                .setPositiveButton("Permission দিন", (dialog, which) -> {
                    PermissionHelper.requestPermissions(this, new PermissionHelper.PermissionCallback() {
                        @Override
                        public void onPermissionGranted() {
                            Toast.makeText(VideoComparisonActivity.this,
                                    "Permission granted", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onPermissionDenied() {
                            Toast.makeText(VideoComparisonActivity.this,
                                    "Permission denied", Toast.LENGTH_SHORT).show();
                            finish(); // Close activity if permission denied
                        }
                    });
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .show();
    }

    // ★ FIX #1: Register ActivityResultLaunchers (replaces onActivityResult)
    private void registerActivityResultLaunchers() {
        originalVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri videoUri = result.getData().getData();
                        if (videoUri != null) {
                            originalVideoUri = videoUri;
                            loadVideoPreview(videoUri, imgOriginal, tvOriginalInfo, "অরিজিনাল", true);
                            btnSelectOriginal.setText("✓ Original Selected");
                            updateCompareButtonState();
                        }
                    }
                }
        );

        processedVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri videoUri = result.getData().getData();
                        if (videoUri != null) {
                            processedVideoUri = videoUri;
                            loadVideoPreview(videoUri, imgProcessed, tvProcessedInfo, "প্রসেসড", false);
                            btnSelectProcessed.setText("✓ Processed Selected");
                            updateCompareButtonState();
                        }
                    }
                }
        );
    }

    // ★ FIX #2: OnBackPressedCallback replaces deprecated onBackPressed()
    private void setupBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Clean up bitmaps before exiting
                recycleBitmaps();
                finish();
            }
        });
    }

    private void initViews() {
        imgOriginal = findViewById(R.id.imgOriginal);
        imgProcessed = findViewById(R.id.imgProcessed);
        tvOriginalInfo = findViewById(R.id.tvOriginalInfo);
        tvProcessedInfo = findViewById(R.id.tvProcessedInfo);
        tvHashOriginal = findViewById(R.id.tvHashOriginal);
        tvHashProcessed = findViewById(R.id.tvHashProcessed);
        tvSimilarity = findViewById(R.id.tvSimilarity);
        tvVerdict = findViewById(R.id.tvVerdict);
        btnSelectOriginal = findViewById(R.id.btnSelectOriginal);
        btnSelectProcessed = findViewById(R.id.btnSelectProcessed);
        btnCompare = findViewById(R.id.btnCompare);
        progressBar = findViewById(R.id.progressBar);
        resultLayout = findViewById(R.id.resultLayout);

        resultLayout.setVisibility(View.GONE);
        btnCompare.setEnabled(false);
    }

    private void setupListeners() {
        // ★ FIX #1: Use launchers instead of startActivityForResult
        btnSelectOriginal.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            intent.setType("video/*");
            originalVideoLauncher.launch(intent);
        });

        btnSelectProcessed.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            intent.setType("video/*");
            processedVideoLauncher.launch(intent);
        });

        btnCompare.setOnClickListener(v -> compareVideos());
    }

    private void updateCompareButtonState() {
        btnCompare.setEnabled(originalVideoUri != null && processedVideoUri != null);
    }

    private void loadVideoPreview(Uri uri, ImageView imageView, TextView textView,
                                  String label, boolean isOriginal) {
        new Thread(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(this, uri);

                Bitmap thumbnail = retriever.getFrameAtTime(1000000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (thumbnail == null) {
                    thumbnail = retriever.getFrameAtTime(0);
                }

                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

                final Bitmap finalThumbnail = thumbnail;
                final long durationMs = duration != null ? Long.parseLong(duration) : 0;

                runOnUiThread(() -> {
                    // ★ FIX: Recycle old bitmap before setting new one
                    if (isOriginal) {
                        if (originalThumbnail != null && !originalThumbnail.isRecycled()) {
                            originalThumbnail.recycle();
                        }
                        originalThumbnail = finalThumbnail;
                    } else {
                        if (processedThumbnail != null && !processedThumbnail.isRecycled()) {
                            processedThumbnail.recycle();
                        }
                        processedThumbnail = finalThumbnail;
                    }

                    if (finalThumbnail != null) {
                        imageView.setImageBitmap(finalThumbnail);
                    }

                    String info = String.format(Locale.US,
                            "%s\n%s×%s\n%.2f সেকেন্ড",
                            label,
                            width != null ? width : "?",
                            height != null ? height : "?",
                            durationMs / 1000.0);
                    textView.setText(info);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading preview", e);
                runOnUiThread(() -> {
                    textView.setText(label + "\n⚠️ Preview load failed");
                });
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void compareVideos() {
        if (originalVideoUri == null || processedVideoUri == null) {
            Toast.makeText(this, "দুটি ভিডিও সিলেক্ট করুন", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        resultLayout.setVisibility(View.GONE);
        btnCompare.setEnabled(false);

        new Thread(() -> {
            MediaMetadataRetriever r1 = null;
            MediaMetadataRetriever r2 = null;

            try {
                // ════════════════════════════════════════════════════════════
                // 1. Calculate MD5 Hashes
                // ★ FIX #3: Use ContentResolver.openInputStream() instead of getRealPath()
                // ════════════════════════════════════════════════════════════
                String hashOriginal = calculateMD5FromUri(originalVideoUri);
                String hashProcessed = calculateMD5FromUri(processedVideoUri);

                runOnUiThread(() -> {
                    String origHash = hashOriginal.length() >= 16
                            ? hashOriginal.substring(0, 16) + "..."
                            : hashOriginal;
                    String procHash = hashProcessed.length() >= 16
                            ? hashProcessed.substring(0, 16) + "..."
                            : hashProcessed;
                    tvHashOriginal.setText("MD5: " + origHash);
                    tvHashProcessed.setText("MD5: " + procHash);
                });

                // ════════════════════════════════════════════════════════════
                // 2. Compare Video Properties
                // ════════════════════════════════════════════════════════════
                r1 = new MediaMetadataRetriever();
                r2 = new MediaMetadataRetriever();

                r1.setDataSource(this, originalVideoUri);
                r2.setDataSource(this, processedVideoUri);

                int w1 = safeParseInt(r1.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                int h1 = safeParseInt(r1.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                long d1 = safeParseLong(r1.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);

                int w2 = safeParseInt(r2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
                int h2 = safeParseInt(r2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                long d2 = safeParseLong(r2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);

                // ════════════════════════════════════════════════════════════
                // 3. Frame Similarity Analysis
                // ════════════════════════════════════════════════════════════
                double frameSimilarity = compareFrames(r1, r2, Math.min(d1, d2));

                // ════════════════════════════════════════════════════════════
                // 4. Calculate Overall Difference Score
                // ════════════════════════════════════════════════════════════
                boolean hashDifferent = !hashOriginal.equals(hashProcessed)
                        && !hashOriginal.equals("error")
                        && !hashProcessed.equals("error");
                boolean durationDifferent = Math.abs(d1 - d2) > 100; // >100ms difference
                boolean resolutionDifferent = (w1 != w2 || h1 != h2);

                double differenceScore = 0;

                if (hashDifferent) differenceScore += 40; // MD5 different = 40%
                if (durationDifferent) differenceScore += 20; // Duration different = 20%
                if (resolutionDifferent) differenceScore += 10; // Resolution different = 10%
                if (frameSimilarity < 95) differenceScore += (100 - frameSimilarity) * 0.3; // Frame difference

                // ════════════════════════════════════════════════════════════
                // 5. Verdict
                // ════════════════════════════════════════════════════════════
                final String verdict;
                final int verdictColor;

                if (differenceScore >= 60) {
                    verdict = "✅ সম্পূর্ণ ভিন্ন ভিডিও\n\nCopyright-free হওয়ার সম্ভাবনা: খুব বেশি";
                    verdictColor = 0xFF4CAF50; // Green
                } else if (differenceScore >= 40) {
                    verdict = "⚠️ মাঝারি পার্থক্য\n\nCopyright-free হওয়ার সম্ভাবনা: মাঝারি";
                    verdictColor = 0xFFFF9800; // Orange
                } else if (differenceScore >= 20) {
                    verdict = "⚠️ কম পার্থক্য\n\nCopyright-free হওয়ার সম্ভাবনা: কম";
                    verdictColor = 0xFFFF5722; // Deep Orange
                } else {
                    verdict = "❌ প্রায় একই ভিডিও\n\nCopyright-free নয়!";
                    verdictColor = 0xFFF44336; // Red
                }

                final double finalScore = differenceScore;
                final double finalFrameSimilarity = frameSimilarity;
                final boolean finalHashDiff = hashDifferent;
                final boolean finalDurDiff = durationDifferent;
                final boolean finalResDiff = resolutionDifferent;

                runOnUiThread(() -> {
                    tvSimilarity.setText(String.format(Locale.US,
                            "পার্থক্য স্কোর: %.1f%%\n\n" +
                                    "MD5: %s\n" +
                                    "Duration: %s\n" +
                                    "Resolution: %s\n" +
                                    "Frame Match: %.1f%%",
                            finalScore,
                            finalHashDiff ? "✓ Different" : "✗ Same",
                            finalDurDiff ? "✓ Different" : "✗ Same",
                            finalResDiff ? "✓ Different" : "✗ Same",
                            finalFrameSimilarity
                    ));
                    tvVerdict.setText(verdict);
                    tvVerdict.setTextColor(verdictColor);

                    progressBar.setVisibility(View.GONE);
                    resultLayout.setVisibility(View.VISIBLE);
                    btnCompare.setEnabled(true);
                });

            } catch (Exception e) {
                Log.e(TAG, "Comparison error", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "তুলনা করতে ব্যর্থ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                    btnCompare.setEnabled(true);
                });
            } finally {
                // ★ FIX: Always release retrievers
                if (r1 != null) try { r1.release(); } catch (Exception ignored) {}
                if (r2 != null) try { r2.release(); } catch (Exception ignored) {}
            }
        }).start();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // ★ FIX #4: Frame Comparison with proper bitmap recycling
    // ════════════════════════════════════════════════════════════════════════════
    private double compareFrames(MediaMetadataRetriever r1, MediaMetadataRetriever r2, long durationMs) {
        int samplePoints = 10; // Check 10 frames
        int matches = 0;
        int validComparisons = 0;

        try {
            long intervalMs = Math.max(1000, durationMs / samplePoints);

            for (int i = 0; i < samplePoints; i++) {
                long timeUs = (i * intervalMs * 1000L); // ms to us

                Bitmap frame1 = null;
                Bitmap frame2 = null;

                try {
                    frame1 = r1.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    frame2 = r2.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                    if (frame1 != null && frame2 != null) {
                        double similarity = calculateImageSimilarity(frame1, frame2);
                        validComparisons++;
                        if (similarity > 0.85) { // 85% similar
                            matches++;
                        }
                    }
                } finally {
                    // ★ FIX: Always recycle bitmaps immediately
                    if (frame1 != null && !frame1.isRecycled()) frame1.recycle();
                    if (frame2 != null && !frame2.isRecycled()) frame2.recycle();
                }
            }

            if (validComparisons == 0) return 50.0; // Default if no frames
            return (matches * 100.0 / validComparisons);

        } catch (Exception e) {
            Log.e(TAG, "Frame comparison error", e);
            return 50.0; // Default to 50% if error
        }
    }

    private double calculateImageSimilarity(Bitmap img1, Bitmap img2) {
        Bitmap small1 = null;
        Bitmap small2 = null;

        try {
            // Resize to small size for comparison
            small1 = Bitmap.createScaledBitmap(img1, 16, 16, true);
            small2 = Bitmap.createScaledBitmap(img2, 16, 16, true);

            int totalPixels = 16 * 16;
            int matchingPixels = 0;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int color1 = small1.getPixel(x, y);
                    int color2 = small2.getPixel(x, y);

                    // Compare RGB values with tolerance
                    int r1 = (color1 >> 16) & 0xFF;
                    int g1 = (color1 >> 8) & 0xFF;
                    int b1 = color1 & 0xFF;

                    int r2 = (color2 >> 16) & 0xFF;
                    int g2 = (color2 >> 8) & 0xFF;
                    int b2 = color2 & 0xFF;

                    int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);

                    if (diff < 100) { // Tolerance
                        matchingPixels++;
                    }
                }
            }

            return matchingPixels * 1.0 / totalPixels;

        } catch (Exception e) {
            return 0.5;
        } finally {
            // ★ FIX: Recycle scaled bitmaps
            if (small1 != null && !small1.isRecycled()) small1.recycle();
            if (small2 != null && !small2.isRecycled()) small2.recycle();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // ★ FIX #3: MD5 Hash Calculation using ContentResolver (works on Android 10+)
    // ════════════════════════════════════════════════════════════════════════════
    private String calculateMD5FromUri(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                return "error_stream_null";
            }

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;

            while ((read = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "MD5 calculation error", e);
            return "error";
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ★ FIX: Safe parsing helpers
    private int safeParseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long safeParseLong(String value, long defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void recycleBitmaps() {
        if (originalThumbnail != null && !originalThumbnail.isRecycled()) {
            originalThumbnail.recycle();
            originalThumbnail = null;
        }
        if (processedThumbnail != null && !processedThumbnail.isRecycled()) {
            processedThumbnail.recycle();
            processedThumbnail = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleBitmaps();
    }
}