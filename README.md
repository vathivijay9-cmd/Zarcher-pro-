# Hello Android Starter

A modern, robust, and clean Android application template powered by **Jetpack Compose**, **Material 3**, and **Modern Android Development (MAD)** best practices. 

This repository is pre-configured for public installation, automated continuous integration, and seamless local or cloud-based building.

---

## 🚀 Downloading and Installing the App

### 1. Pre-built APKs (GitHub Actions)
Every time a change is pushed to this repository, a public installation package (APK) is automatically compiled and uploaded.
1. Go to the **Actions** tab of this GitHub repository.
2. Click on the latest run of the **Android CI Build** workflow.
3. Scroll down to the **Artifacts** section at the bottom of the page.
4. Download the `app-debug` zip file, extract it, and install `app-debug.apk` directly on compatible Android devices (minSdk 24+).

### 2. Standard Mobile Web Preview
When developing inside Google AI Studio, the live Streaming Android Emulator is automatically reloaded to show the active interactive builder.

---

## 🛠️ How to Build and Run Locally

Ensure you have **Java Development Kit (JDK) 17** or higher installed on your system.

### 1. Clone the Repository
```bash
git clone <your-repository-url>
cd hello-android-starter
```

### 2. Build the Debug APK
The standard Gradle wrapper is included in the root directory for standard, deterministic builds. Run:
```bash
# On Linux / macOS
./gradlew assembleDebug

# On Windows
gradlew.bat assembleDebug
```
The output APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Run on Connected Device / Emulator
Connect your Android device via USB/Wi-Fi (with USB Debugging enabled) or start an Android Virtual Device (AVD). Then run:
```bash
# On Linux / macOS
./gradlew installDebug

# On Windows
gradlew.bat installDebug
```

### 4. Run Local Unit & Robolectric Tests
```bash
# On Linux / macOS
./gradlew test

# On Windows
gradlew.bat test
```

---

## 🔒 Configuration & API Secrets

To prevent API keys from leaking to public controllers, this project integrates the **Secrets Gradle Plugin**. 

To use secrets (such as `GEMINI_API_KEY`):
1. Copy the template:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and fill in your custom API keys:
   ```properties
   GEMINI_API_KEY=your_actual_api_key_here
   ```
The plugin automatically injects these values into `BuildConfig` at compile-time under safe variables, fully avoiding raw strings in source control.

---

## 📂 Project Architecture

This application utilizes a clean and optimized package structure:
* **Jetpack Compose (M3)**: Beautiful declarative layouts following strict dynamic color schemes.
* **Gradle Version Catalogs**: High-performance dependency synchronization inside `gradle/libs.versions.toml`.
* **Automated Signing Configurations**: Ready-to-use fallback signing properties allowing public contributions and builds without environmental crash loops.
