# Push to Team GitHub

An Android app that helps a FIRST LEGO League (FLL) team save their LEGO SPIKE Prime code, meeting notes, and robot designs to a shared GitHub repository — without any Git knowledge.

## What It Does

| Feature | Description |
|:---|:---|
| **Save Robot Code** | Share a `.llsp3` project from the SPIKE app → app auto-detects the project name, asks who worked on it, and pushes to GitHub. |
| **Time Machine** | Browse past checkpoints of any project. Restore any version by tapping "Open this in SPIKE." Nothing is ever deleted. |
| **Documentation Upload** | Share photos, PDFs, or notes from Camera, Google Keep, Word, Drive, etc. → picks a category (Meeting Notes / Robot Design / Innovation Project) and commits. |
| **Contributor Attribution** | Tap name chips to record who worked on each push. Names "stick" for the day so you don't re-select every time. |

## Screens

### 1. Team Code (Home / Time Machine)
The launcher screen. Shows all project folders from the repo with their last push info. Pull to refresh. Tap **Open in SPIKE** to download the latest, or **View Checkpoints ⏪** to browse history and restore older versions.

### 2. Save Checkpoint (Share from SPIKE)
Triggered when a kid shares a `.llsp3` from SPIKE. Auto-fills the project name. Select contributor chips, optionally pick which robot, write a comment about what changed, and push.

### 3. Documentation Upload
Triggered when sharing images, PDFs, or text from any app. Pick a category, add a title, select contributors, and commit.

### 4. Setup (Settings)
One-time coach setup: GitHub token, repo details, tablet name, team roster, and optional robot nicknames.

---

## Setup Instructions

### 1. Create the GitHub Repository

Create a repository (private is fine) with this folder structure:

```
your-repo/
├── robot-game/       ← Base path for robot code (projects go here as sub-folders)
│   └── design/       ← Robot design photos
├── meeting-notes/    ← Meeting notes, photos, etc.
├── research-project/ ← Innovation project documentation
└── planning/         ← Optional: team planning docs
```

### 2. Create a Safe GitHub Token

> **⚠️ Important:** This token will be on a tablet used by kids. Scope it tightly.

1. Go to **GitHub → Profile → Settings → Developer settings**.
2. **Personal access tokens → Fine-grained tokens → Generate new token**.
3. **Token name:** `FLL Tablet - Robot Game Sync`
4. **Expiration:** End of season (e.g. 90 days).
5. **Repository access:** **Only select repositories** → pick your team repo.
6. **Permissions → Repository permissions:**
   - ✅ **Contents: Read and write** (allows pushing code/files).
   - ✅ **Metadata: Read-only** (auto-set, required).
   - ❌ Everything else: **No access**.
7. **Account permissions:** All **No access**.

### 3. Install & Configure the App

1. Build and install the APK on each team tablet.
2. Open the app → it will redirect to Settings on first launch.
3. Enter:
   - **GitHub token** (from step 2)
   - **Repo owner** (your GitHub username)
   - **Repo name**
   - **Branch** (usually `main`)
   - **Base path** (usually `robot-game`)
   - **Tablet name** (e.g. `Tablet A`, `Dad's Tablet`)
   - **Team roster** (comma-separated, include coaches: `Fido, Whiskers, Polly, Bubbles, Coach Owl`)
   - **Robot nicknames** (optional: `Robot Alpha, Robot Beta`)
4. Tap **Test Connection** to verify everything works.

---

## Security Model

This app is designed to be **safe for kids ages 10–13** to use unsupervised:

| Threat | How It's Handled |
|:---|:---|
| Kid tries to delete the repo | Token has no `Administration` permission → GitHub returns 403. |
| Kid tries to access coach's other repos | Fine-grained PAT is scoped to exactly 1 repository → other repos are invisible. |
| Kid tries to rewrite history | GitHub Contents API only creates forward commits; it has no force-push capability. |
| Kid tries to delete the main branch | GitHub permanently forbids deleting the default branch, even without rulesets. |
| Kid pushes garbage over a working file | Every past version is preserved in Git history. Recoverable via the Time Machine or `git revert` on the coach's laptop. |
| Token is extracted from the tablet | Token is stored in `EncryptedSharedPreferences` (Android Keystore). Not readable as plain text on disk. |

> **Coach tip:** Run `git pull` on your laptop periodically as an additional backup.

---

## How It Works (Technical)

### File Layout in the Repo

Each time a kid pushes code, the app commits:

```
robot-game/
└── Run 1/
    ├── Run 1.llsp3           ← Latest version (overwritten each push)
    ├── .github-issue         ← Optional: {"issue": 7} (coach sets once)
    └── extracted/            ← Full mirror of the .llsp3 ZIP contents
        ├── project.json      ← Scratch blocks (pretty-printed for diffs)
        ├── manifest.json     ← Project metadata (pretty-printed)
        ├── icon.svg          ← Project icon/preview
        └── ...               ← Any other files inside the archive
```

### Commit Message Format

```
Run 1 (#7) [Fido, Whiskers] (Tablet A, Robot Alpha) — 2026-09-13 — Tuned gyro turn
```

- **Project name** + **issue link** (if associated) + **contributors** + **tablet** (+ optional **robot**) + **date** + **comment**.

### Version History

Git's commit log IS the version history. The Time Machine screen provides a kid-friendly interface to browse it. Restoring an old version creates a new forward commit — nothing is ever deleted.

---

## Building

```powershell
# Set JAVA_HOME if needed
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Build debug APK
.\gradlew.bat assembleDebug

# Output: app\build\outputs\apk\debug\app-debug.apk
```

## License

Internal tool for FLL team use.
