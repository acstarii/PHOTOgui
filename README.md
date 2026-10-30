# PHOTOgui

A personal, self-hosted photo library with a Google Photos-style web UI.
Photos live on this computer, up to the storage limit you set. No cloud, no third-party libraries.

## Set up (Windows 10)

1. **Install Java (JDK 17 or newer).** In a Command Prompt:
   `winget install EclipseAdoptium.Temurin.21.JDK`
   Then close and reopen the Command Prompt. Check with `javac -version`.
2. **Edit `config.properties`.** Set `password`, `quotaGB` (storage you allow), and optionally `dataDir`
   (e.g. `D:/MyPhotosData`, with forward slashes).
3. **Double-click `run.bat`.** It builds the app and starts the server.
   When Windows Firewall asks, allow access on **private networks**.
4. Open **http://localhost:8080** and sign in.

## Reach it from anywhere

Install **Tailscale** (tailscale.com) on this computer and on your phone/laptop, signed in to the same account.
Then open `http://<this computer's Tailscale address>:8080` (starts with `100.`) from any of your devices, on any network.
The address is also printed when the server starts.

Keep this computer awake: Settings > System > Power & sleep > Sleep > Never (when plugged in).

## Notes

- Supported: JPG, PNG, GIF, WebP, BMP. Not yet: HEIC (iPhone) and video.
- Photos are stored in the created photo directory 'data' or optionally your own directory; previews in `<your_directory>/thumbs`; the list in `index.tsv`.
- **Back up `<your_directory>`**. If the disk dies, the photos are gone.
- Uploading the same photo twice is detected and ignored.
- 10 wrong passwords within 10 minutes locks sign-in for the rest of that window.

## Things to add

- Albums and Search Bar
- Support for all types of Raw Files, all types of videos, HEIC
- File Converter built in
- File Explorer (Big Update)
- Add switch button for file --> photos 
- Accessible from other devices that don't have tailscale
