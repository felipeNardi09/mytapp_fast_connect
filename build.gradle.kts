// Raiz do projeto da biblioteca MyTapp Fast Connect.
//
// Dois módulos publicáveis:
//   :core    -> mytapp_fast_connect-core    (Kotlin/JVM puro, protocolo)
//   :android -> mytapp_fast_connect-android (implementação BLE Android, depende do core)
//
// A publicação vai para o GitHub Packages. Configure github.owner / github.repo em
// gradle.properties e as credenciais (gpr.user / gpr.key) em ~/.gradle/gradle.properties
// ou via variáveis de ambiente GITHUB_ACTOR / GITHUB_TOKEN.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    group = "com.mytapp.fastconnect"
    version = "0.1.0"

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    val owner = (findProperty("github.owner") as String?) ?: "OWNER"
                    val repo = (findProperty("github.repo") as String?) ?: "REPO"
                    url = uri("https://maven.pkg.github.com/$owner/$repo")
                    credentials {
                        username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                        password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
