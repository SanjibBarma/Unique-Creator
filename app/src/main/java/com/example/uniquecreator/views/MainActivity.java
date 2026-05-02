package com.example.uniquecreator.views;

import static com.karumi.dexter.BuildConfig.VERSION_NAME;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.example.uniquecreator.BaseActivity;
import com.example.uniquecreator.R;
import com.example.uniquecreator.helper.PermissionHelper;
import com.example.uniquecreator.helper.SharedPrefHelper;
import com.example.uniquecreator.helper.TransformSettings;
import com.example.uniquecreator.helper.WatermarkConfig;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends BaseActivity {

    private static final String TAG = "MainActivity";
    private static final int MAX_LOGO_SIZE = 512;

    // Upload Section
    private FrameLayout dropZone;
    private LinearLayout fileInfoLayout, optionsLayout;
    private ImageView previewThumbnail;
    private TextView fileNameText, fileDetailText;
    private Button selectVideoBtn, changeVideoBtn, startProcessBtn;
    private TextView step1Status, processSummary, transformCount;

    // Animated Views
    private View logoPulse, uploadPulse;
    private TextView logoIcon, uploadIcon;

    // Collapsible Sections
    private LinearLayout outputHeader, outputContent;
    private LinearLayout transformHeader, transformContent;
    private LinearLayout watermarkHeader, watermarkContent;
    private LinearLayout logoRemoverHeader, logoRemoverContent;
    private TextView outputToggle, transformToggle, watermarkToggle, logoRemoverToggle;
    private TextView outputSummary, watermarkSummary, logoRemoverSummary;
    private boolean outputExpanded = false;
    private boolean transformExpanded = false;
    private boolean watermarkExpanded = false;
    private boolean logoRemoverExpanded = false;

    // Quick Action Buttons
    private Button btnEnableAll, btnDisableAll;
    private Button btnPresetMax, btnPresetSubtle, btnPresetAudio, btnPresetVideo;

    // Resolution & Ratio
    private ChipGroup resolutionGroup, ratioGroup;

    // Original 21 Transform Switches
    private SwitchCompat swFlip, swSpeed, swHue, swBright, swSaturation;
    private SwitchCompat swZoom, swRotate, swGamma, swNoise, swVignette;
    private SwitchCompat swBorder, swTint, swPixelShift, swSharpen, swTrim;
    private SwitchCompat swVolume, swBlur, swSepia;
    private SwitchCompat swChromatic, swBarrel, swPitch;

    // 4 Additional Transform Switches
    private SwitchCompat swSpectralNoise, swTemporalJitter, swAmbientNoise, swBitrateRandom, swBorderProgress;

    // Ultimate Bypass Switches (Version 7.0)
    private SwitchCompat swPerspective3D, swVariableSpeed, swLumaPulse, swAudioPhaseShift, swSubPixelJitter;

    // Advanced Bypass Switches
    private SwitchCompat swAspectDistortion, swAudioEq, swMetadataScrubbing, swJunkData;

    // Original Transform Sliders
    private Slider slSpeed, slHue, slBright, slSaturation, slZoom;
    private Slider slRotate, slGamma, slNoise, slVignette, slBorder;
    private Slider slTint, slPixelShift, slSharpen, slTrim, slVolume, slBlur, slSepia;
    private Slider slChromatic, slBarrel, slPitch, slBorderProgress;

    // Ultimate Bypass Sliders (Version 7.0)
    private Slider slPerspectiveTiltX, slPerspectiveTiltY, slVariableSpeedIntensity, slLumaPulseIntensity;

    // Additional Sliders
    private Slider slSpectralNoise, slTemporalJitter, slAmbientNoise, slBitrateRandom, slSubPixelJitter;

    // Slider Value TextViews
    private TextView speedValue, hueValue, brightValue, saturationValue, zoomValue;
    private TextView rotateValue, gammaValue, noiseValue, vignetteValue, borderValue;
    private TextView tintValue, pixelShiftValue, sharpenValue, trimValue, volumeValue;
    private TextView blurValue, sepiaValue, chromaticValue, barrelValue, pitchValue;
    private TextView spectralNoiseValue, temporalJitterValue, ambientNoiseValue, bitrateRandomValue, borderProgressValue;

    // Ultimate Bypass Value TextViews (Version 7.0)
    private TextView perspectiveXTiltValue, perspectiveYTiltValue, variableSpeedValue, lumaPulseValue, subPixelJitterValue;

    // Overlay / Watermark
    private ChipGroup overlayModeGroup;
    private LinearLayout logoSection, textSection;
    private ImageView logoPreview;
    private TextView logoDropText, tvAppVersion;
    private Button logoPickBtn, logoRemoveBtn;
    private ChipGroup logoPosGroup, textPosGroup, wmStyleGroup;
    private Slider slLogoSize, slLogoOpacity, slWmFont, slWmOpacity;
    private EditText etWmText;
    private View wmColorSwatch;

    // Dynamic watermark controls
    private SwitchCompat swDynamicWatermark;
    private Slider slWatermarkInterval;
    private TextView tvWatermarkIntervalValue;

    // ═══════════════════════════════════════════════════════════════
    // Logo Remover Section (Manual Only)
    // ═══════════════════════════════════════════════════════════════

    // Manual Override Views
    private SwitchCompat swManualLogoRemover;
    private LinearLayout manualRemovalSection;

    // Common Views
    private LinearLayout blurIntensitySection;
    private ChipGroup removalMethodGroup;
    private Slider slRemovalX, slRemovalY, slRemovalWidth, slRemovalHeight, slRemovalBlur;
    private TextView removalXValue, removalYValue, removalWidthValue, removalHeightValue, removalBlurValue;
    private Button btnLogoTopLeft, btnLogoTopRight, btnLogoBottomLeft, btnLogoBottomRight;

    // State
    private Uri selectedVideoUri;
    private String selectedResolution = "original";
    private String selectedRatio = "original";
    private long videoDurationMs = 0;
    private Bitmap videoThumbnail;

    private final TransformSettings ts = new TransformSettings();
    private final WatermarkConfig wm = new WatermarkConfig();
    private int wmColorInt = 0xFFFFFFFF;
    private View borderProgressColorSwatch;
    private int borderProgressColorInt = 0xFF00FF80;

    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private ActivityResultLauncher<Intent> logoPickerLauncher;
    private SharedPrefHelper prefHelper;

    // ═══════════════════════════════════════════════════════════════
// REACTION FACE SECTION
// ═══════════════════════════════════════════════════════════════
    private LinearLayout reactionFaceHeader, reactionFaceContent;
    private TextView reactionFaceToggle, reactionFaceSummary;
    private boolean reactionFaceExpanded = false;

    // Reaction Face Controls
    private SwitchCompat swReactionFace, swFaceAudio;
    private LinearLayout faceVideoPickerArea, reactionFaceSection, faceAudioSection;
    private FrameLayout faceVideoDropZone;
    private LinearLayout faceVideoInfoLayout;
    private ImageView faceVideoThumbnail;
    private TextView faceVideoName, faceVideoDetail, faceVideoSizeValue, faceCornerValue;
    private ImageButton btnRemoveFaceVideo;
    private LinearLayout facePositionSection, faceSizeSection, faceCornerSection;
    private Slider slFaceVideoSize, slFaceCornerRadius;

    // Position chips (manual handling since we excluded center)
    private com.google.android.material.chip.Chip chipFacePosTopLeft, chipFacePosTopRight;
    private com.google.android.material.chip.Chip chipFacePosBottomLeft, chipFacePosBottomRight;

    // State
    private Uri selectedFaceVideoUri;
    private Bitmap faceVideoThumbnailBitmap;
    private int selectedFacePosition = 0; // 0=TopLeft, 1=TopRight, 2=BottomLeft, 3=BottomRight

    // Launcher
    private ActivityResultLauncher<Intent> faceVideoPickerLauncher;

    @Override
    protected SecurityCheckMode getSecurityCheckMode() {
        return SecurityCheckMode.ALWAYS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefHelper = SharedPrefHelper.getInstance(this);

        registerActivityResultLaunchers();
        initViews();
        setupListeners();
        startAnimations();
        setAppVersion();

        if (PermissionHelper.hasPermissions(this)) {
            Log.d(TAG, "Permissions already granted");
        } else {
            checkPermissions();
        }

        LinearLayout btnCheckCopyright = findViewById(R.id.btnCheckCopyright);
        btnCheckCopyright.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, VideoComparisonActivity.class);
            startActivity(intent);
        });

        ImageButton btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(v -> showAppInfoDialog());
    }

    private void registerActivityResultLaunchers() {
        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        selectedVideoUri = result.getData().getData();
                        if (selectedVideoUri != null) loadVideoInfo();
                    }
                }
        );

        logoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri logoUri = result.getData().getData();
                        if (logoUri != null) loadLogoImage(logoUri);
                    }
                }
        );

        // Face Video Picker
        faceVideoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        selectedFaceVideoUri = result.getData().getData();
                        if (selectedFaceVideoUri != null) {
                            loadFaceVideoInfo();
                        }
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoThumbnail != null && !videoThumbnail.isRecycled()) {
            videoThumbnail.recycle();
            videoThumbnail = null;
        }

        if (faceVideoThumbnailBitmap != null && !faceVideoThumbnailBitmap.isRecycled()) {
            faceVideoThumbnailBitmap.recycle();
            faceVideoThumbnailBitmap = null;
        }

        WatermarkConfig.sharedLogoBitmap = null;
    }

    private void initViews() {
        // Upload section
        dropZone = findViewById(R.id.dropZone);
        fileInfoLayout = findViewById(R.id.fileInfoLayout);
        optionsLayout = findViewById(R.id.optionsLayout);
        previewThumbnail = findViewById(R.id.previewThumbnail);
        fileNameText = findViewById(R.id.fileNameText);
        fileDetailText = findViewById(R.id.fileDetailText);
        selectVideoBtn = findViewById(R.id.selectVideoBtn);
        changeVideoBtn = findViewById(R.id.changeVideoBtn);
        startProcessBtn = findViewById(R.id.startProcessBtn);

        step1Status = findViewById(R.id.step1Status);
        processSummary = findViewById(R.id.processSummary);
        transformCount = findViewById(R.id.transformCount);

        // Animated views
        logoPulse = findViewById(R.id.logoPulse);
        uploadPulse = findViewById(R.id.uploadPulse);
        logoIcon = findViewById(R.id.logoIcon);
        uploadIcon = findViewById(R.id.uploadIcon);

        // Output section
        outputHeader = findViewById(R.id.outputHeader);
        outputContent = findViewById(R.id.outputContent);
        outputToggle = findViewById(R.id.outputToggle);
        outputSummary = findViewById(R.id.outputSummary);

        // Transform section
        transformHeader = findViewById(R.id.transformHeader);
        transformContent = findViewById(R.id.transformContent);
        transformToggle = findViewById(R.id.transformToggle);

        // Watermark section
        watermarkHeader = findViewById(R.id.watermarkHeader);
        watermarkContent = findViewById(R.id.watermarkContent);
        watermarkToggle = findViewById(R.id.watermarkToggle);
        watermarkSummary = findViewById(R.id.watermarkSummary);

        // Logo Remover Section
        logoRemoverHeader = findViewById(R.id.logoRemoverHeader);
        logoRemoverContent = findViewById(R.id.logoRemoverContent);
        logoRemoverToggle = findViewById(R.id.logoRemoverToggle);
        logoRemoverSummary = findViewById(R.id.logoRemoverSummary);

        // Quick actions
        btnEnableAll = findViewById(R.id.btnEnableAll);
        btnDisableAll = findViewById(R.id.btnDisableAll);
        btnPresetMax = findViewById(R.id.btnPresetMax);
        btnPresetSubtle = findViewById(R.id.btnPresetSubtle);
        btnPresetAudio = findViewById(R.id.btnPresetAudio);
        btnPresetVideo = findViewById(R.id.btnPresetVideo);

        // Resolution & Ratio
        resolutionGroup = findViewById(R.id.resolutionGroup);
        ratioGroup = findViewById(R.id.ratioGroup);

        initTransformViews();

        // Overlay / Watermark
        overlayModeGroup = findViewById(R.id.overlayModeGroup);
        logoSection = findViewById(R.id.logoSection);
        textSection = findViewById(R.id.textSection);
        logoPreview = findViewById(R.id.logoPreview);
        logoDropText = findViewById(R.id.logoDropText);
        logoPickBtn = findViewById(R.id.logoPickBtn);
        logoRemoveBtn = findViewById(R.id.logoRemoveBtn);
        logoPosGroup = findViewById(R.id.logoPosGroup);
        textPosGroup = findViewById(R.id.textPosGroup);
        wmStyleGroup = findViewById(R.id.wmStyleGroup);
        slLogoSize = findViewById(R.id.slLogoSize);
        slLogoOpacity = findViewById(R.id.slLogoOpacity);
        slWmFont = findViewById(R.id.slWmFont);
        slWmOpacity = findViewById(R.id.slWmOpacity);
        etWmText = findViewById(R.id.etWmText);
        wmColorSwatch = findViewById(R.id.wmColorSwatch);
        tvAppVersion = findViewById(R.id.tvAppVersion);

        // Dynamic watermark
        swDynamicWatermark = findViewById(R.id.swDynamicWatermark);
        slWatermarkInterval = findViewById(R.id.slWatermarkInterval);
        tvWatermarkIntervalValue = findViewById(R.id.tvWatermarkIntervalValue);

        // ═══════════════════════════════════════════════════════════════
        // Logo Remover controls (Manual Only)
        // ═══════════════════════════════════════════════════════════════

        // Manual Override
        swManualLogoRemover = findViewById(R.id.swManualLogoRemover);
        manualRemovalSection = findViewById(R.id.manualRemovalSection);

        // Common controls
        blurIntensitySection = findViewById(R.id.blurIntensitySection);
        removalMethodGroup = findViewById(R.id.removalMethodGroup);

        slRemovalX = findViewById(R.id.slRemovalX);
        slRemovalY = findViewById(R.id.slRemovalY);
        slRemovalWidth = findViewById(R.id.slRemovalWidth);
        slRemovalHeight = findViewById(R.id.slRemovalHeight);
        slRemovalBlur = findViewById(R.id.slRemovalBlur);

        removalXValue = findViewById(R.id.removalXValue);
        removalYValue = findViewById(R.id.removalYValue);
        removalWidthValue = findViewById(R.id.removalWidthValue);
        removalHeightValue = findViewById(R.id.removalHeightValue);
        removalBlurValue = findViewById(R.id.removalBlurValue);

        btnLogoTopLeft = findViewById(R.id.btnLogoTopLeft);
        btnLogoTopRight = findViewById(R.id.btnLogoTopRight);
        btnLogoBottomLeft = findViewById(R.id.btnLogoBottomLeft);
        btnLogoBottomRight = findViewById(R.id.btnLogoBottomRight);

        // ═══════════════════════════════════════════════════════════════
// REACTION FACE SECTION
// ═══════════════════════════════════════════════════════════════
        reactionFaceHeader = findViewById(R.id.reactionFaceHeader);
        reactionFaceContent = findViewById(R.id.reactionFaceContent);
        reactionFaceToggle = findViewById(R.id.reactionFaceToggle);
        reactionFaceSummary = findViewById(R.id.reactionFaceSummary);

// Reaction Face Controls
        swReactionFace = findViewById(R.id.swReactionFace);
        swFaceAudio = findViewById(R.id.swFaceAudio);
        faceVideoPickerArea = findViewById(R.id.faceVideoPickerArea);
        reactionFaceSection = findViewById(R.id.reactionFaceSection);
        faceAudioSection = findViewById(R.id.faceAudioSection);
        faceVideoDropZone = findViewById(R.id.faceVideoDropZone);
        faceVideoInfoLayout = findViewById(R.id.faceVideoInfoLayout);
        faceVideoThumbnail = findViewById(R.id.faceVideoThumbnail);
        faceVideoName = findViewById(R.id.faceVideoName);
        faceVideoDetail = findViewById(R.id.faceVideoDetail);
        faceVideoSizeValue = findViewById(R.id.faceVideoSizeValue);
        faceCornerValue = findViewById(R.id.faceCornerValue);
        btnRemoveFaceVideo = findViewById(R.id.btnRemoveFaceVideo);
        facePositionSection = findViewById(R.id.facePositionSection);
        faceSizeSection = findViewById(R.id.faceSizeSection);
        faceCornerSection = findViewById(R.id.faceCornerSection);
        slFaceVideoSize = findViewById(R.id.slFaceVideoSize);
        slFaceCornerRadius = findViewById(R.id.slFaceCornerRadius);

// Position chips
        chipFacePosTopLeft = findViewById(R.id.chipFacePosTopLeft);
        chipFacePosTopRight = findViewById(R.id.chipFacePosTopRight);
        chipFacePosBottomLeft = findViewById(R.id.chipFacePosBottomLeft);
        chipFacePosBottomRight = findViewById(R.id.chipFacePosBottomRight);
    }

    private void initTransformViews() {
        // Original 21 switches
        swFlip = findViewById(R.id.swFlip);
        swSpeed = findViewById(R.id.swSpeed);
        swHue = findViewById(R.id.swHue);
        swBright = findViewById(R.id.swBright);
        swSaturation = findViewById(R.id.swSaturation);
        swZoom = findViewById(R.id.swZoom);
        swRotate = findViewById(R.id.swRotate);
        swGamma = findViewById(R.id.swGamma);
        swNoise = findViewById(R.id.swNoise);
        swVignette = findViewById(R.id.swVignette);
        swBorder = findViewById(R.id.swBorder);
        swTint = findViewById(R.id.swTint);
        swPixelShift = findViewById(R.id.swPixelShift);
        swSharpen = findViewById(R.id.swSharpen);
        swTrim = findViewById(R.id.swTrim);
        swVolume = findViewById(R.id.swVolume);
        swBlur = findViewById(R.id.swBlur);
        swSepia = findViewById(R.id.swSepia);
        swChromatic = findViewById(R.id.swChromatic);
        swBarrel = findViewById(R.id.swBarrel);
        swPitch = findViewById(R.id.swPitch);
        swBorderProgress = findViewById(R.id.swBorderProgress);

        // 4 additional switches
        swSpectralNoise = findViewById(R.id.swSpectralNoise);
        swTemporalJitter = findViewById(R.id.swTemporalJitter);
        swAmbientNoise = findViewById(R.id.swAmbientNoise);
        swBitrateRandom = findViewById(R.id.swBitrateRandom);

        // Ultimate Bypass (Version 7.0)
        swPerspective3D = findViewById(R.id.swPerspective3D);
        swVariableSpeed = findViewById(R.id.swVariableSpeed);
        swLumaPulse = findViewById(R.id.swLumaPulse);
        swAudioPhaseShift = findViewById(R.id.swAudioPhaseShift);
        swSubPixelJitter = findViewById(R.id.swSubPixelJitter);

        // Advanced Bypass switches
        swAspectDistortion = findViewById(R.id.swAspectDistortion);
        swAudioEq = findViewById(R.id.swAudioEq);
        swMetadataScrubbing = findViewById(R.id.swMetadataScrubbing);
        swJunkData = findViewById(R.id.swJunkData);

        // Original sliders
        slSpeed = findViewById(R.id.slSpeed);
        slHue = findViewById(R.id.slHue);
        slBright = findViewById(R.id.slBright);
        slSaturation = findViewById(R.id.slSaturation);
        slZoom = findViewById(R.id.slZoom);
        slRotate = findViewById(R.id.slRotate);
        slGamma = findViewById(R.id.slGamma);
        slNoise = findViewById(R.id.slNoise);
        slVignette = findViewById(R.id.slVignette);
        slBorder = findViewById(R.id.slBorder);
        slTint = findViewById(R.id.slTint);
        slPixelShift = findViewById(R.id.slPixelShift);
        slSharpen = findViewById(R.id.slSharpen);
        slTrim = findViewById(R.id.slTrim);
        slVolume = findViewById(R.id.slVolume);
        slBlur = findViewById(R.id.slBlur);
        slSepia = findViewById(R.id.slSepia);
        slChromatic = findViewById(R.id.slChromatic);
        slBarrel = findViewById(R.id.slBarrel);
        slPitch = findViewById(R.id.slPitch);
        slBorderProgress = findViewById(R.id.slBorderProgress);

        // Additional sliders
        slSpectralNoise = findViewById(R.id.slSpectralNoise);
        slTemporalJitter = findViewById(R.id.slTemporalJitter);
        slAmbientNoise = findViewById(R.id.slAmbientNoise);
        slBitrateRandom = findViewById(R.id.slBitrateRandom);

        // Ultimate Bypass (Version 7.0)
        slPerspectiveTiltX = findViewById(R.id.slPerspectiveTiltX);
        slPerspectiveTiltY = findViewById(R.id.slPerspectiveTiltY);
        slVariableSpeedIntensity = findViewById(R.id.slVariableSpeedIntensity);
        slLumaPulseIntensity = findViewById(R.id.slLumaPulseIntensity);
        slSubPixelJitter = findViewById(R.id.slSubPixelJitter);

        // Slider value TextViews
        speedValue = findViewById(R.id.speedValue);
        hueValue = findViewById(R.id.hueValue);
        brightValue = findViewById(R.id.brightValue);
        saturationValue = findViewById(R.id.saturationValue);
        zoomValue = findViewById(R.id.zoomValue);
        rotateValue = findViewById(R.id.rotateValue);
        gammaValue = findViewById(R.id.gammaValue);
        noiseValue = findViewById(R.id.noiseValue);
        vignetteValue = findViewById(R.id.vignetteValue);
        borderValue = findViewById(R.id.borderValue);
        tintValue = findViewById(R.id.tintValue);
        pixelShiftValue = findViewById(R.id.pixelShiftValue);
        sharpenValue = findViewById(R.id.sharpenValue);
        trimValue = findViewById(R.id.trimValue);
        volumeValue = findViewById(R.id.volumeValue);
        blurValue = findViewById(R.id.blurValue);
        sepiaValue = findViewById(R.id.sepiaValue);
        chromaticValue = findViewById(R.id.chromaticValue);
        barrelValue = findViewById(R.id.barrelValue);
        pitchValue = findViewById(R.id.pitchValue);
        spectralNoiseValue = findViewById(R.id.spectralNoiseValue);
        temporalJitterValue = findViewById(R.id.temporalJitterValue);
        ambientNoiseValue = findViewById(R.id.ambientNoiseValue);
        bitrateRandomValue = findViewById(R.id.bitrateRandomValue);
        borderProgressValue = findViewById(R.id.borderProgressValue);
        borderProgressColorSwatch = findViewById(R.id.borderProgressColorSwatch);

        // Ultimate Bypass (Version 7.0)
        perspectiveXTiltValue = findViewById(R.id.perspectiveXTiltValue);
        perspectiveYTiltValue = findViewById(R.id.perspectiveYTiltValue);
        variableSpeedValue = findViewById(R.id.variableSpeedValue);
        lumaPulseValue = findViewById(R.id.lumaPulseValue);
        subPixelJitterValue = findViewById(R.id.subPixelJitterValue);
    }

    private void setupListeners() {
        // Video selection
        if (dropZone != null) dropZone.setOnClickListener(view -> {
            if (prefHelper.getDeviceId() != null && prefHelper.getDeviceId().equals(
                    Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID))) {
                openVideoPicker();
            } else {
                Toast.makeText(MainActivity.this, "You are not an authorized user!", Toast.LENGTH_SHORT).show();
            }
        });

        if (selectVideoBtn != null) selectVideoBtn.setOnClickListener(view -> {
            if (prefHelper.getDeviceId() != null && prefHelper.getDeviceId().equals(
                    Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID))) {
                openVideoPicker();
            } else {
                Toast.makeText(MainActivity.this, "You are not an authorized user!", Toast.LENGTH_SHORT).show();
            }
        });

        if (changeVideoBtn != null) changeVideoBtn.setOnClickListener(v -> resetAll());

        // Start processing
        if (startProcessBtn != null) {
            startProcessBtn.setOnClickListener(v -> {
                if (selectedVideoUri == null) {
                    Toast.makeText(this, "প্রথমে একটি ভিডিও সিলেক্ট করুন", Toast.LENGTH_SHORT).show();
                    return;
                }
                startProcessing();
            });
        }

        // Collapsible section toggles
        if (outputHeader != null) outputHeader.setOnClickListener(v -> toggleOutputSection());
        if (transformHeader != null)
            transformHeader.setOnClickListener(v -> toggleTransformSection());
        if (watermarkHeader != null)
            watermarkHeader.setOnClickListener(v -> toggleWatermarkSection());
        if (logoRemoverHeader != null)
            logoRemoverHeader.setOnClickListener(v -> toggleLogoRemoverSection());

        // Quick actions
        if (btnEnableAll != null) btnEnableAll.setOnClickListener(v -> {
            resetSlidersToDefault();
            setAllTransforms(true);
        });
        if (btnDisableAll != null) btnDisableAll.setOnClickListener(v -> {
            resetSlidersToDefault();
            setAllTransforms(false);
        });

        // Presets
        if (btnPresetMax != null) btnPresetMax.setOnClickListener(v -> applyPreset("max"));
        if (btnPresetSubtle != null) btnPresetSubtle.setOnClickListener(v -> applyPreset("subtle"));
        if (btnPresetAudio != null) btnPresetAudio.setOnClickListener(v -> applyPreset("audio"));
        if (btnPresetVideo != null) btnPresetVideo.setOnClickListener(v -> applyPreset("video"));

        // Resolution selection
        if (resolutionGroup != null) {
            resolutionGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipOriginal) selectedResolution = "original";
                else if (checkedId == R.id.chip1080p) selectedResolution = "1080";
                else if (checkedId == R.id.chip720p) selectedResolution = "720";
                else if (checkedId == R.id.chip480p) selectedResolution = "480";
                updateProcessSummary();
                updateOutputSummary();
            });
        }

        // Ratio selection
        if (ratioGroup != null) {
            ratioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipRatioOriginal) selectedRatio = "original";
                else if (checkedId == R.id.chip16_9) selectedRatio = "16:9";
                else if (checkedId == R.id.chip9_16) selectedRatio = "9:16";
                else if (checkedId == R.id.chip1_1) selectedRatio = "1:1";
                else if (checkedId == R.id.chip4_3) selectedRatio = "4:3";
                updateProcessSummary();
                updateOutputSummary();
            });
        }

        // Overlay mode selection
        if (overlayModeGroup != null) {
            overlayModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                boolean showLogo = (checkedId == R.id.chipOverlayLogo || checkedId == R.id.chipOverlayBoth);
                boolean showText = (checkedId == R.id.chipOverlayText || checkedId == R.id.chipOverlayBoth);

                if (logoSection != null)
                    logoSection.setVisibility(showLogo ? View.VISIBLE : View.GONE);
                if (textSection != null)
                    textSection.setVisibility(showText ? View.VISIBLE : View.GONE);

                if (checkedId == R.id.chipOverlayNone) wm.mode = WatermarkConfig.Mode.NONE;
                else if (checkedId == R.id.chipOverlayLogo) wm.mode = WatermarkConfig.Mode.LOGO;
                else if (checkedId == R.id.chipOverlayText) wm.mode = WatermarkConfig.Mode.TEXT;
                else if (checkedId == R.id.chipOverlayBoth) wm.mode = WatermarkConfig.Mode.BOTH;

                updateWatermarkSummary();
            });
        }

        // Logo picker
        if (logoPickBtn != null) logoPickBtn.setOnClickListener(v -> openLogoPicker());
        if (logoRemoveBtn != null) logoRemoveBtn.setOnClickListener(v -> removeLogo());

        // Logo position
        if (logoPosGroup != null) {
            logoPosGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipLogoPosTopLeft)
                    wm.logoPosition = WatermarkConfig.Position.TOP_LEFT;
                else if (checkedId == R.id.chipLogoPosTopRight)
                    wm.logoPosition = WatermarkConfig.Position.TOP_RIGHT;
                else if (checkedId == R.id.chipLogoPosBottomLeft)
                    wm.logoPosition = WatermarkConfig.Position.BOTTOM_LEFT;
                else if (checkedId == R.id.chipLogoPosBottomRight)
                    wm.logoPosition = WatermarkConfig.Position.BOTTOM_RIGHT;
                else if (checkedId == R.id.chipLogoPosCenter)
                    wm.logoPosition = WatermarkConfig.Position.CENTER;
            });
        }

        // Text position
        if (textPosGroup != null) {
            textPosGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipTextPosTopLeft)
                    wm.textPosition = WatermarkConfig.Position.TOP_LEFT;
                else if (checkedId == R.id.chipTextPosTopRight)
                    wm.textPosition = WatermarkConfig.Position.TOP_RIGHT;
                else if (checkedId == R.id.chipTextPosBottomLeft)
                    wm.textPosition = WatermarkConfig.Position.BOTTOM_LEFT;
                else if (checkedId == R.id.chipTextPosBottomRight)
                    wm.textPosition = WatermarkConfig.Position.BOTTOM_RIGHT;
                else if (checkedId == R.id.chipTextPosCenter)
                    wm.textPosition = WatermarkConfig.Position.CENTER;
            });
        }

        // Text style
        if (wmStyleGroup != null) {
            wmStyleGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.chipStyleNormal)
                    wm.textStyle = WatermarkConfig.TextStyle.NORMAL;
                else if (checkedId == R.id.chipStyleBold)
                    wm.textStyle = WatermarkConfig.TextStyle.BOLD;
                else if (checkedId == R.id.chipStyleOutline)
                    wm.textStyle = WatermarkConfig.TextStyle.OUTLINE;
            });
        }

        // Color picker
        if (wmColorSwatch != null) wmColorSwatch.setOnClickListener(v -> showColorPicker());

        // Dynamic watermark
        if (swDynamicWatermark != null) {
            swDynamicWatermark.setOnCheckedChangeListener((b, checked) -> {
                if (slWatermarkInterval != null) {
                    slWatermarkInterval.setEnabled(checked);
                    slWatermarkInterval.setAlpha(checked ? 1.0f : 0.5f);
                }
                if (tvWatermarkIntervalValue != null) {
                    tvWatermarkIntervalValue.setAlpha(checked ? 1.0f : 0.5f);
                }
            });
        }

        if (slWatermarkInterval != null && tvWatermarkIntervalValue != null) {
            tvWatermarkIntervalValue.setText((int) slWatermarkInterval.getValue() + " সেকেন্ড");
            slWatermarkInterval.addOnChangeListener((slider, value, fromUser) ->
                    tvWatermarkIntervalValue.setText((int) value + " সেকেন্ড"));
        }

        // ═══════════════════════════════════════════════════════════════
        // MANUAL OVERRIDE
        // ═══════════════════════════════════════════════════════════════
        if (swManualLogoRemover != null) {
            swManualLogoRemover.setOnCheckedChangeListener((buttonView, isChecked) -> {
                applyManualRemovalEnabled(isChecked);
                updateLogoRemoverSummary();
            });
            applyManualRemovalEnabled(swManualLogoRemover.isChecked());
        }

        // ═══════════════════════════════════════════════════════════════
        // REMOVAL METHOD
        // ═══════════════════════════════════════════════════════════════
        if (removalMethodGroup != null) {
            removalMethodGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (blurIntensitySection != null) {
                    blurIntensitySection.setVisibility(
                            checkedId == R.id.chipMethodBlur ? View.VISIBLE : View.GONE
                    );
                }
                updateLogoRemoverSummary();
            });
        }

        // Quick Position Buttons
        if (btnLogoTopLeft != null) btnLogoTopLeft.setOnClickListener(v -> {
            if (swManualLogoRemover != null && !swManualLogoRemover.isChecked()) return;
            setLogoRemovalPosition(10, 10);
        });
        if (btnLogoTopRight != null) btnLogoTopRight.setOnClickListener(v -> {
            if (swManualLogoRemover != null && !swManualLogoRemover.isChecked()) return;
            setLogoRemovalPosition(1720, 10);
        });
        if (btnLogoBottomLeft != null) btnLogoBottomLeft.setOnClickListener(v -> {
            if (swManualLogoRemover != null && !swManualLogoRemover.isChecked()) return;
            setLogoRemovalPosition(10, 930);
        });
        if (btnLogoBottomRight != null) btnLogoBottomRight.setOnClickListener(v -> {
            if (swManualLogoRemover != null && !swManualLogoRemover.isChecked()) return;
            setLogoRemovalPosition(1720, 930);
        });

        // ═══════════════════════════════════════════════════════════════
