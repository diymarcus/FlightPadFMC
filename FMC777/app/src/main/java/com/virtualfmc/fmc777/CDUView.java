package com.virtualfmc.fmc777;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

public class CDUView extends View {

    private static final String TAG = "CDUView";
    private static final int CDU_ROWS = 14;
    private static final int CDU_COLS = 24;

    private static final boolean DEBUG_MODE = false;

    private SoundPlayer soundPlayer;
    private boolean soundEnabled = true;
    private boolean hapticEnabled = true;

    private TouchArea pressedArea = null;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Cockpit-feel press animation — same recipe as iFly737MAX 2026-05-06.
    // Subtle dark overlay fades in then decelerates out; alpha 128 = ~50% black.
    private static final int  HIGHLIGHT_MAX_ALPHA = 128;
    private static final long PRESS_FADE_IN_MS    = 100;
    private static final long PRESS_FADE_OUT_MS   = 240;
    private int currentHighlightAlpha = 0;
    private AnimatorSet pressAnimator = null;

    private long lastTouchTime = 0;
    // 50 ms allows ~20 presses/sec — enough for any human typing on a CDU.
    private static final long TOUCH_DEBOUNCE_MS = 50;

    // ─── 7 hardcoded keepers — extracted from CDU_Images/777_OWN/Calib/press_shapes_777.png
    // via tools/extract_calib_markers.py FMC777_OWN. These are positions that
    // can NOT be auto-detected from the bezel skin (top-bar UI, EXEC LED slot,
    // 4 annunciator label strips). Format: { x0, y0, x1, y1 } normalised 0..1.
    private static final float[] SETTINGS_BTN_RECT       = { 0.0613f, 0.0027f, 0.1250f, 0.0446f };
    private static final float[] CLOSE_BTN_RECT          = { 0.8786f, 0.0027f, 0.9423f, 0.0446f };
    private static final float[] EXEC_LED_RECT           = { 0.7782f, 0.5377f, 0.8714f, 0.5569f };
    private static final float[] ANNUN_LEFT_TOP_RECT     = { 0.0343f, 0.6762f, 0.0745f, 0.7442f };  // DSPY
    private static final float[] ANNUN_LEFT_BOTTOM_RECT  = { 0.0349f, 0.7473f, 0.0745f, 0.8215f };  // FAIL
    private static final float[] ANNUN_RIGHT_TOP_RECT    = { 0.9237f, 0.6758f, 0.9639f, 0.7350f };  // MSG
    private static final float[] ANNUN_RIGHT_BOTTOM_RECT = { 0.9237f, 0.7362f, 0.9639f, 0.8215f };  // OFST

    // CDU character-grid screen rect (fixed by skin geometry).
    private static final float SCREEN_L = 0.14666007f;
    private static final float SCREEN_T = 0.07033213f;
    private static final float SCREEN_R = 0.84544694f;
    private static final float SCREEN_B = 0.43650627f;

    // ─── Bezel-strip annunciators (real PMDG 777 layout) ──────────────────────
    // Two recessed strips on the keypad bezel — left strip holds DSPY (top,
    // white) and FAIL (bottom, red); right strip holds MSG (top, white) and
    // OFST (bottom, amber). Each label is rendered as stacked vertical text.
    // Indices preserved for MainActivity's setAnnunciatorState() contract.
    static final int ANN_MSG  = 0;
    static final int ANN_OFST = 1;
    static final int ANN_DSPY = 2;
    static final int ANN_FAIL = 3;

    private static final String[] ANN_NAMES = {"MSG", "OFST", "DSPY", "FAIL"};
    private static final float[][] ANN_RECTS = {
        ANNUN_RIGHT_TOP_RECT,    // MSG  — right top
        ANNUN_RIGHT_BOTTOM_RECT, // OFST — right bottom
        ANNUN_LEFT_TOP_RECT,     // DSPY — left top
        ANNUN_LEFT_BOTTOM_RECT,  // FAIL — left bottom
    };
    private static final int[] ANN_COLOR_ON = {
        Color.rgb(255, 255, 255),  // MSG  — white  (PMDG 777 in-sim render)
        Color.rgb(255, 160,   0),  // OFST — amber
        Color.rgb(255, 255, 255),  // DSPY — white
        Color.rgb(255,   0,   0),  // FAIL — red
    };
    private static final int[] ANN_COLOR_OFF = {
        Color.rgb( 80,  80, 80),   // dim white
        Color.rgb( 80,  50,  0),   // dim amber
        Color.rgb( 80,  80, 80),   // dim white
        Color.rgb( 80,   0,  0),   // dim red
    };
    // DEBUG: force all 4 bezel annunciators ON for visual verification of
    // positioning + colours after skin / rect-constant changes. Mirrors the
    // iFly / FBWA320 LED_TEST_ALL_ON pattern.
    private static final boolean LED_TEST_ALL_ON = false;

    private final boolean[] annOn = {false, false, false, false};
    private boolean ledExec = false;

    // ─── Shape + Hint plumbing for auto-detected bezel buttons ────────────────
    enum Shape { CIRCLE, ROUNDED_RECT }

    private static final class Hint {
        final String label;
        final float cx, cy, hw, hh;
        final Shape shape;
        Hint(String label, float cx, float cy, float hw, float hh, Shape shape) {
            this.label = label; this.cx = cx; this.cy = cy;
            this.hw = hw; this.hh = hh; this.shape = shape;
        }
    }

    private static final Shape RR = Shape.ROUNDED_RECT;
    private static final Shape CR = Shape.CIRCLE;

