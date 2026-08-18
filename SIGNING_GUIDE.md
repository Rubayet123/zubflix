# AI Studio Android APK Signing & Installation Guide

This guide explains why you encountered installation errors on your **Samsung S24 (Android 16)** and **Pixel 7**, how the build/signing systems interact between **AI Studio** and your local **Android Studio**, and how to guarantee successful installations on any device for future projects.

---

## 1. Why the Installation Failed (Root Cause Analysis)

When transferring a project from AI Studio to a local machine and building an APK, you hit two distinct device security and package validation mechanisms:

### Issue A: "You cannot install this app on this device" (Samsung S24 / Android 16)
This error is triggered by two main security features:
1. **Samsung Auto Blocker**: Samsung S24 devices come with a security suite called *Auto Blocker* enabled by default. It strictly blocks "sideloading" (installing APKs from file managers, web browsers, or unauthorized sources outside the Google Play Store or Galaxy Store).
2. **Signature Mismatch**: Android identifies apps by their Package Name (`applicationId`) and their **digital signature** (the cryptographic key used to sign the APK). 
   - **AI Studio** builds your preview app using a custom `debug.keystore` bundled directly in the workspace root.
   - If you previously installed the app via the AI Studio preview link, and then tried to build the app locally in **Android Studio** using your computer's local default debug key, the signatures mismatched. Android blocks this installation to prevent malicious app hijacking.

### Issue B: "There was a problem parsing the package" (Pixel 7)
This error occurs when Android's Package Installer tries to read the APK and fails. The primary reasons are:
1. **The "Test-Only" Flag**: When you click the **"Run" (Green Play button)** in Android Studio, it compiles a specialized "instant run" APK and injects `android:testOnly="true"` into the manifest. Android will refuse to parse or install this APK when side-loaded manually. It can *only* be installed via Android Studio's automated ADB connection.
2. **Incomplete or Corrupt Downloads**: Transferring the APK from your computer to your phone via chat apps (like WhatsApp, Telegram, or Gmail) can sometimes compress, modify, or corrupt the APK package structure.

---

## 2. Preventing Signing Errors in Future Projects

To make your AI Studio projects work flawlessly both online and locally, we updated your `build.gradle.kts` with an **Adaptive Signing Strategy**. 

Here is how you can set up any future Android projects to prevent these issues:

### Step 1: Use the Adaptive Gradle Signing Configuration
Instead of hardcoding a path to a workspace-specific keystore file (which causes local builds to fail when that file is missing), use a Gradle script that checks for the file's existence first:

```kotlin
android {
    // ...
    
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
            val keystoreFile = file(keystorePath)
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // Fallback to local workspace debug key if available
                val localDebugKeystore = file("${rootDir}/debug.keystore")
                if (localDebugKeystore.exists()) {
                    storeFile = localDebugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
            enableV1Signing = true
            enableV2Signing = true
        }

        // Smart Check: Only register "debugConfig" if the custom debug.keystore exists in the folder
        val localDebugKeystore = file("${rootDir}/debug.keystore")
        if (localDebugKeystore.exists()) {
            create("debugConfig") {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            val localDebugKeystore = file("${rootDir}/debug.keystore")
            if (localDebugKeystore.exists()) {
                // Use the matching key bundled with AI Studio
                signingConfig = signingConfigs.getByName("debugConfig")
            } else {
                // FALLBACK: Use your local computer's built-in Android Studio debug keystore
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
}
```

---

## 3. Step-by-Step Installation Checklist for Physical Devices

To guarantee a successful installation on your Samsung S24, Pixel 7, or any other device, follow these instructions:

### 1. Completely Uninstall Previous Versions
Before installing a newly compiled APK, completely remove any old versions of the app from your phone:
- Go to **Settings > Apps > Zubflix** (or your app name) and tap **Uninstall**.
- *Important for Android 14+*: If you have multiple user profiles (e.g., Secure Folder, Work Profile, Guest), make sure to select **"Uninstall for all users"** from the three-dot menu in the App Info screen.

### 2. Disable Samsung Auto Blocker (Samsung Only)
If you are using a Samsung S24 or newer Galaxy device:
1. Go to **Settings > Security and Privacy > Auto Blocker**.
2. Toggle **Auto Blocker** to **Off**. (You can turn it back on after installing your debug app).

### 3. Build a Standard APK (Do NOT use the green "Run" button APK)
To sideload an APK manually without using a USB cable:
1. In Android Studio, go to the top menu bar.
2. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3. Once finished, a notification popup will appear in the bottom-right corner. Click **locate** to find the compiled `app-debug.apk`.
4. This APK is safe for sideloading and does not contain the restricting `testOnly` flag.

### 4. Enable "Install Unknown Apps" for Your File Manager
When opening the `.apk` file on your phone:
1. Open your phone's file manager app (e.g., *My Files* on Samsung or *Files by Google* on Pixel).
2. Tap the APK file to install.
3. If prompted, tap **Settings** in the popup and toggle **Allow from this source** to grant permission to the file manager app.
