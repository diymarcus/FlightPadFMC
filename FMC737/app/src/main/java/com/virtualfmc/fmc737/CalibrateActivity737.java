package com.virtualfmc.fmc737;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;

public class CalibrateActivity737 extends Activity {

    public static final String EXTRA_MODE = "calibration_mode";
    public static final String MODE_FULL  = "full";
    public static final String MODE_LEDS  = "leds";

    private static final String[] SEQUENCE_FULL = {
        // UI Elements + LED + bezel annunciators
        "SETTINGS_BTN","CLOSE_BTN",
        "FMC_LED", "FMC_MSG", "FMC_OFST", "FMC_CALL", "FMC_FAIL",
        // LSK Left
        "LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L",
        // LSK Right
        "LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R",
        // Screen corners (TL + BR define the rectangle)
        "SCREEN_TL","SCREEN_BR",
        // Function row 1
        "INIT","RTE","DEP","CLB","CRZ","DES",
        // Function row 2
        "MENU","LEGS","HOLD","PROG","EXEC",
        // Key row 1 (N1LIMIT, FIX, MAN - MAN is non-functional)
        "N1LIMIT","FIX","MAN",
        // Key row 2 (PREV PAGE, NEXT PAGE, PREV MENU - PREV MENU is non-functional)
        "PREV PAGE","NEXT PAGE","PREV MENU",
        // Letters A-E
        "A","B","C","D","E",
        // Letters F-J
        "F","G","H","I","J",
        // Number 1,2,3 + Letters K-O
        "1","2","3","K","L","M","N","O",
        // Number 4,5,6 + Letters P-T
        "4","5","6","P","Q","R","S","T",
        // Number 7,8,9 + Letters U-Y
        "7","8","9","U","V","W","X","Y",
        // Bottom row
        ".","0","+/-","Z","SP","DEL","/","CLR",
    };

    /** LEDs-only quick recalibration — just the EXEC LED + bezel annunciators.
     *  Use after a skin update, or whenever any annunciator drifts after a
     *  full pass. Saves only FMC_LED_* / FMC_MSG_* / FMC_OFST_* /
     *  FMC_CALL_* / FMC_FAIL_* prefs without overwriting the full key
     *  calibration or the calibration_complete flag. */
    private static final String[] SEQUENCE_LEDS = {
        "FMC_LED", "FMC_MSG", "FMC_OFST", "FMC_CALL", "FMC_FAIL",
    };

    private String[] BUTTON_SEQUENCE = SEQUENCE_FULL;
    private boolean ledsOnlyMode = false;

