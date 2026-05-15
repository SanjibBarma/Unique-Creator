package com.example.uniquecreator.helper;

import android.graphics.Rect;

import java.io.Serializable;

/**
 * Complete transformation settings with 35 features for maximum copyright bypass
 * Version: 7.0 (Ultimate Bypass)
 * Updated: January 2025
 */
public class TransformSettings implements Serializable {

    private static final long serialVersionUID = 7L; // ★ Updated for Advanced Bypass

    // ════════════════════════════════════════════════════════════════════
    // ADVANCED BYPASS FEATURES (27-35)
    // ════════════════════════════════════════════════════════════════════

    // 27. Aspect Distortion (subtle stretch)
    public boolean aspectDistortionEnabled = true;
    public float aspectDistortionX = 1.008f; // Reduced from 1.015
    public float aspectDistortionY = 0.992f; // Reduced from 0.985

    // 28. Audio EQ Shifting (bass/treble balance)
    public boolean audioEqEnabled = true;
    public float audioEqBass = 1.15f;    // 0.8 - 1.5
    public float audioEqTreble = 1.05f;  // 0.8 - 1.5

    // 29. Metadata Scrubbing (clean file info)
    public boolean metadataScrubbingEnabled = true;

    // 30. Junk Data Injection (unique file hash)
    public boolean junkDataEnabled = true;

    // ════════════════════════════════════════════════════════════════════
    // ULTIMATE BYPASS FEATURES (31-35) - Version 7.0
    // ════════════════════════════════════════════════════════════════════

    // 31. 3D Perspective Warp (Structural Tilt)
    public boolean perspective3DEnabled = true;
    public float perspectiveTiltX = 1.2f; 
    public float perspectiveTiltY = 0.8f; 

    // 32. Temporal Variable Speed (VFR Oscillation)
    public boolean variableSpeedEnabled = true;
    public float variableSpeedIntensity = 1.5f; // 1.5% in UI

    // 33. Dynamic Luma/Chroma Pulse (Subtle Oscillation)
    public boolean lumaPulseEnabled = true;
    public float lumaPulseIntensity = 6.0f; // 0.6% in UI (stored as 6.0)

    // 34. Audio Phase Shifting (Waveform Inversion)
    public boolean audioPhaseShiftEnabled = true;

    // 35. Sub-Pixel Jitter (Micro Shake)
    public boolean subPixelJitterEnabled = true;
    public float jitterStrength = 4.0f; // Strength level 4 in UI

    // ════════════════════════════════════════════════════════════════════
    // ORIGINAL 21 TRANSFORMATIONS
    // ════════════════════════════════════════════════════════════════════

    // 1. Flip (horizontal mirror)
    public boolean flipEnabled = true;

    // 2. Speed (playback rate)
    public boolean speedEnabled = true;
    public float speed = 1.01f; // Updated for stepSize 0.01 compatibility (from 0.8)

    // 3. Hue shift (color rotation)
    public boolean hueEnabled = true;
    public float hue = 2.0f; // Reduced from 5.0

    // 4. Brightness (luminance multiplier)
    public boolean brightEnabled = true;
    public float bright = 1.03f; // 0.5 - 2.0

    // 5. Saturation (color intensity)
    public boolean satEnabled = true;
    public float saturation = 1.05f; // 0.0 - 2.0

    // 6. Zoom (scale from center)
    public boolean zoomEnabled = true;
    public float zoom = 1.02f; // Reduced from 1.03

    // 7. Rotation (slight angle)
    public boolean rotateEnabled = true;
    public float rotate = 0.3f; // Reduced from 0.5

    // 8. Gamma (contrast curve)
    public boolean gammaEnabled = true;
    public float gamma = 1.02f; // 0.5 - 2.0

    // 9. Noise / Film grain
    public boolean noiseEnabled = true;
    public float noise = 0.015f; // 0.005 - 0.05

    // 10. Vignette (edge darkening)
    public boolean vignetteEnabled = true;
    public float vignette = 0.2f; // 0.0 - 1.0

    // 11. Border (black frame)
    public boolean borderEnabled = true;
    public int border = 4; // px (0 - 50)

    // 12. Tint (color overlay)
    public boolean tintEnabled = true;
    public float tint = 0.03f; // 0.0 - 0.5
    public int tintColor = 0xFF4A00E0; // Purple

    // 13. Pixel shift (chromatic offset)
    public boolean pixelShiftEnabled = true;
    public int pixelShift = 2; // px (-10 to +10)

    // 14. Sharpen (edge enhancement)
    public boolean sharpenEnabled = true;
    public float sharpen = 0.2f; // 0.0 - 1.0

    // 15. Trim start (cut beginning)
    public boolean trimEnabled = true;
    public float trim = 0.3f; // seconds

    // 16. Volume (audio level)
    public boolean volumeEnabled = true;
    public float volume = 0.97f; // 0.0 - 3.0

    // 17. Blur (slight softening)
    public boolean blurEnabled = true;
    public float blur = 0.3f; // px radius

    // 18. Sepia (vintage tone)
    public boolean sepiaEnabled = true;
    public float sepia = 0.05f; // 0.0 - 1.0

    // 19. Chromatic Aberration (RGB channel split)
    public boolean chromaticEnabled = true;
    public float chromatic = 1.1f; // px RGB shift (0.0 - 5.0)

    // 20. Barrel Distortion (lens curve effect)
    public boolean barrelEnabled = true;
    public float barrel = 0.045f; // 0.02 - 0.08

    // 21. Audio Pitch Shift (independent of speed)
    public boolean pitchEnabled = true;
    public float pitch = 1.012f; // Compatible with stepSize 0.002 (from 0.98)