    // 69 bezel-button hints — extracted verbatim from the existing FMC777_OWN
    // touchAreas (centre + half-extents), each tagged with its press-overlay
    // shape. These are search-region hints for the pixel detector, NOT
    // user-tunable calibration data. Same layout as PMDG 777 NG3 except for
    // the OWN-only differences (bottom-pinned skin handled in onSizeChanged).
    private static final Hint[] BUTTON_HINTS = {
        // LSK Left
        new Hint("LSK1L", 0.060290247f, 0.13249597f, 0.04f, 0.025f, RR),
        new Hint("LSK2L", 0.05340007f,  0.18277177f, 0.04f, 0.025f, RR),
        new Hint("LSK3L", 0.057346556f, 0.23488782f, 0.04f, 0.025f, RR),
        new Hint("LSK4L", 0.05340007f,  0.28891772f, 0.04f, 0.025f, RR),
        new Hint("LSK5L", 0.057346556f, 0.34103380f, 0.04f, 0.025f, RR),
        new Hint("LSK6L", 0.057346556f, 0.39318663f, 0.04f, 0.025f, RR),
        // LSK Right
        new Hint("LSK1R", 0.94161830f,  0.12874186f, 0.04f, 0.025f, RR),
        new Hint("LSK2R", 0.93964505f,  0.18402314f, 0.04f, 0.025f, RR),
        new Hint("LSK3R", 0.94161830f,  0.23613920f, 0.04f, 0.025f, RR),
        new Hint("LSK4R", 0.93964505f,  0.28891772f, 0.04f, 0.025f, RR),
        new Hint("LSK5R", 0.94161830f,  0.34103380f, 0.04f, 0.025f, RR),
        new Hint("LSK6R", 0.93867460f,  0.39318663f, 0.04f, 0.025f, RR),
        // Function row 1: INIT, RTE, DEP ARR, ALTN, CRZ
        new Hint("INIT",     0.1594f, 0.5257f, 0.04f, 0.025f, RR),
        new Hint("RTE",      0.2860f, 0.5238f, 0.04f, 0.025f, RR),
        new Hint("DEP ARR",  0.4057f, 0.5257f, 0.04f, 0.025f, RR),
        new Hint("ALTN",     0.5343f, 0.5251f, 0.04f, 0.025f, RR),
        new Hint("CRZ",      0.6590f, 0.5238f, 0.04f, 0.025f, RR),
        // Function row 2: FIX, LEGS, HOLD, FMC, PROG, EXEC
        new Hint("FIX",      0.1614f, 0.5829f, 0.04f, 0.025f, RR),
        new Hint("LEGS",     0.2860f, 0.5829f, 0.04f, 0.025f, RR),
        new Hint("HOLD",     0.4087f, 0.5816f, 0.04f, 0.025f, RR),
        new Hint("FMC",      0.5314f, 0.5866f, 0.04f, 0.025f, RR),
        new Hint("PROG",     0.6570f, 0.5835f, 0.04f, 0.025f, RR),
        new Hint("EXEC",     0.8229f, 0.5835f, 0.04f, 0.025f, RR),
        // Cluster row 1: MENU, NAV
        new Hint("MENU",     0.1594f, 0.6425f, 0.04f, 0.025f, RR),
        new Hint("NAV",      0.2831f, 0.6432f, 0.04f, 0.025f, RR),
        // Letters A-E (cluster row 1 right side)
        new Hint("A", 0.4352f, 0.6620f, 0.04f, 0.025f, RR),
        new Hint("B", 0.5382f, 0.6633f, 0.04f, 0.025f, RR),
        new Hint("C", 0.6413f, 0.6614f, 0.04f, 0.025f, RR),
        new Hint("D", 0.7414f, 0.6583f, 0.04f, 0.025f, RR),
        new Hint("E", 0.8484f, 0.6614f, 0.04f, 0.025f, RR),
        // Cluster row 2: PREV PAGE, NEXT PAGE + Letters F-J
        new Hint("PREV PAGE", 0.1614f, 0.7060f, 0.04f, 0.025f, RR),
        new Hint("NEXT PAGE", 0.2831f, 0.7066f, 0.04f, 0.025f, RR),
        new Hint("F", 0.4352f, 0.7198f, 0.04f, 0.025f, RR),
        new Hint("G", 0.5382f, 0.7198f, 0.04f, 0.025f, RR),
        new Hint("H", 0.6423f, 0.7198f, 0.04f, 0.025f, RR),
        new Hint("I", 0.7414f, 0.7210f, 0.04f, 0.025f, RR),
        new Hint("J", 0.8454f, 0.7179f, 0.04f, 0.025f, RR),
        // Numbers 1-3 + K-O
        new Hint("1", 0.1319f, 0.7807f, 0.04f, 0.025f, CR),
        new Hint("2", 0.2281f, 0.7807f, 0.04f, 0.025f, CR),
        new Hint("3", 0.3312f, 0.7770f, 0.04f, 0.025f, CR),
        new Hint("K", 0.4352f, 0.7807f, 0.04f, 0.025f, RR),
        new Hint("L", 0.5373f, 0.7770f, 0.04f, 0.025f, RR),
        new Hint("M", 0.6423f, 0.7776f, 0.04f, 0.025f, RR),
        new Hint("N", 0.7463f, 0.7807f, 0.04f, 0.025f, RR),
        new Hint("O", 0.8454f, 0.7770f, 0.04f, 0.025f, RR),
        // Numbers 4-6 + P-T
        new Hint("4", 0.1300f, 0.8385f, 0.04f, 0.025f, CR),
        new Hint("5", 0.2311f, 0.8367f, 0.04f, 0.025f, CR),
        new Hint("6", 0.3223f, 0.8373f, 0.04f, 0.025f, CR),
        new Hint("P", 0.4352f, 0.8367f, 0.04f, 0.025f, RR),
        new Hint("Q", 0.5382f, 0.8385f, 0.04f, 0.025f, RR),
        new Hint("R", 0.6384f, 0.8367f, 0.04f, 0.025f, RR),
        new Hint("S", 0.7404f, 0.8385f, 0.04f, 0.025f, RR),
        new Hint("T", 0.8484f, 0.8367f, 0.04f, 0.025f, RR),
        // Numbers 7-9 + U-Y
        new Hint("7", 0.1261f, 0.8963f, 0.04f, 0.025f, CR),
        new Hint("8", 0.2291f, 0.8951f, 0.04f, 0.025f, CR),
        new Hint("9", 0.3272f, 0.8951f, 0.04f, 0.025f, CR),
        new Hint("U", 0.4382f, 0.8951f, 0.04f, 0.025f, RR),
        new Hint("V", 0.5373f, 0.8944f, 0.04f, 0.025f, RR),
        new Hint("W", 0.6384f, 0.8951f, 0.04f, 0.025f, RR),
        new Hint("X", 0.7414f, 0.8951f, 0.04f, 0.025f, RR),
        new Hint("Y", 0.8454f, 0.8963f, 0.04f, 0.025f, RR),
        // Bottom row: ., 0, +/-, Z, SP, DEL, /, CLR
        new Hint(".",   0.1270f, 0.9522f, 0.04f, 0.025f, CR),
        new Hint("0",   0.2281f, 0.9528f, 0.04f, 0.025f, CR),
        new Hint("+/-", 0.3272f, 0.9560f, 0.04f, 0.025f, CR),
        new Hint("Z",   0.4352f, 0.9528f, 0.04f, 0.025f, RR),
        new Hint("SP",  0.5382f, 0.9522f, 0.04f, 0.025f, RR),
        new Hint("DEL", 0.6413f, 0.9528f, 0.04f, 0.025f, RR),
        new Hint("/",   0.7444f, 0.9560f, 0.04f, 0.025f, RR),
        new Hint("CLR", 0.8425f, 0.9522f, 0.04f, 0.025f, RR),
    };

