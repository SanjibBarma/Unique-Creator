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
            if (mmr != null) try { mmr.release(); } catch (Exception ignored) {}
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
            } catch (Exception ignored) {}
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

        float inputRatio = (float) inputWidth / inputHeight;
        float targetRatio = inputRatio;

        if (!"original".equals(aspectRatio)) {
            switch (aspectRatio) {
                case "16:9": targetRatio = 16f / 9f; break;
                case "9:16": targetRatio = 9f / 16f; break;
                case "1:1": targetRatio = 1f; break;
                case "4:3": targetRatio = 4f / 3f; break;
                case "4:5": targetRatio = 4f / 5f; break;
                case "3:4": targetRatio = 3f / 4f; break;
                default: targetRatio = inputRatio; break;
            }
            Log.d(TAG, "Target ratio: " + aspectRatio + " = " + targetRatio);
        }

        int croppedWidth, croppedHeight;
        if (Math.abs(inputRatio - targetRatio) < 0.01f) {
            croppedWidth = inputWidth;
            croppedHeight = inputHeight;
        } else if (inputRatio > targetRatio) {
            croppedHeight = inputHeight;
            croppedWidth = Math.round(inputHeight * targetRatio);
        } else {
            croppedWidth = inputWidth;
            croppedHeight = Math.round(inputWidth / targetRatio);
        }

        croppedWidth = Math.min(croppedWidth, inputWidth);
        croppedHeight = Math.min(croppedHeight, inputHeight);

        outputWidth = croppedWidth;
        outputHeight = croppedHeight;

        if (!"original".equals(resolution)) {
            try {
                int targetHeight = Integer.parseInt(resolution);
                if (outputHeight > targetHeight) {
                    float scale = (float) targetHeight / outputHeight;
                    outputWidth = Math.round(outputWidth * scale);
                    outputHeight = targetHeight;
                }
            } catch (Exception e) {
                Log.w(TAG, "Invalid resolution value: " + resolution);
            }
        }

        outputWidth = Math.max(128, (outputWidth / 2) * 2);
        outputHeight = Math.max(128, (outputHeight / 2) * 2);

        final int FINAL_WIDTH = outputWidth;
        final int FINAL_HEIGHT = outputHeight;

        float cropOffsetX = 0f, cropOffsetY = 0f, cropScaleX = 1f, cropScaleY = 1f;
        if (croppedWidth != inputWidth || croppedHeight != inputHeight) {
            cropScaleX = (float) croppedWidth / inputWidth;
            cropScaleY = (float) croppedHeight / inputHeight;
            cropOffsetX = (1f - cropScaleX) / 2f;
            cropOffsetY = (1f - cropScaleY) / 2f;
        }

        final float CROP_OFFSET_X = cropOffsetX;
        final float CROP_OFFSET_Y = cropOffsetY;
        final float CROP_SCALE_X = cropScaleX;
        final float CROP_SCALE_Y = cropScaleY;

        Log.d(TAG, "FINAL Output: " + FINAL_WIDTH + "x" + FINAL_HEIGHT);

        long trimStartUs = (ts.trimEnabled && ts.trim > 0) ? (long) (ts.trim * 1_000_000L) : 0L;
        float speedFactor = (ts.speedEnabled && ts.speed > 0.1f) ? ts.speed : 1.0f;
        float volumeFactor = ts.volumeEnabled ? Math.max(0f, Math.min(3f, ts.volume)) : 1.0f;

        long effectiveDurationUs = (long) ((durationUs - trimStartUs) / speedFactor);
        float effectiveDurationSec = effectiveDurationUs / 1_000_000.0f;

        String outputPath = createOutputPath();

        // ════════════════════════════════════════════════════════════════════
        // STEP 3: Video Encoder
        // ════════════════════════════════════════════════════════════════════
        int baseBitrate = Math.max(4_000_000, FINAL_WIDTH * FINAL_HEIGHT * 5);
        int bitrate = baseBitrate;
        if (ts.bitrateRandomEnabled && ts.bitrateVariation > 0) {
            float variation = 1.0f - ts.bitrateVariation + (random.nextFloat() * ts.bitrateVariation * 2);
            bitrate = (int) (baseBitrate * variation);
        }

        MediaFormat encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, FINAL_WIDTH, FINAL_HEIGHT);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        try {
            encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        } catch (Exception ignored) {}

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
                0x3142, 1, EGL14.EGL_NONE
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
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("EGLContext তৈরি ব্যর্থ");

        int[] surfAttribs = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("EGLSurface তৈরি ব্যর্থ");

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

        // ════════════════════════════════════════════════════════════════════
        // STEP 6: REACTION FACE VIDEO SETUP (NORMAL SPEED)
        // ════════════════════════════════════════════════════════════════════
        boolean hasFaceVideo = ts.hasReactionFace();
        int faceTextureId = -1;
        SurfaceTexture faceSurfaceTexture = null;
        Surface faceDecoderSurface = null;
        MediaCodec faceDecoder = null;
        MediaExtractor faceExtractor = null;
        MediaFormat faceVideoFormat = null;
        MediaFormat faceAudioFormat = null;
        int faceVideoTrackIndex = -1;
        int faceAudioTrackIndex = -1;

        final Object faceSyncObject = new Object();
        final boolean[] faceFrameAvailable = {false};
        boolean faceInputDone = false;
        boolean faceOutputDone = false;

        int faceRawWidth = 0, faceRawHeight = 0;
        Uri faceUri = null;

        if (hasFaceVideo) {
            try {
                callback.onProgress(8, "Reaction Face লোড হচ্ছে...");

                faceUri = Uri.parse(ts.reactionFaceUri);
                faceExtractor = new MediaExtractor();
                faceExtractor.setDataSource(context, faceUri, null);

                for (int i = 0; i < faceExtractor.getTrackCount(); i++) {
                    MediaFormat format = faceExtractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null) {
                        if (mime.startsWith("video/") && faceVideoTrackIndex < 0) {
                            faceVideoTrackIndex = i;
                            faceVideoFormat = format;
                        } else if (mime.startsWith("audio/") && faceAudioTrackIndex < 0) {
                            faceAudioTrackIndex = i;
                            faceAudioFormat = format;
                        }
                    }
                }

                if (faceVideoTrackIndex >= 0 && faceVideoFormat != null) {
                    faceRawWidth = faceVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
                    faceRawHeight = faceVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    String faceMime = faceVideoFormat.getString(MediaFormat.KEY_MIME);

                    int[] faceTexArr = new int[1];
                    GLES20.glGenTextures(1, faceTexArr, 0);
                    faceTextureId = faceTexArr[0];
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, faceTextureId);
                    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                    faceSurfaceTexture = new SurfaceTexture(faceTextureId);
                    faceSurfaceTexture.setDefaultBufferSize(faceRawWidth, faceRawHeight);
                    faceSurfaceTexture.setOnFrameAvailableListener(st -> {
                        synchronized (faceSyncObject) {
                            faceFrameAvailable[0] = true;
                            faceSyncObject.notifyAll();
                        }
                    });

                    faceDecoderSurface = new Surface(faceSurfaceTexture);
                    faceDecoder = MediaCodec.createDecoderByType(faceMime);
                    faceDecoder.configure(faceVideoFormat, faceDecoderSurface, null, 0);
                    faceDecoder.start();

                    faceExtractor.selectTrack(faceVideoTrackIndex);
                    if (trimStartUs > 0) {
                        faceExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    }

                    Log.d(TAG, "Face video setup: " + faceRawWidth + "x" + faceRawHeight);
                    if (faceAudioTrackIndex >= 0) {
                        Log.d(TAG, "Face audio track found: " + faceAudioTrackIndex);
                    } else {
                        Log.d(TAG, "Face video has NO audio track");
                    }
                } else {
                    hasFaceVideo = false;
                    Log.w(TAG, "Face video track not found");
                }

            } catch (Exception e) {
                Log.e(TAG, "Face video setup error", e);
                hasFaceVideo = false;
            }
        }

        final boolean useFaceVideo = hasFaceVideo;
        final int FACE_TEX_ID = faceTextureId;
        final MediaCodec FACE_DECODER = faceDecoder;
        final MediaExtractor FACE_EXTRACTOR = faceExtractor;
        final SurfaceTexture FACE_SURFACE_TEXTURE = faceSurfaceTexture;
        final Uri FACE_URI = faceUri;
        final int FACE_AUDIO_TRACK_INDEX = faceAudioTrackIndex;
        final MediaFormat FACE_AUDIO_FORMAT = faceAudioFormat;

        // ★★★ FIX: Properly check if face has audio ★★★
        final boolean FACE_HAS_AUDIO = (faceAudioTrackIndex >= 0 && faceAudioFormat != null);

        // ★★★ FIXED: Face position calculation using PIXEL coordinates ★★★
        final float faceSize = ts.reactionFaceSize / 100f;
        final float faceCornerRadius = ts.reactionFaceCornerRadius / 100f;

        // Calculate pixel-based position
        int facePadding = 20;
        int faceSizePixels = (int) (FINAL_WIDTH * faceSize);

        // ★ Position in SCREEN coordinates (Y=0 at TOP, like UI)
        int facePixelX = 0, facePixelY = 0;
        switch (ts.reactionFacePosition) {
            case TransformSettings.FACE_POS_TOP_LEFT:
                facePixelX = facePadding;
                facePixelY = facePadding;
                break;
            case TransformSettings.FACE_POS_TOP_RIGHT:
                facePixelX = FINAL_WIDTH - faceSizePixels - facePadding;
                facePixelY = facePadding;
                break;
            case TransformSettings.FACE_POS_BOTTOM_LEFT:
                facePixelX = facePadding;
                facePixelY = FINAL_HEIGHT - faceSizePixels - facePadding;
                break;
            case TransformSettings.FACE_POS_BOTTOM_RIGHT:
            default:
                facePixelX = FINAL_WIDTH - faceSizePixels - facePadding;
                facePixelY = FINAL_HEIGHT - faceSizePixels - facePadding;
                break;
        }

        // Normalized for shader (0-1 range, screen space with Y=0 at top)
        final float FACE_RECT_X = (float) facePixelX / FINAL_WIDTH;
        final float FACE_RECT_Y = (float) facePixelY / FINAL_HEIGHT;
        final float FACE_RECT_W = faceSize;
        final float FACE_RECT_H = (float) faceSizePixels / FINAL_HEIGHT;
        final float FACE_CORNER_RADIUS = faceCornerRadius;

        Log.d(TAG, "★ Face rect: x=" + FACE_RECT_X + " y=" + FACE_RECT_Y + " w=" + FACE_RECT_W + " h=" + FACE_RECT_H);

        // Create shader
        int program = createShaderProgram(ts, FINAL_WIDTH, FINAL_HEIGHT,
                CROP_OFFSET_X, CROP_OFFSET_Y, CROP_SCALE_X, CROP_SCALE_Y,
                useFaceVideo, FACE_RECT_X, FACE_RECT_Y, FACE_RECT_W, FACE_RECT_H, FACE_CORNER_RADIUS);
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

        int faceTextureHandle = GLES20.glGetUniformLocation(program, "sFaceTexture");
        int hasFaceVideoHandle = GLES20.glGetUniformLocation(program, "uHasFaceVideo");
        int faceRectHandle = GLES20.glGetUniformLocation(program, "uFaceRect");
        int faceCornerRadiusHandle = GLES20.glGetUniformLocation(program, "uFaceCornerRadius");

        // Logo removal
        Rect removalRect = ts.getRemovalRect();
        if (ts.isLogoRemovalEnabled() && removalRect != null) {
            GLES20.glUniform1i(logoRemovalHandle, 1);
            GLES20.glUniform4f(logoRectHandle,
                    (float) removalRect.left / FINAL_WIDTH,
                    (float) removalRect.top / FINAL_HEIGHT,
                    (float) removalRect.right / FINAL_WIDTH,
                    (float) removalRect.bottom / FINAL_HEIGHT);
            GLES20.glUniform1i(logoMethodHandle, ts.logoRemovalMethod);
        } else {
            GLES20.glUniform1i(logoRemovalHandle, 0);
        }

        GLES20.glUniform1i(flipEnabledHandle, ts.flipEnabled ? 1 : 0);
        GLES20.glUniform1f(totalDurationHandle, effectiveDurationSec);

        // Watermark
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

        float[] faceStMatrix = new float[16];
        Matrix.setIdentityM(faceStMatrix, 0);

        // ════════════════════════════════════════════════════════════════════
        // STEP 7: SurfaceTexture + Decoder
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
        // STEP 8: In-memory video chunk collection
        // ════════════════════════════════════════════════════════════════════
        ArrayList<byte[]> videoChunks = new ArrayList<>();
        ArrayList<MediaCodec.BufferInfo> videoChunkInfos = new ArrayList<>();
        MediaFormat encodedVideoFormat = null;

        extractor.selectTrack(videoTrackIndex);
        if (trimStartUs > 0) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        // ════════════════════════════════════════════════════════════════════
        // STEP 9: Main encode loop
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

        int totalEstimatedFrames = Math.max(1, (int) (effectiveDurationUs * frameRate / 1_000_000.0));
        long frameIntervalUs = 1_000_000L / frameRate;
        long lastPts = -1L;
        long videoFirstPts = -1L;
        long lastProgressUpdate = System.currentTimeMillis();

        boolean pendingDuplicate = false;
        long duplicatePts = 0;
        float[] duplicateStMatrix = new float[16];

        while (!encoderDone && !isCancelled) {

            // Main decoder input
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

            // ★★★ Face decoder input - NORMAL SPEED (NO speed adjustment) ★★★
            if (useFaceVideo && !faceInputDone && FACE_DECODER != null && FACE_EXTRACTOR != null) {
                int faceInIdx = FACE_DECODER.dequeueInputBuffer(0);
                if (faceInIdx >= 0) {
                    ByteBuffer faceInBuf = FACE_DECODER.getInputBuffer(faceInIdx);
                    if (faceInBuf != null) {
                        faceInBuf.clear();
                        int sz = FACE_EXTRACTOR.readSampleData(faceInBuf, 0);
                        if (sz < 0) {
                            FACE_DECODER.queueInputBuffer(faceInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            faceInputDone = true;
                        } else {
                            // ★★★ Use actual timestamp (normal speed) ★★★
                            long faceRawPts = FACE_EXTRACTOR.getSampleTime();
                            FACE_DECODER.queueInputBuffer(faceInIdx, 0, sz, faceRawPts, 0);
                            FACE_EXTRACTOR.advance();
                        }
                    }
                }
            }

            // Face decoder output
            if (useFaceVideo && !faceOutputDone && FACE_DECODER != null) {
                MediaCodec.BufferInfo faceDecInfo = new MediaCodec.BufferInfo();
                int faceOutIdx = FACE_DECODER.dequeueOutputBuffer(faceDecInfo, 0);
                if (faceOutIdx >= 0) {
                    boolean doRender = (faceDecInfo.size != 0);
                    FACE_DECODER.releaseOutputBuffer(faceOutIdx, doRender);

                    if (doRender && FACE_SURFACE_TEXTURE != null) {
                        boolean gotFace = awaitNewFrame(faceSyncObject, faceFrameAvailable);
                        if (gotFace) {
                            FACE_SURFACE_TEXTURE.updateTexImage();
                            FACE_SURFACE_TEXTURE.getTransformMatrix(faceStMatrix);
                        }
                    }

                    if ((faceDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        faceOutputDone = true;
                        Log.d(TAG, "Face video ended");
                    }
                }
            }

            // Handle pending duplication
            if (pendingDuplicate && !outputDone) {
                renderFrame(eglDisplay, eglSurface, eglContext, program, textureId, watermarkTexIdHolder[0],
                        FACE_TEX_ID, FINAL_WIDTH, FINAL_HEIGHT, mvpMatrix, duplicateStMatrix, faceStMatrix,
                        vertexBuffer, positionHandle, textureCoordHandle, mvpMatrixHandle, stMatrixHandle,
                        textureHandle, watermarkHandle, hasWatermarkHandle, faceTextureHandle,
                        hasFaceVideoHandle, faceRectHandle, faceCornerRadiusHandle, currentTimeHandle,
                        duplicatePts, useWatermark, useFaceVideo && !faceOutputDone,
                        FACE_RECT_X, FACE_RECT_Y, FACE_RECT_W, FACE_RECT_H, FACE_CORNER_RADIUS);

                lastPts = duplicatePts;
                frameCount++;
                duplicatedFrames++;
                pendingDuplicate = false;
            }

            // Main decoder output
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

                            long rawPts = decoderInfo.presentationTimeUs;
                            if (videoFirstPts < 0) videoFirstPts = rawPts;
                            long outputPts = (long) ((rawPts - videoFirstPts) / speedFactor);
                            if (lastPts >= 0 && outputPts <= lastPts)
                                outputPts = lastPts + frameIntervalUs;

                            boolean shouldRenderFrame = true;
                            boolean shouldDuplicate = false;

                            if (ts.temporalJitterEnabled && ts.jitterIntensity > 0 && frameCount > 10) {
                                float rand = random.nextFloat();
                                if (rand < ts.jitterIntensity) {
                                    if (random.nextBoolean()) {
                                        shouldRenderFrame = false;
                                        skippedFrames++;
                                    } else {
                                        shouldDuplicate = true;
                                    }
                                }
                            }

                            if (shouldRenderFrame) {
                                if (isDynamicWatermark && wm != null && wm.isActive()) {
                                    long intervalUs = wm.positionChangeIntervalSec * 1_000_000L;
                                    if ((outputPts - lastWatermarkUpdateUs) >= intervalUs) {
                                        Bitmap newWmBitmap = createWatermarkBitmap(wm, FINAL_WIDTH, FINAL_HEIGHT, outputPts);
                                        if (newWmBitmap != null) {
                                            GLES20.glDeleteTextures(1, watermarkTexIdHolder, 0);
                                            watermarkTexIdHolder[0] = createBitmapTexture(newWmBitmap);
                                            newWmBitmap.recycle();
                                            lastWatermarkUpdateUs = outputPts;
                                        }
                                    }
                                }

                                renderFrame(eglDisplay, eglSurface, eglContext, program, textureId, watermarkTexIdHolder[0],
                                        FACE_TEX_ID, FINAL_WIDTH, FINAL_HEIGHT, mvpMatrix, stMatrix, faceStMatrix,
                                        vertexBuffer, positionHandle, textureCoordHandle, mvpMatrixHandle, stMatrixHandle,
                                        textureHandle, watermarkHandle, hasWatermarkHandle, faceTextureHandle,
                                        hasFaceVideoHandle, faceRectHandle, faceCornerRadiusHandle, currentTimeHandle,
                                        outputPts, useWatermark, useFaceVideo && !faceOutputDone,
                                        FACE_RECT_X, FACE_RECT_Y, FACE_RECT_W, FACE_RECT_H, FACE_CORNER_RADIUS);

                                lastPts = outputPts;
                                frameCount++;

                                if (shouldDuplicate) {
                                    pendingDuplicate = true;
                                    duplicatePts = outputPts + (frameIntervalUs / 2);
                                    System.arraycopy(stMatrix, 0, duplicateStMatrix, 0, 16);
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

            // Encoder output
            while (true) {
                int encIdx = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US);
                if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encodedVideoFormat == null) {
                        encodedVideoFormat = encoder.getOutputFormat();
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

        Log.d(TAG, "Video loop done. Frames=" + frameCount + " chunks=" + videoChunks.size());

        // Cleanup video resources
        try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
        try { encoder.stop(); encoder.release(); } catch (Exception ignored) {}
        try { extractor.release(); } catch (Exception ignored) {}
        try { decoderSurface.release(); } catch (Exception ignored) {}
        try { outputSurfaceTexture.release(); } catch (Exception ignored) {}

        // Cleanup face video resources
        if (useFaceVideo) {
            try { if (FACE_DECODER != null) { FACE_DECODER.stop(); FACE_DECODER.release(); } } catch (Exception ignored) {}
            try { if (FACE_EXTRACTOR != null) FACE_EXTRACTOR.release(); } catch (Exception ignored) {}
            try { if (faceDecoderSurface != null) faceDecoderSurface.release(); } catch (Exception ignored) {}
            try { if (FACE_SURFACE_TEXTURE != null) FACE_SURFACE_TEXTURE.release(); } catch (Exception ignored) {}
            if (FACE_TEX_ID != -1) GLES20.glDeleteTextures(1, new int[]{FACE_TEX_ID}, 0);
        }

        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        GLES20.glDeleteTextures(1, watermarkTexIdHolder, 0);
        GLES20.glDeleteProgram(program);

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
        try { encoderSurface.release(); } catch (Exception ignored) {}

        if (isCancelled) {
            Log.d(TAG, "Cancelled");
            return;
        }

        if (videoChunks.isEmpty() || encodedVideoFormat == null) {
            callback.onError("ভিডিও এনকোড ব্যর্থ (কোনো ফ্রেম নেই)");
            return;
        }

        callback.onProgress(75, "ভিডিও সম্পন্ন...");

        // ════════════════════════════════════════════════════════════════════
        // AUDIO PROCESSING WITH FACE AUDIO MIXING
        // ════════════════════════════════════════════════════════════════════
        if (audioTrackIndex >= 0 && audioFormat != null) {
            processAudioWithFaceMix(context, inputUri, outputPath, audioTrackIndex, audioFormat,
                    trimStartUs, speedFactor, volumeFactor, effectiveDurationUs, callback, ts,
                    videoChunks, videoChunkInfos, encodedVideoFormat,
                    FACE_HAS_AUDIO, FACE_URI, FACE_AUDIO_TRACK_INDEX, FACE_AUDIO_FORMAT);
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
    // RENDER FRAME HELPER
    // ════════════════════════════════════════════════════════════════════════════
    private void renderFrame(EGLDisplay eglDisplay, EGLSurface eglSurface, EGLContext eglContext,
                             int program, int textureId, int watermarkTexId, int faceTexId,
                             int width, int height, float[] mvpMatrix, float[] stMatrix, float[] faceStMatrix,
                             FloatBuffer vertexBuffer, int positionHandle, int textureCoordHandle,
                             int mvpMatrixHandle, int stMatrixHandle, int textureHandle,
                             int watermarkHandle, int hasWatermarkHandle, int faceTextureHandle,
                             int hasFaceVideoHandle, int faceRectHandle, int faceCornerRadiusHandle,
                             int currentTimeHandle, long currentPts, boolean useWatermark, boolean useFaceVideo,
                             float faceRectX, float faceRectY, float faceRectW, float faceRectH, float faceCornerRadius) {

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        float currentTimeSec = currentPts / 1_000_000.0f;
        GLES20.glUniform1f(currentTimeHandle, currentTimeSec);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexId);
        GLES20.glUniform1i(watermarkHandle, 1);
        GLES20.glUniform1i(hasWatermarkHandle, useWatermark ? 1 : 0);

        if (useFaceVideo && faceTexId != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, faceTexId);
            GLES20.glUniform1i(faceTextureHandle, 2);
            GLES20.glUniform1i(hasFaceVideoHandle, 1);
            GLES20.glUniform4f(faceRectHandle, faceRectX, faceRectY, faceRectW, faceRectH);
            GLES20.glUniform1f(faceCornerRadiusHandle, faceCornerRadius * 0.5f);
        } else {
            GLES20.glUniform1i(hasFaceVideoHandle, 0);
        }

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

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, currentPts * 1000L);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
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
    // SHADER PROGRAM (with FIXED Face Position - NO MIRROR)
    // ════════════════════════════════════════════════════════════════════════════
    private int createShaderProgram(TransformSettings ts, int outW, int outH,
                                    float cropOffsetX, float cropOffsetY,
                                    float cropScaleX, float cropScaleY,
                                    boolean hasFaceOverlay,
                                    float faceRectX, float faceRectY,
                                    float faceRectW, float faceRectH,
                                    float faceCornerRadius) {
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
        fs.append("uniform float uTotalDuration;\n");
        fs.append("uniform float uCurrentTime;\n\n");

        // Face video uniforms
        fs.append("uniform samplerExternalOES sFaceTexture;\n");
        fs.append("uniform int uHasFaceVideo;\n");
        fs.append("uniform vec4 uFaceRect;\n");
        fs.append("uniform float uFaceCornerRadius;\n\n");

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

        // Aspect ratio crop
        boolean needsCrop = (cropScaleX < 0.999f || cropScaleY < 0.999f);
        if (needsCrop) {
            fs.append("    uv = uv * vec2(").append(String.format(Locale.US, "%.6f", cropScaleX))
                    .append(", ").append(String.format(Locale.US, "%.6f", cropScaleY)).append(");\n");
            fs.append("    uv = uv + vec2(").append(String.format(Locale.US, "%.6f", cropOffsetX))
                    .append(", ").append(String.format(Locale.US, "%.6f", cropOffsetY)).append(");\n");
        }

        // Barrel distortion
        if (ts.barrelEnabled && ts.barrel > 0.01f) {
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

        // Rotation
        if (ts.rotateEnabled && Math.abs(ts.rotate) > 0.01f) {
            double rad = Math.toRadians(ts.rotate);
            float cosR = (float) Math.cos(rad), sinR = (float) Math.sin(rad);
            fs.append("    uv -= 0.5;\n");
            fs.append("    uv = vec2(uv.x*").append(cosR).append("-uv.y*").append(sinR)
                    .append(", uv.x*").append(sinR).append("+uv.y*").append(cosR).append(");\n");
            fs.append("    uv += 0.5;\n");
        }

        // Zoom
        if (ts.zoomEnabled && ts.zoom > 1.001f) {
            fs.append("    uv = (uv-0.5)/").append(Math.min(ts.zoom, 3f)).append("+0.5;\n");
        }

        // Pixel shift
        if (ts.pixelShiftEnabled && ts.pixelShift != 0) {
            float sx = ts.pixelShift / (float) outW;
            float sy = ts.pixelShift / (float) outH;
            fs.append("    uv += vec2(").append(sx).append(",").append(sy).append(");\n");
        }

        fs.append("    uv = clamp(uv, 0.001, 0.999);\n");

        // Chromatic aberration
        if (ts.chromaticEnabled && ts.chromatic > 0.3f) {
            float offX = ts.chromatic / outW;
            fs.append("    float cR = texture2D(sTexture, uv - vec2(").append(offX).append(",0.0)).r;\n");
            fs.append("    float cG = texture2D(sTexture, uv).g;\n");
            fs.append("    float cB = texture2D(sTexture, uv + vec2(").append(offX).append(",0.0)).b;\n");
            fs.append("    vec4 color = vec4(cR, cG, cB, 1.0);\n");
        } else {
            fs.append("    vec4 color = texture2D(sTexture, uv);\n");
        }

        // Logo removal
        fs.append("    if(uLogoRemoval == 1) {\n");
        fs.append("        vec2 fragNorm = vec2(gl_FragCoord.x / ").append((float)outW).append(", 1.0 - gl_FragCoord.y / ").append((float)outH).append(");\n");
        fs.append("        if(fragNorm.x >= uLogoRect.x && fragNorm.x <= uLogoRect.z && fragNorm.y >= uLogoRect.y && fragNorm.y <= uLogoRect.w) {\n");
        switch (ts.logoRemovalMethod) {
            case 0:
                float blurStep = Math.min(ts.removalBlurIntensity / outW, 0.05f);
                fs.append("            vec3 blurred = vec3(0.0);\n");
                fs.append("            for(int dx = -3; dx <= 3; dx++) {\n");
                fs.append("                for(int dy = -3; dy <= 3; dy++) {\n");
                fs.append("                    vec2 offset = vec2(float(dx), float(dy)) * ").append(blurStep).append(";\n");
                fs.append("                    blurred += texture2D(sTexture, clamp(uv + offset, 0.001, 0.999)).rgb;\n");
                fs.append("                }\n");
                fs.append("            }\n");
                fs.append("            color.rgb = blurred / 49.0;\n");
                break;
            case 1:
                fs.append("            color.rgb = vec3(0.0);\n");
                break;
            case 2:
                int pixelSize = 16;
                float pxW = (float) pixelSize / outW;
                float pxH = (float) pixelSize / outH;
                fs.append("            vec2 pixelUV = floor(uv / vec2(").append(pxW).append(",").append(pxH).append(")) * vec2(").append(pxW).append(",").append(pxH).append(") + vec2(").append(pxW/2).append(",").append(pxH/2).append(");\n");
                fs.append("            color.rgb = texture2D(sTexture, clamp(pixelUV, 0.001, 0.999)).rgb;\n");
                break;
        }
        fs.append("        }\n");
        fs.append("    }\n");

        // Other transforms
        if (ts.brightEnabled && Math.abs(ts.bright - 1f) > 0.001f) {
            fs.append("    color.rgb *= ").append(ts.bright).append(";\n");
        }
        if (ts.satEnabled && Math.abs(ts.saturation - 1f) > 0.001f) {
            fs.append("    float lum = dot(color.rgb, vec3(0.2126,0.7152,0.0722));\n");
            fs.append("    color.rgb = mix(vec3(lum), color.rgb, ").append(ts.saturation).append(");\n");
        }
        if (ts.hueEnabled && Math.abs(ts.hue) > 0.1f) {
            float hueShift = ts.hue / 360f;
            fs.append("    vec3 hsv = rgb2hsv(color.rgb);\n");
            fs.append("    hsv.x = fract(hsv.x + ").append(hueShift).append(");\n");
            fs.append("    color.rgb = hsv2rgb(hsv);\n");
        }
        if (ts.gammaEnabled && Math.abs(ts.gamma - 1f) > 0.001f) {
            float invG = 1f / ts.gamma;
            fs.append("    color.rgb = pow(max(color.rgb,0.0), vec3(").append(invG).append("));\n");
        }
        if (ts.sepiaEnabled && ts.sepia > 0.001f) {
            fs.append("    vec3 sep;\n");
            fs.append("    sep.r=dot(color.rgb,vec3(0.393,0.769,0.189));\n");
            fs.append("    sep.g=dot(color.rgb,vec3(0.349,0.686,0.168));\n");
            fs.append("    sep.b=dot(color.rgb,vec3(0.272,0.534,0.131));\n");
            fs.append("    color.rgb=mix(color.rgb,sep,").append(ts.sepia).append(");\n");
        }
        if (ts.tintEnabled && ts.tint > 0.001f) {
            float r = ((ts.tintColor >> 16) & 0xFF) / 255f;
            float g = ((ts.tintColor >> 8) & 0xFF) / 255f;
            float b = (ts.tintColor & 0xFF) / 255f;
            fs.append("    color.rgb=mix(color.rgb,vec3(").append(r).append(",").append(g).append(",").append(b).append("),").append(ts.tint).append(");\n");
        }
        if (ts.sharpenEnabled && ts.sharpen > 0.001f) {
            float stepX = 1f / outW;
            float stepY = 1f / outH;
            fs.append("    vec3 sblur = texture2D(sTexture,uv+vec2(-").append(stepX).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(stepX).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,-").append(stepY).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,").append(stepY).append(")).rgb;\n");
            fs.append("    sblur *= 0.25;\n");
            fs.append("    color.rgb += (color.rgb - sblur) * ").append(ts.sharpen).append(";\n");
        }
        if (ts.blurEnabled && ts.blur > 0.01f) {
            float blurOff = ts.blur / outW;
            fs.append("    vec3 blurred = texture2D(sTexture,uv+vec2(-").append(blurOff).append(",-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(-").append(blurOff).append(",0.0)).rgb+");
            fs.append("color.rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(-").append(blurOff).append(",").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",").append(blurOff).append(")).rgb;\n");
            fs.append("    color.rgb = blurred / 9.0;\n");
        }
        if (ts.noiseEnabled && ts.noise > 0.0001f) {
            fs.append("    float n=fract(sin(dot(uv,vec2(12.9898,78.233)))*43758.5453);\n");
            fs.append("    color.rgb += (n-0.5)*").append(ts.noise).append(";\n");
        }
        if (ts.vignetteEnabled && ts.vignette > 0.001f) {
            fs.append("    vec2 vc=uv-0.5;\n");
            fs.append("    float vf=1.0-dot(vc,vc)*").append(ts.vignette * 2f).append(";\n");
            fs.append("    color.rgb *= clamp(vf,0.0,1.0);\n");
        }
        if (ts.borderEnabled && ts.border > 0) {
            float bx = ts.border / (float) outW;
            float by = ts.border / (float) outH;
            fs.append("    if(uv.x<").append(bx).append("||uv.x>").append(1f - bx)
                    .append("||uv.y<").append(by).append("||uv.y>").append(1f - by).append(") color.rgb=vec3(0.0);\n");
        }

        fs.append("    color.rgb = clamp(color.rgb, 0.0, 1.0);\n");

        // Border progress
        if (ts.borderProgressEnabled && ts.borderProgressSize > 0) {
            float bpSizeX = ts.borderProgressSize / (float) outW;
            float bpSizeY = ts.borderProgressSize / (float) outH;
            float bpR = ((ts.borderProgressColor >> 16) & 0xFF) / 255f;
            float bpG = ((ts.borderProgressColor >> 8) & 0xFF) / 255f;
            float bpB = (ts.borderProgressColor & 0xFF) / 255f;

            fs.append("    {\n");
            fs.append("        float progress = clamp(uCurrentTime / max(uTotalDuration, 0.01), 0.0, 1.0);\n");
            fs.append("        float bpx = ").append(String.format(Locale.US, "%.6f", bpSizeX)).append(";\n");
            fs.append("        float bpy = ").append(String.format(Locale.US, "%.6f", bpSizeY)).append(";\n");
            fs.append("        float totalProgress = progress * 4.0;\n");
            fs.append("        bool hit = false;\n");
            fs.append("        vec2 sc = vTextureCoord;\n");
            fs.append("        if(sc.y <= bpy && sc.x <= clamp(totalProgress, 0.0, 1.0)) hit = true;\n");
            fs.append("        if(sc.x >= (1.0 - bpx) && totalProgress >= 1.0 && sc.y <= clamp(totalProgress - 1.0, 0.0, 1.0)) hit = true;\n");
            fs.append("        if(sc.y >= (1.0 - bpy) && totalProgress >= 2.0 && sc.x >= (1.0 - clamp(totalProgress - 2.0, 0.0, 1.0))) hit = true;\n");
            fs.append("        if(sc.x <= bpx && totalProgress >= 3.0 && sc.y >= (1.0 - clamp(totalProgress - 3.0, 0.0, 1.0))) hit = true;\n");
            fs.append("        if(hit) color.rgb = mix(color.rgb, vec3(")
                    .append(String.format(Locale.US, "%.3f", bpR)).append(", ")
                    .append(String.format(Locale.US, "%.3f", bpG)).append(", ")
                    .append(String.format(Locale.US, "%.3f", bpB)).append("), 0.85);\n");
            fs.append("    }\n");
        }

        fs.append("    color.rgb = clamp(color.rgb, 0.0, 1.0);\n");

        // ★★★ FIXED: Reaction Face Overlay - NO MIRROR ★★★
        fs.append("\n    // ═══ Reaction Face Overlay (NO MIRROR) ═══\n");
        fs.append("    if(uHasFaceVideo == 1) {\n");
        fs.append("        vec2 screenUV = vec2(gl_FragCoord.x / ").append((float)outW).append(", 1.0 - gl_FragCoord.y / ").append((float)outH).append(");\n");
        fs.append("        float faceX = uFaceRect.x;\n");
        fs.append("        float faceY = uFaceRect.y;\n");
        fs.append("        float faceW = uFaceRect.z;\n");
        fs.append("        float faceH = uFaceRect.w;\n");
        fs.append("        if(screenUV.x >= faceX && screenUV.x <= faceX + faceW &&\n");
        fs.append("           screenUV.y >= faceY && screenUV.y <= faceY + faceH) {\n");
        fs.append("            vec2 localUV = (screenUV - vec2(faceX, faceY)) / vec2(faceW, faceH);\n");
        fs.append("            vec2 center = vec2(0.5, 0.5);\n");
        fs.append("            vec2 fromCenter = abs(localUV - center);\n");
        fs.append("            float cornerR = uFaceCornerRadius;\n");
        fs.append("            float dist = length(max(fromCenter - (0.5 - cornerR), 0.0)) - cornerR;\n");
        fs.append("            if(dist <= 0.0) {\n");
        fs.append("                vec2 faceTexCoord = vec2(localUV.x, localUV.y);\n");
        fs.append("                vec4 faceColor = texture2D(sFaceTexture, faceTexCoord);\n");
        fs.append("                float edgeSmooth = 1.0 - smoothstep(-0.02, 0.005, dist);\n");
        fs.append("                color.rgb = mix(color.rgb, faceColor.rgb, edgeSmooth);\n");
        fs.append("            }\n");
        fs.append("        }\n");
        fs.append("    }\n");

        // Watermark
        fs.append("\n    if(uHasWatermark==1){\n");
        fs.append("        vec2 wmCoord = vTextureCoord;\n");
        fs.append("        if(uFlipEnabled == 1) wmCoord.x = 1.0 - wmCoord.x;\n");
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
    // AUDIO PROCESSING WITH FACE AUDIO MIXING (70% Main / 100% Face)
    // ════════════════════════════════════════════════════════════════════════════
    private void processAudioWithFaceMix(Context context, Uri inputUri, String videoOutputPath,
                                         int audioTrackIndex, MediaFormat audioFormat,
                                         long trimStartUs, float speedFactor, float volumeFactor,
                                         long maxDurationUs, ProgressCallback callback, TransformSettings ts,
                                         ArrayList<byte[]> videoChunks,
                                         ArrayList<MediaCodec.BufferInfo> videoChunkInfos,
                                         MediaFormat encodedVideoFormat,
                                         boolean hasFaceAudio, Uri faceUri,
                                         int faceAudioTrackIndex, MediaFormat faceAudioFormat) {

        String finalOutputPath = videoOutputPath.replace(".mp4", "_final.mp4");
        MediaExtractor audioExtractor = null;
        MediaCodec audioDecoder = null;
        MediaExtractor faceAudioExtractor = null;
        MediaCodec faceAudioDecoder = null;
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

            // ★★★ VOLUME CALCULATION: Face enabled = 70%/100%, Face disabled = 100% ★★★
            final float MAIN_VIDEO_VOLUME;
            final float FACE_VIDEO_VOLUME;

            boolean useFaceAudio = hasFaceAudio && faceUri != null && faceAudioTrackIndex >= 0 && faceAudioFormat != null;

            if (useFaceAudio) {
                // Face video enabled: Main 70%, Face 100%
                MAIN_VIDEO_VOLUME = volumeFactor * 0.70f;
                FACE_VIDEO_VOLUME = 1.0f;
                Log.d(TAG, "★ Audio Mix Mode: Main=70%, Face=100%");
            } else {
                // Face video disabled: Main 100%
                MAIN_VIDEO_VOLUME = volumeFactor * 1.0f;
                FACE_VIDEO_VOLUME = 0.0f;
                Log.d(TAG, "★ Audio Solo Mode: Main=100%");
            }

            Log.d(TAG, "Face audio check: hasFaceAudio=" + hasFaceAudio + " faceUri=" + faceUri +
                    " trackIndex=" + faceAudioTrackIndex + " format=" + (faceAudioFormat != null));

            if (useFaceAudio) {
                try {
                    faceAudioExtractor = new MediaExtractor();
                    faceAudioExtractor.setDataSource(context, faceUri, null);
                    faceAudioExtractor.selectTrack(faceAudioTrackIndex);
                    if (trimStartUs > 0)
                        faceAudioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    String faceMime = faceAudioFormat.getString(MediaFormat.KEY_MIME);

                    faceAudioDecoder = MediaCodec.createDecoderByType(faceMime);
                    faceAudioDecoder.configure(faceAudioFormat, null, null, 0);
                    faceAudioDecoder.start();

                    Log.d(TAG, "★ Face audio decoder started");
                } catch (Exception e) {
                    Log.e(TAG, "Face audio setup error", e);
                    useFaceAudio = false;
                }
            } else {
                Log.d(TAG, "Face audio not available or disabled");
            }

            final boolean mixFaceAudio = useFaceAudio;

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
            MediaCodec.BufferInfo faceDecInfo = new MediaCodec.BufferInfo();

            boolean inputDone = false, decodeDone = false, encodeDone = false;
            boolean faceInputDone = false, faceDecodeDone = false;
            ArrayList<byte[]> encodedChunks = new ArrayList<>();
            ArrayList<MediaCodec.BufferInfo> chunkInfos = new ArrayList<>();
            MediaFormat outputAudioFormat = null;

            long firstPts = -1L, lastOutputPts = -1L;
            int processedSamples = 0;
            long lastAudioProgress = System.currentTimeMillis();
            short[] pendingSamples = null;
            long pendingPts = 0;
            boolean eosSignalPending = false;

            short[] pendingFaceSamples = null;
            int faceBufferReadPos = 0;

            while (!encodeDone && !isCancelled) {

                // Main audio decoder input
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

                // Face audio decoder input
                if (mixFaceAudio && !faceInputDone && faceAudioDecoder != null && faceAudioExtractor != null) {
                    int faceInIdx = faceAudioDecoder.dequeueInputBuffer(0);
                    if (faceInIdx >= 0) {
                        ByteBuffer faceInBuf = faceAudioDecoder.getInputBuffer(faceInIdx);
                        if (faceInBuf != null) {
                            faceInBuf.clear();
                            int sz = faceAudioExtractor.readSampleData(faceInBuf, 0);
                            if (sz < 0) {
                                faceAudioDecoder.queueInputBuffer(faceInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                faceInputDone = true;
                            } else {
                                faceAudioDecoder.queueInputBuffer(faceInIdx, 0, sz, faceAudioExtractor.getSampleTime(), 0);
                                faceAudioExtractor.advance();
                            }
                        }
                    }
                }

                // Face audio decoder output
                if (mixFaceAudio && !faceDecodeDone && faceAudioDecoder != null) {
                    int faceOutIdx = faceAudioDecoder.dequeueOutputBuffer(faceDecInfo, 0);
                    if (faceOutIdx >= 0) {
                        boolean isEos = (faceDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        ByteBuffer faceOutBuf = faceAudioDecoder.getOutputBuffer(faceOutIdx);

                        if (faceDecInfo.size > 0 && faceOutBuf != null) {
                            faceOutBuf.position(faceDecInfo.offset);
                            faceOutBuf.limit(faceDecInfo.offset + faceDecInfo.size);
                            ShortBuffer sb = faceOutBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            short[] newFaceSamples = new short[sb.remaining()];
                            sb.get(newFaceSamples);

                            if (pendingFaceSamples == null) {
                                pendingFaceSamples = newFaceSamples;
                                faceBufferReadPos = 0;
                            } else {
                                int remaining = pendingFaceSamples.length - faceBufferReadPos;
                                short[] combined = new short[remaining + newFaceSamples.length];
                                System.arraycopy(pendingFaceSamples, faceBufferReadPos, combined, 0, remaining);
                                System.arraycopy(newFaceSamples, 0, combined, remaining, newFaceSamples.length);
                                pendingFaceSamples = combined;
                                faceBufferReadPos = 0;
                            }
                        }

                        faceAudioDecoder.releaseOutputBuffer(faceOutIdx, false);
                        if (isEos) {
                            faceDecodeDone = true;
                            Log.d(TAG, "Face audio decode done");
                        }
                    }
                }

                // Encoder input - pending first
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

                            // ★★★ FIXED: Apply main video volume (70% if face enabled, 100% otherwise) ★★★
                            for (int i = 0; i < samples.length; i++) {
                                float s = samples[i] * MAIN_VIDEO_VOLUME;
                                samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) s));
                            }

                            // ★★★ MIX FACE AUDIO at 100% volume ★★★
                            if (mixFaceAudio && pendingFaceSamples != null) {
                                int available = pendingFaceSamples.length - faceBufferReadPos;
                                int toMix = Math.min(samples.length, available);

                                for (int i = 0; i < toMix; i++) {
                                    float mainSample = samples[i];
                                    float faceSample = pendingFaceSamples[faceBufferReadPos + i] * FACE_VIDEO_VOLUME;
                                    float mixed = mainSample + faceSample;
                                    if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                                    if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                                    samples[i] = (short) mixed;
                                }

                                faceBufferReadPos += toMix;
                                if (faceBufferReadPos >= pendingFaceSamples.length) {
                                    pendingFaceSamples = null;
                                    faceBufferReadPos = 0;
                                }
                            }

                            // Other audio transforms
                            if (ts.pitchEnabled && Math.abs(ts.pitch - 1f) > 0.005f) {
                                samples = applyPitchToSamples(samples, ts.pitch, channelCount);
                            }
                            if (ts.spectralNoiseEnabled && ts.spectralNoise > 0.0001f) {
                                samples = applySpectralNoise(samples, ts.spectralNoise, sampleRate);
                            }
                            if (ts.ambientNoiseEnabled && ts.ambientNoiseLevel > 0.0001f) {
                                samples = applyAmbientNoise(samples, ts.ambientNoiseLevel);
                            }

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
                            String msg = mixFaceAudio ? "অডিও মিক্স: " + processedSamples : "অডিও: " + processedSamples;
                            callback.onProgress(Math.min(AP_END, Math.max(AP_START, pct)), msg);
                            lastAudioProgress = System.currentTimeMillis();
                        }
                    }
                }

                if (decodeDone && pendingSamples == null && eosSignalPending) {
                    int eosIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                    if (eosIdx >= 0) {
                        audioEncoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosSignalPending = false;
                    }
                }

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

            Log.d(TAG, "Audio done: samples=" + processedSamples + " chunks=" + encodedChunks.size() + " faceAudioMixed=" + mixFaceAudio);

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
            try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); } } catch (Exception ignored) {}
            try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
            try { if (faceAudioDecoder != null) { faceAudioDecoder.stop(); faceAudioDecoder.release(); } } catch (Exception ignored) {}
            try { if (faceAudioExtractor != null) faceAudioExtractor.release(); } catch (Exception ignored) {}
        }
    }

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

    private short[] applySpectralNoise(short[] samples, float noiseLevel, int sampleRate) {
        short[] output = new short[samples.length];
        float alpha = (float) (2.0 * Math.PI * 6000.0 / sampleRate);
        alpha = alpha / (alpha + 1.0f);
        float prevInput = 0, prevOutput = 0;
        for (int i = 0; i < samples.length; i++) {
            float noise = (random.nextFloat() - 0.5f) * 2.0f * noiseLevel * Short.MAX_VALUE;
            float filteredNoise = alpha * (prevOutput + noise - prevInput);
            prevInput = noise;
            prevOutput = filteredNoise;
            float signalEnergy = Math.abs(samples[i]) / (float) Short.MAX_VALUE;
            float dynamicNoise = filteredNoise * (0.3f + signalEnergy * 0.7f);
            float result = samples[i] + dynamicNoise;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) result));
        }
        return output;
    }

    private short[] applyAmbientNoise(short[] samples, float noiseLevel) {
        short[] output = new short[samples.length];
        float brownNoise = 0;
        for (int i = 0; i < samples.length; i++) {
            float white = (random.nextFloat() - 0.5f) * noiseLevel * Short.MAX_VALUE;
            brownNoise = (brownNoise + white * 0.02f) * 0.98f;
            brownNoise = Math.max(-0.1f * Short.MAX_VALUE, Math.min(0.1f * Short.MAX_VALUE, brownNoise));
            float signalStrength = Math.abs(samples[i]) / (float) Short.MAX_VALUE;
            float adaptiveNoise = brownNoise * (0.3f + signalStrength * 0.7f);
            float result = samples[i] + adaptiveNoise;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) result));
        }
        return output;
    }

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

    private void muxVideoOnly(MediaFormat videoFormat, ArrayList<byte[]> chunks, ArrayList<MediaCodec.BufferInfo> infos, String outputPath) {
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
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private void muxVideoAndAudioInMemory(MediaFormat videoFormat, ArrayList<byte[]> vChunks, ArrayList<MediaCodec.BufferInfo> vInfos,
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
                else writeVideo = vInfos.get(vi).presentationTimeUs <= aInfos.get(ai).presentationTimeUs;
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
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private Bitmap createWatermarkBitmap(WatermarkConfig wm, int w, int h, long currentTimeUs) {
        if (wm == null || !wm.isActive()) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int pad = 20;
        Bitmap logo = wm.logoBitmap != null ? wm.logoBitmap : WatermarkConfig.sharedLogoBitmap;
        WatermarkConfig.Position currentLogoPos = wm.getDynamicPosition(currentTimeUs);
        WatermarkConfig.Position currentTextPos = wm.dynamicPosition ? wm.getDynamicPosition(currentTimeUs + 500_000) : wm.textPosition;

        if ((wm.mode == WatermarkConfig.Mode.LOGO || wm.mode == WatermarkConfig.Mode.BOTH) && logo != null && !logo.isRecycled()) {
            int lW = w * wm.logoSize / 100;
            int lH = Math.round((float) lW * logo.getHeight() / logo.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(logo, lW, lH, true);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setAlpha(Math.round(wm.logoOpacity / 100f * 255));
            canvas.drawBitmap(scaled, calcX(currentLogoPos, lW, w, pad), calcY(currentLogoPos, lH, h, pad), p);
            if (scaled != logo) scaled.recycle();
        }

        if ((wm.mode == WatermarkConfig.Mode.TEXT || wm.mode == WatermarkConfig.Mode.BOTH) && wm.text != null && !wm.text.trim().isEmpty()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(wm.fontSize);
            p.setTypeface(wm.textStyle == WatermarkConfig.TextStyle.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float tw = p.measureText(wm.text);
            int extraY = (wm.mode == WatermarkConfig.Mode.BOTH && logo != null && currentLogoPos == currentTextPos) ? h * wm.logoSize / 100 + 10 : 0;
            float x = calcX(currentTextPos, (int) tw, w, pad);
            float y = calcTextY(currentTextPos, wm.fontSize, h, pad, extraY);
            p.setColor(Color.BLACK);
            p.setAlpha(Math.round(wm.textOpacity / 100f * 128));
            canvas.drawText(wm.text, x + 2, y + 2, p);
            if (wm.textStyle == WatermarkConfig.TextStyle.OUTLINE) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f);
                p.setColor(Color.BLACK);
                p.setAlpha(Math.round(wm.textOpacity / 100f * 255));
                canvas.drawText(wm.text, x, y, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setColor(wm.textColor);
            p.setAlpha(Math.round(wm.textOpacity / 100f * 255));
            canvas.drawText(wm.text, x, y, p);
        }
        return bmp;
    }

    private float calcX(WatermarkConfig.Position pos, int w, int cW, int pad) {
        switch (pos) {
            case TOP_RIGHT: case BOTTOM_RIGHT: return cW - w - pad;
            case CENTER: return (cW - w) / 2f;
            default: return pad;
        }
    }

    private float calcY(WatermarkConfig.Position pos, int h, int cH, int pad) {
        switch (pos) {
            case BOTTOM_LEFT: case BOTTOM_RIGHT: return cH - h - pad;
            case CENTER: return (cH - h) / 2f;
            default: return pad;
        }
    }

    private float calcTextY(WatermarkConfig.Position pos, int fs, int cH, int pad, int extraY) {
        switch (pos) {
            case BOTTOM_LEFT: case BOTTOM_RIGHT: return cH - pad + extraY;
            case CENTER: return cH / 2f + fs / 2f + extraY;
            default: return pad + fs + extraY;
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