# B612 Mono Font Installation

## Required for FMC777 App

The FMC777 app uses **B612 Mono** font for authentic CDU display appearance.

---

## Download Font

1. Go to: https://www.b612-font.com/
2. Download the font package
3. Extract the ZIP file

---

## Install Font in Project

**Required file:** `B612Mono-Regular.ttf`

**Location:** `FMC777/app/src/main/assets/fonts/`

### Steps:

1. Copy `B612Mono-Regular.ttf` to:
   ```
   FMC777/app/src/main/assets/fonts/B612Mono-Regular.ttf
   ```

2. Verify file exists:
   ```
   FMC777/
   └── app/
       └── src/
           └── main/
               └── assets/
                   └── fonts/
                       └── B612Mono-Regular.ttf  ← Should be here
   ```

3. Rebuild APK in Android Studio:
   - Build → Rebuild Project

---

## Font License

- **License:** Open Font License (OFL)
- **Usage:** Free for personal and commercial use
- **Attribution:** Not required but appreciated

---

## What Happens Without This Font?

✅ **App will still work** - Falls back to system Monospace font

⚠️ **CDU display will look different** - Uses Android's default Monospace

⚠️ **Character distinction may be worse** - B612 Mono has better 0/O, 1/l/I distinction

---

## Font Features

| Feature | Benefit |
|---------|---------|
| **Monospace** | All characters same width (perfect for CDU grid) |
| **Aviation-designed** | Created for aircraft displays |
| **High readability** | Clear, distinct characters |
| **Open license** | Free for commercial use |

---

## Character Comparison

| Character | B612 Mono | System Monospace |
|-----------|-----------|------------------|
| **0** (zero) | ⦿ Slashed | ○ Oval |
| **O** (letter) | ○ Round | ○ Oval |
| **1** (one) | │ With base | │ Simple |
| **l** (lowercase L) | │ With hook | │ Simple |
| **I** (uppercase i) | │ With serifs | │ Simple |

**B612 Mono makes ambiguous characters much clearer!**

---

## Troubleshooting

### Logcat shows "B612 Mono not found in assets"

**Solution:**
1. Check file exists: `FMC777/app/src/main/assets/fonts/B612Mono-Regular.ttf`
2. Check filename is **exactly** `B612Mono-Regular.ttf` (case-sensitive)
3. Clean and rebuild: Build → Clean Project → Rebuild Project
4. Check APK contains font: Open APK with ZIP tool, look in `assets/fonts/`

### Font looks wrong after installation

**Solution:**
1. Uninstall app from device/emulator
2. Rebuild APK: Build → Rebuild Project
3. Reinstall fresh APK

---

## Alternative Fonts (If B612 Unavailable)

If you can't use B612 Mono, these fonts are similar:

1. **Roboto Mono** (Google Fonts) - Free
2. **Source Code Pro** (Adobe) - Free
3. **Fira Mono** (Mozilla) - Free
4. **Inconsolata** - Free

**To use alternative:**
1. Download `.ttf` file
2. Place in `assets/fonts/` folder
3. Update `CDUView.java` line 150:
   ```java
   Typeface.create("fonts/YourFont-Regular.ttf")
   ```

---

*Last Updated: 29 March 2026*
*FMC777 Project - Virtual FMC for PMDG 777X*
