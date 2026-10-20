import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 培养方案数据由仓库根的 data/curriculum 提供,构建时同步进 assets,不在 android/ 下再存一份副本。
// 与 src-tauri 引用 ../dist、../crates 是同一种仓库内依赖。
val curriculumAssetsDir = layout.buildDirectory.dir("generated/curriculumAssets")

val syncCurriculumAssets by tasks.registering(Copy::class) {
    from(File(rootProject.projectDir, "../data/curriculum")) {
        into("curriculum")
    }
    into(curriculumAssetsDir)
}

// 让读这个目录的任务都等同步完成。除了 merge*Assets，lint 的模型任务也直接吃它，
// 只挂 merge 会让 release 构建卡在 Gradle 的隐式依赖检查上。
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true)) {
        dependsOn(syncCurriculumAssets)
    }
}

// 正式签名钥匙不进仓库:CI 把 secret 还原成文件后设 ONEPKU_KEYSTORE_FILE,
// 本机自测可以在 android/local.properties 里写 onepku.keystore.file=… 等项。
// 缺钥匙时 release 出未签名包(只为验证 R8 与资源收缩),CI 那侧会先挡住不让它发布。
fun signingSecret(env: String, prop: String): String? =
    System.getenv(env) ?: runCatching {
        val local = File(rootProject.projectDir, "local.properties")
        if (!local.exists()) return@runCatching null
        Properties().apply { local.inputStream().use { load(it) } }.getProperty(prop)
    }.getOrNull()

val keystoreFile = signingSecret("ONEPKU_KEYSTORE_FILE", "onepku.keystore.file")?.let { File(it) }
val keystorePasswordValue = signingSecret("ONEPKU_KEYSTORE_PASSWORD", "onepku.keystore.password")
val keystoreAliasValue = signingSecret("ONEPKU_KEYSTORE_ALIAS", "onepku.keystore.alias") ?: "onepku"
// 局部变量不能叫 storeFile/storePassword/keyAlias/keyPassword：在 signingConfigs 的 lambda 里
// 它们会被解析成 receiver 自己的属性，赋值等于原地自赋值，签名会静默缺项。
val keystoreKeyPasswordValue = signingSecret("ONEPKU_KEY_PASSWORD", "onepku.key.password") ?: keystorePasswordValue

android {
    namespace = "me.petertian.onepku"
    compileSdk = 35

    sourceSets.getByName("main").assets.srcDir(curriculumAssetsDir)

    defaultConfig {
        applicationId = "me.petertian.onepku"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
    }

    signingConfigs {
        if (keystoreFile != null && keystoreFile.isFile && keystorePasswordValue != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePasswordValue
                keyAlias = keystoreAliasValue
                keyPassword = keystoreKeyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 所有对外发布的包都用同一把钥匙,否则版本之间签名不一致,系统会拒绝覆盖安装。
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
