import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import java.util.Properties
import java.util.regex.Pattern

val isRelease: Boolean
    get() = gradle.startParameter.taskNames.any { it.contains("Release") }

plugins {
    alias(libs.plugins.ehviewer.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibrariesAndroid)
    alias(libs.plugins.baselineprofile)
}

val supportedAbis = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
val releaseAbi = providers.gradleProperty("releaseAbi").orNull
require(releaseAbi == null || releaseAbi in supportedAbis || releaseAbi == "universal") {
    "releaseAbi must be arm64-v8a, armeabi-v7a, x86_64 or universal"
}
val buildAbis = releaseAbi?.takeUnless { it == "universal" }?.let { arrayOf(it) } ?: supportedAbis

android {
    splits {
        abi {
            isEnable = releaseAbi != "universal"
            reset()
            if (isRelease) {
                include(*buildAbis)
                isUniversalApk = releaseAbi == null
            } else {
                include("arm64-v8a", "x86_64")
            }
        }
    }

    // 优先使用外部私有签名配置或环境变量，均缺失时才回退仓库默认调试签名
    val signingProperties = Properties().apply {
        rootProject.file(".private-signing/signing.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    fun signingValue(environment: String, property: String): String? =
        providers.environmentVariable(environment).orNull ?: signingProperties.getProperty(property)

    val customStorePath = signingValue("SIGNING_STORE_FILE", "storeFile")
        ?: rootProject.file(".private-signing/androidkey.jks").takeIf { it.isFile }?.absolutePath
    val customStorePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
    val customKeyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
    val customKeyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
    val hasCustomSigning = customStorePath != null && file(customStorePath).isFile &&
        !customStorePassword.isNullOrEmpty() &&
        !customKeyAlias.isNullOrEmpty() &&
        !customKeyPassword.isNullOrEmpty()

    val signConfig = signingConfigs.create("release") {
        if (hasCustomSigning) {
            storeFile = file(customStorePath)
            storePassword = customStorePassword
            keyAlias = customKeyAlias
            keyPassword = customKeyPassword
        } else {
            storeFile = File(projectDir.path + "/keystore/androidkey.jks")
            storePassword = "000000"
            keyAlias = "key0"
            keyPassword = "000000"
        }
        enableV3Signing = true
        enableV4Signing = true
    }

    fun gitValue(vararg args: String, fallback: String): String = runCatching {
        providers.exec { commandLine = listOf("git", *args) }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    val commitSha = gitValue("rev-parse", "--short=7", "HEAD", fallback = "unknown")
    val commitTime = gitValue("log", "-1", "--format=%ct", fallback = "0")
    val repoName = gitValue("remote", "get-url", "origin", fallback = "ikura23/EhViewer")
        .removePrefix("https://github.com/")
        .removePrefix("git@github.com:")
        .removePrefix("https://codeberg.org/")
        .removePrefix("git@codeberg.org:")
        .removeSuffix(".git")

    val snapshot = !hasProperty("release")

    defaultConfig {
        applicationId = "com.ys.ehviewer"
        versionCode = 180063
        versionName = if (snapshot) {
            "1.15.0-SNAPSHOT"
        } else {
            "1.14.6"
        }
        buildConfigField("boolean", "SNAPSHOT", "$snapshot")
        buildConfigField("String", "RAW_VERSION_NAME", "\"$versionName\"")
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("long", "COMMIT_TIME", commitTime)
        buildConfigField("String", "REPO_NAME", "\"$repoName\"")
        ndk {
            if (isRelease && (releaseAbi == null || releaseAbi == "universal")) {
                abiFilters.addAll(supportedAbis)
            }
            debugSymbolLevel = "FULL"
        }
    }

    flavorDimensions += "api"

    productFlavors {
        create("default") {
            minSdk = 26
        }
        create("marshmallow") {
            applicationIdSuffix = ".m"
            versionNameSuffix = "-M"
        }
    }

    externalNativeBuild {
        cmake {
            path = File("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
        jniLibs {
            excludes += "**/libdatastore_shared_counter.so" // DataStore multi-process
        }
        resources {
            // Required by Layout Inspector
            pickFirsts += "/META-INF/androidx.compose.ui_ui.version"

            excludes += listOf(
                "/META-INF/**",
                "/kotlin/**",
                "**.txt",
                "**.bin",
            )
        }
    }

    androidResources {
        ignoreAssetsPatterns += listOf(
            "!PublicSuffixDatabase.list", // OkHttp
            "!composepreference.preference.generated.resources",
        )
        generateLocaleConfig = true
        localeFilters += listOf(
            "zh",
            "zh-rCN",
            "zh-rHK",
            "zh-rTW",
            "es",
            "ja",
            "ko",
            "fr",
            "de",
            "th",
            "tr",
            "nb-rNO",
        )
    }

    dependenciesInfo.includeInApk = false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signConfig
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        create("benchmarkRelease") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "com.hippo.ehviewer"
}

baselineProfile {
    mergeIntoMain = true
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.i18n)
    implementation(projects.core.ui)

    // https://developer.android.com/jetpack/androidx/releases/activity
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.compiler)

    implementation(libs.compose.preference) {
        // R8 won't remove it because it adds a content provider
        exclude(group = "org.jetbrains.compose.components", module = "components-resources")
    }

    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.datastore)

    // https://developer.android.com/jetpack/androidx/releases/lifecycle
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.compose)

    // https://developer.android.com/jetpack/androidx/releases/paging
    implementation(libs.androidx.paging.compose)

    // https://developer.android.com/jetpack/androidx/releases/room
    implementation(libs.androidx.room.paging)

    implementation(libs.androidx.work.runtime)
    implementation(libs.material.motion.core)
    implementation(libs.material.kolor)

    implementation(libs.bundles.splitties)

    // https://square.github.io/okhttp/changelogs/changelog/
    implementation(platform(libs.okhttp.bom))

    implementation(libs.logcat)

    implementation(libs.diff)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.accompanist.drawable.painter)

    implementation(libs.reorderable)

    implementation(platform(libs.arrow.stack))
    implementation(libs.bundles.arrow)

    // https://coil-kt.github.io/coil/changelog/
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)

    implementation(libs.telephoto.zoomable)

    implementation(libs.ktor.client.okhttp)

    implementation(libs.bundles.kotlinx.serialization)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":benchmark"))

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "coil3.annotation.ExperimentalCoilApi",
            "androidx.paging.ExperimentalPagingApi",
            "me.saket.telephoto.ExperimentalTelephotoApi",
        )
    }
}

ksp {
    arg("compose-destinations.codeGenPackageName", "com.hippo.ehviewer.ui")
}

aboutLibraries {
    collect {
        includePlatform = false
    }
    library {
        exclusionPatterns.add(Pattern.compile("org\\.jetbrains\\.(?:compose|androidx)\\..*"))
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.GROUP
    }
}
