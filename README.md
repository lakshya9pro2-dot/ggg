# LiteWebView 🚀

An ultra-lightweight Android app engineered for older and low-end devices. Built for minimal RAM/CPU footprint, low battery consumption, fast startup, and 24/7 stable operation.

---

## 🌟 Key Features

1. **Embedded NanoHTTPD Server**: Runs on `http://0.0.0.0:8080` (accessible via `http://127.0.0.1:8080` and `http://<device-ip>:8080`) to accept local and remote commands and control playback.
2. **Android System WebView**: Native system WebView with no bundled Chromium bloat, minimal memory footprint, and low CPU usage.
3. **Automatic HLS (.m3u8) Stream Detection**: Inspects network requests and extracts the primary first HLS playlist (`application/vnd.apple.mpegurl` and `.m3u8` URLs) per session without being overwritten by child/variant streams.
4. **Ad & Tracker Blocking**: Real-time request interception filtering out known advertising and telemetry servers.
5. **Ultra Lite Mode**: Blocks images (returns 1x1 transparent pixels to maintain layout without downloading images), disables non-essential WebView features, and optimizes memory usage.
6. **Zero Idle Overhead**: Event-driven architecture with zero background polling loops and proper lifecycle management (`onPause` / `onDestroy`).
7. **CI/CD Built with GitHub Actions**: Automatically tests and compiles both Debug and Release APKs on push.
8. **Android 7 OS Optimized with Live Logs Console**: Includes an on-screen live terminal log view replacing WebView rendering to conserve memory on older Android 7+ devices, with instant toggle between Logs and WebView.
9. **Stream Header Extraction**: Automatically captures and returns required `Origin` and `Referer` headers alongside `.m3u8` URLs in API responses.

---

## 📡 Localhost API Endpoints (`http://127.0.0.1:8080`)

### 1. Load URL in WebView
```http
GET /?url=https://example.com/video
```
or
```http
GET /url=https://example.com/video
```
**Response:**
```json
{
  "success": true,
  "url": "https://example.com/video"
}
```

### 2. Extract HLS Stream URL
```http
GET /extract?url=https://example.com/video&timeout=5
```
**Response (Success):**
```json
{
  "success": true,
  "type": "hls",
  "url": "https://example.com/video/master.m3u8",
  "contentType": "application/vnd.apple.mpegurl",
  "headers": {
    "Origin": "https://example.com",
    "Referer": "https://example.com/video"
  }
}
```
**Response (Not found):**
```json
{
  "success": false,
  "url": null,
  "headers": null,
  "error": "HLS stream not detected"
}
```

### 3. Server & Playback Status
```http
GET /status
```
**Response:**
```json
{
  "success": true,
  "status": "running",
  "host": "0.0.0.0",
  "port": 8080,
  "liteMode": true,
  "currentUrl": "https://example.com/video",
  "hlsDetected": true,
  "hlsUrl": "https://example.com/video/master.m3u8",
  "headers": {
    "Origin": "https://example.com",
    "Referer": "https://example.com/video"
  }
}
```

### 4. Toggle Lite Mode
```http
GET /mode?lite=on
GET /mode?lite=off
```
**Response:**
```json
{
  "success": true,
  "liteMode": true
}
```

---

## 🛠️ Project Structure

```text
├── .github/workflows/
│   └── build-apk.yml          # GitHub Actions workflow for automatic APK builds
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lite/streamview/
│   │   │   │   ├── MainActivity.kt           # Lifecycle, UI & WebView manager
│   │   │   │   ├── server/LiteHttpServer.kt  # NanoHTTPD REST API (127.0.0.1:8080)
│   │   │   │   ├── interceptor/RequestInterceptor.kt # Ad/image blocker & HLS sniffer
│   │   │   │   └── store/HlsUrlStore.kt      # Thread-safe HLS state store
│   │   │   ├── res/                          # Lightweight layouts, colors, styles
│   │   │   └── AndroidManifest.xml           # Cleartext traffic & Internet permissions
│   │   └── test/                             # Unit tests for Interceptor & Store
│   ├── build.gradle.kts                      # R8 shrinking & dependencies
│   └── proguard-rules.pro                    # ProGuard / R8 rules
├── gradle/wrapper/                           # Self-contained Gradle 8.5 wrapper
├── build.gradle.kts                          # Root build script
├── settings.gradle.kts                       # Settings file
└── README.md
```

---

## 🚀 How to Push to GitHub & Build the APK

### Step 1: Create a new repository on GitHub
1. Go to [github.com/new](https://github.com/new).
2. Enter repository name (e.g. `lite-webview`).
3. Leave it empty (do **not** initialize with README or .gitignore).
4. Click **Create repository**.

### Step 2: Initialize Git and Push from Terminal
Run the following commands inside this project directory:

```bash
# Initialize git repository
git init

# Stage all files
git add .

# Create initial commit
git commit -m "Initial commit: Lightweight Android WebView with NanoHTTPD and HLS detection"

# Set default branch to main
git branch -M main

# Add your GitHub repository remote (replace <YOUR_USERNAME> and <YOUR_REPO>)
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git

# Push code to GitHub
git push -u origin main
```

### Step 3: Download the Compiled APK from GitHub Actions
1. Open your repository on GitHub.
2. Click the **Actions** tab at the top.
3. Select the running or completed **Build Android APK** workflow run.
4. Scroll down to the **Artifacts** section at the bottom of the page.
5. Click **LiteWebView-Debug-APK** or **LiteWebView-Release-APK** to download your APK!
6. To trigger a formal release, create a Git tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`) and GitHub Actions will publish the APK files directly to the GitHub Releases page!
