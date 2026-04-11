package com.virtualfmc.fmc777;

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

    private TouchArea pressedArea = null;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastTouchTime = 0;
    private static final long TOUCH_DEBOUNCE_MS = 150;

    private boolean hapticEnabled = true;

    private final char[][] symbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][] colors = new int[CDU_ROWS][CDU_COLS];

    private float settingsX = 0.09561454f, settingsY = 0.02447295f;
    private float closeX = 0.91020817f, closeY = 0.02447295f;
    private float ledX = 0.8228679f, ledY = 0.5445661f;

    // Screen rect calibration (TL + BR corners)
    private float screenL = 0.14666007f, screenT = 0.07033213f;
    private float screenR = 0.84544694f, screenB = 0.43650627f;

    private boolean ledState = false;

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF skinRectF = new RectF();
    private final RectF screenRect = new RectF();
    private final RectF actualScreen = new RectF();

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
    private float imgScaleW  = 1, imgScaleH  = 1;

    private static class TouchArea {
        final RectF rect;
        final String key;
        TouchArea(float l, float t, float r, float b, String k) {
            rect = new RectF(l, t, r, b);
            key  = k;
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
        logD("init() started - PMDG 777 version");
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
                logD("777 skin loaded: " + (skin.getByteCount() / 1024) + "KB (" + skin.getWidth() + "x" + skin.getHeight() + ")");
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
        try {
            SharedPreferences prefs = context.getSharedPreferences("CalibrationData777", Context.MODE_PRIVATE);
            boolean isCalibrated = prefs.getBoolean("calibration_complete", false);

            if (isCalibrated) {
                float sX = prefs.getFloat("SETTINGS_BTN_x", -1f);
                float sY = prefs.getFloat("SETTINGS_BTN_y", -1f);
                float cX = prefs.getFloat("CLOSE_BTN_x", -1f);
                float cY = prefs.getFloat("CLOSE_BTN_y", -1f);
                float lX     = prefs.getFloat("FMC_LED_x",   -1f);
                float lY     = prefs.getFloat("FMC_LED_y",   -1f);
                float sTL_x  = prefs.getFloat("SCREEN_TL_x", -1f);
                float sTL_y  = prefs.getFloat("SCREEN_TL_y", -1f);
                float sBR_x  = prefs.getFloat("SCREEN_BR_x", -1f);
                float sBR_y  = prefs.getFloat("SCREEN_BR_y", -1f);

                if (isValidCalibration(sX, sY, cX, cY, lX, lY)) {
                    settingsX = sX; settingsY = sY;
                    closeX = cX; closeY = cY;
                    ledX = lX; ledY = lY;
                    if (isValidCalibration(sTL_x, sTL_y, sBR_x, sBR_y)) {
                        screenL = sTL_x; screenT = sTL_y;
                        screenR = sBR_x; screenB = sBR_y;
                    }
                } else {
                    resetToSafeDefaults();
                }
            } else {
                resetToSafeDefaults();
            }
        } catch (Exception e) {
            resetToSafeDefaults();
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

    private void logD(String message) {
        if (DEBUG_MODE) {
            android.util.Log.d(TAG, message);
        }
    }

    private boolean isValidCalibration(float... values) {
        for (float v : values) {
            if (v < 0f || v > 1f) return false;
        }
        return true;
    }

    private void resetToSafeDefaults() {
        settingsX = 0.09561454f; settingsY = 0.02447295f;
        closeX = 0.91020817f; closeY = 0.02447295f;
        ledX = 0.8228679f; ledY = 0.5445661f;
        screenL = 0.14666007f; screenT = 0.07033213f;
        screenR = 0.84544694f; screenB = 0.43650627f;
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

        actualScreen.set(imgOffsetX + screenRect.left * imgScaleW, imgOffsetY + screenRect.top * imgScaleH,
                         imgOffsetX + screenRect.right * imgScaleW, imgOffsetY + screenRect.bottom * imgScaleH);
        
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

        touchAreas.add(new TouchArea(settingsX - 0.04f, settingsY - 0.025f, settingsX + 0.04f, settingsY + 0.025f, "SETTINGS_BTN"));
        touchAreas.add(new TouchArea(closeX - 0.04f, closeY - 0.025f, closeX + 0.04f, closeY + 0.025f, "CLOSE_BTN"));

        // Screen area — from calibration (falls back to hardcoded defaults)
        screenRect.set(screenL, screenT, screenR, screenB);

        String[] leftKeys  = {"LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L"};
        String[] rightKeys = {"LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R"};
        float[] lskL_X = {0.060290247f, 0.05340007f, 0.057346556f, 0.05340007f, 0.057346556f, 0.057346556f};
        float[] lskL_Y = {0.13249597f, 0.18277177f, 0.23488782f, 0.28891772f, 0.3410338f, 0.39318663f};
        float[] lskR_X = {0.9416183f, 0.93964505f, 0.9416183f, 0.93964505f, 0.9416183f, 0.9386746f};
        float[] lskR_Y = {0.12874186f, 0.18402314f, 0.2361392f, 0.28891772f, 0.3410338f, 0.39318663f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskL_X[i] - 0.04f, lskL_Y[i] - 0.025f, lskL_X[i] + 0.04f, lskL_Y[i] + 0.025f, leftKeys[i]));
            touchAreas.add(new TouchArea(lskR_X[i] - 0.04f, lskR_Y[i] - 0.025f, lskR_X[i] + 0.04f, lskR_Y[i] + 0.025f, rightKeys[i]));
        }

        touchAreas.add(new TouchArea(0.1194f, 0.5007f, 0.1994f, 0.5507f, "INIT"));
        touchAreas.add(new TouchArea(0.2460f, 0.4988f, 0.3260f, 0.5488f, "RTE"));
        touchAreas.add(new TouchArea(0.3657f, 0.5007f, 0.4457f, 0.5507f, "DEP ARR"));
        touchAreas.add(new TouchArea(0.4943f, 0.5001f, 0.5743f, 0.5501f, "ALTN"));
        touchAreas.add(new TouchArea(0.6190f, 0.4988f, 0.6990f, 0.5488f, "CRZ"));

        touchAreas.add(new TouchArea(0.1214f, 0.5579f, 0.2014f, 0.6079f, "FIX"));
        touchAreas.add(new TouchArea(0.2460f, 0.5579f, 0.3260f, 0.6079f, "LEGS"));
        touchAreas.add(new TouchArea(0.3687f, 0.5566f, 0.4487f, 0.6066f, "HOLD"));
        touchAreas.add(new TouchArea(0.4914f, 0.5616f, 0.5714f, 0.6116f, "FMC"));
        touchAreas.add(new TouchArea(0.6170f, 0.5585f, 0.6970f, 0.6085f, "PROG"));
        touchAreas.add(new TouchArea(0.7829f, 0.5585f, 0.8629f, 0.6085f, "EXEC"));

        touchAreas.add(new TouchArea(0.1194f, 0.6175f, 0.1994f, 0.6675f, "MENU"));
        touchAreas.add(new TouchArea(0.2431f, 0.6182f, 0.3231f, 0.6682f, "NAV"));
        touchAreas.add(new TouchArea(0.3952f, 0.6370f, 0.4752f, 0.6870f, "A"));
        touchAreas.add(new TouchArea(0.4982f, 0.6383f, 0.5782f, 0.6883f, "B"));
        touchAreas.add(new TouchArea(0.6013f, 0.6364f, 0.6813f, 0.6864f, "C"));
        touchAreas.add(new TouchArea(0.7014f, 0.6333f, 0.7814f, 0.6833f, "D"));
        touchAreas.add(new TouchArea(0.8084f, 0.6364f, 0.8884f, 0.6864f, "E"));

        touchAreas.add(new TouchArea(0.1214f, 0.6810f, 0.2014f, 0.7310f, "PREV PAGE"));
        touchAreas.add(new TouchArea(0.2431f, 0.6816f, 0.3231f, 0.7316f, "NEXT PAGE"));
        touchAreas.add(new TouchArea(0.3952f, 0.6948f, 0.4752f, 0.7448f, "F"));
        touchAreas.add(new TouchArea(0.4982f, 0.6948f, 0.5782f, 0.7448f, "G"));
        touchAreas.add(new TouchArea(0.6023f, 0.6948f, 0.6823f, 0.7448f, "H"));
        touchAreas.add(new TouchArea(0.7014f, 0.6960f, 0.7814f, 0.7460f, "I"));
        touchAreas.add(new TouchArea(0.8054f, 0.6929f, 0.8854f, 0.7429f, "J"));

        touchAreas.add(new TouchArea(0.0919f, 0.7557f, 0.1719f, 0.8057f, "1"));
        touchAreas.add(new TouchArea(0.1881f, 0.7557f, 0.2681f, 0.8057f, "2"));
        touchAreas.add(new TouchArea(0.2912f, 0.7520f, 0.3712f, 0.8020f, "3"));
        touchAreas.add(new TouchArea(0.3952f, 0.7557f, 0.4752f, 0.8057f, "K"));
        touchAreas.add(new TouchArea(0.4973f, 0.7520f, 0.5773f, 0.8020f, "L"));
        touchAreas.add(new TouchArea(0.6023f, 0.7526f, 0.6823f, 0.8026f, "M"));
        touchAreas.add(new TouchArea(0.7063f, 0.7557f, 0.7863f, 0.8057f, "N"));
        touchAreas.add(new TouchArea(0.8054f, 0.7520f, 0.8854f, 0.8020f, "O"));

        touchAreas.add(new TouchArea(0.0900f, 0.8135f, 0.1700f, 0.8635f, "4"));
        touchAreas.add(new TouchArea(0.1911f, 0.8117f, 0.2711f, 0.8617f, "5"));
        touchAreas.add(new TouchArea(0.2823f, 0.8123f, 0.3623f, 0.8623f, "6"));
        touchAreas.add(new TouchArea(0.3952f, 0.8117f, 0.4752f, 0.8617f, "P"));
        touchAreas.add(new TouchArea(0.4982f, 0.8135f, 0.5782f, 0.8635f, "Q"));
        touchAreas.add(new TouchArea(0.5984f, 0.8117f, 0.6784f, 0.8617f, "R"));
        touchAreas.add(new TouchArea(0.7004f, 0.8135f, 0.7804f, 0.8635f, "S"));
        touchAreas.add(new TouchArea(0.8084f, 0.8117f, 0.8884f, 0.8617f, "T"));

        touchAreas.add(new TouchArea(0.0861f, 0.8713f, 0.1661f, 0.9213f, "7"));
        touchAreas.add(new TouchArea(0.1891f, 0.8701f, 0.2691f, 0.9201f, "8"));
        touchAreas.add(new TouchArea(0.2872f, 0.8701f, 0.3672f, 0.9201f, "9"));
        touchAreas.add(new TouchArea(0.3982f, 0.8701f, 0.4782f, 0.9201f, "U"));
        touchAreas.add(new TouchArea(0.4973f, 0.8694f, 0.5773f, 0.9194f, "V"));
        touchAreas.add(new TouchArea(0.5984f, 0.8701f, 0.6784f, 0.9201f, "W"));
        touchAreas.add(new TouchArea(0.7014f, 0.8701f, 0.7814f, 0.9201f, "X"));
        touchAreas.add(new TouchArea(0.8054f, 0.8713f, 0.8854f, 0.9213f, "Y"));

        touchAreas.add(new TouchArea(0.0870f, 0.9272f, 0.1670f, 0.9772f, "."));
        touchAreas.add(new TouchArea(0.1881f, 0.9278f, 0.2681f, 0.9778f, "0"));
        touchAreas.add(new TouchArea(0.2872f, 0.9310f, 0.3672f, 0.9810f, "+/-"));
        touchAreas.add(new TouchArea(0.3952f, 0.9278f, 0.4752f, 0.9778f, "Z"));
        touchAreas.add(new TouchArea(0.4982f, 0.9272f, 0.5782f, 0.9772f, "SP"));
        touchAreas.add(new TouchArea(0.6013f, 0.9278f, 0.6813f, 0.9778f, "DEL"));
        touchAreas.add(new TouchArea(0.7044f, 0.9310f, 0.7844f, 0.9810f, "/"));
        touchAreas.add(new TouchArea(0.8025f, 0.9272f, 0.8825f, 0.9772f, "CLR"));
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

        drawLedIndicator(canvas);

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
        
        if (ledState) {
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

    public void recycleSkin() {
        if (skin != null && !skin.isRecycled()) {
            skin.recycle();
            skin = null;
        }
    }
}
