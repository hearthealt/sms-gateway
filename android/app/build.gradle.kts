import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 正式版签名凭据。文件不存在时跳过，保证没配签名的机器仍能构建 debug。
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // 用 reader + UTF-8：Properties.load(InputStream) 按 ISO-8859-1 解析，
        // 会把上面那些中文注释读成乱码
        keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

android {
    namespace = "com.smsgateway.app"
    compileSdk = 34

    defaultConfig {
        // 对外包名（安装后 PackageManager 看到的、签名与升级判定的依据）。
        // 故意与上面的 namespace 分开：namespace 决定 R 类与 BuildConfig 的包路径，
        // 改名要连带搬 30 多个源文件目录，收益为零；applicationId 才是 APK 的身份。
        applicationId = "com.yunyi.smshub"
        minSdk = 26
        targetSdk = 34
        // 版本号与主版本（backend/pom.xml、management/package.json、git tag）保持一致。
        // versionCode 是给系统看的整数、必须单调递增，按 major*10000 + minor*100 + patch 算：
        // 1.0.2 → 10002、1.1.0 → 10100、2.0.0 → 20000。
        versionCode = 10100
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不开混淆：Gson 反序列化的数据类靠反射读字段名，Room 同理，
            // 一旦被 R8 改名就会静默解析失败（表现为「接口通了但字段全是 null」）。
            // 要开的话得先补全 keep 规则并回归测试，见 proguard-rules.pro。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 起默认不生成 BuildConfig，而注册时需要上报 BuildConfig.VERSION_NAME
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // 显式指定版本，不要改用 BOM 的默认值。
    // BOM 2024.01.00 把 compose-ui/animation/foundation 定在 1.6.0，却把 material3 定在 1.1.2，
    // 而 1.1.2 是按 animation-core 1.5.x 编译的。两者混用时，只要组合到 CircularProgressIndicator
    // 这类带动画的组件，运行时就会抛 NoSuchMethodError（KeyframesSpecConfig.at 的返回类型在 1.6 变了），
    // 表现为「一点注册就闪退」。material3 1.2.0 才是与 Compose 1.6.0 配套的版本。
    implementation("androidx.compose.material3:material3:1.2.0")

    // 下拉刷新用的是 **material3 自带**的（`androidx.compose.material3.pulltorefresh`），
    // 队列页与重要日志页同一套。别去引 `androidx.compose.material`（Material 2）——
    // 曾为它多背 456KB，而 material3 1.2.0 里本来就有 `PullToRefreshContainer`。
    implementation("androidx.compose.material:material-icons-extended")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 二维码。
    // zxing:core 负责生成与解码，纯 Java、零传递依赖，minSdk 26 下无需 desugaring。
    // 不要引 zxing:android-core —— 那是给旧 android.hardware.Camera 用的，配 CameraX 用不上。
    implementation("com.google.zxing:core:3.5.3")

    // 相机。**版本必须锁 1.3.4**：1.3.0 起要求 compileSdk 34（本项目正好是 34），
    // 而 1.4.0+ 要求 compileSdk 35，会直接挂在 checkDebugAarMetadata。
    // 另外 camera-compose 在 1.3.x 里并不存在（1.5.0 才稳定），所以预览只能用
    // AndroidView + PreviewView，没有 Compose 原生的取景器可用。
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    testImplementation("junit:junit:4.13.2")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}