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
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class VideoProcessorMediaCodec {

    private static final String TAG = "VideoProcessor";
    private static final int TIMEOUT_US = 2500;
    private static final int FRAME_WAIT_TIMEOUT_MS = 500;

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
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "OOM during processing", oom);
                System.gc();
                if (!isCancelled) {
                    callback.onError("মেমরি কম পড়েছে। ছোট ভিডিও বা কম রেজুলেশন ট্রাই করুন।");
                }
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

        final Object frameSyncObject = new Object();
        final boolean[] frameAvailable = {false};

        callback.onProgress(0, "ভিডিও বিশ্লেষণ...");

        MediaExtractor extractor = new MediaExtractor();
        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = context.getContentResolver().openAssetFileDescriptor(inputUri, "r");
            if (afd != null) {
                extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } else {
                extractor.setDataSource(context, inputUri, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "AFD fallback, trying direct URI", e);
            extractor.setDataSource(context, inputUri, null);
        } finally {
            if (afd != null) try { afd.close(); } catch (Exception ignored) {}
        }

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

        if (videoTrackIndex < 0 || videoFormat == null) {
            extractor.release();
            callback.onError("ভিডিও ট্র্যাক পাওয়া যায়নি");
            return;
        }

        int rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);

        long durationUs = 5_000_000L;
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(context, inputUri);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) durationUs = Long.parseLong(durStr) * 1000L;
        } catch (Exception e) {
            Log.w(TAG, "MMR duration failed", e);
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

        int inputWidth;
        int inputHeight;
        if (videoRotation == 90 || videoRotation == 270) {
            inputWidth = rawHeight;
            inputHeight = rawWidth;
        } else {
            inputWidth = rawWidth;
            inputHeight = rawHeight;
        }

        callback.onProgress(1, inputWidth + "×" + inputHeight);

        int outputWidth = inputWidth;
        int outputHeight = inputHeight;

        if (resolution == null) resolution = "original";
        if (aspectRatio == null) aspectRatio = "original";

        float inputRatio = (float) inputWidth / inputHeight;
        float targetRatio = inputRatio;

        if (!"original".equals(aspectRatio)) {
            switch (aspectRatio) {
                case "16:9": targetRatio = 16f / 9f; break;
                case "9:16": targetRatio = 9f / 16f; break;
                case "1:1": targetRatio = 1.0f; break;
                case "4:3": targetRatio = 4f / 3f; break;
                case "4:5": targetRatio = 0.8f; break;
                case "3:4": targetRatio = 0.75f; break;
                default: targetRatio = inputRatio; break;
            }
        }

        int croppedWidth;
        int croppedHeight;
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
                int targetRes = Integer.parseInt(resolution);
                // Orientation-aware resolution:
                // In Portrait (H > W), target the width (e.g. 1080p -> 1080x1920)
                // In Landscape (W >= H), target the height (e.g. 1080p -> 1920x1080)
                if (inputHeight > inputWidth) {
                    if (outputWidth != targetRes) {
                        float scale = (float) targetRes / outputWidth;
                        outputWidth = targetRes;
                        outputHeight = Math.round(outputHeight * scale);
                    }
                } else {
                    if (outputHeight != targetRes) {
                        float scale = (float) targetRes / outputHeight;
                        outputHeight = targetRes;
                        outputWidth = Math.round(outputWidth * scale);
                    }
                }
            } catch (Exception ignored) {}
        }

        outputWidth = Math.max(128, (outputWidth / 2) * 2);
        outputHeight = Math.max(128, (outputHeight / 2) * 2);

        final int FINAL_WIDTH = outputWidth;
        final int FINAL_HEIGHT = outputHeight;

        float cropOffsetX = 0f, cropOffsetY = 0f, cropScaleX = 1f, cropScaleY = 1f;
        // অরিজিনাল মুডে যাতে কোনোভাবেই ক্রপ না হয় তা নিশ্চিত করা
        if (!"original".equals(aspectRatio) && (croppedWidth != inputWidth || croppedHeight != inputHeight)) {
            cropScaleX = (float) croppedWidth / inputWidth;
            cropScaleY = (float) croppedHeight / inputHeight;
            cropOffsetX = (1f - cropScaleX) / 2f;
            cropOffsetY = (1f - cropScaleY) / 2f;
        } else {
            // Original ratio এর জন্য সব সময় ফুল স্ক্রিন
            cropScaleX = 1f;
            cropScaleY = 1f;
            cropOffsetX = 0f;
            cropOffsetY = 0f;
        }

        final float CROP_OFFSET_X = cropOffsetX;
        final float CROP_OFFSET_Y = cropOffsetY;
        final float CROP_SCALE_X = cropScaleX;
        final float CROP_SCALE_Y = cropScaleY;

        long trimStartUs = (ts.trimEnabled && ts.trim > 0) ? (long) (ts.trim * 1_000_000L) : 0L;
        float speedFactor = (ts.speedEnabled && ts.speed > 0.1f) ? ts.speed : 1.0f;
        float volumeFactor = ts.volumeEnabled ? Math.max(0f, Math.min(3f, ts.volume)) : 1.0f;

        long effectiveDurationUs = (long) ((durationUs - trimStartUs) / speedFactor);
        float effectiveDurationSec = effectiveDurationUs / 1_000_000.0f;

        // === PROGRESS CALCULATION ===
        boolean hasAudio = (audioTrackIndex >= 0 && audioFormat != null);
        boolean needsAudioProcessing = hasAudio && needsAudioProcessing(ts, false);

        // Weight: video = 75%, audio = 20%, init+finalize = 5%
        // If no audio processing: video = 90%, init+finalize = 10%
        final float VIDEO_WEIGHT = needsAudioProcessing ? 0.75f : 0.90f;
        final float AUDIO_WEIGHT = needsAudioProcessing ? 0.20f : 0.0f;
        final float INIT_WEIGHT = 0.03f;
        final float FINALIZE_WEIGHT = needsAudioProcessing ? 0.02f : 0.07f;
        // Total = INIT + VIDEO + AUDIO + FINALIZE = 1.0

        callback.onProgress(1, "প্রিপারেশন...");

        String outputPath = createOutputPath();
        String tempVideoPath = outputPath.replace(".mp4", "_video_only.mp4");

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

        try {
            encoderFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception ignored) {}

        try {
            encoderFormat.setInteger("operating-rate", frameRate * 6);
        } catch (Exception ignored) {}

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderSurface = encoder.createInputSurface();
        encoder.start();

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

        int[] texArr = new int[1];
        GLES20.glGenTextures(1, texArr, 0);
        int textureId = texArr[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

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
        Uri faceUri = null;

        if (hasFaceVideo) {
            try {
                callback.onProgress(2, "Reaction Face লোড হচ্ছে...");

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
                    String faceMime = faceVideoFormat.getString(MediaFormat.KEY_MIME);
                    int faceRawWidth = faceVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int faceRawHeight = faceVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);

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
                } else {
                    hasFaceVideo = false;
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
        final boolean FACE_HAS_AUDIO = (faceAudioTrackIndex >= 0 && faceAudioFormat != null && ts.reactionFaceAudioEnabled);

        // Re-check audio processing need with face audio info
        final boolean NEEDS_AUDIO_PROCESSING = hasAudio && needsAudioProcessing(ts, FACE_HAS_AUDIO);

        final float faceSize = ts.reactionFaceSize / 100f;
        final float faceCornerRadius = ts.reactionFaceCornerRadius / 100f;

        int facePadding = 20;
        int faceSizePixels = (int) (FINAL_WIDTH * faceSize);

        int facePixelX;
        int facePixelY;
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

        final float FACE_RECT_X = (float) facePixelX / FINAL_WIDTH;
        final float FACE_RECT_Y = (float) facePixelY / FINAL_HEIGHT;
        final float FACE_RECT_W = faceSize;
        final float FACE_RECT_H = (float) faceSizePixels / FINAL_HEIGHT;
        final float FACE_CORNER_RADIUS = faceCornerRadius;

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

        int[] watermarkTexIdHolder = new int[]{0};
        boolean hasWatermark = false;
        final boolean isDynamicWatermark = (wm != null && wm.dynamicPosition);
        long lastWatermarkUpdateUs = 0;

        if (wm != null) {
            wm.syncLogo();
            if (wm.isActive()) {
                Bitmap wmBitmap = createWatermarkBitmapOptimized(wm, FINAL_WIDTH, FINAL_HEIGHT, 0);
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

        // Apply 3D Perspective Warp (Version 7.0)
        if (ts.perspective3DEnabled) {
            float aspect = (float) FINAL_WIDTH / FINAL_HEIGHT;
            float[] projection = new float[16];
            Matrix.perspectiveM(projection, 0, 45f, aspect, 0.1f, 100f);
            float[] view = new float[16];
            // Correct eyeZ for 45 deg FOV is ~2.4142f to avoid shrinking
            Matrix.setLookAtM(view, 0, 0f, 0f, 2.41421356f, 0f, 0f, 0f, 0f, 1f, 0f);
            float[] model = new float[16];
            Matrix.setIdentityM(model, 0);
            // Scale quad to match aspect ratio before rotation to fill the view
            Matrix.scaleM(model, 0, aspect, 1.0f, 1.0f);
            Matrix.rotateM(model, 0, ts.perspectiveTiltX, 0f, 1f, 0f); // X-Tilt
            Matrix.rotateM(model, 0, ts.perspectiveTiltY, 1f, 0f, 0f); // Y-Tilt

            float[] mvp3d = new float[16];
            float[] temp = new float[16];
            Matrix.multiplyMM(temp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp3d, 0, projection, 0, temp, 0);

            float[] combined = new float[16];
            Matrix.multiplyMM(combined, 0, mvp3d, 0, mvpMatrix, 0);
            System.arraycopy(combined, 0, mvpMatrix, 0, 16);
        }

        // Apply Aspect Distortion (Subtle Stretch)
        if (ts.aspectDistortionEnabled) {
            Matrix.scaleM(mvpMatrix, 0, ts.aspectDistortionX, ts.aspectDistortionY, 1.0f);
        }

        float[] stMatrix = new float[16];
        Matrix.setIdentityM(stMatrix, 0);

        float[] faceStMatrix = new float[16];
        Matrix.setIdentityM(faceStMatrix, 0);

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

        MediaMuxer videoOnlyMuxer = new MediaMuxer(tempVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVideoTrack = -1;
        boolean muxStarted = false;
        MediaFormat encodedVideoFormat = null;

        extractor.selectTrack(videoTrackIndex);
        if (trimStartUs > 0) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        // Init done - report 3%
        callback.onProgress(3, "প্রসেসিং শুরু...");

        MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

        boolean inputDone = false;
        boolean outputDone = false;
        boolean encoderDone = false;
        int frameCount = 0;
        long frameIntervalUs = 1_000_000L / frameRate;
        long lastPts = -1L;
        long videoFirstPts = -1L;
        long lastInputPtsProcessed = 0L;
        int frameCounter = 0;
        long lastProgressUpdate = System.currentTimeMillis();

        boolean pendingDuplicate = false;
        long duplicatePts = 0;
        float[] duplicateStMatrix = new float[16];

        final long[] faceFirstPtsHolder = {-1L};
        final boolean[] faceOutputDoneHolder = {false};
        long currentFacePts = -1L;

        while (!encoderDone && !isCancelled) {

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

            if (useFaceVideo && !faceInputDone && FACE_DECODER != null && FACE_EXTRACTOR != null) {
                for (int i = 0; i < 2 && !faceInputDone; i++) {
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
                                FACE_DECODER.queueInputBuffer(faceInIdx, 0, sz, FACE_EXTRACTOR.getSampleTime(), 0);
                                FACE_EXTRACTOR.advance();
                            }
                        }
                    }
                }
            }

            if (pendingDuplicate && !outputDone) {
                renderFrame(eglDisplay, eglSurface, eglContext, program, textureId, watermarkTexIdHolder[0],
                        FACE_TEX_ID, FINAL_WIDTH, FINAL_HEIGHT, mvpMatrix, duplicateStMatrix, faceStMatrix,
                        vertexBuffer, positionHandle, textureCoordHandle, mvpMatrixHandle, stMatrixHandle,
                        textureHandle, watermarkHandle, hasWatermarkHandle, faceTextureHandle,
                        hasFaceVideoHandle, faceRectHandle, faceCornerRadiusHandle, currentTimeHandle,
                        duplicatePts, useWatermark, useFaceVideo && !faceOutputDoneHolder[0],
                        FACE_RECT_X, FACE_RECT_Y, FACE_RECT_W, FACE_RECT_H, FACE_CORNER_RADIUS);
                lastPts = duplicatePts;
                frameCount++;
                pendingDuplicate = false;
            }

            if (!outputDone) {
                int outIdx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
                if (outIdx >= 0) {
                    boolean doRender = (decoderInfo.size != 0);
                    decoder.releaseOutputBuffer(outIdx, doRender);

                    if (doRender) {
                        boolean got = awaitNewFrame(frameSyncObject, frameAvailable);
                        if (got) {
                            outputSurfaceTexture.updateTexImage();
                            outputSurfaceTexture.getTransformMatrix(stMatrix);

                            long rawPts = decoderInfo.presentationTimeUs;
                            if (videoFirstPts < 0) videoFirstPts = rawPts;
                            
                            // 32. Temporal Variable Speed (VFR Oscillation)
                            float currentSpeed = speedFactor;
                            if (ts.variableSpeedEnabled) {
                                float elapsedSec = (rawPts - videoFirstPts) / 1_000_000.0f;
                                float wave = (float) Math.sin(elapsedSec * 2.0);
                                currentSpeed *= (1.0f + wave * (ts.variableSpeedIntensity / 100.0f));
                            }
                            
                            // Accurate cumulative output PTS to maintain 100% sync
                            long outputPts;
                            if (lastPts < 0) {
                                outputPts = 0;
                            } else {
                                long inputDelta = rawPts - (videoFirstPts + lastInputPtsProcessed);
                                outputPts = lastPts + (long)(inputDelta / currentSpeed);
                            }
                            lastInputPtsProcessed = rawPts - videoFirstPts;

                            if (useFaceVideo && FACE_DECODER != null && FACE_SURFACE_TEXTURE != null && !faceOutputDoneHolder[0]) {
                                currentFacePts = advanceFaceVideoToTime(
                                        FACE_DECODER,
                                        FACE_SURFACE_TEXTURE,
                                        faceSyncObject,
                                        faceFrameAvailable,
                                        faceStMatrix,
                                        outputPts,
                                        currentFacePts,
                                        faceFirstPtsHolder,
                                        faceOutputDoneHolder
                                );
                            }

                            boolean shouldRenderFrame = true;
                            boolean shouldDuplicate = false;

                            if (ts.temporalJitterEnabled && ts.jitterIntensity > 0 && frameCount > 10) {
                                float rand = random.nextFloat();
                                if (rand < ts.jitterIntensity) {
                                    if (random.nextBoolean()) {
                                        shouldRenderFrame = false;
                                    } else {
                                        shouldDuplicate = true;
                                    }
                                }
                            }

                            if (shouldRenderFrame) {
                                if (isDynamicWatermark && wm != null && wm.isActive()) {
                                    long intervalUs = wm.positionChangeIntervalSec * 1_000_000L;
                                    if ((outputPts - lastWatermarkUpdateUs) >= intervalUs) {
                                        Bitmap newWmBitmap = createWatermarkBitmapOptimized(wm, FINAL_WIDTH, FINAL_HEIGHT, outputPts);
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
                                        outputPts, useWatermark, useFaceVideo && !faceOutputDoneHolder[0],
                                        FACE_RECT_X, FACE_RECT_Y, FACE_RECT_W, FACE_RECT_H, FACE_CORNER_RADIUS);

                                lastPts = outputPts;
                                frameCount++;

                                if (shouldDuplicate) {
                                    pendingDuplicate = true;
                                    duplicatePts = outputPts + (frameIntervalUs / 2);
                                    System.arraycopy(stMatrix, 0, duplicateStMatrix, 0, 16);
                                }

                                // === SYNCED VIDEO PROGRESS ===
                                if (System.currentTimeMillis() - lastProgressUpdate > 400) {
                                    double videoRatio = 0.0;
                                    if (effectiveDurationUs > 0 && outputPts > 0) {
                                        videoRatio = (double) outputPts / effectiveDurationUs;
                                    }
                                    videoRatio = Math.max(0.0, Math.min(1.0, videoRatio));

                                    // INIT_WEIGHT(3%) already sent, now video portion
                                    int pct = (int) (INIT_WEIGHT * 100 + VIDEO_WEIGHT * 100 * videoRatio);
                                    pct = Math.max(3, Math.min(pct, (int) ((INIT_WEIGHT + VIDEO_WEIGHT) * 100)));
                                    callback.onProgress(pct, "ফ্রেম: " + frameCounter);
                                    lastProgressUpdate = System.currentTimeMillis();
                                }
                                frameCounter++;
                            }
                        }
                    }

                    if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        encoder.signalEndOfInputStream();
                    }
                }
            }

            while (true) {
                int encIdx = encoder.dequeueOutputBuffer(encoderInfo, 0);
                if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encodedVideoFormat == null) {
                        encodedVideoFormat = encoder.getOutputFormat();
                        muxVideoTrack = videoOnlyMuxer.addTrack(encodedVideoFormat);
                        videoOnlyMuxer.start();
                        muxStarted = true;
                    }
                } else if (encIdx >= 0) {
                    ByteBuffer encData = encoder.getOutputBuffer(encIdx);
                    if (encData != null && encodedVideoFormat != null && encoderInfo.size > 0
                            && (encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxStarted) {
                        encData.position(encoderInfo.offset);
                        encData.limit(encoderInfo.offset + encoderInfo.size);
                        videoOnlyMuxer.writeSampleData(muxVideoTrack, encData, encoderInfo);
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

        try { decoder.stop(); } catch (Exception ignored) {}
        try { decoder.release(); } catch (Exception ignored) {}
        try { encoder.stop(); } catch (Exception ignored) {}
        try { encoder.release(); } catch (Exception ignored) {}
        try { extractor.release(); } catch (Exception ignored) {}
        try { decoderSurface.release(); } catch (Exception ignored) {}
        try { outputSurfaceTexture.release(); } catch (Exception ignored) {}

        if (useFaceVideo) {
            try { if (FACE_DECODER != null) { FACE_DECODER.stop(); FACE_DECODER.release(); } } catch (Exception ignored) {}
            try { if (FACE_EXTRACTOR != null) FACE_EXTRACTOR.release(); } catch (Exception ignored) {}
            try { if (faceDecoderSurface != null) faceDecoderSurface.release(); } catch (Exception ignored) {}
            try { if (FACE_SURFACE_TEXTURE != null) FACE_SURFACE_TEXTURE.release(); } catch (Exception ignored) {}
            if (FACE_TEX_ID != -1) GLES20.glDeleteTextures(1, new int[]{FACE_TEX_ID}, 0);
        }

        try {
            if (muxStarted) videoOnlyMuxer.stop();
        } catch (Exception ignored) {}
        try { videoOnlyMuxer.release(); } catch (Exception ignored) {}

        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        GLES20.glDeleteTextures(1, watermarkTexIdHolder, 0);
        GLES20.glDeleteProgram(program);

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
        try { encoderSurface.release(); } catch (Exception ignored) {}

        System.gc();

        if (isCancelled) {
            try { new File(tempVideoPath).delete(); } catch (Exception ignored) {}
            return;
        }

        File tempVideoFile = new File(tempVideoPath);
        if (!tempVideoFile.exists() || tempVideoFile.length() < 1000) {
            callback.onError("ভিডিও এনকোড ব্যর্থ");
            return;
        }

        // Video done progress
        int videoDonePct = (int) ((INIT_WEIGHT + VIDEO_WEIGHT) * 100);
        callback.onProgress(videoDonePct, "ভিডিও সম্পন্ন...");

        if (audioTrackIndex >= 0 && audioFormat != null) {
            processAudioStable(
                    context, inputUri, tempVideoPath, outputPath,
                    audioTrackIndex, audioFormat,
                    trimStartUs, speedFactor, volumeFactor, effectiveDurationUs, callback, ts,
                    FACE_HAS_AUDIO, FACE_URI, FACE_AUDIO_TRACK_INDEX, FACE_AUDIO_FORMAT,
                    INIT_WEIGHT, VIDEO_WEIGHT, AUDIO_WEIGHT, FINALIZE_WEIGHT
            );
        } else {
            boolean renamed = tempVideoFile.renameTo(new File(outputPath));
            if (!renamed) {
                muxTempVideoOnly(tempVideoPath, outputPath);
                try { tempVideoFile.delete(); } catch (Exception ignored) {}
            }
        }

        try { new File(tempVideoPath).delete(); } catch (Exception ignored) {}

        if (isCancelled) return;

        callback.onProgress(95, "ফাইনালাইজ হচ্ছে...");

        File finalFile = new File(outputPath);
        if (!finalFile.exists() || finalFile.length() < 1000) {
            callback.onError("ফাইল তৈরি হয়নি");
            return;
        }

        // Apply Advanced Bypass (Metadata + Junk Data)
        if (ts.metadataScrubbingEnabled || ts.junkDataEnabled) {
            applyAdvancedBypass(finalFile, ts);
        }

        callback.onProgress(100, "✓ সম্পন্ন!");

        // Wait so UI can show 100%
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}

        callback.onComplete(outputPath);
    }

    private boolean needsAudioProcessing(TransformSettings ts, boolean faceAudioMixNeeded) {
        return ts.speedEnabled ||
                ts.volumeEnabled ||
                ts.pitchEnabled ||
                ts.spectralNoiseEnabled ||
                ts.ambientNoiseEnabled ||
                ts.trimEnabled ||
                faceAudioMixNeeded;
    }

    private void processAudioStable(Context context, Uri inputUri, String tempVideoPath, String finalOutputPath,
                                    int audioTrackIndex, MediaFormat audioFormat,
                                    long trimStartUs, float speedFactor, float volumeFactor,
                                    long maxDurationUs, ProgressCallback callback, TransformSettings ts,
                                    boolean hasFaceAudio, Uri faceUri,
                                    int faceAudioTrackIndex, MediaFormat faceAudioFormat,
                                    float initWeight, float videoWeight, float audioWeight, float finalizeWeight) {

        boolean useFaceAudio = hasFaceAudio && faceUri != null && faceAudioTrackIndex >= 0 && faceAudioFormat != null;

        if (shouldPassthroughAudio(ts, useFaceAudio)) {
            int audioStartPct = (int) ((initWeight + videoWeight) * 100);
            callback.onProgress(audioStartPct, "অডিও কপি হচ্ছে...");
            muxVideoWithOriginalAudio(tempVideoPath, context, inputUri, finalOutputPath, audioTrackIndex, trimStartUs, maxDurationUs);
            int audioDonePct = (int) ((initWeight + videoWeight + audioWeight) * 100);
            callback.onProgress(audioDonePct, "অডিও সম্পন্ন");
            return;
        }

        MediaExtractor audioExtractor = null;
        MediaCodec audioDecoder = null;
        MediaExtractor faceAudioExtractor = null;
        MediaCodec faceAudioDecoder = null;
        MediaCodec audioEncoder = null;
        MediaMuxer finalMuxer = null;
        MediaExtractor tempVideoExtractor = null;

        try {
            int audioStartPct = (int) ((initWeight + videoWeight) * 100);
            callback.onProgress(audioStartPct, "অডিও প্রসেসিং...");

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(context, inputUri, null);
            audioExtractor.selectTrack(audioTrackIndex);
            if (trimStartUs > 0) audioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            float mainVolume = useFaceAudio ? volumeFactor * 0.70f : volumeFactor;
            float faceVolume = useFaceAudio ? 1.0f : 0.0f;

            if (useFaceAudio) {
                try {
                    faceAudioExtractor = new MediaExtractor();
                    faceAudioExtractor.setDataSource(context, faceUri, null);
                    faceAudioExtractor.selectTrack(faceAudioTrackIndex);
                    if (trimStartUs > 0) faceAudioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    String faceMime = faceAudioFormat.getString(MediaFormat.KEY_MIME);
                    faceAudioDecoder = MediaCodec.createDecoderByType(faceMime);
                    faceAudioDecoder.configure(faceAudioFormat, null, null, 0);
                    faceAudioDecoder.start();
                } catch (Exception e) {
                    Log.e(TAG, "Face audio setup failed", e);
                    useFaceAudio = false;
                    faceVolume = 0f;
                    mainVolume = volumeFactor;
                }
            }

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

            tempVideoExtractor = new MediaExtractor();
            tempVideoExtractor.setDataSource(tempVideoPath);

            int tempVideoTrackIndex = -1;
            MediaFormat tempVideoFormat = null;
            for (int i = 0; i < tempVideoExtractor.getTrackCount(); i++) {
                MediaFormat f = tempVideoExtractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    tempVideoTrackIndex = i;
                    tempVideoFormat = f;
                    break;
                }
            }
            if (tempVideoTrackIndex < 0 || tempVideoFormat == null) {
                throw new RuntimeException("Temp video track missing");
            }

            finalMuxer = new MediaMuxer(finalOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideoTrack = finalMuxer.addTrack(tempVideoFormat);

            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            MediaFormat outAudioFormat = null;

            while (outAudioFormat == null && !isCancelled) {
                int status = audioEncoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outAudioFormat = audioEncoder.getOutputFormat();
                } else if (status >= 0) {
                    audioEncoder.releaseOutputBuffer(status, false);
                } else {
                    break;
                }
            }

            if (outAudioFormat == null) {
                outAudioFormat = encFmt;
            }

            int muxAudioTrack = finalMuxer.addTrack(outAudioFormat);
            finalMuxer.start();

            tempVideoExtractor.selectTrack(tempVideoTrackIndex);
            ByteBuffer vBuf = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
            while (!isCancelled) {
                vBuf.clear();
                int size = tempVideoExtractor.readSampleData(vBuf, 0);
                if (size < 0) break;
                vInfo.offset = 0;
                vInfo.size = size;
                vInfo.presentationTimeUs = tempVideoExtractor.getSampleTime();
                vInfo.flags = tempVideoExtractor.getSampleFlags();
                finalMuxer.writeSampleData(muxVideoTrack, vBuf, vInfo);
                tempVideoExtractor.advance();
            }

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo faceDecInfo = new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean decodeDone = false;
            boolean encodeDone = false;
            boolean faceInputDone = false;
            boolean faceDecodeDone = false;

            long totalSamplesEncoded = 0;
            int audioFrameCounter = 0;
            long lastAudioProgress = System.currentTimeMillis();

            short[] pendingFaceSamples = null;
            int faceBufferReadPos = 0;

            while (!encodeDone && !isCancelled) {
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

                if (useFaceAudio && !faceInputDone && faceAudioDecoder != null && faceAudioExtractor != null) {
                    int inIdx = faceAudioDecoder.dequeueInputBuffer(0);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = faceAudioDecoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            inBuf.clear();
                            int sz = faceAudioExtractor.readSampleData(inBuf, 0);
                            if (sz < 0) {
                                faceAudioDecoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                faceInputDone = true;
                            } else {
                                faceAudioDecoder.queueInputBuffer(inIdx, 0, sz, faceAudioExtractor.getSampleTime(), 0);
                                faceAudioExtractor.advance();
                            }
                        }
                    }
                }

                if (useFaceAudio && !faceDecodeDone && faceAudioDecoder != null) {
                    int outIdx = faceAudioDecoder.dequeueOutputBuffer(faceDecInfo, 0);
                    if (outIdx >= 0) {
                        ByteBuffer outBuf = faceAudioDecoder.getOutputBuffer(outIdx);
                        if (faceDecInfo.size > 0 && outBuf != null) {
                            outBuf.position(faceDecInfo.offset);
                            outBuf.limit(faceDecInfo.offset + faceDecInfo.size);
                            ShortBuffer sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            short[] newFace = new short[sb.remaining()];
                            sb.get(newFace);

                            if (pendingFaceSamples == null) {
                                pendingFaceSamples = newFace;
                                faceBufferReadPos = 0;
                            } else {
                                int remain = pendingFaceSamples.length - faceBufferReadPos;
                                short[] merged = new short[remain + newFace.length];
                                System.arraycopy(pendingFaceSamples, faceBufferReadPos, merged, 0, remain);
                                System.arraycopy(newFace, 0, merged, remain, newFace.length);
                                pendingFaceSamples = merged;
                                faceBufferReadPos = 0;
                            }
                        }
                        faceAudioDecoder.releaseOutputBuffer(outIdx, false);
                        if ((faceDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            faceDecodeDone = true;
                        }
                    }
                }

                if (!decodeDone) {
                    int outIdx = audioDecoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIdx >= 0) {
                        ByteBuffer outBuf = audioDecoder.getOutputBuffer(outIdx);

                        if (decInfo.size > 0 && outBuf != null) {
                            outBuf.position(decInfo.offset);
                            outBuf.limit(decInfo.offset + decInfo.size);
                            ShortBuffer sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            short[] samples = new short[sb.remaining()];
                            sb.get(samples);

                            // Apply main volume
                            for (int i = 0; i < samples.length; i++) {
                                float s = samples[i] * mainVolume;
                                samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) s));
                            }

                            // Mix face audio
                            if (useFaceAudio && pendingFaceSamples != null) {
                                int available = pendingFaceSamples.length - faceBufferReadPos;
                                int toMix = Math.min(samples.length, available);

                                for (int i = 0; i < toMix; i++) {
                                    float mixed = samples[i] + pendingFaceSamples[faceBufferReadPos + i] * faceVolume;
                                    samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) mixed));
                                }

                                faceBufferReadPos += toMix;
                                if (faceBufferReadPos >= pendingFaceSamples.length) {
                                    pendingFaceSamples = null;
                                    faceBufferReadPos = 0;
                                }
                            }

                            // Apply speed/resampling for sync
                            float audioEffectiveSpeed = speedFactor;

                            if (Math.abs(audioEffectiveSpeed - 1f) > 0.001f) {
                                samples = resampleAudio(samples, audioEffectiveSpeed, channelCount);
                            }

                            // Apply Advanced EQ
                            if (ts.audioEqEnabled) {
                                samples = applyAudioEQ(samples, ts.audioEqBass, ts.audioEqTreble, sampleRate);
                            }

                            // 34. Audio Phase Shifting
                            if (ts.audioPhaseShiftEnabled) {
                                for (int i = 0; i < samples.length; i++) {
                                    samples[i] = (short) (-samples[i]);
                                }
                            }

                            // Apply effects
                            if (ts.pitchEnabled && Math.abs(ts.pitch - 1f) > 0.005f) {
                                samples = applyPitchToSamples(samples, ts.pitch, channelCount);
                            }
                            if (ts.spectralNoiseEnabled && ts.spectralNoise > 0.0001f) {
                                samples = applySpectralNoise(samples, ts.spectralNoise, sampleRate);
                            }
                            if (ts.ambientNoiseEnabled && ts.ambientNoiseLevel > 0.0001f) {
                                samples = applyAmbientNoise(samples, ts.ambientNoiseLevel);
                            }

                            if (samples.length > 0) {
                                int inIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                                if (inIdx >= 0) {
                                    ByteBuffer inBuf = audioEncoder.getInputBuffer(inIdx);
                                    if (inBuf != null) {
                                        inBuf.clear();
                                        ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                                        pcm.asShortBuffer().put(samples);
                                        int writeLen = Math.min(pcm.array().length, inBuf.capacity());
                                        inBuf.put(pcm.array(), 0, writeLen);
                                        
                                        long outPts = (totalSamplesEncoded * 1_000_000L) / (sampleRate * channelCount);
                                        audioEncoder.queueInputBuffer(inIdx, 0, writeLen, outPts, 0);
                                        totalSamplesEncoded += (writeLen / 2);
                                        audioFrameCounter++;

                                        if (audioFrameCounter % 20 == 0 || System.currentTimeMillis() - lastAudioProgress > 400) {
                                            double audioRatio = (maxDurationUs > 0) ? (double) outPts / maxDurationUs : 0.0;
                                            audioRatio = Math.max(0.0, Math.min(1.0, audioRatio));

                                            int audioPct = (int) ((initWeight + videoWeight) * 100 + audioWeight * 100 * audioRatio);
                                            int maxAudioPct = (int) ((initWeight + videoWeight + audioWeight) * 100);
                                            audioPct = Math.max((int) ((initWeight + videoWeight) * 100), Math.min(audioPct, maxAudioPct));
                                            
                            callback.onProgress(audioPct, "অডিও:" + audioFrameCounter);
                            audioFrameCounter++;
                                            lastAudioProgress = System.currentTimeMillis();
                                        }
                                    }
                                }
                            }
                        }

                        audioDecoder.releaseOutputBuffer(outIdx, false);

                        if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decodeDone = true;
                            int eosIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
                            if (eosIdx >= 0) {
                                audioEncoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                    }
                }

                while (true) {
                    int encIdx = audioEncoder.dequeueOutputBuffer(encInfo, 0);
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    } else if (encIdx >= 0) {
                        ByteBuffer buf = audioEncoder.getOutputBuffer(encIdx);
                        if (buf != null && encInfo.size > 0 &&
                                (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(encInfo.offset);
                            buf.limit(encInfo.offset + encInfo.size);
                            finalMuxer.writeSampleData(muxAudioTrack, buf, encInfo);
                        }
                        audioEncoder.releaseOutputBuffer(encIdx, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encodeDone = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Audio processing error", e);
            muxVideoWithOriginalAudio(tempVideoPath, context, inputUri, finalOutputPath, audioTrackIndex, trimStartUs, maxDurationUs);
            return;
        } finally {
            try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); } } catch (Exception ignored) {}
            try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
            try { if (faceAudioDecoder != null) { faceAudioDecoder.stop(); faceAudioDecoder.release(); } } catch (Exception ignored) {}
            try { if (faceAudioExtractor != null) faceAudioExtractor.release(); } catch (Exception ignored) {}
            try { if (tempVideoExtractor != null) tempVideoExtractor.release(); } catch (Exception ignored) {}
            try { if (finalMuxer != null) { finalMuxer.stop(); finalMuxer.release(); } } catch (Exception ignored) {}
        }
    }

    private void muxVideoWithOriginalAudio(String tempVideoPath, Context context, Uri inputUri,
                                           String finalOutputPath, int audioTrackIndex,
                                           long trimStartUs, long maxDurationUs) {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(tempVideoPath);

            int vTrackIndex = -1;
            MediaFormat vFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat f = videoExtractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    vTrackIndex = i;
                    vFormat = f;
                    break;
                }
            }

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(context, inputUri, null);
            audioExtractor.selectTrack(audioTrackIndex);
            if (trimStartUs > 0) audioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            MediaFormat aFormat = audioExtractor.getTrackFormat(audioTrackIndex);

            muxer = new MediaMuxer(finalOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVTrack = muxer.addTrack(vFormat);
            int outATrack = muxer.addTrack(aFormat);
            muxer.start();

            videoExtractor.selectTrack(vTrackIndex);

            ByteBuffer vBuf = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
            while (!isCancelled) {
                vBuf.clear();
                int size = videoExtractor.readSampleData(vBuf, 0);
                if (size < 0) break;
                vInfo.offset = 0;
                vInfo.size = size;
                vInfo.presentationTimeUs = videoExtractor.getSampleTime();
                vInfo.flags = videoExtractor.getSampleFlags();
                muxer.writeSampleData(outVTrack, vBuf, vInfo);
                videoExtractor.advance();
            }

            ByteBuffer aBuf = ByteBuffer.allocateDirect(256 * 1024);
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
            long audioFirstPts = -1;
            while (!isCancelled) {
                aBuf.clear();
                int size = audioExtractor.readSampleData(aBuf, 0);
                if (size < 0) break;

                long pts = audioExtractor.getSampleTime();
                if (audioFirstPts < 0) audioFirstPts = pts;
                long normalizedPts = pts - audioFirstPts;
                if (maxDurationUs > 0 && normalizedPts > maxDurationUs) break;

                aInfo.offset = 0;
                aInfo.size = size;
                aInfo.presentationTimeUs = normalizedPts;
                aInfo.flags = audioExtractor.getSampleFlags();
                muxer.writeSampleData(outATrack, aBuf, aInfo);
                audioExtractor.advance();
            }

        } catch (Exception e) {
            Log.e(TAG, "muxVideoWithOriginalAudio error", e);
            muxTempVideoOnly(tempVideoPath, finalOutputPath);
        } finally {
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private void muxTempVideoOnly(String tempVideoPath, String outputPath) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(tempVideoPath);

            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = f;
                    break;
                }
            }

            if (videoTrack < 0 || format == null) return;

            extractor.selectTrack(videoTrack);
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(format);
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!isCancelled) {
                buf.clear();
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outTrack, buf, info);
                extractor.advance();
            }

        } catch (Exception e) {
            Log.e(TAG, "muxTempVideoOnly error", e);
        } finally {
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private boolean awaitNewFrame(Object syncObj, boolean[] available) {
        synchronized (syncObj) {
            long deadline = System.currentTimeMillis() + FRAME_WAIT_TIMEOUT_MS;
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

    private long advanceFaceVideoToTime(
            MediaCodec faceDecoder,
            SurfaceTexture faceSurfaceTexture,
            Object faceSyncObject,
            boolean[] faceFrameAvailable,
            float[] faceStMatrix,
            long targetPtsUs,
            long currentFacePts,
            long[] faceFirstPtsHolder,
            boolean[] faceOutputDoneHolder) {

        if (faceDecoder == null || faceSurfaceTexture == null || faceOutputDoneHolder[0]) {
            return currentFacePts;
        }

        while (!faceOutputDoneHolder[0]) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx = faceDecoder.dequeueOutputBuffer(info, 0);

            if (outIdx < 0) {
                break;
            }

            boolean doRender = info.size != 0;
            long rawPts = info.presentationTimeUs;

            faceDecoder.releaseOutputBuffer(outIdx, doRender);

            if (doRender) {
                boolean gotFace = awaitNewFrame(faceSyncObject, faceFrameAvailable);
                if (gotFace) {
                    faceSurfaceTexture.updateTexImage();
                    faceSurfaceTexture.getTransformMatrix(faceStMatrix);

                    if (faceFirstPtsHolder[0] < 0) {
                        faceFirstPtsHolder[0] = rawPts;
                    }

                    currentFacePts = rawPts - faceFirstPtsHolder[0];

                    if (currentFacePts >= targetPtsUs) {
                        break;
                    }
                }
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                faceOutputDoneHolder[0] = true;
                break;
            }
        }

        return currentFacePts;
    }

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
        fs.append("uniform int uFlipEnabled;\n");
        fs.append("uniform int uLogoRemoval;\n");
        fs.append("uniform vec4 uLogoRect;\n");
        fs.append("uniform int uLogoMethod;\n");
        fs.append("uniform float uTotalDuration;\n");
        fs.append("uniform float uCurrentTime;\n");
        fs.append("uniform samplerExternalOES sFaceTexture;\n");
        fs.append("uniform int uHasFaceVideo;\n");
        fs.append("uniform vec4 uFaceRect;\n");
        fs.append("uniform float uFaceCornerRadius;\n");

        fs.append("vec3 rgb2hsv(vec3 c){");
        fs.append("vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);");
        fs.append("vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));");
        fs.append("vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));");
        fs.append("float d=q.x-min(q.w,q.y); float e=1.0e-10;");
        fs.append("return vec3(abs(q.z+(q.w-q.y)/(6.0*d+e)),d/(q.x+e),q.x);}\n");

        fs.append("vec3 hsv2rgb(vec3 c){");
        fs.append("vec4 K=vec4(1.0,2.0/3.0,1.0/3.0,3.0);");
        fs.append("vec3 p=abs(fract(c.xxx+K.xyz)*6.0-K.www);");
        fs.append("return c.z*mix(K.xxx,clamp(p-K.xxx,0.0,1.0),c.y);}\n");

        fs.append("void main(){");
        fs.append("vec2 uv=vTextureCoord;");

        // 1. Sub-pixel Jitter (Micro-shake) - Version 7.0
        if (ts.subPixelJitterEnabled) {
            fs.append("float jitterX = sin(uCurrentTime * 15.0) * ").append(ts.jitterStrength / 10000.0f).append(";");
            fs.append("float jitterY = cos(uCurrentTime * 17.0) * ").append(ts.jitterStrength / 10000.0f).append(";");
            fs.append("uv += vec2(jitterX, jitterY);");
        }

        boolean needsCrop = (cropScaleX < 0.999f || cropScaleY < 0.999f);
        if (needsCrop) {
            fs.append("uv=uv*vec2(").append(String.format(Locale.US, "%.6f", cropScaleX))
                    .append(",").append(String.format(Locale.US, "%.6f", cropScaleY)).append(");");
            fs.append("uv=uv+vec2(").append(String.format(Locale.US, "%.6f", cropOffsetX))
                    .append(",").append(String.format(Locale.US, "%.6f", cropOffsetY)).append(");");
        }

        if (ts.barrelEnabled && ts.barrel > 0.01f) {
            // Auto-zoom to compensate for barrel distortion black edges
            float autoZoom = 1.0f + (ts.barrel * 0.25f);
            fs.append("uv = (uv - 0.5) / ").append(autoZoom).append(" + 0.5;");
            fs.append("{vec2 p=uv-0.5; float r2=dot(p,p); p*=(1.0+").append(ts.barrel).append("*r2); uv=p+0.5;");
            fs.append("if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0); return;}}");
        }

        if (ts.rotateEnabled && Math.abs(ts.rotate) > 0.01f) {
            double rad = Math.toRadians(ts.rotate);
            float cosR = (float) Math.cos(rad), sinR = (float) Math.sin(rad);
            fs.append("uv-=0.5;");
            fs.append("uv=vec2(uv.x*").append(cosR).append("-uv.y*").append(sinR)
                    .append(",uv.x*").append(sinR).append("+uv.y*").append(cosR).append(");");
            fs.append("uv+=0.5;");
        }

        if (ts.zoomEnabled && ts.zoom > 1.001f) {
            fs.append("uv=(uv-0.5)/").append(Math.min(ts.zoom, 3f)).append("+0.5;");
        }

        if (ts.pixelShiftEnabled && ts.pixelShift != 0) {
            float sx = ts.pixelShift / (float) outW;
            float sy = ts.pixelShift / (float) outH;
            fs.append("uv+=vec2(").append(sx).append(",").append(sy).append(");");
        }

        fs.append("uv=clamp(uv,0.001,0.999);");

        if (ts.chromaticEnabled && ts.chromatic > 0.3f) {
            float offX = ts.chromatic / outW;
            fs.append("float cR=texture2D(sTexture,uv-vec2(").append(offX).append(",0.0)).r;");
            fs.append("float cG=texture2D(sTexture,uv).g;");
            fs.append("float cB=texture2D(sTexture,uv+vec2(").append(offX).append(",0.0)).b;");
            fs.append("vec4 color=vec4(cR,cG,cB,1.0);");
        } else {
            fs.append("vec4 color=texture2D(sTexture,uv);");
        }

        fs.append("if(uLogoRemoval==1){");
        fs.append("vec2 fragNorm=vec2(gl_FragCoord.x/").append((float) outW).append(",1.0-gl_FragCoord.y/").append((float) outH).append(");");
        fs.append("if(fragNorm.x>=uLogoRect.x&&fragNorm.x<=uLogoRect.z&&fragNorm.y>=uLogoRect.y&&fragNorm.y<=uLogoRect.w){");
        switch (ts.logoRemovalMethod) {
            case 0:
                float blurStep = Math.min(ts.removalBlurIntensity / outW, 0.05f);
                fs.append("vec3 blurred=vec3(0.0);");
                fs.append("for(int dx=-3;dx<=3;dx++){for(int dy=-3;dy<=3;dy++){");
                fs.append("vec2 offset=vec2(float(dx),float(dy))*").append(blurStep).append(";");
                fs.append("blurred+=texture2D(sTexture,clamp(uv+offset,0.001,0.999)).rgb;}}");
                fs.append("color.rgb=blurred/49.0;");
                break;
            case 1:
                fs.append("color.rgb=vec3(0.0);");
                break;
            case 2:
                int pixelSize = 16;
                float pxW = (float) pixelSize / outW;
                float pxH = (float) pixelSize / outH;
                fs.append("vec2 pixelUV=floor(uv/vec2(").append(pxW).append(",").append(pxH).append("))*vec2(")
                        .append(pxW).append(",").append(pxH).append(")+vec2(").append(pxW / 2).append(",").append(pxH / 2).append(");");
                fs.append("color.rgb=texture2D(sTexture,clamp(pixelUV,0.001,0.999)).rgb;");
                break;
        }
        fs.append("}}");

        if (ts.brightEnabled && Math.abs(ts.bright - 1f) > 0.001f) {
            fs.append("color.rgb*=").append(ts.bright).append(";");
        }

        // 33. Dynamic Luma Pulse (Ultimate Bypass)
        if (ts.lumaPulseEnabled) {
            fs.append("float pulse = 1.0 + sin(uCurrentTime * 10.0) * ").append(ts.lumaPulseIntensity / 1000.0f).append(";");
            fs.append("color.rgb *= pulse;");
        }
        if (ts.satEnabled && Math.abs(ts.saturation - 1f) > 0.001f) {
            fs.append("float lum=dot(color.rgb,vec3(0.2126,0.7152,0.0722));");
            fs.append("color.rgb=mix(vec3(lum),color.rgb,").append(ts.saturation).append(");");
        }
        if (ts.hueEnabled && Math.abs(ts.hue) > 0.1f) {
            float hueShift = ts.hue / 360f;
            fs.append("vec3 hsv=rgb2hsv(color.rgb); hsv.x=fract(hsv.x+").append(hueShift).append("); color.rgb=hsv2rgb(hsv);");
        }
        if (ts.gammaEnabled && Math.abs(ts.gamma - 1f) > 0.001f) {
            float invG = 1f / ts.gamma;
            fs.append("color.rgb=pow(max(color.rgb,0.0),vec3(").append(invG).append("));");
        }
        if (ts.sepiaEnabled && ts.sepia > 0.001f) {
            fs.append("vec3 sep;");
            fs.append("sep.r=dot(color.rgb,vec3(0.393,0.769,0.189));");
            fs.append("sep.g=dot(color.rgb,vec3(0.349,0.686,0.168));");
            fs.append("sep.b=dot(color.rgb,vec3(0.272,0.534,0.131));");
            fs.append("color.rgb=mix(color.rgb,sep,").append(ts.sepia).append(");");
        }
        if (ts.tintEnabled && ts.tint > 0.001f) {
            float r = ((ts.tintColor >> 16) & 0xFF) / 255f;
            float g = ((ts.tintColor >> 8) & 0xFF) / 255f;
            float b = (ts.tintColor & 0xFF) / 255f;
            fs.append("color.rgb=mix(color.rgb,vec3(").append(r).append(",").append(g).append(",").append(b).append("),").append(ts.tint).append(");");
        }
        if (ts.sharpenEnabled && ts.sharpen > 0.001f) {
            float stepX = 1f / outW;
            float stepY = 1f / outH;
            fs.append("vec3 sblur=texture2D(sTexture,uv+vec2(-").append(stepX).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(stepX).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,-").append(stepY).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,").append(stepY).append(")).rgb;");
            fs.append("sblur*=0.25;");
            fs.append("color.rgb+=(color.rgb-sblur)*").append(ts.sharpen).append(";");
        }
        if (ts.blurEnabled && ts.blur > 0.01f) {
            float blurOff = ts.blur / outW;
            fs.append("vec3 blurred=texture2D(sTexture,uv+vec2(-").append(blurOff).append(",-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",-").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(-").append(blurOff).append(",0.0)).rgb+");
            fs.append("color.rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",0.0)).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(-").append(blurOff).append(",").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(0.0,").append(blurOff).append(")).rgb+");
            fs.append("texture2D(sTexture,uv+vec2(").append(blurOff).append(",").append(blurOff).append(")).rgb;");
            fs.append("color.rgb=blurred/9.0;");
        }
        if (ts.noiseEnabled && ts.noise > 0.0001f) {
            fs.append("float n=fract(sin(dot(uv,vec2(12.9898,78.233)))*43758.5453);");
            fs.append("color.rgb+=(n-0.5)*").append(ts.noise).append(";");
        }
        if (ts.vignetteEnabled && ts.vignette > 0.001f) {
            fs.append("vec2 vc=uv-0.5;");
            fs.append("float vf=1.0-dot(vc,vc)*").append(ts.vignette * 2f).append(";");
            fs.append("color.rgb*=clamp(vf,0.0,1.0);");
        }
        if (ts.borderEnabled && ts.border > 0) {
            float bx = ts.border / (float) outW;
            float by = ts.border / (float) outH;
            fs.append("if(uv.x<").append(bx).append("||uv.x>").append(1f - bx)
                    .append("||uv.y<").append(by).append("||uv.y>").append(1f - by).append(") color.rgb=vec3(0.0);");
        }

        fs.append("color.rgb=clamp(color.rgb,0.0,1.0);");

        if (ts.borderProgressEnabled && ts.borderProgressSize > 0) {
            float bpSizeX = ts.borderProgressSize / (float) outW;
            float bpSizeY = ts.borderProgressSize / (float) outH;
            float bpR = ((ts.borderProgressColor >> 16) & 0xFF) / 255f;
            float bpG = ((ts.borderProgressColor >> 8) & 0xFF) / 255f;
            float bpB = (ts.borderProgressColor & 0xFF) / 255f;

            fs.append("{");
            fs.append("float progress=clamp(uCurrentTime/max(uTotalDuration,0.01),0.0,1.0);");
            fs.append("float bpx=").append(String.format(Locale.US, "%.6f", bpSizeX)).append(";");
            fs.append("float bpy=").append(String.format(Locale.US, "%.6f", bpSizeY)).append(";");
            fs.append("float totalProgress=progress*4.0;");
            fs.append("bool hit=false;");
            fs.append("vec2 sc=vTextureCoord;");
            fs.append("if(sc.y<=bpy&&sc.x<=clamp(totalProgress,0.0,1.0)) hit=true;");
            fs.append("if(sc.x>=(1.0-bpx)&&totalProgress>=1.0&&sc.y<=clamp(totalProgress-1.0,0.0,1.0)) hit=true;");
            fs.append("if(sc.y>=(1.0-bpy)&&totalProgress>=2.0&&sc.x>=(1.0-clamp(totalProgress-2.0,0.0,1.0))) hit=true;");
            fs.append("if(sc.x<=bpx&&totalProgress>=3.0&&sc.y>=(1.0-clamp(totalProgress-3.0,0.0,1.0))) hit=true;");
            fs.append("if(hit) color.rgb=mix(color.rgb,vec3(")
                    .append(String.format(Locale.US, "%.3f", bpR)).append(",")
                    .append(String.format(Locale.US, "%.3f", bpG)).append(",")
                    .append(String.format(Locale.US, "%.3f", bpB)).append("),0.85);");
            fs.append("}");
        }

        fs.append("color.rgb=clamp(color.rgb,0.0,1.0);");

        fs.append("if(uHasFaceVideo==1){");
        fs.append("vec2 screenUV=vec2(gl_FragCoord.x/").append((float) outW).append(",1.0-gl_FragCoord.y/").append((float) outH).append(");");
        fs.append("float faceX=uFaceRect.x; float faceY=uFaceRect.y; float faceW=uFaceRect.z; float faceH=uFaceRect.w;");
        fs.append("if(screenUV.x>=faceX&&screenUV.x<=faceX+faceW&&screenUV.y>=faceY&&screenUV.y<=faceY+faceH){");
        fs.append("vec2 localUV=(screenUV-vec2(faceX,faceY))/vec2(faceW,faceH);");
        fs.append("vec2 center=vec2(0.5,0.5);");
        fs.append("vec2 fromCenter=abs(localUV-center);");
        fs.append("float cornerR=uFaceCornerRadius;");
        fs.append("float dist=length(max(fromCenter-(0.5-cornerR),0.0))-cornerR;");
        fs.append("if(dist<=0.0){");
        fs.append("vec2 faceTexCoord=vec2(localUV.x,localUV.y);");
        fs.append("vec4 faceColor=texture2D(sFaceTexture,faceTexCoord);");
        fs.append("float edgeSmooth=1.0-smoothstep(-0.02,0.005,dist);");
        fs.append("color.rgb=mix(color.rgb,faceColor.rgb,edgeSmooth);");
        fs.append("}}}");

        fs.append("if(uHasWatermark==1){");
        fs.append("vec2 wmCoord=vTextureCoord;");
        fs.append("if(uFlipEnabled==1) wmCoord.x=1.0-wmCoord.x;");
        fs.append("vec4 wm=texture2D(sWatermark,wmCoord);");
        fs.append("color.rgb=mix(color.rgb,wm.rgb,wm.a);");
        fs.append("}");

        fs.append("gl_FragColor=color;}");
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

    private short[] applyAudioEQ(short[] samples, float bassGain, float trebleGain, int sampleRate) {
        short[] output = new short[samples.length];
        
        // Simple IIR filters for Bass (Low-shelf) and Treble (High-shelf)
        float dt = 1.0f / sampleRate;
        float bassRC = 1.0f / (2.0f * (float) Math.PI * 250.0f); // 250Hz shelf
        float bassAlpha = dt / (bassRC + dt);
        
        float trebleRC = 1.0f / (2.0f * (float) Math.PI * 4000.0f); // 4kHz shelf
        float trebleAlpha = trebleRC / (trebleRC + dt);
        
        float lastLow = 0;
        float lastHigh = 0;
        float lastInput = 0;

        for (int i = 0; i < samples.length; i++) {
            float in = samples[i];
            
            // Bass boost/cut
            float low = lastLow + bassAlpha * (in - lastLow);
            lastLow = low;
            float bassComponent = low * (bassGain - 1.0f);
            
            // Treble boost/cut
            float high = trebleAlpha * (lastHigh + in - lastInput);
            lastInput = in;
            lastHigh = high;
            float trebleComponent = high * (trebleGain - 1.0f);
            
            float out = in + bassComponent + trebleComponent;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) out));
        }
        return output;
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

    private short[] resampleAudio(short[] input, float speedFactor, int channels) {
        if (Math.abs(speedFactor - 1f) < 0.001f) return input;
        int inFrames = input.length / channels;
        // Calculate output frames more precisely
        int outFrames = (int) Math.round(inFrames / speedFactor);
        if (outFrames <= 0) return new short[0];

        short[] output = new short[outFrames * channels];
        // Calculate a precise step to cover the entire input range
        double step = (double)(inFrames - 1) / (outFrames > 1 ? outFrames - 1 : 1);
        double pos = 0.0;
        
        for (int outF = 0; outF < outFrames; outF++) {
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, inFrames - 1);
            float frac = (float)(pos - i0);
            
            for (int ch = 0; ch < channels; ch++) {
                int idx0 = i0 * channels + ch;
                int idx1 = i1 * channels + ch;
                
                // Safety check for array bounds
                if (idx0 < input.length && idx1 < input.length) {
                    short s0 = input[idx0];
                    short s1 = input[idx1];
                    output[outF * channels + ch] = (short) (s0 + frac * (s1 - s0));
                }
            }
            pos += step;
        }
        return output;
    }

    private Bitmap createWatermarkBitmapOptimized(WatermarkConfig wm, int w, int h, long currentTimeUs) {
        if (wm == null || !wm.isActive()) return null;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int pad = 20;
        Bitmap logo = wm.logoBitmap != null ? wm.logoBitmap : WatermarkConfig.sharedLogoBitmap;
        WatermarkConfig.Position currentLogoPos = wm.getDynamicPosition(currentTimeUs);
        WatermarkConfig.Position currentTextPos = wm.dynamicPosition ? wm.getDynamicPosition(currentTimeUs + 500_000) : wm.textPosition;

        if ((wm.mode == WatermarkConfig.Mode.LOGO || wm.mode == WatermarkConfig.Mode.BOTH) &&
                logo != null && !logo.isRecycled()) {
            int lW = Math.max(1, w * wm.logoSize / 100);
            int lH = Math.max(1, Math.round((float) lW * logo.getHeight() / logo.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(logo, lW, lH, true);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(Math.round(wm.logoOpacity / 100f * 255));
            canvas.drawBitmap(scaled, calcX(currentLogoPos, lW, w, pad), calcY(currentLogoPos, lH, h, pad), p);
            if (scaled != logo) scaled.recycle();
        }

        if ((wm.mode == WatermarkConfig.Mode.TEXT || wm.mode == WatermarkConfig.Mode.BOTH) &&
                wm.text != null && !wm.text.trim().isEmpty()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(wm.fontSize);
            p.setTypeface(wm.textStyle == WatermarkConfig.TextStyle.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float tw = p.measureText(wm.text);
            int extraY = (wm.mode == WatermarkConfig.Mode.BOTH && logo != null && currentLogoPos == currentTextPos)
                    ? h * wm.logoSize / 100 + 10 : 0;
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

    private void applyAdvancedBypass(File file, TransformSettings ts) {
        if (file == null || !file.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            // 1. Metadata Scrubbing (Atom level cleaning)
            if (ts.metadataScrubbingEnabled) {
                long fileLength = raf.length();
                byte[] buffer = new byte[8];
                long pos = 0;

                // Simple MP4 atom crawler to find and blank out common metadata atoms
                while (pos < fileLength - 8) {
                    raf.seek(pos);
                    int read = raf.read(buffer);
                    if (read < 8) break;

                    long atomSize = ((long) (buffer[0] & 0xFF) << 24) |
                                    ((long) (buffer[1] & 0xFF) << 16) |
                                    ((long) (buffer[2] & 0xFF) << 8)  |
                                    ((long) (buffer[3] & 0xFF));
                    String atomType = new String(buffer, 4, 4);

                    if (atomSize < 8) break;

                    // Blank out metadata atoms like 'udta', 'meta', '©day', etc.
                    if (atomType.equals("udta") || atomType.equals("meta") || atomType.equals("free")) {
                        raf.seek(pos + 4);
                        raf.write(new byte[]{0, 0, 0, 0}); // Corrupt the type so it's ignored
                    }

                    pos += atomSize;
                }
            }

            // 2. Junk Data Injection (Append random bytes at the end)
            if (ts.junkDataEnabled) {
                raf.seek(raf.length());
                int junkSize = 1024 + random.nextInt(4096); // 1KB to 5KB
                byte[] junk = new byte[junkSize];
                random.nextBytes(junk);
                raf.write(junk);
            }
        } catch (Exception e) {
            Log.e(TAG, "Advanced bypass failed", e);
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

    private boolean shouldPassthroughAudio(TransformSettings ts, boolean faceAudioMixNeeded) {
        return !needsAudioProcessing(ts, faceAudioMixNeeded);
    }
}

