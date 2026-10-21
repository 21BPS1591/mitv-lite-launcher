# Setup guide: MiTV Lite Launcher on a Xiaomi Mi TV

This installs the launcher and, optionally, removes Xiaomi/Google bloatware so the TV runs with far less RAM. Everything is reversible.

> **Why a script?** Android does not let an app remove or disable system apps by itself. The scripts do that over `adb` from your computer. You only run one command.

## What you need

- A Xiaomi Mi TV (developed and tested on Android 9) and a computer on the **same network** (Wi-Fi or Ethernet).
- **adb** (Android platform-tools): download from <https://developer.android.com/tools/releases/platform-tools>, unzip, and add the folder to your `PATH` (or run the script from inside that folder).
- The files from this repo (`git clone` or *Code → Download ZIP*) and the APK from the [Releases page](../../releases).

## 1. Enable network debugging on the TV

1. **Settings → Device Preferences → About**, click **Build** 7 times ("You are now a developer").
2. **Settings → Device Preferences → Developer options**, turn on **USB debugging** and **Network debugging** (called *ADB debugging over network* on some versions).
3. Note the TV's IP address (**Settings → Network**), e.g. `192.168.1.128`.

## 2. Run the installer

Put the APK in the repo folder, then:

**Windows**
```bat
install.bat 192.168.1.128
```

**macOS / Linux / Git Bash**
```bash
./install.sh 192.168.1.128
```

The first time, the TV shows **"Allow USB debugging?"**. Choose **Always allow** and run the command again if it stopped.

The script will, in order:

1. install the launcher,
2. set it as the default home screen (and verify it worked before continuing),
3. uninstall the bloatware listed in [`debloat/remove.txt`](debloat/remove.txt) for the current user,
4. disable the stock launchers listed in [`debloat/disable.txt`](debloat/disable.txt).

Press **Home** on the remote. Done.

Want only the launcher, no debloating? `./install.sh <IP> --keep-bloat` (Windows: `set KEEP_BLOAT=1` first).

## What gets removed

| Package | What it is |
|---|---|
| `com.mitv.tvhome.atv`, `com.mitv.tvhome.mitvplus` | Xiaomi PatchWall home and Mi+ |
| `com.google.android.katniss` | Google voice search / Assistant (the remote's mic button stops working) |
| `com.google.android.tvrecommendations`, `com.google.android.backdrop` | Home recommendations, ambient backdrop |
| `com.google.android.youtube.tv`, `youtube.tvmusic`, `com.google.android.videos`, `com.google.android.play.games` | Stock YouTube, YT Music, Play Movies, Play Games |
| `com.google.android.marvin.talkback`, `com.google.android.tts` | Screen reader and text-to-speech |
| `com.mitv.videoplayer`, `com.mitv.gallery`, `com.mitv.dream`, `com.xiaomi.mimusic2`, `com.xiaomi.mitv.handbook` | Xiaomi video player, gallery, screensaver, music, manual |
| `com.miui.tv.analytics`, `com.xiaomi.statistic` | Xiaomi telemetry |

Disabled (not removed): `com.google.android.tvlauncher`, `leanbacklauncher` (+ `.recommendations`), `com.google.android.apps.tv.launcherx`.

**Check before you run:** this removes stock YouTube and voice search. Install a replacement first if you need one (for example [SmartTube](https://github.com/yuliskov/SmartTube)). Edit the `.txt` files to keep anything you want: delete the line.

## Undo everything

```bash
./restore.sh 192.168.1.128      # Windows: restore.bat 192.168.1.128
```

This re-enables the stock launchers and restores every removed app from the TV's system image (nothing was deleted from the TV's storage; `pm uninstall -k --user 0` only hides the app for your user). A factory reset also restores everything.

## Troubleshooting

- **`Cannot reach <IP>:5555`**: network debugging is off, wrong IP, or the TV is on a different network. Re-check step 1.
- **`unauthorized`**: accept the prompt on the TV screen.
- **Black screen after Home**: never disable all home apps without a working launcher. The script verifies this before disabling anything; if it ever happens, run `restore.sh`.
- **Other Mi TV models**: package names can differ. List what's installed with `adb shell pm list packages` and edit `debloat/*.txt`.
- The now-playing bar supports Spotify only.
