package com.example.uniquecreator.helper;

import android.graphics.Bitmap;

import java.io.Serializable;

public class WatermarkConfig implements Serializable {
    private static final long serialVersionUID = 2L; // ★ Incremented

    public enum Mode {
        NONE, LOGO, TEXT, BOTH
    }

    public enum Position {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
    }

    public enum TextStyle {
        NORMAL, BOLD, OUTLINE
    }

    // Mode
    public Mode mode = Mode.NONE;

    // Logo - not serializable, use static holder
    public transient Bitmap logoBitmap = null;
    public Position logoPosition = Position.TOP_LEFT;
    public int logoSize = 10;       // % of video width
    public int logoOpacity = 80;    // 0-100

    // Text
    public String text = "";
    public Position textPosition = Position.BOTTOM_RIGHT;
    public int fontSize = 22;
    public int textOpacity = 70;    // 0-100
    public int textColor = 0xFFFFFFFF;
    public TextStyle textStyle = TextStyle.NORMAL;

    // ★ NEW: Dynamic watermark (position changes over time)
    public boolean dynamicPosition = false;
    public int positionChangeIntervalSec = 5; // Change position every N seconds

    // Static holder for Bitmap (Bitmap isn't Serializable)
    public static transient Bitmap sharedLogoBitmap = null;

    public WatermarkConfig() {}

    // Helper to sync logo
    public void syncLogo() {
        if (logoBitmap != null) {
            sharedLogoBitmap = logoBitmap;
        } else if (sharedLogoBitmap != null) {
            logoBitmap = sharedLogoBitmap;
        }
    }

    // Check if watermark is active
    public boolean isActive() {
        if (mode == Mode.NONE) return false;
        if (mode == Mode.TEXT && (text == null || text.trim().isEmpty())) return false;
        if (mode == Mode.LOGO && logoBitmap == null && sharedLogoBitmap == null) return false;
        return true;
    }

    /**
     * ★ NEW: Get position based on current video time (for dynamic watermark)
     */
    public Position getDynamicPosition(long currentTimeUs) {
        if (!dynamicPosition || positionChangeIntervalSec <= 0) {
            return logoPosition; // Use static position
        }

        long currentSec = currentTimeUs / 1_000_000;
        int posIndex = (int)((currentSec / positionChangeIntervalSec) % 5);

        Position[] positions = Position.values();
        return positions[posIndex];
    }
}