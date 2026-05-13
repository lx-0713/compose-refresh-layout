plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

group = findProperty("GROUP")?.toString() ?: "com.lx"
version = findProperty("SMART_REFRESH_VERSION")?.toString() ?: "1.0.0"

android {
    namespace = "com.lx.compose.refresh"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
}

publishing {
    repositories {
        mavenLocal()
    }
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "compose-smart-refresh"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Compose SmartRefresh")
                description.set("A highly customizable and high-performance nested scrolling pull-to-refresh component for Jetpack Compose.")
                url.set("https://github.com/lx-0713/compose-smart-refresh")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://opensource.org/licenses/Apache-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("lx-0713")
                        name.set("lixiong")
                    }
                }
                scm {
                    url.set("https://github.com/lx-0713/compose-smart-refresh")
                    connection.set("scm:git:git://github.com/lx-0713/compose-smart-refresh.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lx-0713/compose-smart-refresh.git")
                }
            }
        }
    }
}

signing {
    val keyId = findProperty("signing.keyId")?.toString() ?: System.getenv("SIGNING_KEY_ID")
    val secretKey = findProperty("signing.secretKey")?.toString() ?: System.getenv("SIGNING_SECRET_KEY")
    val password = findProperty("signing.password")?.toString() ?: System.getenv("SIGNING_PASSWORD")
    val keyFile = findProperty("signing.secretKeyRingFile")?.toString()?.let { path -> file(path) }
    if (keyId != null && password != null && (secretKey != null || keyFile?.exists() == true)) {
        if (secretKey != null) {
            useInMemoryPgpKeys(keyId, secretKey, password)
        } else {
            useInMemoryPgpKeys(keyId, keyFile!!.readText(), password)
        }
        sign(publishing.publications)
    }
}
