import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // api: faz parte da superfície pública (Flow/Transport expõem coroutines).
    api(libs.kotlinx.coroutines.core)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "mytapp_fast_connect-core"
            from(components["java"])
            pom {
                name.set("MyTapp Fast Connect — Core")
                description.set("Núcleo Kotlin do protocolo MyTapp Fast Connect (independente de plataforma).")
            }
        }
    }
}
