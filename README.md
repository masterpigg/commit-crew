# Push to Team GitHub

A tiny Android app for our FIRST LEGO League team. It lets the kids save their
SPIKE Prime robot code to GitHub with **one tap after Share** — no git, no
logins, no merge conflicts.

## What it does

1. In the LEGO SPIKE app, a kid opens their project and taps **Share**.
2. They pick **Push to Team GitHub** from the share sheet.
3. The app asks three things:
   - **Which robot?** (Robot 1 / Robot 2)
   - **What did you change?** (a short required comment)
   - taps **Commit & Push**
4. The app commits the project to the team repo and shows a ✅ with a link.

Each push writes three kinds of files under the robot's folder:

```
<base-path>/<robot>/<robot>.llsp3              # latest (overwritten each push)
<base-path>/<robot>/history/<robot>-<timestamp>.llsp3   # immutable snapshot
<base-path>/<robot>/extracted/project.json     # readable Scratch program (for diffs)
<base-path>/<robot>/extracted/manifest.json    # readable project manifest
```

The `.llsp3` is a binary ZIP, so the extracted `project.json` is what makes
GitHub diffs between sessions actually readable. Great for the Robot Design
judges and the engineering notebook.

Commit messages look like:

```
Robot 1 — 2026-09-13 — Tuned Run 1 gyro turn, coral arm clears the wall now
```

## Why an app (and not a web page)

The SPIKE app on Android keeps projects in its own sandbox — a web page can't
reach them. But SPIKE has a **Share** button, and an installed app can register
as a share target, hold one team token in encrypted storage, and give the kids
a friction-free button. That's the whole reason this is a native app.

---

## Building it (one-time, on a computer with Android Studio)

You need **Android Studio** (already installed). It bundles the JDK and will
install the Android SDK for you.

1. **Open the project**
   - Launch Android Studio → **Open** → select `C:\Code\FLL\push-to-github`.
   - On first open, Studio downloads the Android SDK and Gradle, then writes a
     `local.properties` file pointing at the SDK. Let it finish ("Gradle sync").
2. **Build the APK**
   - Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
   - When it finishes, click **locate** to find `app-debug.apk`
     (under `app/build/outputs/apk/debug/`).

### Command-line build (optional)

Once Android Studio has installed the SDK, you can also build from a terminal:

```powershell
# Point Gradle at Android Studio's bundled JDK for this shell:
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd C:\Code\FLL\push-to-github
.\gradlew.bat assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`.

---

## Installing on the tablet

Easiest: with the tablet plugged in via USB and **USB debugging** enabled,
in Android Studio press **Run ▶** with the tablet selected. It installs and
launches the app.

Or install the APK directly:

```powershell
# adb is already installed here:
C:\Code\Android\adb-fastboot\platform-tools\adb.exe install -r `
  C:\Code\FLL\push-to-github\app\build\outputs\apk\debug\app-debug.apk
```

(You may need to allow "install from unknown sources" for the debug APK.)

---

## One-time setup in the app

Open **Push to Team GitHub** on the tablet (it's the launcher screen) and fill in:

| Field | Value for our team |
| --- | --- |
| GitHub token | a fine-grained PAT (see below) |
| Repo owner | e.g. `masterpigg` |
| Repo name | e.g. `final-first-lego-league` |
| Branch | `main` |
| Folder in repo for robot code | `robot-game` |
| Name for Robot 1 | e.g. `robot-1` or a team nickname |
| Name for Robot 2 | e.g. `robot-2` |

Tap **Save setup**, then **Test connection** to confirm the token can reach the
repo.

### Creating the GitHub token (do this yourself, not the kids)

Use a **fine-grained personal access token** scoped to just the one repo:

1. GitHub → **Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token**.
2. **Resource owner:** the account/org that owns the repo.
3. **Repository access:** *Only select repositories* → pick the robot-code repo.
4. **Permissions → Repository permissions → Contents: Read and write.**
5. Set a sensible expiration (e.g. end of season) and generate.
6. Copy the token into the app's **GitHub token** field once.

Because it's scoped to a single repo with only Contents write, a leaked token
can't touch anything else, and you rotate exactly one token if needed.

---

## Notes & limits

- The token is stored with `EncryptedSharedPreferences` (Android Keystore), so
  it isn't sitting in plain text on the tablet.
- "Latest + timestamped history + extracted json" all go up under one commit
  message, so the repo history reads like a session log.
- If the shared file isn't a valid `.llsp3` ZIP, the raw file is still committed;
  the extraction step just no-ops.
- This app only ever talks to `api.github.com`. It doesn't send code anywhere else.
```
