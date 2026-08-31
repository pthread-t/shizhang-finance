plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
}

providers.gradleProperty("billRecord.buildRoot").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { isolatedRoot ->
        allprojects {
            val projectDirectory = if (path == ":") "root" else path.trim(':').replace(':', '/')
            layout.buildDirectory.set(file("$isolatedRoot/$projectDirectory"))
        }
    }
