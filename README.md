# PHOTOgui

A personal, self-hosted photo library with a Google Photos-style web UI.
Photos live on the host computer, up to the storage limit you set. No cloud, no third-party libraries.

## Set up (Windows)

1. **Install Java (JDK 17 or newer).** In a Command Prompt:
   `winget install EclipseAdoptium.Temurin.21.JDK`
   Then close and reopen the Command Prompt. Check with `javac -version`.
2. **Edit `config.properties`.** Set `password`, `quotaGB` (storage you allow), and optionally `dataDir`
   (e.g. `D:/MyPhotosData`, with forward slashes).
3. **Double-click `run.bat`.** It builds the app and starts the server.
   When Windows Firewall asks, allow access on **private networks**.
4. Open **http://localhost:8080** on the host computer and sign in.

## Reach it from anywhere

Install **Tailscale** (tailscale.com) on the host computer and on your phone/laptop, signed in to the same account.
Then open `http://<host computer's Tailscale address>:8080` (starts with `100.`) from any of your devices, on any network.
The address is also printed when the server starts.

Keep this computer awake: Settings > System > Power & sleep > Sleep > Never (when plugged in).

# Setting up Faces

## Setup (once)

1. **Update PHOTOgui.** Unzip `PHOTOgui_V0_4_0-BETA.zip`. Copy your own `config.properties` over the new one, then paste in the Faces lines from the zip's `config.properties` if you want to change any, since the defaults work without them. Your photos and settings stay in your data folder.
2. **Install Python 3** from python.org. In the installer, tick **Add python.exe to PATH**, then finish the install.
3. **Install the two packages.** Open a Command Prompt and run:
   `pip install opencv-python numpy`
   If Windows says `pip` isn't recognized, run `py -m pip install opencv-python numpy` instead.
4. **Start PHOTOgui** with `run.bat`, as usual. The computer needs internet this first time. It downloads two face models (about 40 MB) into a `models` folder next to `run.bat`.
5. **Open the menu button (top left) and choose Faces.**

## What you'll see in the Faces tab

- **"Finding faces… N photos left."** It's working. The newest photos are scanned first, and people appear as they're found. A big library can take hours, but you can use PHOTOgui normally meanwhile. If you close the page, the scan keeps going on the computer.
- **A setup message with a Check again button.** Python or the packages aren't found yet. Follow the steps shown, then press **Check again**. If you installed Python after PHOTOgui was already running, press **Check again** too.
- **A message about the models.** The download failed. It names the two files to get from https://github.com/opencv/opencv_zoo and the folder to put them in. Put them there and press **Check again**.
- **"Face recognition is switched off."** Set `facesEnabled=true` in `config.properties` and restart.

## Using it

- **Open a person** by pressing their round picture to see all their photos.
- **Name** gives the person a name. If someone already has that name, it asks you to press Save again, which combines the two.
- **Combine…** joins this person with another entry for the same person. Pick the other person, then press Combine. This can't be undone.
- **Fix mistakes** puts a small ✕ on each photo. Press it on a photo that isn't this person and it leaves them.
- **Hide person** removes them from the list. They're kept under **Hidden people** at the top of the Faces tab, where you can show them again.
- **Back to the list** with **‹ Faces** at the top.

## Things to know

- People who appear in only one photo aren't listed until they show up again. People you've named are always shown. You can change this with `facesMinPhotos`.
- Only pictures are scanned, not videos.
- Photos you add later are picked up automatically within about a minute.
- If the same person shows up as several entries, use **Combine…**. If different people get mixed, use **Fix mistakes**. If it happens a lot, adjust `facesMatch` in `config.properties` (lower it to merge more, raise it to separate more). That only affects photos scanned afterwards.
- If something goes wrong, check `data/faces/worker.log`.

Your names and corrections are saved in `data/faces/people.tsv`. Deleting the `faces` folder inside your data folder makes PHOTOgui start the scan from scratch, and it never touches your photos.

## Notes

- Supported: All types of picture and video formats.
- Photos and Videos are stored in the created directory 'data' or optionally your own directory; previews in `<your_directory>/thumbs`; the list in `index.tsv`.
- **Back up `<your_directory>`**. If the disk dies, the photos are gone.
- Uploading the same photo twice is detected and ignored.
- 10 wrong passwords within 10 minutes locks sign-in for the rest of that window.

## Things to add

- File Converter built in
- File Explorer (Big Update)
- Add switch button for file --> photos 
- Accessible from other devices that don't have tailscale
- Support for using multiple drives
- Seeing what pictures/files are on what drive
- Different tile size options
- Update sifting method when uploading pictures
- Make the title in the top left the home button
