================================================================================
  FlightPadFMC — Native Android FMC for PMDG 737 & 777
  Version 0.2.0
  By SilenceDIY (Marcus)
  https://github.com/diymarcus/FlightPadFMC
================================================================================

DESCRIPTION
-----------
FlightPadFMC turns your Android tablet into a fully functional CDU (FMC) for
PMDG aircraft in Microsoft Flight Simulator 2020. Microsoft Flight Simulator 2024 (On Test)

Unlike web-based solutions, this is a true native Android app — faster, smoother,
and more responsive. The CDU skin is pixel-accurate, touch input is calibrated.

Supported aircraft:
  - PMDG 737 NGXu (737-700 / 737-800/ 737-900)
  - PMDG 777X

Supported platforms:
  - MSFS 2020 (MSFS 2024 compatible — same SimConnect API)
  - Android 4.4 and above (one APK for all Android versions)
  - Windows 10 / 11 (server app)


HOW IT WORKS
------------
  MSFS 2020 + PMDG Aircraft
          |
    SimConnect SDK
          |
  FlightPadFMC Server (Windows .exe)
          |
      WiFi / LAN  (WebSocket)
          |
  FlightPadFMC App (Android tablet)

The Windows server connects to MSFS via SimConnect, reads the CDU screen data
state from PMDG, and streams it to the Android app over your local
WiFi network. Key presses from the tablet are sent back to the server and
forwarded to PMDG in real time.


REQUIREMENTS
------------
  - Microsoft Flight Simulator 2020/2024 (On Test)
  - PMDG 737 NGXu or PMDG 777X add-on
  - PMDG SDK enabled in aircraft options (see below)
  - MobiFlight WASM Module installed in MSFS Community folder
  - Android tablet (any size, Android 4.4+)
  - Both PC and tablet on the same WiFi / LAN network
  - Windows 10 or 11 (64-bit)


INSTALLATION — STEP BY STEP

Keep in mind that this is an Alpha version. I would greatly appreciate any bug reports or feedback you can provide to help improve the app!
----------------------------

STEP 1 — Enable PMDG SDK
  The PMDG SDK must be enabled to allow external tools to read CDU data.

  For PMDG 737:
    Open this file in a text editor:
    %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalState\
    packages\pmdg-aircraft-738\work\737NG3_Options.ini

    Add or edit the [SDK] section:
      [SDK]
      EnableDataBroadcast=1
      EnableCDUBroadcast.0=1

  For PMDG 777:
    Open this file in a text editor:
    %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalState\
    packages\pmdg-aircraft-77w\work\777_Options.ini

    Add or edit the [SDK] section:
      [SDK]
      EnableDataBroadcast=1
      EnableCDUBroadcast.0=1

  Save the file and restart MSFS if it was running.

  NOTE: If you use the Microsoft Store version of MSFS, the path above is correct.
  For Steam version, the path is:
    %APPDATA%\Microsoft Flight Simulator\Packages\...


STEP 2 — Install MobiFlight WASM Module
  MSFS and provides access to aircraft L-variables.

  1. Download MobiFlight WASM Module from:
     https://github.com/MobiFlight/MobiFlight-WASM-Module
	Or the mobiflight-event-module you can find in archive/Community

  2. Copy the "mobiflight-event-module" folder into your MSFS Community folder.

  Your Community folder is typically:
    %LOCALAPPDATA%\Packages\Microsoft.FlightSimulator_8wekyb3d8bbwe\LocalState\
    packages\Community\

  3. Restart MSFS after installing.

  NOTE: The MobiFlight WASM Module is a free, open-source project.
  If you already have MobiFlight installed for other purposes, the WASM module
  is already present — no extra steps needed.


STEP 3 — Install SimConnect DLL
  The server requires SimConnect.dll to communicate with MSFS.

  The server will automatically search for SimConnect.dll in these locations:
    1. Same folder as FlightPadFMC_Server.exe  (recommended)

  The easiest approach: copy SimConnect.dll next to FlightPadFMC_Server.exe.
  You can find SimConnect.dll in your MSFS SDK installation, or download it
  from the MSFS Developer Mode tools.


STEP 4 — Run the Server
  1. Extract the FlightPadFMC server folder to any location on your PC.
  2. Run FlightPadFMCServer.exe
  3. Start MSFS and load a PMDG aircraft.
  4. The server status will show "MSFS Connected" when ready.
  5.Ensure you Allow Access through your firewall. If the firewall is not configured correctly, the Android app will be unable to communicate with the server.

  The server shows:
    - MSFS status (connected / not connected)
    - Android status (which app is connected)
    - Server status (WebSocket running)
    - Activity log

  Settings (port, log level) are available from the menu bar.
  The server minimizes to the system tray when closed.


