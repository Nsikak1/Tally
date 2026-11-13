# Tally — school library scanner

Open this folder in Android Studio (File > Open). First sync downloads Gradle 8.11.1 and dependencies.

Package: ng.name.nsikak.libaccession  ·  minSdk 25  ·  Kotlin + Compose + Material 3

## Server side (once, on the Avahi box)
    sudo cp server/library.service /etc/avahi/services/library.service
    avahi-browse -rt _library._tcp        # should list the service on port 4321
    sudo ufw allow 5353/udp               # only if ufw is on
The FastAPI server must listen on 0.0.0.0 (not 127.0.0.1).

## Cleartext allow-list
res/xml/network_security_config.xml and CleartextPolicy.ALLOWED_HOSTS (data/settings/ServerConfig.kt)
must list the same hosts: 192.168.1.117 and school.local.

## Icon
docs/gen_icon.py regenerates the adaptive-icon vector paths (pip install shapely cairosvg).

## Testing through a tunnel (any https:// domain works)
On the server, start the API as usual (port 4321), then in another terminal ONE of:
    cloudflared tunnel --url http://localhost:4321        # prints https://<words>.trycloudflare.com
    ssh -R 80:localhost:4321 localhost.run                # prints https://<id>.lhr.life
Check from anywhere:  curl https://<that-address>/health   ->  {"ok":true}
In the app: Settings > Server address > paste the https:// address > Test connection > Save.
Plain http:// is only allowed for 192.168.1.117 and school.local (network_security_config.xml).

## Milestone 2: scanner
Home > Scan a book. CameraX LifecycleCameraController + MlKitAnalyzer (view-referenced coordinates),
bundled ML Kit barcode model. Only valid ISBN-13 (EAN-13 starting 978/979, correct check digit) and valid
ISBN-10 are drawn and selectable. Tap a box to select it; tap anywhere to focus. ML Kit zoom suggestions are on.
Camera testing needs a real device (Waydroid and the server have no camera).

## Milestone 2.5 / 6 additions
- Barcode boxes are smoothed (dead zone + easing + short persistence), so hand shake no longer makes them dance.
- Own auto-zoom (zooms in when the biggest ISBN is small), pinch zoom, torch, HD analysis toggle, type-an-ISBN.
- Text / handwriting mode: Capture -> draw (or tap) a box -> edit the text -> Confirm & save.
  Accession mode fixes O->0, l/I->1 and checks LETTERS-DIGITS (AccessionFormat.kt). Any-text mode leaves the text alone.
- Every confirmed crop + corrected label goes to <app files>/ocr_samples (labels.csv + crop_*.png).
    adb pull /sdcard/Android/data/ng.name.nsikak.libaccession/files/ocr_samples
  or tap "Share samples" in the app.

## PaddleOCR (PP-OCRv6) handwriting reader
    bash scripts/fetch_models.sh                # small + medium (default, ~95 MB in the APK)
    bash scripts/fetch_models.sh small          # only small  (~21 MB, faster)
    bash scripts/fetch_models.sh medium         # only medium (~73 MB, most accurate on handwriting)
Run it before ./gradlew assembleRelease. Each size goes into app/src/main/assets/ppocr/<size>/ (an older single-model
layout is moved into its folder automatically). Choose the size in the app: Settings > Handwriting reader.
The app reads your drawn box with the Paddle recogniser (ONNX Runtime) and uses ML Kit only to find text lines.
Accession mode decodes with a restricted alphabet (A-Z, a-z, 0-9, hyphen) so it cannot invent other characters.

## Layout
Portrait: camera above, panel below. Landscape: camera left, panel right. The back button and the
Barcode | Text switch sit on top of the camera preview.


## Updating (v0.7.0 on)
Server folder `~/dev/Tally` is reused, never recreated:

    cd ~/dev && unzip -oq Tally-vX.zip                                  # overwrite changed files only
    cd Tally && xargs -a docs/REMOVED_FILES.txt -r rm -f --             # delete files removed in newer versions
    bash scripts/build.sh                                              # build + dist/Tally.apk + sha256

Phone (Termux), only the difference is downloaded when the previous Tally.apk is still in Downloads:

    rsync -P --partial --stats ils@ssh.nsikak.name.ng:~/dev/Tally/dist/Tally.apk ~/storage/downloads/Tally.apk
    sha256sum ~/storage/downloads/Tally.apk

Server update (online lookup, api.py, service file): `bash ~/dev/Tally/server/install_update.sh`, see server/README.md.
# Tally
