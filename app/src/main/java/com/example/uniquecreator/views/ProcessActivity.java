package com.example.uniquecreator.views;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.uniquecreator.BaseActivity;
import com.example.uniquecreator.R;
import com.example.uniquecreator.helper.PermissionHelper;
import com.example.uniquecreator.helper.TransformSettings;
import com.example.uniquecreator.helper.WatermarkConfig;
import com.example.uniquecreator.processor.VideoProcessorMediaCodec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

public class ProcessActivity extends BaseActivity {

    private static final String TAG = "ProcessActivity";

    private LinearLayout processingLayout, doneLayout;
    private ProgressBar progressBar, outerRing;
    private TextView progressText, currentStage, tipText, tipIcon;
    private TextView inputFileSize, outputResolution, frameCount, audioFrameCount;
    private View stage1, stage2, stage3, stage4;
    private View stageLine1, stageLine2, stageLine3;
    private LinearLayout logHeader;
    private ScrollView logScrollView;
    private TextView logText, logToggle;

    private FrameLayout thumbnailContainer;
    private ImageView videoThumbnail;
    private TextView videoDuration, outputFileSize, outputResolutionDone, totalFrames, totalAudioFrames;
    private Button downloadBtn, shareBtn, newVideoBtn;
    private View pulseView;

    private Uri inputVideoUri;
    private String resolution, ratio, outputPath;
    private TransformSettings ts;
    private WatermarkConfig wm;
    private VideoProcessorMediaCodec processor;

    private Handler mainHandler;
    private Bitmap thumbnailBitmap;
    private long videoDurationMs = 0;
    private int processedFrames = 0;
    private int processedAudioFrames = 0;
    private boolean logExpanded = false;
    private volatile boolean isProcessing = false;

    private PowerManager.WakeLock wakeLock;

    private final String[] tips = {
            "💡 দ্রুত রেন্ডারিং-এর জন্য এই ট্যাবটি খোলা রাখুন",
            "💡 AI টেকনোলজি ব্যবহার করে ভিডিও ইউনিক করা হচ্ছে",
            "🎨 পিক্সেল লেভেলে পরিবর্তন হচ্ছে",
            "🔊 অডিও ফিঙ্গারপ্রিন্ট পরিবর্তন হচ্ছে",
            "🎬 প্রতিটি ফ্রেম প্রসেস করা হচ্ছে",
            "✨ ইউনিক মেটাডাটা তৈরি হচ্ছে",
            "🛡️ কপিরাইট ডিটেকশন বাইপাস করা হচ্ছে",
            "📊 ভিডিও কোয়ালিটি অপটিমাইজ হচ্ছে",
            "🎯 কন্টেন্ট আইডি এড়ানো হচ্ছে"
    };
    private final String[] tipIcons = {"💡", "💡", "🎨", "🔊", "🎬", "✨", "🛡️", "📊", "🎯"};
    private int currentTipIndex = 0;
    private Handler tipHandler;
    private Runnable tipRunnable;

    // Real synced progress
    private int currentDisplayedProgress = 0;

    // Throttle UI/log updates
    private long lastUiRefreshTime = 0L;
    private long lastLogTime = 0L;
    private String lastLogMessage = "";

    private Dialog videoDialog;
    private VideoView dialogVideoView;
    private Handler seekHandler;
    private Runnable seekRunnable;
    private boolean isPlaying = false;

    @Override
    protected SecurityCheckMode getSecurityCheckMode() {
        return SecurityCheckMode.ALWAYS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process);

        mainHandler = new Handler(Looper.getMainLooper());
        seekHandler = new Handler(Looper.getMainLooper());
        tipHandler = new Handler(Looper.getMainLooper());

        initViews();
        getIntentData();
        setupAnimations();
        startTipRotation();
        setupBackPressHandler();

        if (inputVideoUri == null) {
            showError("ভিডিও URI পাওয়া যায়নি!");
            return;
        }

        try {
            android.database.Cursor cursor = getContentResolver().query(
                    inputVideoUri,
                    new String[]{android.provider.OpenableColumns.SIZE},
                    null, null, null
            );
            if (cursor != null && cursor.moveToFirst()) {
                long size = cursor.getLong(0);
                inputFileSize.setText(formatSize(size));
                addLog("📊 সাইজ: " + formatSize(size));
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get file size", e);
        }

        addLog("🎬 ভিডিও লোড হয়েছে");
        mainHandler.postDelayed(this::startProcessing, 400);
    }

