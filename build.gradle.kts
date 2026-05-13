plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

nexusPublishing {
    packageGroup.set(findProperty("GROUP")?.toString() ?: "com.lx")
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/snapshots/"))
            username.set(findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: "")
            password.set(findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: "")
        }
    }
}
