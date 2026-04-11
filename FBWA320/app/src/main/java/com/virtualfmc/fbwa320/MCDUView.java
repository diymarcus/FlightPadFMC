package com.virtualfmc.fbwa320;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * MCDUView — custom View that draws the FlyByWire A320 MCDU.
 *
 * Renders:
 *   - A320 MCDU skin bitmap (res/drawable/mcdu_skin_a320)
 *   - 14-row × 24-col MCDU screen with per-cell color and size (large/small)
 *   - Settings icon, close icon (top overlay)
 *   - 5 LED indicators: FM1 (amber), IND (amber), RDY (green), -- (amber), FM2 (amber)
 *   - Touch areas for all A320 MCDU buttons
 *
 * All button positions are normalized (0..1) relative to skin image dimensions.
 * Use CalibrateActivityA320 to calibrate positions on your specific device/screen.
 *
 * Key difference vs PMDG CDUView:
 *   - Two text sizes: large (normal rows) and small (label rows)
 *   - 8-color palette: white, green, amber, blue, magenta, cyan, red, yellow
 *   - A320 MCDU key layout (DIR, PROG, PERF, INIT, DATA, F-PLN, etc.)
 */
public class MCDUView extends View {

    private static final String TAG       = "MCDUView";
    private static final int    MCDU_ROWS = 14;
    private static final int    MCDU_COLS = 24;

    private SoundPlayer soundPlayer;
    private boolean soundEnabled  = true;
    private boolean hapticEnabled = true;

    private TouchArea pressedArea = null;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastTouchTime  = 0;
    private static final long TOUCH_DEBOUNCE_MS = 150;

    // UI overlay positions (normalized 0..1)
    private float settingsX = 0.11016846f, settingsY = 0.028602801f;
    private float closeX    = 0.8925476f,  closeY    = 0.0309712f;

    // 5 LED indicators: FM1, IND, RDY, --, FM2
    private static final String[] LED_NAMES = {"FM1", "IND", "RDY", "--", "FM2"};
    // OFF colors (very dim)
    private static final int[] LED_COLOR_OFF = {
        Color.rgb(50, 25, 0),   // FM1 — dim amber
        Color.rgb(50, 25, 0),   // IND — dim amber
        Color.rgb(0,  30, 0),   // RDY — dim green
        Color.rgb(50, 25, 0),   // --  — dim amber
        Color.rgb(50, 25, 0)    // FM2 — dim amber
    };
    // ON colors (lit)
    private static final int[] LED_COLOR_ON = {
        Color.rgb(255, 160, 0), // FM1 — amber
        Color.rgb(255, 160, 0), // IND — amber
        Color.GREEN,            // RDY — green
        Color.rgb(255, 160, 0), // --  — amber
        Color.rgb(255, 160, 0)  // FM2 — amber
    };

    // LED positions (normalized 0..1), set from calibration or defaults
    // Recalibrated 2026-04-11 — LED-only pass using the new CalibView with
    // visible tap markers (ported from PMDG 737 calibration pattern).
    private final float[] ledX5 = {0.27807656f, 0.39011383f, 0.49562606f, 0.60655195f, 0.7175136f};
    private final float[] ledY5 = {0.023003776f,0.023003776f,0.023003776f,0.023003776f,0.023003776f};
    private final boolean[] ledOn = new boolean[5];  // individual on/off per LED

    // Screen rect corners from calibration (normalized 0..1)
    private float calibScreenL = 0.15924072f, calibScreenT = 0.07854814f;
    private float calibScreenR = 0.8425598f,  calibScreenB = 0.4440826f;

    private final char[][]    symbols = new char[MCDU_ROWS][MCDU_COLS];
    private final int[][]     colors  = new int[MCDU_ROWS][MCDU_COLS];
    private final boolean[][] small   = new boolean[MCDU_ROWS][MCDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint     = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect   = new RectF();
    private final RectF actualScreen = new RectF();
    private final RectF skinRectF    = new RectF();

    private float ledBoxW, ledBoxH;
    private float cellW, cellH;
    private float textYOffset, smallYOffset;

    private List<TouchArea> touchAreas = new ArrayList<>();

    public interface KeyPressListener {
        void onKeyPress(String key);
        void onUIAction(String action);
    }
    private KeyPressListener keyListener;

    private float imgOffsetX = 0, imgOffsetY = 0;
    private float imgScaleW  = 1, imgScaleH  = 1;

    private static class TouchArea {
        final RectF  rect;
        final String key;
        TouchArea(float l, float t, float r, float b, String k) {
            rect = new RectF(l, t, r, b);
            key  = k;
        }
    }

    // ─── Constructors ─────────────────────────────────────────────────────────

    public MCDUView(Context context) {
        super(context);
        init(context);
        loadCalibrationData(context);
        setupLayout();
    }

    public MCDUView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
        loadCalibrationData(context);
        setupLayout();
    }

