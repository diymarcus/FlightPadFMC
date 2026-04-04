package com.virtualfmc.fmc777;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.Drawable;
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

    private String fmcPosition = "LEFT";
    private String fmcDisplayText = "Left";

    private float settingsX = 0.7149f, settingsY = 0.0271f;
    private float closeX = 0.8930f, closeY = 0.0271f;
    private float fmcX = 0.2611f, fmcY = 0.0236f;
    private float ledX = 0.8246f, ledY = 0.5469f;

    private boolean ledState = false;

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fmcTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF skinRectF = new RectF();
    private final RectF iconRectF = new RectF();
    private final RectF screenRect = new RectF();
    private final RectF actualScreen = new RectF();
    
    private BlurMaskFilter ledGlowFilter;
    private final Paint.FontMetrics fmcFontMetrics = new Paint.FontMetrics();

    private Drawable settingsIcon;
    private Drawable closeIcon;

    private float iconSize;
    private float fmcBoxPadding;
    private float fmcBoxHeight;
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

        fmcTextPaint.setColor(Color.WHITE);
        fmcTextPaint.setTextAlign(Paint.Align.CENTER);
        fmcTextPaint.setTypeface(Typeface.create("Arial", Typeface.BOLD_ITALIC));

        ledBoxPaint.setStyle(Paint.Style.FILL);
        ledGlowFilter = new BlurMaskFilter(5, BlurMaskFilter.Blur.NORMAL);

        fallbackPaint.setColor(Color.YELLOW);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settingsIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_preferences, context.getTheme());
            closeIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_close_clear_cancel, context.getTheme());
        } else {
            settingsIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_preferences);
            closeIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
        }

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
                            } else if (foundArea.key.equals("FMC_LEFT_TEXT")) {
                                keyListener.onUIAction("FMC_LEFT_TEXT");
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
                float fX = prefs.getFloat("FMC_LEFT_TEXT_x", -1f);
                float fY = prefs.getFloat("FMC_LEFT_TEXT_y", -1f);
                float lX = prefs.getFloat("FMC_LED_x", -1f);
                float lY = prefs.getFloat("FMC_LED_y", -1f);

                if (isValidCalibration(sX, sY, cX, cY, fX, fY, lX, lY)) {
                    settingsX = sX; settingsY = sY;
                    closeX = cX; closeY = cY;
                    fmcX = fX; fmcY = fY;
                    ledX = lX; ledY = lY;
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
        settingsX = 0.7149f; settingsY = 0.0271f;
        closeX = 0.8930f; closeY = 0.0271f;
        fmcX = 0.2611f; fmcY = 0.0236f;
        ledX = 0.8246f; ledY = 0.5469f;
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

    public void setFmcPosition(String position) {
        fmcPosition = position.toUpperCase();
        fmcDisplayText = fmcPosition.substring(0, 1) + fmcPosition.substring(1).toLowerCase();
        postInvalidate();
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
        
        iconSize = h * 0.045f;
        fmcTextPaint.setTextSize(h * 0.020f);
        fmcBoxPadding = h * 0.006f;
        fmcBoxHeight = h * 0.024f;
        
        ledWidth = h * 0.05f;
        ledHeight = h * 0.018f;
    }

    private void setupLayout() {
        if (touchAreas == null) touchAreas = new ArrayList<>();
        else touchAreas.clear();

        touchAreas.add(new TouchArea(settingsX - 0.04f, settingsY - 0.025f, settingsX + 0.04f, settingsY + 0.025f, "SETTINGS_BTN"));
        touchAreas.add(new TouchArea(closeX - 0.04f, closeY - 0.025f, closeX + 0.04f, closeY + 0.025f, "CLOSE_BTN"));
        touchAreas.add(new TouchArea(fmcX - 0.06f, fmcY - 0.025f, fmcX + 0.06f, fmcY + 0.025f, "FMC_LEFT_TEXT"));

        screenRect.set(0.1558f, 0.0750f, 0.8333f, 0.4246f);

        String[] leftKeys  = {"LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L"};
        String[] rightKeys = {"LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R"};
        float[] lskL_X = {0.0515f, 0.0559f, 0.0526f, 0.0559f, 0.0591f, 0.0559f};
        float[] lskL_Y = {0.1313f, 0.1834f, 0.2356f, 0.2856f, 0.3398f, 0.3919f};
        float[] lskR_X = {0.9397f, 0.9365f, 0.9365f, 0.9365f, 0.9332f, 0.9365f};
        float[] lskR_Y = {0.1292f, 0.1834f, 0.2334f, 0.2877f, 0.3419f, 0.3919f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskL_X[i] - 0.04f, lskL_Y[i] - 0.025f, lskL_X[i] + 0.04f, lskL_Y[i] + 0.025f, leftKeys[i]));
            touchAreas.add(new TouchArea(lskR_X[i] - 0.04f, lskR_Y[i] - 0.025f, lskR_X[i] + 0.04f, lskR_Y[i] + 0.025f, rightKeys[i]));
        }

        touchAreas.add(new TouchArea(0.1190f, 0.4996f, 0.1990f, 0.5496f, "INIT"));
        touchAreas.add(new TouchArea(0.2504f, 0.4996f, 0.3304f, 0.5496f, "RTE"));
        touchAreas.add(new TouchArea(0.3699f, 0.5017f, 0.4499f, 0.5517f, "DEP ARR"));
        touchAreas.add(new TouchArea(0.4925f, 0.4975f, 0.5725f, 0.5475f, "ALTN"));
        touchAreas.add(new TouchArea(0.6120f, 0.4975f, 0.6920f, 0.5475f, "CRZ"));

        touchAreas.add(new TouchArea(0.1190f, 0.5517f, 0.1990f, 0.6017f, "FIX"));
        touchAreas.add(new TouchArea(0.2385f, 0.5538f, 0.3185f, 0.6038f, "LEGS"));
        touchAreas.add(new TouchArea(0.3633f, 0.5538f, 0.4433f, 0.6038f, "HOLD"));
        touchAreas.add(new TouchArea(0.4925f, 0.5538f, 0.5725f, 0.6038f, "FMC"));
        touchAreas.add(new TouchArea(0.6120f, 0.5559f, 0.6920f, 0.6059f, "PROG"));
        touchAreas.add(new TouchArea(0.7900f, 0.5580f, 0.8700f, 0.6080f, "EXEC"));

        touchAreas.add(new TouchArea(0.1190f, 0.6157f, 0.1990f, 0.6657f, "MENU"));
        touchAreas.add(new TouchArea(0.2450f, 0.6122f, 0.3250f, 0.6622f, "NAV"));
        touchAreas.add(new TouchArea(0.3894f, 0.6337f, 0.4694f, 0.6837f, "A"));
        touchAreas.add(new TouchArea(0.4958f, 0.6324f, 0.5758f, 0.6824f, "B"));
        touchAreas.add(new TouchArea(0.6022f, 0.6337f, 0.6822f, 0.6837f, "C"));
        touchAreas.add(new TouchArea(0.6988f, 0.6324f, 0.7788f, 0.6824f, "D"));
        touchAreas.add(new TouchArea(0.7998f, 0.6379f, 0.8798f, 0.6879f, "E"));

        touchAreas.add(new TouchArea(0.1158f, 0.6761f, 0.1958f, 0.7261f, "PREV PAGE"));
        touchAreas.add(new TouchArea(0.2450f, 0.6741f, 0.3250f, 0.7241f, "NEXT PAGE"));
        touchAreas.add(new TouchArea(0.3916f, 0.6963f, 0.4716f, 0.7463f, "F"));
        touchAreas.add(new TouchArea(0.4958f, 0.6900f, 0.5758f, 0.7400f, "G"));
        touchAreas.add(new TouchArea(0.5957f, 0.6921f, 0.6757f, 0.7421f, "H"));
        touchAreas.add(new TouchArea(0.6988f, 0.6942f, 0.7788f, 0.7442f, "I"));
        touchAreas.add(new TouchArea(0.7965f, 0.6900f, 0.8765f, 0.7400f, "J"));

        touchAreas.add(new TouchArea(0.0875f, 0.7505f, 0.1675f, 0.8005f, "1"));
        touchAreas.add(new TouchArea(0.1885f, 0.7546f, 0.2685f, 0.8046f, "2"));
        touchAreas.add(new TouchArea(0.2884f, 0.7526f, 0.3684f, 0.8026f, "3"));
        touchAreas.add(new TouchArea(0.3916f, 0.7546f, 0.4716f, 0.8046f, "K"));
        touchAreas.add(new TouchArea(0.4958f, 0.7505f, 0.5758f, 0.8005f, "L"));
        touchAreas.add(new TouchArea(0.6022f, 0.7484f, 0.6822f, 0.7984f, "M"));
        touchAreas.add(new TouchArea(0.7021f, 0.7484f, 0.7821f, 0.7984f, "N"));
        touchAreas.add(new TouchArea(0.7998f, 0.7505f, 0.8798f, 0.8005f, "O"));

        touchAreas.add(new TouchArea(0.0908f, 0.8103f, 0.1708f, 0.8603f, "4"));
        touchAreas.add(new TouchArea(0.1853f, 0.8089f, 0.2653f, 0.8589f, "5"));
        touchAreas.add(new TouchArea(0.2917f, 0.8089f, 0.3717f, 0.8589f, "6"));
        touchAreas.add(new TouchArea(0.3916f, 0.8089f, 0.4716f, 0.8589f, "P"));
        touchAreas.add(new TouchArea(0.4958f, 0.8103f, 0.5758f, 0.8603f, "Q"));
        touchAreas.add(new TouchArea(0.5957f, 0.8103f, 0.6757f, 0.8603f, "R"));
        touchAreas.add(new TouchArea(0.6988f, 0.8124f, 0.7788f, 0.8624f, "S"));
        touchAreas.add(new TouchArea(0.8031f, 0.8068f, 0.8831f, 0.8568f, "T"));

        touchAreas.add(new TouchArea(0.0875f, 0.8665f, 0.1675f, 0.9165f, "7"));
        touchAreas.add(new TouchArea(0.1853f, 0.8686f, 0.2653f, 0.9186f, "8"));
        touchAreas.add(new TouchArea(0.2852f, 0.8665f, 0.3652f, 0.9165f, "9"));
        touchAreas.add(new TouchArea(0.3916f, 0.8686f, 0.4716f, 0.9186f, "U"));
        touchAreas.add(new TouchArea(0.4958f, 0.8728f, 0.5758f, 0.9228f, "V"));
        touchAreas.add(new TouchArea(0.5957f, 0.8686f, 0.6757f, 0.9186f, "W"));
        touchAreas.add(new TouchArea(0.7054f, 0.8707f, 0.7854f, 0.9207f, "X"));
        touchAreas.add(new TouchArea(0.8031f, 0.8665f, 0.8831f, 0.9165f, "Y"));

        touchAreas.add(new TouchArea(0.0843f, 0.9312f, 0.1643f, 0.9812f, "."));
        touchAreas.add(new TouchArea(0.1918f, 0.9291f, 0.2718f, 0.9791f, "0"));
        touchAreas.add(new TouchArea(0.2852f, 0.9291f, 0.3652f, 0.9791f, "+/-"));
        touchAreas.add(new TouchArea(0.3948f, 0.9270f, 0.4748f, 0.9770f, "Z"));
        touchAreas.add(new TouchArea(0.4958f, 0.9270f, 0.5758f, 0.9770f, "SP"));
        touchAreas.add(new TouchArea(0.5957f, 0.9312f, 0.6757f, 0.9812f, "DEL"));
        touchAreas.add(new TouchArea(0.6988f, 0.9312f, 0.7788f, 0.9812f, "/"));
        touchAreas.add(new TouchArea(0.8031f, 0.9270f, 0.8831f, 0.9770f, "CLR"));
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

        drawIcon(canvas, settingsIcon, settingsX, settingsY);
        drawIcon(canvas, closeIcon, closeX, closeY);
        drawFmcText(canvas);
        drawLedIndicator(canvas);

        if (pressedArea != null) {
            float px = imgOffsetX + pressedArea.rect.centerX() * imgScaleW;
            float py = imgOffsetY + pressedArea.rect.centerY() * imgScaleH;
            float radius = (Math.min(pressedArea.rect.width() * imgScaleW, pressedArea.rect.height() * imgScaleH) / 2f) * 0.7f;
            canvas.drawCircle(px, py, radius, highlightPaint);
        }
    }

    private void drawIcon(Canvas canvas, Drawable d, float xFrac, float yFrac) {
        float px = imgOffsetX + xFrac * imgScaleW;
        float py = imgOffsetY + yFrac * imgScaleH;
        if (d != null) {
            iconRectF.set(px - iconSize/2, py - iconSize/2, px + iconSize/2, py + iconSize/2);
            d.setBounds((int)iconRectF.left, (int)iconRectF.top, (int)iconRectF.right, (int)iconRectF.bottom);
            d.draw(canvas);
        } else {
            canvas.drawCircle(px, py, iconSize/2, fallbackPaint);
        }
    }

    private void drawFmcText(Canvas canvas) {
        float px = imgOffsetX + fmcX * imgScaleW;
        float py = imgOffsetY + fmcY * imgScaleH;
        float textWidth = fmcTextPaint.measureText(fmcDisplayText);
        
        canvas.drawRect(px - textWidth/2 - fmcBoxPadding, py - fmcBoxHeight/2, 
                        px + textWidth/2 + fmcBoxPadding, py + fmcBoxHeight/2, boxPaint);
        
        fmcTextPaint.getFontMetrics(fmcFontMetrics);
        float textY = py - (fmcFontMetrics.ascent + fmcFontMetrics.descent) / 2;
        canvas.drawText(fmcDisplayText, px, textY, fmcTextPaint);
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
