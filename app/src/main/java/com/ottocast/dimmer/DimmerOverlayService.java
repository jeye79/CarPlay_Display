package com.ottocast.dimmer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground overlay service providing:
 * 1. Full-screen dimming + warm blue-light filter (respecting Android 12+ untrusted touch limits).
 * 2. One minimal top pill clock capsule (tap -> panel, horizontal swipe -> quick brightness, long-press -> move).
 * 3. Compact auto-collapsing control panel (clock 12h/24h toggle, screen blackout, brightness & blue-light sliders).
 * 4. Full #000000 blackout mode with minimum hardware brightness; tap anywhere to restore.
 */
public class DimmerOverlayService extends Service {

    public static final String ACTION_SHOW_OR_START = "com.ottocast.dimmer.ACTION_SHOW_OR_START";
    public static final String ACTION_TOGGLE_PANEL = "com.ottocast.dimmer.ACTION_TOGGLE_PANEL";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "ottocast_dimmer_channel";

    private static final long LONG_PRESS_DELAY_MS = 500L;
    private static final long AUTO_COLLAPSE_DELAY_MS = 3500L;
    private static final long TRANSIENT_BRIGHTNESS_MS = 1000L;
    private static final long CLOCK_FALLBACK_INTERVAL_MS = 10000L;
    private static final int PANEL_WIDTH_DP = 300;

    private WindowManager windowManager;
    private PrefsManager prefs;
    private Handler handler;

    private View filterView;
    private WindowManager.LayoutParams filterParams;

    private FrameLayout controlRootView;
    private WindowManager.LayoutParams controlParams;
    private LinearLayout capsuleView;
    private TextView capsuleText;
    private LinearLayout panelView;
    private TextView panelClockText;
    private SeekBar brightnessSeekBar;
    private TextView brightnessValueText;
    private SeekBar blueLightSeekBar;
    private TextView blueLightValueText;

    private SimpleDateFormat timeFormat24;
    private SimpleDateFormat timeFormat12;

    private int brightness;
    private int blueLight;
    private boolean use24Hour;
    private boolean isExpanded = false;
    private boolean isBlackout = false;
    private boolean isShowingTransientBrightness = false;
    private int savedSystemBrightness = -1;