    // ════════════════════════════════════════════════════════════════════
    // ENHANCEMENTS (22-26)
    // ════════════════════════════════════════════════════════════════════

    // 22. Audio Spectral Noise (high-frequency masking)
    public boolean spectralNoiseEnabled = true;
    public float spectralNoise = 0.001f; // Reduced from 0.002

    // 23. Temporal Jitter (random frame drop/duplicate)
    public boolean temporalJitterEnabled = true;
    public float jitterIntensity = 0.02f; // 0.0 - 0.1 (2% = subtle)

    // 24. Ambient Background Noise (room tone simulation)
    public boolean ambientNoiseEnabled = true;
    public float ambientNoiseLevel = 0.003f; // Reduced from 0.005

    // 25. Bitrate Randomization (encoding fingerprint)
    public boolean bitrateRandomEnabled = true;
    public float bitrateVariation = 0.15f; // ±15% variation

    // 26. Border Progress (duration-based animated border)
    public boolean borderProgressEnabled = true;
    public int borderProgressSize = 4; // px (2 - 20) - border thickness
    public int borderProgressColor = 0xFF00FF80; // ★ NEW: Green-cyan default color

    // ════════════════════════════════════════════════════════════════════
    // ★★★ LOGO REMOVER (MANUAL ONLY) ★★★
    // ════════════════════════════════════════════════════════════════════

    // Enable manual region specification
    public boolean useManualRegion = false;

    // Manual coordinates (pixels from top-left)
    public int manualX = 0;
    public int manualY = 0;
    public int manualWidth = 100;
    public int manualHeight = 100;

    // Removal method:
    // 0 = Blur (gaussian blur over logo area)
    // 1 = Blackout (solid black box)
    // 2 = Pixelate (mosaic effect)
    public int logoRemovalMethod = 0;

    // Blur intensity (used only when logoRemovalMethod = 0)
    public float removalBlurIntensity = 15f; // 5.0 - 50.0

    // ════════════════════════════════════════════════════════════════════
    // LEGACY COMPATIBILITY (Deprecated but kept for backward compat)
    // ════════════════════════════════════════════════════════════════════

    @Deprecated
    public boolean logoRemoverEnabled = false; // Use useManualRegion instead

    @Deprecated
    public int removalX = 0; // Use manualX instead

    @Deprecated
    public int removalY = 0; // Use manualY instead

    @Deprecated
    public int removalWidth = 100; // Use manualWidth instead

    @Deprecated
    public int removalHeight = 100; // Use manualHeight instead

    @Deprecated
    public int removalMethod = 0; // Use logoRemovalMethod instead

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if any logo removal is enabled
     */
    public boolean isLogoRemovalEnabled() {
        return useManualRegion || logoRemoverEnabled;
    }

    /**
     * Get the active removal coordinates
     * Priority: Manual → Legacy
     */
    public Rect getRemovalRect() {
        if (useManualRegion) {
            return new Rect(manualX, manualY, manualX + manualWidth, manualY + manualHeight);
        }
        if (logoRemoverEnabled) {
            return new Rect(removalX, removalY, removalX + removalWidth, removalY + removalHeight);
        }
        return null;
    }

    /**
     * Sync legacy fields with new structure (for backward compatibility)
     */
    public void syncLegacyFields() {
        if (logoRemoverEnabled && !useManualRegion) {
            // Migrate old settings to new structure
            useManualRegion = true;
            manualX = removalX;
            manualY = removalY;
            manualWidth = removalWidth;
            manualHeight = removalHeight;
            logoRemovalMethod = removalMethod;
        }
    }


    // ════════════════════════════════════════════════════════════════════
// ★★★ REACTION FACE OVERLAY ★★★
// ════════════════════════════════════════════════════════════════════

    // Enable reaction face overlay
    public boolean reactionFaceEnabled = false;

    // Enable reaction face audio
    public boolean reactionFaceAudioEnabled = true;

    // Face video URI (stored as String for Serializable)
    public String reactionFaceUri = null;

    // Position: 0=TopLeft, 1=TopRight, 2=BottomLeft, 3=BottomRight
    public int reactionFacePosition = 0;

    // Size as percentage of video width (10-50%)
    public int reactionFaceSize = 100;

    // Corner radius percentage (0=square, 50=circle)
    public int reactionFaceCornerRadius = 100;

    // ════════════════════════════════════════════════════════════════════
    // ★★★ EXTRA AUDIO BACKGROUND ★★★
    // ════════════════════════════════════════════════════════════════════

    // Enable extra audio background
    public boolean extraAudioEnabled = false;

    // Extra audio URI (stored as String for Serializable)
    public String extraAudioUri = null;

    // Extra audio volume (1-100%)
    public int extraAudioVolume = 10;

// ════════════════════════════════════════════════════════════════════
// HELPER METHODS FOR REACTION FACE
// ════════════════════════════════════════════════════════════════════

    /**
     * Check if reaction face is enabled and has valid URI
     */
    public boolean hasReactionFace() {
        return reactionFaceEnabled && reactionFaceUri != null && !reactionFaceUri.isEmpty();
    }

    /**
     * Check if extra audio is enabled and has valid URI
     */
    public boolean hasExtraAudio() {
        return extraAudioEnabled && extraAudioUri != null && !extraAudioUri.isEmpty();
    }

    /**
     * Get position as enum-like values for easier processing
     */
    public static final int FACE_POS_TOP_LEFT = 0;
    public static final int FACE_POS_TOP_RIGHT = 1;
    public static final int FACE_POS_BOTTOM_LEFT = 2;
    public static final int FACE_POS_BOTTOM_RIGHT = 3;
}