STEP 5 — Install Android App
  Two separate APKs are provided — one for 737, one for 777.

    737FMCPad.apk  — for PMDG 737 NGXu
    777FMCPad.apk  — for PMDG 777X

  To install:
  1. Copy the APK to your Android tablet.
  2. Enable "Install from unknown sources" in Android Settings > Security.
  3. Tap the APK file to install.

  The app works on Android 4.4 through Android 14. No special permissions needed
  beyond local network access.


STEP 6 — Connect
  1. Make sure your PC and tablet are on the same WiFi network.
  2. Open the FlightPadFMC app on your tablet.
  3. The app will auto-discover the server via mDNS (no IP needed in most cases).
  4. If auto-discovery fails, open Settings in the app and enter the server IP
     manually. The server IP is shown in the server window title bar.

  Once connected, the CDU screen will appear and update in real time.


USAGE TIPS
----------
  - Tap the FMC position button (top-left) to switch between LEFT / CENTER / RIGHT
    CDU positions. (Not yet implemented)
  - Tap the gear icon to open Settings.
  - Tap the X button to close the app cleanly.
  - If the server is restarted, the app will reconnect automatically.
  - If the app is minimized (home button), it disconnects. Return to the app to
    reconnect automatically.


TROUBLESHOOTING
---------------
  EXEC LED not working:
    - Make sure MobiFlight WASM Module is installed in the Community folder.
    - Restart MSFS after installing the WASM module.
    - Check the server Activity Log for "MF LVar switch_6042_73X" messages.

  CDU screen not showing / wrong aircraft:
    - Check that PMDG SDK is enabled (Step 1).
    - Make sure the correct APK is installed for your aircraft (737 or 777).
    - The server detects aircraft from the Android app's identify message —
      make sure you opened the correct app.

  App not connecting:
    - Check that PC and tablet are on the same WiFi network.
    - Try entering the server IP manually in the app Settings.
    - Check Windows Firewall — allow FlightPadFMCServer.exe on private networks.
    - Default port is 8765. Change in server Settings if needed.

  Keys not registering in PMDG:
    - Make sure PMDG SDK is enabled (Step 1).


OPEN SOURCE CREDITS
-------------------
  MobiFlight WASM Module
  Copyright (c) 2021 Sebastian Moebius, MobiFlight
  MIT License — https://github.com/MobiFlight/MobiFlight-WASM-Module


================================================================================
  CHANGELOG
================================================================================

Version 0.2.0 — April 2026
---------------------------
  NEW FEATURES:
  + EXEC LED for PMDG 737 — via MobiFlight WASM L-variable (switch_6042_73X)
  + EXEC LED for PMDG 777 — live via SimConnect (CDU data area)

  IMPROVEMENTS:
  + Key input speed: 600ms → 200ms delay between keys
    (discovered PMDG requires proper press+release mouse event cycle)
  + CLR button fixed on both 737 and 777 — now correctly deletes one character
    per tap without clearing the entire scratchpad after 2 seconds
    (used PMDG's K:ROTOR_BRAKE press/release events from HubHop database)
  + MSFS disconnect now detected immediately when MSFS closes
  + Android apps: fixed ghost "Android connected" status after app close
  + Android apps: app now fully disconnects when minimized (home button)
    and reconnects automatically when returned to foreground
  + Android apps: after 5 failed connection attempts, switches to slow retry
    mode (every 30 seconds) instead of stopping — always reconnects when
    server becomes available again
  + Server version bump to 0.2.0

  BUG FIXES:
  - Fixed stale WebSocket callbacks causing ghost reconnects on server (737 app)
  - Fixed missing exec_led message handler in 737 app
  - Fixed CLR confirmation dialog race condition on app close (737 app)
  - Fixed SimConnect_CallDispatch not checking return value on MSFS disconnect

Version 0.1.0 — March 2026
---------------------------
  Initial release.
  + PMDG 777 CDU screen live on Android tablet
  + PMDG 737 CDU screen live on Android tablet
  + Full key input (all CDU buttons) for 737 and 777
  + Auto-discovery via mDNS (no manual IP needed on most networks)
  + Manual IP fallback with retry
  + WiFi auto-reconnect
  + Modern server GUI with connection status indicators
  + System tray support
  + Activity log with filter
  + Touch calibration system
  + Sound and haptic feedback
  + Works on Android 4.4 through Android 14


================================================================================
  SUPPORT & FEEDBACK
================================================================================

  GitHub:   https://github.com/diymarcus/FlightPadFMC
  Website:  silencediy.com

  If you enjoy FlightPadFMC, consider supporting development:
  https://www.paypal.com/donate?hosted_button_id=QD3R46HS9RZ3L

================================================================================
