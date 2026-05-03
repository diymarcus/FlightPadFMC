package com.virtualfmc.fenixa320;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A320 MCDU Touch Calibration Activity — with visible tap markers.
 *
 * Ported from CalibrateActivity737: uses a custom CalibView that
 * draws the MCDU skin directly AND overlays a red dot + yellow label at
 * every tapped position, so you can verify each tap visually before saving.
 *
 * Modes (pass via Intent extra EXTRA_MODE):
 *   MODE_FULL  — all 83 points (default)
 *   MODE_LEDS  — 7 LED indicators only (SharedPreferences keys for everything
 *                else are left untouched, so a previous full calibration stays)
 */
public class CalibrateActivityA320 extends Activity {

    private static final String TAG       = "CalibrateA320";
    private static final String PREFS_KEY = "CalibrationDataA320";

    public  static final String EXTRA_MODE = "mode";
    public  static final String MODE_FULL  = "full";
    public  static final String MODE_LEDS  = "leds";

    // ─── Calibration sequences ───────────────────────────────────────────────

    private static final String[] FULL_KEYS = {
        // UI overlay icons
        "SETTINGS_BTN", "CLOSE_BTN",
        // Screen display corners (defines the MCDU screen area)
        "SCREEN_TL", "SCREEN_BR",
        // 8 LED slots — order MUST match MCDUView.LED_NAMES indexing
        // (0=FM1, 1=FM2, 2=FM-text, 3=IND, 4=RDY, 5=FAIL-text, 6=MCDU_MENU-text, 7=FM-top-rect)
        "LED_FM1", "LED_FM2", "LED_FM", "LED_IND", "LED_RDY", "LED_FAIL", "LED_MCDU_MENU", "LED_FM_TOP",
        // LSK Left
        "LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L",
        // LSK Right
        "LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R",
        // Function Row 1
        "DIR","PROG","PERF","INIT","DATA",
        // Brightness
        "BRT","DIM",
        // Function Row 2
        "FPLN","RADNAV","FUELPRED","SECFPLN","ATCCOMM","MCDU_MENU",
        // Airport
        "AIRPORT",
        // Arrows
        "SLEW_LEFT","SLEW_UP","SLEW_RIGHT","SLEW_DOWN",
        // Numbers
        "1","2","3","4","5","6","7","8","9","0","DOT","PLUSMINUS",
        // Letters A–Z
        "A","B","C","D","E","F","G","H","I","J",
        "K","L","M","N","O","P","Q","R","S","T",
        "U","V","W","X","Y","Z",
        // Special
        "DIV","SP","OVFY","CLR"
    };

    private static final String[] FULL_LABELS = {
        "Settings icon (top right)",
        "Close icon (top left)",
        "Screen TOP-LEFT corner",
        "Screen BOTTOM-RIGHT corner",
        "LED FM1 (top)","LED FM2 (top)","FM text (left)",
        "LED IND (top)","LED RDY (top)",
        "FAIL text (left)","MCDU MENU text (right)","LED FM (top, between RDY+FM2)",
        "LSK 1L","LSK 2L","LSK 3L","LSK 4L","LSK 5L","LSK 6L",
        "LSK 1R","LSK 2R","LSK 3R","LSK 4R","LSK 5R","LSK 6R",
        "DIR","PROG","PERF","INIT","DATA",
        "BRT","DIM",
        "F-PLN","RAD NAV","FUEL PRED","SEC F-PLN","ATC COMM","MCDU MENU",
        "AIRPORT",
        "Arrow LEFT","Arrow UP","Arrow RIGHT","Arrow DOWN",
        "1","2","3","4","5","6","7","8","9","0","DOT","PLUSMINUS",
        "A","B","C","D","E","F","G","H","I","J",
        "K","L","M","N","O","P","Q","R","S","T",
        "U","V","W","X","Y","Z",
        "DIV","SP","OVFY","CLR"
    };

    private static final String[] LED_KEYS = {
        "LED_FM1", "LED_FM2", "LED_FM", "LED_IND", "LED_RDY",
        "LED_FAIL", "LED_MCDU_MENU", "LED_FM_TOP"
    };

    private static final String[] LED_LABELS = {
        "LED FM1 (top)",  "LED FM2 (top)",  "FM text (left)",
        "LED IND (top)",  "LED RDY (top)",
        "FAIL text (left)", "MCDU MENU text (right)",
        "LED FM (top, between RDY+FM2)"
    };

    // ─── State ────────────────────────────────────────────────────────────────

    private String[] keys;
    private String[] labels;
    private String   mode = MODE_FULL;

    private int       currentStep = 0;
    // Points are stored in image-LOCAL pixel coordinates (skin-relative),
    // i.e. already minus imgOffX/imgOffY. Divided by imgW/imgH at save time
    // to produce skin-normalized 0..1 values.
    private final List<float[]> tappedPoints = new ArrayList<>();

