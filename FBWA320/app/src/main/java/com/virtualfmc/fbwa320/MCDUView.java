package com.virtualfmc.fbwa320;

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

public class MCDUView extends View {

    private static final String TAG       = "MCDUView";
    private static final int    MCDU_ROWS = 14;
    private static final int    MCDU_COLS = 24;

    private static final boolean DEBUG_MODE = false;

    private SoundPlayer soundPlayer;
    private boolean soundEnabled  = true;
    private boolean hapticEnabled = true;

    private TouchArea pressedArea = null;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Cockpit-feel press animation — same recipe as iFly737MAX + FMC777_OWN.
    // Subtle dark overlay fades in then decelerates out; alpha 128 = ~50% black.
    private static final int  HIGHLIGHT_MAX_ALPHA = 128;
    private static final long PRESS_FADE_IN_MS    = 100;
    private static final long PRESS_FADE_OUT_MS   = 240;
    private int currentHighlightAlpha = 0;
    private AnimatorSet pressAnimator = null;

    private long lastTouchTime = 0;
    private static final long TOUCH_DEBOUNCE_MS = 50;

    // ─── 10 hardcoded keepers — extracted from CDU_Images/FBWA320/Calib/FBWA320.png
    // via tools/extract_calib_markers.py FBWA320. These are positions that
    // can NOT be auto-detected from the bezel skin (top-bar UI, 5 top-strip
    // LED ovals, 2 left-side stacked annunciators, 1 right-side annunciator).
    // Format: { x0, y0, x1, y1 } normalised 0..1.
    private static final float[] SETTINGS_BTN_RECT      = { 0.0811f, 0.0104f, 0.1370f, 0.0459f };
    private static final float[] CLOSE_BTN_RECT         = { 0.8702f, 0.0104f, 0.9261f, 0.0459f };
    private static final float[] TOP_LED_FM1_RECT       = { 0.2398f, 0.0069f, 0.3263f, 0.0374f };
    private static final float[] TOP_LED_IND_RECT       = { 0.3480f, 0.0069f, 0.4345f, 0.0374f };
    private static final float[] TOP_LED_RDY_RECT       = { 0.4579f, 0.0073f, 0.5445f, 0.0378f };
    private static final float[] TOP_LED_FM_TOP_RECT    = { 0.5661f, 0.0073f, 0.6526f, 0.0378f };
    private static final float[] TOP_LED_FM2_RECT       = { 0.6755f, 0.0073f, 0.7620f, 0.0378f };
    private static final float[] LEFT_STRIP_TOP_RECT    = { 0.0499f, 0.6867f, 0.0895f, 0.7681f };  // FAIL
    private static final float[] LEFT_STRIP_BOTTOM_RECT = { 0.0505f, 0.7712f, 0.0889f, 0.8275f };  // FM
    private static final float[] RIGHT_STRIP_RECT       = { 0.9123f, 0.6852f, 0.9513f, 0.8295f };  // MCDU MENU

    // MCDU character-grid screen rect (fixed by skin geometry).
    private static final float SCREEN_L = 0.15924072f;
    private static final float SCREEN_T = 0.07854814f;
    private static final float SCREEN_R = 0.8425598f;
    private static final float SCREEN_B = 0.4440826f;

    // ─── 8 visual LED slots driven by 7 server states ─────────────────────────
    // Slot 7 (FM_TOP) mirrors slot 2 (FM) — both light when blank/FM is true.
    // setLedState(int idx, boolean on) API contract preserved: idx 0..6 are the
    // server-driven states; the visual layer reads them through LED_STATE_SOURCE.
    private static final String[] LED_NAMES = {"FM1", "FM2", "FM", "IND", "RDY", "FAIL", "MCDU MENU", "FM"};
    private static final int[] LED_STATE_SOURCE = {0, 1, 2, 3, 4, 5, 6, 2};

