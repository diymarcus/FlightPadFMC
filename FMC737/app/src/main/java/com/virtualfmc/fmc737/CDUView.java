package com.virtualfmc.fmc737;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

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

    private long lastTouchTime = 0;
    private static final long TOUCH_DEBOUNCE_MS = 150;

    // UI element positions (calibrated 2026-04-09 for top-bar; LED refreshed
    // 2026-05-03 by Marcus's cockpit-tablet LED-only calibration sweep).
    private float settingsX = 0.1269f, settingsY = 0.0285f;
    private float closeX   = 0.8810f, closeY   = 0.0285f;
    private float ledX     = 0.826781f, ledY     = 0.5711992f;

    // ─── Bezel-strip annunciators (PMDG NG3 layout) ───────────────────────────
    // Real PMDG NG3 CDU has 4 bezel-mounted annunciators around the screen
    // (in addition to the EXEC LED below the screen): MSG, OFST, CALL, FAIL.
    // 737 has NO DSPY (that's 777-only); 777 has NO CALL (that's 737-only).
    // Each label is rendered as stacked vertical text (one char per line),
    // bold, no rectangle. Inactive labels stay visible but dimmed —
    // same pattern Fenix/FBW/FMC777 use.
    //
    // Indices:  0 = MSG, 1 = OFST, 2 = CALL, 3 = FAIL
    static final int ANN_MSG  = 0;
    static final int ANN_OFST = 1;
    static final int ANN_CALL = 2;
    static final int ANN_FAIL = 3;

    private static final String[] ANN_NAMES = {"MSG", "OFST", "CALL", "FAIL"};
    private static final String[] ANN_PREF_KEYS = {"FMC_MSG", "FMC_OFST", "FMC_CALL", "FMC_FAIL"};
    // PMDG NG3 sim render colours — verified in-sim 2026-05-03.
    // MSG renders white in PMDG sim (same as the 777 case — real-cockpit
    // photos hint amber but PMDG's render is white). OFST/FAIL stay amber,
    // CALL is white. Flip any of these if PMDG ever changes the render.
    private static final int[] ANN_COLOR_ON = {
        Color.rgb(255, 255, 255),  // MSG  — white  (PMDG NG3 in-sim render)
        Color.rgb(255, 160,   0),  // OFST — amber
        Color.rgb(255, 255, 255),  // CALL — white
        Color.rgb(255, 160,   0),  // FAIL — amber
    };
    private static final int[] ANN_COLOR_OFF = {
        Color.rgb( 80,  80, 80),   // dim white
        Color.rgb( 80,  50,  0),   // dim amber
        Color.rgb( 80,  80, 80),   // dim white
        Color.rgb( 80,  50,  0),   // dim amber
    };
    // Defaults baked from Marcus's 2026-05-03 cockpit-tablet LED-only
    // calibration sweep (LogCat `CALIBRATION737` tag). Same provenance
    // pattern as FMC777 / Fenix / FBW — uncalibrated installs land on the
    // right NG3 bezel positions out of the box. Indices: 0=MSG, 1=OFST,
    // 2=CALL, 3=FAIL. Bottom row (OFST, FAIL) carries a -0.004 X / +0.0024 Y
    // post-cal nudge from 2026-05-03 LED_TEST_ALL_ON visual review (+5px down,
    // -2px up correction = net +3px down vs raw calibration).
    private final float[] annX = {0.93464273f, 0.9339102f,  0.06420826f,  0.063475714f};
    private final float[] annY = {0.7470603f,  0.82442995f, 0.7470603f,   0.82442995f};
    private final boolean[] annOn = {false, false, false, false};

    // DEBUG: force all 4 bezel annunciators ON regardless of server data —
    // set true to verify positioning + colours after future skin/calibration
    // changes, then set back to false. Mirrors FBWA320's LED_TEST_ALL_ON.
    private static final boolean LED_TEST_ALL_ON = false;

    // Screen rect calibration (TL + BR corners) — calibrated 2026-04-09
    private float screenL = 0.1457f, screenT = 0.0792f;
    private float screenR = 0.8592f, screenB = 0.4427f;

    private boolean ledState = false;

    private final char[][] symbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][] colors = new int[CDU_ROWS][CDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Dedicated paint for the bezel annunciators — system Monospace Bold
    // (NOT B612 from assets), CENTER-aligned per-char. Mirrors FMC777's
    // bulletproof pattern so font-metric variance across devices can't
    // shift the stack horizontally.
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

    private float ledWidth;
    private float ledHeight;
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

    private static class TouchArea {
        final RectF rect;
        final String key;
        TouchArea(float l, float t, float r, float b, String k) {
            rect = new RectF(l, t, r, b);
            key = k;
        }
    }

    public CDUView(Context context) {
        super(context);
        init(context);
        loadCalibrationData(context);
        setupLayout();
    }

    public CDUView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
        loadCalibrationData(context);
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

        highlightPaint.setColor(Color.argb(128, 255, 255, 255));
        highlightPaint.setStyle(Paint.Style.FILL);

        boxPaint.setColor(Color.argb(200, 40, 40, 40));
        boxPaint.setStyle(Paint.Style.FILL);

        ledBoxPaint.setStyle(Paint.Style.FILL);
        ledGlowFilter = new BlurMaskFilter(5, BlurMaskFilter.Blur.NORMAL);

        // Connection status overlay paint — big amber text on the CDU screen
        statusPaint.setColor(Color.rgb(255, 160, 0));  // amber
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // Annunciator label paint — system Monospace Bold, CENTER-aligned
        // per char. Each char is drawn independently centred on px so the
        // stack stays put across devices regardless of which font is loaded.
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

                        postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                pressedArea = null;
                                postInvalidate();
                            }
                        }, 100);
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

    private void loadCalibrationData(Context context) {
        // Always start from safe defaults, then let any saved prefs override
        // them per-key. This way an LEDs-only save (which leaves
        // calibration_complete untouched) still applies, and a fresh install
        // simply keeps the defaults. Same pattern as FMC777 / Fenix / FBW.
        resetToSafeDefaults();
        try {
            SharedPreferences prefs = context.getSharedPreferences("CalibrationData737", Context.MODE_PRIVATE);

            float sX = prefs.getFloat("SETTINGS_BTN_x", -1f);
            float sY = prefs.getFloat("SETTINGS_BTN_y", -1f);
            if (isValidCalibration(sX, sY)) { settingsX = sX; settingsY = sY; }

            float cX = prefs.getFloat("CLOSE_BTN_x", -1f);
            float cY = prefs.getFloat("CLOSE_BTN_y", -1f);
            if (isValidCalibration(cX, cY)) { closeX = cX; closeY = cY; }

            float lX = prefs.getFloat("FMC_LED_x", -1f);
            float lY = prefs.getFloat("FMC_LED_y", -1f);
            if (isValidCalibration(lX, lY)) { ledX = lX; ledY = lY; }

            for (int i = 0; i < ANN_PREF_KEYS.length; i++) {
                float ax = prefs.getFloat(ANN_PREF_KEYS[i] + "_x", -1f);
                float ay = prefs.getFloat(ANN_PREF_KEYS[i] + "_y", -1f);
                if (isValidCalibration(ax, ay)) { annX[i] = ax; annY[i] = ay; }
            }

            float sTL_x = prefs.getFloat("SCREEN_TL_x", -1f);
            float sTL_y = prefs.getFloat("SCREEN_TL_y", -1f);
            float sBR_x = prefs.getFloat("SCREEN_BR_x", -1f);
            float sBR_y = prefs.getFloat("SCREEN_BR_y", -1f);
            if (isValidCalibration(sTL_x, sTL_y, sBR_x, sBR_y)) {
                screenL = sTL_x; screenT = sTL_y;
                screenR = sBR_x; screenB = sBR_y;
            }
        } catch (Exception e) {
            // defaults already in place
        }
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

    private boolean isValidCalibration(float... values) {
        for (float v : values) {
            if (v < 0f || v > 1f) return false;
        }
        return true;
    }

    private void resetToSafeDefaults() {
        // UI Elements: top-bar from 2026-04-09 calibration, LED + bezel
        // annunciators from Marcus's 2026-05-03 cockpit-tablet LED-only
        // calibration sweep. Keep these in sync with the field initialisers
        // up top.
        settingsX = 0.1269f; settingsY = 0.0285f;
        closeX    = 0.8810f; closeY    = 0.0285f;
        ledX      = 0.826781f; ledY    = 0.5711992f;
        annX[ANN_MSG]  = 0.93464273f;  annY[ANN_MSG]  = 0.7470603f;
        annX[ANN_OFST] = 0.9339102f;   annY[ANN_OFST] = 0.82442995f;
        annX[ANN_CALL] = 0.06420826f;  annY[ANN_CALL] = 0.7470603f;
        annX[ANN_FAIL] = 0.063475714f; annY[ANN_FAIL] = 0.82442995f;
        screenL   = 0.1457f; screenT   = 0.0792f;
        screenR   = 0.8592f; screenB   = 0.4427f;
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

    public void setLedState(boolean isOn) {
        ledState = isOn;
        postInvalidate();
    }

    /** Set the on/off state of one bezel annunciator (MSG, OFST, CALL, FAIL).
     *  Use the ANN_* constants for the index. Off state still renders as
     *  dimmed text so the label remains visible on the bezel. */
    public void setAnnunciatorState(int annIndex, boolean isOn) {
        if (annIndex < 0 || annIndex >= annOn.length) return;
        annOn[annIndex] = isOn;
        postInvalidate();
    }

    /** Re-read calibration prefs and redraw. Call from MainActivity.onResume
     *  so positions saved by CalibrateActivity737 take effect immediately
     *  when the user returns to the CDU, without an app restart. */
    public void reloadCalibration(Context context) {
        loadCalibrationData(context);
        setupLayout();
        postInvalidate();
    }

    /** Show a connection status message on the CDU screen area.
     *  Pass null or "" to clear the overlay and show the normal CDU character grid.
     *  Supports "\n" for multi-line messages. */
    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
        postInvalidate();
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


        ledWidth = h * 0.05f;
        ledHeight = h * 0.018f;
    }

    private void setupLayout() {
        if (touchAreas == null) touchAreas = new ArrayList<>();
        else touchAreas.clear();

        // UI elements (Settings, Close, FMC text) - from calibration
        touchAreas.add(new TouchArea(settingsX - 0.04f, settingsY - 0.025f, settingsX + 0.04f, settingsY + 0.025f, "SETTINGS_BTN"));
        touchAreas.add(new TouchArea(closeX - 0.04f, closeY - 0.025f, closeX + 0.04f, closeY + 0.025f, "CLOSE_BTN"));

        // Screen area — from calibration (falls back to hardcoded defaults)
        screenRect.set(screenL, screenT, screenR, screenB);

        // LSK Left buttons — calibrated 2026-04-09
        String[] leftKeys  = {"LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L"};
        float[] lskLX = {0.0467f, 0.0487f, 0.0467f, 0.0467f, 0.0487f, 0.0517f};
        float[] lskLY = {0.1554f, 0.2025f, 0.2514f, 0.3003f, 0.3510f, 0.4012f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskLX[i] - 0.035f, lskLY[i] - 0.025f, lskLX[i] + 0.035f, lskLY[i] + 0.025f, leftKeys[i]));
        }

        // LSK Right buttons — calibrated 2026-04-09
        String[] rightKeys = {"LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R"};
        float[] lskRX = {0.9532f, 0.9552f, 0.9552f, 0.9552f, 0.9552f, 0.9562f};
        float[] lskRY = {0.1554f, 0.2025f, 0.2532f, 0.3021f, 0.3510f, 0.4000f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskRX[i] - 0.035f, lskRY[i] - 0.025f, lskRX[i] + 0.035f, lskRY[i] + 0.025f, rightKeys[i]));
        }

        // Function Row 1 — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1635f - 0.055f, 0.5430f - 0.025f, 0.1635f + 0.055f, 0.5430f + 0.025f, "INIT"));
        touchAreas.add(new TouchArea(0.2922f - 0.055f, 0.5411f - 0.025f, 0.2922f + 0.055f, 0.5411f + 0.025f, "RTE"));
        touchAreas.add(new TouchArea(0.4149f - 0.055f, 0.5969f - 0.025f, 0.4149f + 0.055f, 0.5969f + 0.025f, "DEP"));
        touchAreas.add(new TouchArea(0.4149f - 0.055f, 0.5393f - 0.025f, 0.4149f + 0.055f, 0.5393f + 0.025f, "CLB"));
        touchAreas.add(new TouchArea(0.5405f - 0.055f, 0.5393f - 0.025f, 0.5405f + 0.055f, 0.5393f + 0.025f, "CRZ"));
        touchAreas.add(new TouchArea(0.6642f - 0.055f, 0.5418f - 0.025f, 0.6642f + 0.055f, 0.5418f + 0.025f, "DES"));

        // Function Row 2 — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1675f - 0.055f, 0.5969f - 0.025f, 0.1675f + 0.055f, 0.5969f + 0.025f, "MENU"));
        touchAreas.add(new TouchArea(0.2882f - 0.055f, 0.5981f - 0.025f, 0.2882f + 0.055f, 0.5981f + 0.025f, "LEGS"));
        touchAreas.add(new TouchArea(0.5405f - 0.055f, 0.5969f - 0.025f, 0.5405f + 0.055f, 0.5969f + 0.025f, "HOLD"));
        touchAreas.add(new TouchArea(0.6632f - 0.055f, 0.5981f - 0.025f, 0.6632f + 0.055f, 0.5981f + 0.025f, "PROG"));
        touchAreas.add(new TouchArea(0.8295f - 0.055f, 0.6018f - 0.025f, 0.8295f + 0.055f, 0.6018f + 0.025f, "EXEC"));

        // Key Row 1 — calibrated 2026-04-09 (N1LIMIT, FIX, MAN)
        touchAreas.add(new TouchArea(0.1635f - 0.055f, 0.6588f - 0.025f, 0.1635f + 0.055f, 0.6588f + 0.025f, "N1LIMIT"));
        touchAreas.add(new TouchArea(0.2882f - 0.055f, 0.6563f - 0.025f, 0.2882f + 0.055f, 0.6563f + 0.025f, "FIX"));
        touchAreas.add(new TouchArea(0.7939f - 0.055f, 0.5325f - 0.025f, 0.7939f + 0.055f, 0.5325f + 0.025f, "MAN"));

        // Key Row 2 — calibrated 2026-04-09 (PREV PAGE, NEXT PAGE, PREV MENU)
        touchAreas.add(new TouchArea(0.1615f - 0.055f, 0.7151f - 0.025f, 0.1615f + 0.055f, 0.7151f + 0.025f, "PREV PAGE"));
        touchAreas.add(new TouchArea(0.2941f - 0.055f, 0.7151f - 0.025f, 0.2941f + 0.055f, 0.7151f + 0.025f, "NEXT PAGE"));
        touchAreas.add(new TouchArea(0.8720f - 0.055f, 0.5319f - 0.025f, 0.8720f + 0.055f, 0.5319f + 0.025f, "PREV MENU"));

        // Letters A-E — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.4554f - 0.045f, 0.6607f - 0.025f, 0.4554f + 0.045f, 0.6607f + 0.025f, "A"));
        touchAreas.add(new TouchArea(0.5544f - 0.045f, 0.6619f - 0.025f, 0.5544f + 0.045f, 0.6619f + 0.025f, "B"));
        touchAreas.add(new TouchArea(0.6543f - 0.045f, 0.6607f - 0.025f, 0.6543f + 0.045f, 0.6607f + 0.025f, "C"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.6607f - 0.025f, 0.7503f + 0.045f, 0.6607f + 0.025f, "D"));
        touchAreas.add(new TouchArea(0.8473f - 0.045f, 0.6588f - 0.025f, 0.8473f + 0.045f, 0.6588f + 0.025f, "E"));

        // Letters F-J — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.4584f - 0.045f, 0.7176f - 0.025f, 0.4584f + 0.045f, 0.7176f + 0.025f, "F"));
        touchAreas.add(new TouchArea(0.5554f - 0.045f, 0.7189f - 0.025f, 0.5554f + 0.045f, 0.7189f + 0.025f, "G"));
        touchAreas.add(new TouchArea(0.6494f - 0.045f, 0.7189f - 0.025f, 0.6494f + 0.045f, 0.7189f + 0.025f, "H"));
        touchAreas.add(new TouchArea(0.7513f - 0.045f, 0.7157f - 0.025f, 0.7513f + 0.045f, 0.7157f + 0.025f, "I"));
        touchAreas.add(new TouchArea(0.8453f - 0.045f, 0.7195f - 0.025f, 0.8453f + 0.045f, 0.7195f + 0.025f, "J"));

        // Numbers 1-3 + K-O — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1487f - 0.045f, 0.7789f - 0.025f, 0.1487f + 0.045f, 0.7789f + 0.025f, "1"));
        touchAreas.add(new TouchArea(0.2506f - 0.045f, 0.7789f - 0.025f, 0.2506f + 0.045f, 0.7789f + 0.025f, "2"));
        touchAreas.add(new TouchArea(0.3506f - 0.045f, 0.7770f - 0.025f, 0.3506f + 0.045f, 0.7770f + 0.025f, "3"));
        touchAreas.add(new TouchArea(0.4554f - 0.045f, 0.7770f - 0.025f, 0.4554f + 0.045f, 0.7770f + 0.025f, "K"));
        touchAreas.add(new TouchArea(0.5524f - 0.045f, 0.7752f - 0.025f, 0.5524f + 0.045f, 0.7752f + 0.025f, "L"));
        touchAreas.add(new TouchArea(0.6573f - 0.045f, 0.7789f - 0.025f, 0.6573f + 0.045f, 0.7789f + 0.025f, "M"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.7777f - 0.025f, 0.7503f + 0.045f, 0.7777f + 0.025f, "N"));
        touchAreas.add(new TouchArea(0.8503f - 0.045f, 0.7789f - 0.025f, 0.8503f + 0.045f, 0.7789f + 0.025f, "O"));

        // Numbers 4-6 + P-T — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1536f - 0.045f, 0.8365f - 0.025f, 0.1536f + 0.045f, 0.8365f + 0.025f, "4"));
        touchAreas.add(new TouchArea(0.2506f - 0.045f, 0.8365f - 0.025f, 0.2506f + 0.045f, 0.8365f + 0.025f, "5"));
        touchAreas.add(new TouchArea(0.3466f - 0.045f, 0.8365f - 0.025f, 0.3466f + 0.045f, 0.8365f + 0.025f, "6"));
        touchAreas.add(new TouchArea(0.4525f - 0.045f, 0.8365f - 0.025f, 0.4525f + 0.045f, 0.8365f + 0.025f, "P"));
        touchAreas.add(new TouchArea(0.5544f - 0.045f, 0.8359f - 0.025f, 0.5544f + 0.045f, 0.8359f + 0.025f, "Q"));
        touchAreas.add(new TouchArea(0.6494f - 0.045f, 0.8365f - 0.025f, 0.6494f + 0.045f, 0.8365f + 0.025f, "R"));
        touchAreas.add(new TouchArea(0.7483f - 0.045f, 0.8365f - 0.025f, 0.7483f + 0.045f, 0.8365f + 0.025f, "S"));
        touchAreas.add(new TouchArea(0.8453f - 0.045f, 0.8340f - 0.025f, 0.8453f + 0.045f, 0.8340f + 0.025f, "T"));

        // Numbers 7-9 + U-Y — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1487f - 0.045f, 0.8922f - 0.025f, 0.1487f + 0.045f, 0.8922f + 0.025f, "7"));
        touchAreas.add(new TouchArea(0.2506f - 0.045f, 0.8928f - 0.025f, 0.2506f + 0.045f, 0.8928f + 0.025f, "8"));
        touchAreas.add(new TouchArea(0.3466f - 0.045f, 0.8922f - 0.025f, 0.3466f + 0.045f, 0.8922f + 0.025f, "9"));
        touchAreas.add(new TouchArea(0.4554f - 0.045f, 0.8947f - 0.025f, 0.4554f + 0.045f, 0.8947f + 0.025f, "U"));
        touchAreas.add(new TouchArea(0.5544f - 0.045f, 0.8947f - 0.025f, 0.5544f + 0.045f, 0.8947f + 0.025f, "V"));
        touchAreas.add(new TouchArea(0.6543f - 0.045f, 0.8947f - 0.025f, 0.6543f + 0.045f, 0.8947f + 0.025f, "W"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.8947f - 0.025f, 0.7503f + 0.045f, 0.8947f + 0.025f, "X"));
        touchAreas.add(new TouchArea(0.8453f - 0.045f, 0.8947f - 0.025f, 0.8453f + 0.045f, 0.8947f + 0.025f, "Y"));

        // Bottom Row — calibrated 2026-04-09
        touchAreas.add(new TouchArea(0.1536f - 0.045f, 0.9523f - 0.025f, 0.1536f + 0.045f, 0.9523f + 0.025f, "."));
        touchAreas.add(new TouchArea(0.2506f - 0.045f, 0.9523f - 0.025f, 0.2506f + 0.045f, 0.9523f + 0.025f, "0"));
        touchAreas.add(new TouchArea(0.3495f - 0.045f, 0.9523f - 0.025f, 0.3495f + 0.045f, 0.9523f + 0.025f, "+/-"));
        touchAreas.add(new TouchArea(0.4535f - 0.045f, 0.9523f - 0.025f, 0.4535f + 0.045f, 0.9523f + 0.025f, "Z"));
        touchAreas.add(new TouchArea(0.5544f - 0.045f, 0.9535f - 0.025f, 0.5544f + 0.045f, 0.9535f + 0.025f, "SP"));
        touchAreas.add(new TouchArea(0.6514f - 0.045f, 0.9510f - 0.025f, 0.6514f + 0.045f, 0.9510f + 0.025f, "DEL"));
        touchAreas.add(new TouchArea(0.7483f - 0.045f, 0.9510f - 0.025f, 0.7483f + 0.045f, 0.9510f + 0.025f, "/"));
        touchAreas.add(new TouchArea(0.8473f - 0.045f, 0.9523f - 0.025f, 0.8473f + 0.045f, 0.9523f + 0.025f, "CLR"));
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
            statusPaint.setTextSize(screenH * 0.07f);
            float cx = actualScreen.centerX();
            float cy = actualScreen.centerY();
            String[] lines = connectionStatus.split("\n");
            float lineSpacing = screenH * 0.10f;
            float startY = cy - ((lines.length - 1) * lineSpacing * 0.5f);
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], cx, startY + i * lineSpacing, statusPaint);
            }
            drawLedIndicator(canvas);
            drawAnnunciators(canvas);
            if (pressedArea != null) {
                float px = imgOffsetX + pressedArea.rect.centerX() * imgScaleW;
                float py = imgOffsetY + pressedArea.rect.centerY() * imgScaleH;
                float radius = (Math.min(pressedArea.rect.width() * imgScaleW, pressedArea.rect.height() * imgScaleH) / 2f) * 0.7f;
                canvas.drawCircle(px, py, radius, highlightPaint);
            }
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

        // Flash effect
        if (pressedArea != null) {
            float px = imgOffsetX + pressedArea.rect.centerX() * imgScaleW;
            float py = imgOffsetY + pressedArea.rect.centerY() * imgScaleH;
            float radius = (Math.min(pressedArea.rect.width() * imgScaleW, pressedArea.rect.height() * imgScaleH) / 2f) * 0.7f;
            canvas.drawCircle(px, py, radius, highlightPaint);
        }
    }


    private void drawLedIndicator(Canvas canvas) {
        float px = imgOffsetX + ledX * imgScaleW;
        float py = imgOffsetY + ledY * imgScaleH;

        if (ledState || LED_TEST_ALL_ON) {
            ledBoxPaint.setColor(Color.argb(255, 0, 255, 0));
            ledBoxPaint.setMaskFilter(ledGlowFilter);
            canvas.drawRect(px - ledWidth/2, py - ledHeight/2, px + ledWidth/2, py + ledHeight/2, ledBoxPaint);
            ledBoxPaint.setMaskFilter(null);
            ledBoxPaint.setColor(Color.argb(255, 100, 255, 100));
            canvas.drawRect(px - ledWidth/2, py - ledHeight/2, px + ledWidth/2, py + ledHeight/2, ledBoxPaint);
        } else {
            ledBoxPaint.setColor(Color.argb(255, 40, 40, 40));
            canvas.drawRect(px - ledWidth/2, py - ledHeight/2, px + ledWidth/2, py + ledHeight/2, ledBoxPaint);
        }
    }

    /** Draw the four bezel-strip annunciators (MSG, OFST, CALL, FAIL) as
     *  stacked vertical text — one character per line going down, characters
     *  stay upright (NOT canvas.rotate). Inactive annunciators render in
     *  their dim colour so labels stay visible on the bezel even when off.
     *  Mirrors the FMC777 / Fenix / FBW pattern verbatim: dedicated
     *  annLabelPaint with system Monospace Bold, each char drawn
     *  CENTER-aligned on px so font-metric variance across devices can't
     *  shift the stack horizontally. */
    private void drawAnnunciators(Canvas canvas) {
        // Scales with imgScaleH (drawn skin height) so size stays proportional
        // across resolutions and letterboxed layouts. Same coefficient as
        // FMC777 — adjust here if the NG3 bezel labels need to be larger
        // or smaller after live observation.
        float charSize = imgScaleH * 0.0144f * 1.152f;
        annLabelPaint.setTextSize(charSize);
        annLabelPaint.setFakeBoldText(true);

        Paint.FontMetrics fm = annLabelPaint.getFontMetrics();
        float lineH = charSize;

        for (int i = 0; i < ANN_NAMES.length; i++) {
            float px = imgOffsetX + annX[i] * imgScaleW;
            float py = imgOffsetY + annY[i] * imgScaleH;

            boolean lit = LED_TEST_ALL_ON || annOn[i];
            annLabelPaint.setColor(lit ? ANN_COLOR_ON[i] : ANN_COLOR_OFF[i]);

            String label = ANN_NAMES[i];
            float topY = py - ((label.length() - 1) * lineH) / 2f;
            for (int k = 0; k < label.length(); k++) {
                float charY = topY + k * lineH - (fm.ascent + fm.descent) / 2f;
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
