import java.util.Properties
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

val localMfiTestIdentityDir = providers.gradleProperty("navipilot.localMfiTestIdentityDir").orNull
val debugMfiAssets = layout.buildDirectory.dir("generated/localMfiDebugAssets")
if (localMfiTestIdentityDir != null) {
    val prepareDebugLocalMfiIdentity = tasks.register<Sync>("prepareDebugLocalMfiIdentity") {
        val identityDir = file(localMfiTestIdentityDir)
        from(files(
            identityDir.resolve("identity.pk8"),
            identityDir.resolve("certificate.p7b"),
        )) {
            into("offline-mfi")
        }
        into(debugMfiAssets)
        doFirst {
            require(identityDir.resolve("identity.pk8").isFile &&
                identityDir.resolve("certificate.p7b").isFile
            ) {
                "Debug MFi test identity requires identity.pk8 and certificate.p7b"
            }
        }
    }
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
        dependsOn(prepareDebugLocalMfiIdentity)
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt-config.yml"))
}

android {
    namespace = "com.jixiexiaoge.drivingassist"
    compileSdk = 36
    if (localMfiTestIdentityDir != null) {
        sourceSets.getByName("debug").assets.srcDir(debugMfiAssets)
    }

    val navAbiList: List<String> =
        (project.findProperty("navipilot.abis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")

    defaultConfig {
        applicationId = "com.jixiexiaoge.map2tesla"
        minSdk = 29
        targetSdk = 35
        versionCode = 26102501
        versionName = "5.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub OAuth Client ID（从 local.properties 读取，不硬编码）
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${props.getProperty("GITHUB_CLIENT_ID", "Ov23ctaOHfiktpd9aTE6")}\"")

        ndk {
            abiFilters.clear()
            abiFilters.addAll(navAbiList)
        }
    }


    // 签名配置（从 local.properties 读取，不硬编码密码）
    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
            
            storeFile = project.file(props.getProperty("RELEASE_STORE_FILE", "release.keystore"))
            storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = true
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = false
            packaging {
                jniLibs {
                    pickFirsts += listOf("**/libc++_shared.so")
                    keepDebugSymbols += setOf("*/libc++_shared.so")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    // Kotlin JVM 目标版本（必须与 Java compileOptions 一致）
    kotlinOptions {
        jvmTarget = "11"
        // 修复 Kotlin 编译器内部错误
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",  // 启用 JVM 默认方法
            "-Xno-call-assertions",  // 减少编译时断言检查
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }
    
    // Kotlin Compose Compiler配置（Gradle 9.x + Kotlin 2.1）
    composeCompiler {
        // 启用强跳过模式以提升性能
        // enableStrongSkippingMode = true  // 已废弃，使用featureFlags
    }

    buildFeatures {
        compose = true
        buildConfig = true  // 启用BuildConfig生成
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        disable += "DuplicateNamespace"
        disable += "PackagedPrivateKey"
    }

    // R8优化配置
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "META-INF/com.android.tools/*",
                "META-INF/gradle-plugins/*",
                "META-INF/versions/*",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/**",
            )
        }
        jniLibs {
            pickFirsts += listOf(
                "**/libc++_shared.so",
            )
        }
    }
    
    
}



dependencies {

    implementation(project(":carplay-host"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material View层组件 - 已移除（原为腾讯导航SDK提供主题属性）

    // AppCompat - 已移除（原为腾讯SDK drawable主题解析）

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    testImplementation(libs.junit)
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