    // ─── Views (all created programmatically in onCreate) ────────────────────

    private TextView tvInstruction;
    private TextView tvProgress;
    private Button   btnUndo;
    private Button   btnSave;
    private CalibView calibView;

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Choose which calibration sequence to run.
        Intent intent = getIntent();
        if (intent != null && MODE_LEDS.equals(intent.getStringExtra(EXTRA_MODE))) {
            mode   = MODE_LEDS;
            keys   = LED_KEYS;
            labels = LED_LABELS;
            Log.d(TAG, "Calibration mode: LEDS ONLY (" + keys.length + " points)");
        } else {
            mode   = MODE_FULL;
            keys   = FULL_KEYS;
            labels = FULL_LABELS;
            Log.d(TAG, "Calibration mode: FULL (" + keys.length + " points)");
        }

        // ─── Build UI programmatically ────────────────────────────────────────
        // Top bar + bottom bar use FIXED dp heights so the middle CalibView
        // never resizes between steps — that prevents the skin from "walking"
        // when the instruction text length changes.
        final float density = getResources().getDisplayMetrics().density;
        final int topBarH    = (int)(64 * density);
        final int bottomBarH = (int)(56 * density);
        final int padSm      = (int)( 8 * density);
        final int padMd      = (int)(12 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // Top: instruction (single line, ellipsized) + progress (single line)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(padMd, padSm, padMd, padSm);
        topBar.setBackgroundColor(Color.BLACK);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, topBarH));

        tvInstruction = new TextView(this);
        tvInstruction.setTextColor(Color.YELLOW);
        tvInstruction.setTextSize(16f);
        tvInstruction.setTypeface(Typeface.DEFAULT_BOLD);
        tvInstruction.setSingleLine(true);
        tvInstruction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvInstruction.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        topBar.addView(tvInstruction);

        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.LTGRAY);
        tvProgress.setTextSize(13f);
        tvProgress.setSingleLine(true);
        tvProgress.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        topBar.addView(tvProgress);

        root.addView(topBar);

        // Middle: CalibView — weight=1 fills the space between top + bottom bars
        calibView = new CalibView(this);
        LinearLayout.LayoutParams midP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        calibView.setLayoutParams(midP);
        root.addView(calibView);

        // Bottom: UNDO + SAVE buttons (fixed height row)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(padSm, padSm/2, padSm, padSm/2);
        bottomBar.setBackgroundColor(Color.BLACK);
        bottomBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, bottomBarH));

        btnUndo = new Button(this);
        btnUndo.setText("UNDO");
        btnUndo.setTextSize(14f);
        btnUndo.setBackgroundColor(Color.parseColor("#444444"));
        btnUndo.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams undoP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        undoP.setMargins(0, 0, padSm, 0);
        btnUndo.setLayoutParams(undoP);
        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { undoLast(); }
        });
        bottomBar.addView(btnUndo);

        btnSave = new Button(this);
        btnSave.setText("SAVE");
        btnSave.setTextSize(14f);
        btnSave.setBackgroundColor(Color.parseColor("#006600"));
        btnSave.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        btnSave.setLayoutParams(saveP);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveCalibration();
                Toast.makeText(CalibrateActivityA320.this,
                    "Calibration saved! (" + currentStep + " points)",
                    Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { finish(); }
                }, 1500);
            }
        });
        bottomBar.addView(btnSave);

        root.addView(bottomBar);

        setContentView(root);
        updateUI();
    }

    // ─── Tap handling ────────────────────────────────────────────────────────

    /** Called by CalibView when the user taps inside the skin area. */
    private void onSkinTap(float imgLocalX, float imgLocalY) {
        if (currentStep >= keys.length) {
            Toast.makeText(this, "All done! Tap SAVE.", Toast.LENGTH_SHORT).show();
            return;
        }

        tappedPoints.add(new float[]{imgLocalX, imgLocalY});
        calibView.addPoint(imgLocalX, imgLocalY, keys[currentStep]);

        Log.d(TAG, "CALIBRATION " + keys[currentStep]
            + " px=" + imgLocalX + " py=" + imgLocalY);

        currentStep++;
        updateUI();
    }

    private void undoLast() {
        if (currentStep > 0) {
            currentStep--;
            if (!tappedPoints.isEmpty()) {
                tappedPoints.remove(tappedPoints.size() - 1);
            }
            calibView.removeLastPoint();
            updateUI();
            Toast.makeText(this,
                "Undo — redo: " + labels[currentStep],
                Toast.LENGTH_SHORT).show();
        }
    }

    // ─── UI refresh ──────────────────────────────────────────────────────────

    private void updateUI() {
        int total = keys.length;

        if (currentStep >= total) {
            tvInstruction.setText("All " + total + " points done — tap SAVE");
            tvInstruction.setTextColor(Color.GREEN);
            tvProgress.setText(total + " / " + total);
        } else {
            tvInstruction.setTextColor(Color.YELLOW);
            tvInstruction.setText("Tap: " + labels[currentStep]);
            tvProgress.setText("Step " + (currentStep + 1) + " of " + total
                + "   key=" + keys[currentStep]);
        }

        btnUndo.setEnabled(currentStep > 0);
        btnSave.setEnabled(currentStep > 0);
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    private void saveCalibration() {
        SharedPreferences.Editor ed =
            getSharedPreferences(PREFS_KEY, MODE_PRIVATE).edit();

        float iw = calibView.imgW;
        float ih = calibView.imgH;

        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < tappedPoints.size() && i < keys.length; i++) {
            float[] pt = tappedPoints.get(i);
            float fx = (iw > 1) ? pt[0] / iw : pt[0];
            float fy = (ih > 1) ? pt[1] / ih : pt[1];

            if (fx >= 0f && fx <= 1f && fy >= 0f && fy <= 1f) {
                ed.putFloat(keys[i] + "_x", fx);
                ed.putFloat(keys[i] + "_y", fy);
                Log.d(TAG, "Saved " + keys[i] + " x=" + fx + " y=" + fy);
                validCount++;
            } else {
                Log.w(TAG, "Invalid " + keys[i] + " x=" + fx + " y=" + fy);
                invalidCount++;
            }
        }

        // Only a full pass represents a complete calibration. LED-only pass
        // leaves the completion flag untouched so other previously calibrated
        // points (LSKs, buttons, screen corners) are not flagged incomplete.
        if (MODE_FULL.equals(mode)) {
            ed.putBoolean("calibration_complete", validCount > 10);
        }
        ed.apply();

        Log.d(TAG, "=== CALIBRATION SAVED [" + mode + "]: "
            + validCount + " valid, " + invalidCount + " invalid ===");
    }

    // ─── CalibView — custom View that draws the skin + tap markers ──────────

    /**
     * Lightweight view that loads the MCDU skin bitmap directly (bypassing
     * MCDUView entirely — no screen data, no LEDs drawn) and overlays a red
     * dot + yellow label at every recorded tap. Letterboxes the skin the
     * same way MCDUView does so calibration coordinates transfer 1:1.
     */
    class CalibView extends View {
        private Bitmap skin;
        private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<float[]> points = new ArrayList<>();
        private final List<String>  labels = new ArrayList<>();
        /** Letterbox layout — set in onDraw. Pixel units. */
        float imgOffX = 0, imgOffY = 0, imgW = 1, imgH = 1;

        CalibView(Context ctx) {
            super(ctx);
            try {
                skin = BitmapFactory.decodeResource(
                    ctx.getResources(), R.drawable.mcdu_skin_a320);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load skin: " + e.getMessage());
            }
            dotPaint.setColor(Color.RED);
            dotPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.YELLOW);
            textPaint.setTextSize(18f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);

            setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        float px = e.getX() - imgOffX;
                        float py = e.getY() - imgOffY;
                        onSkinTap(px, py);
                        return true;
                    }
                    return false;
                }
            });
        }

        void addPoint(float px, float py, String label) {
            points.add(new float[]{px, py});
            labels.add(label);
            invalidate();
        }

        void removeLastPoint() {
            if (!points.isEmpty()) {
                points.remove(points.size() - 1);
                labels.remove(labels.size() - 1);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            canvas.drawColor(Color.BLACK);

            if (skin != null && w > 0 && h > 0) {
                float imgR = (float) skin.getWidth() / skin.getHeight();
                float scrR = (float) w / (float) h;
                float dw, dh;
                if (scrR < imgR) {
                    // Screen narrower than skin aspect — letterbox top/bottom
                    dw = w; dh = w / imgR;
                    imgOffX = 0; imgOffY = (h - dh) / 2f;
                } else {
                    // Screen wider than skin aspect — pillarbox left/right
                    dh = h; dw = h * imgR;
                    imgOffX = (w - dw) / 2f; imgOffY = 0;
                }
                imgW = dw; imgH = dh;
                canvas.drawBitmap(skin, null,
                    new RectF(imgOffX, imgOffY, imgOffX + dw, imgOffY + dh),
                    skinPaint);
            }

            // Overlay every recorded tap as a red dot (no label).
            for (int i = 0; i < points.size(); i++) {
                float[] pt = points.get(i);
                float cx = imgOffX + pt[0];
                float cy = imgOffY + pt[1];
                canvas.drawCircle(cx, cy, 10f, dotPaint);
            }
        }
    }
}
