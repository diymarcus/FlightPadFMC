package com.virtualfmc.ifly737max;

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

    // Button-press animation: subtle dark overlay that fades out with deceleration.
    // Mimics a real cockpit button being pushed down (darker due to recess shadow)
    // rather than a flat additive flash.
    private static final int HIGHLIGHT_MAX_ALPHA = 128;      // ~50% black
    private static final long PRESS_FADE_IN_MS = 100;
    private static final long PRESS_FADE_OUT_MS = 240;
    private int currentHighlightAlpha = 0;
    private AnimatorSet pressAnimator = null;

    private long lastTouchTime = 0;
    // Debounce filters spurious double-touches from cheap screens. 50 ms allows
    // ~20 presses/sec which is faster than any human can type on a CDU.
    private static final long TOUCH_DEBOUNCE_MS = 50;

    // The 7 hardcoded keepers — UI positions that can NOT be auto-detected from the
    // bezel skin (top-bar text, EXEC LED slot, annunciator label strips). Extracted
    // by tools/extract_calib_markers.py from CDU_Images/MAX/Calib/Calib.png.
    // Format: { x0, y0, x1, y1 } normalised 0..1 to skin bitmap.
    private static final float[] SETTINGS_BTN_RECT       = { 0.0921f, 0.0071f, 0.1606f, 0.0500f };
    private static final float[] CLOSE_BTN_RECT          = { 0.8417f, 0.0048f, 0.9163f, 0.0529f };
    private static final float[] EXEC_LED_RECT           = { 0.7839f, 0.5624f, 0.8676f, 0.5824f };
    private static final float[] ANNUN_LEFT_TOP_RECT     = { 0.0441f, 0.7100f, 0.0868f, 0.7829f };  // CALL
    private static final float[] ANNUN_LEFT_BOTTOM_RECT  = { 0.0434f, 0.7876f, 0.0875f, 0.8605f };  // FAIL
    private static final float[] ANNUN_RIGHT_TOP_RECT    = { 0.9132f, 0.7095f, 0.9589f, 0.7838f };  // MSG
    private static final float[] ANNUN_RIGHT_BOTTOM_RECT = { 0.9140f, 0.7876f, 0.9589f, 0.8605f };  // OFST

    // Screen rect (top-left + bottom-right normalised), measured 2026-04-09.
    private static final float SCREEN_L = 0.1457f;
    private static final float SCREEN_T = 0.0792f;
    private static final float SCREEN_R = 0.8592f;
    private static final float SCREEN_B = 0.4427f;

    // Per-name LED state — drives EXEC LED above EXEC button + 4 annunciator
    // labels (MSG/OFST/CALL/FAIL) on the bezel side strips. iFly's SDK exposes
    // the same 5 LEDs as PMDG NG3, so server emits identical name-tagged
    // led_update messages and we drive them with the same render pattern.
    private boolean ledExec = false;
    private boolean ledMsg  = false;
    private boolean ledOfst = false;
    private boolean ledCall = false;
    private boolean ledFail = false;

    // PMDG sim render colours (verified 2026-05-07): MSG white, CALL white,
    // OFST amber, FAIL red. Off state stays at a dim version of the same hue
    // so labels remain visible on the bezel even when the annunciator isn't lit.
    private static final int ANN_COLOR_MSG_ON   = Color.rgb(255, 255, 255);
    private static final int ANN_COLOR_OFST_ON  = Color.rgb(255, 160,   0);
    private static final int ANN_COLOR_CALL_ON  = Color.rgb(255, 255, 255);
    private static final int ANN_COLOR_FAIL_ON  = Color.rgb(255,   0,   0);
    private static final int ANN_COLOR_DIM_WHITE = Color.rgb(80, 80, 80);
    private static final int ANN_COLOR_DIM_AMBER = Color.rgb(80, 50,  0);
    private static final int ANN_COLOR_DIM_RED   = Color.rgb(80,  0,  0);

    // DEBUG: force all 4 bezel annunciators ON regardless of server data.
    // Set true for one-off visual verification of positions/colours after
    // skin or rect-constant changes, then back to false. Mirrors the
    // FBWA320 / FMC737 LED_TEST_ALL_ON pattern.
    private static final boolean LED_TEST_ALL_ON = false;

    private final char[][] symbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][] colors = new int[CDU_ROWS][CDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Dedicated paint for the bezel annunciators — system Monospace Bold,
    // not the asset B612 font. CENTER-aligned per-character so font-metric
    // variance across devices can't shift the stack horizontally. Mirrors
    // FMC737 / FMC777 / Fenix / FBW pattern.
    private final Paint annLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Connection status overlay — shown on the CDU screen area when not connected.
    // Big amber centered text replaces the char grid until cleared (setConnectionStatus(null)).
    private String connectionStatus = null;
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF screenRect = new RectF();
    private final RectF actualScreen = new RectF();
    private final RectF skinRectF = new RectF();

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
    private float imgScaleW = 1, imgScaleH = 1;

    enum Shape { CIRCLE, ROUNDED_RECT }

    private static class TouchArea {
        final RectF rect;
        final String key;
        final Shape shape;
        TouchArea(float l, float t, float r, float b, String k, Shape s) {
            rect = new RectF(l, t, r, b);
            key = k;
            shape = s;
        }
        TouchArea(RectF r, String k, Shape s) {
            rect = new RectF(r);
            key = k;
            shape = s;
        }
    }

    /** Hint for the bezel-button auto-detector — gives the search-region centre +
     *  half-extents in normalised skin coords. The actual button bbox is found by
     *  pixel-detecting the largest dark cluster within this region (expanded 40%).
     *  All 65 bezel buttons are listed here; the 7 non-bezel keepers (Settings, Close,
     *  EXEC LED, 4 annunciators) live in the *_RECT constants above instead. */
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

    private static final Hint[] BUTTON_HINTS = {
        // LSK Left
        new Hint("LSK1L", 0.0467f, 0.1554f, 0.035f, 0.025f, RR),
        new Hint("LSK2L", 0.0487f, 0.2025f, 0.035f, 0.025f, RR),
        new Hint("LSK3L", 0.0467f, 0.2514f, 0.035f, 0.025f, RR),
        new Hint("LSK4L", 0.0467f, 0.3003f, 0.035f, 0.025f, RR),
        new Hint("LSK5L", 0.0487f, 0.3510f, 0.035f, 0.025f, RR),
        new Hint("LSK6L", 0.0517f, 0.4012f, 0.035f, 0.025f, RR),
        // LSK Right
        new Hint("LSK1R", 0.9532f, 0.1554f, 0.035f, 0.025f, RR),
        new Hint("LSK2R", 0.9552f, 0.2025f, 0.035f, 0.025f, RR),
        new Hint("LSK3R", 0.9552f, 0.2532f, 0.035f, 0.025f, RR),
        new Hint("LSK4R", 0.9552f, 0.3021f, 0.035f, 0.025f, RR),
        new Hint("LSK5R", 0.9552f, 0.3510f, 0.035f, 0.025f, RR),
        new Hint("LSK6R", 0.9562f, 0.4000f, 0.035f, 0.025f, RR),
        // Function row 1: INIT REF, RTE, DEP ARR, ATC, VNAV
        new Hint("INIT REF", 0.1635f, 0.5430f, 0.055f, 0.025f, RR),
        new Hint("RTE",      0.2922f, 0.5411f, 0.055f, 0.025f, RR),
        new Hint("DEP ARR",  0.4149f, 0.5393f, 0.055f, 0.025f, RR),
        new Hint("ATC",      0.5405f, 0.5393f, 0.055f, 0.025f, RR),
        new Hint("VNAV",     0.6642f, 0.5418f, 0.055f, 0.025f, RR),
        // Function row 2: FIX, LEGS, HOLD, FMC COMM, PROG, EXEC
        new Hint("FIX",      0.1675f, 0.5969f, 0.055f, 0.025f, RR),
        new Hint("LEGS",     0.2882f, 0.5981f, 0.055f, 0.025f, RR),
        new Hint("HOLD",     0.4149f, 0.5969f, 0.055f, 0.025f, RR),
        new Hint("FMC COMM", 0.5405f, 0.5969f, 0.055f, 0.025f, RR),
        new Hint("PROG",     0.6632f, 0.5981f, 0.055f, 0.025f, RR),
        new Hint("EXEC",     0.8295f, 0.6018f, 0.055f, 0.025f, RR),
        // Cluster row 1: MENU, N1 LIMIT
        new Hint("MENU",     0.1635f, 0.6588f, 0.055f, 0.025f, RR),
        new Hint("N1 LIMIT", 0.2882f, 0.6563f, 0.055f, 0.025f, RR),
        // Cluster row 2: PREV PAGE, NEXT PAGE
        new Hint("PREV PAGE", 0.1615f, 0.7151f, 0.055f, 0.025f, RR),
        new Hint("NEXT PAGE", 0.2941f, 0.7151f, 0.055f, 0.025f, RR),
        // Letters A-E
        new Hint("A", 0.4554f, 0.6607f, 0.045f, 0.025f, RR),
        new Hint("B", 0.5544f, 0.6619f, 0.045f, 0.025f, RR),
        new Hint("C", 0.6543f, 0.6607f, 0.045f, 0.025f, RR),
        new Hint("D", 0.7503f, 0.6607f, 0.045f, 0.025f, RR),
        new Hint("E", 0.8473f, 0.6588f, 0.045f, 0.025f, RR),
        // Letters F-J
        new Hint("F", 0.4584f, 0.7176f, 0.045f, 0.025f, RR),
        new Hint("G", 0.5554f, 0.7189f, 0.045f, 0.025f, RR),
        new Hint("H", 0.6494f, 0.7189f, 0.045f, 0.025f, RR),
        new Hint("I", 0.7513f, 0.7157f, 0.045f, 0.025f, RR),
        new Hint("J", 0.8453f, 0.7195f, 0.045f, 0.025f, RR),
        // Numbers 1-3 + K-O
        new Hint("1", 0.1487f, 0.7789f, 0.045f, 0.025f, CR),
        new Hint("2", 0.2506f, 0.7789f, 0.045f, 0.025f, CR),
        new Hint("3", 0.3506f, 0.7770f, 0.045f, 0.025f, CR),
        new Hint("K", 0.4554f, 0.7770f, 0.045f, 0.025f, RR),
        new Hint("L", 0.5524f, 0.7752f, 0.045f, 0.025f, RR),
        new Hint("M", 0.6573f, 0.7789f, 0.045f, 0.025f, RR),
        new Hint("N", 0.7503f, 0.7777f, 0.045f, 0.025f, RR),
        new Hint("O", 0.8503f, 0.7789f, 0.045f, 0.025f, RR),
        // Numbers 4-6 + P-T
        new Hint("4", 0.1536f, 0.8365f, 0.045f, 0.025f, CR),
        new Hint("5", 0.2506f, 0.8365f, 0.045f, 0.025f, CR),
        new Hint("6", 0.3466f, 0.8365f, 0.045f, 0.025f, CR),
        new Hint("P", 0.4525f, 0.8365f, 0.045f, 0.025f, RR),
        new Hint("Q", 0.5544f, 0.8359f, 0.045f, 0.025f, RR),
        new Hint("R", 0.6494f, 0.8365f, 0.045f, 0.025f, RR),
        new Hint("S", 0.7483f, 0.8365f, 0.045f, 0.025f, RR),
        new Hint("T", 0.8453f, 0.8340f, 0.045f, 0.025f, RR),
        // Numbers 7-9 + U-Y
        new Hint("7", 0.1487f, 0.8922f, 0.045f, 0.025f, CR),
        new Hint("8", 0.2506f, 0.8928f, 0.045f, 0.025f, CR),
        new Hint("9", 0.3466f, 0.8922f, 0.045f, 0.025f, CR),
        new Hint("U", 0.4554f, 0.8947f, 0.045f, 0.025f, RR),
        new Hint("V", 0.5544f, 0.8947f, 0.045f, 0.025f, RR),
        new Hint("W", 0.6543f, 0.8947f, 0.045f, 0.025f, RR),
        new Hint("X", 0.7503f, 0.8947f, 0.045f, 0.025f, RR),
        new Hint("Y", 0.8453f, 0.8947f, 0.045f, 0.025f, RR),
        // Bottom row: ., 0, +/-, Z, SP, DEL, /, CLR
        new Hint(".",   0.1536f, 0.9523f, 0.045f, 0.025f, CR),
        new Hint("0",   0.2506f, 0.9523f, 0.045f, 0.025f, CR),
        new Hint("+/-", 0.3495f, 0.9523f, 0.045f, 0.025f, CR),
        new Hint("Z",   0.4535f, 0.9523f, 0.045f, 0.025f, RR),
        new Hint("SP",  0.5544f, 0.9535f, 0.045f, 0.025f, RR),
        new Hint("DEL", 0.6514f, 0.9510f, 0.045f, 0.025f, RR),
        new Hint("/",   0.7483f, 0.9510f, 0.045f, 0.025f, RR),
        new Hint("CLR", 0.8473f, 0.9523f, 0.045f, 0.025f, RR),
    };

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
        android.util.Log.d(TAG, "init() started - PMDG 737 version");
        touchAreas = new ArrayList<>();

        int maxBitmapSize = getMaxBitmapSize();
        android.util.Log.d(TAG, "GPU max bitmap size: " + maxBitmapSize + "px");

        int safeSize = Math.min(1024, maxBitmapSize / 2);
        android.util.Log.d(TAG, "Using safe downsampling size: " + safeSize + "px");

        soundPlayer = SoundPlayer.getInstance();
        soundPlayer.initialize(context);
        android.util.Log.d(TAG, "Sound player initialized");

        try {
            skin = decodeSampledBitmap(context, R.drawable.cdu_skin737, safeSize);
            if (skin != null) {
                android.util.Log.d(TAG, "737 skin loaded: " + (skin.getByteCount() / 1024) + "KB (" + skin.getWidth() + "x" + skin.getHeight() + ")");
                if (skin.getWidth() > maxBitmapSize || skin.getHeight() > maxBitmapSize) {
                    android.util.Log.w(TAG, "⚠ Bitmap exceeds GPU limit - using software rendering");
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                } else {
                    android.util.Log.d(TAG, "✓ Hardware acceleration enabled (default)");
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to load 737 skin", e);
        }

        for (int r = 0; r < CDU_ROWS; r++) {
            for (int c = 0; c < CDU_COLS; c++) {
                symbols[r][c] = ' ';
                colors[r][c] = Color.WHITE;
            }
        }

        // Load B612 Mono Bold font from assets (bundled with app)
        // Fallback to Monospace Bold if font loading fails
        try {
            Typeface b612Font = Typeface.createFromAsset(context.getAssets(), "fonts/B612Mono-Bold.ttf");
            textPaint.setTypeface(b612Font);
            android.util.Log.d(TAG, "✅ B612 Mono Bold font loaded successfully");
        } catch (Exception e) {
            // Fallback to system Monospace Bold if font not found
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

        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
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
    public void setExecLed(boolean isOn) {
        ledExec = isOn;
        postInvalidate();
    }

    public void setMsgLed(boolean isOn)  { ledMsg  = isOn; postInvalidate(); }
    public void setOfstLed(boolean isOn) { ledOfst = isOn; postInvalidate(); }
    public void setCallLed(boolean isOn) { ledCall = isOn; postInvalidate(); }
    public void setFailLed(boolean isOn) { ledFail = isOn; postInvalidate(); }

    /** Show a connection status message on the CDU screen area.
     *  Pass null or "" to clear the overlay and show the normal CDU character grid.
     *  Supports "\n" for multi-line messages. */
    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
        postInvalidate();
    }

    /** Subtle dark-overlay animation: fade in over PRESS_FADE_IN_MS, then fade out
     *  over PRESS_FADE_OUT_MS with deceleration (rebound feel). Replaces the older
     *  binary white-flash effect with something closer to a real button being depressed.
     *  Rapid-press correctness: cancelling an animator still fires its onAnimationEnd,
     *  so we capture each AnimatorSet in a final and only reset state if WE are still
     *  the active animator — otherwise the next animation that just started would be
     *  clobbered to alpha=0 by the dying old listener. */
    private void startPressAnimation() {
        // Capture the old animator and the current alpha BEFORE cancelling —
        // cancel() fires onAnimationEnd synchronously and we don't want the old
        // listener mutating state we're about to read. The new pressAnimator
        // assignment happens BEFORE cancel() too, so when the old listener does
        // fire it sees pressAnimator != itself and skips the reset.
        AnimatorSet old = pressAnimator;
        int startAlpha = currentHighlightAlpha;

        // Start fade-in from CURRENT alpha, not 0 — so a press during the previous
        // shadow's fade-out smoothly tops it back up to max instead of dipping
        // through 0 first. Duration scaled proportionally so a re-press from a
        // half-faded state doesn't feel sluggish.
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
                if (pressAnimator != anim) return;  // a newer press took over
                pressedArea = null;
                currentHighlightAlpha = 0;
                postInvalidate();
            }
        });

        // Take over the slot BEFORE cancelling the old animator — its
        // onAnimationEnd will fire synchronously inside cancel(), and now sees
        // pressAnimator != itself, so it leaves pressedArea + alpha alone.
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
                imgOffsetX = 0; imgOffsetY = (h - drawH) / 2f;
            } else {
                drawH = h; drawW = h * imgRatio;
                imgOffsetX = (w - drawW) / 2f; imgOffsetY = 0;
            }
            imgScaleW = drawW; imgScaleH = drawH;
        } else {
            imgScaleW = w; imgScaleH = h;
            imgOffsetX = 0; imgOffsetY = 0;
        }

        actualScreen.set(
            imgOffsetX + screenRect.left * imgScaleW,
            imgOffsetY + screenRect.top * imgScaleH,
            imgOffsetX + screenRect.right * imgScaleW,
            imgOffsetY + screenRect.bottom * imgScaleH
        );

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

        // 65 bezel buttons — pixel-detected from the skin bitmap.
        scanButtonsFromBezel();
    }

    /** Pixel-detect each bezel button's actual face from the skin bitmap.
     *  For each entry in BUTTON_HINTS, search a 40%-expanded window around the
     *  hinted centre, threshold dark pixels (grey&lt;60), 2x2 binary opening,
     *  take the largest connected component, use its bbox as the touch area.
     *  Single special case: CLR's bottom edge is clamped to the bottom of '/'
     *  (CLR's printed label sticks below the actual button face).
     *  All work is done in skin-bitmap pixel coords; results are stored
     *  normalised to 0..1, identical convention to the previous calibration. */
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
            // Per-hint guard: a single bad pixel-read or OOM should NOT take
            // down the whole CDU. Falling back to the hint rect keeps that
            // button working with a slightly looser press overlay.
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
                // Detection miss — fall back to hint rect so the button still works.
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

    /** Add every BUTTON_HINTS entry as a hint-rect TouchArea — the safe fallback
     *  used whenever pixel detection isn't available (skin null, getPixels throws
     *  on this device, OOM during scan, etc). Press overlays are slightly looser
     *  than the auto-detected version but every button still triggers correctly. */
    private void addAllAsHintFallback() {
        for (Hint h : BUTTON_HINTS) {
            touchAreas.add(new TouchArea(h.cx - h.hw, h.cy - h.hh,
                    h.cx + h.hw, h.cy + h.hh, h.label, h.shape));
        }
    }

    /** Find the largest dark cluster within a hinted search region. Returns the
     *  bbox in normalised 0..1 coords, or null if nothing dark enough was found. */
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

        // 2x2 binary opening = erode then dilate. Removes thin stroke noise like
        // printed text, leaves filled button faces intact.
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

    /** 4-connectivity two-pass union-find connected-components labelling.
     *  Writes labels (1..n) into `labels`; returns the number of components.
     *  No external scipy dependency — minimal allocation, runs in single-digit
     *  ms on a tablet for the ~7K-pixel windows used by the bezel scan. */
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
            // Big amber status text replaces the char grid until live data clears it.
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

        // Draw UI elements (top bar)
        drawLedIndicator(canvas);
        drawAnnunciators(canvas);

        drawPressOverlay(canvas);
    }

    // Reusable RectF for the press overlay so we don't allocate every frame
    // and — more importantly — so we can call the RectF overload of
    // drawRoundRect (API 1) instead of the 6-float overload (API 21+ only)
    // which crashes with NoSuchMethodError on Android 4.4 / dalvikvm.
    private final RectF pressOverlayRect = new RectF();
    private final RectF ledRectF = new RectF();

    /** Press overlay sized to the auto-detected button face. Shape comes from
     *  TouchArea.shape (set by the bezel scan or hint metadata) — circles for
     *  numeric/period/+- keys, rounded rects for everything else. The bbox is
     *  already pixel-tight to the button face so we draw the whole rect, no inset. */
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


    /** Render the EXEC LED slot above the EXEC button. Drawn at 90% of
     *  EXEC_LED_RECT (5% inset each side) so the lit panel sits inside the
     *  bezel cutout instead of edge-to-edge.
     *  Lit = bright white with a soft outer glow (PMDG sim render); off = dark
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

    /** Draw the four bezel-strip annunciators (MSG / OFST / CALL / FAIL) as
     *  stacked vertical text — one character per line going down, characters
     *  upright (NOT canvas.rotate). Inactive annunciators render in their dim
     *  colour so the labels stay readable on the bezel even when off. Mirrors
     *  the FMC737 v0.3.0 implementation verbatim with iFly's marker positions:
     *  CALL on the left strip top, FAIL left bottom, MSG right top, OFST right bottom. */
    private void drawAnnunciators(Canvas canvas) {
        // Char size scales with drawn skin height (NOT raw view height) so it
        // stays proportional through letterboxing on different tablet aspects.
        // Same coefficient as FMC737 — tune here if visual review demands.
        float charSize = imgScaleH * 0.0144f * 1.152f;
        if (charSize < 1f) return;  // pre-layout; nothing meaningful to draw
        annLabelPaint.setTextSize(charSize);
        annLabelPaint.setFakeBoldText(true);

        Paint.FontMetrics fm = annLabelPaint.getFontMetrics();
        float lineH = charSize;
        float baselineAdjust = (fm.ascent + fm.descent) / 2f;

        drawOneAnnunciator(canvas, "MSG",  ANNUN_RIGHT_TOP_RECT,
                ledMsg  || LED_TEST_ALL_ON, ANN_COLOR_MSG_ON,  ANN_COLOR_DIM_WHITE,
                lineH, baselineAdjust);
        drawOneAnnunciator(canvas, "OFST", ANNUN_RIGHT_BOTTOM_RECT,
                ledOfst || LED_TEST_ALL_ON, ANN_COLOR_OFST_ON, ANN_COLOR_DIM_AMBER,
                lineH, baselineAdjust);
        drawOneAnnunciator(canvas, "CALL", ANNUN_LEFT_TOP_RECT,
                ledCall || LED_TEST_ALL_ON, ANN_COLOR_CALL_ON, ANN_COLOR_DIM_WHITE,
                lineH, baselineAdjust);
        drawOneAnnunciator(canvas, "FAIL", ANNUN_LEFT_BOTTOM_RECT,
                ledFail || LED_TEST_ALL_ON, ANN_COLOR_FAIL_ON, ANN_COLOR_DIM_RED,
                lineH, baselineAdjust);
    }

    private void drawOneAnnunciator(Canvas canvas, String label, float[] rect,
                                    boolean lit, int colorOn, int colorOff,
                                    float lineH, float baselineAdjust) {
        float px = imgOffsetX + ((rect[0] + rect[2]) * 0.5f) * imgScaleW;
        float py = imgOffsetY + ((rect[1] + rect[3]) * 0.5f) * imgScaleH;
        annLabelPaint.setColor(lit ? colorOn : colorOff);
        if (lit) {
            annLabelPaint.setShadowLayer(lineH * 0.35f, 0f, 0f, colorOn);
        } else {
            annLabelPaint.clearShadowLayer();
        }
        float topY = py - ((label.length() - 1) * lineH) / 2f;
        for (int k = 0; k < label.length(); k++) {
            float charY = topY + k * lineH - baselineAdjust;
            canvas.drawText(String.valueOf(label.charAt(k)), px, charY, annLabelPaint);
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