    private void setupBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (videoDialog != null && videoDialog.isShowing()) {
                    dismissVideoDialog();
                    return;
                }

                if (doneLayout != null && doneLayout.getVisibility() == View.VISIBLE) {
                    cleanupAndFinish();
                } else if (isProcessing) {
                    showExitConfirmation();
                } else {
                    cleanupAndFinish();
                }
            }
        });
    }

    private void showExitConfirmation() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("প্রসেসিং বন্ধ করবেন?")
                .setMessage("ভিডিও প্রসেসিং চলছে। বন্ধ করলে সব কাজ হারিয়ে যাবে।")
                .setPositiveButton("বন্ধ করুন", (dialog, which) -> {
                    if (processor != null) {
                        processor.cancel();
                    }
                    cleanupAndFinish();
                })
                .setNegativeButton("চালু রাখুন", null)
                .show();
    }

    private void cleanupAndFinish() {
        isProcessing = false;
        if (processor != null) {
            processor.cancel();
        }
        finish();
    }

    private void initViews() {
        processingLayout = findViewById(R.id.processingLayout);
        doneLayout = findViewById(R.id.doneLayout);
        progressBar = findViewById(R.id.progressBar);
        outerRing = findViewById(R.id.outerRing);
        progressText = findViewById(R.id.progressText);
        currentStage = findViewById(R.id.currentStage);
        tipText = findViewById(R.id.tipText);
        tipIcon = findViewById(R.id.tipIcon);
        inputFileSize = findViewById(R.id.inputFileSize);
        outputResolution = findViewById(R.id.outputResolution);
        frameCount = findViewById(R.id.frameCount);
        audioFrameCount = findViewById(R.id.audioFrameCount);

        stage1 = findViewById(R.id.stage1);
        stage2 = findViewById(R.id.stage2);
        stage3 = findViewById(R.id.stage3);
        stage4 = findViewById(R.id.stage4);
        stageLine1 = findViewById(R.id.stageLine1);
        stageLine2 = findViewById(R.id.stageLine2);
        stageLine3 = findViewById(R.id.stageLine3);

        logHeader = findViewById(R.id.logHeader);
        logScrollView = findViewById(R.id.logScrollView);
        logText = findViewById(R.id.logText);
        logToggle = findViewById(R.id.logToggle);

        thumbnailContainer = findViewById(R.id.thumbnailContainer);
        videoThumbnail = findViewById(R.id.videoThumbnail);
        videoDuration = findViewById(R.id.videoDuration);
        outputFileSize = findViewById(R.id.outputFileSize);
        outputResolutionDone = findViewById(R.id.outputResolutionDone);
        totalFrames = findViewById(R.id.totalFrames);
        totalAudioFrames = findViewById(R.id.totalAudioFrames);
        downloadBtn = findViewById(R.id.downloadBtn);
        shareBtn = findViewById(R.id.shareBtn);
        newVideoBtn = findViewById(R.id.newVideoBtn);
        pulseView = findViewById(R.id.pulseView);

        logHeader.setOnClickListener(v -> toggleLog());
        thumbnailContainer.setOnClickListener(v -> showVideoPlayerDialog());
        downloadBtn.setOnClickListener(v -> saveToGallery());
        shareBtn.setOnClickListener(v -> shareVideo());
        newVideoBtn.setOnClickListener(v -> cleanupAndFinish());

        if (logText != null) logText.setText("");
    }

    private void toggleLog() {
        logExpanded = !logExpanded;
        if (logScrollView != null) logScrollView.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        if (logToggle != null) logToggle.setText(logExpanded ? "▲" : "▼");
    }

    private void setupAnimations() {
        TextView processingIcon = findViewById(R.id.processingIcon);
        if (processingIcon != null) {
            ObjectAnimator pulseX = ObjectAnimator.ofFloat(processingIcon, "scaleX", 1f, 1.2f, 1f);
            pulseX.setDuration(1000);
            pulseX.setRepeatCount(ValueAnimator.INFINITE);
            pulseX.start();

            ObjectAnimator pulseY = ObjectAnimator.ofFloat(processingIcon, "scaleY", 1f, 1.2f, 1f);
            pulseY.setDuration(1000);
            pulseY.setRepeatCount(ValueAnimator.INFINITE);
            pulseY.start();
        }
    }

    private void startTipRotation() {
        tipRunnable = new Runnable() {
            @Override
            public void run() {
                if (tipText == null || tipIcon == null) return;
                if (tips.length == 0 || tipIcons.length == 0) return;

                int safeLength = Math.min(tips.length, tipIcons.length);

                tipText.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    currentTipIndex = (currentTipIndex + 1) % safeLength;
                    tipText.setText(tips[currentTipIndex]);
                    tipIcon.setText(tipIcons[currentTipIndex]);
                    tipText.animate().alpha(1f).setDuration(300).start();
                }).start();

                tipHandler.postDelayed(this, 4000);
            }
        };
        tipHandler.postDelayed(tipRunnable, 4000);
    }

    private void stopTipRotation() {
        if (tipRunnable != null) {
            tipHandler.removeCallbacks(tipRunnable);
        }
    }

    private void getIntentData() {
        Intent i = getIntent();
        String uriString = i.getStringExtra("VIDEO_URI");
        if (uriString != null) {
            inputVideoUri = Uri.parse(uriString);
        }

        resolution = i.getStringExtra("RESOLUTION");
        if (resolution == null || resolution.isEmpty()) resolution = "original";

        ratio = i.getStringExtra("RATIO");
        if (ratio == null || ratio.isEmpty()) ratio = "original";

        Log.d(TAG, "Intent received → resolution='" + resolution + "' ratio='" + ratio + "'");

        ts = (TransformSettings) i.getSerializableExtra("TRANSFORM_SETTINGS");
        wm = (WatermarkConfig) i.getSerializableExtra("WATERMARK_CONFIG");

        if (ts == null) ts = new TransformSettings();
        if (wm == null) wm = new WatermarkConfig();

        if (wm.logoBitmap == null) {
            wm.logoBitmap = WatermarkConfig.sharedLogoBitmap;
        }
    }

    private void startProcessing() {
        isProcessing = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "CopyrightFree:ProcessingWakeLock"
            );
            wakeLock.acquire(30 * 60 * 1000L);
        }

        addLog("🚀 প্রসেসিং শুরু হচ্ছে...");
        addLog("📐 Resolution: " + resolution + " | Ratio: " + ratio);
        updateProgress(5);
        updateStage(1, "বিশ্লেষণ করা হচ্ছে...");

        try {
            processor = new VideoProcessorMediaCodec(this);

            processor.processVideo(this, inputVideoUri, resolution, ratio, ts, wm,
                    new VideoProcessorMediaCodec.ProgressCallback() {
                        @Override
                        public void onProgress(int percent, String message) {
                            runOnUiThread(() -> handleProgressUpdate(percent, message));
                        }

                        @Override
                        public void onComplete(String path) {
                            outputPath = path;
                            isProcessing = false;
                            releaseWakeLock();
                            runOnUiThread(() -> {
                                addLog("✅ সম্পন্ন: " + new File(path).getName());
                                showDone();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            isProcessing = false;
                            releaseWakeLock();
                            runOnUiThread(() -> {
                                addLog("❌ Error: " + error);
                                showError(error);
                            });
                        }
                    });

        } catch (Exception e) {
            isProcessing = false;
            Log.e(TAG, "Error starting processor", e);
            showError("প্রসেসর শুরু করতে ব্যর্থ: " + e.getMessage());
        }
    }

    private void handleProgressUpdate(int percent, String message) {
        updateProgress(percent);

        long now = System.currentTimeMillis();

        if (now - lastUiRefreshTime >= 200 || percent >= 100 || message.contains("×")
                || message.contains("ফ্রেম:") || message.contains("অডিও:")) {
            parseAndUpdateUI(percent, message);
            lastUiRefreshTime = now;
        }

        if (message != null && !message.trim().isEmpty()) {
            if (!message.equals(lastLogMessage) || (now - lastLogTime) >= 700) {
                addLog(message);
                lastLogMessage = message;
                lastLogTime = now;
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void parseAndUpdateUI(int percent, String message) {
        if (percent < 15) {
            updateStage(1, "বিশ্লেষণ করা হচ্ছে...");
        } else if (percent < 35) {
            updateStage(2, "প্রসেসিং শুরু...");
        } else if (percent < 100) {
            updateStage(3, "এনকোডিং হচ্ছে...");
        } else {
            updateStage(4, "✓ সম্পন্ন!");
        }

        if (message != null && message.contains("ফ্রেম:")) {
            try {
                String[] parts = message.split(":");
                if (parts.length > 1) {
                    processedFrames = Integer.parseInt(parts[1].trim());
                    if (frameCount != null) frameCount.setText(String.valueOf(processedFrames));
                }
            } catch (Exception ignored) {}
        }

        if (message != null && message.contains("অডিও:")) {
            try {
                String[] parts = message.split(":");
                if (parts.length > 1) {
                    processedAudioFrames = Integer.parseInt(parts[1].trim());
                    if (audioFrameCount != null) {
                        audioFrameCount.setText(String.valueOf(processedAudioFrames));
                    }
                }
            } catch (Exception ignored) {}
        }

        if (message != null && message.contains("×")) {
            if (outputResolution != null) outputResolution.setText(message);
        }
    }

    private void updateStage(int stage, String text) {
        if (currentStage != null) currentStage.setText(text);

        if (stage1 != null) stage1.setBackgroundResource(stage >= 1 ? R.drawable.stage_dot_active : R.drawable.stage_dot_inactive);
        if (stage2 != null) stage2.setBackgroundResource(stage >= 2 ? R.drawable.stage_dot_active : R.drawable.stage_dot_inactive);
        if (stage3 != null) stage3.setBackgroundResource(stage >= 3 ? R.drawable.stage_dot_active : R.drawable.stage_dot_inactive);
        if (stage4 != null) stage4.setBackgroundResource(stage >= 4 ? R.drawable.stage_dot_active : R.drawable.stage_dot_inactive);

        int activeColor = ContextCompat.getColor(this, R.color.colorAccent2);
        int inactiveColor = ContextCompat.getColor(this, R.color.text_muted);

        if (stageLine1 != null) stageLine1.setBackgroundColor(stage >= 2 ? activeColor : inactiveColor);
        if (stageLine2 != null) stageLine2.setBackgroundColor(stage >= 3 ? activeColor : inactiveColor);
        if (stageLine3 != null) stageLine3.setBackgroundColor(stage >= 4 ? activeColor : inactiveColor);
    }

    private void updateProgress(int target) {
        target = Math.max(0, Math.min(100, target));

        if (target < currentDisplayedProgress) return;
        currentDisplayedProgress = target;

        if (progressBar != null) progressBar.setProgress(target);
        if (outerRing != null) outerRing.setProgress(target);
        if (progressText != null) progressText.setText(String.valueOf(target));
    }

    private void addLog(String message) {
        Log.d(TAG, message);
        if (logText != null) {
            logText.append(message + "\n");
            if (logExpanded && logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void showError(String error) {
        addLog("❌ " + error);
        if (currentStage != null) {
            currentStage.setText("Error!");
            currentStage.setTextColor(Color.RED);
        }
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void showDone() {
        stopTipRotation();
        updateProgress(100);
        updateStage(4, "✓ সম্পন্ন!");

        if (processingLayout != null) {
            processingLayout.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                        processingLayout.setVisibility(View.GONE);
                        if (doneLayout != null) {
                            doneLayout.setVisibility(View.VISIBLE);
                            doneLayout.setAlpha(0f);
                            doneLayout.animate().alpha(1f).setDuration(500).start();
                        }

                        startSuccessAnimation();
                        loadVideoThumbnail();
                        loadVideoStats();
                    }).start();
        }
    }

    private void startSuccessAnimation() {
        if (pulseView == null) return;

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(pulseView, "scaleX", 0.8f, 1.3f, 0.8f);
        scaleX.setDuration(1500);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator scaleY = ObjectAnimator.ofFloat(pulseView, "scaleY", 0.8f, 1.3f, 0.8f);
        scaleY.setDuration(1500);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator alpha = ObjectAnimator.ofFloat(pulseView, "alpha", 0.5f, 0.2f, 0.5f);
        alpha.setDuration(1500);
        alpha.setRepeatCount(ValueAnimator.INFINITE);
        alpha.setInterpolator(new AccelerateDecelerateInterpolator());

        scaleX.start();
        scaleY.start();
        alpha.start();

        View successIcon = findViewById(R.id.successIcon);
        if (successIcon != null) {
            successIcon.setScaleX(0f);
            successIcon.setScaleY(0f);
            successIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(500)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }
    }

    private void loadVideoThumbnail() {
        if (outputPath == null) return;

        new Thread(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(outputPath);

                thumbnailBitmap = retriever.getFrameAtTime(1000000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (thumbnailBitmap == null) {
                    thumbnailBitmap = retriever.getFrameAtTime(0);
                }

                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    videoDurationMs = Long.parseLong(durationStr);
                }

                runOnUiThread(() -> {
                    if (thumbnailBitmap != null && videoThumbnail != null) {
                        videoThumbnail.setImageBitmap(thumbnailBitmap);
                    }
                    if (videoDuration != null) {
                        videoDuration.setText(formatDuration(videoDurationMs));
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading thumbnail", e);
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void loadVideoStats() {
        if (outputPath == null) return;

        File outputFile = new File(outputPath);
        if (outputFileSize != null) outputFileSize.setText(formatSize(outputFile.length()));
        if (totalFrames != null) totalFrames.setText(String.valueOf(processedFrames));
        if (totalAudioFrames != null) totalAudioFrames.setText(String.valueOf(processedAudioFrames));

        new Thread(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(outputPath);

                String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

                if (width != null && height != null) {
                    runOnUiThread(() -> {
                        if (outputResolutionDone != null) {
                            outputResolutionDone.setText(width + "×" + height);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting video stats", e);
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void showVideoPlayerDialog() {
        if (outputPath == null) {
            Toast.makeText(this, "ভিডিও পাথ নেই", Toast.LENGTH_SHORT).show();
            return;
        }

        File outputFile = new File(outputPath);
        if (!outputFile.exists()) {
            Toast.makeText(this, "ভিডিও ফাইল পাওয়া যায়নি", Toast.LENGTH_SHORT).show();
            return;
        }

        videoDialog = new Dialog(this);
        videoDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        videoDialog.setContentView(R.layout.dialog_video_player);

        Window window = videoDialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(android.view.Gravity.CENTER);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.9f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        videoDialog.setCancelable(true);

        ImageButton btnClose = videoDialog.findViewById(R.id.btnCloseDialog);
        dialogVideoView = videoDialog.findViewById(R.id.dialogVideoView);
        ProgressBar loadingProgress = videoDialog.findViewById(R.id.videoLoadingProgress);
        ImageButton btnPlayPause = videoDialog.findViewById(R.id.btnPlayPause);
        ImageButton btnRewind = videoDialog.findViewById(R.id.btnRewind);
        ImageButton btnForward = videoDialog.findViewById(R.id.btnForward);
        SeekBar seekBar = videoDialog.findViewById(R.id.videoSeekBar);
        TextView tvCurrentTime = videoDialog.findViewById(R.id.tvCurrentTime);
        TextView tvTotalTime = videoDialog.findViewById(R.id.tvTotalTime);

        if (tvTotalTime != null) tvTotalTime.setText(formatDuration(videoDurationMs));
        if (btnClose != null) btnClose.setOnClickListener(v -> dismissVideoDialog());

        Uri videoUri;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                videoUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outputFile);
            } else {
                videoUri = Uri.fromFile(outputFile);
            }
        } catch (Exception e) {
            videoUri = Uri.fromFile(outputFile);
        }

        dialogVideoView.setVideoURI(videoUri);

        dialogVideoView.setOnPreparedListener(mp -> {
            if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
            if (seekBar != null) seekBar.setMax(mp.getDuration());
            if (tvTotalTime != null) tvTotalTime.setText(formatDuration(mp.getDuration()));
            isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            mp.start();
            startSeekBarUpdate(seekBar, tvCurrentTime);
        });

        dialogVideoView.setOnCompletionListener(mp -> {
            isPlaying = false;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
            stopSeekBarUpdate();
            if (seekBar != null) seekBar.setProgress(0);
            if (tvCurrentTime != null) tvCurrentTime.setText("00:00");
        });

        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(v -> {
                if (dialogVideoView.isPlaying()) {
                    dialogVideoView.pause();
                    isPlaying = false;
                    btnPlayPause.setImageResource(R.drawable.ic_play);
                    stopSeekBarUpdate();
                } else {
                    dialogVideoView.start();
                    isPlaying = true;
                    btnPlayPause.setImageResource(R.drawable.ic_pause);
                    startSeekBarUpdate(seekBar, tvCurrentTime);
                }
            });
        }

        if (btnRewind != null) {
            btnRewind.setOnClickListener(v -> {
                int newPos = Math.max(0, dialogVideoView.getCurrentPosition() - 10000);
                dialogVideoView.seekTo(newPos);
                if (seekBar != null) seekBar.setProgress(newPos);
                if (tvCurrentTime != null) tvCurrentTime.setText(formatDuration(newPos));
            });
        }

        if (btnForward != null) {
            btnForward.setOnClickListener(v -> {
                int newPos = Math.min(dialogVideoView.getDuration(),
                        dialogVideoView.getCurrentPosition() + 10000);
                dialogVideoView.seekTo(newPos);
                if (seekBar != null) seekBar.setProgress(newPos);
                if (tvCurrentTime != null) tvCurrentTime.setText(formatDuration(newPos));
            });
        }

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && tvCurrentTime != null) {
                        tvCurrentTime.setText(formatDuration(progress));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    stopSeekBarUpdate();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    dialogVideoView.seekTo(seekBar.getProgress());
                    if (isPlaying) startSeekBarUpdate(seekBar, tvCurrentTime);
                }
            });
        }

        dialogVideoView.setOnErrorListener((mp, what, extra) -> {
            if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
            Toast.makeText(this, "ভিডিও প্লে করা যাচ্ছে না", Toast.LENGTH_SHORT).show();
            return true;
        });

        videoDialog.setOnDismissListener(dialog -> {
            stopSeekBarUpdate();
            if (dialogVideoView != null) {
                try { dialogVideoView.stopPlayback(); } catch (Exception ignored) {}
            }
            isPlaying = false;
        });

        videoDialog.show();
        dialogVideoView.requestFocus();
    }

    private void startSeekBarUpdate(SeekBar seekBar, TextView tvCurrentTime) {
        if (seekBar == null || tvCurrentTime == null) return;

        seekRunnable = new Runnable() {
            @Override
            public void run() {
                if (dialogVideoView != null && dialogVideoView.isPlaying()) {
                    int pos = dialogVideoView.getCurrentPosition();
                    seekBar.setProgress(pos);
                    tvCurrentTime.setText(formatDuration(pos));
                    seekHandler.postDelayed(this, 500);
                }
            }
        };
        seekHandler.post(seekRunnable);
    }

    private void stopSeekBarUpdate() {
        if (seekRunnable != null) {
            seekHandler.removeCallbacks(seekRunnable);
        }
    }

    private void dismissVideoDialog() {
        if (videoDialog != null && videoDialog.isShowing()) {
            videoDialog.dismiss();
        }
    }

    private void saveToGallery() {
        if (outputPath == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!PermissionHelper.hasPermissions(this)) {
                Toast.makeText(this, "Storage Permission নেই", Toast.LENGTH_SHORT).show();
                PermissionHelper.requestPermissions(this, new PermissionHelper.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        saveToGallery();
                    }

                    @Override
                    public void onPermissionDenied() {
                        Toast.makeText(ProcessActivity.this,
                                "Permission ছাড়া সেভ করা যাবে না", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
        }

        if (downloadBtn != null) {
            downloadBtn.setEnabled(false);
            downloadBtn.setText("⏳ সেভ হচ্ছে...");
        }

        new Thread(() -> {
            boolean success = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? saveToGalleryQ() : saveToGalleryLegacy();

            runOnUiThread(() -> {
                if (downloadBtn != null) {
                    downloadBtn.setEnabled(true);
                    downloadBtn.setText("📥  গ্যালারিতে সেভ করুন");
                }

                if (success) {
                    Toast.makeText(this, "✅ ভিডিও Gallery তে সেভ হয়েছে!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "সেভ করা যায়নি", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private boolean saveToGalleryQ() {
        try {
            File src = new File(outputPath);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, src.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/CopyrightFree");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;

            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(src)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (out != null) out.write(buf, 0, len);
                }
            }

            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Save error", e);
            return false;
        }
    }

    private boolean saveToGalleryLegacy() {
        try {
            File moviesDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "CopyrightFree");
            if (!moviesDir.exists()) moviesDir.mkdirs();

            File src = new File(outputPath);
            File dest = new File(moviesDir, src.getName());

            try (FileInputStream in = new FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }

            MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Save error", e);
            return false;
        }
    }

    private void shareVideo() {
        if (outputPath == null) return;
        File file = new File(outputPath);
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "ভিডিও শেয়ার করুন"));
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / 1048576.0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoDialog != null && videoDialog.isShowing() && dialogVideoView != null) {
            if (dialogVideoView.isPlaying()) dialogVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isProcessing = false;
        if (processor != null) {
            processor.cancel();
        }
        releaseWakeLock();
        stopTipRotation();
        stopSeekBarUpdate();
        dismissVideoDialog();
        if (thumbnailBitmap != null && !thumbnailBitmap.isRecycled()) {
            thumbnailBitmap.recycle();
            thumbnailBitmap = null;
        }
    }
}