    private int currentIndex = 0;
    private List<float[]> tappedPoints = new ArrayList<>();
    private TextView tvInstruction;
    private CalibView calibView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Mode comes from the launching Intent (default = full sweep)
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_LEDS.equals(mode)) {
            BUTTON_SEQUENCE = SEQUENCE_LEDS;
            ledsOnlyMode = true;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView statusBar = new TextView(this);
        statusBar.setText(ledsOnlyMode
            ? "LED CALIBRATION 737 - Tap centre of EXEC LED, then MSG / OFST / CALL / FAIL"
            : "CALIBRATION MODE 737");
        statusBar.setTextColor(Color.YELLOW);
        statusBar.setTextSize(8f);
        statusBar.setPadding(2,2,2,2);
        statusBar.setBackgroundColor(Color.BLACK);
        root.addView(statusBar);

        tvInstruction = new TextView(this);
        tvInstruction.setTextColor(Color.WHITE);
        tvInstruction.setTextSize(12f);
        tvInstruction.setPadding(4,2,4,2);
        tvInstruction.setBackgroundColor(Color.argb(180,0,0,0));
        root.addView(tvInstruction);

        calibView = new CalibView(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        calibView.setLayoutParams(p);
        root.addView(calibView);

        Button undoBtn = new Button(this);
        undoBtn.setText("UNDO LAST");
        undoBtn.setTextSize(10f);
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoLast();
            }
        });
        root.addView(undoBtn);

        setContentView(root);
        updateInstruction();
    }

    private void tap(float x, float y) {
        if (currentIndex >= BUTTON_SEQUENCE.length) return;
        String key = BUTTON_SEQUENCE[currentIndex];
        tappedPoints.add(new float[]{x, y});
        currentIndex++;
        calibView.addPoint(x, y, key);
        updateInstruction();
        if (currentIndex >= BUTTON_SEQUENCE.length) {
            logResults();
        }
    }

    private void undoLast() {
        if (currentIndex > 0) {
            currentIndex--;
            tappedPoints.remove(tappedPoints.size()-1);
            calibView.removeLastPoint();
            updateInstruction();
        }
    }

    private void updateInstruction() {
        if (currentIndex < BUTTON_SEQUENCE.length) {
            String btnName = BUTTON_SEQUENCE[currentIndex];
            String prefix = ledsOnlyMode ? "TAP LED" : (currentIndex < 4 ? "UI ELEMENT" : "TAP");
            tvInstruction.setText(
                (currentIndex+1) + "/" + BUTTON_SEQUENCE.length +
                " → " + prefix + ": " + btnName
            );
        } else {
            tvInstruction.setText("DONE! Check Logcat for CALIBRATION737 tag.");
        }
    }

    private void logResults() {
        float iw = calibView.imgW;
        float ih = calibView.imgH;

        SharedPreferences prefs = getSharedPreferences("CalibrationData737", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < tappedPoints.size() && i < BUTTON_SEQUENCE.length; i++) {
            float[] pt = tappedPoints.get(i);
            float fx = (iw > 1) ? pt[0]/iw : pt[0];
            float fy = (ih > 1) ? pt[1]/ih : pt[1];
            if (fx >= 0f && fx <= 1f && fy >= 0f && fy <= 1f) {
                editor.putFloat(BUTTON_SEQUENCE[i] + "_x", fx);
                editor.putFloat(BUTTON_SEQUENCE[i] + "_y", fy);
                Log.d("CALIBRATION737", BUTTON_SEQUENCE[i] + ": x=" + fx + " y=" + fy);
                validCount++;
            } else {
                Log.w("CALIBRATION737", "Invalid: " + BUTTON_SEQUENCE[i] + " x=" + fx + " y=" + fy);
                invalidCount++;
            }
        }

        // Only the FULL sweep (>10 points) sets calibration_complete.
        // LEDs-only re-runs leave the flag (and the existing key calibration)
        // untouched — same pattern as FMC777 / Fenix.
        if (!ledsOnlyMode) {
            editor.putBoolean("calibration_complete", validCount > 10);
        }
        editor.apply();

        String msg = "Saved " + validCount + " points";
        if (invalidCount > 0) msg += " (" + invalidCount + " invalid)";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        Log.d("CALIBRATION737", "Calibration saved: " + validCount + " valid, " + invalidCount + " invalid");

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    class CalibView extends View {
        private Bitmap skin;
        private Paint skinPaint  = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<float[]> points = new ArrayList<>();
        private List<String>  labels = new ArrayList<>();
        float imgOffX=0, imgOffY=0, imgW=1, imgH=1;

        CalibView(android.content.Context ctx) {
            super(ctx);
            try {
                skin = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.cdu_skin737);
            } catch (Exception e) {}
            dotPaint.setColor(Color.RED);
            dotPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.YELLOW);
            textPaint.setTextSize(16f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    float px = e.getX() - imgOffX;
                    float py = e.getY() - imgOffY;
                    tap(px, py);
                    return true;
                }
                return false;
            });
        }

        void addPoint(float px, float py, String label) {
            points.add(new float[]{px, py});
            labels.add(label);
            invalidate();
        }

        void removeLastPoint() {
            if (!points.isEmpty()) {
                points.remove(points.size()-1);
                labels.remove(labels.size()-1);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (skin != null) {
                float imgR = (float)skin.getWidth()/skin.getHeight();
                float scrR = (float)w/h;
                float dw, dh;
                if (scrR < imgR) { dw=w; dh=w/imgR; imgOffX=0; imgOffY=(h-dh)/2f; }
                else { dh=h; dw=h*imgR; imgOffX=(w-dw)/2f; imgOffY=0; }
                imgW = dw; imgH = dh;
                canvas.drawBitmap(skin, null,
                    new RectF(imgOffX, imgOffY, imgOffX+dw, imgOffY+dh), skinPaint);
            }
            for (int i=0; i<points.size(); i++) {
                float px = imgOffX + points.get(i)[0];
                float py = imgOffY + points.get(i)[1];
                canvas.drawCircle(px, py, 10, dotPaint);
                canvas.drawText(labels.get(i), px+12, py+5, textPaint);
            }
        }
    }
}
