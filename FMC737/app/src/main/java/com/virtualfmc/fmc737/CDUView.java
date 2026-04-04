package com.virtualfmc.fmc737;

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
    private boolean hapticEnabled = true;

    private TouchArea pressedArea = null;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastTouchTime = 0;
    private static final long TOUCH_DEBOUNCE_MS = 150;

    private String fmcPosition = "LEFT";
    private String fmcDisplayText = "Left";

    // UI element positions (calibrated)
    private float settingsX = 0.955f, settingsY = 0.030f;
    private float closeX = 0.045f, closeY = 0.030f;
    private float fmcX = 0.500f, fmcY = 0.030f;
    private float ledX = 0.500f, ledY = 0.050f;

    private boolean ledState = false;

    private final char[][] symbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][] colors = new int[CDU_ROWS][CDU_COLS];

    private Bitmap skin;

    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fmcTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF screenRect = new RectF();
    private final RectF actualScreen = new RectF();
    private final RectF skinRectF = new RectF();
    private final RectF iconRectF = new RectF();

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
            SharedPreferences prefs = context.getSharedPreferences("CalibrationData737", Context.MODE_PRIVATE);
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

    private boolean isValidCalibration(float... values) {
        for (float v : values) {
            if (v < 0f || v > 1f) return false;
        }
        return true;
    }

    private void resetToSafeDefaults() {
        // UI Elements (from calibration 26 March 2026 - 20:56)
        settingsX = 0.6860f; settingsY = 0.0229f;
        closeX = 0.8750f; closeY = 0.0266f;
        fmcX = 0.2447f; fmcY = 0.0254f;
        ledX = 0.8315f; ledY = 0.5721f;
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

    public String getFmcPosition() {
        return fmcPosition;
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

        // UI elements (Settings, Close, FMC text) - from calibration
        touchAreas.add(new TouchArea(settingsX - 0.04f, settingsY - 0.025f, settingsX + 0.04f, settingsY + 0.025f, "SETTINGS_BTN"));
        touchAreas.add(new TouchArea(closeX - 0.04f, closeY - 0.025f, closeX + 0.04f, closeY + 0.025f, "CLOSE_BTN"));
        touchAreas.add(new TouchArea(fmcX - 0.06f, fmcY - 0.025f, fmcX + 0.06f, fmcY + 0.025f, "FMC_LEFT_TEXT"));

        // Screen area - from calibration 20:56
        float sL = 0.1556f, sT = 0.0860f;
        float sR = 0.8453f, sB = 0.4359f;
        screenRect.set(sL, sT, sR, sB);

        // LSK Left buttons - from calibration 20:56
        String[] leftKeys  = {"LSK1L","LSK2L","LSK3L","LSK4L","LSK5L","LSK6L"};
        float[] lskLX = {0.0448f, 0.0467f, 0.0467f, 0.0448f, 0.0467f, 0.0467f};
        float[] lskLY = {0.1566f, 0.2043f, 0.2538f, 0.3059f, 0.3510f, 0.3994f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskLX[i] - 0.035f, lskLY[i] - 0.025f, lskLX[i] + 0.035f, lskLY[i] + 0.025f, leftKeys[i]));
        }

        // LSK Right buttons - from calibration 20:56
        String[] rightKeys = {"LSK1R","LSK2R","LSK3R","LSK4R","LSK5R","LSK6R"};
        float[] lskRX = {0.9562f, 0.9562f, 0.9522f, 0.9562f, 0.9522f, 0.9492f};
        float[] lskRY = {0.1523f, 0.2031f, 0.2526f, 0.3021f, 0.3498f, 0.3969f};
        for (int i = 0; i < 6; i++) {
            touchAreas.add(new TouchArea(lskRX[i] - 0.035f, lskRY[i] - 0.025f, lskRX[i] + 0.035f, lskRY[i] + 0.025f, rightKeys[i]));
        }

        // Function Row 1 - from calibration 20:56
        touchAreas.add(new TouchArea(0.1615f - 0.055f, 0.5399f - 0.025f, 0.1615f + 0.055f, 0.5399f + 0.025f, "INIT"));
        touchAreas.add(new TouchArea(0.2902f - 0.055f, 0.5411f - 0.025f, 0.2902f + 0.055f, 0.5411f + 0.025f, "RTE"));
        touchAreas.add(new TouchArea(0.4089f - 0.055f, 0.5931f - 0.025f, 0.4089f + 0.055f, 0.5931f + 0.025f, "DEP ARR"));
        touchAreas.add(new TouchArea(0.4149f - 0.055f, 0.5424f - 0.025f, 0.4149f + 0.055f, 0.5424f + 0.025f, "CLB"));
        touchAreas.add(new TouchArea(0.5425f - 0.055f, 0.5424f - 0.025f, 0.5425f + 0.055f, 0.5424f + 0.025f, "CRZ"));
        touchAreas.add(new TouchArea(0.6652f - 0.055f, 0.5399f - 0.025f, 0.6652f + 0.055f, 0.5399f + 0.025f, "DES"));

        // Function Row 2 - from calibration 20:56
        touchAreas.add(new TouchArea(0.1675f - 0.055f, 0.5969f - 0.025f, 0.1675f + 0.055f, 0.5969f + 0.025f, "MENU"));
        touchAreas.add(new TouchArea(0.2922f - 0.055f, 0.5969f - 0.025f, 0.2922f + 0.055f, 0.5969f + 0.025f, "LEGS"));
        touchAreas.add(new TouchArea(0.5425f - 0.055f, 0.5969f - 0.025f, 0.5425f + 0.055f, 0.5969f + 0.025f, "HOLD"));
        touchAreas.add(new TouchArea(0.6692f - 0.055f, 0.5981f - 0.025f, 0.6692f + 0.055f, 0.5981f + 0.025f, "PROG"));
        touchAreas.add(new TouchArea(0.8265f - 0.055f, 0.6049f - 0.025f, 0.8265f + 0.055f, 0.6049f + 0.025f, "EXEC"));

        // Key Row 1 - from calibration 20:56 (N1LIMIT, FIX, MAN)
        touchAreas.add(new TouchArea(0.1655f - 0.055f, 0.6569f - 0.025f, 0.1655f + 0.055f, 0.6569f + 0.025f, "N1LIMIT"));
        touchAreas.add(new TouchArea(0.2951f - 0.055f, 0.6569f - 0.025f, 0.2951f + 0.055f, 0.6569f + 0.025f, "FIX"));
        touchAreas.add(new TouchArea(0.7899f - 0.055f, 0.5331f - 0.025f, 0.7899f + 0.055f, 0.5331f + 0.025f, "MAN"));

        // Key Row 2 - from calibration 20:56 (PREV PAGE, NEXT PAGE, PREV MENU)
        touchAreas.add(new TouchArea(0.1655f - 0.055f, 0.7114f - 0.025f, 0.1655f + 0.055f, 0.7114f + 0.025f, "PREV PAGE"));
        touchAreas.add(new TouchArea(0.3011f - 0.055f, 0.7114f - 0.025f, 0.3011f + 0.055f, 0.7114f + 0.025f, "NEXT PAGE"));
        touchAreas.add(new TouchArea(0.8770f - 0.055f, 0.5331f - 0.025f, 0.8770f + 0.055f, 0.5331f + 0.025f, "PREV MENU"));

        // Letters A-E - from calibration 20:56
        touchAreas.add(new TouchArea(0.4564f - 0.045f, 0.6619f - 0.025f, 0.4564f + 0.045f, 0.6619f + 0.025f, "A"));
        touchAreas.add(new TouchArea(0.5504f - 0.045f, 0.6594f - 0.025f, 0.5504f + 0.045f, 0.6594f + 0.025f, "B"));
        touchAreas.add(new TouchArea(0.6543f - 0.045f, 0.6619f - 0.025f, 0.6543f + 0.045f, 0.6619f + 0.025f, "C"));
        touchAreas.add(new TouchArea(0.7483f - 0.045f, 0.6594f - 0.025f, 0.7483f + 0.045f, 0.6594f + 0.025f, "D"));
        touchAreas.add(new TouchArea(0.8473f - 0.045f, 0.6619f - 0.025f, 0.8473f + 0.045f, 0.6619f + 0.025f, "E"));

        // Letters F-J - from calibration 20:56
        touchAreas.add(new TouchArea(0.4564f - 0.045f, 0.7151f - 0.025f, 0.4564f + 0.045f, 0.7151f + 0.025f, "F"));
        touchAreas.add(new TouchArea(0.5524f - 0.045f, 0.7201f - 0.025f, 0.5524f + 0.045f, 0.7201f + 0.025f, "G"));
        touchAreas.add(new TouchArea(0.6543f - 0.045f, 0.7213f - 0.025f, 0.6543f + 0.045f, 0.7213f + 0.025f, "H"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.7201f - 0.025f, 0.7503f + 0.045f, 0.7201f + 0.025f, "I"));
        touchAreas.add(new TouchArea(0.8433f - 0.045f, 0.7176f - 0.025f, 0.8433f + 0.045f, 0.7176f + 0.025f, "J"));

        // Numbers 1-3 + K-O - from calibration 20:56
        touchAreas.add(new TouchArea(0.1487f - 0.045f, 0.7801f - 0.025f, 0.1487f + 0.045f, 0.7801f + 0.025f, "1"));
        touchAreas.add(new TouchArea(0.2486f - 0.045f, 0.7764f - 0.025f, 0.2486f + 0.045f, 0.7764f + 0.025f, "2"));
        touchAreas.add(new TouchArea(0.3525f - 0.045f, 0.7746f - 0.025f, 0.3525f + 0.045f, 0.7746f + 0.025f, "3"));
        touchAreas.add(new TouchArea(0.4525f - 0.045f, 0.7764f - 0.025f, 0.4525f + 0.045f, 0.7764f + 0.025f, "K"));
        touchAreas.add(new TouchArea(0.5504f - 0.045f, 0.7801f - 0.025f, 0.5504f + 0.045f, 0.7801f + 0.025f, "L"));
        touchAreas.add(new TouchArea(0.6504f - 0.045f, 0.7746f - 0.025f, 0.6504f + 0.045f, 0.7746f + 0.025f, "M"));
        touchAreas.add(new TouchArea(0.7523f - 0.045f, 0.7746f - 0.025f, 0.7523f + 0.045f, 0.7746f + 0.025f, "N"));
        touchAreas.add(new TouchArea(0.8473f - 0.045f, 0.7801f - 0.025f, 0.8473f + 0.045f, 0.7801f + 0.025f, "O"));

        // Numbers 4-6 + P-T - from calibration 20:56
        touchAreas.add(new TouchArea(0.1516f - 0.045f, 0.8371f - 0.025f, 0.1516f + 0.045f, 0.8371f + 0.025f, "4"));
        touchAreas.add(new TouchArea(0.2486f - 0.045f, 0.8346f - 0.025f, 0.2486f + 0.045f, 0.8346f + 0.025f, "5"));
        touchAreas.add(new TouchArea(0.3466f - 0.045f, 0.8334f - 0.025f, 0.3466f + 0.045f, 0.8334f + 0.025f, "6"));
        touchAreas.add(new TouchArea(0.4525f - 0.045f, 0.8346f - 0.025f, 0.4525f + 0.045f, 0.8346f + 0.025f, "P"));
        touchAreas.add(new TouchArea(0.5544f - 0.045f, 0.8371f - 0.025f, 0.5544f + 0.045f, 0.8371f + 0.025f, "Q"));
        touchAreas.add(new TouchArea(0.6504f - 0.045f, 0.8371f - 0.025f, 0.6504f + 0.045f, 0.8371f + 0.025f, "R"));
        touchAreas.add(new TouchArea(0.7583f - 0.045f, 0.8384f - 0.025f, 0.7583f + 0.045f, 0.8384f + 0.025f, "S"));
        touchAreas.add(new TouchArea(0.8473f - 0.045f, 0.8346f - 0.025f, 0.8473f + 0.045f, 0.8346f + 0.025f, "T"));

        // Numbers 7-9 + U-Y - from calibration 20:56
        touchAreas.add(new TouchArea(0.1516f - 0.045f, 0.8984f - 0.025f, 0.1516f + 0.045f, 0.8984f + 0.025f, "7"));
        touchAreas.add(new TouchArea(0.2555f - 0.045f, 0.8928f - 0.025f, 0.2555f + 0.045f, 0.8928f + 0.025f, "8"));
        touchAreas.add(new TouchArea(0.3486f - 0.045f, 0.8928f - 0.025f, 0.3486f + 0.045f, 0.8928f + 0.025f, "9"));
        touchAreas.add(new TouchArea(0.4564f - 0.045f, 0.8972f - 0.025f, 0.4564f + 0.045f, 0.8972f + 0.025f, "U"));
        touchAreas.add(new TouchArea(0.5524f - 0.045f, 0.8928f - 0.025f, 0.5524f + 0.045f, 0.8928f + 0.025f, "V"));
        touchAreas.add(new TouchArea(0.6484f - 0.045f, 0.8928f - 0.025f, 0.6484f + 0.045f, 0.8928f + 0.025f, "W"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.8959f - 0.025f, 0.7503f + 0.045f, 0.8959f + 0.025f, "X"));
        touchAreas.add(new TouchArea(0.8522f - 0.045f, 0.8959f - 0.025f, 0.8522f + 0.045f, 0.8959f + 0.025f, "Y"));

        // Bottom Row - from calibration 20:56
        touchAreas.add(new TouchArea(0.1516f - 0.045f, 0.9541f - 0.025f, 0.1516f + 0.045f, 0.9541f + 0.025f, "."));
        touchAreas.add(new TouchArea(0.2506f - 0.045f, 0.9492f - 0.025f, 0.2506f + 0.045f, 0.9492f + 0.025f, "0"));
        touchAreas.add(new TouchArea(0.3466f - 0.045f, 0.9504f - 0.025f, 0.3466f + 0.045f, 0.9504f + 0.025f, "+/-"));
        touchAreas.add(new TouchArea(0.4564f - 0.045f, 0.9554f - 0.025f, 0.4564f + 0.045f, 0.9554f + 0.025f, "Z"));
        touchAreas.add(new TouchArea(0.5564f - 0.045f, 0.9554f - 0.025f, 0.5564f + 0.045f, 0.9554f + 0.025f, "SP"));
        touchAreas.add(new TouchArea(0.6543f - 0.045f, 0.9504f - 0.025f, 0.6543f + 0.045f, 0.9504f + 0.025f, "DEL"));
        touchAreas.add(new TouchArea(0.7503f - 0.045f, 0.9504f - 0.025f, 0.7503f + 0.045f, 0.9504f + 0.025f, "/"));
        touchAreas.add(new TouchArea(0.8522f - 0.045f, 0.9517f - 0.025f, 0.8522f + 0.045f, 0.9517f + 0.025f, "CLR"));
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

        // Draw UI elements (top bar)
        drawIcon(canvas, settingsIcon, settingsX, settingsY);
        drawIcon(canvas, closeIcon, closeX, closeY);
        drawFmcText(canvas);
        drawLedIndicator(canvas);

        // Flash effect
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
        if (soundPlayer != null) {
            soundPlayer.release();
            soundPlayer = null;
        }
    }
}
