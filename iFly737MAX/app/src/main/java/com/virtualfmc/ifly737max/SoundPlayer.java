package com.virtualfmc.ifly737max;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

/**
 * Sound player for button press feedback.
 * Plays a short beep sound when valid CDU buttons are pressed.
 * Uses SoundPool for efficient short sound playback.
 * Features lazy loading - sound is loaded only on first button press.
 */
public class SoundPlayer {

    private static final String TAG = "SoundPlayer";
    private static SoundPlayer instance;

    private SoundPool soundPool;
    private int clickSoundId;
    private boolean soundLoaded = false;
    private boolean isInitializing = false;  // Prevent multiple init calls
    private float volume = 0.5f;  // 50% volume
    private Context appContext = null;  // Store context for lazy initialization

    private SoundPlayer() {
        // Private constructor for singleton
    }

    /**
     * Get singleton instance
     */
    public static synchronized SoundPlayer getInstance() {
        if (instance == null) {
            instance = new SoundPlayer();
        }
        return instance;
    }

    /**
     * Initialize the sound player with the button press sound
     * Uses lazy loading - call this early but sound loads on first play
     * @param context Application context
     * @return true if initialization successful
     */
    @SuppressWarnings("deprecation")
    public boolean initialize(Context context) {
        if (soundPool != null) {
            return true;  // Already initialized
        }

        // Store application context for lazy loading
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }

        try {
            // Create SoundPool - use Builder for API 21+, deprecated constructor for API 19-20
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
                soundPool = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(audioAttributes)
                    .build();
            } else {
                soundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
            }

            // Load the sound from res/raw/button_click.wav
            clickSoundId = soundPool.load(appContext, R.raw.button_click, 1);

            // Ensure sound is ready before playing
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                    if (status == 0) { // 0 = Success
                        soundLoaded = true;
                        isInitializing = false;
                        Log.d(TAG, "Sound loaded successfully, soundId: " + clickSoundId);
                        // Play the sound immediately on first load (for the first button press that triggered loading)
                        soundPool.play(clickSoundId, volume, volume, 1, 0, 1.0f);
                    } else {
                        isInitializing = false;
                        Log.e(TAG, "Sound load failed, status: " + status);
                    }
                }
            });

            Log.d(TAG, "SoundPool initialized");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing sound player", e);
            return false;
        }
    }

    /**
     * Play the button press sound
     * Lazy loading: initializes SoundPool on first call
     * Call this only when a valid button area is pressed
     */
    public void playButtonClick() {
        // Lazy initialization - load sound on first use
        if (!isInitializing && !soundLoaded) {
            isInitializing = true;
            Log.d(TAG, "Lazy initializing SoundPool on first button press");
            if (appContext != null) {
                // Initialize and play when loaded
                initialize(appContext);
                // Sound will play in OnLoadCompleteListener when ready
                return;
            }
        }

        if (soundLoaded && clickSoundId > 0) {
            // Play sound: volume left, volume right, priority, loop (0 = no), rate (1.0 = normal)
            soundPool.play(clickSoundId, volume, volume, 1, 0, 1.0f);
        }
        // If isInitializing is true but not loaded yet, silently skip (will play on next press)
    }

    /**
     * Set the volume for button press sounds
     * @param volume Volume level (0.0f to 1.0f)
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * Release resources
     */
    public void release() {
        if (soundPool != null) {
            try {
                soundPool.release();
                soundPool = null;
                soundLoaded = false;
                clickSoundId = 0;
                isInitializing = false;
                Log.d(TAG, "SoundPool released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing SoundPool", e);
            }
        }
        // Don't null the singleton instance - just reset state
        // This prevents NPE if getInstance() is called after release()
    }

    /**
     * Check if sound player is ready to play sounds
     */
    public boolean isReady() {
        return soundLoaded && clickSoundId > 0;
    }
}
