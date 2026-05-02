package com.example.uniquecreator.views;

import static com.karumi.dexter.BuildConfig.VERSION_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.uniquecreator.BaseActivity;
import com.example.uniquecreator.R;
import com.example.uniquecreator.helper.SharedPrefHelper;
import com.example.uniquecreator.model.ApiRequest;
import com.example.uniquecreator.security.SecurityHelper;
import com.example.uniquecreator.viewModel.UserCheckViewModel;

public class SplashActivity extends BaseActivity {

    private static final long SPLASH_DURATION = 3000;
    private static final long ANIMATION_DURATION = 800;
    private static final long API_TIMEOUT = 10000;

    // Views
    private View bgGradient1, bgGradient2, bgGradient3;
    private FrameLayout logoContainer;
    private View ring1, ring2, ring3;
    private View logoPulse, logoBg;
    private TextView logoIcon;
    private View particle1, particle2, particle3, particle4;
    private TextView appName, tagline;
    private LinearLayout featurePills;
    private TextView pill1, pill2, pill3;
    private View progressBar;
    private TextView loadingText, versionText;
    private LinearLayout mainContent, bottomSection;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isAnimating = true;
    private boolean isApiCallCompleted = false;
    private boolean isApiSuccess = false;

    private SharedPrefHelper prefHelper;
    private UserCheckViewModel viewModel;