// REACTION FACE LISTENERS
// ═══════════════════════════════════════════════════════════════
        if (reactionFaceHeader != null) {
            reactionFaceHeader.setOnClickListener(v -> toggleReactionFaceSection());
        }

// Main switch
        if (swReactionFace != null) {
            swReactionFace.setOnCheckedChangeListener((buttonView, isChecked) -> {
                applyReactionFaceEnabled(isChecked);
                updateReactionFaceSummary();
            });
            applyReactionFaceEnabled(swReactionFace.isChecked());
        }

// Face video picker
        if (faceVideoDropZone != null) {
            faceVideoDropZone.setOnClickListener(v -> {
                if (swReactionFace != null && swReactionFace.isChecked()) {
                    openFaceVideoPicker();
                } else {
                    Toast.makeText(this, "প্রথমে Reaction Face চালু করুন", Toast.LENGTH_SHORT).show();
                }
            });
        }

// Remove face video
        if (btnRemoveFaceVideo != null) {
            btnRemoveFaceVideo.setOnClickListener(v -> removeFaceVideo());
        }

// Position chips (manual single selection)
        View.OnClickListener positionClickListener = v -> {
            // Uncheck all first
            if (chipFacePosTopLeft != null) chipFacePosTopLeft.setChecked(false);
            if (chipFacePosTopRight != null) chipFacePosTopRight.setChecked(false);
            if (chipFacePosBottomLeft != null) chipFacePosBottomLeft.setChecked(false);
            if (chipFacePosBottomRight != null) chipFacePosBottomRight.setChecked(false);

            // Check clicked one
            ((com.google.android.material.chip.Chip) v).setChecked(true);

            // Update position
            int id = v.getId();
            if (id == R.id.chipFacePosTopLeft) selectedFacePosition = 0;
            else if (id == R.id.chipFacePosTopRight) selectedFacePosition = 1;
            else if (id == R.id.chipFacePosBottomLeft) selectedFacePosition = 2;
            else if (id == R.id.chipFacePosBottomRight) selectedFacePosition = 3;

            updateReactionFaceSummary();
        };

        if (chipFacePosTopLeft != null) chipFacePosTopLeft.setOnClickListener(positionClickListener);
        if (chipFacePosTopRight != null) chipFacePosTopRight.setOnClickListener(positionClickListener);
        if (chipFacePosBottomLeft != null) chipFacePosBottomLeft.setOnClickListener(positionClickListener);
        if (chipFacePosBottomRight != null) chipFacePosBottomRight.setOnClickListener(positionClickListener);

