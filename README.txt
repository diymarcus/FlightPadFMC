================================================================================
  FlightPadFMC -- QUICK START
================================================================================

Turn an Android tablet into a virtual FMC / CDU / MCDU for MSFS 2020/2024.
Supports PMDG 737, PMDG 777, iFly 737 MAX, FlyByWire A320 and Fenix A320.

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
  777PMDG-Silence.apk                   -- PMDG 777 variant (bottom-pinned
                                           skin, installs alongside 777PMDG)
  iFly737MAX.apk                        -- Android app for iFly 737 MAX
  FBWA320.apk                           -- Android app for FlyByWire A320
  FenixA320.apk                         -- Android app for Fenix A320
  SDK2020\ / SDK2024\                   -- SimConnect.dll for each sim
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
        EnableCDUBroadcast.1=1

     (The .1 line enables the First Officer CDU used by the new
     swipe-between-CDUs feature -- see WHAT'S NEW below.)

  3. Sideload 737PMDG.apk or 777PMDG.apk on your Android device.

  (As of v0.3.0, the MobiFlight WASM module is no longer needed for the
  PMDG 737 — all bezel annunciators read directly from the PMDG SDK.)


IFLY 737 MAX
------------
  1. Install the iFly 737 MAX and make sure the "iFly Plugin" add-on is
     enabled in MSFS Add-Ons (it ships with the iFly install).

  2. Sideload iFly737MAX.apk on your Android device.

  No PMDG SDK setup, no SimBridge, no MobiFlight needed.


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
Version 0.4.1 -- August 2026

  NEW:
  + Captain <-> FO CDU swipe (PMDG 737 + 777) -- swipe left on the CDU
    display to switch to the First Officer's CDU, swipe right to return
    to the Captain's. A green CAPT CDU / FO CDU label confirms the
    switch. New "CDU Side" option in the app settings sets the default
    side applied on every connect -- ideal for a dedicated FO tablet.
    Two tablets can connect at once, one per seat.
    REQUIRES EnableCDUBroadcast.1=1 in the aircraft's options.ini
    (see the PMDG section above).

  IMPROVEMENTS:
  + Smarter "Android app outdated" check -- the server now tracks the
    minimum compatible app version per aircraft. Only the PMDG 737 and
    777 apps need the 0.4.1 update; the FBW A320, Fenix A320 and iFly
    737 MAX 0.4.0 apps remain fully compatible.

  CHANGES:
  + Server bumped to 0.4.1; PMDG 737 / 777 / 777-Silence APKs bumped
    to versionCode 4 / versionName "0.4.1". FBW / Fenix / iFly APKs
    unchanged from 0.4.0 -- no reinstall needed for those.

Previous release (v0.4.0, May 2026): iFly 737 MAX support, automatic
aircraft detection, the Server Console health panel, tablet-side status
overlays, and a visual-polish round across all apps.

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
