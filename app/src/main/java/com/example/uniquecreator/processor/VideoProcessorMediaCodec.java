package com.example.uniquecreator.processor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.example.uniquecreator.helper.TransformSettings;
import com.example.uniquecreator.helper.WatermarkConfig;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class VideoProcessorMediaCodec {

    private static final String TAG = "VideoProcessor";
    private static final int TIMEOUT_US = 10000;

    public interface ProgressCallback {
        void onProgress(int percent, String message);
        void onComplete(String outputPath);
        void onError(String error);
    }

    private final Context context;
    private volatile boolean isCancelled = false;
    private final Random random = new Random();

    public VideoProcessorMediaCodec(Context context) {
        this.context = context;
    }

    public void cancel() {
        isCancelled = true;
    }

    public void processVideo(Context context, Uri inputUri, String resolution, String aspectRatio,
                             TransformSettings ts, WatermarkConfig wm,
                             ProgressCallback callback) {
        isCancelled = false;
        new Thread(() -> {
            try {
                doProcess(context, inputUri, resolution, aspectRatio, ts, wm, callback);
            } catch (Exception e) {
                Log.e(TAG, "Processing error", e);
                if (!isCancelled) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
                }
            }
        }, "VideoProcessor").start();
    }

    private void doProcess(Context context, Uri inputUri, String resolution, String aspectRatio,
                           TransformSettings ts, WatermarkConfig wm,
                           ProgressCallback callback) throws Exception {

        if (ts == null) ts = new TransformSettings();
        if (wm == null) wm = new WatermarkConfig();

        // Thread-safe synchronization objects
        final Object frameSyncObject = new Object();
        final boolean[] frameAvailable = {false};

        callback.onProgress(1, "ভিডিও বিশ্লেষণ...");

        // ════════════════════════════════════════════════════════════════════
        // STEP 1: Extract video info
        // ════════════════════════════════════════════════════════════════════
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, inputUri, null);

        int videoTrackIndex = -1;
        int audioTrackIndex = -1;
        MediaFormat videoFormat = null;
        MediaFormat audioFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null) {
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i;
                    videoFormat = format;
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }
        }

        if (videoTrackIndex < 0) {
            extractor.release();
            callback.onError("ভিডিও ট্র্যাক পাওয়া যায়নি");
            return;
        }

        int rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);

        // Get duration from container
        long durationUs = 5_000_000L;
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(context, inputUri);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                durationUs = Long.parseLong(durStr) * 1000L;
            }
        } catch (Exception e) {
            Log.w(TAG, "MMR duration failed, falling back to track", e);
            if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION);
            }
        } finally {
            if (mmr != null) try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }

        int frameRate = 30;
        if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            int fr = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            if (fr > 0 && fr <= 120) frameRate = fr;
        }

        // Read video rotation
        int videoRotation = 0;
        if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            videoRotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
        }
        if (videoRotation == 0) {
            try {
                MediaMetadataRetriever r2 = new MediaMetadataRetriever();
                r2.setDataSource(context, inputUri);
                String rotStr = r2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotStr != null) videoRotation = Integer.parseInt(rotStr);
                r2.release();
            } catch (Exception ignored) {
            }
        }

        Log.d(TAG, "Raw: " + rawWidth + "x" + rawHeight + ", rot=" + videoRotation + ", fps=" + frameRate + ", dur=" + durationUs);

        int inputWidth, inputHeight;
        if (videoRotation == 90 || videoRotation == 270) {
            inputWidth = rawHeight;
            inputHeight = rawWidth;
        } else {
            inputWidth = rawWidth;
            inputHeight = rawHeight;
        }

        callback.onProgress(5, inputWidth + "×" + inputHeight);

        // ════════════════════════════════════════════════════════════════════
        // STEP 2: Output dimensions with PROPER Resolution & Aspect Ratio
        // ════════════════════════════════════════════════════════════════════
        int outputWidth = inputWidth;
        int outputHeight = inputHeight;

        if (resolution == null) resolution = "original";
        if (aspectRatio == null) aspectRatio = "original";

        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "Input: " + inputWidth + "x" + inputHeight);
        Log.d(TAG, "Requested: resolution='" + resolution + "' aspectRatio='" + aspectRatio + "'");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2A: Calculate target aspect ratio
        // ═══════════════════════════════════════════════════════════════
        float inputRatio = (float) inputWidth / inputHeight;
        float targetRatio = inputRatio; // Default: keep original

        if (!"original".equals(aspectRatio)) {
            switch (aspectRatio) {
                case "16:9":
                    targetRatio = 16f / 9f;
                    break;
                case "9:16":
                    targetRatio = 9f / 16f;
                    break;
                case "1:1":
                    targetRatio = 1f;
                    break;
                case "4:3":
                    targetRatio = 4f / 3f;
                    break;
                case "4:5":
                    targetRatio = 4f / 5f;
                    break;
                case "3:4":
                    targetRatio = 3f / 4f;
                    break;
                default:
                    targetRatio = inputRatio;
                    break;
            }
            Log.d(TAG, "Target ratio: " + aspectRatio + " = " + targetRatio);
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 2B: Calculate cropped dimensions (center crop to target ratio)
        // ═══════════════════════════════════════════════════════════════
        int croppedWidth, croppedHeight;

        if (Math.abs(inputRatio - targetRatio) < 0.01f) {
            // Same ratio, no crop needed
            croppedWidth = inputWidth;
            croppedHeight = inputHeight;
            Log.d(TAG, "Same ratio, no crop needed");
        } else if (inputRatio > targetRatio) {
            // Input is WIDER than target → crop width (remove sides)
            croppedHeight = inputHeight;
            croppedWidth = Math.round(inputHeight * targetRatio);
            Log.d(TAG, "Input wider → cropping width: " + inputWidth + " → " + croppedWidth);
        } else {
            // Input is TALLER than target → crop height (remove top/bottom)
            croppedWidth = inputWidth;
            croppedHeight = Math.round(inputWidth / targetRatio);
            Log.d(TAG, "Input taller → cropping height: " + inputHeight + " → " + croppedHeight);
        }

        // Ensure dimensions don't exceed input
        croppedWidth = Math.min(croppedWidth, inputWidth);
        croppedHeight = Math.min(croppedHeight, inputHeight);

        Log.d(TAG, "After ratio crop: " + croppedWidth + "x" + croppedHeight);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2C: Apply Resolution scaling AFTER ratio crop
        // ═══════════════════════════════════════════════════════════════
        outputWidth = croppedWidth;
        outputHeight = croppedHeight;

        if (!"original".equals(resolution)) {
            try {
                int targetHeight = Integer.parseInt(resolution);
                if (outputHeight > targetHeight) {
                    float scale = (float) targetHeight / outputHeight;
                    outputWidth = Math.round(outputWidth * scale);
                    outputHeight = targetHeight;
                    Log.d(TAG, "Resolution scaled to: " + outputWidth + "x" + outputHeight);
                } else {
                    Log.d(TAG, "Target resolution " + targetHeight + "p >= current, no scaling");
                }
            } catch (Exception e) {
                Log.w(TAG, "Invalid resolution value: " + resolution);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 2D: Ensure even dimensions (required by H.264 encoder)
        // ═══════════════════════════════════════════════════════════════
        outputWidth = Math.max(128, (outputWidth / 2) * 2);
        outputHeight = Math.max(128, (outputHeight / 2) * 2);

        final int FINAL_WIDTH = outputWidth;
        final int FINAL_HEIGHT = outputHeight;

        // ═══════════════════════════════════════════════════════════════
        // STEP 2E: Calculate crop UV coordinates for shader
        // ═══════════════════════════════════════════════════════════════
        float cropOffsetX = 0f;
        float cropOffsetY = 0f;
        float cropScaleX = 1f;
        float cropScaleY = 1f;

        if (croppedWidth != inputWidth || croppedHeight != inputHeight) {
            // Calculate the portion of input texture to use
            cropScaleX = (float) croppedWidth / inputWidth;
            cropScaleY = (float) croppedHeight / inputHeight;

            // Center the crop (offset to center)
            cropOffsetX = (1f - cropScaleX) / 2f;
            cropOffsetY = (1f - cropScaleY) / 2f;

            Log.d(TAG, "Crop UV: scaleX=" + cropScaleX + " scaleY=" + cropScaleY +
                    " offsetX=" + cropOffsetX + " offsetY=" + cropOffsetY);
        }

        final float CROP_OFFSET_X = cropOffsetX;
        final float CROP_OFFSET_Y = cropOffsetY;
        final float CROP_SCALE_X = cropScaleX;
        final float CROP_SCALE_Y = cropScaleY;
        final boolean NEEDS_CROP = (cropScaleX < 0.999f || cropScaleY < 0.999f);

        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "FINAL Output: " + FINAL_WIDTH + "x" + FINAL_HEIGHT + " (needsCrop=" + NEEDS_CROP + ")");
        Log.d(TAG, "═══════════════════════════════════════════════════════════");

        // ════════════════════════════════════════════════════════════════════
        // STEP 2F: Other settings
        // ════════════════════════════════════════════════════════════════════
        long trimStartUs = (ts.trimEnabled && ts.trim > 0) ? (long) (ts.trim * 1_000_000L) : 0L;
        float speedFactor = (ts.speedEnabled && ts.speed > 0.1f) ? ts.speed : 1.0f;
        float volumeFactor = ts.volumeEnabled ? Math.max(0f, Math.min(3f, ts.volume)) : 1.0f;

        Log.d(TAG, "Trim=" + trimStartUs + "us Speed=" + speedFactor + " Vol=" + volumeFactor);
        long effectiveDurationUs = (long) ((durationUs - trimStartUs) / speedFactor);
        Log.d(TAG, "★ Effective Duration: " + (effectiveDurationUs / 1_000_000.0f) + "s");
        float effectiveDurationSec = effectiveDurationUs / 1_000_000.0f;

        String outputPath = createOutputPath();

        // ════════════════════════════════════════════════════════════════════
        // STEP 3: Video Encoder with Bitrate Randomization
        // ════════════════════════════════════════════════════════════════════
        int baseBitrate = Math.max(4_000_000, FINAL_WIDTH * FINAL_HEIGHT * 5);

        // Bitrate randomization for encoding fingerprint variation
        int bitrate = baseBitrate;
        if (ts.bitrateRandomEnabled && ts.bitrateVariation > 0) {
            float variation = 1.0f - ts.bitrateVariation + (random.nextFloat() * ts.bitrateVariation * 2);
            bitrate = (int) (baseBitrate * variation);
            Log.d(TAG, "★ Bitrate randomized: " + String.format(Locale.US, "%.2f", variation) + "x = " + bitrate);
        }

        MediaFormat encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, FINAL_WIDTH, FINAL_HEIGHT);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // Variable bitrate mode for more encoding variation
        try {
            encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        } catch (Exception e) {
            Log.w(TAG, "VBR mode not supported, using default");
        }

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderSurface = encoder.createInputSurface();
        encoder.start();

        // ════════════════════════════════════════════════════════════════════
        // STEP 4: EGL Setup
        // ════════════════════════════════════════════════════════════════════
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        int[] recordableAttribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };
        EGL14.eglChooseConfig(eglDisplay, recordableAttribs, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) {
            int[] plainAttribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGL14.eglChooseConfig(eglDisplay, plainAttribs, 0, configs, 0, 1, numConfigs, 0);
        }
        if (numConfigs[0] == 0) throw new RuntimeException("EGL config না পাওয়া গেছে");

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        EGLContext eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT)
            throw new RuntimeException("EGLContext তৈরি ব্যর্থ");

        int[] surfAttribs = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE)
            throw new RuntimeException("EGLSurface তৈরি ব্যর্থ");

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        // ════════════════════════════════════════════════════════════════════
        // STEP 5: GL Resources
        // ════════════════════════════════════════════════════════════════════
        int[] texArr = new int[1];
        GLES20.glGenTextures(1, texArr, 0);
        int textureId = texArr[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // ★ Pass crop parameters to shader
        int program = createShaderProgram(ts, FINAL_WIDTH, FINAL_HEIGHT, CROP_OFFSET_X, CROP_OFFSET_Y, CROP_SCALE_X, CROP_SCALE_Y);
        GLES20.glUseProgram(program);
        if (program == 0) throw new RuntimeException("Shader তৈরি ব্যর্থ");

        int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        int textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        int mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        int stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
        int textureHandle = GLES20.glGetUniformLocation(program, "sTexture");
        int watermarkHandle = GLES20.glGetUniformLocation(program, "sWatermark");
        int hasWatermarkHandle = GLES20.glGetUniformLocation(program, "uHasWatermark");
        int logoRemovalHandle = GLES20.glGetUniformLocation(program, "uLogoRemoval");
        int logoRectHandle = GLES20.glGetUniformLocation(program, "uLogoRect");
        int logoMethodHandle = GLES20.glGetUniformLocation(program, "uLogoMethod");
        int flipEnabledHandle = GLES20.glGetUniformLocation(program, "uFlipEnabled");
        int currentTimeHandle = GLES20.glGetUniformLocation(program, "uCurrentTime");
        int totalDurationHandle = GLES20.glGetUniformLocation(program, "uTotalDuration");

        // Apply logo removal settings
        Rect removalRect = ts.getRemovalRect();
        if (ts.isLogoRemovalEnabled() && removalRect != null) {
            GLES20.glUniform1i(logoRemovalHandle, 1);
            GLES20.glUniform4f(logoRectHandle,
                    (float) removalRect.left / FINAL_WIDTH,
                    (float) removalRect.top / FINAL_HEIGHT,
                    (float) removalRect.right / FINAL_WIDTH,
                    (float) removalRect.bottom / FINAL_HEIGHT);
            GLES20.glUniform1i(logoMethodHandle, ts.logoRemovalMethod);
            Log.d(TAG, "Logo removal applied: " + removalRect.toString());
        } else {
            GLES20.glUniform1i(logoRemovalHandle, 0);
            Log.d(TAG, "No logo removal applied");
        }

        // Set flip flag for watermark mirroring
        GLES20.glUniform1i(flipEnabledHandle, ts.flipEnabled ? 1 : 0);
        Log.d(TAG, "Flip enabled: " + ts.flipEnabled);

        // Set total duration for border progress
//        float effectiveDurationSec = effectiveDurationUs / 1_000_000.0f;
        GLES20.glUniform1f(totalDurationHandle, effectiveDurationSec);
        Log.d(TAG, "★ Border Progress Duration: " + effectiveDurationSec + "s (original=" + (durationUs/1_000_000.0f) + "s)");

        // Watermark texture with dynamic support
        int[] watermarkTexIdHolder = new int[]{0};
        boolean hasWatermark = false;
        final boolean isDynamicWatermark = (wm != null && wm.dynamicPosition);
        long lastWatermarkUpdateUs = 0;

        if (wm != null) {
            wm.syncLogo();
            if (wm.isActive()) {
                Bitmap wmBitmap = createWatermarkBitmap(wm, FINAL_WIDTH, FINAL_HEIGHT, 0);
                if (wmBitmap != null) {
                    watermarkTexIdHolder[0] = createBitmapTexture(wmBitmap);
                    wmBitmap.recycle();
                    hasWatermark = true;
                }
            }
        }
        if (watermarkTexIdHolder[0] == 0) {
            Bitmap dummy = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            dummy.eraseColor(Color.TRANSPARENT);
            watermarkTexIdHolder[0] = createBitmapTexture(dummy);
            dummy.recycle();
        }
        final boolean useWatermark = hasWatermark;

        // Vertex buffer
        float[] vertices = {
                -1f, -1f, 0, 0f, 0f,
                1f, -1f, 0, 1f, 0f,
                -1f, 1f, 0, 0f, 1f,
                1f, 1f, 0, 1f, 1f,
        };
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices).position(0);

        float[] mvpMatrix = new float[16];
        Matrix.setIdentityM(mvpMatrix, 0);
        if (ts.flipEnabled) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f);

        float[] stMatrix = new float[16];
        Matrix.setIdentityM(stMatrix, 0);

        // ════════════════════════════════════════════════════════════════════
        // STEP 6: SurfaceTexture + Decoder
        // ════════════════════════════════════════════════════════════════════
        SurfaceTexture outputSurfaceTexture = new SurfaceTexture(textureId);
        outputSurfaceTexture.setDefaultBufferSize(rawWidth, rawHeight);
        outputSurfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameSyncObject) {
                frameAvailable[0] = true;
                frameSyncObject.notifyAll();
            }
        });

        Surface decoderSurface = new Surface(outputSurfaceTexture);
        MediaCodec decoder = MediaCodec.createDecoderByType(videoMime);
        decoder.configure(videoFormat, decoderSurface, null, 0);
        decoder.start();

        // ════════════════════════════════════════════════════════════════════
        // STEP 7: In-memory video chunk collection
        // ════════════════════════════════════════════════════════════════════
        ArrayList<byte[]> videoChunks = new ArrayList<>();
        ArrayList<MediaCodec.BufferInfo> videoChunkInfos = new ArrayList<>();
        MediaFormat encodedVideoFormat = null;

        extractor.selectTrack(videoTrackIndex);
        if (trimStartUs > 0) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        // ════════════════════════════════════════════════════════════════════
        // STEP 8: Main encode loop with Temporal Jitter
        // ════════════════════════════════════════════════════════════════════
        final int VP_START = 10, VP_END = 75;
        callback.onProgress(VP_START, "প্রসেসিং শুরু...");

        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

        boolean inputDone = false;
        boolean outputDone = false;
        boolean encoderDone = false;
        int frameCount = 0;
        int skippedFrames = 0;
        int duplicatedFrames = 0;