// Size slider
        if (slFaceVideoSize != null && faceVideoSizeValue != null) {
            faceVideoSizeValue.setText((int) slFaceVideoSize.getValue() + "%");
            slFaceVideoSize.addOnChangeListener((slider, value, fromUser) -> {
                faceVideoSizeValue.setText((int) value + "%");
                updateReactionFaceSummary();
            });
        }

// Corner radius slider
        if (slFaceCornerRadius != null && faceCornerValue != null) {
            faceCornerValue.setText((int) slFaceCornerRadius.getValue() + "%");
            slFaceCornerRadius.addOnChangeListener((slider, value, fromUser) -> {
                faceCornerValue.setText((int) value + "%");
            });
        }

        setupSwitchSliderPairs();
        setupSliderValueListeners();
    }

    private void setupSwitchSliderPairs() {
        // Original pairs
        setupPair(swSpeed, slSpeed);
        setupPair(swHue, slHue);
        setupPair(swBright, slBright);
        setupPair(swSaturation, slSaturation);
        setupPair(swZoom, slZoom);
        setupPair(swRotate, slRotate);
        setupPair(swGamma, slGamma);
        setupPair(swNoise, slNoise);
        setupPair(swVignette, slVignette);
        setupPair(swBorder, slBorder);
        setupPair(swTint, slTint);
        setupPair(swPixelShift, slPixelShift);
        setupPair(swSharpen, slSharpen);
        setupPair(swTrim, slTrim);
        setupPair(swVolume, slVolume);
        setupPair(swBlur, slBlur);
        setupPair(swSepia, slSepia);
        setupPair(swChromatic, slChromatic);
        setupPair(swBarrel, slBarrel);
        setupPair(swPitch, slPitch);
        setupPair(swBorderProgress, slBorderProgress);

        // Additional pairs
        setupPair(swSpectralNoise, slSpectralNoise);
        setupPair(swTemporalJitter, slTemporalJitter);
        setupPair(swAmbientNoise, slAmbientNoise);
        setupPair(swBitrateRandom, slBitrateRandom);

        // Ultimate Bypass (Version 7.0)
        setupPair(swPerspective3D, slPerspectiveTiltX);
        setupPair(swPerspective3D, slPerspectiveTiltY);
        setupPair(swVariableSpeed, slVariableSpeedIntensity);
        setupPair(swLumaPulse, slLumaPulseIntensity);
        setupPair(swSubPixelJitter, slSubPixelJitter);

        if (swFlip != null) {
            swFlip.setOnCheckedChangeListener((b, c) -> updateTransformCount());
        }
        if (swAspectDistortion != null) {
            swAspectDistortion.setOnCheckedChangeListener((b, c) -> updateTransformCount());
        }
        if (swAudioEq != null) {
            swAudioEq.setOnCheckedChangeListener((b, c) -> updateTransformCount());
        }
        if (swMetadataScrubbing != null) {
            swMetadataScrubbing.setOnCheckedChangeListener((b, c) -> updateTransformCount());
        }
        if (swJunkData != null) {
            swJunkData.setOnCheckedChangeListener((b, c) -> updateTransformCount());
        }
    }

    private void setupPair(SwitchCompat sw, Slider sl) {
        if (sw == null) return;

        if (sl != null) {
            sl.setEnabled(sw.isChecked());
            sl.setAlpha(sw.isChecked() ? 1.0f : 0.5f);
        }

        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (sl != null) {
                sl.setEnabled(isChecked);
                sl.setAlpha(isChecked ? 1.0f : 0.5f);
            }
            updateTransformCount();
        });
    }

    private void setupSliderValueListeners() {
        bindSliderValue(slSpeed, speedValue, v -> String.format(Locale.US, "%.2fx", v));
        bindSliderValue(slHue, hueValue, v -> String.format(Locale.US, "%.0f°", v));
        bindSliderValue(slBright, brightValue, v -> String.format(Locale.US, "%.2fx", v));
        bindSliderValue(slSaturation, saturationValue, v -> String.format(Locale.US, "%.2fx", v));
        bindSliderValue(slZoom, zoomValue, v -> String.format(Locale.US, "%.2fx", v));
        bindSliderValue(slRotate, rotateValue, v -> String.format(Locale.US, "%.1f°", v));
        bindSliderValue(slGamma, gammaValue, v -> String.format(Locale.US, "%.2f", v));
        bindSliderValue(slNoise, noiseValue, v -> String.format(Locale.US, "%.3f", v));
        bindSliderValue(slVignette, vignetteValue, v -> String.format(Locale.US, "%.2f", v));
        bindSliderValue(slBorder, borderValue, v -> String.format(Locale.US, "%.0fpx", v));
        bindSliderValue(slTint, tintValue, v -> String.format(Locale.US, "%.2f", v));
        bindSliderValue(slPixelShift, pixelShiftValue, v -> String.format(Locale.US, "%.0fpx", v));
        bindSliderValue(slSharpen, sharpenValue, v -> String.format(Locale.US, "%.2f", v));
        bindSliderValue(slTrim, trimValue, v -> String.format(Locale.US, "%.1fs", v));
        bindSliderValue(slVolume, volumeValue, v -> String.format(Locale.US, "%.2fx", v));
        bindSliderValue(slBlur, blurValue, v -> String.format(Locale.US, "%.1fpx", v));
        bindSliderValue(slSepia, sepiaValue, v -> String.format(Locale.US, "%.2f", v));
        bindSliderValue(slChromatic, chromaticValue, v -> String.format(Locale.US, "%.1fpx", v));
        bindSliderValue(slBarrel, barrelValue, v -> String.format(Locale.US, "%.3f", v));
        bindSliderValue(slPitch, pitchValue, v -> String.format(Locale.US, "%.3fx", v));
        bindSliderValue(slSpectralNoise, spectralNoiseValue, v -> String.format(Locale.US, "%.3f", v));
        bindSliderValue(slTemporalJitter, temporalJitterValue, v -> String.format(Locale.US, "%.0f%%", v * 100));
        bindSliderValue(slAmbientNoise, ambientNoiseValue, v -> String.format(Locale.US, "%.3f", v));
        bindSliderValue(slBitrateRandom, bitrateRandomValue, v -> String.format(Locale.US, "%.0f%%", v * 100));
        bindSliderValue(slBorderProgress, borderProgressValue, v -> String.format(Locale.US, "%.0fpx", v));

        // Ultimate Bypass (Version 7.0)
        bindSliderValue(slPerspectiveTiltX, perspectiveXTiltValue, v -> String.format(Locale.US, "%.1f°", v));
        bindSliderValue(slPerspectiveTiltY, perspectiveYTiltValue, v -> String.format(Locale.US, "%.1f°", v));
        bindSliderValue(slVariableSpeedIntensity, variableSpeedValue, v -> String.format(Locale.US, "%.1f%%", v));
        bindSliderValue(slLumaPulseIntensity, lumaPulseValue, v -> String.format(Locale.US, "%.1f%%", v / 10f));
        bindSliderValue(slSubPixelJitter, subPixelJitterValue, v -> v > 10 ? "High" : (v > 5 ? "Medium" : "Low"));

        // Logo Remover sliders – guard against changes when switch is off
        bindSliderValueGuarded(slRemovalX, removalXValue,
                v -> String.format(Locale.US, "%.0fpx", v), () -> swManualLogoRemover);
        bindSliderValueGuarded(slRemovalY, removalYValue,
                v -> String.format(Locale.US, "%.0fpx", v), () -> swManualLogoRemover);
        bindSliderValueGuarded(slRemovalWidth, removalWidthValue,
                v -> String.format(Locale.US, "%.0fpx", v), () -> swManualLogoRemover);
        bindSliderValueGuarded(slRemovalHeight, removalHeightValue,
                v -> String.format(Locale.US, "%.0fpx", v), () -> swManualLogoRemover);
        bindSliderValueGuarded(slRemovalBlur, removalBlurValue,
                v -> String.format(Locale.US, "%.0fpx", v), null);
        // Border Progress Color Picker
        if (borderProgressColorSwatch != null) {
            borderProgressColorSwatch.setOnClickListener(v -> showBorderProgressColorPicker());
        }
    }

    @FunctionalInterface
    interface ValueFormatter {
        String format(float value);
    }

    private void showBorderProgressColorPicker() {
        String[] names = {
                "সবুজ-সায়ান", "সবুজ", "নীল", "সায়ান", "বেগুনি",
                "গোলাপি", "লাল", "কমলা", "হলুদ", "সাদা", "সোনালি"
        };
        int[] vals = {
                0xFF00FF80,  // Green-Cyan (default)
                0xFF00FF00,  // Green
                0xFF4488FF,  // Blue
                0xFF00FFFF,  // Cyan
                0xFF8844FF,  // Purple
                0xFFFF44AA,  // Pink
                0xFFFF4444,  // Red
                0xFFFF8844,  // Orange
                0xFFFFFF00,  // Yellow
                0xFFFFFFFF,  // White
                0xFFFFD700   // Gold
        };

        new android.app.AlertDialog.Builder(this)
                .setTitle("Border Progress রং নির্বাচন")
                .setItems(names, (d, w) -> {
                    borderProgressColorInt = vals[w];
                    if (borderProgressColorSwatch != null) {
                        borderProgressColorSwatch.setBackgroundColor(borderProgressColorInt);
                    }
                    Toast.makeText(this, "✓ " + names[w] + " সিলেক্ট হয়েছে", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void bindSliderValue(Slider slider, TextView textView, ValueFormatter formatter) {
        if (slider == null || textView == null) return;
        textView.setText(formatter.format(slider.getValue()));
        slider.addOnChangeListener((sl, value, fromUser) ->
                textView.setText(formatter.format(value)));
    }

    private void bindSliderValueGuarded(Slider slider, TextView textView,
                                        ValueFormatter formatter,
                                        java.util.function.Supplier<SwitchCompat> guardSwitch) {
        if (slider == null || textView == null) return;
        textView.setText(formatter.format(slider.getValue()));
        slider.addOnChangeListener((sl, value, fromUser) -> {
            if (fromUser && guardSwitch != null) {
                SwitchCompat sw = guardSwitch.get();
                if (sw != null && !sw.isChecked()) {
                    sl.setValue(sl.getValue());
                    return;
                }
            }
            textView.setText(formatter.format(value));
        });
    }

    // Apply manual removal controls enable/disable
    private void applyManualRemovalEnabled(boolean enabled) {
        if (manualRemovalSection != null) {
            manualRemovalSection.setAlpha(enabled ? 1.0f : 0.4f);
        }
        setSliderInteractive(slRemovalX, enabled);
        setSliderInteractive(slRemovalY, enabled);
        setSliderInteractive(slRemovalWidth, enabled);
        setSliderInteractive(slRemovalHeight, enabled);

        if (btnLogoTopLeft != null) btnLogoTopLeft.setAlpha(enabled ? 1.0f : 0.4f);
        if (btnLogoTopRight != null) btnLogoTopRight.setAlpha(enabled ? 1.0f : 0.4f);
        if (btnLogoBottomLeft != null) btnLogoBottomLeft.setAlpha(enabled ? 1.0f : 0.4f);
        if (btnLogoBottomRight != null) btnLogoBottomRight.setAlpha(enabled ? 1.0f : 0.4f);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setSliderInteractive(Slider slider, boolean interactive) {
        if (slider == null) return;
        slider.setEnabled(interactive);
        slider.setAlpha(interactive ? 1.0f : 0.4f);
        if (interactive) {
            slider.setOnTouchListener(null);
        } else {
            slider.setOnTouchListener((v, event) -> true);
        }
    }

    private void startAnimations() {
        // (same as before – no changes)
        if (logoPulse != null) {
            animateScale(logoPulse, 1f, 1.3f, 2000);
            ObjectAnimator a = ObjectAnimator.ofFloat(logoPulse, "alpha", 0.3f, 0.1f, 0.3f);
            a.setDuration(2000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.start();
        }

        if (logoIcon != null) {
            ObjectAnimator r = ObjectAnimator.ofFloat(logoIcon, "rotation", 0f, 360f);
            r.setDuration(20000);
            r.setRepeatCount(ValueAnimator.INFINITE);
            r.setInterpolator(new LinearInterpolator());
            r.start();
        }

        if (uploadPulse != null) {
            animateScale(uploadPulse, 1f, 1.2f, 1500);
            ObjectAnimator a = ObjectAnimator.ofFloat(uploadPulse, "alpha", 0.5f, 0.2f, 0.5f);
            a.setDuration(1500);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.start();
        }

        if (uploadIcon != null) {
            ObjectAnimator b = ObjectAnimator.ofFloat(uploadIcon, "translationY", 0f, -10f, 0f);
            b.setDuration(1000);
            b.setRepeatCount(ValueAnimator.INFINITE);
            b.start();
        }
    }

    private void animateScale(View v, float from, float to, int duration) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", from, to, from);
        sx.setDuration(duration);
        sx.setRepeatCount(ValueAnimator.INFINITE);
        sx.setInterpolator(new LinearInterpolator());
        sx.start();

        ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", from, to, from);
        sy.setDuration(duration);
        sy.setRepeatCount(ValueAnimator.INFINITE);
        sy.setInterpolator(new LinearInterpolator());
        sy.start();
    }

    private void toggleOutputSection() {
        outputExpanded = !outputExpanded;
        if (outputContent != null) {
            outputContent.setVisibility(outputExpanded ? View.VISIBLE : View.GONE);
            if (outputExpanded) {
                outputContent.setAlpha(0f);
                outputContent.animate().alpha(1f).setDuration(300).start();
            }
        }
        if (outputToggle != null) outputToggle.setText(outputExpanded ? "▲" : "▼");
    }

    private void toggleTransformSection() {
        transformExpanded = !transformExpanded;
        if (transformContent != null) {
            transformContent.setVisibility(transformExpanded ? View.VISIBLE : View.GONE);
            if (transformExpanded) {
                transformContent.setAlpha(0f);
                transformContent.animate().alpha(1f).setDuration(300).start();
            }
        }
        if (transformToggle != null) transformToggle.setText(transformExpanded ? "▲" : "▼");
    }

    private void toggleWatermarkSection() {
        watermarkExpanded = !watermarkExpanded;
        if (watermarkContent != null) {
            watermarkContent.setVisibility(watermarkExpanded ? View.VISIBLE : View.GONE);
            if (watermarkExpanded) {
                watermarkContent.setAlpha(0f);
                watermarkContent.animate().alpha(1f).setDuration(300).start();
            }
        }
        if (watermarkToggle != null) watermarkToggle.setText(watermarkExpanded ? "▲" : "▼");
    }

    private void toggleLogoRemoverSection() {
        logoRemoverExpanded = !logoRemoverExpanded;
        if (logoRemoverContent != null) {
            logoRemoverContent.setVisibility(logoRemoverExpanded ? View.VISIBLE : View.GONE);
            if (logoRemoverExpanded) {
                logoRemoverContent.setAlpha(0f);
                logoRemoverContent.animate().alpha(1f).setDuration(300).start();
            }
        }
        if (logoRemoverToggle != null) {
            logoRemoverToggle.setText(logoRemoverExpanded ? "▲" : "▼");
        }
    }

    private void resetSlidersToDefault() {
        TransformSettings defaultTs = new TransformSettings();
        
        if (slSpeed != null) slSpeed.setValue(defaultTs.speed);
        if (slHue != null) slHue.setValue(defaultTs.hue);
        if (slBright != null) slBright.setValue(defaultTs.bright);
        if (slSaturation != null) slSaturation.setValue(defaultTs.saturation);
        if (slZoom != null) slZoom.setValue(defaultTs.zoom);
        if (slRotate != null) slRotate.setValue(defaultTs.rotate);
        if (slGamma != null) slGamma.setValue(defaultTs.gamma);
        if (slNoise != null) slNoise.setValue(defaultTs.noise);
        if (slVignette != null) slVignette.setValue(defaultTs.vignette);
        if (slBorder != null) slBorder.setValue(defaultTs.border);
        if (slTint != null) slTint.setValue(defaultTs.tint);
        if (slPixelShift != null) slPixelShift.setValue(defaultTs.pixelShift);
        if (slSharpen != null) slSharpen.setValue(defaultTs.sharpen);
        if (slTrim != null) slTrim.setValue(defaultTs.trim);
        if (slVolume != null) slVolume.setValue(defaultTs.volume);
        if (slBlur != null) slBlur.setValue(defaultTs.blur);
        if (slSepia != null) slSepia.setValue(defaultTs.sepia);
        if (slChromatic != null) slChromatic.setValue(defaultTs.chromatic);
        if (slBarrel != null) slBarrel.setValue(defaultTs.barrel);
        if (slPitch != null) slPitch.setValue(defaultTs.pitch);
        if (slSpectralNoise != null) slSpectralNoise.setValue(defaultTs.spectralNoise);
        if (slTemporalJitter != null) slTemporalJitter.setValue(defaultTs.jitterIntensity);
        if (slAmbientNoise != null) slAmbientNoise.setValue(defaultTs.ambientNoiseLevel);
        if (slBitrateRandom != null) slBitrateRandom.setValue(defaultTs.bitrateVariation);
        if (slBorderProgress != null) slBorderProgress.setValue(defaultTs.borderProgressSize);
        if (slPerspectiveTiltX != null) slPerspectiveTiltX.setValue(defaultTs.perspectiveTiltX);
        if (slPerspectiveTiltY != null) slPerspectiveTiltY.setValue(defaultTs.perspectiveTiltY);
        if (slVariableSpeedIntensity != null) slVariableSpeedIntensity.setValue(defaultTs.variableSpeedIntensity);
        if (slLumaPulseIntensity != null) slLumaPulseIntensity.setValue(defaultTs.lumaPulseIntensity);
        if (slSubPixelJitter != null) slSubPixelJitter.setValue(defaultTs.jitterStrength);
    }

    private void setAllTransforms(boolean enabled) {
        SwitchCompat[] all = {
                swFlip, swSpeed, swHue, swBright, swSaturation, swZoom, swRotate,
                swGamma, swNoise, swVignette, swBorder, swTint, swPixelShift,
                swSharpen, swTrim, swVolume, swBlur, swSepia, swChromatic, swBarrel, swPitch,
                swSpectralNoise, swTemporalJitter, swAmbientNoise, swBitrateRandom, swBorderProgress,
                swAspectDistortion, swAudioEq, swMetadataScrubbing, swJunkData,
                swPerspective3D, swVariableSpeed, swLumaPulse, swAudioPhaseShift, swSubPixelJitter
        };
        for (SwitchCompat s : all) {
            if (s != null) s.setChecked(enabled);
        }
        updateTransformCount();
    }

    private void applyPreset(String preset) {
        resetSlidersToDefault();
        switch (preset) {
            case "max":
                setAllTransforms(true);
                Toast.makeText(this, "✓ Max Bypass প্রিসেট সক্রিয়", Toast.LENGTH_SHORT).show();
                break;
            case "subtle":
                setAllTransforms(false);
                if (swFlip != null) swFlip.setChecked(true);
                if (swSpeed != null) swSpeed.setChecked(true);
                if (swHue != null) swHue.setChecked(true);
                if (swBright != null) swBright.setChecked(true);
                if (swNoise != null) swNoise.setChecked(true);
                // Advanced bypass is always good for subtle
                if (swAspectDistortion != null) swAspectDistortion.setChecked(true);
                if (swAudioEq != null) swAudioEq.setChecked(true);
                if (swMetadataScrubbing != null) swMetadataScrubbing.setChecked(true);
                if (swJunkData != null) swJunkData.setChecked(true);
                if (swPerspective3D != null) swPerspective3D.setChecked(true);
                if (swVariableSpeed != null) swVariableSpeed.setChecked(true);
                if (swLumaPulse != null) swLumaPulse.setChecked(true);
                if (swAudioPhaseShift != null) swAudioPhaseShift.setChecked(true);
                if (swSubPixelJitter != null) swSubPixelJitter.setChecked(true);
                Toast.makeText(this, "✓ Subtle প্রিসেট সক্রিয়", Toast.LENGTH_SHORT).show();
                break;
            case "audio":
                setAllTransforms(false);
                if (swSpeed != null) swSpeed.setChecked(true);
                if (swVolume != null) swVolume.setChecked(true);
                if (swPitch != null) swPitch.setChecked(true);
                if (swSpectralNoise != null) swSpectralNoise.setChecked(true);
                if (swAmbientNoise != null) swAmbientNoise.setChecked(true);
                if (swAudioEq != null) swAudioEq.setChecked(true);
                if (swAudioPhaseShift != null) swAudioPhaseShift.setChecked(true);
                if (swMetadataScrubbing != null) swMetadataScrubbing.setChecked(true);
                if (swJunkData != null) swJunkData.setChecked(true);
                Toast.makeText(this, "✓ Audio-focused প্রিসেট সক্রিয়", Toast.LENGTH_SHORT).show();
                break;
            case "video":
                setAllTransforms(false);
                if (swFlip != null) swFlip.setChecked(true);
                if (swZoom != null) swZoom.setChecked(true);
                if (swRotate != null) swRotate.setChecked(true);
                if (swHue != null) swHue.setChecked(true);
                if (swBright != null) swBright.setChecked(true);
                if (swSaturation != null) swSaturation.setChecked(true);
                if (swNoise != null) swNoise.setChecked(true);
                if (swBorder != null) swBorder.setChecked(true);
                if (swChromatic != null) swChromatic.setChecked(true);
                if (swBarrel != null) swBarrel.setChecked(true);
                if (swPerspective3D != null) swPerspective3D.setChecked(true);
                if (swVariableSpeed != null) swVariableSpeed.setChecked(true);
                if (swLumaPulse != null) swLumaPulse.setChecked(true);
                if (swSubPixelJitter != null) swSubPixelJitter.setChecked(true);
                if (swAspectDistortion != null) swAspectDistortion.setChecked(true);
                if (swMetadataScrubbing != null) swMetadataScrubbing.setChecked(true);
                if (swJunkData != null) swJunkData.setChecked(true);
                Toast.makeText(this, "✓ Video-focused প্রিসেট সক্রিয়", Toast.LENGTH_SHORT).show();
                break;
        }
        updateTransformCount();
    }

    private int countEnabled() {
        int count = 0;
        SwitchCompat[] all = {
                swFlip, swSpeed, swHue, swBright, swSaturation, swZoom, swRotate,
                swGamma, swNoise, swVignette, swBorder, swTint, swPixelShift,
                swSharpen, swTrim, swVolume, swBlur, swSepia, swChromatic, swBarrel, swPitch,
                swSpectralNoise, swTemporalJitter, swAmbientNoise, swBitrateRandom, swBorderProgress,
                swAspectDistortion, swAudioEq, swMetadataScrubbing, swJunkData,
                swPerspective3D, swVariableSpeed, swLumaPulse, swAudioPhaseShift, swSubPixelJitter
        };
        for (SwitchCompat s : all) {
            if (s != null && s.isChecked()) count++;
        }
        return count;
    }

    private void updateTransformCount() {
        int count = countEnabled();
        if (transformCount != null) {
            transformCount.setText(count + " টি ট্রান্সফর্ম · " + (count == 35 ? "সব ON" : count + " ON"));
        }
        updateProcessSummary();
    }

    private void updateOutputSummary() {
        if (outputSummary == null) return;

        String resText;
        switch (selectedResolution) {
            case "1080":
                resText = "1080p HD";
                break;
            case "720":
                resText = "720p";
                break;
            case "480":
                resText = "480p";
                break;
            default:
                resText = "অরিজিনাল রেজুলেশন";
                break;
        }

        String ratioText;
        switch (selectedRatio) {
            case "16:9":
                ratioText = "16:9 YouTube";
                break;
            case "9:16":
                ratioText = "9:16 Reels";
                break;
            case "1:1":
                ratioText = "1:1 Square";
                break;
            case "4:3":
                ratioText = "4:3";
                break;
            default:
                ratioText = "অরিজিনাল রেশিও";
                break;
        }

        outputSummary.setText(resText + " · " + ratioText);
    }

    private void updateWatermarkSummary() {
        if (watermarkSummary == null) return;

        switch (wm.mode) {
            case LOGO:
                watermarkSummary.setText("লোগো যোগ হবে");
                watermarkSummary.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
                break;
            case TEXT:
                watermarkSummary.setText("টেক্সট যোগ হবে");
                watermarkSummary.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
                break;
            case BOTH:
                watermarkSummary.setText("লোগো + টেক্সট যোগ হবে");
                watermarkSummary.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
                break;
            default:
                watermarkSummary.setText("ঐচ্ছিক · ব্র্যান্ডিং যোগ করুন");
                watermarkSummary.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                break;
        }
    }

    private void updateLogoRemoverSummary() {
        if (logoRemoverSummary == null) return;

        boolean manualEnabled = swManualLogoRemover != null && swManualLogoRemover.isChecked();

        if (manualEnabled) {
            String removalMethod = "Blur";
            if (removalMethodGroup != null) {
                int checkedId = removalMethodGroup.getCheckedChipId();
                if (checkedId == R.id.chipMethodBlackout) removalMethod = "Blackout";
                else if (checkedId == R.id.chipMethodPixelate) removalMethod = "Pixelate";
            }
            logoRemoverSummary.setText("✓ Manual (" + removalMethod + ")");
            logoRemoverSummary.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
        } else {
            logoRemoverSummary.setText("Manual · ভিডিও থেকে লোগো মুছুন");
            logoRemoverSummary.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void updateProcessSummary() {
        int count = countEnabled();
        if (processSummary != null) {
            String resText = selectedResolution.equals("original") ? "অরিজিনাল" : selectedResolution + "p";
            String features = count + " ট্রান্সফর্ম";

            boolean manualEnabled = swManualLogoRemover != null && swManualLogoRemover.isChecked();
            if (manualEnabled) {
                features += " + Logo Remove";
            }

            // ★ Add Reaction Face
            boolean reactionEnabled = swReactionFace != null && swReactionFace.isChecked() && selectedFaceVideoUri != null;
            if (reactionEnabled) {
                features += " + Reaction";
            }

            processSummary.setText(features + " · " + resText + " রেজুলেশন");
        }
    }

    private void setLogoRemovalPosition(int x, int y) {
        if (slRemovalX != null) slRemovalX.setValue(Math.min(x, slRemovalX.getValueTo()));
        if (slRemovalY != null) slRemovalY.setValue(Math.min(y, slRemovalY.getValueTo()));
        Toast.makeText(this, "Position: " + x + ", " + y, Toast.LENGTH_SHORT).show();
    }

    // Permissions
    private void checkPermissions() {
        PermissionHelper.requestPermissions(this, new PermissionHelper.PermissionCallback() {
            @Override
            public void onPermissionGranted() {
                Log.d(TAG, "Permissions granted");
            }

            @Override
            public void onPermissionDenied() {
                Toast.makeText(MainActivity.this,
                        getString(R.string.permission_required), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.handlePermissionResult(requestCode, grantResults);
    }

    // Video Picker
    private void openVideoPicker() {
        if (!PermissionHelper.hasPermissions(this)) {
            Toast.makeText(this, "প্রথমে Permission দিন", Toast.LENGTH_SHORT).show();
            checkPermissions();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        intent.setType("video/*");
        videoPickerLauncher.launch(intent);
    }

    private void openLogoPicker() {
        if (!PermissionHelper.hasPermissions(this)) {
            Toast.makeText(this, "প্রথমে Permission দিন", Toast.LENGTH_SHORT).show();
            checkPermissions();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        logoPickerLauncher.launch(intent);
    }

    private void loadVideoInfo() {
        if (selectedVideoUri == null) return;

        try {
            Cursor cursor = getContentResolver().query(selectedVideoUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                String fileName = nameIdx >= 0 ? cursor.getString(nameIdx) : "video.mp4";
                long fileSize = sizeIdx >= 0 ? cursor.getLong(sizeIdx) : 0;
                if (fileNameText != null) fileNameText.setText(fileName);
                loadVideoMetadata(selectedVideoUri, fileSize);
                cursor.close();
            }

            if (step1Status != null) {
                step1Status.setText("✓ সম্পন্ন");
                step1Status.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
            }

            if (dropZone != null) {
                dropZone.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    dropZone.setVisibility(View.GONE);
                    if (fileInfoLayout != null) {
                        fileInfoLayout.setVisibility(View.VISIBLE);
                        fileInfoLayout.setAlpha(0f);
                        fileInfoLayout.animate().alpha(1f).setDuration(300).start();
                    }
                    if (optionsLayout != null) {
                        optionsLayout.setVisibility(View.VISIBLE);
                        optionsLayout.setAlpha(0f);
                        optionsLayout.animate().alpha(1f).setDuration(300).setStartDelay(100).start();
                    }
                }).start();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading video", e);
            Toast.makeText(this, "ভিডিও লোড ব্যর্থ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadVideoMetadata(Uri uri, long fileSize) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String durationFormatted = "0:00";
            if (durationStr != null) {
                videoDurationMs = Long.parseLong(durationStr);
                long s = TimeUnit.MILLISECONDS.toSeconds(videoDurationMs) % 60;
                long m = TimeUnit.MILLISECONDS.toMinutes(videoDurationMs) % 60;
                long h = TimeUnit.MILLISECONDS.toHours(videoDurationMs);
                durationFormatted = h > 0
                        ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                        : String.format(Locale.US, "%d:%02d", m, s);
            }

            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String ht = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String res = (w != null && ht != null) ? " · " + w + "×" + ht : "";

            if (fileDetailText != null)
                fileDetailText.setText(formatFileSize(fileSize) + " · " + durationFormatted + res);

            videoThumbnail = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (videoThumbnail == null)
                videoThumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (videoThumbnail != null && previewThumbnail != null)
                previewThumbnail.setImageBitmap(videoThumbnail);

        } catch (Exception e) {
            Log.e(TAG, "Error getting metadata", e);
            if (fileDetailText != null) fileDetailText.setText(formatFileSize(fileSize));
        } finally {
            if (retriever != null) try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        // Reset resolution and ratio to original for each new video
        if (resolutionGroup != null) resolutionGroup.check(R.id.chipOriginal);
        if (ratioGroup != null) ratioGroup.check(R.id.chipRatioOriginal);
        selectedResolution = "original";
        selectedRatio = "original";
        updateOutputSummary();
    }

    private void loadLogoImage(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "লোগো খুলতে পারছি না", Toast.LENGTH_SHORT).show();
                return;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            is.close();

            int ss = 1;
            if (opts.outWidth > MAX_LOGO_SIZE * 2 || opts.outHeight > MAX_LOGO_SIZE * 2)
                ss = Math.max(opts.outWidth / (MAX_LOGO_SIZE * 2), opts.outHeight / (MAX_LOGO_SIZE * 2));

            is = getContentResolver().openInputStream(uri);
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = ss;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            is.close();

            if (bmp == null) {
                Toast.makeText(this, "লোগো ডিকোড ব্যর্থ", Toast.LENGTH_SHORT).show();
                return;
            }

            if (bmp.getWidth() > MAX_LOGO_SIZE) {
                float scale = (float) MAX_LOGO_SIZE / bmp.getWidth();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, MAX_LOGO_SIZE, Math.round(bmp.getHeight() * scale), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            wm.logoBitmap = bmp;
            WatermarkConfig.sharedLogoBitmap = bmp;

            if (logoPreview != null) {
                logoPreview.setImageBitmap(bmp);
                logoPreview.setVisibility(View.VISIBLE);
            }
            if (logoRemoveBtn != null) logoRemoveBtn.setVisibility(View.VISIBLE);
            if (logoDropText != null)
                logoDropText.setText("✓ লোগো সফল (" + bmp.getWidth() + "×" + bmp.getHeight() + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error loading logo", e);
            Toast.makeText(this, "লোগো লোড ব্যর্থ", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeLogo() {
        if (wm.logoBitmap != null && wm.logoBitmap != WatermarkConfig.sharedLogoBitmap) {
            wm.logoBitmap.recycle();
        }
        wm.logoBitmap = null;
        WatermarkConfig.sharedLogoBitmap = null;
        if (logoPreview != null) {
            logoPreview.setImageBitmap(null);
            logoPreview.setVisibility(View.GONE);
        }
        if (logoRemoveBtn != null) logoRemoveBtn.setVisibility(View.GONE);
        if (logoDropText != null) logoDropText.setText("📷 লোগো বেছে নিন");
    }

    private void showColorPicker() {
        String[] names = {"সাদা", "হলুদ", "লাল", "সবুজ", "নীল", "কমলা", "বেগুনি", "গোলাপি", "কালো"};
        int[] vals = {0xFFFFFFFF, 0xFFFFFF00, 0xFFFF4444, 0xFF44FF88, 0xFF4488FF, 0xFFFF8844, 0xFF8844FF, 0xFFFF44AA, 0xFF000000};

        new android.app.AlertDialog.Builder(this)
                .setTitle("টেক্সট রং নির্বাচন")
                .setItems(names, (d, w) -> {
                    wmColorInt = vals[w];
                    wm.textColor = wmColorInt;
                    if (wmColorSwatch != null) wmColorSwatch.setBackgroundColor(wmColorInt);
                })
                .show();
    }

    // Collect Settings & Start Processing
    private void collectSettings() {
        // Original transforms
        ts.flipEnabled = swFlip != null && swFlip.isChecked();
        ts.speedEnabled = swSpeed != null && swSpeed.isChecked();
        ts.speed = slSpeed != null ? slSpeed.getValue() : 1.0f;
        ts.hueEnabled = swHue != null && swHue.isChecked();
        ts.hue = slHue != null ? slHue.getValue() : 0f;
        ts.brightEnabled = swBright != null && swBright.isChecked();
        ts.bright = slBright != null ? slBright.getValue() : 1.0f;
        ts.satEnabled = swSaturation != null && swSaturation.isChecked();
        ts.saturation = slSaturation != null ? slSaturation.getValue() : 1.0f;
        ts.zoomEnabled = swZoom != null && swZoom.isChecked();
        ts.zoom = slZoom != null ? slZoom.getValue() : 1.0f;
        ts.rotateEnabled = swRotate != null && swRotate.isChecked();
        ts.rotate = slRotate != null ? slRotate.getValue() : 0f;
        ts.gammaEnabled = swGamma != null && swGamma.isChecked();
        ts.gamma = slGamma != null ? slGamma.getValue() : 1.0f;
        ts.noiseEnabled = swNoise != null && swNoise.isChecked();
        ts.noise = slNoise != null ? slNoise.getValue() : 0f;
        ts.vignetteEnabled = swVignette != null && swVignette.isChecked();
        ts.vignette = slVignette != null ? slVignette.getValue() : 0f;
        ts.borderEnabled = swBorder != null && swBorder.isChecked();
        ts.border = slBorder != null ? (int) slBorder.getValue() : 0;
        ts.tintEnabled = swTint != null && swTint.isChecked();
        ts.tint = slTint != null ? slTint.getValue() : 0f;
        ts.pixelShiftEnabled = swPixelShift != null && swPixelShift.isChecked();
        ts.pixelShift = slPixelShift != null ? (int) slPixelShift.getValue() : 0;
        ts.sharpenEnabled = swSharpen != null && swSharpen.isChecked();
        ts.sharpen = slSharpen != null ? slSharpen.getValue() : 0f;
        ts.trimEnabled = swTrim != null && swTrim.isChecked();
        ts.trim = slTrim != null ? slTrim.getValue() : 0f;
        ts.volumeEnabled = swVolume != null && swVolume.isChecked();
        ts.volume = slVolume != null ? slVolume.getValue() : 1.0f;
        ts.blurEnabled = swBlur != null && swBlur.isChecked();
        ts.blur = slBlur != null ? slBlur.getValue() : 0f;
        ts.sepiaEnabled = swSepia != null && swSepia.isChecked();
        ts.sepia = slSepia != null ? slSepia.getValue() : 0f;
        ts.chromaticEnabled = swChromatic != null && swChromatic.isChecked();
        ts.chromatic = slChromatic != null ? slChromatic.getValue() : 1.0f;
        ts.barrelEnabled = swBarrel != null && swBarrel.isChecked();
        ts.barrel = slBarrel != null ? slBarrel.getValue() : 0.045f;
        ts.pitchEnabled = swPitch != null && swPitch.isChecked();
        ts.pitch = slPitch != null ? slPitch.getValue() : 1.012f;
        // Border Progress (NEW)
        ts.borderProgressEnabled = swBorderProgress != null && swBorderProgress.isChecked();
        ts.borderProgressSize = slBorderProgress != null ? (int) slBorderProgress.getValue() : 4;
        ts.borderProgressColor = borderProgressColorInt;

        // Additional transforms
        ts.spectralNoiseEnabled = swSpectralNoise != null && swSpectralNoise.isChecked();
        ts.spectralNoise = slSpectralNoise != null ? slSpectralNoise.getValue() : 0.002f;
        ts.temporalJitterEnabled = swTemporalJitter != null && swTemporalJitter.isChecked();
        ts.jitterIntensity = slTemporalJitter != null ? slTemporalJitter.getValue() : 0.02f;
        ts.ambientNoiseEnabled = swAmbientNoise != null && swAmbientNoise.isChecked();
        ts.ambientNoiseLevel = slAmbientNoise != null ? slAmbientNoise.getValue() : 0.005f;
        ts.bitrateRandomEnabled = swBitrateRandom != null && swBitrateRandom.isChecked();
        ts.bitrateVariation = slBitrateRandom != null ? slBitrateRandom.getValue() : 0.15f;

        // Advanced Bypass (NEW)
        ts.aspectDistortionEnabled = swAspectDistortion != null && swAspectDistortion.isChecked();
        ts.audioEqEnabled = swAudioEq != null && swAudioEq.isChecked();
        ts.metadataScrubbingEnabled = swMetadataScrubbing != null && swMetadataScrubbing.isChecked();
        ts.junkDataEnabled = swJunkData != null && swJunkData.isChecked();

        // Ultimate Bypass (Version 7.0 - NEW)
        ts.perspective3DEnabled = swPerspective3D != null && swPerspective3D.isChecked();
        ts.perspectiveTiltX = slPerspectiveTiltX != null ? slPerspectiveTiltX.getValue() : 1.2f;
        ts.perspectiveTiltY = slPerspectiveTiltY != null ? slPerspectiveTiltY.getValue() : 0.8f;
        ts.variableSpeedEnabled = swVariableSpeed != null && swVariableSpeed.isChecked();
        ts.variableSpeedIntensity = slVariableSpeedIntensity != null ? slVariableSpeedIntensity.getValue() : 1.5f;
        ts.lumaPulseEnabled = swLumaPulse != null && swLumaPulse.isChecked();
        ts.lumaPulseIntensity = slLumaPulseIntensity != null ? slLumaPulseIntensity.getValue() : 6.0f;
        ts.audioPhaseShiftEnabled = swAudioPhaseShift != null && swAudioPhaseShift.isChecked();
        ts.subPixelJitterEnabled = swSubPixelJitter != null && swSubPixelJitter.isChecked();
        ts.jitterStrength = slSubPixelJitter != null ? slSubPixelJitter.getValue() : 4.0f;

        // ═══════════════════════════════════════════════════════════════
        // MANUAL OVERRIDE
        // ═══════════════════════════════════════════════════════════════
        ts.useManualRegion = swManualLogoRemover != null && swManualLogoRemover.isChecked();
        if (ts.useManualRegion) {
            ts.manualX = slRemovalX != null ? (int) slRemovalX.getValue() : 0;
            ts.manualY = slRemovalY != null ? (int) slRemovalY.getValue() : 0;
            ts.manualWidth = slRemovalWidth != null ? (int) slRemovalWidth.getValue() : 100;
            ts.manualHeight = slRemovalHeight != null ? (int) slRemovalHeight.getValue() : 100;
        } else {
            ts.manualX = 0;
            ts.manualY = 0;
            ts.manualWidth = 0;
            ts.manualHeight = 0;
        }

        // ═══════════════════════════════════════════════════════════════
        // REMOVAL METHOD
        // ═══════════════════════════════════════════════════════════════
        ts.removalBlurIntensity = slRemovalBlur != null ? slRemovalBlur.getValue() : 15f;
        if (removalMethodGroup != null) {
            int methodId = removalMethodGroup.getCheckedChipId();
            if (methodId == R.id.chipMethodBlur) ts.logoRemovalMethod = 0;
            else if (methodId == R.id.chipMethodBlackout) ts.logoRemovalMethod = 1;
            else if (methodId == R.id.chipMethodPixelate) ts.logoRemovalMethod = 2;
        }

        ts.syncLegacyFields();

        // Watermark
        wm.text = etWmText != null && etWmText.getText() != null ? etWmText.getText().toString().trim() : "";
        wm.logoSize = slLogoSize != null ? (int) slLogoSize.getValue() : 10;
        wm.logoOpacity = slLogoOpacity != null ? (int) slLogoOpacity.getValue() : 80;
        wm.fontSize = slWmFont != null ? (int) slWmFont.getValue() : 22;
        wm.textOpacity = slWmOpacity != null ? (int) slWmOpacity.getValue() : 70;
        wm.textColor = wmColorInt;

        wm.dynamicPosition = swDynamicWatermark != null && swDynamicWatermark.isChecked();
        wm.positionChangeIntervalSec = slWatermarkInterval != null ? (int) slWatermarkInterval.getValue() : 5;

        wm.syncLogo();

        // ═══════════════════════════════════════════════════════════════
// REACTION FACE
// ═══════════════════════════════════════════════════════════════
        ts.reactionFaceEnabled = swReactionFace != null && swReactionFace.isChecked() && selectedFaceVideoUri != null;
        ts.reactionFaceAudioEnabled = swFaceAudio != null && swFaceAudio.isChecked();
        ts.reactionFaceUri = selectedFaceVideoUri != null ? selectedFaceVideoUri.toString() : null;
        ts.reactionFacePosition = selectedFacePosition;
        ts.reactionFaceSize = slFaceVideoSize != null ? (int) slFaceVideoSize.getValue() : 10;
        ts.reactionFaceCornerRadius = slFaceCornerRadius != null ? (int) slFaceCornerRadius.getValue() : 100;
    }

    private void startProcessing() {
        collectSettings();
        if (selectedVideoUri == null) {
            Toast.makeText(this, "ভিডিও সিলেক্ট করুন", Toast.LENGTH_SHORT).show();
            return;
        }

        if (resolutionGroup != null) {
            int resId = resolutionGroup.getCheckedChipId();
            if (resId == R.id.chip1080p) selectedResolution = "1080";
            else if (resId == R.id.chip720p) selectedResolution = "720";
            else if (resId == R.id.chip480p) selectedResolution = "480";
            else selectedResolution = "original";
        }

        if (ratioGroup != null) {
            int ratId = ratioGroup.getCheckedChipId();
            if (ratId == R.id.chip16_9) selectedRatio = "16:9";
            else if (ratId == R.id.chip9_16) selectedRatio = "9:16";
            else if (ratId == R.id.chip1_1) selectedRatio = "1:1";
            else if (ratId == R.id.chip4_3) selectedRatio = "4:3";
            else selectedRatio = "original";
        }

        Intent intent = new Intent(this, ProcessActivity.class);
        intent.putExtra("VIDEO_URI", selectedVideoUri.toString());
        intent.putExtra("RESOLUTION", selectedResolution);
        intent.putExtra("RATIO", selectedRatio);
        intent.putExtra("TRANSFORM_SETTINGS", ts);
        intent.putExtra("WATERMARK_CONFIG", wm);
        startActivity(intent);
    }

    // Utility Methods
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void resetAll() {
        selectedVideoUri = null;
        videoDurationMs = 0;

        if (videoThumbnail != null && !videoThumbnail.isRecycled()) {
            videoThumbnail.recycle();
            videoThumbnail = null;
        }
        if (previewThumbnail != null) previewThumbnail.setImageBitmap(null);

        if (step1Status != null) {
            step1Status.setText("⏳ অপেক্ষমান");
            step1Status.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }

        if (fileInfoLayout != null) {
            fileInfoLayout.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                fileInfoLayout.setVisibility(View.GONE);
                if (optionsLayout != null) optionsLayout.setVisibility(View.GONE);
                if (dropZone != null) {
                    dropZone.setVisibility(View.VISIBLE);
                    dropZone.setAlpha(0f);
                    dropZone.animate().alpha(1f).setDuration(300).start();
                }
            }).start();
        }

        removeLogo();
        if (etWmText != null) etWmText.setText("");
        wm.mode = WatermarkConfig.Mode.NONE;

        if (resolutionGroup != null) resolutionGroup.check(R.id.chipOriginal);
        if (ratioGroup != null) ratioGroup.check(R.id.chipRatioOriginal);
        if (overlayModeGroup != null) overlayModeGroup.check(R.id.chipOverlayNone);

        selectedResolution = "original";
        selectedRatio = "original";

        if (logoSection != null) logoSection.setVisibility(View.GONE);
        if (textSection != null) textSection.setVisibility(View.GONE);

        if (swManualLogoRemover != null) swManualLogoRemover.setChecked(false);

        // Reset Advanced Bypass
        if (swAspectDistortion != null) swAspectDistortion.setChecked(true);
        if (swAudioEq != null) swAudioEq.setChecked(true);
        if (swMetadataScrubbing != null) swMetadataScrubbing.setChecked(true);
        if (swJunkData != null) swJunkData.setChecked(true);

        outputExpanded = false;
        transformExpanded = false;
        watermarkExpanded = false;
        logoRemoverExpanded = false;
        if (outputContent != null) outputContent.setVisibility(View.GONE);
        if (transformContent != null) transformContent.setVisibility(View.GONE);
        if (watermarkContent != null) watermarkContent.setVisibility(View.GONE);
        if (logoRemoverContent != null) logoRemoverContent.setVisibility(View.GONE);
        if (outputToggle != null) outputToggle.setText("▼");
        if (transformToggle != null) transformToggle.setText("▼");
        if (watermarkToggle != null) watermarkToggle.setText("▼");
        if (logoRemoverToggle != null) logoRemoverToggle.setText("▼");

        // Reset Reaction Face
        if (swReactionFace != null) swReactionFace.setChecked(false);
        if (swFaceAudio != null) swFaceAudio.setChecked(true);
        removeFaceVideo();
        selectedFacePosition = 0;
        if (chipFacePosTopLeft != null) chipFacePosTopLeft.setChecked(true);
        if (chipFacePosTopRight != null) chipFacePosTopRight.setChecked(false);
        if (chipFacePosBottomLeft != null) chipFacePosBottomLeft.setChecked(false);
        if (chipFacePosBottomRight != null) chipFacePosBottomRight.setChecked(false);
        if (slFaceVideoSize != null) slFaceVideoSize.setValue(10);
        if (slFaceCornerRadius != null) slFaceVideoSize.setValue(100);

        // Reset expansion state
        reactionFaceExpanded = false;
        if (reactionFaceContent != null) reactionFaceContent.setVisibility(View.GONE);
        if (reactionFaceToggle != null) reactionFaceToggle.setText("▼");

        updateOutputSummary();
        updateWatermarkSummary();
        updateLogoRemoverSummary();
        updateTransformCount();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!PermissionHelper.hasPermissions(this)) {
            Log.w(TAG, "Permissions revoked");
            if (selectVideoBtn != null) selectVideoBtn.setEnabled(false);
            if (changeVideoBtn != null) changeVideoBtn.setEnabled(false);
        } else {
            if (selectVideoBtn != null) selectVideoBtn.setEnabled(true);
            if (changeVideoBtn != null) changeVideoBtn.setEnabled(true);
        }
    }

    private void setAppVersion() {
        if (tvAppVersion == null) return;

        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            tvAppVersion.setText("v" + versionName);
        } catch (Exception e) {
            tvAppVersion.setText("v" + VERSION_NAME);
        }
    }

    private void showAppInfoDialog() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_info);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            android.util.DisplayMetrics displayMetrics = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int maxHeight = (int) (displayMetrics.heightPixels * 0.9);

            dialog.getWindow().setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    Math.min(android.view.WindowManager.LayoutParams.WRAP_CONTENT, maxHeight)
            );
        }

        dialog.setCancelable(true);

        Button btnClose = dialog.findViewById(R.id.btnCloseDialog);
        ImageButton btnCloseX = dialog.findViewById(R.id.btnCloseDialogX);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnCloseX.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════
// REACTION FACE METHODS
// ═══════════════════════════════════════════════════════════════

    private void toggleReactionFaceSection() {
        reactionFaceExpanded = !reactionFaceExpanded;
        if (reactionFaceContent != null) {
            reactionFaceContent.setVisibility(reactionFaceExpanded ? View.VISIBLE : View.GONE);
            if (reactionFaceExpanded) {
                reactionFaceContent.setAlpha(0f);
                reactionFaceContent.animate().alpha(1f).setDuration(300).start();
            }
        }
        if (reactionFaceToggle != null) {
            reactionFaceToggle.setText(reactionFaceExpanded ? "▲" : "▼");
        }

        // Show the section when expanded
        if (reactionFaceSection != null && reactionFaceExpanded) {
            reactionFaceSection.setVisibility(View.VISIBLE);
        }
    }

    private void applyReactionFaceEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.5f;

        if (faceVideoPickerArea != null) faceVideoPickerArea.setAlpha(alpha);
        if (faceAudioSection != null) faceAudioSection.setAlpha(alpha);
        if (facePositionSection != null) facePositionSection.setAlpha(alpha);
        if (faceSizeSection != null) faceSizeSection.setAlpha(alpha);
        if (faceCornerSection != null) faceCornerSection.setAlpha(alpha);

        // Enable/disable sliders/switches
        if (swFaceAudio != null) {
            swFaceAudio.setEnabled(enabled);
        }
        if (slFaceVideoSize != null) {
            slFaceVideoSize.setEnabled(enabled);
        }
        if (slFaceCornerRadius != null) {
            slFaceCornerRadius.setEnabled(enabled);
        }

        // Show reactionFaceSection
        if (reactionFaceSection != null) {
            reactionFaceSection.setVisibility(View.VISIBLE);
        }
    }

    private void openFaceVideoPicker() {
        if (!PermissionHelper.hasPermissions(this)) {
            Toast.makeText(this, "প্রথমে Permission দিন", Toast.LENGTH_SHORT).show();
            checkPermissions();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        intent.setType("video/*");
        faceVideoPickerLauncher.launch(intent);
    }

    private void loadFaceVideoInfo() {
        if (selectedFaceVideoUri == null) return;

        try {
            Cursor cursor = getContentResolver().query(selectedFaceVideoUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String fileName = nameIdx >= 0 ? cursor.getString(nameIdx) : "face_video.mp4";
                if (faceVideoName != null) faceVideoName.setText(fileName);
                cursor.close();
            }

            // Get thumbnail and duration
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(this, selectedFaceVideoUri);

                // Duration
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String durationFormatted = "0:00";
                if (durationStr != null) {
                    long durationMs = Long.parseLong(durationStr);
                    long s = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60;
                    long m = TimeUnit.MILLISECONDS.toMinutes(durationMs);
                    durationFormatted = String.format(Locale.US, "%d:%02d", m, s);
                }

                // Resolution
                String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String res = (w != null && h != null) ? w + "×" + h : "";

                if (faceVideoDetail != null) {
                    faceVideoDetail.setText(durationFormatted + " · " + res);
                }

                // Thumbnail
                if (faceVideoThumbnailBitmap != null && !faceVideoThumbnailBitmap.isRecycled()) {
                    faceVideoThumbnailBitmap.recycle();
                }
                faceVideoThumbnailBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (faceVideoThumbnailBitmap != null && faceVideoThumbnail != null) {
                    faceVideoThumbnail.setImageBitmap(faceVideoThumbnailBitmap);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting face video metadata", e);
                if (faceVideoDetail != null) faceVideoDetail.setText("Video loaded");
            } finally {
                if (retriever != null) try { retriever.release(); } catch (Exception ignored) {}
            }

            // Update UI
            if (faceVideoDropZone != null) faceVideoDropZone.setVisibility(View.GONE);
            if (faceVideoInfoLayout != null) {
                faceVideoInfoLayout.setVisibility(View.VISIBLE);
                faceVideoInfoLayout.setAlpha(0f);
                faceVideoInfoLayout.animate().alpha(1f).setDuration(300).start();
            }

            updateReactionFaceSummary();

        } catch (Exception e) {
            Log.e(TAG, "Error loading face video", e);
            Toast.makeText(this, "Face video লোড ব্যর্থ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void removeFaceVideo() {
        selectedFaceVideoUri = null;

        if (faceVideoThumbnailBitmap != null && !faceVideoThumbnailBitmap.isRecycled()) {
            faceVideoThumbnailBitmap.recycle();
            faceVideoThumbnailBitmap = null;
        }

        if (faceVideoThumbnail != null) faceVideoThumbnail.setImageBitmap(null);
        if (faceVideoInfoLayout != null) faceVideoInfoLayout.setVisibility(View.GONE);
        if (faceVideoDropZone != null) faceVideoDropZone.setVisibility(View.VISIBLE);

        updateReactionFaceSummary();
    }

    private void updateReactionFaceSummary() {
        if (reactionFaceSummary == null) return;

        boolean enabled = swReactionFace != null && swReactionFace.isChecked();
        boolean hasVideo = selectedFaceVideoUri != null;

        if (enabled && hasVideo) {
            String posText;
            switch (selectedFacePosition) {
                case 1: posText = "Top-Right"; break;
                case 2: posText = "Bottom-Left"; break;
                case 3: posText = "Bottom-Right"; break;
                default: posText = "Top-Left"; break;
            }
            int size = slFaceVideoSize != null ? (int) slFaceVideoSize.getValue() : 10;
            reactionFaceSummary.setText("✓ " + posText + " · " + size + "%");
            reactionFaceSummary.setTextColor(ContextCompat.getColor(this, R.color.colorAccent2));
        } else if (enabled) {
            reactionFaceSummary.setText("Face video সিলেক্ট করুন");
            reactionFaceSummary.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        } else {
            reactionFaceSummary.setText("ঐচ্ছিক · আপনার ফেস ভিডিও ওভারলে করুন");
            reactionFaceSummary.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }

        updateProcessSummary();
    }
}

