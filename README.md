# PHOTOgui
A computer based, self-hosted photo gallery written in java. You can change how much storage is allocated, and you can reach it from anywhere using Tailscale.

# Set up (Windows 10)
Install Java (JDK 17 or newer). In a Command Prompt:
`winget install EclipseAdoptium.Temurin.21.JDK`
Then close and reopen the Command Prompt. Check with `javac -version`.
Edit `config.properties`. Set `password`, `quotaGB` (storage you allow), and optionally `dataDir`
(e.g. `D:/MyPhotosData`, with forward slashes).
Double-click `run.bat`. It builds the app and starts the server.
When Windows Firewall asks, allow access on private networks.
Open http://localhost:8080 and sign in.