    private final char[][] symbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][] colors = new int[CDU_ROWS][CDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Dedicated annunciator paint — system Monospace Bold (NOT the asset B612
    // font); CENTER-aligned per-char so font-metric variance across devices
    // can't shift the stack. Mirrors Fenix/FBW/iFly bulletproof pattern.
    private final Paint annLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Connection status overlay — shown on the CDU screen area when not connected.
    private String connectionStatus = null;
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ─── Captain↔FO swipe (v0.4.1) ─────────────────────────────────────────────
    // Horizontal swipe on the CDU DISPLAY area (never the bezel — buttons fire
    // on ACTION_DOWN, so the gesture is restricted to the button-free screen
    // rect). Swipe left → FO, swipe right → Captain.
    public interface SwipeListener {
        void onSwipe(boolean towardFo);
    }
    private SwipeListener swipeListener;
    private float swipeStartX, swipeStartY;
    private boolean swipeCandidate = false;
    private static final float SWIPE_MIN_DX_FRACTION    = 0.20f; // of screen rect width
    private static final float SWIPE_MAX_OFF_AXIS_RATIO = 0.6f;  // |dy| <= |dx| * this

    // Transient side label ("CAPT CDU" / "FO CDU") — CDU-green, holds briefly
    // then fades so the pilot knows which seat's CDU is on screen.
    private String sideLabelText = null;
    private int sideLabelAlpha = 0;
    private ValueAnimator sideLabelAnimator = null;
    private final Paint sideLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF skinRectF = new RectF();
    private final RectF screenRect = new RectF();
    private final RectF actualScreen = new RectF();
    // Reusable RectF for press overlay — using the RectF overload of
    // drawRoundRect (API 1) instead of the 6-float overload (API 21+ only,
    // crashes on Android 4.4 / dalvikvm with NoSuchMethodError).
    private final RectF pressOverlayRect = new RectF();
    private final RectF ledRectF = new RectF();

    private BlurMaskFilter ledGlowFilter;
    private final Paint.FontMetrics fmcFontMetrics = new Paint.FontMetrics();

    private float cellW;
    private float cellH;
    private float textYOffset;

    private List<TouchArea> touchAreas = new ArrayList<>();

    public interface KeyPressListener {
        void onKeyPress(String key);
        void onUIAction(String action);
    }
    private KeyPressListener keyListener;

    private float imgOffsetX = 0, imgOffsetY = 0;
    private float imgScaleW  = 1, imgScaleH  = 1;

    private static class TouchArea {
        final RectF rect;
        final String key;
        final Shape shape;
        TouchArea(float l, float t, float r, float b, String k, Shape s) {
            rect = new RectF(l, t, r, b);
            key  = k;
            shape = s;
        }
        TouchArea(RectF r, String k, Shape s) {
            rect = new RectF(r);
            key  = k;
            shape = s;
        }
    }

    public CDUView(Context context) {
        super(context);
        init(context);
        setupLayout();
    }

    public CDUView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
        setupLayout();
    }

    private void init(Context context) {
        logD("init() started - PMDG 777 (public) version");
        touchAreas = new ArrayList<>();

        int maxBitmapSize = getMaxBitmapSize();
        logD("GPU max bitmap size: " + maxBitmapSize + "px");

        int safeSize = Math.min(1024, maxBitmapSize / 2);
        logD("Using safe downsampling size: " + safeSize + "px");

        soundPlayer = SoundPlayer.getInstance();
        soundPlayer.initialize(context);
        logD("Sound player initialized");

        try {
            skin = decodeSampledBitmap(context, R.drawable.cdu_skin777, safeSize);
            if (skin != null) {
                logD("777 skin loaded: " + (skin.getByteCount() / 1024) + "KB ("
                        + skin.getWidth() + "x" + skin.getHeight() + ")");
                if (skin.getWidth() > maxBitmapSize || skin.getHeight() > maxBitmapSize) {
                    android.util.Log.w(TAG, "⚠ Bitmap exceeds GPU limit - using software rendering");
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                } else {
                    logD("✓ Hardware acceleration enabled (default)");
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to load 777 skin", e);
        }

        for (int r = 0; r < CDU_ROWS; r++) {
            for (int c = 0; c < CDU_COLS; c++) {
                symbols[r][c] = ' ';
                colors[r][c]  = Color.WHITE;
            }
        }

        // Load B612 Mono Bold font from assets (bundled with app)
        // Fallback to Monospace Bold if font loading fails
        try {
            Typeface b612Font = Typeface.createFromAsset(context.getAssets(), "fonts/B612Mono-Bold.ttf");
            textPaint.setTypeface(b612Font);
            android.util.Log.d(TAG, "✅ B612 Mono Bold font loaded successfully");
        } catch (Exception e) {
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            android.util.Log.w(TAG, "⚠ B612 Mono Bold not found in assets, using Monospace fallback", e);
        }

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        highlightPaint.setColor(Color.BLACK);  // alpha modulated per-frame by pressAnimator
        highlightPaint.setStyle(Paint.Style.FILL);

        boxPaint.setColor(Color.argb(200, 40, 40, 40));
        boxPaint.setStyle(Paint.Style.FILL);

        ledBoxPaint.setStyle(Paint.Style.FILL);
        ledGlowFilter = new BlurMaskFilter(5, BlurMaskFilter.Blur.NORMAL);

        statusPaint.setColor(Color.rgb(255, 160, 0));  // amber
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        annLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        annLabelPaint.setTextAlign(Paint.Align.CENTER);

        sideLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        sideLabelPaint.setTextAlign(Paint.Align.CENTER);

        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    // Captain↔FO swipe (v0.4.1) — resolve a gesture that
                    // started on the display area. Mostly-horizontal drag of
                    // at least SWIPE_MIN_DX_FRACTION of the screen width fires
                    // the listener; anything else is ignored.
                    if (swipeCandidate && event.getAction() == MotionEvent.ACTION_UP
                            && swipeListener != null) {
                        float dx = event.getX() - swipeStartX;
                        float dy = event.getY() - swipeStartY;
                        float minDx = actualScreen.width() * SWIPE_MIN_DX_FRACTION;
                        if (Math.abs(dx) >= minDx
                                && Math.abs(dy) <= Math.abs(dx) * SWIPE_MAX_OFF_AXIS_RATIO) {
                            swipeListener.onSwipe(dx < 0);
                        }
                    }
                    swipeCandidate = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    long currentTime = System.currentTimeMillis();
                    if (lastTouchTime > 0 && currentTime - lastTouchTime < TOUCH_DEBOUNCE_MS) {
                        return true;
                    }
                    lastTouchTime = currentTime;

                    float fx = (event.getX() - imgOffsetX) / imgScaleW;
                    float fy = (event.getY() - imgOffsetY) / imgScaleH;

                    TouchArea foundArea = null;
                    for (TouchArea ta : touchAreas) {
                        if (ta.rect.contains(fx, fy)) {
                            foundArea = ta;
                            break;
                        }
                    }

                    if (foundArea == null && actualScreen.contains(event.getX(), event.getY())) {
                        // Button-free display area — arm the swipe tracker.
                        swipeCandidate = true;
                        swipeStartX = event.getX();
                        swipeStartY = event.getY();
                    } else {
                        swipeCandidate = false;
                    }

                    if (foundArea != null) {
                        v.performClick();
                        pressedArea = foundArea;
                        postInvalidate();

                        if (hapticEnabled) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        }

                        if (soundEnabled && soundPlayer != null) {
                            soundPlayer.playButtonClick();
                        }

                        if (keyListener != null) {
                            if (foundArea.key.equals("SETTINGS_BTN")) {
                                keyListener.onUIAction("SETTINGS_BTN");
                            } else if (foundArea.key.equals("CLOSE_BTN")) {
                                keyListener.onUIAction("CLOSE_BTN");
                            } else {
                                keyListener.onKeyPress(foundArea.key);
                            }
                        }

                        startPressAnimation();
                    }
                    return true;
                }
                return true;
            }
        });
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int getMaxBitmapSize() {
        try {
            int[] maxSize = new int[1];
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
            int result = maxSize[0];
            return result > 0 ? result : 2048;
        } catch (Exception e) {
            return 2048;
        }
    }

    private void logD(String message) {
        if (DEBUG_MODE) {
            android.util.Log.d(TAG, message);
        }
    }

    private Bitmap decodeSampledBitmap(Context context, int resId, int reqSize) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inScaled = false;
            BitmapFactory.decodeResource(context.getResources(), resId, options);

            int origHeight = options.outHeight;
            int origWidth = options.outWidth;

            int sampleSize = 1;
            if (origHeight > reqSize || origWidth > reqSize) {
                final int halfHeight = origHeight / 2;
                final int halfWidth = origWidth / 2;
                while ((halfHeight / sampleSize) >= reqSize && (halfWidth / sampleSize) >= reqSize) {
                    sampleSize *= 2;
                }
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inScaled = false;

            return BitmapFactory.decodeResource(context.getResources(), resId, options);
        } catch (OutOfMemoryError e) {
            System.gc();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void setKeyPressListener(KeyPressListener l) {
        keyListener = l;
    }

    /** Captain↔FO swipe callback (v0.4.1). Fired only for mostly-horizontal
     *  swipes that start on the button-free CDU display area. */
    public void setSwipeListener(SwipeListener l) {
        swipeListener = l;
    }

    /** Flash a transient CDU-green side label ("CAPT CDU" / "FO CDU") over
     *  the display: full brightness briefly, then a quick fade. Same
     *  animator-swap ordering discipline as startPressAnimation() — assign
     *  the new animator before cancelling the old one. */
    public void showSideLabel(String text) {
        ValueAnimator old = sideLabelAnimator;
        sideLabelText = text;
        sideLabelAlpha = 255;

        ValueAnimator fade = ValueAnimator.ofInt(255, 0);
        fade.setStartDelay(700);
        fade.setDuration(400);
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (sideLabelAnimator != animation) return; // superseded
                sideLabelAlpha = (Integer) animation.getAnimatedValue();
                postInvalidate();
            }
        });
        sideLabelAnimator = fade;
        if (old != null) old.cancel();
        fade.start();
        postInvalidate();
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setHapticEnabled(boolean enabled) {
        this.hapticEnabled = enabled;
    }

    public boolean isHapticEnabled() {
        return hapticEnabled;
    }

    /** EXEC LED above the EXEC button — white when armed (PMDG sim render). */
    public void setLedState(boolean isOn) {
        ledExec = isOn;
        postInvalidate();
    }

    /** Set the on/off state of one bezel annunciator (MSG, OFST, DSPY, FAIL).
     *  Use the ANN_* constants for the index. Off state still renders as
     *  dimmed text so the label remains visible on the bezel. */
    public void setAnnunciatorState(int annIndex, boolean isOn) {
        if (annIndex < 0 || annIndex >= annOn.length) return;
        annOn[annIndex] = isOn;
        postInvalidate();
    }

    /** Show a connection status message on the CDU screen area.
     *  Pass null or "" to clear the overlay and show the normal CDU character grid.
     *  Supports "\n" for multi-line messages. */
    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
        postInvalidate();
    }

    /** Cockpit-feel press animation: fade in over PRESS_FADE_IN_MS, then fade
     *  out over PRESS_FADE_OUT_MS with deceleration. Rapid-press correctness:
     *  AnimatorSet.cancel() fires onAnimationEnd synchronously, so we assign
     *  the new animator to the pressAnimator slot BEFORE cancelling the old
     *  one — the dying old listener checks `if (pressAnimator != anim) return`
     *  and leaves pressedArea + alpha alone, so the fresh animation isn't
     *  clobbered. fadeIn starts from current alpha so re-press during fade-out
     *  smoothly tops up instead of dipping through 0. */
    private void startPressAnimation() {
        AnimatorSet old = pressAnimator;
        int startAlpha = currentHighlightAlpha;

        long fadeInMs = PRESS_FADE_IN_MS;
        if (startAlpha > 0 && startAlpha < HIGHLIGHT_MAX_ALPHA) {
            fadeInMs = PRESS_FADE_IN_MS * (HIGHLIGHT_MAX_ALPHA - startAlpha) / HIGHLIGHT_MAX_ALPHA;
            if (fadeInMs < 1) fadeInMs = 1;
        }
        ValueAnimator fadeIn = ValueAnimator.ofInt(startAlpha, HIGHLIGHT_MAX_ALPHA);
        fadeIn.setDuration(fadeInMs);
        fadeIn.addUpdateListener(a -> {
            currentHighlightAlpha = (int) a.getAnimatedValue();
            postInvalidate();
        });

        ValueAnimator fadeOut = ValueAnimator.ofInt(HIGHLIGHT_MAX_ALPHA, 0);
        fadeOut.setDuration(PRESS_FADE_OUT_MS);
        fadeOut.setInterpolator(new DecelerateInterpolator(2.0f));
        fadeOut.addUpdateListener(a -> {
            currentHighlightAlpha = (int) a.getAnimatedValue();
            postInvalidate();
        });

        final AnimatorSet anim = new AnimatorSet();
        anim.playSequentially(fadeIn, fadeOut);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (pressAnimator != anim) return;  // newer press took over
                pressedArea = null;
                currentHighlightAlpha = 0;
                postInvalidate();
            }
        });

        pressAnimator = anim;
        if (old != null) old.cancel();
        anim.start();
    }

    public void updateScreen(char[][] syms, int[][] cols) {
        for (int r = 0; r < CDU_ROWS; r++) {
            System.arraycopy(syms[r], 0, symbols[r], 0, CDU_COLS);
            System.arraycopy(cols[r], 0, colors[r], 0, CDU_COLS);
        }
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setupLayout();

        if (skin != null) {
            float imgW = skin.getWidth();
            float imgH = skin.getHeight();
            float imgRatio = imgW / imgH;
            float scrRatio = (float) w / h;
            float drawW, drawH;
            if (scrRatio < imgRatio) {
                drawW = w; drawH = w / imgRatio;
                imgOffsetX = 0; imgOffsetY = (h - drawH) / 2f;   // centred (public 777 — not bottom-pinned)
            } else {
                drawH = h; drawW = h * imgRatio;
                imgOffsetX = (w - drawW) / 2f; imgOffsetY = 0;
            }
            imgScaleW = drawW; imgScaleH = drawH;
        } else {
            imgScaleW = w; imgScaleH = h;
            imgOffsetX = 0; imgOffsetY = 0;
        }

        actualScreen.set(imgOffsetX + screenRect.left * imgScaleW,
                         imgOffsetY + screenRect.top * imgScaleH,
                         imgOffsetX + screenRect.right * imgScaleW,
                         imgOffsetY + screenRect.bottom * imgScaleH);

        cellW = actualScreen.width() / CDU_COLS;
        cellH = actualScreen.height() / CDU_ROWS;

        textPaint.setTextSize(cellH * 0.75f);
        textYOffset = cellH * 0.8f;
    }

    private void setupLayout() {
        if (touchAreas == null) touchAreas = new ArrayList<>();
        else touchAreas.clear();

        // 2 hardcoded UI buttons (top bar — not on the bezel, can't auto-detect)
        touchAreas.add(new TouchArea(SETTINGS_BTN_RECT[0], SETTINGS_BTN_RECT[1],
                SETTINGS_BTN_RECT[2], SETTINGS_BTN_RECT[3], "SETTINGS_BTN", Shape.ROUNDED_RECT));
        touchAreas.add(new TouchArea(CLOSE_BTN_RECT[0], CLOSE_BTN_RECT[1],
                CLOSE_BTN_RECT[2], CLOSE_BTN_RECT[3], "CLOSE_BTN", Shape.ROUNDED_RECT));

        // CDU character-grid screen rect — fixed by skin geometry.
        screenRect.set(SCREEN_L, SCREEN_T, SCREEN_R, SCREEN_B);

        // 69 bezel buttons — pixel-detected from the skin bitmap.
        scanButtonsFromBezel();
    }

    /** Pixel-detect each bezel button's actual face from the skin bitmap.
     *  Mirrors the iFly737MAX 2026-05-06 implementation exactly: search rect,
     *  grey&lt;60 mask, 2x2 binary opening, biggest connected component bbox.
     *  CLR-bottom-clamped-to-/ special case preserved from v6 algorithm. */
    private void scanButtonsFromBezel() {
        if (skin == null) {
            android.util.Log.w(TAG, "scanButtonsFromBezel: skin null, falling back to hint rects");
            addAllAsHintFallback();
            return;
        }
        int W, H;
        try {
            W = skin.getWidth();
            H = skin.getHeight();
        } catch (Exception e) {
            android.util.Log.e(TAG, "scanButtonsFromBezel: skin getWidth/Height threw, falling back to hint rects", e);
            addAllAsHintFallback();
            return;
        }
        java.util.Map<String, RectF> detected = new java.util.HashMap<String, RectF>();
        int detectErrors = 0;
        for (Hint h : BUTTON_HINTS) {
            RectF bbox = null;
            try {
                bbox = detectButtonBbox(h, W, H);
            } catch (Throwable t) {
                detectErrors++;
                if (detectErrors == 1) {
                    android.util.Log.e(TAG, "detectButtonBbox threw for '" + h.label
                            + "' — falling back to hint rect for it (and any further failures)", t);
                }
            }
            if (bbox != null) detected.put(h.label, bbox);
        }
        // CLR special case — printed label drops below the button face; clamp to /'s bottom.
        RectF clr   = detected.get("CLR");
        RectF slash = detected.get("/");
        if (clr != null && slash != null) {
            clr.bottom = slash.bottom;
        }
        int detectedCount = 0;
        for (Hint h : BUTTON_HINTS) {
            RectF bbox = detected.get(h.label);
            if (bbox == null) {
                touchAreas.add(new TouchArea(h.cx - h.hw, h.cy - h.hh,
                        h.cx + h.hw, h.cy + h.hh, h.label, h.shape));
            } else {
                touchAreas.add(new TouchArea(bbox, h.label, h.shape));
                detectedCount++;
            }
        }
        android.util.Log.d(TAG, "scanButtonsFromBezel: detected " + detectedCount
                + "/" + BUTTON_HINTS.length + " bezel buttons from " + W + "x" + H
                + " skin (" + detectErrors + " errors)");
    }

    private void addAllAsHintFallback() {
        for (Hint h : BUTTON_HINTS) {
            touchAreas.add(new TouchArea(h.cx - h.hw, h.cy - h.hh,
                    h.cx + h.hw, h.cy + h.hh, h.label, h.shape));
        }
    }

    private RectF detectButtonBbox(Hint h, int W, int H) {
        final float expand = 1.40f;
        final int darkThreshold = 60;
        float hw = h.hw * expand, hh = h.hh * expand;
        int sx0 = Math.max(0, Math.round((h.cx - hw) * W));
        int sx1 = Math.min(W, Math.round((h.cx + hw) * W));
        int sy0 = Math.max(0, Math.round((h.cy - hh) * H));
        int sy1 = Math.min(H, Math.round((h.cy + hh) * H));
        int sw = sx1 - sx0, sh = sy1 - sy0;
        if (sw <= 2 || sh <= 2) return null;

        int[] pixels = new int[sw * sh];
        skin.getPixels(pixels, 0, sw, sx0, sy0, sw, sh);
        boolean[] mask = new boolean[sw * sh];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int grey = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3;
            mask[i] = grey < darkThreshold;
        }

        boolean[] eroded = new boolean[sw * sh];
        for (int y = 0; y < sh - 1; y++) {
            for (int x = 0; x < sw - 1; x++) {
                int i = y * sw + x;
                eroded[i] = mask[i] && mask[i + 1] && mask[i + sw] && mask[i + sw + 1];
            }
        }
        boolean[] opened = new boolean[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int i = y * sw + x;
                if (eroded[i]) {
                    opened[i] = true;
                    if (x + 1 < sw) opened[i + 1] = true;
                    if (y + 1 < sh) opened[i + sw] = true;
                    if (x + 1 < sw && y + 1 < sh) opened[i + sw + 1] = true;
                }
            }
        }

        int[] labels = new int[sw * sh];
        int n = connectedComponents(opened, sw, sh, labels);
        if (n == 0) return null;
        int[] count = new int[n + 1];
        for (int v : labels) if (v > 0) count[v]++;
        int bestLbl = 1;
        for (int i = 2; i <= n; i++) if (count[i] > count[bestLbl]) bestLbl = i;
        if (count[bestLbl] < 8) return null;

        int bx0 = sw, by0 = sh, bx1 = -1, by1 = -1;
        for (int y = 0; y < sh; y++) {
            int row = y * sw;
            for (int x = 0; x < sw; x++) {
                if (labels[row + x] == bestLbl) {
                    if (x < bx0) bx0 = x;
                    if (x > bx1) bx1 = x;
                    if (y < by0) by0 = y;
                    if (y > by1) by1 = y;
                }
            }
        }
        if (bx1 < 0) return null;
        return new RectF(
                (sx0 + bx0)     / (float) W,
                (sy0 + by0)     / (float) H,
                (sx0 + bx1 + 1) / (float) W,
                (sy0 + by1 + 1) / (float) H);
    }

    private int connectedComponents(boolean[] mask, int w, int h, int[] labels) {
        int[] parent = new int[64];
        parent[0] = 0;
        int next = 1;
        java.util.Arrays.fill(labels, 0);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                if (!mask[i]) continue;
                int up = (y > 0 && mask[i - w]) ? labels[i - w] : 0;
                int lt = (x > 0 && mask[i - 1]) ? labels[i - 1] : 0;
                if (up == 0 && lt == 0) {
                    if (next >= parent.length) {
                        parent = java.util.Arrays.copyOf(parent, parent.length * 2);
                    }
                    parent[next] = next;
                    labels[i] = next;
                    next++;
                } else if (up != 0 && lt == 0) {
                    labels[i] = up;
                } else if (up == 0) {
                    labels[i] = lt;
                } else {
                    int u = findRoot(parent, up);
                    int v = findRoot(parent, lt);
                    int min = Math.min(u, v), max = Math.max(u, v);
                    if (min != max) parent[max] = min;
                    labels[i] = min;
                }
            }
        }
        int[] remap = new int[next];
        int compCount = 0;
        for (int i = 1; i < next; i++) {
            if (findRoot(parent, i) == i) {
                compCount++;
                remap[i] = compCount;
            }
        }
        for (int i = 1; i < next; i++) {
            if (remap[i] == 0) remap[i] = remap[findRoot(parent, i)];
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != 0) labels[i] = remap[labels[i]];
        }
        return compCount;
    }

    private static int findRoot(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (skin != null) {
            skinRectF.set(imgOffsetX, imgOffsetY, imgOffsetX + imgScaleW, imgOffsetY + imgScaleH);
            canvas.drawBitmap(skin, null, skinRectF, skinPaint);
        } else {
            canvas.drawColor(Color.DKGRAY);
        }

        screenPaint.setColor(Color.BLACK);
        canvas.drawRect(actualScreen, screenPaint);

        if (connectionStatus != null && !connectionStatus.isEmpty()) {
            float screenH = actualScreen.height();
            // Auto-fit: long status messages (e.g. aircraft mismatch /
            // "Open matching app or load matching aircraft") would otherwise
            // run off both edges of the CDU rect. Measure widest line at the
            // default size and shrink textSize proportionally to fit ~94 %
            // of screen width, floored at a readable minimum.
            String[] lines = connectionStatus.split("\n");
            float screenW     = actualScreen.width();
            float textSize    = screenH * 0.07f;
            float minTextSize = screenH * 0.035f;
            float maxLineW    = screenW * 0.94f;
            statusPaint.setTextSize(textSize);
            float widest = 0f;
            for (String l : lines) {
                float lineW = statusPaint.measureText(l);
                if (lineW > widest) widest = lineW;
            }
            if (widest > maxLineW) {
                textSize *= maxLineW / widest;
                if (textSize < minTextSize) textSize = minTextSize;
                statusPaint.setTextSize(textSize);
            }
            float cx = actualScreen.centerX();
            float cy = actualScreen.centerY();
            float lineSpacing = textSize * 1.42f;
            float startY = cy - ((lines.length - 1) * lineSpacing * 0.5f);
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], cx, startY + i * lineSpacing, statusPaint);
            }
            drawLedIndicator(canvas);
            drawAnnunciators(canvas);
            drawPressOverlay(canvas);
            return;
        }

        for (int r = 0; r < CDU_ROWS; r++) {
            float rowY = actualScreen.top + r * cellH + textYOffset;
            for (int c = 0; c < CDU_COLS; c++) {
                if (symbols[r][c] != ' ') {
                    textPaint.setColor(colors[r][c]);
                    canvas.drawText(symbols[r], c, 1,
                                    actualScreen.left + c * cellW + cellW * 0.5f,
                                    rowY, textPaint);
                }
            }
        }

        drawSideLabel(canvas);
        drawLedIndicator(canvas);
        drawAnnunciators(canvas);
        drawPressOverlay(canvas);
    }

    /** Transient CDU-green side label drawn over the display centre —
     *  Captain↔FO swipe feedback (v0.4.1). */
    private void drawSideLabel(Canvas canvas) {
        if (sideLabelText == null || sideLabelAlpha <= 0) return;
        sideLabelPaint.setTextSize(actualScreen.height() * 0.11f);
        sideLabelPaint.setColor(Color.argb(sideLabelAlpha, 0, 255, 0));
        canvas.drawText(sideLabelText, actualScreen.centerX(),
                        actualScreen.centerY(), sideLabelPaint);
    }

    /** Press overlay sized to the auto-detected button face. Shape comes from
     *  TouchArea.shape (set by the bezel scan or hint metadata). Uses the
     *  RectF overload of drawRoundRect (API 1) — the float-args overload is
     *  API 21+ only and crashes on Android 4.4 / dalvikvm. */
    private void drawPressOverlay(Canvas canvas) {
        if (pressedArea == null || currentHighlightAlpha <= 0) return;
        RectF r = pressedArea.rect;
        float l = imgOffsetX + r.left   * imgScaleW;
        float t = imgOffsetY + r.top    * imgScaleH;
        float rt = imgOffsetX + r.right  * imgScaleW;
        float b = imgOffsetY + r.bottom * imgScaleH;
        highlightPaint.setAlpha(currentHighlightAlpha);

        if (pressedArea.shape == Shape.CIRCLE) {
            float cx = (l + rt) * 0.5f;
            float cy = (t + b) * 0.5f;
            float radius = Math.min(rt - l, b - t) * 0.5f;
            canvas.drawCircle(cx, cy, radius, highlightPaint);
        } else {
            float corner = Math.min(rt - l, b - t) * 0.22f;
            pressOverlayRect.set(l, t, rt, b);
            canvas.drawRoundRect(pressOverlayRect, corner, corner, highlightPaint);
        }
    }

    /** Render the EXEC LED slot above the EXEC button at EXEC_LED_RECT.
     *  Drawn at 90% of EXEC_LED_RECT (5% inset each side) so the lit panel
     *  sits inside the bezel cutout instead of edge-to-edge.
     *  Lit = bright white with soft outer glow (PMDG sim render); off = dark
     *  grey filler. */
    private void drawLedIndicator(Canvas canvas) {
        float l = imgOffsetX + EXEC_LED_RECT[0] * imgScaleW;
        float t = imgOffsetY + EXEC_LED_RECT[1] * imgScaleH;
        float r = imgOffsetX + EXEC_LED_RECT[2] * imgScaleW;
        float b = imgOffsetY + EXEC_LED_RECT[3] * imgScaleH;
        float dx = (r - l) * 0.05f;
        float dy = (b - t) * 0.05f;
        l += dx; r -= dx;
        t += dy; b -= dy;
        boolean lit = ledExec || LED_TEST_ALL_ON;
        ledRectF.set(l, t, r, b);
        float corner = Math.min(r - l, b - t) * 0.25f;

        if (lit) {
            ledBoxPaint.setColor(Color.argb(255, 255, 255, 255));
            ledBoxPaint.setMaskFilter(ledGlowFilter);
            canvas.drawRoundRect(ledRectF, corner, corner, ledBoxPaint);
            ledBoxPaint.setMaskFilter(null);
            ledBoxPaint.setColor(Color.argb(255, 255, 255, 255));
            canvas.drawRoundRect(ledRectF, corner, corner, ledBoxPaint);
        } else {
            ledBoxPaint.setColor(Color.argb(255, 40, 40, 40));
            canvas.drawRoundRect(ledRectF, corner, corner, ledBoxPaint);
        }
    }

    /** Draw the four bezel-strip annunciators (MSG, OFST, DSPY, FAIL) as
     *  stacked vertical text — one character per line going down, characters
     *  upright (NOT canvas.rotate). Inactive annunciators render in their dim
     *  colour so labels stay readable on the bezel even when off. */
    private void drawAnnunciators(Canvas canvas) {
        float charSize = imgScaleH * 0.0144f * 1.152f;
        if (charSize < 1f) return;  // pre-layout; nothing meaningful to draw
        annLabelPaint.setTextSize(charSize);
        annLabelPaint.setFakeBoldText(true);

        Paint.FontMetrics fm = annLabelPaint.getFontMetrics();
        float lineH = charSize;
        float baselineAdjust = (fm.ascent + fm.descent) / 2f;

        for (int i = 0; i < ANN_NAMES.length; i++) {
            float[] rect = ANN_RECTS[i];
            float px = imgOffsetX + ((rect[0] + rect[2]) * 0.5f) * imgScaleW;
            float py = imgOffsetY + ((rect[1] + rect[3]) * 0.5f) * imgScaleH;
            boolean lit = LED_TEST_ALL_ON || annOn[i];
            annLabelPaint.setColor(lit ? ANN_COLOR_ON[i] : ANN_COLOR_OFF[i]);
            if (lit) {
                annLabelPaint.setShadowLayer(charSize * 0.35f, 0f, 0f, ANN_COLOR_ON[i]);
            } else {
                annLabelPaint.clearShadowLayer();
            }

            String label = ANN_NAMES[i];
            float topY = py - ((label.length() - 1) * lineH) / 2f;
            for (int k = 0; k < label.length(); k++) {
                float charY = topY + k * lineH - baselineAdjust;
                canvas.drawText(String.valueOf(label.charAt(k)), px, charY, annLabelPaint);
            }
        }
    }

    public void recycleSkin() {
        if (skin != null && !skin.isRecycled()) {
            skin.recycle();
            skin = null;
        }
        if (soundPlayer != null) {
            soundPlayer.release();
            soundPlayer = null;
        }
    }
}