    @Override
    protected SecurityCheckMode getSecurityCheckMode() {
        return SecurityCheckMode.ALWAYS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hideSystemUI();
        setContentView(R.layout.activity_splash);

        // Debug build এ একবার run করো:
        String debugHash = SecurityHelper.getInstance(this).getAppSignatureHash();
        Log.d("DEBUG_HASH", debugHash);

        // Release build এ একবার run করো:
        String releaseHash = SecurityHelper.getInstance(this).getAppSignatureHash();
        Log.d("RELEASE_HASH", releaseHash);

        prefHelper = SharedPrefHelper.getInstance(this);
        viewModel = new ViewModelProvider(this).get(UserCheckViewModel.class);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
            }
        });

        initViews();
        setInitialStates();
        startAnimations();

        // Check if already verified
        if (prefHelper.isVerified()) {
            // Already verified - skip API call, go directly to main
            handler.postDelayed(this::navigateToMain, SPLASH_DURATION);
        } else {
            // First time - need API verification
            checkAndCallApi();
        }
    }

    private void checkAndCallApi() {
        if (!isNetworkAvailable()) {
            handler.postDelayed(this::showErrorState, SPLASH_DURATION - 500);
            return;
        }

        Runnable timeoutRunnable = () -> {
            if (!isApiCallCompleted) {
                isApiCallCompleted = true;
                showErrorState();
            }
        };
        handler.postDelayed(timeoutRunnable, API_TIMEOUT);

        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;

        String deviceName;
        if (model.startsWith(manufacturer)) {
            deviceName = model;
        } else {
            deviceName = manufacturer + " " + model;
        }

        try {
            ApiRequest request = new ApiRequest(Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID), deviceName);
            viewModel.sendData(request).observe(this, status -> {
                handler.removeCallbacks(timeoutRunnable);

                if (isApiCallCompleted) return;
                isApiCallCompleted = true;

                if (status != null && status.equals("success")) {
                    isApiSuccess = true;

                    // Save verified flag
                    prefHelper.setVerified(true);
                    prefHelper.setDeviceId(Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
                    handler.postDelayed(this::navigateToMain, SPLASH_DURATION);
                } else {
                    showErrorState();
                }
            });
        } catch (Exception e) {
            handler.removeCallbacksAndMessages(null);
            isApiCallCompleted = true;
            handler.postDelayed(this::showErrorState, 500);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return false;
            }

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) {
                return false;
            }

            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } else {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
    }

    private void showErrorState() {
        runOnUiThread(() -> {
            if (loadingText != null) {
                loadingText.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .withEndAction(() -> {
                            loadingText.setText("আপনি সিস্টেমের অন্তর্ভুক্ত নন");
                            loadingText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light));
                            loadingText.animate()
                                    .alpha(1f)
                                    .setDuration(150)
                                    .start();
                        })
                        .start();
            }

            if (progressBar != null) {
                progressBar.setBackgroundTintList(
                        ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                );
            }
        });
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
    }

    private void initViews() {
        bgGradient1 = findViewById(R.id.bgGradient1);
        bgGradient2 = findViewById(R.id.bgGradient2);
        bgGradient3 = findViewById(R.id.bgGradient3);

        logoContainer = findViewById(R.id.logoContainer);
        ring1 = findViewById(R.id.ring1);
        ring2 = findViewById(R.id.ring2);
        ring3 = findViewById(R.id.ring3);
        logoPulse = findViewById(R.id.logoPulse);
        logoBg = findViewById(R.id.logoBg);
        logoIcon = findViewById(R.id.logoIcon);

        particle1 = findViewById(R.id.particle1);
        particle2 = findViewById(R.id.particle2);
        particle3 = findViewById(R.id.particle3);
        particle4 = findViewById(R.id.particle4);

        appName = findViewById(R.id.appName);
        tagline = findViewById(R.id.tagline);
        featurePills = findViewById(R.id.featurePills);
        pill1 = findViewById(R.id.pill1);
        pill2 = findViewById(R.id.pill2);
        pill3 = findViewById(R.id.pill3);

        progressBar = findViewById(R.id.progressBar);
        loadingText = findViewById(R.id.loadingText);
        mainContent = findViewById(R.id.mainContent);
        bottomSection = findViewById(R.id.bottomSection);

        versionText = findViewById(R.id.versionText);
        setAppVersion();
    }

    private void setAppVersion() {
        if (versionText == null) return;

        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            versionText.setText("v" + versionName);
        } catch (Exception e) {
            versionText.setText("v" + VERSION_NAME);
        }
    }

    private void setInitialStates() {
        logoContainer.setAlpha(0f);
        logoContainer.setScaleX(0.5f);
        logoContainer.setScaleY(0.5f);

        appName.setAlpha(0f);
        appName.setTranslationY(30f);

        tagline.setAlpha(0f);
        tagline.setTranslationY(20f);

        pill1.setAlpha(0f);
        pill1.setTranslationY(20f);
        pill2.setAlpha(0f);
        pill2.setTranslationY(20f);
        pill3.setAlpha(0f);
        pill3.setTranslationY(20f);

        bottomSection.setAlpha(0f);
        bottomSection.setTranslationY(30f);

        progressBar.getLayoutParams().width = 0;
    }

    private void startAnimations() {
        animateBackgrounds();
        handler.postDelayed(this::animateLogoEntrance, 200);
        handler.postDelayed(this::animateProgress, 500);
    }

    private void animateBackgrounds() {
        ObjectAnimator bg1X = ObjectAnimator.ofFloat(bgGradient1, "translationX", -100f, -80f, -100f);
        ObjectAnimator bg1Y = ObjectAnimator.ofFloat(bgGradient1, "translationY", -100f, -120f, -100f);
        bg1X.setDuration(8000);
        bg1Y.setDuration(6000);
        bg1X.setRepeatCount(ValueAnimator.INFINITE);
        bg1Y.setRepeatCount(ValueAnimator.INFINITE);
        bg1X.setInterpolator(new AccelerateDecelerateInterpolator());
        bg1Y.setInterpolator(new AccelerateDecelerateInterpolator());
        bg1X.start();
        bg1Y.start();

        ObjectAnimator bg2X = ObjectAnimator.ofFloat(bgGradient2, "translationX", 80f, 100f, 80f);
        ObjectAnimator bg2Y = ObjectAnimator.ofFloat(bgGradient2, "translationY", 80f, 60f, 80f);
        bg2X.setDuration(7000);
        bg2Y.setDuration(9000);
        bg2X.setRepeatCount(ValueAnimator.INFINITE);
        bg2Y.setRepeatCount(ValueAnimator.INFINITE);
        bg2X.setInterpolator(new AccelerateDecelerateInterpolator());
        bg2Y.setInterpolator(new AccelerateDecelerateInterpolator());
        bg2X.start();
        bg2Y.start();

        ObjectAnimator bg3X = ObjectAnimator.ofFloat(bgGradient3, "translationX", 50f, 30f, 50f);
        ObjectAnimator bg3Y = ObjectAnimator.ofFloat(bgGradient3, "translationY", 150f, 170f, 150f);
        bg3X.setDuration(6000);
        bg3Y.setDuration(8000);
        bg3X.setRepeatCount(ValueAnimator.INFINITE);
        bg3Y.setRepeatCount(ValueAnimator.INFINITE);
        bg3X.setInterpolator(new AccelerateDecelerateInterpolator());
        bg3Y.setInterpolator(new AccelerateDecelerateInterpolator());
        bg3X.start();
        bg3Y.start();
    }

    private void animateLogoEntrance() {
        AnimatorSet logoEntrance = new AnimatorSet();
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logoContainer, "alpha", 0f, 1f);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logoContainer, "scaleX", 0.5f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logoContainer, "scaleY", 0.5f, 1f);
        logoEntrance.playTogether(logoAlpha, logoScaleX, logoScaleY);
        logoEntrance.setDuration(ANIMATION_DURATION);
        logoEntrance.setInterpolator(new OvershootInterpolator(1.2f));
        logoEntrance.start();

        animateRings();
        animatePulse();
        animateParticles();
        animateLogoIcon();

        handler.postDelayed(this::animateAppName, 300);
        handler.postDelayed(this::animateTagline, 450);
        handler.postDelayed(this::animatePills, 600);
        handler.postDelayed(this::animateBottomSection, 700);
    }

    private void animateRings() {
        ObjectAnimator ring1Rotation = ObjectAnimator.ofFloat(ring1, "rotation", 0f, 360f);
        ring1Rotation.setDuration(10000);
        ring1Rotation.setRepeatCount(ValueAnimator.INFINITE);
        ring1Rotation.setInterpolator(new LinearInterpolator());
        ring1Rotation.start();

        ObjectAnimator ring2Rotation = ObjectAnimator.ofFloat(ring2, "rotation", 0f, -360f);
        ring2Rotation.setDuration(15000);
        ring2Rotation.setRepeatCount(ValueAnimator.INFINITE);
        ring2Rotation.setInterpolator(new LinearInterpolator());
        ring2Rotation.start();

        ObjectAnimator ring3Rotation = ObjectAnimator.ofFloat(ring3, "rotation", 0f, 360f);
        ring3Rotation.setDuration(20000);
        ring3Rotation.setRepeatCount(ValueAnimator.INFINITE);
        ring3Rotation.setInterpolator(new LinearInterpolator());
        ring3Rotation.start();

        animateRingScale(ring1, 1f, 1.05f, 2000);
        animateRingScale(ring2, 1f, 1.08f, 2500);
        animateRingScale(ring3, 1f, 1.1f, 3000);
    }

    private void animateRingScale(View ring, float from, float to, long duration) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ring, "scaleX", from, to, from);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ring, "scaleY", from, to, from);
        scaleX.setDuration(duration);
        scaleY.setDuration(duration);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleY.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleX.start();
        scaleY.start();
    }

    private void animatePulse() {
        ObjectAnimator pulseScaleX = ObjectAnimator.ofFloat(logoPulse, "scaleX", 1f, 1.4f, 1f);
        ObjectAnimator pulseScaleY = ObjectAnimator.ofFloat(logoPulse, "scaleY", 1f, 1.4f, 1f);
        ObjectAnimator pulseAlpha = ObjectAnimator.ofFloat(logoPulse, "alpha", 0.6f, 0f, 0.6f);

        AnimatorSet pulseSet = new AnimatorSet();
        pulseSet.playTogether(pulseScaleX, pulseScaleY, pulseAlpha);
        pulseSet.setDuration(1500);
        pulseSet.setInterpolator(new AccelerateDecelerateInterpolator());

        pulseSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isAnimating) {
                    pulseSet.start();
                }
            }
        });
        pulseSet.start();
    }

    private void animateLogoIcon() {
        ObjectAnimator iconRotation = ObjectAnimator.ofFloat(logoIcon, "rotation", 0f, 360f);
        iconRotation.setDuration(20000);
        iconRotation.setRepeatCount(ValueAnimator.INFINITE);
        iconRotation.setInterpolator(new LinearInterpolator());
        iconRotation.start();

        ObjectAnimator iconScaleX = ObjectAnimator.ofFloat(logoIcon, "scaleX", 1f, 1.05f, 1f);
        ObjectAnimator iconScaleY = ObjectAnimator.ofFloat(logoIcon, "scaleY", 1f, 1.05f, 1f);
        iconScaleX.setDuration(2000);
        iconScaleY.setDuration(2000);
        iconScaleX.setRepeatCount(ValueAnimator.INFINITE);
        iconScaleY.setRepeatCount(ValueAnimator.INFINITE);
        iconScaleX.start();
        iconScaleY.start();
    }

    private void animateParticles() {
        animateSingleParticle(particle1, 0, -15, 2000);
        animateSingleParticle(particle2, 15, 0, 2500);
        animateSingleParticle(particle3, 0, 15, 2200);
        animateSingleParticle(particle4, -15, 0, 2800);
    }

    private void animateSingleParticle(View particle, float offsetX, float offsetY, long duration) {
        float startX = particle.getTranslationX();
        float startY = particle.getTranslationY();

        ObjectAnimator moveX = ObjectAnimator.ofFloat(particle, "translationX", startX, startX + offsetX, startX);
        ObjectAnimator moveY = ObjectAnimator.ofFloat(particle, "translationY", startY, startY + offsetY, startY);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(particle, "alpha", 0.8f, 1f, 0.8f);
        ObjectAnimator scale = ObjectAnimator.ofFloat(particle, "scaleX", 1f, 1.3f, 1f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(moveX, moveY, alpha, scale);
        set.setDuration(duration);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isAnimating) {
                    set.start();
                }
            }
        });
        set.start();
    }

    private void animateAppName() {
        ObjectAnimator alpha = ObjectAnimator.ofFloat(appName, "alpha", 0f, 1f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(appName, "translationY", 30f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, transY);
        set.setDuration(500);
        set.setInterpolator(new DecelerateInterpolator());
        set.start();
    }

    private void animateTagline() {
        ObjectAnimator alpha = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(tagline, "translationY", 20f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, transY);
        set.setDuration(500);
        set.setInterpolator(new DecelerateInterpolator());
        set.start();
    }

    private void animatePills() {
        animatePill(pill1, 0);
        animatePill(pill2, 100);
        animatePill(pill3, 200);
    }

    private void animatePill(View pill, long delay) {
        handler.postDelayed(() -> {
            ObjectAnimator alpha = ObjectAnimator.ofFloat(pill, "alpha", 0f, 1f);
            ObjectAnimator transY = ObjectAnimator.ofFloat(pill, "translationY", 20f, 0f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(pill, "scaleX", 0.8f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(pill, "scaleY", 0.8f, 1f);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(alpha, transY, scaleX, scaleY);
            set.setDuration(400);
            set.setInterpolator(new OvershootInterpolator(1.5f));
            set.start();
        }, delay);
    }

    private void animateBottomSection() {
        ObjectAnimator alpha = ObjectAnimator.ofFloat(bottomSection, "alpha", 0f, 1f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(bottomSection, "translationY", 30f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, transY);
        set.setDuration(500);
        set.setInterpolator(new DecelerateInterpolator());
        set.start();
    }

    private void animateProgress() {
        int targetWidth = (int) (200 * getResources().getDisplayMetrics().density);

        ValueAnimator widthAnimator = ValueAnimator.ofInt(0, targetWidth);
        widthAnimator.setDuration(SPLASH_DURATION - 800);
        widthAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        widthAnimator.addUpdateListener(animation -> {
            int width = (int) animation.getAnimatedValue();
            progressBar.getLayoutParams().width = width;
            progressBar.requestLayout();
        });
        widthAnimator.start();

        String[] loadingTexts = {
                "অ্যাপ লোড হচ্ছে...",
                "FFmpeg ইনিশিয়ালাইজ হচ্ছে...",
                "ট্রান্সফর্ম মডিউল লোড হচ্ছে...",
                "প্রস্তুত হচ্ছে..."
        };

        for (int i = 0; i < loadingTexts.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                if (loadingText != null && (isApiSuccess || !isApiCallCompleted)) {
                    loadingText.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction(() -> {
                                if (!isApiCallCompleted || isApiSuccess) {
                                    loadingText.setText(loadingTexts[index]);
                                    loadingText.animate()
                                            .alpha(1f)
                                            .setDuration(150)
                                            .start();
                                }
                            })
                            .start();
                }
            }, i * (SPLASH_DURATION - 800) / loadingTexts.length);
        }
    }

    private void navigateToMain() {
        isAnimating = false;

        mainContent.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(300)
                .start();

        bottomSection.animate()
                .alpha(0f)
                .translationY(30f)
                .setDuration(300)
                .start();

        handler.postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 350);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isAnimating = false;
        handler.removeCallbacksAndMessages(null);
    }
}