    private final Runnable autoCollapseRunnable = this::collapseToCapsule;
    private final Runnable revertCapsuleClockRunnable = () -> {
        isShowingTransientBrightness = false;
        updateClockText();
    };
    private final Runnable clockFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            updateClockText();
            handler.postDelayed(this, CLOCK_FALLBACK_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockText();
        }
    };

    private interface SliderChangeCallback {
        void onChanged(int progress);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = new PrefsManager(this);
        handler = new Handler(Looper.getMainLooper());

        timeFormat24 = new SimpleDateFormat("HH:mm", Locale.getDefault());
        timeFormat12 = new SimpleDateFormat("a h:mm", Locale.getDefault());

        brightness = prefs.getBrightness();
        blueLight = prefs.getBlueLight();
        use24Hour = prefs.isUse24Hour();

        startForegroundNotification();
        createFilterWindow();
        createControlWindow();
        registerTimeUpdates();

        applyFilter();
        updateClockText();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_TOGGLE_PANEL.equals(action)) {
            if (isBlackout) {
                setBlackoutMode(false);
            } else if (isExpanded) {
                collapseToCapsule();
            } else {
                expandPanel();
            }
        } else {
            if (isBlackout) {
                setBlackoutMode(false);
            } else {
                collapseToCapsule();
            }
            if (controlRootView != null) {
                controlRootView.setVisibility(View.VISIBLE);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controlRootView == null || controlParams == null) {
            return;
        }
        if (isExpanded) {
            updatePanelLayoutPosition();
        } else {
            controlParams.x = clampCapsuleX(prefs.getCapsuleX());
            controlParams.y = clampCapsuleY(prefs.getCapsuleY(dp(12)));
            updateControlLayoutSafely();
        }
        applyFilter();
    }

    @Override
    public void onDestroy() {
        restoreSystemBrightnessIfNeeded();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        try {
            unregisterReceiver(timeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        removeViewSafely(controlRootView);
        removeViewSafely(filterView);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            nm.createNotificationChannel(channel);
        }

        Intent toggleIntent = new Intent(this, DimmerOverlayService.class);
        toggleIntent.setAction(ACTION_TOGGLE_PANEL);
        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createFilterWindow() {
        filterView = new View(this);
        filterView.setBackgroundColor(Color.BLACK);
        filterView.setOnTouchListener((v, event) -> {
            if (isBlackout && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                setBlackoutMode(false);
                return true;
            }
            return isBlackout;
        });

        filterParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                getNormalFilterFlags(),
                PixelFormat.TRANSLUCENT
        );
        filterParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            filterParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            filterParams.setFitInsetsTypes(0);
        }

        windowManager.addView(filterView, filterParams);
    }

    private void createControlWindow() {
        controlRootView = new FrameLayout(this);
        controlRootView.setOnTouchListener((v, event) -> {
            if (isExpanded && event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                collapseToCapsule();
                return true;
            }
            return false;
        });

        buildCapsuleView();
        buildPanelView();

        controlRootView.addView(capsuleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        ));
        panelView.setVisibility(View.GONE);
        controlRootView.addView(panelView, new FrameLayout.LayoutParams(
                getTargetPanelWidthPx(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        ));

        controlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                getCollapsedControlFlags(),
                PixelFormat.TRANSLUCENT
        );
        controlParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        controlParams.x = clampCapsuleX(prefs.getCapsuleX());
        controlParams.y = clampCapsuleY(prefs.getCapsuleY(dp(12)));

        windowManager.addView(controlRootView, controlParams);
    }

    private void buildCapsuleView() {
        capsuleView = new LinearLayout(this);
        capsuleView.setOrientation(LinearLayout.HORIZONTAL);
        capsuleView.setGravity(Gravity.CENTER);
        capsuleView.setPadding(dp(16), dp(6), dp(16), dp(6));
        applyNormalCapsuleStyle();

        capsuleText = new TextView(this);
        capsuleText.setTextColor(Color.parseColor("#F1F3F4"));
        capsuleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        capsuleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        capsuleText.setFontFeatureSettings("tnum");
        capsuleText.setSingleLine(true);
        capsuleText.setGravity(Gravity.CENTER);

        capsuleView.addView(capsuleText);
        capsuleView.setOnTouchListener(new CapsuleTouchListener());
    }

    private void buildPanelView() {
        panelView = new LinearLayout(this) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelAutoCollapse();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    scheduleAutoCollapse();
                }
                return false;
            }
        };
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setClickable(true);
        panelView.setPadding(dp(14), dp(12), dp(14), dp(12));
        panelView.setBackground(createRoundedBackground(
                Color.parseColor("#F016181D"),
                Color.parseColor("#33FFFFFF"),
                dp(1),
                18f
        ));

        // Top header row: Clock (tap to toggle 12/24h) + [화면 끄기] + [✕]
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerParams.bottomMargin = dp(10);

        panelClockText = new TextView(this);
        panelClockText.setTextColor(Color.WHITE);
        panelClockText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        panelClockText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panelClockText.setFontFeatureSettings("tnum");
        panelClockText.setPadding(dp(4), dp(4), dp(8), dp(4));
        panelClockText.setOnClickListener(v -> {
            use24Hour = !use24Hour;
            prefs.setUse24Hour(use24Hour);
            updateClockText();
            scheduleAutoCollapse();
        });
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        headerRow.addView(panelClockText, clockParams);

        TextView blackoutButton = createActionChip(
                "화면 끄기",
                Color.parseColor("#E8EAED"),
                dp(12),
                v -> setBlackoutMode(true)
        );
        headerRow.addView(blackoutButton);

        TextView closeButton = createActionChip(
                "✕",
                Color.parseColor("#A0A6B0"),
                dp(10),
                v -> stopSelf()
        );
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.leftMargin = dp(6);
        headerRow.addView(closeButton, closeParams);

        panelView.addView(headerRow, headerParams);

        // Brightness slider row
        SliderWidgets brightnessRow = buildSliderRow(
                "밝기",
                Color.parseColor("#B0B5BD"),
                Color.parseColor("#E8EAED"),
                brightness,
                progress -> {
                    brightness = progress;
                    prefs.setBrightness(progress);
                    applyFilter();
                }
        );
        brightnessSeekBar = brightnessRow.seekBar;
        brightnessValueText = brightnessRow.valueText;
        LinearLayout.LayoutParams row1Params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row1Params.bottomMargin = dp(6);
        panelView.addView(brightnessRow.row, row1Params);

        // Blue-light filter slider row
        SliderWidgets blueLightRow = buildSliderRow(
                "블루라이트",
                Color.parseColor("#FFB74D"),
                Color.parseColor("#FFB74D"),
                blueLight,
                progress -> {
                    blueLight = progress;
                    prefs.setBlueLight(progress);
                    applyFilter();
                }
        );
        blueLightSeekBar = blueLightRow.seekBar;
        blueLightValueText = blueLightRow.valueText;
        panelView.addView(blueLightRow.row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    private TextView createActionChip(
            String text,
            int textColor,
            int horizontalPaddingPx,
            View.OnClickListener listener
    ) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(horizontalPaddingPx, dp(6), horizontalPaddingPx, dp(6));
        chip.setBackground(createRoundedBackground(
                Color.parseColor("#FF252830"),
                Color.parseColor("#26FFFFFF"),
                dp(1),
                999f
        ));
        chip.setOnClickListener(listener);
        return chip;
    }

    private static final class SliderWidgets {
        final LinearLayout row;
        final SeekBar seekBar;
        final TextView valueText;

        SliderWidgets(LinearLayout row, SeekBar seekBar, TextView valueText) {
            this.row = row;
            this.seekBar = seekBar;
            this.valueText = valueText;
        }
    }

    private SliderWidgets buildSliderRow(
            String labelText,
            int labelColor,
            int accentColor,
            int initialProgress,
            SliderChangeCallback callback
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(labelColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setSingleLine(true);
        row.addView(label, new LinearLayout.LayoutParams(
                dp(68),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(initialProgress);
        styleSeekBar(seekBar, accentColor);
        row.addView(seekBar, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView valueText = new TextView(this);
        valueText.setText(formatPercent(initialProgress));
        valueText.setTextColor(accentColor);
        valueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        valueText.setFontFeatureSettings("tnum");
        valueText.setGravity(Gravity.END);
        valueText.setSingleLine(true);
        row.addView(valueText, new LinearLayout.LayoutParams(
                dp(42),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                valueText.setText(formatPercent(progress));
                if (fromUser) {
                    callback.onChanged(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                cancelAutoCollapse();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                scheduleAutoCollapse();
            }
        });

        return new SliderWidgets(row, seekBar, valueText);
    }

    private void styleSeekBar(SeekBar seekBar, int accentColor) {
        ColorStateList tint = ColorStateList.valueOf(accentColor);
        seekBar.setProgressTintList(tint);
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#3DFFFFFF")));
        seekBar.setThumbTintList(tint);
        Drawable thumb = seekBar.getThumb();
        if (thumb != null) {
            thumb.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        }
    }

    private void applyNormalCapsuleStyle() {
        capsuleView.setBackground(createRoundedBackground(
                Color.parseColor("#D9121418"),
                Color.parseColor("#33FFFFFF"),
                dp(1),
                999f
        ));
    }

    private GradientDrawable createRoundedBackground(
            int fillColor,
            int strokeColor,
            int strokeWidthPx,
            float radiusDp
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dpF(radiusDp));
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    private void expandPanel() {
        if (isBlackout || controlRootView == null) {
            return;
        }
        isExpanded = true;
        isShowingTransientBrightness = false;
        handler.removeCallbacks(revertCapsuleClockRunnable);

        if (brightnessSeekBar != null) {
            brightnessSeekBar.setProgress(brightness);
            brightnessValueText.setText(formatPercent(brightness));
        }
        if (blueLightSeekBar != null) {
            blueLightSeekBar.setProgress(blueLight);
            blueLightValueText.setText(formatPercent(blueLight));
        }
        updateClockText();

        capsuleView.setVisibility(View.GONE);
        panelView.setVisibility(View.VISIBLE);
        controlParams.flags = getCollapsedControlFlags()
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        updatePanelLayoutPosition();
        scheduleAutoCollapse();
    }

    private void collapseToCapsule() {
        cancelAutoCollapse();
        if (controlRootView == null) {
            return;
        }
        isExpanded = false;
        panelView.setVisibility(View.GONE);
        capsuleView.setVisibility(View.VISIBLE);
        controlParams.flags = getCollapsedControlFlags();
        controlParams.x = clampCapsuleX(prefs.getCapsuleX());
        controlParams.y = clampCapsuleY(prefs.getCapsuleY(dp(12)));
        updateControlLayoutSafely();
    }

    private void setBlackoutMode(boolean enable) {
        if (isBlackout == enable) {
            return;
        }
        isBlackout = enable;
        cancelAutoCollapse();
        handler.removeCallbacks(revertCapsuleClockRunnable);
        isShowingTransientBrightness = false;

        if (enable) {
            applySystemBlackoutBrightness(true);
            collapseToCapsule();
            if (controlRootView != null) {
                controlRootView.setVisibility(View.GONE);
            }
            applyFilter();
        } else {
            applySystemBlackoutBrightness(false);
            applyFilter();
            if (controlRootView != null) {
                controlRootView.setVisibility(View.VISIBLE);
            }
            updateClockText();
        }
    }

    /**
     * Minimizes hardware system brightness if WRITE_SETTINGS permission happens to be granted.
     * Falls back silently if not granted (CarPlay video box environments rely primarily on #000000 overlay).
     */
    private void applySystemBlackoutBrightness(boolean blackout) {
        try {
            if (!Settings.System.canWrite(this)) {
                return;
            }
            if (blackout) {
                savedSystemBrightness = Settings.System.getInt(
                        getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        -1
                );
                Settings.System.putInt(
                        getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        0
                );
            } else {
                restoreSystemBrightnessIfNeeded();
            }
        } catch (Throwable ignored) {
        }
    }

    private void restoreSystemBrightnessIfNeeded() {
        if (savedSystemBrightness >= 0) {
            try {
                if (Settings.System.canWrite(this)) {
                    Settings.System.putInt(
                            getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS,
                            savedSystemBrightness
                    );
                }
            } catch (Throwable ignored) {
            }
            savedSystemBrightness = -1;
        }
    }

    private void applyFilter() {
        if (filterView == null || filterParams == null) {
            return;
        }

        if (isBlackout) {
            filterParams.flags = getNormalFilterFlags()
                    & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN;
            filterView.setBackgroundColor(Color.BLACK);
            filterParams.alpha = 1.0f;
            filterParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            updateFilterLayoutSafely();
            return;
        }

        filterParams.flags = getNormalFilterFlags();
        filterParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

        float maxSafeAlpha = getMaxSafeOverlayAlpha();
        float dimFraction = (100 - brightness) / 100f;
        float warmFraction = blueLight / 100f;

        float dimAlpha = dimFraction * (maxSafeAlpha * 0.92f);
        float warmAlpha = warmFraction * 0.42f;
        float totalAlpha = Math.min(maxSafeAlpha, 1f - (1f - dimAlpha) * (1f - warmAlpha));

        float denom = dimAlpha + warmAlpha;
        float warmWeight = (totalAlpha > 0.001f && denom > 0.001f)
                ? (warmAlpha * (1f - dimAlpha * 0.8f)) / denom
                : 0f;
        warmWeight = Math.max(0f, Math.min(1f, warmWeight));

        int r = Math.round(255f * warmWeight);
        int g = Math.round(136f * warmWeight);
        filterView.setBackgroundColor(Color.rgb(r, g, 0));
        filterParams.alpha = totalAlpha;

        updateFilterLayoutSafely();
    }

    private float getMaxSafeOverlayAlpha() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InputManager inputManager = getSystemService(InputManager.class);
            if (inputManager != null) {
                float maxAllowed = inputManager.getMaximumObscuringOpacityForTouch();
                return Math.max(0.10f, Math.min(0.85f, maxAllowed * 0.96f));
            }
            return 0.76f;
        }
        return 0.85f;
    }

    private int getNormalFilterFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
    }

    private int getCollapsedControlFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    private void registerTimeUpdates() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, filter);
        handler.postDelayed(clockFallbackRunnable, CLOCK_FALLBACK_INTERVAL_MS);
    }

    private void updateClockText() {
        String formatted = getFormattedTime();
        if (panelClockText != null) {
            panelClockText.setText(formatted);
        }
        if (capsuleText != null && !isShowingTransientBrightness) {
            capsuleText.setText(formatted);
        }
    }

    private String getFormattedTime() {
        SimpleDateFormat fmt = use24Hour ? timeFormat24 : timeFormat12;
        return fmt.format(new Date());
    }

    private void scheduleAutoCollapse() {
        cancelAutoCollapse();
        if (isExpanded) {
            handler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS);
        }
    }

    private void cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable);
    }

    private void updatePanelLayoutPosition() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int panelWidth = getTargetPanelWidthPx();
        if (panelView.getLayoutParams() != null) {
            panelView.getLayoutParams().width = panelWidth;
        }
        int maxHorizontalOffset = Math.max(0, (dm.widthPixels - panelWidth) / 2 - dp(8));
        int savedX = prefs.getCapsuleX();
        int savedY = prefs.getCapsuleY(dp(12));
        controlParams.x = Math.max(-maxHorizontalOffset, Math.min(maxHorizontalOffset, savedX));
        controlParams.y = Math.max(0, Math.min(Math.max(0, dm.heightPixels - dp(190)), savedY));
        updateControlLayoutSafely();
    }

    private int getTargetPanelWidthPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.min(dp(PANEL_WIDTH_DP), Math.max(dp(220), dm.widthPixels - dp(24)));
    }

    private int clampCapsuleX(int x) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxOffset = Math.max(0, dm.widthPixels / 2 - dp(48));
        return Math.max(-maxOffset, Math.min(maxOffset, x));
    }

    private int clampCapsuleY(int y) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxY = Math.max(0, dm.heightPixels - dp(60));
        return Math.max(0, Math.min(maxY, y));
    }

    private void updateFilterLayoutSafely() {
        try {
            if (filterView != null && filterView.isAttachedToWindow()) {
                windowManager.updateViewLayout(filterView, filterParams);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void updateControlLayoutSafely() {
        try {
            if (controlRootView != null && controlRootView.isAttachedToWindow()) {
                windowManager.updateViewLayout(controlRootView, controlParams);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void removeViewSafely(View view) {
        if (view != null && windowManager != null) {
            try {
                if (view.isAttachedToWindow()) {
                    windowManager.removeView(view);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private int dp(int value) {
        return Math.round(dpF(value));
    }

    private float dpF(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private static String formatPercent(int value) {
        return value + "%";
    }

    /**
     * Handles tap (expand panel), horizontal swipe (quick brightness adjust),
     * and long press + drag (reposition top capsule).
     */
    private final class CapsuleTouchListener implements View.OnTouchListener {
        private static final int MODE_NONE = 0;
        private static final int MODE_BRIGHTNESS = 1;
        private static final int MODE_MOVE = 2;

        private final int touchSlop = ViewConfiguration.get(DimmerOverlayService.this).getScaledTouchSlop();

        private int mode = MODE_NONE;
        private float downRawX;
        private float downRawY;
        private int startControlX;
        private int startControlY;
        private int startBrightness;

        private final Runnable longPressRunnable = () -> {
            if (mode == MODE_NONE) {
                mode = MODE_MOVE;
                capsuleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                capsuleView.setBackground(createRoundedBackground(
                        Color.parseColor("#E61C222B"),
                        Color.parseColor("#CC8AB4F8"),
                        dp(2),
                        999f
                ));
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = MODE_NONE;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startControlX = controlParams.x;
                    startControlY = controlParams.y;
                    startBrightness = brightness;
                    handler.removeCallbacks(revertCapsuleClockRunnable);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;

                    if (mode == MODE_NONE) {
                        if (Math.hypot(dx, dy) > touchSlop) {
                            handler.removeCallbacks(longPressRunnable);
                            if (Math.abs(dx) >= Math.abs(dy)) {
                                mode = MODE_BRIGHTNESS;
                            }
                        }
                    }

                    if (mode == MODE_BRIGHTNESS) {
                        int delta = Math.round(dx / dpF(2.2f));
                        int updated = PrefsManager.clampPercent(startBrightness + delta);
                        if (updated != brightness) {
                            brightness = updated;
                            prefs.setBrightness(updated);
                            applyFilter();
                        }
                        isShowingTransientBrightness = true;
                        capsuleText.setText("☀ " + brightness + "%");
                        if (brightnessSeekBar != null) {
                            brightnessSeekBar.setProgress(brightness);
                            brightnessValueText.setText(formatPercent(brightness));
                        }
                    } else if (mode == MODE_MOVE) {
                        controlParams.x = clampCapsuleX(startControlX + Math.round(dx));
                        controlParams.y = clampCapsuleY(startControlY + Math.round(dy));
                        updateControlLayoutSafely();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (mode == MODE_NONE && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.hypot(dx, dy) <= touchSlop) {
                            expandPanel();
                        }
                    } else if (mode == MODE_BRIGHTNESS) {
                        handler.postDelayed(revertCapsuleClockRunnable, TRANSIENT_BRIGHTNESS_MS);
                    } else if (mode == MODE_MOVE) {
                        applyNormalCapsuleStyle();
                        prefs.setCapsulePosition(controlParams.x, controlParams.y);
                    }
                    mode = MODE_NONE;
                    return true;

                default:
                    return false;
            }
        }
    }
}
