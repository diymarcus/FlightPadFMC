================================================================================
  FlightPadFMC -- QUICK START
================================================================================

Turn an Android tablet into a virtual FMC / CDU / MCDU for MSFS 2020/2024.
Supports PMDG 737, PMDG 777, FlyByWire A320, and Fenix A320.

This file is a quick install reference only. For full instructions,
troubleshooting, options.ini paths, calibration, changelog, and credits,
see:

  - README.md  (in this folder)
  - https://github.com/diymarcus/FlightPadFMC


WHAT'S IN THIS ARCHIVE
----------------------
  FlightPadFMCServer.exe                -- runs on your Windows sim PC
  737PMDG.apk                           -- Android app for PMDG 737
  777PMDG.apk                           -- Android app for PMDG 777
  FBWA320.apk                           -- Android app for FlyByWire A320
  FenixA320.apk                         -- Android app for Fenix A320
  README.md                             -- full documentation
  README.txt                            -- this file
  LICENSE                               -- MIT license


GENERAL SETUP (any aircraft)
----------------------------
  1. Copy FlightPadFMCServer.exe somewhere on your Windows PC and run it.
  2. Sideload the matching .apk on your Android device.
  3. Make sure the PC and the tablet are on the same WiFi / LAN.
  4. Open the app -- it auto-discovers the server via mDNS.
     If discovery fails, enter the PC's IP manually in the app settings.

You also need to enable a few things per aircraft -- pick your aircraft
below. You only need to do the section(s) that match what you fly.


PMDG 737 / 777
--------------
  1. Load the PMDG aircraft in MSFS fully past "ready to fly" once, so
     PMDG generates its options.ini file.

  2. Open the options.ini for that aircraft (paths in README.md) and
     add this section, with a TRAILING BLANK LINE at the end of the
     file (PMDG drops the [SDK] section if there is no trailing newline):

        [SDK]
        EnableDataBroadcast=1
        EnableCDUBroadcast.0=1

  3. Sideload 737PMDG.apk or 777PMDG.apk on your Android device.

  (As of v0.3.0, the MobiFlight WASM module is no longer needed for the
  PMDG 737 — all bezel annunciators read directly from the PMDG SDK.)


FLYBYWIRE A320
--------------
  1. Install FlyByWire A32NX (free, via the FlyByWire installer at
     https://flybywiresim.com/download/).

  2. Make sure FlyByWire SimBridge is running (the FBW installer
     starts it automatically). It listens on localhost:8380.

  3. Sideload FBWA320.apk on your Android device.

  No PMDG SDK or MobiFlight WASM needed.


FENIX A320
----------
  1. Install Fenix A320 v2 (paid add-on).

  2. Load Fenix in MSFS once -- its built-in GraphQL server starts
     automatically on localhost:8083, no extra setup.

  3. Sideload FenixA320.apk on your Android device.

  No PMDG SDK, no MobiFlight WASM, and no SimBridge needed.


WHAT'S NEW IN THIS RELEASE
--------------------------
Version 0.3.0 -- May 2026

  NEW:
  + PMDG 737 NG3 bezel annunciators end-to-end -- MSG, OFST, CALL,
    FAIL drawn on the keypad bezel alongside EXEC. Same pipeline as
    the 777 annunciators. Captain CDU only.
  + Server auto-close when MSFS exits -- opt-in setting, ~10 s
    debounce. File -> Settings -> "Close server when MSFS exits".
    Default OFF so existing behaviour is preserved.
  + Optional auto-launch with MSFS via exe.xml -- see STEP 4 in
    README.md. Pair with the new auto-close setting and the server
    follows MSFS without any manual start/stop.
  + 737 EXEC LED now reads via the PMDG NG3 SDK -- MobiFlight WASM
    module no longer required for any aircraft.
  + Fresh-client annunciator state replay on identify -- any
    annunciator already lit when the Android app connects (notably
    PMDG NG3 cold-and-dark MSG) appears immediately.

  IMPROVEMENTS:
  + Activity log noticeably quieter -- three per-tick CDU-area
    dispatch DEBUG lines removed. State changes still log at INFO.
  + Calibration changes take effect immediately on return to the
    CDU, no restart needed.

  CHANGES:
  + Server version bumped to 0.3.0; all 5 APKs bumped to
    versionCode 2 / versionName "0.3.0".

  REMOVED:
  - MobiFlight WASM Module dependency (and the bundled
    Community/mobiflight-event-module/ folder -- no longer ships).

For older versions, see the full CHANGELOG in README.md or:
  https://github.com/diymarcus/FlightPadFMC/releases


SUPPORT
-------
  GitHub:  https://github.com/diymarcus/FlightPadFMC
  Website: https://silencediy.com

  If FlightPadFMC works for you and you'd like to support development:
  https://www.paypal.com/donate?hosted_button_id=QD3R46HS9RZ3L


================================================================================
  For everything else (mDNS firewall, calibration, troubleshooting,
  full changelog, credits, license), open README.md.
================================================================================
