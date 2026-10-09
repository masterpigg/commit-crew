# Commit Crew

[![Build Android & iOS](https://github.com/masterpigg/commit-crew/actions/workflows/build.yml/badge.svg)](https://github.com/masterpigg/commit-crew/actions/workflows/build.yml)

An Android tablet app that teaches a FIRST LEGO League (FLL) Challenge team the basics of **Kanban** and **version control** by doing them for real. Kids move sticky-note tasks across a board, save their LEGO® SPIKE™ Prime code as checkpoints, and snap photos of meeting notes, and everything lands in the team's own GitHub repository. Nobody on the team needs to know Git.

It was built for a team of kids ages 10 to 13 and their coaches, and it is designed so a tablet can be handed to a kid without risk to the coach's GitHub account.

> **Project status:** Android is the supported platform and is used by a real team during the season. The shared Kotlin Multiplatform module also builds for iOS in CI, but there is no iOS app and iOS is **untested**.

---

## What it does

The app has three tabs, plus share targets so other apps can send files straight to GitHub.

| Tab | What kids do | What happens on GitHub |
|:---|:---|:---|
| **📋 Task Board** (home screen) | Add sticky notes, drag them between **To-Do 📌**, **Doing 🛠️** and **Done ✅**, tag who is working on each one, its category (Robot Game, Innovation, General) and the FLL Core Values it shows. Filter by category, worker or Core Value. | Each sticky note is a GitHub **issue**. Columns, workers, categories and Core Values are issue **labels**. |
| **📸 Snap Notes** | Take a photo or pick a file, optionally transcribe handwriting with on-device OCR, pick a category (Meeting Notes, Robot Design, Innovation Project), tap who contributed, and push. Browse everything saved so far. | A commit into `meeting-notes/`, `robot-game/design/` or `innovation-project/`. Large photos are compressed first. |
| **🤖 Team Code** (Time Machine) | See every robot program, when it was last pushed and by whom. **Open in SPIKE** downloads the latest copy. **View Checkpoints ⏪** lists past versions, and any of them can be reopened in SPIKE. Old projects can be archived. | Reads the commit history. Restoring a version makes a new commit, so nothing is ever deleted. |

**Saving robot code.** In the SPIKE app, tap **Share** on a project and pick **Push Robot Code to GitHub**. The app fills in the project name, asks who worked on it (names stay selected for the rest of the day), which robot it was for (optional), and what changed. It then commits the `.llsp3` file, an unzipped copy of its contents so changes show up as readable diffs, and a kid-friendly `README.md` for that project.

**Sharing documents.** Photos, PDFs, Word files and text shared from any other app (Camera, Google Keep, Drive, Word, ...) open the **Push Snap Notes / Docs to GitHub** screen.

**Voice input.** Text fields such as comments and sticky-note titles have a microphone button for speech-to-text, so kids can talk instead of type.

---

## Getting started (for coaches)

### 1. Create the team repository

Create a new GitHub repository for your team. **We strongly recommend making it private**, because it will hold your team members' first names (as labels and in commit messages) and photos of their work.

You can start with an empty repository. The first time the app connects it creates the labels it needs and adds starter `README.md` files, giving this layout:

```
your-team-repo/
├── README.md             ← Team info, season goals and roster links (fill in the blanks)
├── robot-game/           ← SPIKE Prime projects, one sub-folder per project
│   └── design/           ← Robot design photos and sketches
├── innovation-project/   ← Innovation Project research and notes
└── meeting-notes/        ← Meeting notes, whiteboard photos, reflections
```

### 2. Create a tightly scoped GitHub token

> **⚠️ This token will live on a tablet used by kids. Scope it to one repository and nothing else.**

1. On GitHub go to **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. **Token name:** something like `FLL Tablet`.
3. **Expiration:** the end of your season (for example 90 days). Two weeks before it runs out, the app shows a warning on the Task Board and in Setup, so you have time to make a new one.
4. **Repository access:** **Only select repositories**, then pick your team repository.
5. **Repository permissions:**
   - ✅ **Contents: Read and write** (saving code, notes and photos)
   - ✅ **Issues: Read and write** (the Task Board)
   - ✅ **Metadata: Read-only** (set automatically, required)
   - ❌ Everything else: **No access**
6. **Account permissions:** leave all at **No access**.

### 3. Install the app

There is no Play Store listing yet. Either:

- **Download a release:** grab the `.apk` from the latest [GitHub Release](https://github.com/masterpigg/commit-crew/releases/latest), or
- **Download a development build:** open the latest successful run of the [Build Android & iOS workflow](https://github.com/masterpigg/commit-crew/actions/workflows/build.yml) (you must be signed in to GitHub) and download the `app-debug-apk` artifact, or
- **Build it yourself** (see [Building from source](#building-from-source)).

Then install the APK on each team tablet. You will need to allow installing apps from unknown sources. The app needs Android 7.0 or newer, and the free **LEGO Education SPIKE** app for the code features.

### 4. Set up each tablet

Open the app. On first launch it goes straight to **Team GitHub Setup** (later you can reach it from the gear button in the toolbar). Fill in:

| Field | Example |
|:---|:---|
| GitHub token | the token from step 2 |
| Repo owner | your GitHub username or organization |
| Repo name | `fll-team-workspace` |
| Branch | `main` |
| Folder in repo for robot code | `robot-game` |
| Tablet name | `Tablet A`, `Coach's Tablet` |
| Team roster (comma-separated, include coaches) | `Fido, Whiskers, Polly, Bubbles, Nibbles, Coach Pigg, Coach Owl` |
| Robot nicknames (optional) | `Robot Alpha, Robot Beta` |

Tap **Test connection**, then **Save setup**. Names that start with "Coach" are shown in gray on the board so adult tasks stand out from the kids' tasks.

> **Tip:** Use first names or nicknames in the roster, not full names.

---

## Safety model

The app is meant to be safe for kids ages 10 to 13 to use without an adult watching every tap.

| Worry | How it is handled |
|:---|:---|
| A kid deletes or renames the repository | The token has no **Administration** permission, so GitHub refuses. |
| A kid reaches the coach's other repositories | The fine-grained token can see exactly one repository. |
| A kid erases history | The app only makes forward commits through the GitHub Contents API. It cannot force-push. |
| A kid deletes the main branch | GitHub never allows deleting a repository's default branch. |
| A kid saves broken code over working code | Every version stays in Git history and can be reopened from the Time Machine, or reverted from a laptop. |
| Someone copies the token off the tablet | The token is stored with `EncryptedSharedPreferences`, backed by the Android Keystore, and app backups are disabled. |

The token is only ever sent to `api.github.com`. The app has no analytics, no ads and no server of its own. Handwriting OCR runs on the device using Google ML Kit, and voice input uses Android's built-in speech recognizer.

> **Coach tip:** run `git pull` on a laptop now and then as an extra backup.

---

## How it works

### What a code push commits

```
robot-game/
└── Run 1/
    ├── Run 1.llsp3       ← Latest version (overwritten on each push; history keeps the rest)
    ├── README.md         ← Kid-friendly summary with a preview and recent history
    └── extracted/        ← Unzipped contents of the .llsp3, for readable diffs
        ├── project.json  ← Scratch blocks, pretty-printed
        ├── manifest.json ← Project metadata, pretty-printed
        └── ...           ← Icons and any other files in the archive
```

### Commit messages

```
Run 1 (#7) [Fido, Whiskers] (Tablet A, Robot Alpha) — 2026-09-13 — Tuned gyro turn
```

That is the project name, the linked issue (if a coach linked one from the Time Machine), who worked on it, the tablet and optional robot, the date, and the kid's comment. Git's commit log *is* the version history, and the Time Machine is a kid-friendly view of it.

### Task Board labels

The app creates and uses these labels in the team repository:

- `todo`, `doing`, `done` for the columns
- one label per roster name for who is working on a task
- `core-value:discovery`, `core-value:innovation`, `core-value:impact`, `core-value:inclusion`, `core-value:teamwork`, `core-value:fun` for the FLL Core Values

If the repository is linked to a GitHub Project (v2), the app also picks up the colors of that project's select-field options.

---

## Building from source

**Requirements:** JDK 17 and the Android SDK (installing [Android Studio](https://developer.android.com/studio) gives you both). The Gradle wrapper downloads Gradle itself.

```bash
git clone https://github.com/masterpigg/commit-crew.git
cd commit-crew

# Run the unit tests
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest

# Build a debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew.bat` instead of `./gradlew`. If Gradle cannot find Java, point `JAVA_HOME` at Android Studio's bundled JDK, for example `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` in PowerShell.

### Publishing a release

Pushing a tag that starts with `v` runs the [Release APK workflow](.github/workflows/release.yml). It runs the unit tests, builds a signed release APK (for installing directly on tablets) and a signed Android App Bundle (`.aab`, the format Google Play requires for uploads), and attaches both to a new GitHub Release with generated notes:

```bash
git tag v1.2.0
git push origin v1.2.0
```

The version name comes from the tag (`v1.2.0` becomes `1.2.0`) and the version code from the workflow's run number. The workflow needs a signing key, set up once:

1. Create a keystore (keep it and its passwords somewhere safe; Android only installs updates signed with the same key):
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias commit-crew -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode it: `base64 -w0 release.keystore` on Linux, `base64 -i release.keystore` on macOS, or `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))` in PowerShell.
3. In the repository, go to **Settings → Secrets and variables → Actions** and add these secrets:

   | Secret | Value |
   |:---|:---|
   | `RELEASE_KEYSTORE_BASE64` | The base64 text from step 2 |
   | `RELEASE_KEYSTORE_PASSWORD` | The keystore password |
   | `RELEASE_KEY_ALIAS` | The alias, e.g. `commit-crew` |
   | `RELEASE_KEY_PASSWORD` | The key password (the same as the keystore password unless you set a different one) |

Never commit the keystore; `*.keystore` is already in `.gitignore`. To build a signed release locally, set `RELEASE_KEYSTORE_PATH` and the three password and alias variables before running `./gradlew :app:assembleRelease` (APK) or `./gradlew :app:bundleRelease` (App Bundle). Without them the build produces unsigned files.

### Project layout

| Module | Contents |
|:---|:---|
| `app/` | The Android app (Kotlin, View-based UI): activities, GitHub REST client, encrypted settings, `.llsp3` extraction |
| `shared/` | Kotlin Multiplatform code shared with a future iOS app: data models, FLL Core Values, README templates, owner colors |
| `.github/workflows/build.yml` | CI: runs unit tests and builds the debug APK on every push and pull request, and builds the shared module for iOS on macOS |
| `.github/workflows/release.yml` | Release: on a `v*` tag, builds a signed release APK and App Bundle and publishes them as a GitHub Release |

---

## Contributing

Issues and pull requests are welcome, especially from other FLL coaches and teams. Please:

- Run the unit tests before opening a pull request.
- Use made-up names in examples, tests and screenshots, never real kids' names.
- Keep the app safe for unsupervised kids: no new token permissions without a good reason.

## Trademarks

FIRST® and FIRST® LEGO® League are registered trademarks of FIRST. LEGO® and SPIKE™ are trademarks of the LEGO Group. This is an independent, unofficial project and is not endorsed by FIRST or the LEGO Group.

## License

Commit Crew is free software, released under the [GNU General Public License v3.0](LICENSE).

In plain terms: anyone can use, copy, change and share it for free. If someone distributes a changed version, they must release their source code under the same license, so the app and every version built from it stay free and open for everyone.

Copyright © 2026 Paul Pigg.