    // Per-slot keeper rect (which of the 10 keepers each visual slot lives in).
    private static final float[][] LED_RECTS = {
        TOP_LED_FM1_RECT,        // 0 — FM1     (top strip, horizontal)
        TOP_LED_FM2_RECT,        // 1 — FM2     (top strip, horizontal)
        LEFT_STRIP_BOTTOM_RECT,  // 2 — FM      (left strip, vertical text)
        TOP_LED_IND_RECT,        // 3 — IND     (top strip, horizontal)
        TOP_LED_RDY_RECT,        // 4 — RDY     (top strip, horizontal)
        LEFT_STRIP_TOP_RECT,     // 5 — FAIL    (left strip, vertical text)
        RIGHT_STRIP_RECT,        // 6 — MCDU MENU (right strip, vertical text)
        TOP_LED_FM_TOP_RECT,     // 7 — FM_TOP  (top strip, horizontal — mirrors FM)
    };
    // true = stacked vertical text; false = horizontal text centred in rect.
    private static final boolean[] LED_TEXT_VERTICAL = {false, false, true, false, false, true, true, false};

    private static final int[] LED_COLOR_OFF = {
        Color.rgb(50, 25, 0),   // FM1       — dim amber
        Color.rgb(50, 25, 0),   // FM2       — dim amber
        Color.rgb(60, 60, 60),  // FM        — dim white (real A320 FM annun is white)
        Color.rgb(50, 25, 0),   // IND       — dim amber
        Color.rgb(0,  30, 0),   // RDY       — dim green
        Color.rgb(50,  0, 0),   // FAIL      — dim red
        Color.rgb(50, 25, 0),   // MCDU_MENU — dim amber
        Color.rgb(60, 60, 60),  // FM_TOP    — dim white (mirrors FM)
    };
    private static final int[] LED_COLOR_ON = {
        Color.rgb(255, 160, 0), // FM1       — amber
        Color.rgb(255, 160, 0), // FM2       — amber
        Color.WHITE,            // FM        — white
        Color.rgb(255, 160, 0), // IND       — amber
        Color.GREEN,            // RDY       — green
        Color.RED,              // FAIL      — red
        Color.rgb(255, 160, 0), // MCDU_MENU — amber
        Color.WHITE,            // FM_TOP    — white (mirrors FM)
    };

    // DEBUG: force all 8 visual LEDs ON regardless of server data — set true
    // to verify positioning + colours after future skin/keeper changes.
    private static final boolean LED_TEST_ALL_ON = false;

    private final boolean[] ledOn = new boolean[7];  // 7 server-driven states

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

