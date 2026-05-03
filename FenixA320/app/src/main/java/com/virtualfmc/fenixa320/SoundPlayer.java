package com.virtualfmc.fenixa320;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

public class SoundPlayer {

    private static final String TAG = "SoundPlayer";
    private static SoundPlayer instance;

    private SoundPool soundPool;
    private int clickSoundId;
    private boolean soundLoaded = false;
    private boolean isInitializing = false;
    private float volume = 0.5f;
    private Context appContext = null;

    private SoundPlayer() {}

    public static synchronized SoundPlayer getInstance() {
        if (instance == null) {
            instance = new SoundPlayer();
        }
        return instance;
    }

    @SuppressWarnings("deprecation")
    public boolean initialize(Context context) {
        if (soundPool != null) return true;

        if (appContext == null) {
            appContext = context.getApplicationContext();
        }

        try {
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

            clickSoundId = soundPool.load(appContext, R.raw.button_click, 1);

            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                    if (status == 0) {
                        soundLoaded = true;
                        isInitializing = false;
                        soundPool.play(clickSoundId, volume, volume, 1, 0, 1.0f);
                    } else {
                        isInitializing = false;
                        Log.e(TAG, "Sound load failed, status: " + status);
                    }
                }
            });

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing sound player", e);
            return false;
        }
    }

    public void playButtonClick() {
        if (!isInitializing && !soundLoaded) {
            isInitializing = true;
            if (appContext != null) {
                initialize(appContext);
                return;
            }
        }
        if (soundLoaded && clickSoundId > 0) {
            soundPool.play(clickSoundId, volume, volume, 1, 0, 1.0f);
        }
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public void release() {
        if (soundPool != null) {
            try {
                soundPool.release();
                soundPool = null;
                soundLoaded = false;
                clickSoundId = 0;
                isInitializing = false;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing SoundPool", e);
            }
        }
    }

    public boolean isReady() {
        return soundLoaded && clickSoundId > 0;
    }
}
