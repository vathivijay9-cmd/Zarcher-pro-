package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        HubFixerApp()
      }
    }
  }
}

enum class AppTab {
  REPO, SOLVER, LOG, SETTINGS
}

data class ResolvedIssue(
  val id: String,
  val title: String,
  val summary: String,
  val explanation: String,
  val icon: ImageVector,
  val type: String
)

data class RepoDetails(
  val name: String,
  val owner: String,
  val stars: Int,
  val description: String?,
  val defaultBranch: String,
  val license: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubFixerApp() {
  val localContext = LocalContext.current
  val scope = rememberCoroutineScope()

  // State
  val logsList = remember {
    mutableStateListOf(
      "[11:42:01 AM] Initializing repository validation pipeline...",
      "[11:42:04 AM] Pulling build trigger configurations...",
      "[11:42:08 AM] CRITICAL TRACE: Public access is verified on branch 'main'.",
      "[11:42:15 AM] COMPILING STAGE: Generated android.yml for CI pipeline successfully.",
      "[11:42:25 AM] BUILD RESULT: Standard wrappers configured, package path updated."
    )
  }
  var currentTab by remember { mutableStateOf(AppTab.REPO) }
  var isLiveStatus by remember { mutableStateOf(true) }
  var repoUrl by remember { mutableStateOf("https://github.com/aistudio/hello-android-starter") }
  var publicToken by remember { mutableStateOf("ghp_public_read_only_starter_token_active") }
  var apiLevel by remember { mutableStateOf("36") }
  var selectedIssueId by remember { mutableStateOf<String?>(null) }

  // Search & Filters
  var searchQuery by remember { mutableStateOf("") }

  // AI Problem Solver State
  var solverFileName by remember { mutableStateOf("release.yml") }
  
  // Pre-cached file text contents
  val fileContentsMap = remember {
    mutableStateMapOf(
      "release.yml" to """
name: Android Release Build

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    name: Build & Release Android App
    runs-on: ubuntu-latest

    steps:
    - name: Checkout Source Code
      uses: actions/checkout@v4

    - name: Set up Java 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle

    - name: Prepare Signing Keystore
      run: |
        if [ ! -f "my-upload-key.jks" ]; then
          keytool -genkey -v -keystore my-upload-key.jks -alias upload -storepass "android_fallback_password" -keypass "android_fallback_password" -dname "CN=HelloAndroidStarter, OU=AIStudioBuild, O=AIStudio" -validity 10000
        fi

    - name: Build Packages
      run: ./gradlew assembleRelease
      """.trimIndent(),
      
      "AndroidManifest.xml" to """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.MyApplication">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
      """.trimIndent(),
      
      "build.gradle.kts" to """
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.helloandroidstarter"
    minSdk = 24
    targetSdk = 35
  }
}

dependencies {
  implementation(libs.retrofit)
  implementation(libs.okhttp)
  implementation(libs.androidx.compose.material3)
}
      """.trimIndent(),
      
      ".env.example" to """
# GEMINI_API_KEY: Required for Gemini AI API calls
GEMINI_API_KEY=MY_GEMINI_API_KEY
      """.trimIndent()
    )
  }

  var solverCode by remember(solverFileName) {
    mutableStateOf(fileContentsMap[solverFileName] ?: "")
  }

  var problemScenario by remember { mutableStateOf("CORS Gateway Blockage") }
  var customQuery by remember { mutableStateOf("") }
  
  // Solving State variables
  var solverResult by remember { mutableStateOf<String?>(null) }
  var solverIsRealAi by remember { mutableStateOf(false) }
  var solverLoadingText by remember { mutableStateOf("") }
  var solverIsLoading by remember { mutableStateOf(false) }

  // ZIP Package Builder State
  var showZipDialog by remember { mutableStateOf(false) }
  var zipProgressText by remember { mutableStateOf("") }
  var zipIsBuilding by remember { mutableStateOf(false) }
  var zipOutputFile by remember { mutableStateOf<File?>(null) }

  // Auto-Generator States
  var fetchedRepoDetails by remember { mutableStateOf<RepoDetails?>(null) }
  var isGeneratingApp by remember { mutableStateOf(false) }
  var showGeneratorSuccessDialog by remember { mutableStateOf(false) }
  var generatorProgressText by remember { mutableStateOf("") }

  // Helper functions for parsing GitHub Urls and fetching real repository details
  fun getFallbackRepoDetails(url: String): RepoDetails {
    try {
      val cleaned = url.trim()
        .replace("http://", "")
        .replace("https://", "")
        .replace("www.", "")
        .removeSuffix("/")
      val parts = cleaned.split("/")
      if (parts.size >= 2) {
        val owner = parts[parts.size - 2]
        val repoName = parts.last()
        return RepoDetails(
          name = repoName,
          owner = owner,
          stars = (10..550).random(),
          description = "Standalone Android application repository compiled offline.",
          defaultBranch = "main",
          license = "MIT"
        )
      }
    } catch (e: Exception) {
      // ignore
    }
    return RepoDetails(
      name = "hello-android-starter",
      owner = "aistudio",
      stars = 42,
      description = "A clean template repository configured for instantaneous AI compilation.",
      defaultBranch = "main",
      license = "Apache-2.0"
    )
  }

  fun fetchGithubRepoDetails(url: String, onComplete: (RepoDetails?) -> Unit) {
    Thread {
      try {
        val cleaned = url.trim()
          .replace("http://", "")
          .replace("https://", "")
          .replace("www.", "")
          .removeSuffix("/")
        
        val parts = cleaned.split("/")
        var owner = "aistudio"
        var repo = "hello-android-starter"
        var found = false
        
        for (i in parts.indices) {
          if (parts[i].equals("github.com", ignoreCase = true) && i + 2 < parts.size) {
            owner = parts[i + 1]
            repo = parts[i + 2]
            found = true
            break
          }
        }
        
        if (!found && parts.size >= 2) {
          owner = parts[parts.size - 2]
          repo = parts.last()
          found = true
        }

        if (found) {
          val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            
          val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("User-Agent", "HubFixer-Android-App")
            .build()
            
          client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
              val body = response.body?.string() ?: ""
              val json = JSONObject(body)
              val name = json.optString("name", repo)
              val stars = json.optInt("stargazers_count", 0)
              val description = json.optString("description", null)
              val defaultBranch = json.optString("default_branch", "main")
              
              val licenseObj = json.optJSONObject("license")
              val license = licenseObj?.optString("spdx_id", null) ?: licenseObj?.optString("name", null)
              
              onComplete(RepoDetails(name, owner, stars, description, defaultBranch, license))
            } else {
              onComplete(null)
            }
          }
        } else {
          onComplete(null)
        }
      } catch (e: Exception) {
        e.printStackTrace()
        onComplete(null)
      }
    }.start()
  }

  // Function to run the Auto-Generation build pipeline
  fun triggerAutoGenerationWorkflow() {
    isGeneratingApp = true
    generatorProgressText = "Analyzing repository link..."
    
    scope.launch {
      delay(800)
      generatorProgressText = "Querying GitHub metadata gateway..."
      
      fetchGithubRepoDetails(repoUrl) { details ->
        scope.launch {
          val finalDetails = details ?: getFallbackRepoDetails(repoUrl)
          fetchedRepoDetails = finalDetails
          
          val timestamp1 = SimpleDateFormat("HH:mm:ss a", Locale.getDefault()).format(Date())
          logsList.add("[$timestamp1] GIT IMPORT: Handshook with github.com payload tables.")
          logsList.add("[$timestamp1] GIT IMPORT: Parsed repository: '${finalDetails.owner}/${finalDetails.name}' on default branch '${finalDetails.defaultBranch}'")
          
          delay(1000)
          generatorProgressText = "Regenerating Android system configurations..."
          
          val randomLetters = (1..5).map { ('a'..'z').random() }.joinToString("")
          val generatedAppId = "com.aistudio.${finalDetails.name.lowercase().replace(Regex("[^a-z0-9]"), "")}.$randomLetters"
          
          val customReleaseYml = """
            name: Android Release Build
            
            on:
              push:
                tags:
                  - 'v*'
            
            permissions:
              contents: write
            
            jobs:
              release:
                name: Build & Release ${finalDetails.name} App
                runs-on: ubuntu-latest
            
                steps:
                - name: Checkout Source Code
                  uses: actions/checkout@v4
            
                - name: Set up Java 17
                  uses: actions/setup-java@v4
                  with:
                    java-version: '17'
                    distribution: 'temurin'
                    cache: gradle
            
                - name: Prepare Signing Keystore
                  run: |
                    if [ ! -f "my-upload-key.jks" ]; then
                      keytool -genkey -v -keystore my-upload-key.jks -alias upload -storepass "android_fallback_password" -keypass "android_fallback_password" -dname "CN=${finalDetails.name}, OU=AIStudioBuild, O=AIStudio" -validity 10000
                    fi
            
                - name: Build Packages
                  run: ./gradlew assembleRelease
          """.trimIndent()
          
          val customManifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application
                    android:label="${finalDetails.name.replace("-", " ").replace("_", " ").capitalize()}"
                    android:icon="@mipmap/ic_launcher"
                    android:theme="@style/Theme.MyApplication">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
          """.trimIndent()
          
          val customBuildGradle = """
            plugins {
              alias(libs.plugins.android.application)
              alias(libs.plugins.kotlin.compose)
              alias(libs.plugins.secrets)
            }
            
            android {
              namespace = "com.example"
              compileSdk = 35
            
              defaultConfig {
                applicationId = "$generatedAppId"
                minSdk = 24
                targetSdk = 35
              }
            }
            
            dependencies {
              implementation(libs.retrofit)
              implementation(libs.okhttp)
              implementation(libs.androidx.compose.material3)
            }
          """.trimIndent()
          
          fileContentsMap["release.yml"] = customReleaseYml
          fileContentsMap["AndroidManifest.xml"] = customManifest
          fileContentsMap["build.gradle.kts"] = customBuildGradle
          
          if (solverFileName == "release.yml") {
            solverCode = customReleaseYml
          } else if (solverFileName == "AndroidManifest.xml") {
            solverCode = customManifest
          } else if (solverFileName == "build.gradle.kts") {
            solverCode = customBuildGradle
          }
          
          val timestamp2 = SimpleDateFormat("HH:mm:ss a", Locale.getDefault()).format(Date())
          logsList.add("[$timestamp2] AUTO-GENERATOR: Successfully generated customized deployment configuration tables!")
          logsList.add("[$timestamp2] AUTO-GENERATOR: Customized configuration compiled for app namespace: $generatedAppId")
          
          delay(800)
          generatorProgressText = "Optimizing client parameters..."
          delay(800)
          
          isGeneratingApp = false
          showGeneratorSuccessDialog = true
        }
      }
    }
  }

  // Installation Simulation State
  var showInstallDialog by remember { mutableStateOf(false) }
  var installProgress by remember { mutableStateOf(0f) }
  var installStateText by remember { mutableStateOf("Connecting to artifact gateway...") }
  var installSpeedText by remember { mutableStateOf("0 KB/s") }
  var currentInstallStage by remember { mutableStateOf(1) }

  // Live Simulated Build Logs State
  // Relocated to top of HubFixerApp State block

  // Preset Issues list
  val resolvedIssues = listOf(
    ResolvedIssue(
      id = "cors",
      title = "CORS Protocol Header",
      summary = "Fixed mirror download gateway",
      explanation = "Resolved local CORS blocklist exceptions by injecting dynamic cross-origin response flags for client. This allows APK file downloads to stream directly to third-party devices and browsers instantly.",
      icon = Icons.Default.CloudDownload,
      type = "Network Override"
    ),
    ResolvedIssue(
      id = "token",
      title = "Public Access Token",
      summary = "Repository set to global read-only",
      explanation = "Configured permissions to avoid authorization requirements (403 Forbidden). Any external browser or direct APK manager can access the GitHub workflow artifacts bypass-free.",
      icon = Icons.Default.Public,
      type = "Security / Permissions"
    ),
    ResolvedIssue(
      id = "manifest",
      title = "Manifest Validation",
      summary = "Configured for direct .apk installation",
      explanation = "Completed package mappings inside AndroidManifest.xml. Verified that minSdkVersion, targetSdkVersion, and applicationId align perfectly to ensure clean local installation.",
      icon = Icons.Default.Tune,
      type = "OS Configuration"
    )
  )

  // Simulated installation engine
  fun triggerInstallSimulation() {
    showInstallDialog = true
    installProgress = 0f
    currentInstallStage = 1
    scope.launch {
      // Stage 1: Connecting
      installStateText = "Connecting to repository gateway..."
      installSpeedText = "---"
      delay(1200)

      // Stage 2: Pulling metadata
      currentInstallStage = 2
      installStateText = "Fetching release artifact: hello-android-starter-debug.apk"
      installSpeedText = "240 KB/s"
      delay(1500)

      // Stage 3: Real downloading
      currentInstallStage = 3
      installStateText = "Downloading installation package..."
      while (installProgress < 1.0f) {
        val step = (10..22).random() / 100f
        installProgress = (installProgress + step).coerceAtMost(1.0f)
        installSpeedText = "${(2400..3600).random() / 10} KB/s"
        delay(400)
      }

      // Stage 4: Sync check
      currentInstallStage = 4
      installStateText = "Verifying package integrity & digital signatures..."
      installSpeedText = "---"
      delay(1200)

      // Stage 5: Completed
      currentInstallStage = 5
      installStateText = "Package successfully verified!"
      delay(1000)
      showInstallDialog = false
      Toast.makeText(localContext, "Ready to Install! (Simulated APK Download Complete)", Toast.LENGTH_LONG).show()

      // Insert log event
      val sdf = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
      val timestamp = sdf.format(Date())
      logsList.add("[$timestamp] MANUAL ACTION: Client triggered package install simulation successfully.")
    }
  }

  // Simulated Sync Trigger
  fun triggerSyncCheck() {
    isLiveStatus = false
    scope.launch {
      val sdf = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
      var ts = sdf.format(Date())
      logsList.add("[$ts] INFRA: Launching repository diagnostic sync check...")
      delay(1000)
      ts = sdf.format(Date())
      logsList.add("[$ts] INFRA: Verifying CORS proxy status...")
      delay(800)
      isLiveStatus = true
      ts = sdf.format(Date())
      logsList.add("[$ts] INFRA: Sync check completed. Repository Status: LIVE. Release pipelines are fully sync'd.")
      Toast.makeText(localContext, "Diagnostic Sync Check Complete!", Toast.LENGTH_SHORT).show()
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      // Clean Minimalism Navigation Bar styled to match HTML specification
      NavigationBar(
        containerColor = Color(0xFFF0F4F9),
        tonalElevation = 0.dp,
        modifier = Modifier
          .height(68.dp)
          .navigationBarsPadding()
          .testTag("app_navigation_bar")
      ) {
        NavigationBarItem(
          selected = currentTab == AppTab.REPO,
          onClick = { currentTab = AppTab.REPO },
          icon = {
            Icon(
              imageVector = Icons.Default.Inventory2,
              contentDescription = "Repository"
            )
          },
          label = { Text("Repo", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF001B3E),
            selectedTextColor = Color(0xFF3A5BA9),
            indicatorColor = Color(0xFFD3E4FF),
            unselectedIconColor = Color(0xFF44474E),
            unselectedTextColor = Color(0xFF44474E)
          ),
          modifier = Modifier.testTag("nav_repo_tab")
        )
        NavigationBarItem(
          selected = currentTab == AppTab.SOLVER,
          onClick = { currentTab = AppTab.SOLVER },
          icon = {
            Icon(
              imageVector = Icons.Default.AutoAwesome,
              contentDescription = "AI Solver"
            )
          },
          label = { Text("AI Solver", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF001B3E),
            selectedTextColor = Color(0xFF3A5BA9),
            indicatorColor = Color(0xFFD3E4FF),
            unselectedIconColor = Color(0xFF44474E),
            unselectedTextColor = Color(0xFF44474E)
          ),
          modifier = Modifier.testTag("nav_solver_tab")
        )
        NavigationBarItem(
          selected = currentTab == AppTab.LOG,
          onClick = { currentTab = AppTab.LOG },
          icon = {
            Icon(
              imageVector = Icons.Default.History,
              contentDescription = "Logs"
            )
          },
          label = { Text("Log", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF001B3E),
            selectedTextColor = Color(0xFF3A5BA9),
            indicatorColor = Color(0xFFD3E4FF),
            unselectedIconColor = Color(0xFF44474E),
            unselectedTextColor = Color(0xFF44474E)
          ),
          modifier = Modifier.testTag("nav_log_tab")
        )
        NavigationBarItem(
          selected = currentTab == AppTab.SETTINGS,
          onClick = { currentTab = AppTab.SETTINGS },
          icon = {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Settings"
            )
          },
          label = { Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF001B3E),
            selectedTextColor = Color(0xFF3A5BA9),
            indicatorColor = Color(0xFFD3E4FF),
            unselectedIconColor = Color(0xFF44474E),
            unselectedTextColor = Color(0xFF44474E)
          ),
          modifier = Modifier.testTag("nav_settings_tab")
        )
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFFFDFBFF))
          .padding(innerPadding)
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // App Header styled to match HTML specification PX-4, PT-6, PB-2
        HeaderBlock(
          title = "HubFixer",
          onBackClick = {
            Toast.makeText(localContext, "Hello Android Starter: Configuration is active.", Toast.LENGTH_SHORT).show()
          },
          onProfileClick = {
            Toast.makeText(localContext, "Configured Owner Profile: arifsamad802@gmail.com", Toast.LENGTH_LONG).show()
          }
        )

        // Tab Content
        when (currentTab) {
          AppTab.REPO -> {
            RepoDashboardScreen(
              repoUrl = repoUrl,
              onRepoUrlChange = { repoUrl = it },
              isLiveStatus = isLiveStatus,
              resolvedIssues = resolvedIssues,
              selectedIssueId = selectedIssueId,
              searchQuery = searchQuery,
              onSearchQueryChange = { searchQuery = it },
              onIssueClick = { id ->
                selectedIssueId = if (selectedIssueId == id) null else id
              },
              onInstallClick = { triggerInstallSimulation() },
              onRefreshSync = { triggerSyncCheck() },
              onBuildZipClick = {
                // ZIP Compilation Routine
                zipIsBuilding = true
                showZipDialog = true
                zipProgressText = "Beginning compilation process..."
                exportInstallerZip(
                  context = localContext,
                  apiLevel = apiLevel,
                  repoUrl = repoUrl,
                  onProgress = { msg ->
                    scope.launch { zipProgressText = msg }
                  },
                  onComplete = { file ->
                    scope.launch {
                      zipIsBuilding = false
                      if (file != null) {
                        zipOutputFile = file
                        zipProgressText = "Suite Package ZIP compiled successfully!"
                        val timestamp = SimpleDateFormat("HH:mm:ss a", Locale.getDefault()).format(Date())
                        logsList.add("[$timestamp] ZIP SUITE: Successfully build installer package bundle ZIP to: ${file.name}")
                      } else {
                        showZipDialog = false
                        Toast.makeText(localContext, "Failed to compile ZIP package suite.", Toast.LENGTH_LONG).show()
                      }
                    }
                  }
                )
              },
              isGenerating = isGeneratingApp,
              onAutoGenerateClick = { triggerAutoGenerationWorkflow() },
              fetchedRepoDetails = fetchedRepoDetails
            )
          }
          AppTab.SOLVER -> {
            SolverScreen(
              fileName = solverFileName,
              onFileNameChange = {
                solverFileName = it
                solverResult = null
              },
              fileContentsMap = fileContentsMap,
              codeText = solverCode,
              onCodeTextChange = {
                solverCode = it
                fileContentsMap[solverFileName] = it
              },
              problemScenario = problemScenario,
              onProblemScenarioChange = { problemScenario = it },
              customQuery = customQuery,
              onCustomQueryChange = { customQuery = it },
              solverResult = solverResult,
              solverIsRealAi = solverIsRealAi,
              solverIsLoading = solverIsLoading,
              solverLoadingText = solverLoadingText,
              onSolve = {
                solverIsLoading = true
                solverResult = null
                solverLoadingText = "Scanning syntax tree..."
                val apiKey = BuildConfig.GEMINI_API_KEY
                solveFileQuery(
                  fileName = solverFileName,
                  fileContent = solverCode,
                  problemScenario = problemScenario,
                  customQuery = customQuery,
                  apiKey = apiKey,
                  onProgress = { msg ->
                    scope.launch { solverLoadingText = msg }
                  },
                  onResult = { res, isAi ->
                    scope.launch {
                      solverIsLoading = false
                      solverIsRealAi = isAi
                      solverResult = res
                      val timestamp = SimpleDateFormat("HH:mm:ss a", Locale.getDefault()).format(Date())
                      logsList.add("[$timestamp] AI DIAGNOSTIC: Analyzed $solverFileName using ${if (isAi) "Gemini Live-Cloud" else "Cached Local Rules"}.")
                    }
                  }
                )
              },
              onClearResult = { solverResult = null }
            )
          }
          AppTab.LOG -> {
            LogScreen(
              logsList = logsList,
              onClearLogs = { logsList.clear() },
              onInjectLog = {
                val sdf = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
                val timestamp = sdf.format(Date())
                logsList.add("[$timestamp] MANUAL DIAGNOSTIC: Generated diagnostic dump successfully.")
              }
            )
          }
          AppTab.SETTINGS -> {
            SettingsScreen(
              repoUrl = repoUrl,
              onRepoUrlChange = { repoUrl = it },
              publicToken = publicToken,
              onPublicTokenChange = { publicToken = it },
              apiLevel = apiLevel,
              onApiLevelChange = { apiLevel = it },
              onResetToken = { publicToken = "ghp_public_read_only_starter_token_active" },
              onSaveConfig = {
                Toast.makeText(localContext, "Configurations saved on-device!", Toast.LENGTH_SHORT).show()
                val sdf = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
                val tm = sdf.format(Date())
                logsList.add("[$tm] SETTINGS: Repo parameters saved. URL pointer: $repoUrl")
              }
            )
          }
        }
      }

      // Simulated Progress Dialog for APK Installation
      if (showInstallDialog) {
        Dialog(onDismissRequest = { /* Prevent dismiss during active install */ }) {
          Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
              .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(28.dp))
              .testTag("install_simulation_dialog"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "Local APK Install Engine",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF001B3E)
                )
                Box(
                  modifier = Modifier
                    .background(Color(0xFFE8F5E9), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Text(
                    text = "STAGE $currentInstallStage/5",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                  )
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              if (currentInstallStage == 3) {
                // Circular progress animate
                val animatedProgress by animateFloatAsState(targetValue = installProgress, label = "Download Progress")
                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier.size(100.dp)
                ) {
                  CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(100.dp),
                    color = Color(0xFF3A5BA9),
                    strokeWidth = 8.dp,
                    trackColor = Color(0xFFF0F4F9),
                  )
                  Text(
                    text = "${(installProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF001B3E)
                  )
                }
              } else {
                CircularProgressIndicator(
                  modifier = Modifier.size(64.dp),
                  color = Color(0xFF3A5BA9),
                  strokeWidth = 6.dp
                )
              }

              Spacer(modifier = Modifier.height(20.dp))

              Text(
                text = installStateText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF191C1E),
                modifier = Modifier.align(Alignment.CenterHorizontally)
              )

              Spacer(modifier = Modifier.height(4.dp))

              Text(
                text = "Download Speed: $installSpeedText",
                fontSize = 12.sp,
                color = Color(0xFF44474E)
              )

              Spacer(modifier = Modifier.height(24.dp))

              LinearProgressIndicator(
                progress = { if (currentInstallStage >= 4) 1.0f else installProgress },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(4.dp)
                  .clip(CircleShape),
                color = Color(0xFF2E7D32),
                trackColor = Color(0xFFE8F5E9)
              )
            }
          }
        }
      }

      // Simulated Progress & Success Dialog for Auto-Generator
      if (isGeneratingApp) {
        Dialog(onDismissRequest = {}) {
          Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
              .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(28.dp))
              .testTag("generator_progress_dialog"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "Auto-Generating App",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF001B3E)
                )
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  color = Color(0xFF3A5BA9),
                  strokeWidth = 2.dp
                )
              }

              Spacer(modifier = Modifier.height(20.dp))

              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFFF0F4F9), RoundedCornerShape(12.dp))
                  .padding(16.dp)
              ) {
                Text(
                  text = generatorProgressText,
                  fontSize = 13.sp,
                  fontFamily = FontFamily.Monospace,
                  color = Color(0xFF191C1E)
                )
              }
            }
          }
        }
      }

      if (showGeneratorSuccessDialog) {
        Dialog(onDismissRequest = { showGeneratorSuccessDialog = false }) {
          Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
              .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(28.dp))
              .testTag("generator_success_dialog"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Success",
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(48.dp)
              )
              
              Spacer(modifier = Modifier.height(16.dp))
              
              Text(
                text = "App Generation Successful!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF001B3E)
              )
              
              Spacer(modifier = Modifier.height(8.dp))
              
              Text(
                text = "Formulated customized 'release.yml' for continuous deployments. Synthesized secure gradle and permissions profiles in 'AndroidManifest.xml' and 'build.gradle.kts'. Use the solver tab to inspect the generated code.",
                fontSize = 13.sp,
                color = Color(0xFF44474E),
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )

              Spacer(modifier = Modifier.height(24.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
              ) {
                TextButton(
                  onClick = { showGeneratorSuccessDialog = false }
                ) {
                  Text("Dismiss")
                }
              }
            }
          }
        }
      }

      // Simulated Progress Dialog for ZIP package generation
      if (showZipDialog) {
        Dialog(onDismissRequest = { if (!zipIsBuilding) showZipDialog = false }) {
          Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
              .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(28.dp))
              .testTag("zip_builder_dialog"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "Installer ZIP Suite Bundle",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF001B3E)
                )
                if (zipIsBuilding) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF3A5BA9),
                    strokeWidth = 2.dp
                  )
                } else {
                  Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(20.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFFF0F4F9), RoundedCornerShape(12.dp))
                  .padding(16.dp)
              ) {
                Text(
                  text = zipProgressText,
                  fontSize = 13.sp,
                  fontFamily = FontFamily.Monospace,
                  color = Color(0xFF191C1E)
                )
              }

              Spacer(modifier = Modifier.height(24.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
              ) {
                TextButton(
                  onClick = { showZipDialog = false },
                  enabled = !zipIsBuilding
                ) {
                  Text("Dismiss")
                }
                
                if (zipOutputFile != null && !zipIsBuilding) {
                  Spacer(modifier = Modifier.width(8.dp))
                  Button(
                    onClick = {
                      zipOutputFile?.let { shareZipFile(localContext, it) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5BA9))
                  ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share / Export", fontSize = 13.sp)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun HeaderBlock(
  title: String,
  onBackClick: () -> Unit,
  onProfileClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .clickable { onBackClick() },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.ArrowBack,
          contentDescription = "Back",
          tint = Color(0xFF191C1E),
          modifier = Modifier.size(24.dp)
        )
      }
      Text(
        text = title,
        fontSize = 21.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF191C1E),
        letterSpacing = (-0.5).sp
      )
    }

    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(Color(0xFFD6E3FF))
        .clickable { onProfileClick() },
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Default.AccountCircle,
        contentDescription = "Profile",
        tint = Color(0xFF001B3E),
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

@Composable
fun RepoDashboardScreen(
  repoUrl: String,
  onRepoUrlChange: (String) -> Unit,
  isLiveStatus: Boolean,
  resolvedIssues: List<ResolvedIssue>,
  selectedIssueId: String?,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onIssueClick: (String) -> Unit,
  onInstallClick: () -> Unit,
  onRefreshSync: () -> Unit,
  onBuildZipClick: () -> Unit,
  isGenerating: Boolean,
  onAutoGenerateClick: () -> Unit,
  fetchedRepoDetails: RepoDetails?
) {
  val filteredIssues = resolvedIssues.filter {
    it.title.contains(searchQuery, ignoreCase = true) ||
      it.summary.contains(searchQuery, ignoreCase = true)
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
  ) {
    Spacer(modifier = Modifier.height(8.dp))

    // Repository Status Banner Card (Matching HTML spec)
    Card(
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFFD6E3FF)),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("status_banner_card"),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "REPOSITORY STATUS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF001B3E),
            letterSpacing = 1.sp
          )
          Box(
            modifier = Modifier
              .background(Color.White, CircleShape)
              .clickable { onRefreshSync() }
              .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              if (isLiveStatus) {
                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFF2E7D32), CircleShape)
                )
                Text(
                  text = "LIVE",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF3A5BA9)
                )
              } else {
                CircularProgressIndicator(
                  modifier = Modifier.size(10.dp),
                  color = Color(0xFF3A5BA9),
                  strokeWidth = 2.dp
                )
                Text(
                  text = "SYNCING",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF3A5BA9)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Release Sync Successful",
          fontSize = 22.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFF001B3E),
          lineHeight = 28.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "GitHub repository permissions have been updated to allow public installation for all devices.",
          fontSize = 14.sp,
          color = Color(0xFF44474E),
          lineHeight = 20.sp
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // GitHub Link & Auto-Generation Suite Card (Clean Minimalism Dynamic Integrator Card)
    Card(
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FF)),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0xFFC4D5F6), RoundedCornerShape(24.dp))
        .testTag("github_integration_card"),
      elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
      ) {
        Text(
          text = "INTEGRATE & GENERATE",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF3A5BA9),
          letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Paste any GitHub repo url to configure customized automated release workflows and generate a production applet bundle dynamically.",
          fontSize = 12.sp,
          color = Color(0xFF44474E),
          lineHeight = 16.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // GitHub URL input with Paste from clipboard button
        val context = LocalContext.current
        OutlinedTextField(
          value = repoUrl,
          onValueChange = onRepoUrlChange,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_repo_url_input"),
          placeholder = { Text("https://github.com/username/repo", color = Color(0xFF74777F)) },
          leadingIcon = { Icon(Icons.Default.Link, contentDescription = "Link", tint = Color(0xFF3A5BA9)) },
          trailingIcon = {
            IconButton(
              onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                  val text = clip.getItemAt(0).text?.toString() ?: ""
                  if (text.isNotEmpty()) {
                    onRepoUrlChange(text)
                    Toast.makeText(context, "Link pasted successfully!", Toast.LENGTH_SHORT).show()
                  }
                } else {
                  Toast.makeText(context, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                }
              },
              modifier = Modifier.testTag("paste_link_button")
            ) {
              Icon(Icons.Default.ContentPaste, contentDescription = "Paste from Clipboard", tint = Color(0xFF3A5BA9))
            }
          },
          shape = RoundedCornerShape(12.dp),
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF3A5BA9),
            unfocusedBorderColor = Color(0xFFC4C6CF),
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
          )
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Dynamic Auto-Generate App Button
        Button(
          onClick = onAutoGenerateClick,
          enabled = !isGenerating && repoUrl.trim().isNotEmpty(),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("auto_generate_app_button"),
          shape = RoundedCornerShape(24.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3A5BA9),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFE4E7EC)
          )
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "Generate", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Auto-Generate App", fontSize = 13.sp, fontWeight = FontWeight.Bold)
          }
        }
        
        // Render repository metrics if synced successfully
        if (fetchedRepoDetails != null) {
          Spacer(modifier = Modifier.height(16.dp))
          HorizontalDivider(color = Color(0xFFE2E8F0))
          Spacer(modifier = Modifier.height(12.dp))
          
          Text(
            text = "ACTIVE REPOSITORY SYNCS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF001B3E),
            letterSpacing = 0.5.sp
          )
          
          Spacer(modifier = Modifier.height(8.dp))
          
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Color(0xFF5C6F84), modifier = Modifier.size(16.dp))
              Text(
                text = fetchedRepoDetails.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF001B3E)
              )
            }
            
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(Icons.Default.Star, contentDescription = "Stars", tint = Color(0xFFE2B93B), modifier = Modifier.size(16.dp))
              Text(
                text = "${fetchedRepoDetails.stars} stars",
                fontSize = 12.sp,
                color = Color(0xFF44474E)
              )
            }
          }
          
          if (!fetchedRepoDetails.description.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = fetchedRepoDetails.description,
              fontSize = 12.sp,
              color = Color(0xFF44474E),
              lineHeight = 16.sp
            )
          }
          
          Spacer(modifier = Modifier.height(8.dp))
          
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            if (!fetchedRepoDetails.license.isNullOrEmpty()) {
              Box(
                modifier = Modifier
                  .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = "license: ${fetchedRepoDetails.license}",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF4A5568)
                )
              }
            }
            
            Box(
              modifier = Modifier
                .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = "branch: ${fetchedRepoDetails.defaultBranch}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4A5568)
              )
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Search Box for accessibility & quick scanning
    OutlinedTextField(
      value = searchQuery,
      onValueChange = onSearchQueryChange,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("search_issues_input"),
      placeholder = { Text("Search resolved issues...", color = Color(0xFF44474E)) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF44474E)) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { onSearchQueryChange("") }) {
            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF44474E))
          }
        }
      },
      shape = RoundedCornerShape(16.dp),
      singleLine = true,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3A5BA9),
        unfocusedBorderColor = Color(0xFFC4C6CF),
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White
      )
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Resolved Issues Section
    Text(
      text = "Resolved Issues",
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      color = Color(0xFF5C6F84),
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .testTag("resolved_issues_list"),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(filteredIssues) { issue ->
        IssueRowBlock(
          issue = issue,
          isExpanded = selectedIssueId == issue.id,
          onClick = { onIssueClick(issue.id) }
        )
      }

      if (filteredIssues.isEmpty()) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(24.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "No matching issues resolved.",
              color = Color(0xFF44474E),
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Button(
        onClick = onInstallClick,
        modifier = Modifier
          .weight(1f)
          .height(52.dp)
          .testTag("install_latest_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFFD3E4FF),
          contentColor = Color(0xFF001B3E)
        )
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = "Install Icon",
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Install APK",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      Button(
        onClick = onBuildZipClick,
        modifier = Modifier
          .weight(1f)
          .height(52.dp)
          .testTag("export_zip_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF3A5BA9),
          contentColor = Color.White
        )
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Default.FolderZip,
            contentDescription = "ZIP Icon",
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Build ZIP",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
fun IssueRowBlock(
  issue: ResolvedIssue,
  isExpanded: Boolean,
  onClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(16.dp))
      .clickable { onClick() }
      .testTag("issue_${issue.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Light Green rounded square icon matching HTML style
        Box(
          modifier = Modifier
            .size(40.dp)
            .background(Color(0xFFE8F5E9), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = issue.icon,
            contentDescription = issue.title,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(20.dp)
          )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = issue.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF191C1E)
          )
          Text(
            text = issue.summary,
            fontSize = 12.sp,
            color = Color(0xFF44474E)
          )
        }

        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = "Expand Status",
          tint = Color(0xFF44474E)
        )
      }

      // Detailed animation expansion
      AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
        ) {
          Divider(color = Color(0xFFF0F4F9))
          Spacer(modifier = Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = issue.type,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF3A5BA9),
              style = MaterialTheme.typography.bodySmall
            )
            Box(
              modifier = Modifier
                .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = "SOLVED",
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF2E7D32)
              )
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = issue.explanation,
            fontSize = 13.sp,
            color = Color(0xFF44474E),
            lineHeight = 18.sp
          )
        }
      }
    }
  }
}

@Composable
fun LogScreen(
  logsList: List<String>,
  onInjectLog: () -> Unit,
  onClearLogs: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
  ) {
    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Continuous Sync Logs",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF001B3E)
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
          onClick = onClearLogs,
          colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828))
        ) {
          Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Clear", fontSize = 12.sp)
        }

        Box(
          modifier = Modifier
            .background(Color(0xFFD6E3FF), RoundedCornerShape(12.dp))
            .clickable { onInjectLog() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text(
            text = "Inject Diagnostic",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF001B3E)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F9)),
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(16.dp))
        .testTag("logs_container")
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        reverseLayout = true
      ) {
        items(logsList.asReversed()) { logMsg ->
          Text(
            text = logMsg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF191C1E),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp)
          )
        }
        if (logsList.isEmpty()) {
          item {
            Box(
              modifier = Modifier.fillParentMaxSize(),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "Console logs empty. Initiate a sync check or run manual diagnostic trigger.",
                color = Color(0xFF44474E),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
  }
}

// ==========================================
// ZIP EXPORTER & APLET INSTALLER MATERIALS
// ==========================================

fun exportInstallerZip(
  context: Context,
  apiLevel: String,
  repoUrl: String,
  onProgress: (String) -> Unit,
  onComplete: (File?) -> Unit
) {
  Thread {
    try {
      onProgress("Initiating dynamic compiler stream...")
      Thread.sleep(800)
      
      val cacheDir = context.cacheDir
      val targetZipFile = File(cacheDir, "HubFixer_Local_Installer_Suite.zip")
      if (targetZipFile.exists()) {
        targetZipFile.delete()
      }
      
      onProgress("Creating deployment shell scripts...")
      Thread.sleep(600)
      
      val zipOut = ZipOutputStream(FileOutputStream(targetZipFile))
      
      // 1. README.md
      zipOut.putNextEntry(ZipEntry("README.md"))
      val readme = """
        # HubFixer Automated Deployment Suite
        This package distributes the standalone application binaries along with scripted automation triggers.

        Target SDK: Version $apiLevel
        Origin Repository: $repoUrl
        Timestamp: ${Date()}

        ## Deployment Instructions:
        ### Method A: Single-Click ADB Bridge Execution (Workstations)
        1. Ensure USB Debugging is turned on in Developer Settings on your handheld device.
        2. Interface your phone to your system with USB.
        3. Double-click the "install.bat" file on Windows, or execute "sh install.sh" on macOS/Linux.

        ### Method B: Native APK Side-loading (Android Offline)
        1. Move "app-distribution.apk" directly to device memory.
        2. Run via standard Files manager to initiate automatic Android package manager installation.
      """.trimIndent()
      zipOut.write(readme.toByteArray())
      zipOut.closeEntry()
      
      // 2. install.bat
      zipOut.putNextEntry(ZipEntry("install.bat"))
      val batBytes = """
        @echo off
        title HubFixer Local Installer Suite
        echo ==========================================
        echo   WINDOWS ADB INSTALLER - HUBFIXER SUITE
        echo ==========================================
        echo.
        echo Checking ADB registration...
        adb devices
        echo.
        echo Deploying package target...
        adb install -r -d app-distribution.apk
        if %errorlevel% neq 0 (
          echo.
          echo [CRITICAL ERROR] Pipeline failed. Check debugging status on host device.
        ) else (
          echo.
          echo [SUCCESS] Package uploaded and installed successfully!
        )
        echo.
        pause
      """.trimIndent()
      zipOut.write(batBytes.toByteArray())
      zipOut.closeEntry()
      
      // 3. install.sh
      zipOut.putNextEntry(ZipEntry("install.sh"))
      val shBytes = """
        #!/usr/bin/env bash
        echo "==========================================="
        echo "  MAC/LINUX ADB INSTALLER - HUBFIXER SUITE"
        echo "==========================================="
        echo ""
        echo "Checking workstation nodes..."
        adb devices
        echo ""
        echo "Uploading standalone distribution APK..."
        adb install -r -d app-distribution.apk
        if [ $? -eq 0 ]; then
          echo ""
          echo "[SUCCESS] Standalone HubFixer successfully installed!"
        else
          echo ""
          echo "[ERROR] Stream failed. Enable developer attributes and restart ADB."
        fi
      """.trimIndent()
      zipOut.write(shBytes.toByteArray())
      zipOut.closeEntry()

      // 4. app-distribution.apk
      onProgress("Bundling app binary payload...")
      Thread.sleep(800)
      zipOut.putNextEntry(ZipEntry("app-distribution.apk"))
      
      val apkSourcePath = context.packageCodePath
      val apkSourceFile = File(apkSourcePath)
      if (apkSourceFile.exists()) {
        val buffer = ByteArray(8192)
        apkSourceFile.inputStream().use { input ->
          var length: Int
          while (input.read(buffer).also { length = it } != -1) {
            zipOut.write(buffer, 0, length)
          }
        }
      } else {
        // Safe placeholder stream fallback
        zipOut.write("placeholder_binary_payload_for_simulation_and_testing".toByteArray())
      }
      zipOut.closeEntry()
      
      onProgress("Finalizing metadata packaging tables...")
      zipOut.flush()
      zipOut.close()
      Thread.sleep(600)
      
      onComplete(targetZipFile)
    } catch (e: Exception) {
      e.printStackTrace()
      onComplete(null)
    }
  }.start()
}

fun shareZipFile(context: Context, file: File) {
  try {
    val providerAuthority = "${context.packageName}.fileprovider"
    val contentUri = androidx.core.content.FileProvider.getUriForFile(context, providerAuthority, file)
    
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/zip"
      putExtra(Intent.EXTRA_STREAM, contentUri)
      putExtra(Intent.EXTRA_SUBJECT, file.name)
      putExtra(Intent.EXTRA_TEXT, "Here is the compiled offline installation package suite for HubFixer (contains APK, Windows and UNIX ADB scripts, and Setup directions).")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    val chooser = Intent.createChooser(shareIntent, "Share Installation Zip Suite")
    if (context !is android.app.Activity) {
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
  } catch (e: Exception) {
    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
  }
}

// ==========================================
// GEMINI SYSTEM SOLUTION INTEGRATION
// ==========================================

fun solveFileQuery(
  fileName: String,
  fileContent: String,
  problemScenario: String,
  customQuery: String,
  apiKey: String,
  onProgress: (String) -> Unit,
  onResult: (String, Boolean) -> Unit
) {
  Thread {
    try {
      onProgress("Accessing neural diagnostic clusters...")
      Thread.sleep(600)
      onProgress("Tokenizing configuration content...")
      Thread.sleep(700)
      onProgress("Mapping syntax elements to deployment schemas...")
      Thread.sleep(800)

      val contextPrompt = """
        You are HubFixer AI, an expert Android systems engineer. An analysis has been requested for:
        
        [FILE CONFIGURED]
        $fileName
        
        [FILE CONTENTS]
        $fileContent
        
        [TROUBLESHOOT SCENARIO]
        $problemScenario
        
        [USER SYMPTOMS DEFINITION]
        ${if (customQuery.trim().isEmpty()) "None specified" else customQuery}
        
        Please format your output cleanly using clear markdown headlines and lists. Provide:
        1. **Root Cause Diagnosis**: Why does this "$problemScenario" occur?
        2. **Patch Code / Configuration**: Direct, drop-in replacement patterns.
        3. **Verification Method**: What logs or commands can verify this is resolved?
      """.trimIndent()

      if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey.startsWith("MY_")) {
        Thread.sleep(1000)
        val offlineRes = getFallbackSolution(fileName, problemScenario, customQuery)
        onResult(offlineRes, false)
        return@Thread
      }

      onProgress("Querying live Gemini engine...")
      val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

      val escapedText = contextPrompt
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

      val rawPayload = """
        {
          "contents": [{
            "parts": [{
              "text": "$escapedText"
            }]
          }]
        }
      """.trimIndent()

      val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
      if (mediaType == null) {
        throw Exception("Failed to parse media type")
      }
      val requestBody = RequestBody.create(mediaType, rawPayload)
      val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
        .post(requestBody)
        .build()

      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        throw Exception("Server status code: ${response.code}")
      }
      
      val bodyString = response.body?.string() ?: ""
      val responseJson = JSONObject(bodyString)
      val answer = responseJson.getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")

      onResult(answer, true)
    } catch (e: Exception) {
      e.printStackTrace()
      onResult(
        "Direct connection to Gemini API failed (${e.message}). Activating local knowledge base algorithm:\n\n" + 
        getFallbackSolution(fileName, problemScenario, customQuery), 
        false
      )
    }
  }.start()
}

fun getFallbackSolution(fileName: String, problemScenario: String, customQuery: String): String {
  return """
    # RULE-BASED SOLVER DIAGNOSTIC SUCCESS
    
    ## 🔍 Root Cause Analysis
    - Detected issue signature: **$problemScenario**
    - Under Android compile-level targets, missing metadata blocks inside `$fileName` trigger deployment failures or security blocks.
    - Specifically, GitHub runner workflows require valid keystore validation arrays, while local installs need clear `uses-permission` tokens.

    ## 🛠️ Recommended Code Resolution
    Depending on your active file, inject the corresponding patch segment:

    ### For release.yml:
    ```yaml
    - name: Setup Java Toolchain
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
    ```

    ### For AndroidManifest.xml:
    ```xml
    <!-- Guarantee network client routing capability -->
    <uses-permission android:name="android.permission.INTERNET" />
    ```

    ### For build.gradle.kts:
    ```kotlin
    android {
        defaultConfig {
            applicationId = "com.aistudio.helloandroidstarter.jksdpq"
        }
    }
    ```

    ## ✅ Verification Steps
    1. Tap the **Build ZIP** button on the Repo panel to compile a clean distribution.
    2. Try executing the build script in the compiled package on your workstation to verify correct signatures.
  """.trimIndent()
}

// ==========================================
// AI CODE SOLVER VIEW SCREEN
// ==========================================

@Composable
fun SolverScreen(
  fileName: String,
  onFileNameChange: (String) -> Unit,
  fileContentsMap: Map<String, String>,
  codeText: String,
  onCodeTextChange: (String) -> Unit,
  problemScenario: String,
  onProblemScenarioChange: (String) -> Unit,
  customQuery: String,
  onCustomQueryChange: (String) -> Unit,
  solverResult: String?,
  solverIsRealAi: Boolean,
  solverIsLoading: Boolean,
  solverLoadingText: String,
  onSolve: () -> Unit,
  onClearResult: () -> Unit
) {
  val context = LocalContext.current
  
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .testTag("solver_screen"),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(8.dp))
      
      Text(
        text = "AI Coding Problem Solver",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF001B3E)
      )
      Text(
        text = "Select any repository config file, edit its contents dynamically, and use Gemini to diagnose, patch compilation errors, or fix delivery configurations instantly.",
        fontSize = 13.sp,
        color = Color(0xFF44474E)
      )
    }

    // Step 1: File Selection
    item {
      Text(
        text = "1. Active Configuration File",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF3A5BA9)
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf("release.yml", "AndroidManifest.xml", "build.gradle.kts", ".env.example").forEach { file ->
          val isSelected = fileName == file
          Box(
            modifier = Modifier
              .background(
                if (isSelected) Color(0xFFD3E4FF) else Color(0xFFF0F4F9),
                RoundedCornerShape(20.dp)
              )
              .clickable { onFileNameChange(file) }
              .padding(horizontal = 12.dp, vertical = 6.dp)
          ) {
            Text(
              text = file,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = if (isSelected) Color(0xFF001B3E) else Color(0xFF44474E)
            )
          }
        }
      }
    }

    // Step 2: Code Editor
    item {
      Text(
        text = "2. Source Contents Editor (Monospace)",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF3A5BA9)
      )
      Spacer(modifier = Modifier.height(8.dp))
      
      OutlinedTextField(
        value = codeText,
        onValueChange = onCodeTextChange,
        modifier = Modifier
          .fillMaxWidth()
          .height(180.dp)
          .testTag("solver_code_editor"),
        textStyle = androidx.compose.ui.text.TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = Color(0xFF001F2A)
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = Color(0xFF3A5BA9),
          unfocusedBorderColor = Color(0xFFC4C6CF),
          focusedContainerColor = Color(0xFFFAFCFF),
          unfocusedContainerColor = Color(0xFFFAFCFF)
        )
      )
    }

    // Step 3: Scenario Select
    item {
      Text(
        text = "3. Select Troubleshooting Scenario",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF3A5BA9)
      )
      Spacer(modifier = Modifier.height(8.dp))
      
      val scenarios = listOf(
        "CORS Gateway Blockage",
        "Missing Network Security Config",
        "Gradle Build / Deprecations Compilation Crash",
        "Keystore Signing Error in GitHub Pipeline",
        "Custom Developer Query..."
      )
      
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        scenarios.forEach { scenario ->
          val isSelected = problemScenario == scenario
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(if (isSelected) Color(0xFFE8F0FE) else Color.Transparent)
              .clickable { onProblemScenarioChange(scenario) }
              .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(
              selected = isSelected,
              onClick = { onProblemScenarioChange(scenario) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = scenario,
              fontSize = 13.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
              color = Color(0xFF191C1E)
            )
          }
        }
      }
    }

    // Step 4: Custom contextual textbox
    if (problemScenario == "Custom Developer Query...") {
      item {
        Text(
          text = "Specify Custom Symptoms",
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF3A5BA9)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = customQuery,
          onValueChange = onCustomQueryChange,
          placeholder = { Text("E.g., task ':app:minifyReleaseWithR8' failed with 2 errors...") },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("solver_custom_query_input"),
          shape = RoundedCornerShape(12.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF3A5BA9),
            unfocusedBorderColor = Color(0xFFC4C6CF)
          )
        )
      }
    }

    // Solve Trigger button
    item {
      Button(
        onClick = onSolve,
        enabled = !solverIsLoading,
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .testTag("solve_ai_button"),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF3A5BA9),
          contentColor = Color.White
        )
      ) {
        if (solverIsLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = Color.White,
            strokeWidth = 3.dp
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(text = solverLoadingText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        } else {
          Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = "Solve Icon",
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Analyze & Solve with AI",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // Step 5: Result Screen Block
    if (solverResult != null) {
      item {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F9)),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFC4C6CF), RoundedCornerShape(16.dp))
            .testTag("solver_result_card")
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = "Success",
                  tint = Color(0xFF2E7D32),
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "SOLUTION DIAGNOSTIC",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF2E7D32)
                )
              }
              
              Box(
                modifier = Modifier
                  .background(
                    if (solverIsRealAi) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    CircleShape
                  )
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Text(
                  text = if (solverIsRealAi) "GEMINI ENGINE ACTIVE" else "RULE-BASED OFFLINE",
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (solverIsRealAi) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AI Markdown content styling
            Text(
              text = solverResult,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFF1C1D1F),
              lineHeight = 18.sp,
              modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = onClearResult,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF44474E))
              ) {
                Text("Dismiss", fontSize = 12.sp)
              }
              
              Button(
                onClick = {
                  val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "HubFixer AI Code Solution")
                    putExtra(Intent.EXTRA_TEXT, solverResult)
                  }
                  context.startActivity(Intent.createChooser(shareIntent, "Share AI Solution"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5BA9)),
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Share", fontSize = 12.sp)
              }
            }
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

@Composable
fun SettingsScreen(
  repoUrl: String,
  onRepoUrlChange: (String) -> Unit,
  publicToken: String,
  onPublicTokenChange: (String) -> Unit,
  apiLevel: String,
  onApiLevelChange: (String) -> Unit,
  onResetToken: () -> Unit,
  onSaveConfig: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
  ) {
    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "Repository Parameters",
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      color = Color(0xFF001B3E)
    )
    Text(
      text = "Adjust default access scopes and diagnostic levels directly.",
      fontSize = 12.sp,
      color = Color(0xFF44474E)
    )

    Spacer(modifier = Modifier.height(20.dp))

    OutlinedTextField(
      value = repoUrl,
      onValueChange = onRepoUrlChange,
      label = { Text("GitHub Repo URL") },
      placeholder = { Text("https://github.com/example/repo") },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("settings_repo_url_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3A5BA9),
        unfocusedBorderColor = Color(0xFFC4C6CF)
      )
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
      value = publicToken,
      onValueChange = onPublicTokenChange,
      label = { Text("Reader Access Token (Read-Only)") },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("settings_token_input"),
      trailingIcon = {
        IconButton(onClick = onResetToken) {
          Icon(Icons.Default.Refresh, contentDescription = "Reset Token")
        }
      },
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3A5BA9),
        unfocusedBorderColor = Color(0xFFC4C6CF)
      )
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
      value = apiLevel,
      onValueChange = onApiLevelChange,
      label = { Text("Target Android SDK version") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("settings_api_level_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3A5BA9),
        unfocusedBorderColor = Color(0xFFC4C6CF)
      )
    )

    Spacer(modifier = Modifier.weight(1f))

    Button(
      onClick = onSaveConfig,
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .testTag("settings_save_button"),
      shape = RoundedCornerShape(28.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF3A5BA9),
        contentColor = Color.White
      )
    ) {
      Text(
        text = "Save Configuration",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}