    // 70 bezel-button hints — same table as tools/scan_buttons_preview.py
    // APPS["FBWA320"]["hints"]. LSK uniform cx (0.0566 left / 0.9473 right) so
    // bboxes line up vertically; 4px right-trim post-process applied in
    // scanButtonsFromBezel() so press shadow doesn't overflow the LED bar
    // onto the bezel inset. DOT/0/PLUSMINUS are CIRCLE (Airbus pad-style);
    // BRT + DIM dropped (verified decorative on both Fenix + FBW).
    private static final Hint[] BUTTON_HINTS = {
        // LSK Left — uniform cx
        new Hint("LSK1L", 0.0566f, 0.14510710f, 0.025f, 0.025f, RR),
        new Hint("LSK2L", 0.0566f, 0.19505244f, 0.025f, 0.025f, RR),
        new Hint("LSK3L", 0.0566f, 0.24437086f, 0.025f, 0.025f, RR),
        new Hint("LSK4L", 0.0566f, 0.29550040f, 0.025f, 0.025f, RR),
        new Hint("LSK5L", 0.0566f, 0.34541090f, 0.025f, 0.025f, RR),
        new Hint("LSK6L", 0.0566f, 0.39594835f, 0.025f, 0.025f, RR),
        // LSK Right — uniform cx
        new Hint("LSK1R", 0.9473f, 0.14510710f, 0.025f, 0.025f, RR),
        new Hint("LSK2R", 0.9473f, 0.19505244f, 0.025f, 0.025f, RR),
        new Hint("LSK3R", 0.9473f, 0.24437086f, 0.025f, 0.025f, RR),
        new Hint("LSK4R", 0.9473f, 0.29550040f, 0.025f, 0.025f, RR),
        new Hint("LSK5R", 0.9473f, 0.34659510f, 0.025f, 0.025f, RR),
        new Hint("LSK6R", 0.9473f, 0.39831677f, 0.025f, 0.025f, RR),
        // Function row 1: DIR, PROG, PERF, INIT, DATA
        new Hint("DIR",   0.16479492f, 0.51718944f, 0.055f, 0.028f, RR),
        new Hint("PROG",  0.28329468f, 0.51718944f, 0.055f, 0.028f, RR),
        new Hint("PERF",  0.39718628f, 0.51718944f, 0.055f, 0.028f, RR),
        new Hint("INIT",  0.51385500f, 0.51718944f, 0.055f, 0.028f, RR),
        new Hint("DATA",  0.63052370f, 0.51718944f, 0.055f, 0.028f, RR),
        // Function row 2: FPLN, RADNAV, FUELPRED, SECFPLN, ATCCOMM, MCDU_MENU
        new Hint("FPLN",      0.16387940f, 0.5706874f, 0.055f, 0.028f, RR),
        new Hint("RADNAV",    0.28051758f, 0.5706874f, 0.055f, 0.028f, RR),
        new Hint("FUELPRED",  0.39535522f, 0.5706874f, 0.055f, 0.028f, RR),
        new Hint("SECFPLN",   0.51940920f, 0.5706874f, 0.055f, 0.028f, RR),
        new Hint("ATCCOMM",   0.63421630f, 0.5706874f, 0.055f, 0.028f, RR),
        new Hint("MCDU_MENU", 0.74996950f, 0.5706874f, 0.055f, 0.028f, RR),
        // AIRPORT
        new Hint("AIRPORT", 0.15924072f, 0.6206327f, 0.055f, 0.028f, RR),
        // Slew arrows
        new Hint("SLEW_LEFT",  0.15924072f, 0.67472273f, 0.045f, 0.030f, RR),
        new Hint("SLEW_UP",    0.27774048f, 0.67472273f, 0.045f, 0.030f, RR),
        new Hint("SLEW_RIGHT", 0.15924072f, 0.72585230f, 0.045f, 0.030f, RR),
        new Hint("SLEW_DOWN",  0.27682495f, 0.72585230f, 0.045f, 0.030f, RR),
        // Letters A-E
        new Hint("A", 0.47219850f, 0.65034217f, 0.045f, 0.028f, RR),
        new Hint("B", 0.56478880f, 0.64678960f, 0.045f, 0.028f, RR),
        new Hint("C", 0.65551760f, 0.64797380f, 0.045f, 0.028f, RR),
        new Hint("D", 0.75274660f, 0.64797380f, 0.045f, 0.028f, RR),
        new Hint("E", 0.84255980f, 0.64501330f, 0.045f, 0.028f, RR),
        // Letters F-J
        new Hint("F", 0.47125244f, 0.70620850f, 0.045f, 0.028f, RR),
        new Hint("G", 0.56756590f, 0.70801970f, 0.045f, 0.028f, RR),
        new Hint("H", 0.65551760f, 0.70861170f, 0.045f, 0.028f, RR),
        new Hint("I", 0.75274660f, 0.70979595f, 0.045f, 0.028f, RR),
        new Hint("J", 0.84625244f, 0.70620850f, 0.045f, 0.028f, RR),
        // Letters K-O
        new Hint("K", 0.47219850f, 0.77161810f, 0.045f, 0.028f, RR),
        new Hint("L", 0.56478880f, 0.76803070f, 0.045f, 0.028f, RR),
        new Hint("M", 0.65921020f, 0.76862276f, 0.045f, 0.028f, RR),
        new Hint("N", 0.75274660f, 0.76684650f, 0.045f, 0.028f, RR),
        new Hint("O", 0.84347534f, 0.76803070f, 0.045f, 0.028f, RR),
        // Numbers 1-3
        new Hint("1", 0.15924072f, 0.79063493f, 0.045f, 0.028f, CR),
        new Hint("2", 0.25460815f, 0.79063493f, 0.045f, 0.028f, CR),
        new Hint("3", 0.35089110f, 0.79063493f, 0.045f, 0.028f, CR),
        // Letters P-T
        new Hint("P", 0.47219850f, 0.82689240f, 0.045f, 0.028f, RR),
        new Hint("Q", 0.56292725f, 0.82985290f, 0.045f, 0.028f, RR),
        new Hint("R", 0.66107180f, 0.82985290f, 0.045f, 0.028f, RR),
        new Hint("S", 0.74996950f, 0.82866865f, 0.045f, 0.028f, RR),
        new Hint("T", 0.84347534f, 0.82866865f, 0.045f, 0.028f, RR),
        // Numbers 4-6
        new Hint("4", 0.15924072f, 0.84235660f, 0.045f, 0.028f, CR),
        new Hint("5", 0.25552368f, 0.84235660f, 0.045f, 0.028f, CR),
        new Hint("6", 0.35183716f, 0.84235660f, 0.045f, 0.028f, CR),
        // Letters U-Y
        new Hint("U", 0.47219850f, 0.88812244f, 0.045f, 0.028f, RR),
        new Hint("V", 0.56478880f, 0.88812244f, 0.045f, 0.028f, RR),
        new Hint("W", 0.65551760f, 0.88693820f, 0.045f, 0.028f, RR),
        new Hint("X", 0.74902344f, 0.88871450f, 0.045f, 0.028f, RR),
        new Hint("Y", 0.84255980f, 0.88871450f, 0.045f, 0.028f, RR),
        // Numbers 7-9
        new Hint("7", 0.15924072f, 0.89822290f, 0.045f, 0.028f, CR),
        new Hint("8", 0.25738525f, 0.89703876f, 0.045f, 0.028f, CR),
        new Hint("9", 0.35461426f, 0.89345133f, 0.045f, 0.028f, CR),
        // Bottom row: DOT, 0, PLUSMINUS round; Z, DIV, SP, OVFY, CLR rectangular.
        new Hint("DOT",       0.15832520f, 0.94994456f, 0.045f, 0.028f, CR),
        new Hint("0",         0.25738525f, 0.94694924f, 0.045f, 0.028f, CR),
        new Hint("PLUSMINUS", 0.35461426f, 0.94694924f, 0.045f, 0.028f, CR),
        new Hint("Z",         0.47125244f, 0.94994456f, 0.045f, 0.028f, RR),
        new Hint("DIV",       0.56201170f, 0.94694924f, 0.045f, 0.028f, RR),
        new Hint("SP",        0.65551760f, 0.94517297f, 0.045f, 0.028f, RR),
        new Hint("OVFY",      0.74996950f, 0.94813347f, 0.045f, 0.028f, RR),
        new Hint("CLR",       0.84347534f, 0.94694924f, 0.045f, 0.028f, RR),
    };

