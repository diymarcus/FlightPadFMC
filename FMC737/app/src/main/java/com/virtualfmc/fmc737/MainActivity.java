package com.virtualfmc.fmc737;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import com.neovisionaries.ws.client.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final String SERVICE_TYPE = "_flightpadfmc._tcp";
    private static final String PREFS_NAME = "FlightPadFMCPrefs";
    private static final String KEY_MODE = "connection_mode";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_SOUND_ENABLED = "sound_enabled";
    private static final String KEY_HAPTIC_ENABLED = "haptic_enabled";

    // Pre-parsed status colors
    private static final int COLOR_STATUS_CONNECTED = Color.parseColor("#00aa00");
    private static final int COLOR_STATUS_CONNECTING = Color.parseColor("#ff8c00");
    private static final int COLOR_STATUS_ERROR = Color.parseColor("#aa0000");
    private static final int COLOR_STATUS_DISCONNECTED = Color.parseColor("#cc0000");
    private static final int COLOR_STATUS_DEFAULT = Color.parseColor("#666666");

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private int retryCount = 0;
    private boolean isConnecting = false; // Safeguard flag

    private String serverIp = "";
    private int serverPort = 8765;
    private int connectionMode = 0;

    private TextView tvStatus;
    private CDUView  cduView;
    private WebSocket webSocket;
    private Handler  mainHandler;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean isDiscoveryActive = false;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable discoveryTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (connectionMode == 0) {
                Log.d(TAG, "Discovery timeout, attempting manual connection to " + serverIp);
                if (serverIp != null && !serverIp.isEmpty()) {
                    connectToServer();
                } else {
                    isConnecting = false;
                    updateStatus("Discovery timed out. Set IP manually.");
                }
            }
        }
    };

    private static final int CDU_ROWS = 14;
    private static final int CDU_COLS = 24;
    private final char[][] screenSymbols = new char[CDU_ROWS][CDU_COLS];
    private final int[][]  screenColors  = new int[CDU_ROWS][CDU_COLS];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== onCreate STARTED ===");

        setupCrashLogging();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            setContentView(R.layout.activity_main);
            Log.d(TAG, "Layout inflated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error inflating layout", e);
            return;
        }

        mainHandler = new Handler(Looper.getMainLooper());

        try {
            tvStatus    = findViewById(R.id.tv_status);
            cduView     = findViewById(R.id.cdu_view);

            loadSettings();
            resetScreen();

            cduView.setKeyPressListener(new CDUView.KeyPressListener() {
                @Override
                public void onKeyPress(String key) {
                    sendKeyPress(key);
                }

                @Override
                public void onUIAction(String action) {
                    handleUIAction(action);
                }
            });

            try {
                nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
                Log.d(TAG, "NSD Manager initialized");
            } catch (Exception e) {
                Log.w(TAG, "NSD not supported on this device", e);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                registerNetworkCallback();
            } else {
                Log.d(TAG, "WiFi auto-reconnect: Skipping on Android 4.4");
            }

            Log.d(TAG, "=== onCreate COMPLETE ===");
        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR in onCreate", e);
            updateStatus("Startup Error: " + e.getMessage());
        }
    }

    private void loadSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            connectionMode = prefs.getInt(KEY_MODE, 0);
            serverIp = prefs.getString(KEY_IP, "");
            serverPort = prefs.getInt(KEY_PORT, 8765);
            boolean soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true);
            boolean hapticEnabled = prefs.getBoolean(KEY_HAPTIC_ENABLED, true);

            if (cduView != null) {
                cduView.setSoundEnabled(soundEnabled);
                cduView.setHapticEnabled(hapticEnabled);
            }

            Log.d(TAG, "Settings loaded: IP=" + serverIp + ", Mode=" + connectionMode);
        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
        }
    }

    private void saveSettings(int mode, String ip, int port, boolean sound, boolean haptic) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_MODE, mode);
            editor.putString(KEY_IP, ip);
            editor.putInt(KEY_PORT, port);
            editor.putBoolean(KEY_SOUND_ENABLED, sound);
            editor.putBoolean(KEY_HAPTIC_ENABLED, haptic);
            editor.apply();

            connectionMode = mode;
            serverIp = ip;
            serverPort = port;
            retryCount = 0;

            if (cduView != null) {
                cduView.setSoundEnabled(sound);
                cduView.setHapticEnabled(haptic);
            }

            Log.d(TAG, "Settings saved: mode=" + mode + " ip=" + ip);
            startConnectionProcess();
        } catch (Exception e) {
            Log.e(TAG, "Error saving settings", e);
        }
    }

    private synchronized void startConnectionProcess() {
        Log.d(TAG, "Starting connection process...");

        // Prevent multiple simultaneous connection attempts
        if (isConnecting) {
            Log.d(TAG, "Connection already in progress, skipping");
            return;
        }
        isConnecting = true;

        // Stop any existing discovery
        stopDiscovery();

        // Close existing websocket — clear listeners first to prevent stale callbacks
        if (webSocket != null) {
            try {
                webSocket.clearListeners();
                webSocket.disconnect();
                webSocket = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing old websocket", e);
            }
        }

        mainHandler.removeCallbacksAndMessages(null);

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (connectionMode == 0) {
                    startDiscoveryProcess();
                } else {
                    connectToServer();
                }
            }
        }).start();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        final RadioButton rbAuto = dialogView.findViewById(R.id.rb_auto);
        final RadioButton rbManual = dialogView.findViewById(R.id.rb_manual);
        final EditText etIp = dialogView.findViewById(R.id.et_ip);
        final EditText etPort = dialogView.findViewById(R.id.et_port);
        final CheckBox cbSound = dialogView.findViewById(R.id.cb_sound);
        final CheckBox cbHaptic = dialogView.findViewById(R.id.cb_haptic);

        if (connectionMode == 0) rbAuto.setChecked(true); else rbManual.setChecked(true);
        etIp.setText(serverIp);
        etPort.setText(String.valueOf(serverPort));

        boolean soundEnabled = cduView != null && cduView.isSoundEnabled();
        cbSound.setChecked(soundEnabled);

        boolean hapticEnabled = cduView != null && cduView.isHapticEnabled();
        cbHaptic.setChecked(hapticEnabled);

        builder.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int mode = rbAuto.isChecked() ? 0 : 1;
                String ip = etIp.getText().toString();
                int port = serverPort;
                try { port = Integer.parseInt(etPort.getText().toString()); } catch (Exception e) {}
                boolean sound = cbSound.isChecked();
                boolean haptic = cbHaptic.isChecked();
                saveSettings(mode, ip, port, sound, haptic);
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void resetScreen() {
        for (int r = 0; r < CDU_ROWS; r++)
            for (int c = 0; c < CDU_COLS; c++) {
                screenSymbols[r][c] = ' ';
                screenColors[r][c]  = Color.WHITE;
            }
        cduView.updateScreen(screenSymbols, screenColors);
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) { updateStatus("Searching for server..."); }
            @Override
            @SuppressWarnings("deprecation")
            public void onServiceFound(NsdServiceInfo service) {
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int err) {
                        Log.w(TAG, "mDNS resolve failed: " + err);
                    }
                    @Override
                    public void onServiceResolved(NsdServiceInfo si) {
                        mainHandler.removeCallbacks(discoveryTimeoutRunnable);
                        serverIp = si.getHost().getHostAddress();
                        serverPort = si.getPort();
                        stopDiscovery();
                        retryCount = 0;
                        isConnecting = false;  // Reset flag before connectToServer
                        connectToServer();
                    }
                });
            }
            @Override
            public void onServiceLost(NsdServiceInfo service) {}
            @Override
            public void onDiscoveryStopped(String regType) { isDiscoveryActive = false; }
            @Override
            public void onStartDiscoveryFailed(String regType, int err) {
                isDiscoveryActive = false;
                isConnecting = false;
                handleFailure("Discovery failed");
            }
            @Override
            public void onStopDiscoveryFailed(String regType, int err) { isDiscoveryActive = false; }
        };
    }

    private synchronized void startDiscoveryProcess() {
        if (nsdManager == null) {
            updateStatus("mDNS not supported");
            isConnecting = false;
            connectToServer();
            return;
        }
        Log.d(TAG, "Starting mDNS discovery...");
        stopDiscovery();
        initializeDiscoveryListener();
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            isDiscoveryActive = true;
            mainHandler.postDelayed(discoveryTimeoutRunnable, 5000);
        } catch (Exception e) {
            Log.e(TAG, "mDNS failed", e);
            isConnecting = false;
            handleFailure("mDNS Error");
        }
    }

    private synchronized void stopDiscovery() {
        if (isDiscoveryActive && nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception e) {}
        }
        isDiscoveryActive = false;
    }

    private synchronized void connectToServer() {
        if (serverIp == null || serverIp.isEmpty()) {
            isConnecting = false;
            updateStatus("No server IP address set");
            return;
        }

        // Clean up any existing connection first — clear listeners to prevent stale callbacks
        if (webSocket != null) {
            try {
                webSocket.clearListeners();
                webSocket.disconnect();
                webSocket = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing old websocket", e);
            }
        }

        final String url = "ws://" + serverIp + ":" + serverPort;
        
        // Update status on UI thread with null check
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tvStatus != null) {
                    updateStatus("Connecting to " + serverIp + "...");
                }
            }
        });
        
        Log.d(TAG, "Connecting to: " + url);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    webSocket = new WebSocketFactory().createSocket(url, 5000);
                    webSocket.addListener(new WebSocketAdapter() {
                        @Override
                        public void onConnected(WebSocket ws, java.util.Map<String,java.util.List<String>> h) {
                            if (ws != webSocket) return; // Ignore old/replaced connections
                            isConnecting = false;
                            retryCount = 0;
                            updateStatus("Connected to " + serverIp);
                            
                            // Send aircraft type to server
                            try {
                                JSONObject identifyMsg = new JSONObject();
                                identifyMsg.put("type", "identify");
                                identifyMsg.put("aircraft", "737");  // This is the 737 app
                                ws.sendText(identifyMsg.toString());
                                Log.d(TAG, "Sent aircraft identification to server: 737");
                            } catch (Exception e) {
                                Log.e(TAG, "Error sending aircraft identification", e);
                            }
                        }
                        @Override
                        public void onTextMessage(WebSocket ws, String msg) {
                            if (ws != webSocket) return;
                            try {
                                JSONObject data = new JSONObject(msg);
                                if (data.getString("type").equals("screen_update")) {
                                    updateScreen(data.getJSONArray("screen"));
                                    
                                    // Handle LED state from server
                                    if (data.has("led")) {
                                        boolean ledOn = data.getBoolean("led");
                                        mainHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (cduView != null) {
                                                    cduView.setLedState(ledOn);
                                                }
                                            }
                                        });
                                    }
                                } else if (data.getString("type").equals("led_update")) {
                                    // Handle dedicated LED update
                                    final boolean ledOn = data.getBoolean("led_on");
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (cduView != null) {
                                                cduView.setLedState(ledOn);
                                            }
                                        }
                                    });
                                } else if (data.getString("type").equals("exec_led")) {
                                    // EXEC LED update (matches FMC777 handler)
                                    final boolean ledOn = data.getBoolean("value");
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (cduView != null) cduView.setLedState(ledOn);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing message", e);
                            }
                        }
                        @Override
                        public void onDisconnected(WebSocket ws, WebSocketFrame s, WebSocketFrame c, boolean b) {
                            if (ws != webSocket) return;
                            Log.d(TAG, "WebSocket disconnected");
                            handleFailure("Disconnected");
                        }
                        @Override
                        public void onError(WebSocket ws, WebSocketException e) {
                            if (ws != webSocket) return;
                            Log.e(TAG, "WebSocket error: " + e.getMessage(), e);
                            handleFailure("Connection error");
                        }
                    });
                    webSocket.connect();
                } catch (Exception e) {
                    Log.e(TAG, "WebSocket exception: " + e.getMessage(), e);
                    handleFailure("Failed to connect");
                }
            }
        }).start();
    }

    private void handleFailure(String reason) {
        isConnecting = false;
        webSocket = null;
        retryCount++;
        Log.d(TAG, "Connection failed (attempt " + retryCount + "): " + reason);

        mainHandler.removeCallbacksAndMessages(null);

        if (retryCount <= MAX_RETRY_ATTEMPTS) {
            // Phase 1 — fast retries every 3 seconds
            updateStatus(reason + " - retrying (" + retryCount + "/" + MAX_RETRY_ATTEMPTS + ")...");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() { startConnectionProcess(); }
            }, 3000);
        } else {
            // Phase 2 — slow retries every 30 seconds indefinitely
            updateStatus("Waiting for server...");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() { startConnectionProcess(); }
            }, 30000);
        }
    }

    private void updateStatus(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tvStatus != null) {
                    if (msg.contains("Connected")) {
                        tvStatus.setTextColor(COLOR_STATUS_CONNECTED);
                    } else if (msg.contains("Connecting") || msg.contains("retrying")) {
                        tvStatus.setTextColor(COLOR_STATUS_CONNECTING);
                    } else if (msg.contains("unreachable") || msg.contains("error") || msg.contains("Failed")) {
                        tvStatus.setTextColor(COLOR_STATUS_ERROR);
                    } else if (msg.contains("Disconnected")) {
                        tvStatus.setTextColor(COLOR_STATUS_DISCONNECTED);
                    } else {
                        tvStatus.setTextColor(COLOR_STATUS_DEFAULT);
                    }
                    tvStatus.setText(msg);
                }
            }
        });
    }

    private void updateScreen(JSONArray screen) {
        try {
            for (int r = 0; r < screen.length() && r < CDU_ROWS; r++) {
                JSONArray rd = screen.getJSONArray(r);
                for (int c = 0; c < rd.length() && c < CDU_COLS; c++) {
                    JSONObject cell = rd.getJSONObject(c);
                    String s = cell.getString("s");
                    screenSymbols[r][c] = s.length() > 0 ? s.charAt(0) : ' ';
                    screenColors[r][c]  = getCduColor(cell.getInt("c"));
                }
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cduView.updateScreen(screenSymbols, screenColors);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error updating screen", e);
        }
    }

    private int getCduColor(int code) {
        switch (code) {
            case 1:  return Color.CYAN;
            case 2:  return Color.GREEN;
            case 3:  return Color.MAGENTA;
            case 4:  return Color.rgb(255, 191, 0);
            case 5:  return Color.RED;
            default: return Color.WHITE;
        }
    }

    private void sendKeyPress(String key) {
        if (webSocket != null && webSocket.isOpen()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "key_press");
                msg.put("key", key);
                webSocket.sendText(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending key press", e);
            }
        }
    }

    private void handleUIAction(String action) {
        if ("SETTINGS_BTN".equals(action))   showSettingsDialog();
        else if ("CLOSE_BTN".equals(action)) finish();
    }

    @Override protected void onPause() {
        super.onPause();
        // Disconnect fully when app goes to background — no background connection
        stopDiscovery();
        mainHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.clearListeners();
            webSocket.disconnect();
            webSocket = null;
        }
        isConnecting = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always reconnect fresh when returning to foreground
        retryCount = 0;
        isConnecting = false;
        startConnectionProcess();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            unregisterNetworkCallback();
        }
        if (webSocket != null) {
            webSocket.clearListeners();
            webSocket.disconnect();
        }
        if (cduView != null) cduView.recycleSkin();
        System.gc();
    }

    @SuppressWarnings("NewApi")
    private void registerNetworkCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return;
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            NetworkRequest request = builder.build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    retryCount = 0;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startConnectionProcess();
                        }
                    }, 1000);
                }
                @Override
                public void onLost(Network network) {
                    retryCount = 0;
                    updateStatus("WiFi disconnected - waiting...");
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (NoClassDefFoundError | Exception e) {
            Log.e(TAG, "WiFi auto-reconnect not available: " + e.getMessage());
        }
    }

    @SuppressWarnings("NewApi")
    private void unregisterNetworkCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return;
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (NoClassDefFoundError | Exception e) {
            Log.e(TAG, "Failed to unregister network callback: " + e.getMessage());
        }
    }

    private void setupCrashLogging() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                        new java.io.File(getFilesDir(), "crash_log.txt"), true);
                    java.io.PrintWriter pw = new java.io.PrintWriter(fw);
                    pw.println("=== CRASH: " + new java.util.Date() + " ===");
                    pw.println("Thread: " + thread.getName());
                    pw.println("Exception: " + throwable.toString());
                    pw.println(android.util.Log.getStackTraceString(throwable));
                    pw.println();
                    pw.close();
                    fw.close();
                } catch (java.io.IOException e) {
                    Log.e(TAG, "Failed to write crash log", e);
                }
                System.exit(1);
            }
        });
    }
}
