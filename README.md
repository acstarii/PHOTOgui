# PHOTOgui

A personal, self-hosted photo library with a Google Photos-style web UI.
Photos live on the host computer, up to the storage limit you set. No cloud, no third-party libraries.

# Installing PHOTOgui (V0.5.0-BETA, Windows 10/11)

## Part 1: Install the app

1. **Unzip** `PHOTOgui_V0_5_0-BETA.zip` somewhere permanent, for example `C:\PHOTOgui`. Don't run it from inside the zip.
2. **Install Java.** Open a Command Prompt and run:
   `winget install EclipseAdoptium.Temurin.21.JDK`
   Close the Command Prompt and open a new one, then check with `javac -version`. It should print a version number.
3. **Edit `config.properties`** (right-click, Open with Notepad). Set these:
   - `password=` a strong password of your choice. The server won't start while it says `CHANGE_ME`.
   - `quotaGB=` how much storage you allow, in GB.
   - `dataDir=` where photos are stored, for example `D:/MyPhotosData`. Use forward slashes.
   - Leave the Faces lines alone.
4. **Double-click `run.bat`.** It builds the app and starts the server, so leave that window open. If Windows Firewall asks, allow access on **private networks**.
5. **Open http://localhost:8080** and sign in with your password.

If you're updating from an older version, unzip the new one, copy your old `config.properties` over the new one, then add the Faces lines from the new one. Your photos and settings stay in your data folder.

## Part 2: Reach it from your phone and anywhere else

1. Install **Tailscale** (tailscale.com) on this computer and on your phone, signed in to the same account.
2. On your phone, open `http://<this computer's Tailscale address>:8080`. The address starts with `100.` and is printed in the `run.bat` window when the server starts.
3. Keep the computer awake: Settings, System, Power & sleep, Sleep, **Never** (when plugged in).

## Part 3: Optional extras

- **iPhone photos (HEIC):** `winget install ImageMagick.ImageMagick`
- **Videos:** `winget install Gyan.FFmpeg`
- Reopen the Command Prompt after each, then restart `run.bat`.

## Part 4: Face recognition (optional)

1. Install **Python 3** from python.org. Tick **Add python.exe to PATH** in the installer. Python 3.11 or 3.12 is the safest choice.
2. In a Command Prompt, run:
    `winget install Microsoft.VCRedist.2015+.x64`
    `py install 3.12`
    `pip install opencv-python numpy onnxruntime`
   If `pip` isn't recognized, use `py -m pip install opencv-python numpy onnxruntime`.
4. Restart `run.bat`, then open the menu button (top left) and choose **Faces**. The face models download by themselves the first time, and the accurate one is a few hundred MB, so allow a few minutes. The Faces tab shows progress.

## To start it again later

Double-click `run.bat`. Keep its window open while you use PHOTOgui. Closing it stops the server.

## Things to add

- File Converter built in
- File Explorer (Big Update)
- Add switch button for file --> photos 
- Accessible from other devices that don't have tailscale
- Support for using multiple drives
- Seeing what pictures/files are on what drive
- Native Application to run alongside the tailscale website