    private final char[][]    symbols = new char[MCDU_ROWS][MCDU_COLS];
    private final int[][]     colors  = new int[MCDU_ROWS][MCDU_COLS];
    private final boolean[][] small   = new boolean[MCDU_ROWS][MCDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint     = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Connection status overlay — shown on the MCDU screen area when not connected.
    private String connectionStatus = null;
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF skinRectF        = new RectF();
    private final RectF screenRect       = new RectF();
    private final RectF actualScreen     = new RectF();
    // Reusable RectF for press overlay — drawRoundRect(RectF, float, float, Paint)
    // is API 1; the 6-float overload is API 21+ and crashes on Android 4.4.
    private final RectF pressOverlayRect = new RectF();

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

    public MCDUView(Context context) {
        super(context);
        init(context);
        setupLayout();
    }

    public MCDUView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
        setupLayout();
    }

    private void init(Context context) {
        touchAreas = new ArrayList<>();

        int maxBitmapSize = getMaxBitmapSize();
        int safeSize      = Math.min(1024, maxBitmapSize / 2);

        soundPlayer = SoundPlayer.getInstance();
        soundPlayer.initialize(context);

        try {
            skin = decodeSampledBitmap(context, R.drawable.mcdu_skin_a320, safeSize);
            if (skin != null) {
                logD("A320 skin loaded: " + skin.getWidth() + "x" + skin.getHeight());
                if (skin.getWidth() > maxBitmapSize || skin.getHeight() > maxBitmapSize) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to load A320 skin", e);
        }

        for (int r = 0; r < MCDU_ROWS; r++) {
            for (int c = 0; c < MCDU_COLS; c++) {
                symbols[r][c] = ' ';
                colors[r][c]  = Color.WHITE;
                small[r][c]   = false;
            }
        }

        try {
            Typeface b612 = Typeface.createFromAsset(context.getAssets(), "fonts/B612Mono-Bold.ttf");
            textPaint.setTypeface(b612);
            smallPaint.setTypeface(b612);
        } catch (Exception e) {
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            smallPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            android.util.Log.w(TAG, "B612Mono not found, using fallback", e);
        }

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        smallPaint.setColor(Color.WHITE);
        smallPaint.setTextAlign(Paint.Align.CENTER);

        highlightPaint.setColor(Color.BLACK);
        highlightPaint.setStyle(Paint.Style.FILL);

        boxPaint.setColor(Color.argb(200, 40, 40, 40));
        boxPaint.setStyle(Paint.Style.FILL);

        ledLabelPaint.setTextAlign(Paint.Align.CENTER);
        ledLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        statusPaint.setColor(Color.rgb(255, 160, 0));  // amber
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

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
                        if      ("SETTINGS_BTN".equals(found.key)) keyListener.onUIAction("SETTINGS_BTN");
                        else if ("CLOSE_BTN".equals(found.key))    keyListener.onUIAction("CLOSE_BTN");
                        else                                       keyListener.onKeyPress(found.key);
                    }

                    startPressAnimation();
                }
                return true;
            }
        });
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private int getMaxBitmapSize() {
        try {
            int[] maxSize = new int[1];
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
            return maxSize[0] > 0 ? maxSize[0] : 2048;
        } catch (Exception e) { return 2048; }
    }

    private void logD(String message) {
        if (DEBUG_MODE) android.util.Log.d(TAG, message);
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
            opts.inJustDecodeBounds = false;
            opts.inSampleSize       = sampleSize;
            opts.inPreferredConfig  = Bitmap.Config.RGB_565;
            opts.inScaled           = false;
            return BitmapFactory.decodeResource(context.getResources(), resId, opts);
        } catch (OutOfMemoryError e) { System.gc(); return null; }
        catch (Exception e) { return null; }
    }

    public void setKeyPressListener(KeyPressListener l) { keyListener = l; }

    public void setSoundEnabled(boolean e)  { soundEnabled = e; }
    public boolean isSoundEnabled()         { return soundEnabled; }
    public void setHapticEnabled(boolean e) { hapticEnabled = e; }
    public boolean isHapticEnabled()        { return hapticEnabled; }

    /** Set individual LED state. Index map (matches FenixA320):
     *  0=FM1, 1=FM2, 2=FM (SimBridge "blank"), 3=IND, 4=RDY,
     *  5=FAIL (decorative — never set on FBW),
     *  6=MCDU_MENU (decorative — never set on FBW). */
    public void setLedState(int index, boolean on) {
        if (index >= 0 && index < 7) { ledOn[index] = on; postInvalidate(); }
    }

    /** Convenience: set RDY (index 4) — used for SimBridge connected state. */
    public void setLedState(boolean on) { setLedState(4, on); }

    public boolean getLedState(int index) {
        return (index >= 0 && index < 7) && ledOn[index];
    }

    /** Show a connection status message on the MCDU screen area.
     *  Pass null or "" to clear the overlay and show the normal MCDU character grid.
     *  Supports "\n" for multi-line messages. */
    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
        postInvalidate();
    }

    public void updateScreen(char[][] syms, int[][] cols, boolean[][] smallFlags) {
        for (int r = 0; r < MCDU_ROWS; r++) {
            System.arraycopy(syms[r], 0, symbols[r], 0, MCDU_COLS);
            System.arraycopy(cols[r], 0, colors[r],  0, MCDU_COLS);
            for (int c = 0; c < MCDU_COLS; c++) small[r][c] = smallFlags[r][c];
        }
        postInvalidate();
    }

    /** Cockpit-feel press animation: fade in over PRESS_FADE_IN_MS, then fade
     *  out over PRESS_FADE_OUT_MS with deceleration. AnimatorSet.cancel() fires
     *  onAnimationEnd synchronously, so the new animator is assigned to the
     *  pressAnimator slot BEFORE cancelling the old one — the dying old listener
     *  checks `if (pressAnimator != anim) return` and leaves pressedArea + alpha
     *  alone, so the fresh animation isn't clobbered. fadeIn starts from current
     *  alpha so re-press during fade-out smoothly tops up instead of dipping
     *  through 0. */
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
    }

    private void setupLayout() {
        if (touchAreas == null) touchAreas = new ArrayList<>();
        else touchAreas.clear();

        // 2 hardcoded UI buttons (top bar — not on the bezel, can't auto-detect)
        touchAreas.add(new TouchArea(SETTINGS_BTN_RECT[0], SETTINGS_BTN_RECT[1],
                SETTINGS_BTN_RECT[2], SETTINGS_BTN_RECT[3], "SETTINGS_BTN", Shape.ROUNDED_RECT));
        touchAreas.add(new TouchArea(CLOSE_BTN_RECT[0], CLOSE_BTN_RECT[1],
                CLOSE_BTN_RECT[2], CLOSE_BTN_RECT[3], "CLOSE_BTN", Shape.ROUNDED_RECT));

        // MCDU character-grid screen rect — fixed by skin geometry.
        screenRect.set(SCREEN_L, SCREEN_T, SCREEN_R, SCREEN_B);

        // 70 bezel buttons — pixel-detected from the skin bitmap.
        scanButtonsFromBezel();
    }

    /** Pixel-detect each bezel button's actual face from the skin bitmap.
     *  Mirrors tools/scan_buttons_preview.py: search rect, grey&lt;60 mask,
     *  2x2 binary opening, biggest connected component bbox. After detection,
     *  applies an LSK 4 px right-trim post-process so the press shadow doesn't
     *  overflow the LSK bar onto the bezel inset to its right. */
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
            android.util.Log.e(TAG, "scanButtonsFromBezel: skin getWidth/Height threw", e);
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
        // LSK 4 px right-trim — same post-process as tools/scan_buttons_preview.py
        // for FBWA320. Detector finds a few pixels of bezel inset past the visible
        // LSK bar edge; trim so the press shadow sits flush on the bar face.
        float trimNorm = 4f / W;
        for (java.util.Map.Entry<String, RectF> e : detected.entrySet()) {
            if (e.getKey().startsWith("LSK")) {
                e.getValue().right -= trimNorm;
            }
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
            canvas.drawColor(Color.rgb(0x2d, 0x37, 0x48));
        }

        screenPaint.setColor(Color.BLACK);
        canvas.drawRect(actualScreen, screenPaint);

        if (connectionStatus != null && !connectionStatus.isEmpty()) {
            float screenH = actualScreen.height();
            // Auto-fit: long status messages (e.g. aircraft mismatch /
            // "Open matching app or load matching aircraft") would otherwise
            // run off both edges of the MCDU rect. Measure widest line at the
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
            drawLedIndicators(canvas);
            drawPressOverlay(canvas);
            return;
        }

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
        drawPressOverlay(canvas);
    }

    /** Press overlay sized to the auto-detected button face. Shape comes from
     *  TouchArea.shape (set by the bezel scan or hint metadata). Uses the
     *  RectF overload of drawRoundRect (API 1) — the float-args overload is
     *  API 21+ only and crashes on Android 4.4 / dalvikvm. */
    private void drawPressOverlay(Canvas canvas) {
        if (pressedArea == null || currentHighlightAlpha <= 0) return;
        RectF r = pressedArea.rect;
        float l  = imgOffsetX + r.left   * imgScaleW;
        float t  = imgOffsetY + r.top    * imgScaleH;
        float rt = imgOffsetX + r.right  * imgScaleW;
        float b  = imgOffsetY + r.bottom * imgScaleH;
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

    /** Draw 8 visual LED slots — top-strip 5 horizontal labels, left-strip 2
     *  stacked vertical (FAIL upper, FM lower), right-strip 1 stacked vertical
     *  (MCDU MENU). Each slot is centred in its keeper rect. Inactive slots
     *  render in dim colour so labels stay readable on the bezel. */
    private void drawLedIndicators(Canvas canvas) {
        for (int i = 0; i < 8; i++) {
            float[] rect = LED_RECTS[i];
            float px = imgOffsetX + ((rect[0] + rect[2]) * 0.5f) * imgScaleW;
            float py = imgOffsetY + ((rect[1] + rect[3]) * 0.5f) * imgScaleH;
            boolean lit = LED_TEST_ALL_ON || ledOn[LED_STATE_SOURCE[i]];
            int textCol = lit ? LED_COLOR_ON[i] : LED_COLOR_OFF[i];

            // Text size scales with the drawn skin (imgScaleH) — keeps
            // annunciators proportional on letterboxed tablets.
            float charSize = imgScaleH * 0.0144f * 1.152f;
            if (charSize < 1f) continue;

            ledLabelPaint.setColor(textCol);
            ledLabelPaint.setTextSize(charSize);
            ledLabelPaint.setFakeBoldText(true);
            if (lit) {
                ledLabelPaint.setShadowLayer(charSize * 0.35f, 0f, 0f, LED_COLOR_ON[i]);
            } else {
                ledLabelPaint.clearShadowLayer();
            }

            Paint.FontMetrics fm = ledLabelPaint.getFontMetrics();
            float baselineAdjust = (fm.ascent + fm.descent) / 2f;

            if (LED_TEXT_VERTICAL[i]) {
                String name = LED_NAMES[i];
                float lineH = charSize;
                float topY = py - ((name.length() - 1) * lineH) / 2f;
                for (int k = 0; k < name.length(); k++) {
                    char ch = name.charAt(k);
                    if (ch == ' ') continue;
                    float charY = topY + k * lineH - baselineAdjust;
                    canvas.drawText(String.valueOf(ch), px, charY, ledLabelPaint);
                }
            } else {
                float charY = py - baselineAdjust;
                canvas.drawText(LED_NAMES[i], px, charY, ledLabelPaint);
            }
            ledLabelPaint.setFakeBoldText(false);
        }
    }

    /**
     * Convert a raw touch pixel coordinate (from event.getX/Y on this View)
     * to skin-image-normalised space (0..1 relative to the drawn skin area).
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
