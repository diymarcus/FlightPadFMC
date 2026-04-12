package com.virtualfmc.fbwa320;

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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FlightPadFMC — FlyByWire A320 MCDU for Android
 *
 * Connects to FlyByWire SimBridge WebSocket:
 *   ws://[server_ip]:8380/interfaces/v1/mcdu
 *
 * Protocol (verified from SimBridge source):
 *
 *   KEY INPUT  (client → sim):   plain text  "event:left:KEY_NAME"
 *   SCREEN UPDATE (sim → client): "update:{JSON}"
 *     JSON = { "left": { "lines":[[l,c,r]×12], "title":"", "titleLeft":"",
 *                        "page":"", "scratchpad":"", "arrows":[],
 *                        "annunciators":{fm1,ind,rdy,blank,fm2,...} } }
 *   Inline tags in strings: {amber}{green}{cyan}{white}{magenta}{red}{yellow}
 *                            {big}{small}{sp}{end}{left}{right}{inop}
 */
public class MainActivity extends Activity {

    private static final String TAG          = "MainActivity";
    private static final String SERVICE_TYPE = "_flightpadfmc._tcp";
    private static final String PREFS_NAME   = "FBWMCDUPrefs";
    private static final String KEY_MODE     = "connection_mode";
    private static final String KEY_IP       = "server_ip";
    private static final String KEY_PORT     = "server_port";
    private static final String KEY_SOUND_ENABLED  = "sound_enabled";
    private static final String KEY_HAPTIC_ENABLED = "haptic_enabled";

    private static final int DEFAULT_PORT       = 8765;
    private static final int MAX_RETRY_ATTEMPTS = 5;

    // Status bar colors
    private static final int COLOR_CONNECTED    = Color.parseColor("#00aa00");
    private static final int COLOR_CONNECTING   = Color.parseColor("#ff8c00");
    private static final int COLOR_ERROR        = Color.parseColor("#aa0000");
    private static final int COLOR_DISCONNECTED = Color.parseColor("#cc0000");
    private static final int COLOR_DEFAULT      = Color.parseColor("#666666");

    private String  serverIp      = "";
    private int     serverPort    = DEFAULT_PORT;
    private int     connectionMode = 0;  // 0=auto (mDNS), 1=manual
    private int     retryCount    = 0;
    private boolean isConnecting  = false;

