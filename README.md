================================================================================
  FlightPadFMC — Native Android FMC / MCDU for PMDG 737, PMDG 777, iFly 737 MAX, FlyByWire A320 & Fenix A320
  Version 0.4.1
  By SilenceDIY (Marcus)
  https://github.com/diymarcus/FlightPadFMC
  https://silencediy.com/flight-pad-fmc-server
================================================================================

DESCRIPTION
-----------
FlightPadFMC turns your Android tablet or phone into a fully functional CDU /
MCDU for the PMDG 737, PMDG 777, iFly 737 MAX, FlyByWire A320 and Fenix A320
in Microsoft Flight Simulator 2020 and 2024.

Unlike web-based solutions, this is a true native Android app — faster,
smoother and more responsive. The CDU / MCDU skins are pixel-accurate, touch
input is calibrated, and the display updates live over your local WiFi.

Five separate Android apps are provided, one per aircraft type:
  - 737PMDG.apk      — PMDG 737 NGXu (700 / 800 / 900)
  - 777PMDG.apk      — PMDG 777-300ER / 777F
  - iFly737MAX.apk   — iFly 737 MAX (7 / 8 / 8200 / 9 / 10)
  - FBWA320.apk      — FlyByWire A32NX (via SimBridge)
  - FenixA320.apk    — Fenix A320 v2 (via Fenix's built-in GraphQL server)

One Windows server acts as the bridge between MSFS and the Android apps. The
same server handles all five aircraft — simply launch the matching app on
your tablet and the server figures out the rest.

Supported platforms:
  - Microsoft Flight Simulator 2020 (SimConnect v11)
  - Microsoft Flight Simulator 2024 (SimConnect v12)
  - Android 4.4 through Android 14 (single APK per aircraft)
  - Windows 10 / 11, 64-bit (server app)


HOW IT WORKS
------------
  PMDG 737 / 777:
      MSFS + PMDG Aircraft
            |
         SimConnect (PMDG SDK)
            |
      FlightPadFMC Server (FlightPadFMCServer.exe)
            |
          WiFi / LAN  (WebSocket)
            |
      FlightPadFMC App (Android)

  iFly 737 MAX:
      MSFS + iFly 737 MAX
            |
       Win32 IPC: WM_COPYDATA (keys) + Shared Memory (screen + LEDs)
            |
      FlightPadFMC Server   <-- talks to the iFly Plugin window
            |
          WiFi / LAN  (WebSocket)
            |
      iFly737MAX App (Android)

  FlyByWire A320:
      MSFS + FlyByWire A32NX
            |
      FlyByWire SimBridge
            |
      FlightPadFMC Server   <-- proxies SimBridge
            |
          WiFi / LAN  (WebSocket)
            |
      FBWA320 App (Android)

  Fenix A320:
      MSFS + Fenix A320 v2
            |
      Fenix GraphQL Server (built-in, port 8083)
            |
      FlightPadFMC Server   <-- proxies Fenix GraphQL
            |
          WiFi / LAN  (WebSocket)
            |
      FenixA320 App (Android)

The server connects to MSFS via SimConnect (PMDG), Win32 IPC + shared
memory (iFly), proxies SimBridge (FlyByWire), or proxies Fenix's built-in
GraphQL server (Fenix), reads the CDU / MCDU screen data, and streams it
to the Android app over your local network. Key presses are sent back to
the server and forwarded to the aircraft in real time. The server
automatically detects which aircraft is loaded in MSFS and shows it in the
Server Console.


REQUIREMENTS
------------
  General (all users):
    - Microsoft Flight Simulator 2020 OR 2024
    - Windows 10 / 11, 64-bit
    - Android device on the same WiFi / LAN as the PC
    - Android 4.4 or newer

  For PMDG 737 / 777:
    - PMDG 737 NGXu or PMDG 777 add-on
    - PMDG SDK enabled in the aircraft options (Step 1)
    - (As of v0.3.0, the MobiFlight WASM Module is no longer required.)

  For iFly 737 MAX:
    - iFly 737 MAX add-on installed in MSFS
    - The "iFly Plugin" add-on enabled in MSFS Add-Ons (it ships with the
      iFly 737 MAX install — no separate download). FlightPadFMC reads
      the CDU screen and LEDs from the plugin's shared-memory output and
      sends key presses to the plugin's hidden window via WM_COPYDATA.
      No PMDG SDK setup needed.

  For FlyByWire A320:
    - FlyByWire A32NX (free, from flightsim.to or FBW installer)
    - FlyByWire SimBridge running on the same PC (Step 3)

  For Fenix A320:
    - Fenix A320 v2 (paid add-on)
    - No additional install — FlightPadFMC connects to Fenix's
      built-in GraphQL server on port 8083 automatically. MobiFlight
      WASM is NOT required for Fenix.


INSTALLATION — STEP BY STEP
----------------------------

This is an Alpha release. Bug reports and feedback are very welcome — they
help me track down the last rough edges before a proper 1.0.


STEP 1 — Enable the PMDG SDK   (skip if you only use the iFly 737 MAX or A320 — FBW or Fenix)
---------------------------------------------------------------
The PMDG SDK must be enabled so external tools (like FlightPadFMC) can read
the CDU data. You need to edit a small .ini file once per aircraft per sim.

In every case:
  - Open the file in a text editor (Notepad works).
  - Add or edit the [SDK] section so it contains:
      [SDK]
      EnableDataBroadcast=1
      EnableCDUBroadcast.0=1
      EnableCDUBroadcast.1=1
    (The .0 line is the Captain CDU; the .1 line enables the First Officer
     CDU feed used by the swipe-between-CDUs feature added in v0.4.1. If you
     only ever use the Captain CDU you can leave .1 out, but it costs
     nothing to enable.)
  - Save the file. IMPORTANT: make sure there is a blank line / trailing
    newline at the end — PMDG has been known to discard the [SDK] section
    if the file does not end cleanly.
  - Restart MSFS if it was already running.
  - Load the aircraft fully, past "ready to fly", at least once. The file
    is regenerated by PMDG on first load, so if it does not exist yet, fly
    the aircraft once and it will appear.

  ---------------------------------------------------------------
  MSFS 2020
  ---------------------------------------------------------------

  PMDG 737 (MSFS 2020, MS Store):
    %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalState\
    packages\pmdg-aircraft-738\work\737NG3_Options.ini

  PMDG 737 (MSFS 2020, Steam):
    %APPDATA%\Microsoft Flight Simulator\Packages\pmdg-aircraft-738\work\
    737NG3_Options.ini

  PMDG 777 (MSFS 2020, MS Store):
    %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalState\
    packages\pmdg-aircraft-77w\work\777_Options.ini

  PMDG 777 (MSFS 2020, Steam):
    %APPDATA%\Microsoft Flight Simulator\Packages\pmdg-aircraft-77w\work\
    777_Options.ini

  ---------------------------------------------------------------
  MSFS 2024
  ---------------------------------------------------------------

  MSFS 2024 stores PMDG options under a different root than 2020 — look in
  the "Limitless" package, inside a WASM\MSFS2024 subfolder.

  IMPORTANT — filename varies on MSFS 2024. PMDG appears to have dropped
  the "NG3" suffix on the 737 options file in their MSFS 2024 builds, so
  the actual file you need to edit may be either:
    - 737_Options.ini      (newer PMDG MSFS 2024 builds — confirmed by
                            user feedback on Steam 2024)
    - 737NG3_Options.ini   (older legacy name, may still apply on some
                            installs)
  Edit whichever one actually exists in the work\ folder. The 777 keeps
  its original name (777_Options.ini) on both 2020 and 2024.

  PMDG 737 (MSFS 2024, MS Store):
    %LOCALAPPDATA%\Packages\Microsoft.Limitless_8wekyb3d8bbwe\LocalState\
    WASM\MSFS2024\pmdg-aircraft-738\work\737_Options.ini   (or 737NG3_Options.ini)

  PMDG 737 (MSFS 2024, Steam):
    %APPDATA%\Microsoft Flight Simulator 2024\WASM\MSFS2024\
    pmdg-aircraft-738\work\737_Options.ini   (or 737NG3_Options.ini)

  PMDG 777 (MSFS 2024, MS Store):
    %LOCALAPPDATA%\Packages\Microsoft.Limitless_8wekyb3d8bbwe\LocalState\
    WASM\MSFS2024\pmdg-aircraft-77w\work\777_Options.ini

  PMDG 777 (MSFS 2024, Steam):
    %APPDATA%\Microsoft Flight Simulator 2024\WASM\MSFS2024\
    pmdg-aircraft-77w\work\777_Options.ini

  The folder name may show up as "pmdg-aircraft-737" or "pmdg-aircraft-777"
  instead of "738" / "77w" depending on which PMDG build you own. If you
  don't see the exact folder above, look for any folder starting with
  "pmdg-aircraft-73" (for the 737) or "pmdg-aircraft-77" (for the 777) in
  the same parent directory — that's the one to edit.


STEP 2 — Set up the iFly 737 MAX   (skip if you only use PMDG / FBW / Fenix)
----------------------------------------------------------------------------
The iFly 737 MAX uses its own communication mechanism — separate from the
PMDG SDK. There is no .ini file to edit. The two requirements are:

  1. The iFly 737 MAX add-on installed in MSFS (you already have this if
     you fly the iFly 737).

  2. The "iFly Plugin" enabled in MSFS Add-Ons. The plugin ships with the
     iFly 737 MAX install and is enabled by default — but if you have ever
     disabled add-ons in MSFS, double-check it is ON:
       MSFS Main Menu -> Profile -> Add-Ons (or the in-flight Add-Ons toolbar)
     Look for "iFly Plugin" (MSFS 2020) or "iFly Plugin - MSFS2024" (MSFS 2024).

The plugin exposes a hidden window the FlightPadFMC server talks to via
WM_COPYDATA, and a shared-memory region the server reads CDU screen + LED
state from. No external EXE, no Community-folder add-on, no MobiFlight
dependency.

(Earlier versions of FlightPadFMC required the MobiFlight WASM Module in
your Community folder to drive the PMDG 737 EXEC LED. As of v0.3.0 this
is no longer needed for any aircraft — leave the WASM module installed if
you have it for other tools, FlightPadFMC simply doesn't use it.)


STEP 3 — Install FlyByWire SimBridge   (skip if you only use the 737 / 777 or Fenix)
------------------------------------------------------------------------------------
The A320 support proxies the MCDU through FlyByWire's own SimBridge tool.
SimBridge is free and maintained by the FlyByWire team, and it is installed
through the FlyByWire A32NX installer — you do not need a separate download.

  1. Launch the FlyByWire Installer (the same tool you used to install the
     A32NX itself). If you don't have it yet:
       https://flybywiresim.com/download/

  2. In the installer, open the A32NX section and start the setup / update
     flow. During setup you will be offered a list of optional components —
     tick "SimBridge" (or "Install SimBridge") and let the installer finish.

  3. Launch SimBridge. It runs as a small tray app on the PC. You can
     start it in any of these ways (pick whichever is easiest):
       - From the Start menu shortcut created by the FBW installer.
       - Let the FBW installer start it for you at the end of setup.
       - Directly from the Community folder:
           <Community>\flybywire-externaltools-simbridge\fbw-simbridge.exe
         (this is the exact executable — you can pin it to the taskbar
         or make a desktop shortcut for one-click launch).

  4. Load the A32NX in MSFS.

  SimBridge must be running before you connect the FBWA320 Android app,
  otherwise the app will just sit at "Waiting for server...". The
  FlightPadFMC server Activity Log will also tell you if SimBridge is
  missing or not reachable.

  More info:
    https://docs.flybywiresim.com/tools/simbridge/


STEP 4 — Run the FlightPadFMC Server
------------------------------------
  1. Extract the FlightPadFMC server folder anywhere on your PC.

  2. Inside that folder you will find two SimConnect subfolders:
       dist\SDK2020\SimConnect.dll   <-- for MSFS 2020
       dist\SDK2024\SimConnect.dll   <-- for MSFS 2024
     Both DLLs ship with the release — no manual copy needed.

  3. Run FlightPadFMCServer.exe.

  4. In the server: File -> Settings, set "MSFS Version" to 2020 or 2024
     to match your installation, then restart the server.

  5. Start MSFS and load your aircraft. The server will show:
       - MSFS:     Connected / Not connected
       - Android:  which FMC app is connected
       - Server:   WebSocket running / stopped

  6. Allow the server through Windows Firewall when prompted. If the firewall
     blocks the server, the Android apps will never see it on the LAN.

  Activity Log, port setting and About window are available from the menu.
  Closing the window minimises the server to the system tray.

  Optional — Auto-launch the server with MSFS
  -------------------------------------------
  MSFS can launch FlightPadFMCServer.exe automatically when the sim starts,
  via its built-in "exe.xml" add-on launcher mechanism. Pair this with the
  new "Close server when MSFS exits" Setting (File -> Settings, in the
  Server section, ~10 s debounce) so the server also auto-quits when you
  close MSFS — no manual server start/stop needed.

  Edit (or create) the exe.xml file in your MSFS user folder. Typical
  locations:

    MSFS 2020 (MS Store):
      %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalCache\exe.xml
    MSFS 2020 (Steam):
      %APPDATA%\Microsoft Flight Simulator\exe.xml
    MSFS 2024 (MS Store):
      %LOCALAPPDATA%\Packages\Microsoft.Limitless_8wekyb3d8bbwe\LocalCache\exe.xml
    MSFS 2024 (Steam):
      %APPDATA%\Microsoft Flight Simulator 2024\exe.xml

  (MSFS 2024 paths follow the same convention as MSFS 2020 — verify on
  your install if exe.xml doesn't appear at that location.)

  Add the following block INSIDE the existing <SimBase.Document> root
  (do NOT overwrite the file — exe.xml is shared with other add-ons,
  one <Launch.Addon> per add-on):

      <Launch.Addon>
          <Name>FlightPadServer</Name>
          <Disabled>false</Disabled>
          <Path>FULL\PATH\TO\FlightPadFMCServer.exe</Path>
      </Launch.Addon>

  Replace <Path> with the absolute path to where you extracted the server
  (e.g. C:\FlightPadFMC\FlightPadFMCServer.exe). MSFS does not expand
  environment variables in this field — use a real, full path.


STEP 5 — Install the Android App(s)
-----------------------------------
Five APKs are provided, one per aircraft. Install only the one(s) you fly:

    737PMDG.apk      — for PMDG 737 NGXu
    777PMDG.apk      — for PMDG 777
    iFly737MAX.apk   — for iFly 737 MAX
    FBWA320.apk      — for FlyByWire A32NX
    FenixA320.apk    — for Fenix A320 v2

To install:
  1. IMPORTANT — If you already have an older version of the same
     FlightPadFMC app installed, uninstall it first (Android Settings >
     Apps > PMDG737 / PMDG777 / iFly737MAX / FBWA320 / FenixA320 > Uninstall).
     Installing a new APK on top of an old one can fail with a "package
     conflict" / "app not installed" error because the signing keys
     between builds may differ. Uninstalling the old version first
     avoids this.
  2. Copy the APK to your Android device.
  3. Enable "Install from unknown sources" in Android Settings > Security
     (or "Install unknown apps" on newer Android versions).
  4. Tap the APK file to install.

The apps work on Android 4.4 through Android 14. The only permission used
is local network access.

In-app settings:
  Tap the gear icon on the CDU / MCDU skin to open the in-app Settings
  dialog. From here you can:
    - Switch connection mode between Auto (mDNS auto-discovery) and
      Manual (type the server IP yourself)
    - Enter the server IP and port manually if auto-discovery does not
      find the server on your network
    - Toggle click sound and haptic feedback

  If the app sits on "Waiting for server..." or "No IP — tap gear to
  configure", open Settings, switch to Manual mode, and enter the IP
  address shown in the server window title bar (default port 8765).
  Auto mode works on most home networks, but some routers block mDNS —
  in that case Manual mode is the reliable fallback.


STEP 6 — Connect
----------------
  1. Make sure the PC and the Android device are on the same WiFi / LAN.
  2. Launch the matching app on your tablet:
       - 737PMDG     -> PMDG 737 loaded
       - 777PMDG     -> PMDG 777 loaded
       - iFly737MAX  -> iFly 737 MAX loaded + iFly Plugin enabled
       - FBWA320     -> A32NX loaded + SimBridge running
       - FenixA320   -> Fenix A320 v2 loaded (no extra setup)
  3. The app auto-discovers the server via mDNS. No IP entry needed in most
     home networks.
  4. If auto-discovery fails: open the in-app Settings, switch mode to
     "Manual", and type the server IP shown in the server window title bar.
     Default port is 8765.

Once connected, the CDU / MCDU screen appears and updates live. The server
also fires a fake MENU key on connect, so the display is never blank.


CAPTAIN / FO CDU  (PMDG 737 + 777 — new in v0.4.1)
--------------------------------------------------
  - Swipe LEFT on the CDU display area to switch to the First Officer's
    CDU; swipe RIGHT to return to the Captain's. A green "FO CDU" /
    "CAPT CDU" label flashes briefly to confirm which seat you're on.
  - The in-app Settings has a new "CDU Side" option (Captain CDU /
    First Officer CDU). The saved side is applied every time the app
    connects — set it to First Officer for a dedicated FO tablet.
    Swiping is a temporary override and does not change this setting.
  - Dual-tablet cockpits: two devices can be connected at the same
    time, one per seat. Screen, bezel annunciators and key presses all
    follow each tablet's selected side independently.
  - REQUIRES EnableCDUBroadcast.1=1 in the aircraft's options.ini (see
    STEP 1). Without it, the FO screen stays blank after a swipe.


USAGE TIPS
----------
  - Tap the gear icon on the skin to open in-app Settings.
  - Tap the X icon to cleanly close the app.
  - If the server restarts, the app reconnects automatically (5 fast retries,
    then a 30-second slow retry until it comes back).
  - Pressing Home / switching apps disconnects the Android side cleanly;
    returning to the app reconnects automatically.
  - The server tray icon turns green when an Android app is connected.


TROUBLESHOOTING
---------------
  EXEC LED not working (PMDG 737):
    - Make sure the PMDG SDK is enabled in 737NG3_Options.ini (Step 1).
    - In the server Activity Log, watch for "EXEC LED: ON/OFF (737 Captain
      via SDK)" messages when you press EXEC in the sim. If those appear,
      the server is reading the LED correctly — check the Android side.
    - As of v0.3.0 the MobiFlight WASM module is no longer used for any
      LED — if you still have it installed for other purposes that's
      fine, FlightPadFMC simply doesn't read from it anymore.

  FO CDU blank after swiping (PMDG 737 / 777):
    - Add EnableCDUBroadcast.1=1 to the aircraft's options.ini (STEP 1)
      and restart MSFS. The .0 line only enables the Captain CDU feed;
      the .1 line enables the First Officer's.

  CDU / MCDU screen not showing:
    - PMDG: verify PMDG SDK is enabled (Step 1) and MSFS was restarted.
    - A320: verify SimBridge is running (launch the SimBridge tray app —
      see Step 3). The server Activity Log will show SimBridge retry
      messages if it cannot reach it.
    - Make sure you launched the correct app for the aircraft you loaded.
    - In the server, check that "MSFS Version" matches your installation
      (2020 vs 2024).

  App not connecting:
    - PC and Android on the same WiFi network?
    - Windows Firewall allowing FlightPadFMCServer.exe on private networks?
    - Default port 8765 not already used by another app?
    - Try "Manual" mode in the app and enter the server IP by hand.

  Keys not registering:
    - PMDG: PMDG SDK must be enabled. Also make sure the aircraft has been
      fully loaded past "ready to fly" at least once after enabling the SDK.
    - A320: SimBridge must be running before you press any key.

  Sound is quiet or inconsistent on newer Android:
    - Update to v0.2.1 — sound output was reworked in this release and now
      follows the media volume slider cleanly on Android 12+.


KNOWN LIMITATIONS
-----------------
The following items are known and are NOT bugs in FlightPadFMC — they are
limitations of the upstream data sources we depend on. They will be addressed
in future releases as the underlying tools gain the necessary features, or as
FlightPadFMC adds support for aircraft that do expose the missing data.

  FBWA320 — MCDU annunciator LEDs stay dark
    The 5 annunciator indicators at the top of the A320 MCDU skin (FM1, IND,
    RDY, --, FM2) are drawn on the app but remain off under all conditions,
    including after triggering FMGC failures from the FlyByWire flyPad
    Failures menu. Reason: the FlyByWire A32NX does not simulate the MCDU
    annunciator panel state in the in-sim MCDU gauge either, so FlyByWire
    SimBridge has no data to forward. This was verified on a live A32NX by
    triggering FMGC1 failures and observing that the annunciators never lit
    up on the real cockpit MCDU in the sim — it is an FBW limitation, not a
    FlightPadFMC bug. The slot-remap is wired and ready to fire if/when a
    future SimBridge release starts pushing the data; until then the FBW
    annunciators are decorative-only.

    Fenix A320 users: the equivalent annunciator slots (FM1, IND, RDY, FM,
    FM2 horizontal + FAIL / FM / MCDU MENU vertical) DO drive live from
    Fenix's GraphQL stream and behave correctly during normal flight.

  FBWA320 — certain MCDU pages may show placeholder characters
    On some MCDU pages where the FBW HTML MCDU gauge uses special cockpit
    glyphs (empty data-entry boxes, custom symbols), the Android app may
    render a plain character or a small placeholder square instead of the
    intended glyph. This is because FlyByWire's in-sim MCDU uses a custom
    font with remapped codepoints that B612 Mono (the aviation font used by
    FlightPadFMC) does not have a matching glyph for. Functionality is not
    affected — only the visual appearance of that specific character. A
    character translation table (translateFbwChar() in MainActivity.java)
    is already in place for known remaps and will be extended in future
    updates as more are identified.

  FBWA320 — not all MCDU pages fully verified
    FlightPadFMC v0.2.1 is the first release with FlyByWire A320 support.
    The common MCDU pages (MCDU MENU, INIT A/B, F-PLN, PROG, PERF) have been
    tested, but the A32NX MCDU has many less frequently used pages that have
    not yet been exercised in a live flight. If you see an alignment, color,
    or rendering issue on a specific page, please report it — the fix is
    usually one line once the broken data is captured from the server log.

  (Earlier versions had a limitation here on PMDG annunciators —
  resolved as of v0.3.0; the 777 and 737 CDUs now drive all bezel
  annunciators end-to-end via the PMDG SDK.)


CREDITS
-------
  PMDG Simulations — for the 737 NGXu and 777 add-ons, and for shipping
  the PMDG SDK that makes external CDU clients like FlightPadFMC possible.
  https://pmdg.com/

  iFly Simulations Software — for the iFly 737 MAX add-on and the iFly
  Plugin SDK that exposes CDU screen data and key input via Win32 IPC.
  https://www.flight1.com/

  FlyByWire Simulations — for the FlyByWire A32NX and SimBridge.
  Copyright (c) FlyByWire Simulations
  GPL-3.0 — https://github.com/flybywiresim/simbridge

  Fenix Simulations — for the Fenix A320 v2 and its built-in GraphQL
  server which makes external MCDU clients straightforward.
  https://fenixsim.com/

  B612 Mono font
  SIL Open Font License — designed for aviation cockpit displays


================================================================================
  CHANGELOG
================================================================================

Version 0.4.1 — August 2026
---------------------------
  NEW:
  + Captain <-> FO CDU swipe (PMDG 737 + 777)
      - Swipe left on the CDU display to switch to the First Officer's
        CDU, swipe right to return to the Captain's. A green CAPT CDU /
        FO CDU label flashes so you always know which seat you're on.
      - New "CDU Side" setting in the Android app (Captain / First
        Officer). The saved side is applied on every connect, so a
        dedicated FO tablet always comes up on the FO CDU. Swiping is
        a temporary override and never changes the saved setting.
      - Dual-tablet support: two tablets can connect at once, one on
        the Captain CDU and one on the FO CDU — screen, annunciators
        and key input all follow each tablet's selected side.
      - Requires EnableCDUBroadcast.1=1 in the aircraft's options.ini
        (see STEP 1) — without it the FO screen stays blank.

  IMPROVEMENTS:
  + Smarter "Android app outdated" check — the server now tracks the
      minimum compatible app version PER AIRCRAFT. Only the PMDG 737
      and 777 apps need the 0.4.1 update; the FBW A320, Fenix A320 and
      iFly 737 MAX 0.4.0 apps remain fully compatible and are no
      longer flagged as outdated.

  CHANGES:
  + Server version bumped to 0.4.1; PMDG 737 / 777 APKs
    bumped to versionCode 4 / versionName "0.4.1". The FBW A320,
    Fenix A320 and iFly 737 MAX APKs are unchanged from 0.4.0 — no
    reinstall needed for those.

Version 0.4.0 — May 2026
------------------------
  NEW:
  + iFly 737 MAX support via new iFly737MAX Android app
      - Server module (ifly737max_handler) talks to the iFly Plugin
        window via WM_COPYDATA for keys and reads CDU screen + 5 LEDs
        (EXEC / MSG / FAIL / CALL / OFST) from shared memory
      - Works on MSFS 2020 AND MSFS 2024, supports all MAX variants
        (7 / 8 / 8200 / 9 / 10)
      - No PMDG SDK, no SimBridge, no MobiFlight — just the iFly Plugin
        that ships with the iFly 737 MAX install
  + Automatic aircraft detection in the Server Console
      - Server now identifies the loaded MSFS aircraft via SimConnect
        AircraftLoaded event (flight-load) AND the TITLE SimVar
        subscription (profile-menu pick — fires before Fly is clicked)
      - The Server Console "Aircraft" row updates instantly as you
        change aircraft in the MSFS profile menu, matching MobiFlight
        behaviour. Five aircraft mapped: PMDG 777, PMDG 737NG,
        iFly 737 MAX, FlyByWire A320, Fenix A320
  + New Server Console panel — black status panel right of Connection
      - Surfaces non-scrolling state: which aircraft is loaded, SDK
        health (PMDG broadcast, iFly shm, SimBridge, Fenix WS),
        aircraft mismatch warnings, MSFS auto-exit countdown,
        outdated-Android-app warning, and a "Blue Sky Captain" happy-
        path row when everything's healthy
      - Activity log mirrors every transition with [OK] / [WARN] /
        [ERROR] / [INFO] tags so .log files carry the same diagnosis
  + Tablet-side status overlay (status_message wire format)
      - When the server detects an issue (PMDG SDK silent, SimBridge
        not running, Fenix EFB not running, aircraft mismatch), the
        diagnosis appears as an amber overlay on the tablet's CDU/MCDU
        screen — auto-fits to the screen width on long messages
  + SimBridge 3-state startup check
      - Distinguishes "package missing", "package found but tray app
        not running on :8380", and "ready" — log message tells you
        exactly what's wrong instead of a single generic "found"
  + Server connection status shows MSFS version
      - "Connected MSFS 2020" / "Connected MSFS 2024" instead of
        bare "Connected" — confirms which SimConnect DLL is loaded
  + Optional auto-close + auto-launch already shipped in v0.3.0; v0.4.0
      adds the matching "Server updated to v0.4.0" first-launch banner
      to remind users to install the matching APKs

  IMPROVEMENTS — All 6 Android apps:
  + Pixel-detected button shapes from the bezel skin — no manual
      calibration needed for press feedback (Calibrate buttons removed
      from settings; calibration data baked from cockpit-tablet sweeps)
  + Cockpit-feel press animation — soft fade-in/fade-out shadow,
      tuned for rapid-press cancel-ordering correctness
  + Auto-fit overlay text — long status messages shrink to fit
      instead of running off the screen edges
  + Annunciator text now glows when lit — soft halo radiating from
      each glyph in the same colour as the label, mimicking how MSG /
      OFST / DSPY / FAIL / CALL appear in the actual sim cockpit
  + EXEC LED rendered as a rounded-corner pill (PMDG / iFly only) at
      90% size in white — matches the in-sim render
  + FAIL annunciator now red on iFly + FMC737 (was amber)
  + FM annunciator now white on FBW + Fenix (was amber)
  + Settings-save manual-IP reconnect bug fixed across the fleet
  + Launcher labels unified: PMDG737 / PMDG777 / PMDG777-Silence /
      iFly737MAX / FBWA320 / FenixA320

  CHANGES:
  + Server version bumped to 0.4.0; all 6 APKs bumped to versionCode 3
    / versionName "0.4.0"

Version 0.3.0 — May 2026
------------------------
  NEW:
  + Fenix A320 v2 support via new FenixA320 Android app
      - Server proxies Fenix's built-in GraphQL stream (port 8083) — no
        SimBridge, no MobiFlight WASM, no extra install needed
      - Renders MCDU + 7-slot annunciator strip; key input via GraphQL
        writeInt mutations on system.switches.S_CDU1_KEY_*
      - Live annunciators (FM1 / IND / RDY / FM / FM2 + FAIL + MCDU MENU)
        verified end-to-end against the in-sim Fenix MCDU
  + PMDG 737 NG3 bezel annunciators end-to-end — MSG, OFST, CALL, FAIL
      drawn on the keypad bezel (MSG/OFST right strip, CALL/FAIL left
      strip), driven directly from the PMDG SDK alongside EXEC. Same
      pipeline as the 777 annunciators. Captain CDU only.
  + Server auto-close when MSFS exits — opt-in setting, ~10 s debounce
      File -> Settings -> "Close server when MSFS exits". When MSFS
      closes, the server cleanly quits ~10 seconds later. If MSFS
      reconnects within the window, the server stays alive. Default OFF
      so existing behaviour is preserved.
  + Optional auto-launch with MSFS via exe.xml — see STEP 4 in this
      README. Pair with the new auto-close setting and the server's
      lifecycle follows MSFS without any manual start/stop.
  + 737 EXEC LED now reads via the PMDG NG3 SDK
      MobiFlight WASM module is no longer required for the 737 EXEC
      LED — the server reads it directly from PMDG_NG3_Data along
      with MSG, CALL, FAIL, OFST. The MobiFlight install step from
      earlier versions is no longer needed for any aircraft.
  + Fresh-client annunciator state replay on identify
      Any annunciator already lit when an Android client connects
      (notably PMDG NG3 cold-and-dark MSG) now appears immediately.
      Earlier versions only sent state on transitions, so an
      already-on annunciator would stay invisibly off on the client
      until something happened to toggle it.

  IMPROVEMENTS:
  + Activity log noticeably quieter — three per-tick CDU-area dispatch
      DEBUG lines removed. State changes still log at INFO.
  + Calibration changes (CalibrateActivity737 / 777 / Fenix / FBW)
      take effect immediately on return to the CDU, no restart needed.

  CHANGES:
  + Server version bumped to 0.3.0; all 5 APKs bumped to versionCode 2
    / versionName "0.3.0".

  REMOVED:
  - MobiFlight WASM Module dependency (and the bundled
    Community/mobiflight-event-module/ folder — no longer ships).

Version 0.2.15 — April 2026
---------------------------
  BUG FIXES:
  - Fixed reconnect race condition where a stale WebSocket disconnect could
    clobber the active aircraft state, causing all CDU keys to be silently
    dropped until the user manually minimized and reopened the Android app.
    Server now tracks all connected clients and only resets state when the
    last client disconnects.
  - Fixed Android apps silently reconnecting to the server while minimized.
    The WiFi NetworkCallback was not unregistered in onPause, so WiFi state
    changes could trigger startConnectionProcess() in the background. Now
    properly unregistered on pause, re-registered on resume. All 3 apps fixed.
  - Fixed PMDG 737 DEP ARR button not working — Android app sent "DEP" but
    server expected "DEP ARR". Added alias in the server key map.
  - mDNS auto-discovery timeout increased from 5 seconds to 15 seconds —
    Android NsdManager warm-up + router multicast propagation often took
    longer than 5 seconds, causing auto-discovery to always time out and
    fall back to manual IP entry.
  - Removed dead [UI] section from server settings (start_minimized,
    window_width, window_height were never read by any code).

  CHANGES:
  + Server version bumped to 0.2.15.

Version 0.2.1 — April 2026
---------------------------
  NEW:
  + FlyByWire A32NX support via new FBWA320 Android app
      - Server proxies FlyByWire SimBridge to the app
      - mDNS auto-discovery like the PMDG apps
      - All MCDU keys mapped, 5 annunciator LEDs (FM1, IND, RDY, FM2)
  + MSFS 2024 fully supported
      - Server ships with both SimConnect DLLs (SDK2020 / SDK2024)
      - New "MSFS Version" setting in server Settings
  + Clickable "SilenceDIY.com" link in server header
  + Server About window updated for A320 + MSFS 2024

  IMPROVEMENTS:
  + New Photoshop-drawn skins for all three apps, freshly calibrated
  + Settings / Close icons now drawn on the skin itself — no Android system
    drawables required
  + Sound output reworked for Android 12+ — fixes quiet / inconsistent click
    volume on newer phones, now follows the media volume slider
    (SoundPool streams = 1, trimmed 80 ms click WAV, USAGE_MEDIA routing)
  + APK output filenames are now 737PMDG.apk / 777PMDG.apk / FBWA320.apk
    (no more generic app-debug.apk)
  + Server fires a fake MENU keypress 100 ms after any app connects, so the
    screen is never blank when connecting to a static page (PMDG + A320)
  + FBWA320: multi-color + multi-alignment MCDU rendering — green flight
    levels, small labels, {right}-aligned text all render correctly
  + FBWA320: corrected SimBridge line array order [left, right, center]
    — fixes right-side text appearing in the center column

  BUG FIXES:
  - FBWA320 MCDU alignment swap (right vs center columns)
  - FBWA320 multi-color text (previously single dominant color per row)
  - Calibration positions for all three apps re-baked against the new skins

Version 0.2.0 — April 2026
---------------------------
  NEW:
  + EXEC LED for PMDG 737 — via MobiFlight WASM L-var (switch_6042_73X)
  + EXEC LED for PMDG 777 — live via SimConnect (CDU data area)

  IMPROVEMENTS:
  + Key input speed: 600 ms -> 200 ms delay between keys
    (discovered PMDG requires a proper press + release mouse event cycle)
  + CLR button fixed on both 737 and 777 — now deletes one character per tap
    without clearing the entire scratchpad after 2 seconds
    (PMDG K:ROTOR_BRAKE press/release events from the HubHop database)
  + MSFS disconnect detected immediately when MSFS closes
  + Android: fixed ghost "Android connected" status after app close
  + Android: app fully disconnects on Home button and reconnects on return
  + Android: after 5 fast retries, switches to 30 s slow retry indefinitely

  BUG FIXES:
  - Stale WebSocket callbacks causing ghost reconnects on server (737 app)
  - Missing exec_led message handler in 737 app
  - CLR confirmation dialog race condition on close (737 app)
  - SimConnect_CallDispatch return value never checked on MSFS disconnect

Version 0.1.0 — March 2026
---------------------------
  Initial release.
  + PMDG 777 and PMDG 737 CDU screens live on Android
  + Full key input for all CDU buttons
  + mDNS auto-discovery + manual IP fallback with retry
  + WiFi auto-reconnect
  + Modern server GUI with connection status indicators
  + System tray support
  + Activity log with filter
  + Touch calibration system
  + Sound and haptic feedback
  + Works on Android 4.4 through Android 14


================================================================================
  ROADMAP — What's next
================================================================================

  - PFD / MFD secondary display app — replace the 7" HDMI MSFS pop-out
    screen with a WiFi-driven Android tablet, sharing this server's EXE
  - Single Android app with automatic skin-switch between aircraft
  - Second CDU support (Captain + F/O simultaneously)
  - iOS port (community contribution welcome)


================================================================================
  SUPPORT & FEEDBACK
================================================================================

  GitHub:   https://github.com/diymarcus/FlightPadFMC
  Website:  https://silencediy.com

  If you enjoy FlightPadFMC, consider supporting development:
  https://www.paypal.com/donate?hosted_button_id=QD3R46HS9RZ3L

================================================================================

P.S. Captains, I’ve seen quite a few downloads so far—thank you! If you have a moment, I’d love to hear your feedback. I am especially looking for MSFS 2024 users to verify compatibility, as it has currently only been confirmed for MSFS 2020. Blue skies!
