plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.petros"

// Plugin version is the running commit count on the branch (cheap auto-
// incrementing integer). Build N+1 is one commit ahead of build N — easy
// to reason about without parsing SHAs.
val buildNumber: String = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    "0"
}
version = "0.1.$buildNumber"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    intellijPlatform {
        // Use the locally installed IDE rather than downloading. JetBrains'
        // Maven repo lags behind point releases by days/weeks; the local
        // install always matches what `runIde` will sandbox against.
        //
        // Resolution order: gradle property `localIdePath` (override), else
        // env var `IREVIEW_LOCAL_IDE_PATH`, else default to the typical
        // user-installed IDEA location.
        val defaultPath = "${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app/Contents"
        val idePath = providers.gradleProperty("localIdePath")
            .orElse(providers.environmentVariable("IREVIEW_LOCAL_IDE_PATH"))
            .orElse(defaultPath)
        local(idePath)
        bundledPlugin("com.intellij.java")
        // Java code instrumentation is enabled by default in plugin 2.2+.
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