    // ─── Init ─────────────────────────────────────────────────────────────────

    private void init(Context context) {
        touchAreas = new ArrayList<>();

        int maxBitmapSize = getMaxBitmapSize();
        int safeSize      = Math.min(1024, maxBitmapSize / 2);

        soundPlayer = SoundPlayer.getInstance();
        soundPlayer.initialize(context);

        try {
            skin = decodeSampledBitmap(context, R.drawable.mcdu_skin_a320, safeSize);
            if (skin != null) {
                android.util.Log.d(TAG, "A320 skin loaded: " + skin.getWidth() + "x" + skin.getHeight());
                if (skin.getWidth() > maxBitmapSize || skin.getHeight() > maxBitmapSize) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to load A320 skin — using dark background", e);
        }

        for (int r = 0; r < MCDU_ROWS; r++)
            for (int c = 0; c < MCDU_COLS; c++) {
                symbols[r][c] = ' ';
                colors[r][c]  = Color.WHITE;
                small[r][c]   = false;
            }

        try {
            Typeface b612 = Typeface.createFromAsset(context.getAssets(), "fonts/B612Mono-Bold.ttf");
            textPaint.setTypeface(b612);
            smallPaint.setTypeface(b612);
            android.util.Log.d(TAG, "B612Mono-Bold font loaded");
        } catch (Exception e) {
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            smallPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            android.util.Log.w(TAG, "B612Mono not found, using fallback");
        }

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        smallPaint.setColor(Color.WHITE);
        smallPaint.setTextAlign(Paint.Align.CENTER);

        highlightPaint.setColor(Color.argb(128, 255, 255, 255));
        highlightPaint.setStyle(Paint.Style.FILL);

        boxPaint.setColor(Color.argb(200, 40, 40, 40));
        boxPaint.setStyle(Paint.Style.FILL);

        ledBoxPaint.setStyle(Paint.Style.FILL);

        ledLabelPaint.setTextAlign(Paint.Align.CENTER);
        ledLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_DOWN) return true;

                long now = System.currentTimeMillis();
                if (lastTouchTime > 0 && now - lastTouchTime < TOUCH_DEBOUNCE_MS) return true;
                lastTouchTime = now;

                float fx = (event.getX() - imgOffsetX) / imgScaleW;
                float fy = (event.getY() - imgOffsetY) / imgScaleH;

                TouchArea found = null;
                for (TouchArea ta : touchAreas) {
                    if (ta.rect.contains(fx, fy)) { found = ta; break; }
                }

                if (found != null) {
                    v.performClick();
                    pressedArea = found;
                    postInvalidate();

                    if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (soundEnabled && soundPlayer != null) soundPlayer.playButtonClick();

                    if (keyListener != null) {
                        if ("SETTINGS_BTN".equals(found.key))  keyListener.onUIAction("SETTINGS_BTN");
                        else if ("CLOSE_BTN".equals(found.key)) keyListener.onUIAction("CLOSE_BTN");
                        else if ("CALIBRATE".equals(found.key)) keyListener.onUIAction("CALIBRATE");
                        else                                    keyListener.onKeyPress(found.key);
                    }

                    postDelayed(new Runnable() {
                        @Override
                        public void run() { pressedArea = null; postInvalidate(); }
                    }, 100);
                }
                return true;
            }
        });
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    // ─── Calibration ──────────────────────────────────────────────────────────

    private void loadCalibrationData(Context context) {
        try {
            SharedPreferences prefs =
                context.getSharedPreferences("CalibrationDataA320", Context.MODE_PRIVATE);

            if (!prefs.getBoolean("calibration_complete", false)) {
                resetToSafeDefaults();
                return;
            }

            // Settings / close icons
            float sX = prefs.getFloat("SETTINGS_BTN_x", -1f);
            float sY = prefs.getFloat("SETTINGS_BTN_y", -1f);
            float cX = prefs.getFloat("CLOSE_BTN_x",    -1f);
            float cY = prefs.getFloat("CLOSE_BTN_y",    -1f);
            if (isValid(sX, sY, cX, cY)) {
                settingsX = sX; settingsY = sY;
                closeX    = cX; closeY    = cY;
            }

            // Screen rect corners
            float scrTLx = prefs.getFloat("SCREEN_TL_x", -1f);
            float scrTLy = prefs.getFloat("SCREEN_TL_y", -1f);
            float scrBRx = prefs.getFloat("SCREEN_BR_x", -1f);
            float scrBRy = prefs.getFloat("SCREEN_BR_y", -1f);
            if (isValid(scrTLx, scrTLy, scrBRx, scrBRy) && scrBRx > scrTLx && scrBRy > scrTLy) {
                calibScreenL = scrTLx; calibScreenT = scrTLy;
                calibScreenR = scrBRx; calibScreenB = scrBRy;
            }

            // 5 LED positions
            String[] ledKeys = {"LED_FM1","LED_IND","LED_RDY","LED_DASH","LED_FM2"};
            for (int i = 0; i < 5; i++) {
                float lx = prefs.getFloat(ledKeys[i] + "_x", -1f);
                float ly = prefs.getFloat(ledKeys[i] + "_y", -1f);
                if (isValid(lx, ly)) { ledX5[i] = lx; ledY5[i] = ly; }
            }

        } catch (Exception e) {
            resetToSafeDefaults();
        }
    }

    private boolean isValid(float... vals) {
        for (float v : vals) if (v < 0f || v > 1f) return false;
        return true;
    }

    private void resetToSafeDefaults() {
        // Calibrated 2026-04-09 on FBWA320 skin
        settingsX = 0.11016846f; settingsY = 0.028602801f;
        closeX    = 0.8925476f;  closeY    = 0.0309712f;

        calibScreenL = 0.15924072f; calibScreenT = 0.07854814f;
        calibScreenR = 0.8425598f;  calibScreenB = 0.4440826f;

        // LED defaults — recalibrated 2026-04-11 (LED-only pass with CalibView markers).
        ledX5[0] = 0.27807656f; ledY5[0] = 0.023003776f; // FM1
        ledX5[1] = 0.39011383f; ledY5[1] = 0.023003776f; // IND
        ledX5[2] = 0.49562606f; ledY5[2] = 0.023003776f; // RDY
        ledX5[3] = 0.60655195f; ledY5[3] = 0.023003776f; // --
        ledX5[4] = 0.7175136f;  ledY5[4] = 0.023003776f; // FM2
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private void setupLayout() {
        if (touchAreas == null) touchAreas = new ArrayList<>();
        else touchAreas.clear();

        touchAreas.add(new TouchArea(settingsX-0.04f, settingsY-0.025f, settingsX+0.04f, settingsY+0.025f, "SETTINGS_BTN"));
        touchAreas.add(new TouchArea(closeX   -0.04f, closeY   -0.025f, closeX   +0.04f, closeY   +0.025f, "CLOSE_BTN"));

        // Screen rect — from calibration or defaults
        screenRect.set(calibScreenL, calibScreenT, calibScreenR, calibScreenB);

        // ── LSK Left — calibrated 2026-04-09 ─────────────────────────────────
        String[] leftKeys = {"LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L"};
        float[] lskLX = {0.054626465f, 0.05923462f, 0.054626465f, 0.057403564f, 0.05923462f, 0.054626465f};
        float[] lskLY = {0.1451071f, 0.19505244f, 0.24437086f, 0.2955004f, 0.3454109f, 0.39594835f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskLX[i]-0.035f, lskLY[i]-0.025f, lskLX[i]+0.035f, lskLY[i]+0.025f, leftKeys[i]));
        }

        // ── LSK Right — calibrated 2026-04-09 ────────────────────────────────
        String[] rightKeys = {"LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R"};
        float[] lskRX = {0.9453125f, 0.9471741f, 0.9471741f, 0.9480896f, 0.9480896f, 0.9480896f};
        float[] lskRY = {0.1451071f, 0.19505244f, 0.24437086f, 0.2955004f, 0.3465951f, 0.39831677f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskRX[i]-0.035f, lskRY[i]-0.025f, lskRX[i]+0.035f, lskRY[i]+0.025f, rightKeys[i]));
        }

        // ── Function Row 1 — calibrated 2026-04-09 ───────────────────────────
        String[] row1Keys = {"DIR","PROG","PERF","INIT","DATA"};
        float[] row1X = {0.16479492f, 0.28329468f, 0.39718628f, 0.513855f, 0.6305237f};
        float   row1Y = 0.51718944f;
        for (int i = 0; i < 5; i++) {
            touchAreas.add(new TouchArea(row1X[i]-0.055f, row1Y-0.028f, row1X[i]+0.055f, row1Y+0.028f, row1Keys[i]));
        }
        touchAreas.add(new TouchArea(0.8647766f-0.045f, 0.51718944f-0.025f, 0.8647766f+0.045f, 0.51718944f+0.025f, "BRT"));
        touchAreas.add(new TouchArea(0.8647766f-0.045f, 0.56891114f-0.025f, 0.8647766f+0.045f, 0.56891114f+0.025f, "DIM"));

        // ── Function Row 2 — calibrated 2026-04-09 ───────────────────────────
        String[] row2Keys = {"FPLN","RADNAV","FUELPRED","SECFPLN","ATCCOMM","MCDU_MENU"};
        float[] row2X = {0.1638794f, 0.28051758f, 0.39535522f, 0.5194092f, 0.6342163f, 0.7499695f};
        float   row2Y = 0.5706874f;
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(row2X[i]-0.055f, row2Y-0.028f, row2X[i]+0.055f, row2Y+0.028f, row2Keys[i]));
        }

        // ── AIRPORT — calibrated 2026-04-09 ───────────────────────────────────
        touchAreas.add(new TouchArea(0.15924072f-0.055f, 0.6206327f-0.028f, 0.15924072f+0.055f, 0.6206327f+0.028f, "AIRPORT"));

        // ── Arrows — calibrated 2026-04-09 ────────────────────────────────────
        touchAreas.add(new TouchArea(0.15924072f-0.045f, 0.67472273f-0.030f, 0.15924072f+0.045f, 0.67472273f+0.030f, "SLEW_LEFT"));
        touchAreas.add(new TouchArea(0.27774048f-0.045f, 0.67472273f-0.030f, 0.27774048f+0.045f, 0.67472273f+0.030f, "SLEW_UP"));
        touchAreas.add(new TouchArea(0.15924072f-0.045f, 0.7258523f-0.030f,  0.15924072f+0.045f, 0.7258523f+0.030f,  "SLEW_RIGHT"));
        touchAreas.add(new TouchArea(0.27682495f-0.045f, 0.7258523f-0.030f,  0.27682495f+0.045f, 0.7258523f+0.030f,  "SLEW_DOWN"));

        // ── Numbers 1–9 — calibrated 2026-04-09 ──────────────────────────────
        float[] numX = {0.15924072f,0.25460815f,0.3508911f, 0.15924072f,0.25552368f,0.35183716f, 0.15924072f,0.25738525f,0.35461426f};
        float[] numY = {0.79063493f,0.79063493f,0.79063493f, 0.8423566f,0.8423566f,0.8423566f, 0.8982229f,0.89703876f,0.89345133f};
        String[] nums9 = {"1","2","3","4","5","6","7","8","9"};
        for (int i = 0; i < 9; i++) {
            touchAreas.add(new TouchArea(numX[i]-0.045f, numY[i]-0.028f, numX[i]+0.045f, numY[i]+0.028f, nums9[i]));
        }

        // ── Letters A–E — calibrated 2026-04-09 ──────────────────────────────
        touchAreas.add(new TouchArea(0.4721985f-0.045f,  0.65034217f-0.028f, 0.4721985f+0.045f,  0.65034217f+0.028f, "A"));
        touchAreas.add(new TouchArea(0.5647888f-0.045f,  0.6467896f-0.028f,  0.5647888f+0.045f,  0.6467896f+0.028f,  "B"));
        touchAreas.add(new TouchArea(0.6555176f-0.045f,  0.6479738f-0.028f,  0.6555176f+0.045f,  0.6479738f+0.028f,  "C"));
        touchAreas.add(new TouchArea(0.7527466f-0.045f,  0.6479738f-0.028f,  0.7527466f+0.045f,  0.6479738f+0.028f,  "D"));
        touchAreas.add(new TouchArea(0.8425598f-0.045f,  0.6450133f-0.028f,  0.8425598f+0.045f,  0.6450133f+0.028f,  "E"));

        // ── Letters F–J — calibrated 2026-04-09 ──────────────────────────────
        touchAreas.add(new TouchArea(0.47125244f-0.045f, 0.7062085f-0.028f,  0.47125244f+0.045f, 0.7062085f+0.028f,  "F"));
        touchAreas.add(new TouchArea(0.5675659f-0.045f,  0.7080197f-0.028f,  0.5675659f+0.045f,  0.7080197f+0.028f,  "G"));
        touchAreas.add(new TouchArea(0.6555176f-0.045f,  0.7086117f-0.028f,  0.6555176f+0.045f,  0.7086117f+0.028f,  "H"));
        touchAreas.add(new TouchArea(0.7527466f-0.045f,  0.70979595f-0.028f, 0.7527466f+0.045f,  0.70979595f+0.028f, "I"));
        touchAreas.add(new TouchArea(0.84625244f-0.045f, 0.7062085f-0.028f,  0.84625244f+0.045f, 0.7062085f+0.028f,  "J"));

        // ── Letters K–O — calibrated 2026-04-09 ──────────────────────────────
        touchAreas.add(new TouchArea(0.4721985f-0.045f,  0.7716181f-0.028f,  0.4721985f+0.045f,  0.7716181f+0.028f,  "K"));
        touchAreas.add(new TouchArea(0.5647888f-0.045f,  0.7680307f-0.028f,  0.5647888f+0.045f,  0.7680307f+0.028f,  "L"));
        touchAreas.add(new TouchArea(0.6592102f-0.045f,  0.76862276f-0.028f, 0.6592102f+0.045f,  0.76862276f+0.028f, "M"));
        touchAreas.add(new TouchArea(0.7527466f-0.045f,  0.7668465f-0.028f,  0.7527466f+0.045f,  0.7668465f+0.028f,  "N"));
        touchAreas.add(new TouchArea(0.84347534f-0.045f, 0.7680307f-0.028f,  0.84347534f+0.045f, 0.7680307f+0.028f,  "O"));

        // ── Letters P–T — calibrated 2026-04-09 ──────────────────────────────
        touchAreas.add(new TouchArea(0.4721985f-0.045f,  0.8268924f-0.028f,  0.4721985f+0.045f,  0.8268924f+0.028f,  "P"));
        touchAreas.add(new TouchArea(0.56292725f-0.045f, 0.8298529f-0.028f,  0.56292725f+0.045f, 0.8298529f+0.028f,  "Q"));
        touchAreas.add(new TouchArea(0.6610718f-0.045f,  0.8298529f-0.028f,  0.6610718f+0.045f,  0.8298529f+0.028f,  "R"));
        touchAreas.add(new TouchArea(0.7499695f-0.045f,  0.82866865f-0.028f, 0.7499695f+0.045f,  0.82866865f+0.028f, "S"));
        touchAreas.add(new TouchArea(0.84347534f-0.045f, 0.82866865f-0.028f, 0.84347534f+0.045f, 0.82866865f+0.028f, "T"));

        // ── Letters U–Y — calibrated 2026-04-09 ──────────────────────────────
        touchAreas.add(new TouchArea(0.4721985f-0.045f,  0.88812244f-0.028f, 0.4721985f+0.045f,  0.88812244f+0.028f, "U"));
        touchAreas.add(new TouchArea(0.5647888f-0.045f,  0.88812244f-0.028f, 0.5647888f+0.045f,  0.88812244f+0.028f, "V"));
        touchAreas.add(new TouchArea(0.6555176f-0.045f,  0.8869382f-0.028f,  0.6555176f+0.045f,  0.8869382f+0.028f,  "W"));
        touchAreas.add(new TouchArea(0.74902344f-0.045f, 0.8887145f-0.028f,  0.74902344f+0.045f, 0.8887145f+0.028f,  "X"));
        touchAreas.add(new TouchArea(0.8425598f-0.045f,  0.8887145f-0.028f,  0.8425598f+0.045f,  0.8887145f+0.028f,  "Y"));

        // ── Bottom row — calibrated 2026-04-09 ────────────────────────────────
        touchAreas.add(new TouchArea(0.1583252f-0.045f,  0.94994456f-0.028f, 0.1583252f+0.045f,  0.94994456f+0.028f, "DOT"));
        touchAreas.add(new TouchArea(0.25738525f-0.045f, 0.94694924f-0.028f, 0.25738525f+0.045f, 0.94694924f+0.028f, "0"));
        touchAreas.add(new TouchArea(0.35461426f-0.045f, 0.94694924f-0.028f, 0.35461426f+0.045f, 0.94694924f+0.028f, "PLUSMINUS"));
        touchAreas.add(new TouchArea(0.47125244f-0.045f, 0.94994456f-0.028f, 0.47125244f+0.045f, 0.94994456f+0.028f, "Z"));
        touchAreas.add(new TouchArea(0.5620117f-0.045f,  0.94694924f-0.028f, 0.5620117f+0.045f,  0.94694924f+0.028f, "DIV"));
        touchAreas.add(new TouchArea(0.6555176f-0.045f,  0.94517297f-0.028f, 0.6555176f+0.045f,  0.94517297f+0.028f, "SP"));
        touchAreas.add(new TouchArea(0.7499695f-0.045f,  0.94813347f-0.028f, 0.7499695f+0.045f,  0.94813347f+0.028f, "OVFY"));
        touchAreas.add(new TouchArea(0.84347534f-0.045f, 0.94694924f-0.028f, 0.84347534f+0.045f, 0.94694924f+0.028f, "CLR"));
    }

    // ─── Size changed ─────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setupLayout();

        if (skin != null) {
            float imgW = skin.getWidth(), imgH = skin.getHeight();
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
            imgOffsetX + screenRect.left   * imgScaleW,
            imgOffsetY + screenRect.top    * imgScaleH,
            imgOffsetX + screenRect.right  * imgScaleW,
            imgOffsetY + screenRect.bottom * imgScaleH
        );

        cellW = actualScreen.width()  / MCDU_COLS;
        cellH = actualScreen.height() / MCDU_ROWS;

        textPaint.setTextSize(cellH * 0.75f);
        smallPaint.setTextSize(cellH * 0.55f);
        textYOffset  = cellH * 0.80f;
        smallYOffset = cellH * 0.75f;

        // Each LED box: narrow rectangle
        ledBoxW = h * 0.038f;
        ledBoxH = h * 0.014f;
        ledLabelPaint.setTextSize(h * 0.010f);
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (skin != null) {
            skinRectF.set(imgOffsetX, imgOffsetY, imgOffsetX + imgScaleW, imgOffsetY + imgScaleH);
            canvas.drawBitmap(skin, null, skinRectF, skinPaint);
        } else {
            canvas.drawColor(Color.rgb(0x2d, 0x37, 0x48));
        }

        screenPaint.setColor(Color.BLACK);
        canvas.drawRect(actualScreen, screenPaint);

        for (int r = 0; r < MCDU_ROWS; r++) {
            float rowY = actualScreen.top + r * cellH;
            for (int c = 0; c < MCDU_COLS; c++) {
                if (symbols[r][c] == ' ') continue;

                boolean isSmall = small[r][c];
                Paint p = isSmall ? smallPaint : textPaint;
                p.setColor(colors[r][c]);

                float charX = actualScreen.left + c * cellW + cellW * 0.5f;
                float charY = rowY + (isSmall ? smallYOffset : textYOffset);

                canvas.drawText(symbols[r], c, 1, charX, charY, p);
            }
        }

        drawLedIndicators(canvas);

        if (pressedArea != null) {
            float px = imgOffsetX + pressedArea.rect.centerX() * imgScaleW;
            float py = imgOffsetY + pressedArea.rect.centerY() * imgScaleH;
            float radius = Math.min(pressedArea.rect.width() * imgScaleW,
                                    pressedArea.rect.height() * imgScaleH) / 2f * 0.7f;
            canvas.drawCircle(px, py, radius, highlightPaint);
        }
    }


    private final RectF ledRect = new RectF();

    private void drawLedIndicators(Canvas canvas) {
        for (int i = 0; i < 5; i++) {
            float px = imgOffsetX + ledX5[i] * imgScaleW;
            float py = imgOffsetY + ledY5[i] * imgScaleH;

            ledRect.set(px - ledBoxW/2, py - ledBoxH/2, px + ledBoxW/2, py + ledBoxH/2);
            ledBoxPaint.setColor(ledOn[i] ? LED_COLOR_ON[i] : LED_COLOR_OFF[i]);
            canvas.drawRect(ledRect, ledBoxPaint);

            // Label below the LED box
            ledLabelPaint.setColor(ledOn[i] ? LED_COLOR_ON[i] : Color.rgb(80, 80, 80));
            canvas.drawText(LED_NAMES[i], px, ledRect.bottom + ledBoxH * 1.2f, ledLabelPaint);
        }
    }

    // ─── Bitmap helper ────────────────────────────────────────────────────────

    private int getMaxBitmapSize() {
        try {
            int[] maxSize = new int[1];
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
            return maxSize[0] > 0 ? maxSize[0] : 2048;
        } catch (Exception e) { return 2048; }
    }

    private Bitmap decodeSampledBitmap(Context context, int resId, int reqSize) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            opts.inScaled = false;
            BitmapFactory.decodeResource(context.getResources(), resId, opts);
            int sampleSize = 1;
            if (opts.outHeight > reqSize || opts.outWidth > reqSize) {
                while ((opts.outHeight / 2 / sampleSize) >= reqSize &&
                       (opts.outWidth  / 2 / sampleSize) >= reqSize) sampleSize *= 2;
            }
            opts.inJustDecodeBounds   = false;
            opts.inSampleSize         = sampleSize;
            opts.inPreferredConfig    = Bitmap.Config.RGB_565;
            opts.inScaled             = false;
            return BitmapFactory.decodeResource(context.getResources(), resId, opts);
        } catch (OutOfMemoryError e) { System.gc(); return null; }
        catch (Exception e) { return null; }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public void setKeyPressListener(KeyPressListener l) { keyListener = l; }

    public void updateScreen(char[][] syms, int[][] cols, boolean[][] smallFlags) {
        for (int r = 0; r < MCDU_ROWS; r++) {
            System.arraycopy(syms[r],  0, symbols[r], 0, MCDU_COLS);
            System.arraycopy(cols[r],  0, colors[r],  0, MCDU_COLS);
            for (int c = 0; c < MCDU_COLS; c++) small[r][c] = smallFlags[r][c];
        }
        postInvalidate();
    }

    /** Set individual LED state. index: 0=FM1, 1=IND, 2=RDY, 3=--, 4=FM2 */
    public void setLedState(int index, boolean on) {
        if (index >= 0 && index < 5) { ledOn[index] = on; postInvalidate(); }
    }

    /** Convenience: set RDY (index 2) — used for SimBridge connected state. */
    public void setLedState(boolean on) { setLedState(2, on); }

    public boolean getLedState(int index) {
        return (index >= 0 && index < 5) && ledOn[index];
    }

    public void setSoundEnabled(boolean e)  { soundEnabled = e; }
    public boolean isSoundEnabled()         { return soundEnabled; }
    public void setHapticEnabled(boolean e) { hapticEnabled = e; }
    public boolean isHapticEnabled()        { return hapticEnabled; }

    /**
     * Convert a raw touch pixel coordinate (from event.getX/Y on this View)
     * to skin-image-normalized space (0..1 relative to the drawn skin area).
     * This is the coordinate space used by all touch areas in setupLayout().
     * Use these in CalibrateActivityA320 instead of dividing by view width/height.
     */
    public float toSkinNormX(float rawX) {
        if (imgScaleW <= 0) return rawX / Math.max(1f, getWidth());
        return (rawX - imgOffsetX) / imgScaleW;
    }

    public float toSkinNormY(float rawY) {
        if (imgScaleH <= 0) return rawY / Math.max(1f, getHeight());
        return (rawY - imgOffsetY) / imgScaleH;
    }

    public void recycleSkin() {
        if (skin != null && !skin.isRecycled()) skin.recycle();
        skin = null;
        if (soundPlayer != null) soundPlayer.release();
    }
}
