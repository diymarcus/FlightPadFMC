package com.virtualfmc.fmc777;

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

public class CalibrateActivity777 extends Activity {

    private static final String[] BUTTON_SEQUENCE = {
        "SETTINGS_BTN", "CLOSE_BTN", "FMC_LED",
        "LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L",
        "LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R",
        // Screen corners (TL + BR define the rectangle)
        "SCREEN_TL","SCREEN_BR",
        "INIT","RTE","DEP ARR","ALTN","CRZ",
        "FIX","LEGS","HOLD","FMC","PROG","EXEC",
        "MENU","NAV", "PREV PAGE","NEXT PAGE",
        "A","B","C","D","E", "F","G","H","I","J",
        "1","2","3","K","L","M","N","O",
        "4","5","6","P","Q","R","S","T",
        "7","8","9","U","V","W","X","Y",
        ".","0","+/-","Z","SP","DEL","/","CLR",
    };

    private int currentIndex = 0;
    private final List<float[]> tappedPoints = new ArrayList<float[]>();
    private TextView tvInstruction;
    private CalibView calibView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.BLACK);

            TextView statusBar = new TextView(this);
            statusBar.setText("CALIBRATION MODE 777 - Tap button centers");
            statusBar.setTextColor(Color.YELLOW);
            statusBar.setTextSize(10f);
            statusBar.setPadding(5,5,5,5);
            root.addView(statusBar);

            tvInstruction = new TextView(this);
            tvInstruction.setTextColor(Color.WHITE);
            tvInstruction.setTextSize(14f);
            tvInstruction.setPadding(10,5,10,5);
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
            Log.d("CalibrateActivity777", "Activity created");
        } catch (Exception e) {
            Log.e("CalibrateActivity777", "Error in onCreate", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (calibView != null && calibView.skin != null) {
            calibView.skin.recycle();
            calibView.skin = null;
        }
    }

    private void tap(float x, float y) {
        if (currentIndex >= BUTTON_SEQUENCE.length) return;
        
        // Validate tap position (must be within image bounds)
        if (x < 0 || y < 0 || x > calibView.imgW || y > calibView.imgH) {
            Toast.makeText(this, "Tap INSIDE the CDU image!", Toast.LENGTH_SHORT).show();
            return;
        }
        
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
            String key = BUTTON_SEQUENCE[currentIndex];
            tvInstruction.setText((currentIndex+1) + "/" + BUTTON_SEQUENCE.length + " → TAP: " + key);
        } else {
            tvInstruction.setText("DONE! Saving...");
        }
    }

    private void logResults() {
        float iw = calibView.imgW;
        float ih = calibView.imgH;

        SharedPreferences prefs = getSharedPreferences("CalibrationData777", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < tappedPoints.size(); i++) {
            float[] pt = tappedPoints.get(i);
            float fx = pt[0]/iw;
            float fy = pt[1]/ih;
            
            // Validate normalized coordinates
            if (fx >= 0f && fx <= 1f && fy >= 0f && fy <= 1f) {
                editor.putFloat(BUTTON_SEQUENCE[i] + "_x", fx);
                editor.putFloat(BUTTON_SEQUENCE[i] + "_y", fy);
                Log.d("CALIBRATION777", BUTTON_SEQUENCE[i] + ": x=" + fx + " y=" + fy);
                validCount++;
            } else {
                Log.w("CALIBRATION777", "Invalid: " + BUTTON_SEQUENCE[i] + " x=" + fx + " y=" + fy);
                invalidCount++;
            }
        }

        editor.putBoolean("calibration_complete", validCount > 10);  // At least 10 valid points
        editor.apply();

        String msg = "Saved " + validCount + " points";
        if (invalidCount > 0) msg += " (" + invalidCount + " invalid)";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        
        Log.d("CALIBRATION777", "Calibration saved: " + validCount + " valid, " + invalidCount + " invalid");
        
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    class CalibView extends View {
        Bitmap skin;
        private final Paint skinPaint  = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<float[]> points = new ArrayList<float[]>();
        private final List<String>  labels = new ArrayList<String>();
        private final RectF skinRectF = new RectF();
        float imgOffX=0, imgOffY=0, imgW=1, imgH=1;

        CalibView(android.content.Context ctx) {
            super(ctx);
            skin = decodeSampledBitmap(ctx, R.drawable.cdu_skin777, 1024);
            dotPaint.setColor(Color.RED);
            dotPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.YELLOW);
            textPaint.setTextSize(16f);
            textPaint.setTextAlign(Paint.Align.CENTER);

            setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        tap(e.getX() - imgOffX, e.getY() - imgOffY);
                        return true;
                    }
                    return false;
                }
            });
        }

        private Bitmap decodeSampledBitmap(android.content.Context context, int resId, int reqSize) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                options.inScaled = false; // Prevent density-based scaling
                BitmapFactory.decodeResource(context.getResources(), resId, options);
                
                int sampleSize = 1;
                while ((options.outHeight / sampleSize) >= reqSize && (options.outWidth / sampleSize) >= reqSize) {
                    sampleSize *= 2;
                }
                
                options.inJustDecodeBounds = false;
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inScaled = false; // Ensure no scaling after decoding
                return BitmapFactory.decodeResource(context.getResources(), resId, options);
            } catch (Exception e) { 
                Log.e("CalibrateActivity777", "Error loading skin", e);
                return null; 
            }
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
            if (w == 0 || h == 0) return;

            if (skin == null) {
                canvas.drawColor(Color.argb(255, 60, 60, 60));
                imgOffX = 0; imgOffY = 0; imgW = w; imgH = h;
                
                Paint msgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                msgPaint.setColor(Color.YELLOW);
                msgPaint.setTextSize(14f);
                msgPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⚠ Skin image not found", w/2f, h/2f, msgPaint);
                canvas.drawText("Check cdu_skin777.png in drawable", w/2f, h/2f + 20, msgPaint);
                return;
            }

            float imgR = (float)skin.getWidth()/skin.getHeight();
            float scrR = (float)w/h;
            float dw, dh;
            if (scrR < imgR) { dw=w; dh=w/imgR; imgOffX=0; imgOffY=(h-dh)/2f; }
            else { dh=h; dw=h*imgR; imgOffX=(w-dw)/2f; imgOffY=0; }
            imgW = dw; imgH = dh;
            
            try {
                skinRectF.set(imgOffX, imgOffY, imgOffX+dw, imgOffY+dh);
                canvas.drawBitmap(skin, null, skinRectF, skinPaint);
            } catch (Exception e) {
                Log.e("CalibrateActivity777", "Failed to draw skin", e);
            }

            // Draw tap points
            for (int i=0; i<points.size(); i++) {
                float px = imgOffX + points.get(i)[0];
                float py = imgOffY + points.get(i)[1];
                canvas.drawCircle(px, py, 10, dotPaint);
                canvas.drawText(labels.get(i), px, py - 15, textPaint);
            }
        }
    }
}
