buildscript {
    dependencies {
        // AGP 9.5's built-in Kotlin defaults to 2.2.10. Libraries such as
        // coroutines and lifecycle pull kotlin-stdlib 2.4.x, which that
        // compiler cannot read.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