    private TextView tvStatus;
    private MCDUView mcduView;
    private WebSocket webSocket;
    private Handler mainHandler;

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
                    updateStatus("Discovery timed out. Set IP manually.", COLOR_DEFAULT);
                }
            }
        }
    };

    // Screen buffers — 14 rows × 24 cols
    // Row 0 = title row, rows 1-12 = lines[0-11], row 13 = scratchpad
    private static final int MCDU_ROWS = 14;
    private static final int MCDU_COLS = 24;
    private final char[][]    screenSymbols = new char[MCDU_ROWS][MCDU_COLS];
    private final int[][]     screenColors  = new int[MCDU_ROWS][MCDU_COLS];
    private final boolean[][] screenSmall   = new boolean[MCDU_ROWS][MCDU_COLS];

    // ─── Tagged segment parser ────────────────────────────────────────────────

    private static final int ALIGN_LEFT   = 0;
    private static final int ALIGN_CENTER = 1;
    private static final int ALIGN_RIGHT  = 2;

    /** One run of contiguous characters with uniform color, size, and alignment. */
    private static class TaggedSegment {
        final String  text;
        final int     color;
        final boolean small;
        final int     align;
        TaggedSegment(String t, int c, boolean s, int a) { text = t; color = c; small = s; align = a; }
    }

    /**
     * Walk a SimBridge MCDU string and emit a list of segments.
     * Color and size tags ({green}, {small}, etc.) cause segment splits so each
     * run renders with its own attributes (fixes multi-color text).
     * Alignment tags ({left}, {right}, {center}) override the segment's
     * alignment from that point onward in the string (fixes inline-right bug).
     * {end} is intentionally ignored — there is no real tag stack in the
     * SimBridge wire format; FBW always re-asserts color before each colored run.
     */
    private List<TaggedSegment> parseTaggedSegments(String s, boolean defaultSmall, int defaultAlign) {
        List<TaggedSegment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        int     color = Color.WHITE;
        boolean small = defaultSmall;
        int     align = defaultAlign;
        StringBuilder sb = new StringBuilder();

        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '{') {
                int end = s.indexOf('}', i);
                if (end > i) {
                    String tag = s.substring(i + 1, end).toLowerCase();

                    int     newColor = color;
                    boolean newSmall = small;
                    int     newAlign = align;

                    switch (tag) {
                        case "amber":   newColor = Color.rgb(255, 160, 0); break;
                        case "red":     newColor = Color.RED; break;
                        case "green":   newColor = Color.GREEN; break;
                        case "cyan":    newColor = Color.CYAN; break;
                        case "white":   newColor = Color.WHITE; break;
                        case "magenta": newColor = Color.MAGENTA; break;
                        case "yellow":  newColor = Color.YELLOW; break;
                        case "blue":    newColor = Color.rgb(100, 180, 255); break;
                        case "small":   newSmall = true; break;
                        case "big":     newSmall = false; break;
                        case "left":    newAlign = ALIGN_LEFT;   break;
                        case "right":   newAlign = ALIGN_RIGHT;  break;
                        case "center":  newAlign = ALIGN_CENTER; break;
                        case "sp":      sb.append(' '); break;
                        case "end":     break; // no-op, no tag stack
                        default:        break; // unknown tag (e.g. inop) — ignore
                    }

                    boolean changed = (newColor != color) || (newSmall != small) || (newAlign != align);
                    if (changed && sb.length() > 0) {
                        out.add(new TaggedSegment(sb.toString(), color, small, align));
                        sb.setLength(0);
                    }
                    color = newColor;
                    small = newSmall;
                    align = newAlign;

                    i = end + 1;
                } else {
                    sb.append(ch);
                    i++;
                }
            } else {
                sb.append(ch);
                i++;
            }
        }

        if (sb.length() > 0) {
            out.add(new TaggedSegment(sb.toString(), color, small, align));
        }
        return out;
    }

    /**
     * Render an MCDU row from up to 3 slot strings. Each slot has a default
     * alignment, but inline {left}/{right}/{center} tags inside any slot
     * override that on a per-segment basis. All segments are then bucketed
     * by their final alignment and placed: left segments stack from col 0,
     * right segments stack so the last char ends at MCDU_COLS-1, center
     * segments are centered.
     */
    private void renderRow(int row,
                           String leftStr,   int leftDefault,
                           String centerStr, int centerDefault,
                           String rightStr,  int rightDefault,
                           boolean defaultSmall) {
        List<TaggedSegment> all = new ArrayList<>();
        all.addAll(parseTaggedSegments(leftStr,   defaultSmall, leftDefault));
        all.addAll(parseTaggedSegments(centerStr, defaultSmall, centerDefault));
        all.addAll(parseTaggedSegments(rightStr,  defaultSmall, rightDefault));

        List<TaggedSegment> leftSegs   = new ArrayList<>();
        List<TaggedSegment> rightSegs  = new ArrayList<>();
        List<TaggedSegment> centerSegs = new ArrayList<>();
        for (TaggedSegment seg : all) {
            if (seg.text.isEmpty()) continue;
            if      (seg.align == ALIGN_LEFT)  leftSegs.add(seg);
            else if (seg.align == ALIGN_RIGHT) rightSegs.add(seg);
            else                                centerSegs.add(seg);
        }

        // Left-aligned: stack from col 0
        int col = 0;
        for (TaggedSegment seg : leftSegs) {
            placeText(row, col, seg.text, seg.color, seg.small);
            col += seg.text.length();
        }

        // Right-aligned: last char lands on col MCDU_COLS-1
        int totalRight = 0;
        for (TaggedSegment seg : rightSegs) totalRight += seg.text.length();
        col = Math.max(0, MCDU_COLS - totalRight);
        for (TaggedSegment seg : rightSegs) {
            placeText(row, col, seg.text, seg.color, seg.small);
            col += seg.text.length();
        }

        // Center-aligned: centered as a group
        int totalCenter = 0;
        for (TaggedSegment seg : centerSegs) totalCenter += seg.text.length();
        col = Math.max(0, (MCDU_COLS - totalCenter) / 2);
        for (TaggedSegment seg : centerSegs) {
            placeText(row, col, seg.text, seg.color, seg.small);
            col += seg.text.length();
        }
    }

    /** Write text into a screen row starting at col, with given color and size.
     *  Applies FBW MCDU font glyph remaps so B612 Mono shows the same visual
     *  characters the FBW HTML MCDU shows in the sim. */
    private void placeText(int row, int col, String text, int color, boolean small) {
        for (int i = 0; i < text.length() && col + i < MCDU_COLS; i++) {
            screenSymbols[row][col + i] = translateFbwChar(text.charAt(i));
            screenColors[row][col + i]  = color;
            screenSmall[row][col + i]   = small;
        }
    }

    /** Map FBW MCDU wire characters to their visible glyphs in B612 Mono.
     *  FBW's in-sim MCDU uses a custom HTML font that remaps a few ASCII
     *  codepoints to cockpit-style glyphs (e.g. '|' is drawn as a thick
     *  slash). We translate those codepoints so the Android app matches. */
    private char translateFbwChar(char c) {
        switch (c) {
            case '|': return '/';   // FBW font: '|' glyph is a slash
            default:  return c;
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupCrashLogging();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            setContentView(R.layout.activity_main);
        } catch (Exception e) {
            Log.e(TAG, "Error inflating layout", e);
            return;
        }

        mainHandler = new Handler(Looper.getMainLooper());

        try {
            tvStatus = findViewById(R.id.tv_status);
            mcduView = findViewById(R.id.mcdu_view);

            loadSettings();
            resetScreen();

            mcduView.setKeyPressListener(new MCDUView.KeyPressListener() {
                @Override public void onKeyPress(String key)    { sendKeyPress(key); }
                @Override public void onUIAction(String action) { handleUIAction(action); }
            });

            try {
                nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
                Log.d(TAG, "NSD Manager initialized");
            } catch (Exception e) {
                Log.w(TAG, "NSD not supported on this device", e);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                registerNetworkCallback();
            }

            Log.d(TAG, "=== onCreate COMPLETE ===");
        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR in onCreate", e);
            updateStatus("Startup error: " + e.getMessage(), COLOR_ERROR);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-register WiFi callback + reconnect fresh when returning to foreground
        registerNetworkCallback();
        retryCount   = 0;
        isConnecting = false;
        startConnectionProcess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // CRITICAL: unregister NetworkCallback FIRST so onAvailable() can't fire
        // after we've disconnected and re-trigger startConnectionProcess() while
        // the app is minimized. Without this, WiFi state changes would cause the
        // paused app to silently reconnect to the server in the background.
        unregisterNetworkCallback();
        stopAndDisconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAndDisconnect();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            unregisterNetworkCallback();
        }
        if (mcduView != null) mcduView.recycleSkin();
        System.gc();
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        connectionMode = prefs.getInt(KEY_MODE, 0);
        serverIp       = prefs.getString(KEY_IP, "");
        serverPort     = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        boolean sound  = prefs.getBoolean(KEY_SOUND_ENABLED,  true);
        boolean haptic = prefs.getBoolean(KEY_HAPTIC_ENABLED, true);
        if (mcduView != null) {
            mcduView.setSoundEnabled(sound);
            mcduView.setHapticEnabled(haptic);
        }
    }

    private void saveSettings(int mode, String ip, int port, boolean sound, boolean haptic) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt(KEY_MODE, mode);
        ed.putString(KEY_IP, ip);
        ed.putInt(KEY_PORT, port);
        ed.putBoolean(KEY_SOUND_ENABLED,  sound);
        ed.putBoolean(KEY_HAPTIC_ENABLED, haptic);
        ed.apply();
        connectionMode = mode;
        serverIp       = ip;
        serverPort     = port;
        retryCount     = 0;
        if (mcduView != null) {
            mcduView.setSoundEnabled(sound);
            mcduView.setHapticEnabled(haptic);
        }
        startConnectionProcess();
    }

    // ─── Connection ───────────────────────────────────────────────────────────

    private synchronized void startConnectionProcess() {
        if (isConnecting) return;
        isConnecting = true;
        stopDiscovery();
        if (webSocket != null) {
            try { webSocket.clearListeners(); webSocket.disconnect(); webSocket = null; } catch (Exception e) {}
        }
        mainHandler.removeCallbacksAndMessages(null);
        new Thread(new Runnable() {
            @Override public void run() {
                if (connectionMode == 0) {
                    startDiscoveryProcess();
                } else {
                    connectToServer();
                }
            }
        }).start();
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) { updateStatus("Searching for server...", COLOR_CONNECTING); }
            @Override
            @SuppressWarnings("deprecation")
            public void onServiceFound(NsdServiceInfo service) {
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo si, int err) {
                        Log.w(TAG, "mDNS resolve failed: " + err);
                    }
                    @Override public void onServiceResolved(NsdServiceInfo si) {
                        mainHandler.removeCallbacks(discoveryTimeoutRunnable);
                        serverIp   = si.getHost().getHostAddress();
                        serverPort = si.getPort();
                        stopDiscovery();
                        retryCount   = 0;
                        isConnecting = false;
                        connectToServer();
                    }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {}
            @Override public void onDiscoveryStopped(String regType) { isDiscoveryActive = false; }
            @Override public void onStartDiscoveryFailed(String regType, int err) {
                isDiscoveryActive = false;
                isConnecting      = false;
                handleFailure("Discovery failed");
            }
            @Override public void onStopDiscoveryFailed(String regType, int err) { isDiscoveryActive = false; }
        };
    }

    private synchronized void startDiscoveryProcess() {
        if (nsdManager == null) {
            isConnecting = false;
            connectToServer();
            return;
        }
        stopDiscovery();
        initializeDiscoveryListener();
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            isDiscoveryActive = true;
            // 15 s window — NsdManager warm-up + router multicast propagation can
            // easily take 5-8 s on first query, so 5 s was too tight and always
            // fell through to the manual-IP fallback before discovery resolved.
            mainHandler.postDelayed(discoveryTimeoutRunnable, 15000);
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
            updateStatus("No IP \u2014 tap \u2699 to configure", COLOR_DEFAULT);
            return;
        }

        if (webSocket != null) {
            try { webSocket.clearListeners(); webSocket.disconnect(); webSocket = null; } catch (Exception e) {}
        }

        final String url = "ws://" + serverIp + ":" + serverPort;
        mainHandler.post(new Runnable() {
            @Override public void run() { updateStatus("\u21ba Connecting...", COLOR_CONNECTING); }
        });
        Log.d(TAG, "Connecting: " + url);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    webSocket = new WebSocketFactory().createSocket(url, 5000);
                    webSocket.addListener(new WebSocketAdapter() {
                        @Override
                        public void onConnected(WebSocket ws, java.util.Map<String, java.util.List<String>> h) {
                            if (ws != webSocket) return;
                            isConnecting = false;
                            retryCount   = 0;
                            updateStatus("\u25cf Connected", COLOR_CONNECTED);
                            Log.d(TAG, "Server connected");
                            // Identify as A320 to the server
                            try {
                                JSONObject identify = new JSONObject();
                                identify.put("type", "identify");
                                identify.put("aircraft", "A320");
                                ws.sendText(identify.toString());
                                Log.d(TAG, "Sent identify: A320");
                            } catch (Exception e) {
                                Log.e(TAG, "Error sending identify", e);
                            }
                        }

                        @Override
                        public void onTextMessage(WebSocket ws, String msg) {
                            if (ws != webSocket) return;
                            parseSimBridgeMessage(msg);
                        }

                        @Override
                        public void onDisconnected(WebSocket ws, WebSocketFrame s, WebSocketFrame c, boolean b) {
                            if (ws != webSocket) return;
                            Log.d(TAG, "SimBridge disconnected");
                            ledsOff();
                            handleFailure("Disconnected");
                        }

                        @Override
                        public void onError(WebSocket ws, WebSocketException e) {
                            if (ws != webSocket) return;
                            Log.e(TAG, "WS error: " + e.getMessage());
                            handleFailure("Connection error");
                        }
                    });
                    webSocket.connect();
                } catch (Exception e) {
                    Log.e(TAG, "WS exception: " + e.getMessage(), e);
                    handleFailure("Failed to connect");
                }
            }
        }).start();
    }

    private void handleFailure(String reason) {
        isConnecting = false;
        webSocket    = null;
        retryCount++;
        mainHandler.removeCallbacksAndMessages(null);
        ledsOff();

        if (retryCount <= MAX_RETRY_ATTEMPTS) {
            updateStatus("\u21ba Retry " + retryCount + "/" + MAX_RETRY_ATTEMPTS + "  " + reason, COLOR_CONNECTING);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { startConnectionProcess(); }
            }, 3000);
        } else {
            updateStatus("\u25cb Waiting for SimBridge...", COLOR_DEFAULT);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { startConnectionProcess(); }
            }, 30000);
        }
    }

    private void ledsOff() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (mcduView == null) return;
                for (int i = 0; i < 5; i++) mcduView.setLedState(i, false);
            }
        });
    }

    // ─── SimBridge Message Parsing ────────────────────────────────────────────

    /**
     * Parse a SimBridge WebSocket message.
     *
     * Format (verified from SimBridge source):
     *   "update:{JSON}"  — screen update
     *   "print:{JSON}"   — printer relay (ignore)
     *
     * JSON structure:
     *   { "left": {
     *       "lines": [ [leftStr, centerStr, rightStr], ... ],  // 12 entries
     *       "title": "{cyan}TITLE{end}",
     *       "titleLeft": "",
     *       "page": "{small}1/3{end}",
     *       "scratchpad": "text",
     *       "arrows": [up, down, left, right],
     *       "annunciators": { "fm1":bool, "ind":bool, "rdy":bool,
     *                         "blank":bool, "fm2":bool, ... }
     *     }
     *   }
     *
     * Screen row mapping:
     *   Row  0 → title + titleLeft + page
     *   Rows 1–12 → lines[0..11]  (even=label/small, odd=data/large)
     *   Row 13 → scratchpad
     */
    private void parseSimBridgeMessage(String message) {
        try {
            if (message.startsWith("print:")) return;

            String jsonStr = message.startsWith("update:") ? message.substring(7) : message;

            // Debug: full raw JSON payload — capture via `adb logcat -s FBWA320`
            // to see exactly what FBW SimBridge is sending for each page.
            Log.d(TAG, "SimBridge RAW: " + jsonStr);

            JSONObject root = new JSONObject(jsonStr);

            if (!root.has("left")) {
                Log.w(TAG, "No 'left' in message: " + message.substring(0, Math.min(80, message.length())));
                return;
            }

            JSONObject left = root.getJSONObject("left");

            // Clear screen buffers
            for (int r = 0; r < MCDU_ROWS; r++)
                for (int c = 0; c < MCDU_COLS; c++) {
                    screenSymbols[r][c] = ' ';
                    screenColors[r][c]  = Color.WHITE;
                    screenSmall[r][c]   = false;
                }

            // Row 0: title row — titleLeft (left), page (center), title (right) per FBW MCDU layout
            String titleLeft = left.optString("titleLeft", "");
            String title     = left.optString("title",     "");
            String page      = left.optString("page",      "");
            renderRow(0,
                      titleLeft, ALIGN_LEFT,
                      page,      ALIGN_CENTER,
                      title,     ALIGN_RIGHT,
                      false);

            // Slew arrows on title row — indicate which CDU nav keys are valid
            // on the current page. FBW sends arrows as [up, down, left, right]
            // booleans. On the real A320 MCDU these appear at the top-right,
            // e.g. ← → on INIT A/B for lateral page slewing.
            JSONArray arrows = left.optJSONArray("arrows");
            if (arrows != null && arrows.length() >= 4) {
                boolean upA    = arrows.optBoolean(0, false);
                boolean downA  = arrows.optBoolean(1, false);
                boolean leftA  = arrows.optBoolean(2, false);
                boolean rightA = arrows.optBoolean(3, false);

                // Place from the rightmost column leftwards so the right arrow
                // always lands on the edge. Arrows are drawn white.
                int ac = MCDU_COLS - 1;
                if (rightA) { placeText(0, ac--, "\u2192", Color.WHITE, false); } // →
                if (leftA)  { placeText(0, ac--, "\u2190", Color.WHITE, false); } // ←
                if (downA)  { placeText(0, ac--, "\u2193", Color.WHITE, false); } // ↓
                if (upA)    { placeText(0, ac--, "\u2191", Color.WHITE, false); } // ↑
            }

            // Rows 1–12: lines[0..11]
            // Even indices = label rows (small text), odd = data rows (large)
            JSONArray lines = left.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < Math.min(lines.length(), 12); i++) {
                    boolean labelRow = (i % 2 == 0);
                    Object lineObj = lines.get(i);
                    String ls = "", cs = "", rs = "";
                    if (lineObj instanceof JSONArray) {
                        JSONArray a = (JSONArray) lineObj;
                        // SimBridge MCDU line array order: [left, right, center]
                        ls = a.length() > 0 ? a.optString(0, "") : "";
                        rs = a.length() > 1 ? a.optString(1, "") : "";
                        cs = a.length() > 2 ? a.optString(2, "") : "";
                    } else if (lineObj instanceof String) {
                        ls = (String) lineObj;
                    }
                    renderRow(1 + i,
                              ls, ALIGN_LEFT,
                              cs, ALIGN_CENTER,
                              rs, ALIGN_RIGHT,
                              labelRow);
                }
            }

            // Row 13: scratchpad — single string, default left-aligned
            renderRow(13,
                      left.optString("scratchpad", ""), ALIGN_LEFT,
                      "", ALIGN_CENTER,
                      "", ALIGN_RIGHT,
                      false);

            // Annunciators → LED states (0=FM1, 1=IND, 2=RDY, 3=--, 4=FM2)
            JSONObject ann = left.optJSONObject("annunciators");
            if (ann != null) {
                final boolean fm1   = ann.optBoolean("fm1",   false);
                final boolean ind   = ann.optBoolean("ind",   false);
                final boolean rdy   = ann.optBoolean("rdy",   false);
                final boolean blank = ann.optBoolean("blank", false);
                final boolean fm2   = ann.optBoolean("fm2",   false);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (mcduView == null) return;
                        mcduView.setLedState(0, fm1);
                        mcduView.setLedState(1, ind);
                        mcduView.setLedState(2, rdy);
                        mcduView.setLedState(3, blank);
                        mcduView.setLedState(4, fm2);
                    }
                });
            }

            // Copy buffers and post to UI
            final char[][]    sym = new char[MCDU_ROWS][MCDU_COLS];
            final int[][]     col = new int[MCDU_ROWS][MCDU_COLS];
            final boolean[][] sm  = new boolean[MCDU_ROWS][MCDU_COLS];
            for (int r = 0; r < MCDU_ROWS; r++) {
                System.arraycopy(screenSymbols[r], 0, sym[r], 0, MCDU_COLS);
                System.arraycopy(screenColors[r],  0, col[r], 0, MCDU_COLS);
                for (int c = 0; c < MCDU_COLS; c++) sm[r][c] = screenSmall[r][c];
            }
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (mcduView != null) mcduView.updateScreen(sym, col, sm);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            Log.d(TAG, "Raw: " + message.substring(0, Math.min(200, message.length())));
        }
    }

    // ─── Key Press ────────────────────────────────────────────────────────────

    /**
     * Send a key press to SimBridge.
     * Format (verified): plain text  "event:left:KEY_NAME"
     * CLR long-press:    "event:left:CLR_Held"
     */
    private void sendKeyPress(String key) {
        if (webSocket == null || !webSocket.isOpen()) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "key_press");
            msg.put("key", key);
            webSocket.sendText(msg.toString());
            Log.d(TAG, "Key sent to server: " + key);
        } catch (Exception e) {
            Log.e(TAG, "Key send error: " + key, e);
        }
    }

    // Key translation is handled server-side (A320_KEY_MAP in server_gui.py)

    // ─── UI ───────────────────────────────────────────────────────────────────

    private void resetScreen() {
        for (int r = 0; r < MCDU_ROWS; r++)
            for (int c = 0; c < MCDU_COLS; c++) {
                screenSymbols[r][c] = ' ';
                screenColors[r][c]  = Color.WHITE;
                screenSmall[r][c]   = false;
            }
        if (mcduView != null) mcduView.updateScreen(screenSymbols, screenColors, screenSmall);
    }

    /** Update the status bar. Always appends IP:port if configured. */
    private void updateStatus(final String label, final int color) {
        final String addr = (serverIp != null && !serverIp.isEmpty())
            ? "   " + serverIp + ":" + serverPort : "";
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (tvStatus == null) return;
                tvStatus.setTextColor(color);
                tvStatus.setText(label + addr);
            }
        });
    }

    private void handleUIAction(String action) {
        if ("SETTINGS_BTN".equals(action))   showSettingsDialog();
        else if ("CLOSE_BTN".equals(action)) finish();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        final RadioButton rbAuto   = dialogView.findViewById(R.id.rb_auto);
        final RadioButton rbManual = dialogView.findViewById(R.id.rb_manual);
        final EditText    etIp     = dialogView.findViewById(R.id.et_ip);
        final EditText    etPort   = dialogView.findViewById(R.id.et_port);
        final CheckBox    cbSound  = dialogView.findViewById(R.id.cb_sound);
        final CheckBox    cbHaptic = dialogView.findViewById(R.id.cb_haptic);
        if (connectionMode == 0) rbAuto.setChecked(true); else rbManual.setChecked(true);
        etIp.setText(serverIp);
        etPort.setText(String.valueOf(serverPort));
        cbSound.setChecked(mcduView != null && mcduView.isSoundEnabled());
        cbHaptic.setChecked(mcduView != null && mcduView.isHapticEnabled());

        builder.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int    mode = rbAuto.isChecked() ? 0 : 1;
                String ip   = etIp.getText().toString().trim();
                int    port = DEFAULT_PORT;
                try { port = Integer.parseInt(etPort.getText().toString()); } catch (Exception e) {}
                saveSettings(mode, ip, port, cbSound.isChecked(), cbHaptic.isChecked());
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        // NOTE: The "Calibrate LEDs Only" button (btn_calibrate_leds) and
        // its click handler were removed after calibration values were baked
        // into MCDUView.java defaults (2026-04-11). The XML element is
        // commented out in dialog_settings.xml — uncomment both that and
        // this handler if the skin ever needs recalibration.
        builder.show();
    }

    // ─── WiFi reconnect ───────────────────────────────────────────────────────

    @SuppressWarnings("NewApi")
    private void registerNetworkCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return;
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    retryCount = 0;
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() { startConnectionProcess(); }
                    }, 1000);
                }
                @Override
                public void onLost(Network network) {
                    retryCount = 0;
                    updateStatus("\u25cb WiFi disconnected...", COLOR_DISCONNECTED);
                }
            };
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "WiFi callback unavailable: " + e.getMessage());
        }
    }

    @SuppressWarnings("NewApi")
    private void unregisterNetworkCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return;
        try {
            if (connectivityManager != null && networkCallback != null)
                connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Unregister network callback failed: " + e.getMessage());
        }
    }

    private void stopAndDisconnect() {
        stopDiscovery();
        mainHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.clearListeners();
            webSocket.disconnect();
            webSocket = null;
        }
        isConnecting = false;
    }

    // ─── Crash logging ────────────────────────────────────────────────────────

    private void setupCrashLogging() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                        new java.io.File(getFilesDir(), "crash_log.txt"), true);
                    java.io.PrintWriter pw = new java.io.PrintWriter(fw);
                    pw.println("=== CRASH: " + new java.util.Date() + " ===");
                    pw.println(android.util.Log.getStackTraceString(throwable));
                    pw.println();
                    pw.close(); fw.close();
                } catch (java.io.IOException e) {
                    Log.e(TAG, "Failed to write crash log", e);
                }
                System.exit(1);
            }
        });
    }
}