//        long effectiveDurationUs = (long) ((durationUs - trimStartUs) / speedFactor);
        int totalEstimatedFrames = Math.max(1, (int) (effectiveDurationUs * frameRate / 1_000_000.0));
        long frameIntervalUs = 1_000_000L / frameRate;
        long lastPts = -1L;
        long videoFirstPts = -1L;
        long lastProgressUpdate = System.currentTimeMillis();

        // For temporal jitter
        boolean pendingDuplicate = false;
        long duplicatePts = 0;
        float[] duplicateStMatrix = new float[16];

        while (!encoderDone && !isCancelled) {

            // --- DECODER INPUT ---
            if (!inputDone) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        int sz = extractor.readSampleData(inBuf, 0);
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
            }

            // --- Handle pending frame duplication ---
            if (pendingDuplicate && !outputDone) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

                GLES20.glViewport(0, 0, FINAL_WIDTH, FINAL_HEIGHT);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(program);

                // Update current time for border progress
                float currentTimeSec = duplicatePts / 1_000_000.0f;
                GLES20.glUniform1f(currentTimeHandle, currentTimeSec);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                GLES20.glUniform1i(textureHandle, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexIdHolder[0]);
                GLES20.glUniform1i(watermarkHandle, 1);
                GLES20.glUniform1i(hasWatermarkHandle, useWatermark ? 1 : 0);

                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
                GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, duplicateStMatrix, 0);

                vertexBuffer.position(0);
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer);
                GLES20.glEnableVertexAttribArray(positionHandle);
                vertexBuffer.position(3);
                GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer);
                GLES20.glEnableVertexAttribArray(textureCoordHandle);

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(positionHandle);
                GLES20.glDisableVertexAttribArray(textureCoordHandle);
                GLES20.glFlush();

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, duplicatePts * 1000L);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                lastPts = duplicatePts;
                frameCount++;
                duplicatedFrames++;
                pendingDuplicate = false;

                Log.d(TAG, "★ Jitter: Duplicated frame rendered at pts=" + duplicatePts);
            }

            // --- DECODER OUTPUT ---
            if (!outputDone) {
                int outIdx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
                if (outIdx >= 0) {
                    boolean doRender = (decoderInfo.size != 0);
                    decoder.releaseOutputBuffer(outIdx, doRender);

                    if (doRender) {
                        boolean got = awaitNewFrame(frameSyncObject, frameAvailable);
                        if (got) {
                            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                            outputSurfaceTexture.updateTexImage();
                            outputSurfaceTexture.getTransformMatrix(stMatrix);

                            // PTS calculation
                            long rawPts = decoderInfo.presentationTimeUs;
                            if (videoFirstPts < 0) videoFirstPts = rawPts;
                            long outputPts = (long) ((rawPts - videoFirstPts) / speedFactor);
                            if (lastPts >= 0 && outputPts <= lastPts)
                                outputPts = lastPts + frameIntervalUs;

                            // Temporal Jitter - randomly skip or duplicate frames
                            boolean shouldRenderFrame = true;
                            boolean shouldDuplicate = false;

                            if (ts.temporalJitterEnabled && ts.jitterIntensity > 0 && frameCount > 10) {
                                float rand = random.nextFloat();
                                if (rand < ts.jitterIntensity) {
                                    if (random.nextBoolean()) {
                                        // Skip this frame
                                        shouldRenderFrame = false;
                                        skippedFrames++;
                                        Log.d(TAG, "★ Jitter: Skip frame " + frameCount);
                                    } else {
                                        // Duplicate this frame
                                        shouldDuplicate = true;
                                    }
                                }
                            }

                            if (shouldRenderFrame) {
                                // Dynamic watermark update
                                if (isDynamicWatermark && wm != null && wm.isActive()) {
                                    long intervalUs = wm.positionChangeIntervalSec * 1_000_000L;
                                    if ((outputPts - lastWatermarkUpdateUs) >= intervalUs) {
                                        Bitmap newWmBitmap = createWatermarkBitmap(wm, FINAL_WIDTH, FINAL_HEIGHT, outputPts);
                                        if (newWmBitmap != null) {
                                            GLES20.glDeleteTextures(1, watermarkTexIdHolder, 0);
                                            watermarkTexIdHolder[0] = createBitmapTexture(newWmBitmap);
                                            newWmBitmap.recycle();
                                            lastWatermarkUpdateUs = outputPts;
                                            Log.d(TAG, "★ Dynamic watermark updated at " + (outputPts / 1_000_000) + "s");
                                        }
                                    }
                                }

                                // Render frame
                                GLES20.glViewport(0, 0, FINAL_WIDTH, FINAL_HEIGHT);
                                GLES20.glClearColor(0f, 0f, 0f, 1f);
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                                GLES20.glUseProgram(program);

                                // ★ Update current time for border progress
                                float currentTimeSec = outputPts / 1_000_000.0f;
                                GLES20.glUniform1f(currentTimeHandle, currentTimeSec);

                                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                                GLES20.glUniform1i(textureHandle, 0);

                                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexIdHolder[0]);
                                GLES20.glUniform1i(watermarkHandle, 1);
                                GLES20.glUniform1i(hasWatermarkHandle, useWatermark ? 1 : 0);

                                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
                                GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0);

                                vertexBuffer.position(0);
                                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer);
                                GLES20.glEnableVertexAttribArray(positionHandle);
                                vertexBuffer.position(3);
                                GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer);
                                GLES20.glEnableVertexAttribArray(textureCoordHandle);

                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                                GLES20.glDisableVertexAttribArray(positionHandle);
                                GLES20.glDisableVertexAttribArray(textureCoordHandle);
                                GLES20.glFlush();

                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, outputPts * 1000L);
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                                lastPts = outputPts;
                                frameCount++;

                                // Set up frame duplication if needed
                                if (shouldDuplicate) {
                                    pendingDuplicate = true;
                                    duplicatePts = outputPts + (frameIntervalUs / 2);
                                    System.arraycopy(stMatrix, 0, duplicateStMatrix, 0, 16);
                                    Log.d(TAG, "★ Jitter: Preparing duplicate for frame " + frameCount);
                                }

                                if (System.currentTimeMillis() - lastProgressUpdate > 250) {
                                    int pct = VP_START + (int) ((VP_END - VP_START) * (double) frameCount / totalEstimatedFrames);
                                    callback.onProgress(Math.min(VP_END, Math.max(VP_START, pct)), "ফ্রেম: " + frameCount);
                                    lastProgressUpdate = System.currentTimeMillis();
                                }
                            }
                        }
                    }

                    if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        encoder.signalEndOfInputStream();
                    }
                }
            }

            // --- ENCODER OUTPUT: collect chunks ---
            while (true) {
                int encIdx = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US);
                if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encodedVideoFormat == null) {
                        encodedVideoFormat = encoder.getOutputFormat();
                        Log.d(TAG, "Encoder format captured");
                    }
                } else if (encIdx >= 0) {
                    ByteBuffer encData = encoder.getOutputBuffer(encIdx);
                    if (encData != null && encodedVideoFormat != null && encoderInfo.size > 0
                            && (encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encData.position(encoderInfo.offset);
                        encData.limit(encoderInfo.offset + encoderInfo.size);
                        byte[] chunk = new byte[encoderInfo.size];
                        encData.get(chunk);
                        MediaCodec.BufferInfo si = new MediaCodec.BufferInfo();
                        si.set(0, chunk.length, encoderInfo.presentationTimeUs, encoderInfo.flags);
                        videoChunks.add(chunk);
                        videoChunkInfos.add(si);
                    }
                    encoder.releaseOutputBuffer(encIdx, false);
                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        Log.d(TAG, "Loop done. Frames=" + frameCount + " chunks=" + videoChunks.size() +
                " skipped=" + skippedFrames + " duplicated=" + duplicatedFrames);

        // ════════════════════════════════════════════════════════════════════
        // Cleanup GL / EGL / Codec
        // ════════════════════════════════════════════════════════════════════
        try {
            decoder.stop();
            decoder.release();
        } catch (Exception e) {
            Log.e(TAG, "decoder release", e);
        }
        try {
            encoder.stop();
            encoder.release();
        } catch (Exception e) {
            Log.e(TAG, "encoder release", e);
        }
        try {
            extractor.release();
        } catch (Exception ignored) {
        }
        try {
            decoderSurface.release();
        } catch (Exception ignored) {
        }
        try {
            outputSurfaceTexture.release();
        } catch (Exception ignored) {
        }

        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        GLES20.glDeleteTextures(1, watermarkTexIdHolder, 0);
        GLES20.glDeleteProgram(program);

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
        try {
            encoderSurface.release();
        } catch (Exception ignored) {
        }

        if (isCancelled) {
            Log.d(TAG, "Cancelled");
            return;
        }

        if (videoChunks.isEmpty() || encodedVideoFormat == null) {
            callback.onError("ভিডিও এনকোড ব্যর্থ (কোনো ফ্রেম নেই)");
            return;
        }

        callback.onProgress(75, "ভিডিও সম্পন্ন...");

        if (audioTrackIndex >= 0 && audioFormat != null) {
            processAudioWithVolume(context, inputUri, outputPath, audioTrackIndex, audioFormat,
                    trimStartUs, speedFactor, volumeFactor, effectiveDurationUs, callback, ts,
                    videoChunks, videoChunkInfos, encodedVideoFormat);
        } else {
            callback.onProgress(80, "ভিডিও মিক্স হচ্ছে...");
            muxVideoOnly(encodedVideoFormat, videoChunks, videoChunkInfos, outputPath);
        }

        if (isCancelled) return;
        callback.onProgress(90, "ফাইনালাইজ হচ্ছে...");

        File finalFile = new File(outputPath);
        if (!finalFile.exists() || finalFile.length() < 1000) {
            callback.onError("ফাইল তৈরি হয়নি");
            return;
        }

        callback.onProgress(98, "সম্পন্ন হচ্ছে...");
        Log.d(TAG, "Complete! frames=" + frameCount + " size=" + finalFile.length());
        callback.onProgress(100, "✓ সম্পন্ন!");
        callback.onComplete(outputPath);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // FRAME SYNC
    // ════════════════════════════════════════════════════════════════════════════
    private boolean awaitNewFrame(Object syncObj, boolean[] available) {
        final int TIMEOUT_MS = 1500;
        synchronized (syncObj) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!available[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                try {
                    syncObj.wait(remaining);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            available[0] = false;
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════════
    // SHADER PROGRAM (with Aspect Ratio Crop Support & Flip compensation for watermark)
    // ════════════════════════════════════════════════════════════════════════════
    private int createShaderProgram(TransformSettings ts, int outW, int outH,
                                    float cropOffsetX, float cropOffsetY,
                                    float cropScaleX, float cropScaleY) {
        if (ts == null) ts = new TransformSettings();

        String vertexShader =
                "uniform mat4 uMVPMatrix;\n" +
                        "uniform mat4 uSTMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec4 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = uMVPMatrix * aPosition;\n" +
                        "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                        "}\n";

        StringBuilder fs = new StringBuilder();
        fs.append("#extension GL_OES_EGL_image_external : require\n");
        fs.append("precision mediump float;\n");
        fs.append("varying vec2 vTextureCoord;\n");
        fs.append("uniform samplerExternalOES sTexture;\n");
        fs.append("uniform sampler2D sWatermark;\n");
        fs.append("uniform int uHasWatermark;\n");
        fs.append("uniform int uFlipEnabled;\n\n");
        fs.append("uniform int uLogoRemoval;\n");
        fs.append("uniform vec4 uLogoRect;\n");
        fs.append("uniform int uLogoMethod;\n\n");
        fs.append("uniform float uTotalDuration;\n\n");
        fs.append("uniform float uCurrentTime;\n");

        // HSV helpers
        fs.append("vec3 rgb2hsv(vec3 c) {\n");
        fs.append("    vec4 K = vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);\n");
        fs.append("    vec4 p = mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));\n");
        fs.append("    vec4 q = mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));\n");
        fs.append("    float d=q.x-min(q.w,q.y); float e=1.0e-10;\n");
        fs.append("    return vec3(abs(q.z+(q.w-q.y)/(6.0*d+e)),d/(q.x+e),q.x);\n");
        fs.append("}\n");
        fs.append("vec3 hsv2rgb(vec3 c) {\n");
        fs.append("    vec4 K=vec4(1.0,2.0/3.0,1.0/3.0,3.0);\n");
        fs.append("    vec3 p=abs(fract(c.xxx+K.xyz)*6.0-K.www);\n");
        fs.append("    return c.z*mix(K.xxx,clamp(p-K.xxx,0.0,1.0),c.y);\n");
        fs.append("}\n\n");

        fs.append("void main() {\n");
        fs.append("    vec2 uv = vTextureCoord;\n");

        // ═══════════════════════════════════════════════════════════════
        // ★ ASPECT RATIO CENTER CROP (FIRST - before all other transforms)
        // ═══════════════════════════════════════════════════════════════
        boolean needsCrop = (cropScaleX < 0.999f || cropScaleY < 0.999f);
        if (needsCrop) {
            fs.append("\n    // ═══ Aspect Ratio Center Crop ═══\n");
            fs.append("    uv = uv * vec2(").append(String.format(Locale.US, "%.6f", cropScaleX))
                    .append(", ").append(String.format(Locale.US, "%.6f", cropScaleY)).append(");\n");
            fs.append("    uv = uv + vec2(").append(String.format(Locale.US, "%.6f", cropOffsetX))
                    .append(", ").append(String.format(Locale.US, "%.6f", cropOffsetY)).append(");\n");
        }

        // BARREL DISTORTION
        if (ts.barrelEnabled && ts.barrel > 0.01f) {
            fs.append("\n    // Barrel Distortion\n");
            fs.append("    {\n");
            fs.append("        vec2 p = uv - 0.5;\n");
            fs.append("        float r2 = dot(p,p);\n");
            fs.append("        p *= (1.0 + ").append(ts.barrel).append(" * r2);\n");
            fs.append("        uv = p + 0.5;\n");
            fs.append("        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n");
            fs.append("            gl_FragColor = vec4(0.0,0.0,0.0,1.0); return;\n");
            fs.append("        }\n");
            fs.append("    }\n");
        }

        // ROTATE
        if (ts.rotateEnabled && Math.abs(ts.rotate) > 0.01f) {
            double rad = Math.toRadians(ts.rotate);
            float cosR = (float) Math.cos(rad), sinR = (float) Math.sin(rad);
            fs.append("\n    // Rotation\n");
            fs.append("    uv -= 0.5;\n");
            fs.append("    uv = vec2(uv.x*").append(cosR).append("-uv.y*").append(sinR)
                    .append(", uv.x*").append(sinR).append("+uv.y*").append(cosR).append(");\n");
            fs.append("    uv += 0.5;\n");
        }

        // ZOOM
        if (ts.zoomEnabled && ts.zoom > 1.001f) {
            fs.append("\n    // Zoom\n");
            fs.append("    uv = (uv-0.5)/").append(Math.min(ts.zoom, 3f)).append("+0.5;\n");
        }

        // PIXEL SHIFT
        if (ts.pixelShiftEnabled && ts.pixelShift != 0) {
            float sx = ts.pixelShift / (float) outW;
            float sy = ts.pixelShift / (float) outH;
            fs.append("\n    // Pixel Shift\n");
            fs.append("    uv += vec2(").append(sx).append(",").append(sy).append(");\n");
        }

        fs.append("\n    uv = clamp(uv, 0.001, 0.999);\n");

        // CHROMATIC ABERRATION
        if (ts.chromaticEnabled && ts.chromatic > 0.3f) {
            float offX = ts.chromatic / outW;
            fs.append("\n    // Chromatic Aberration\n");
            fs.append("    float cR = texture2D(sTexture, uv - vec2(").append(offX).append(",0.0)).r;\n");
            fs.append("    float cG = texture2D(sTexture, uv).g;\n");
            fs.append("    float cB = texture2D(sTexture, uv + vec2(").append(offX).append(",0.0)).b;\n");
            fs.append("    vec4 color = vec4(cR, cG, cB, 1.0);\n");
        } else {
            fs.append("\n    vec4 color = texture2D(sTexture, uv);\n");
        }

        // ★ LOGO REMOVAL — use gl_FragCoord for pixel-accurate position matching UI selection
        // gl_FragCoord.xy = actual output pixel, same coordinate system as the UI sliders
        // uLogoRect is normalized: (left/W, top/H, right/W, bottom/H) in screen space (Y=0 at top)
        // gl_FragCoord.y is Y=0 at bottom, so we flip: screenY = 1.0 - (fragY / outH)
        fs.append("\n    // ═══ Logo Removal (pixel-accurate via gl_FragCoord) ═══\n");
        fs.append("    if(uLogoRemoval == 1) {\n");
        fs.append("        vec2 fragNorm = vec2(gl_FragCoord.x / ").append((float)outW).append(", 1.0 - gl_FragCoord.y / ").append((float)outH).append(");\n");
        fs.append("        if(fragNorm.x >= uLogoRect.x && fragNorm.x <= uLogoRect.z && fragNorm.y >= uLogoRect.y && fragNorm.y <= uLogoRect.w) {\n");
        switch (ts.logoRemovalMethod) {
            case 0: // Blur — sample around current pixel in video UV space
                float blurStep = Math.min(ts.removalBlurIntensity / outW, 0.05f);
                fs.append("            vec3 blurred = vec3(0.0);\n");
                fs.append("            int samples = 0;\n");
                fs.append("            for(int dx = -3; dx <= 3; dx++) {\n");
                fs.append("                for(int dy = -3; dy <= 3; dy++) {\n");
                fs.append("                    vec2 offset = vec2(float(dx), float(dy)) * ").append(blurStep).append(";\n");
                fs.append("                    blurred += texture2D(sTexture, clamp(uv + offset, 0.001, 0.999)).rgb;\n");
                fs.append("                    samples++;\n");
                fs.append("                }\n");
                fs.append("            }\n");
                fs.append("            color.rgb = blurred / float(samples);\n");
                break;
            case 1: // Blackout
                fs.append("            color.rgb = vec3(0.0);\n");
                break;
            case 2: // Pixelate — snap to block grid in video UV space
                int pixelSize = 16;
                float pxW = (float) pixelSize / outW;
                float pxH = (float) pixelSize / outH;
                fs.append("            vec2 pixelUV = floor(uv / vec2(").append(pxW).append(",").append(pxH).append(")) * vec2(").append(pxW).append(",").append(pxH).append(") + vec2(").append(pxW/2).append(",").append(pxH/2).append(");\n");
                fs.append("            color.rgb = texture2D(sTexture, clamp(pixelUV, 0.001, 0.999)).rgb;\n");
                break;
        }
        fs.append("        }\n");
        fs.append("    }\n");

        // BRIGHTNESS
        if (ts.brightEnabled && Math.abs(ts.bright - 1f) > 0.001f) {
            fs.append("    color.rgb *= ").append(ts.bright).append(";\n");
        }

        // SATURATION
        if (ts.satEnabled && Math.abs(ts.saturation - 1f) > 0.001f) {
            fs.append("    float lum = dot(color.rgb, vec3(0.2126,0.7152,0.0722));\n");
            fs.append("    color.rgb = mix(vec3(lum), color.rgb, ").append(ts.saturation).append(");\n");
        }

        // HUE
        if (ts.hueEnabled && Math.abs(ts.hue) > 0.1f) {
            float hueShift = ts.hue / 360f;
            fs.append("    vec3 hsv = rgb2hsv(color.rgb);\n");
            fs.append("    hsv.x = fract(hsv.x + ").append(hueShift).append(");\n");
            fs.append("    color.rgb = hsv2rgb(hsv);\n");
        }

        // GAMMA
        if (ts.gammaEnabled && Math.abs(ts.gamma - 1f) > 0.001f) {
            float invG = 1f / ts.gamma;
            fs.append("    color.rgb = pow(max(color.rgb,0.0), vec3(").append(invG).append("));\n");
        }

        // SEPIA
        if (ts.sepiaEnabled && ts.sepia > 0.001f) {
            fs.append("    vec3 sep;\n");
            fs.append("    sep.r=dot(color.rgb,vec3(0.393,0.769,0.189));\n");
            fs.append("    sep.g=dot(color.rgb,vec3(0.349,0.686,0.168));\n");
            fs.append("    sep.b=dot(color.rgb,vec3(0.272,0.534,0.131));\n");
            fs.append("    color.rgb=mix(color.rgb,sep,").append(ts.sepia).append(");\n");
        }

        // TINT
        if (ts.tintEnabled && ts.tint > 0.001f) {
            float r = ((ts.tintColor >> 16) & 0xFF) / 255f;
            float g = ((ts.tintColor >> 8) & 0xFF) / 255f;
            float b = (ts.tintColor & 0xFF) / 255f;
            fs.append("    color.rgb=mix(color.rgb,vec3(").append(r).append(",").append(g).append(",").append(b).append("),").append(ts.tint).append(");\n");
        }

        // SHARPEN
        if (ts.sharpenEnabled && ts.sharpen > 0.001f) {
            float stepX = 1f / outW;
            float stepY = 1f / outH;
            fs.append("    vec3 sblur =\n");
            fs.append("        texture2D(sTexture,uv+vec2(-").append(stepX).append(",0.0)).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(").append(stepX).append(",0.0)).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(0.0,-").append(stepY).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(0.0,").append(stepY).append(")).rgb;\n");
            fs.append("    sblur *= 0.25;\n");
            fs.append("    color.rgb += (color.rgb - sblur) * ").append(ts.sharpen).append(";\n");
        }

        // BLUR
        if (ts.blurEnabled && ts.blur > 0.01f) {
            float blurOff = ts.blur / outW;
            fs.append("    vec3 blurred =\n");
            fs.append("        texture2D(sTexture,uv+vec2(-").append(blurOff).append(",-").append(blurOff).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(0.0,-").append(blurOff).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(").append(blurOff).append(",-").append(blurOff).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(-").append(blurOff).append(",0.0)).rgb+\n");
            fs.append("        color.rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(").append(blurOff).append(",0.0)).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(-").append(blurOff).append(",").append(blurOff).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(0.0,").append(blurOff).append(")).rgb+\n");
            fs.append("        texture2D(sTexture,uv+vec2(").append(blurOff).append(",").append(blurOff).append(")).rgb;\n");
            fs.append("    color.rgb = blurred / 9.0;\n");
        }

        // NOISE
        if (ts.noiseEnabled && ts.noise > 0.0001f) {
            fs.append("    float n=fract(sin(dot(uv,vec2(12.9898,78.233)))*43758.5453);\n");
            fs.append("    color.rgb += (n-0.5)*").append(ts.noise).append(";\n");
        }

        // VIGNETTE
        if (ts.vignetteEnabled && ts.vignette > 0.001f) {
            fs.append("    vec2 vc=uv-0.5;\n");
            fs.append("    float vf=1.0-dot(vc,vc)*").append(ts.vignette * 2f).append(";\n");
            fs.append("    color.rgb *= clamp(vf,0.0,1.0);\n");
        }

        // BORDER
        if (ts.borderEnabled && ts.border > 0) {
            float bx = ts.border / (float) outW;
            float by = ts.border / (float) outH;
            fs.append("    if(uv.x<").append(bx).append("||uv.x>").append(1f - bx)
                    .append("||uv.y<").append(by).append("||uv.y>").append(1f - by).append(") color.rgb=vec3(0.0);\n");
        }

        fs.append("    color.rgb = clamp(color.rgb, 0.0, 1.0);\n");

        // ═══════════════════════════════════════════════════════════════
        // ★ BORDER PROGRESS (Duration-based animated border) ★
        // ═══════════════════════════════════════════════════════════════
        if (ts.borderProgressEnabled && ts.borderProgressSize > 0) {
            float bpSizeX = ts.borderProgressSize / (float) outW;
            float bpSizeY = ts.borderProgressSize / (float) outH;

            // ★ Extract color components from settings
            float bpR = ((ts.borderProgressColor >> 16) & 0xFF) / 255f;
            float bpG = ((ts.borderProgressColor >> 8) & 0xFF) / 255f;
            float bpB = (ts.borderProgressColor & 0xFF) / 255f;

            fs.append("\n    // ═══ Border Progress ═══\n");
            fs.append("    {\n");
            fs.append("        float progress = clamp(uCurrentTime / max(uTotalDuration, 0.01), 0.0, 1.0);\n");
            fs.append("        float bpx = ").append(String.format(Locale.US, "%.6f", bpSizeX)).append(";\n");
            fs.append("        float bpy = ").append(String.format(Locale.US, "%.6f", bpSizeY)).append(";\n");
            fs.append("        \n");
            fs.append("        // Total perimeter = 4 units (each edge = 1 unit)\n");
            fs.append("        float totalProgress = progress * 4.0;\n");
            fs.append("        bool hit = false;\n");
            fs.append("        vec2 sc = vTextureCoord;\n");
            fs.append("        \n");

            // OpenGL coordinates: (0,0) = bottom-left, (1,1) = top-right
            // We want: Bottom → Right → Top → Left (clockwise from bottom-left)

            // Edge 1: Bottom edge - LEFT to RIGHT (y near 0, x goes 0→1)
            fs.append("        // Edge 1: Bottom (left to right)\n");
            fs.append("        if(sc.y <= bpy) {\n");
            fs.append("            float edgeProgress = clamp(totalProgress, 0.0, 1.0);\n");
            fs.append("            if(sc.x <= edgeProgress) hit = true;\n");
            fs.append("        }\n");

            // Edge 2: Right edge - BOTTOM to TOP (x near 1, y goes 0→1)
            fs.append("        \n");
            fs.append("        // Edge 2: Right (bottom to top)\n");
            fs.append("        if(sc.x >= (1.0 - bpx)) {\n");
            fs.append("            if(totalProgress >= 1.0) {\n");
            fs.append("                float edgeProgress = clamp(totalProgress - 1.0, 0.0, 1.0);\n");
            fs.append("                if(sc.y <= edgeProgress) hit = true;\n");
            fs.append("            }\n");
            fs.append("        }\n");

            // Edge 3: Top edge - RIGHT to LEFT (y near 1, x goes 1→0)
            fs.append("        \n");
            fs.append("        // Edge 3: Top (right to left)\n");
            fs.append("        if(sc.y >= (1.0 - bpy)) {\n");
            fs.append("            if(totalProgress >= 2.0) {\n");
            fs.append("                float edgeProgress = clamp(totalProgress - 2.0, 0.0, 1.0);\n");
            fs.append("                // Right to left: x >= (1.0 - edgeProgress)\n");
            fs.append("                if(sc.x >= (1.0 - edgeProgress)) hit = true;\n");
            fs.append("            }\n");
            fs.append("        }\n");

            // Edge 4: Left edge - TOP to BOTTOM (x near 0, y goes 1→0)
            fs.append("        \n");
            fs.append("        // Edge 4: Left (top to bottom)\n");
            fs.append("        if(sc.x <= bpx) {\n");
            fs.append("            if(totalProgress >= 3.0) {\n");
            fs.append("                float edgeProgress = clamp(totalProgress - 3.0, 0.0, 1.0);\n");
            fs.append("                // Top to bottom: y >= (1.0 - edgeProgress)\n");
            fs.append("                // When edgeProgress=0, y>=1 (top only)\n");
            fs.append("                // When edgeProgress=1, y>=0 (full left edge down to bottom)\n");
            fs.append("                if(sc.y >= (1.0 - edgeProgress)) hit = true;\n");
            fs.append("            }\n");
            fs.append("        }\n");

            fs.append("        \n");
            fs.append("        if(hit) {\n");
            fs.append("            color.rgb = mix(color.rgb, vec3(")
                    .append(String.format(Locale.US, "%.3f", bpR)).append(", ")
                    .append(String.format(Locale.US, "%.3f", bpG)).append(", ")
                    .append(String.format(Locale.US, "%.3f", bpB)).append("), 0.85);\n");
            fs.append("        }\n");
            fs.append("    }\n");
        }

        fs.append("    color.rgb = clamp(color.rgb, 0.0, 1.0);\n");

        // WATERMARK (with flip compensation)
        fs.append("\n    // ═══ Watermark Overlay ═══\n");
        fs.append("    if(uHasWatermark==1){\n");
        fs.append("        vec2 wmCoord = vTextureCoord;\n");
        fs.append("        // Watermark bitmap is pre-flipped for OpenGL, so use direct coords\n");
        fs.append("        // Only flip X if video is horizontally mirrored\n");
        fs.append("        if(uFlipEnabled == 1) {\n");
        fs.append("            wmCoord.x = 1.0 - wmCoord.x;\n");
        fs.append("        }\n");
        fs.append("        vec4 wm = texture2D(sWatermark, wmCoord);\n");
        fs.append("        color.rgb = mix(color.rgb, wm.rgb, wm.a);\n");
        fs.append("    }\n");

        fs.append("    gl_FragColor = color;\n");
        fs.append("}\n");

        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fsh = compileShader(GLES20.GL_FRAGMENT_SHADER, fs.toString());
        if (vs == 0 || fsh == 0) return 0;

        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fsh);
        GLES20.glLinkProgram(prog);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return 0;
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fsh);
        return prog;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    // ════════════════════════════════════════════════════════════════════════════
    // AUDIO PROCESSING (Enhanced with Spectral Noise & Ambient Noise)
    // ════════════════════════════════════════════════════════════════════════════
    private void processAudioWithVolume(Context context, Uri inputUri, String videoOutputPath,
                                        int audioTrackIndex, MediaFormat audioFormat,
                                        long trimStartUs, float speedFactor, float volumeFactor,
                                        long maxDurationUs, ProgressCallback callback, TransformSettings ts,
                                        ArrayList<byte[]> videoChunks,
                                        ArrayList<MediaCodec.BufferInfo> videoChunkInfos,
                                        MediaFormat encodedVideoFormat) {

        String finalOutputPath = videoOutputPath.replace(".mp4", "_final.mp4");
        MediaExtractor audioExtractor = null;
        MediaCodec audioDecoder = null;
        MediaCodec audioEncoder = null;

        try {
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(context, inputUri, null);
            audioExtractor.selectTrack(audioTrackIndex);
            if (trimStartUs > 0)
                audioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int localAudioChannels = channelCount;

            final int AP_START = 75, AP_END = 90;
            int estimatedSamples = (int) (maxDurationUs * (long) sampleRate / 1_000_000L / 1024);
            if (estimatedSamples < 1) estimatedSamples = 1;

            audioDecoder = MediaCodec.createDecoderByType(audioMime);
            audioDecoder.configure(audioFormat, null, null, 0);
            audioDecoder.start();

            MediaFormat encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            boolean inputDone = false, decodeDone = false, encodeDone = false;
            ArrayList<byte[]> encodedChunks = new ArrayList<>();
            ArrayList<MediaCodec.BufferInfo> chunkInfos = new ArrayList<>();
            MediaFormat outputAudioFormat = null;

            long firstPts = -1L, lastOutputPts = -1L;
            int processedSamples = 0;
            long lastAudioProgress = System.currentTimeMillis();
            short[] pendingSamples = null;
            long pendingPts = 0;
            boolean eosSignalPending = false;

            while (!encodeDone && !isCancelled) {

                // DECODER INPUT
                if (!inputDone) {
                    int inIdx = audioDecoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = audioDecoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            inBuf.clear();
                            int sz = audioExtractor.readSampleData(inBuf, 0);
                            if (sz < 0) {
                                audioDecoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                audioDecoder.queueInputBuffer(inIdx, 0, sz, audioExtractor.getSampleTime(), 0);
                                audioExtractor.advance();
                            }
                        }
                    }
                }

                // ENCODER INPUT — pending first
                if (pendingSamples != null) {
                    int encInIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                    if (encInIdx >= 0) {
                        writeSamplesToEncoder(audioEncoder, encInIdx, pendingSamples, pendingPts);
                        lastOutputPts = pendingPts;
                        processedSamples++;
                        pendingSamples = null;
                        if (eosSignalPending) {
                            int eosIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                            if (eosIdx >= 0) {
                                audioEncoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                eosSignalPending = false;
                            }
                        }
                    }
                } else if (!decodeDone) {
                    // DECODER OUTPUT
                    int outIdx = audioDecoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIdx >= 0) {
                        boolean isEos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        ByteBuffer outBuf = audioDecoder.getOutputBuffer(outIdx);

                        if (decInfo.size > 0 && outBuf != null) {
                            outBuf.position(decInfo.offset);
                            outBuf.limit(decInfo.offset + decInfo.size);
                            ShortBuffer sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            short[] samples = new short[sb.remaining()];
                            sb.get(samples);

                            // ═══════════════════════════════════════════════════
                            // AUDIO TRANSFORMATION PIPELINE
                            // ═══════════════════════════════════════════════════

                            // 1. Volume adjustment
                            for (int i = 0; i < samples.length; i++) {
                                float s = samples[i] * volumeFactor;
                                samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) s));
                            }

                            // 2. Pitch shift
                            if (ts.pitchEnabled && Math.abs(ts.pitch - 1f) > 0.005f) {
                                samples = applyPitchToSamples(samples, ts.pitch, localAudioChannels);
                            }

                            // 3. Spectral Noise Injection
                            if (ts.spectralNoiseEnabled && ts.spectralNoise > 0.0001f) {
                                samples = applySpectralNoise(samples, ts.spectralNoise, sampleRate);
                            }

                            // 4. Ambient Background Noise
                            if (ts.ambientNoiseEnabled && ts.ambientNoiseLevel > 0.0001f) {
                                samples = applyAmbientNoise(samples, ts.ambientNoiseLevel);
                            }

                            // ═══════════════════════════════════════════════════

                            long pts = decInfo.presentationTimeUs;
                            if (firstPts < 0) firstPts = pts;
                            long outputPts = (long) ((pts - firstPts) / speedFactor);

                            if (maxDurationUs > 0 && outputPts >= maxDurationUs) {
                                audioDecoder.releaseOutputBuffer(outIdx, false);
                                decodeDone = true;
                                eosSignalPending = true;
                                continue;
                            }

                            if (lastOutputPts >= 0 && outputPts <= lastOutputPts)
                                outputPts = lastOutputPts + 1;

                            int encInIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                            if (encInIdx >= 0) {
                                writeSamplesToEncoder(audioEncoder, encInIdx, samples, outputPts);
                                lastOutputPts = outputPts;
                                processedSamples++;
                            } else {
                                pendingSamples = samples;
                                pendingPts = outputPts;
                            }
                        }

                        audioDecoder.releaseOutputBuffer(outIdx, false);

                        if (isEos) {
                            decodeDone = true;
                            if (pendingSamples == null) {
                                int eosIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                                if (eosIdx >= 0) {
                                    audioEncoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                } else {
                                    eosSignalPending = true;
                                }
                            } else {
                                eosSignalPending = true;
                            }
                        }

                        if (System.currentTimeMillis() - lastAudioProgress > 250) {
                            int pct = AP_START + (int) ((AP_END - AP_START) * (double) processedSamples / estimatedSamples);
                            callback.onProgress(Math.min(AP_END, Math.max(AP_START, pct)), "অডিও: " + processedSamples);
                            lastAudioProgress = System.currentTimeMillis();
                        }
                    }
                }

                // EOS retry
                if (decodeDone && pendingSamples == null && eosSignalPending) {
                    int eosIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                    if (eosIdx >= 0) {
                        audioEncoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosSignalPending = false;
                    }
                }

                // ENCODER OUTPUT
                while (true) {
                    int encOutIdx = audioEncoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                    if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputAudioFormat = audioEncoder.getOutputFormat();
                    } else if (encOutIdx >= 0) {
                        ByteBuffer encOutBuf = audioEncoder.getOutputBuffer(encOutIdx);
                        if (encInfo.size > 0 && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && encOutBuf != null) {
                            byte[] chunk = new byte[encInfo.size];
                            encOutBuf.position(encInfo.offset);
                            encOutBuf.get(chunk);
                            encodedChunks.add(chunk);
                            MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
                            ci.set(0, chunk.length, encInfo.presentationTimeUs, encInfo.flags);
                            chunkInfos.add(ci);
                        }
                        audioEncoder.releaseOutputBuffer(encOutIdx, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encodeDone = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }

            Log.d(TAG, "Audio done: samples=" + processedSamples + " chunks=" + encodedChunks.size());

            if (!isCancelled && outputAudioFormat != null && !encodedChunks.isEmpty()) {
                callback.onProgress(AP_END, "মিক্স হচ্ছে...");
                muxVideoAndAudioInMemory(encodedVideoFormat, videoChunks, videoChunkInfos,
                        outputAudioFormat, encodedChunks, chunkInfos, finalOutputPath);

                File orig = new File(videoOutputPath);
                File final_ = new File(finalOutputPath);
                if (final_.exists() && final_.length() > orig.length() / 2) {
                    orig.delete();
                    final_.renameTo(orig);
                } else {
                    final_.delete();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Audio processing error", e);
        } finally {
            try {
                if (audioDecoder != null) {
                    audioDecoder.stop();
                    audioDecoder.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (audioEncoder != null) {
                    audioEncoder.stop();
                    audioEncoder.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (audioExtractor != null) audioExtractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * PCM samples → encoder input buffer
     */
    private void writeSamplesToEncoder(MediaCodec encoder, int bufIdx, short[] samples, long pts) {
        ByteBuffer buf = encoder.getInputBuffer(bufIdx);
        if (buf == null) {
            encoder.queueInputBuffer(bufIdx, 0, 0, pts, 0);
            return;
        }
        buf.clear();
        ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        pcm.asShortBuffer().put(samples);
        int writeLen = Math.min(pcm.array().length, buf.capacity());
        buf.put(pcm.array(), 0, writeLen);
        encoder.queueInputBuffer(bufIdx, 0, writeLen, pts, 0);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // AUDIO ENHANCEMENT METHODS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Apply spectral noise to mask audio fingerprint
     * High-frequency noise that confuses spectral analysis algorithms
     */
    private short[] applySpectralNoise(short[] samples, float noiseLevel, int sampleRate) {
        short[] output = new short[samples.length];

        // High-pass filter coefficient (fc ~ 6000 Hz)
        float alpha = (float) (2.0 * Math.PI * 6000.0 / sampleRate);
        alpha = alpha / (alpha + 1.0f);

        float prevInput = 0;
        float prevOutput = 0;

        for (int i = 0; i < samples.length; i++) {
            // Generate white noise
            float noise = (random.nextFloat() - 0.5f) * 2.0f * noiseLevel * Short.MAX_VALUE;

            // High-pass filter the noise (only high frequencies)
            float filteredNoise = alpha * (prevOutput + noise - prevInput);
            prevInput = noise;
            prevOutput = filteredNoise;

            // Dynamic: add more noise during transients (rapid changes)
            float signalEnergy = Math.abs(samples[i]) / (float) Short.MAX_VALUE;
            float dynamicNoise = filteredNoise * (0.3f + signalEnergy * 0.7f);

            float result = samples[i] + dynamicNoise;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) result));
        }

        return output;
    }

    /**
     * Apply ambient background noise (room tone simulation)
     * Uses Brown noise (1/f² spectrum) for natural sound
     */
    private short[] applyAmbientNoise(short[] samples, float noiseLevel) {
        short[] output = new short[samples.length];

        float brownNoise = 0;

        for (int i = 0; i < samples.length; i++) {
            // Generate brown noise (cumulative filtered white noise)
            float white = (random.nextFloat() - 0.5f) * noiseLevel * Short.MAX_VALUE;
            brownNoise = (brownNoise + white * 0.02f) * 0.98f; // Low-pass + decay

            // Clamp brown noise
            brownNoise = Math.max(-0.1f * Short.MAX_VALUE, Math.min(0.1f * Short.MAX_VALUE, brownNoise));

            // Apply with adaptive strength based on signal
            float signalStrength = Math.abs(samples[i]) / (float) Short.MAX_VALUE;
            float adaptiveNoise = brownNoise * (0.3f + signalStrength * 0.7f);

            float result = samples[i] + adaptiveNoise;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) result));
        }

        return output;
    }

    /**
     * Pitch shift using linear interpolation resampling
     */
    private short[] applyPitchToSamples(short[] input, float pitchFactor, int channels) {
        if (Math.abs(pitchFactor - 1f) < 0.005f) return input;

        int inFrames = input.length / channels;
        int outFrames = inFrames;
        short[] output = new short[outFrames * channels];

        float step = pitchFactor;
        float pos = 0f;

        for (int outF = 0; outF < outFrames; outF++) {
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, inFrames - 1);
            float frac = pos - i0;

            for (int ch = 0; ch < channels; ch++) {
                short s0 = input[i0 * channels + ch];
                short s1 = input[i1 * channels + ch];
                output[outF * channels + ch] = (short) (s0 + frac * (s1 - s0));
            }

            pos += step;
            if (pos >= inFrames - 1) break;
        }

        return output;
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MUX HELPERS
    // ════════════════════════════════════════════════════════════════════════════

    private void muxVideoOnly(MediaFormat videoFormat,
                              ArrayList<byte[]> chunks, ArrayList<MediaCodec.BufferInfo> infos,
                              String outputPath) {
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(videoFormat);
            muxer.start();
            for (int i = 0; i < chunks.size(); i++)
                muxer.writeSampleData(track, ByteBuffer.wrap(chunks.get(i)), infos.get(i));
        } catch (Exception e) {
            Log.e(TAG, "muxVideoOnly error", e);
        } finally {
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * PTS-interleaved in-memory mux — ensures perfect A/V sync
     */
    private void muxVideoAndAudioInMemory(
            MediaFormat videoFormat, ArrayList<byte[]> vChunks, ArrayList<MediaCodec.BufferInfo> vInfos,
            MediaFormat audioFormat, ArrayList<byte[]> aChunks, ArrayList<MediaCodec.BufferInfo> aInfos,
            String outputPath) {
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = muxer.addTrack(videoFormat);
            int aTrack = muxer.addTrack(audioFormat);
            muxer.start();

            int vi = 0, ai = 0;
            while (vi < vChunks.size() || ai < aChunks.size()) {
                boolean writeVideo;
                if (vi >= vChunks.size()) writeVideo = false;
                else if (ai >= aChunks.size()) writeVideo = true;
                else
                    writeVideo = vInfos.get(vi).presentationTimeUs <= aInfos.get(ai).presentationTimeUs;

                if (writeVideo) {
                    muxer.writeSampleData(vTrack, ByteBuffer.wrap(vChunks.get(vi)), vInfos.get(vi++));
                } else {
                    muxer.writeSampleData(aTrack, ByteBuffer.wrap(aChunks.get(ai)), aInfos.get(ai++));
                }
            }
            Log.d(TAG, "Mux done: v=" + vi + " a=" + ai);
        } catch (Exception e) {
            Log.e(TAG, "muxInMemory error", e);
        } finally {
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // WATERMARK HELPERS (with Dynamic Position Support and proper text placement)
    // ════════════════════════════════════════════════════════════════════════════

    private Bitmap createWatermarkBitmap(WatermarkConfig wm, int w, int h, long currentTimeUs) {
        if (wm == null || !wm.isActive()) return null;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int pad = 20;

        Bitmap logo = wm.logoBitmap != null ? wm.logoBitmap : WatermarkConfig.sharedLogoBitmap;

        // Get dynamic position based on time
        WatermarkConfig.Position currentLogoPos = wm.getDynamicPosition(currentTimeUs);
        WatermarkConfig.Position currentTextPos = wm.dynamicPosition
                ? wm.getDynamicPosition(currentTimeUs + 500_000) // Offset by 0.5 sec for text
                : wm.textPosition;

        if ((wm.mode == WatermarkConfig.Mode.LOGO || wm.mode == WatermarkConfig.Mode.BOTH)
                && logo != null && !logo.isRecycled()) {
            int lW = w * wm.logoSize / 100;
            int lH = Math.round((float) lW * logo.getHeight() / logo.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(logo, lW, lH, true);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setAlpha(Math.round(wm.logoOpacity / 100f * 255));
            canvas.drawBitmap(scaled,
                    calcX(currentLogoPos, lW, w, pad),
                    calcY(currentLogoPos, lH, h, pad), p);
            if (scaled != logo) scaled.recycle();
        }

        if ((wm.mode == WatermarkConfig.Mode.TEXT || wm.mode == WatermarkConfig.Mode.BOTH)
                && wm.text != null && !wm.text.trim().isEmpty()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(wm.fontSize);
            p.setTypeface(wm.textStyle == WatermarkConfig.TextStyle.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float tw = p.measureText(wm.text);
            int extraY = (wm.mode == WatermarkConfig.Mode.BOTH && logo != null
                    && currentLogoPos == currentTextPos) ? h * wm.logoSize / 100 + 10 : 0;
            float x = calcX(currentTextPos, (int) tw, w, pad);
            float y = calcTextY(currentTextPos, wm.fontSize, h, pad, extraY);

            // Shadow
            p.setColor(Color.BLACK);
            p.setAlpha(Math.round(wm.textOpacity / 100f * 128));
            canvas.drawText(wm.text, x + 2, y + 2, p);

            // Outline
            if (wm.textStyle == WatermarkConfig.TextStyle.OUTLINE) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f);
                p.setColor(Color.BLACK);
                p.setAlpha(Math.round(wm.textOpacity / 100f * 255));
                canvas.drawText(wm.text, x, y, p);
                p.setStyle(Paint.Style.FILL);
            }

            // Text
            p.setColor(wm.textColor);
            p.setAlpha(Math.round(wm.textOpacity / 100f * 255));
            canvas.drawText(wm.text, x, y, p);
        }

        return bmp;
    }

    // ═══════════════════════════════════════════════════════════════
    // ★ FIXED POSITION CALCULATION METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate X position in Canvas coordinates
     * (Same logic as before, X is not affected by OpenGL flip)
     */
    private int calcXFixed(WatermarkConfig.Position pos, int elementWidth, int canvasWidth, int pad) {
        switch (pos) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                return canvasWidth - elementWidth - pad;
            case CENTER:
                return (canvasWidth - elementWidth) / 2;
            case TOP_LEFT:
            case BOTTOM_LEFT:
            default:
                return pad;
        }
    }

    /**
     * Calculate Y position in Canvas coordinates
     * After bitmap flip, these become correct OpenGL positions
     */
    private int calcYFixed(WatermarkConfig.Position pos, int elementHeight, int canvasHeight, int pad) {
        switch (pos) {
            case TOP_LEFT:
            case TOP_RIGHT:
                return pad;  // Canvas top (will become OpenGL top after flip)
            case BOTTOM_LEFT:
            case BOTTOM_RIGHT:
                return canvasHeight - elementHeight - pad;  // Canvas bottom
            case CENTER:
            default:
                return (canvasHeight - elementHeight) / 2;
        }
    }

    /**
     * Calculate text Y position (baseline) in Canvas coordinates
     * Text Y is the baseline, not the top of the text
     */
    private float calcTextYFixed(WatermarkConfig.Position pos, int textHeight, int canvasHeight, int pad, int extraY) {
        switch (pos) {
            case TOP_LEFT:
            case TOP_RIGHT:
                return pad + textHeight + extraY;  // Below padding + extraY
            case BOTTOM_LEFT:
            case BOTTOM_RIGHT:
                return canvasHeight - pad + extraY;  // Above bottom padding
            case CENTER:
            default:
                return (canvasHeight + textHeight) / 2f + extraY;
        }
    }
    private float calcX(WatermarkConfig.Position pos, int w, int cW, int pad) {
        switch (pos) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                return cW - w - pad;
            case CENTER:
                return (cW - w) / 2f;
            default:
                return pad;
        }
    }

    private float calcY(WatermarkConfig.Position pos, int h, int cH, int pad) {
        switch (pos) {
            case BOTTOM_LEFT:
            case BOTTOM_RIGHT:
                return cH - h - pad;
            case CENTER:
                return (cH - h) / 2f;
            default:
                return pad;
        }
    }

    private float calcTextY(WatermarkConfig.Position pos, int fs, int cH, int pad, int extraY) {
        switch (pos) {
            case BOTTOM_LEFT:
            case BOTTOM_RIGHT:
                return cH - pad + extraY;
            case CENTER:
                return cH / 2f + fs / 2f + extraY;
            default:
                return pad + fs + extraY;
        }
    }

    private int createBitmapTexture(Bitmap bitmap) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        return t[0];
    }

    private String createOutputPath() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(context.getExternalFilesDir(null), "CopyrightFree");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "CF_" + ts + ".mp4").getAbsolutePath();
    }
}