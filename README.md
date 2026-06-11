# MyTapp Fast Connect

Biblioteca para o protocolo **MyTapp Fast Connect** sobre BLE. Dois módulos:

| Módulo | Coordenada Maven | O que é |
| --- | --- | --- |
| `:core` | `com.mytapp.fastconnect:mytapp_fast_connect-core` | Protocolo em Kotlin/JVM puro (sem Android): `MyTappFastConnectClient`, `MessageBuilder`, `Protocol`, `ConfigParams`, `ServingParams`, `Transport`, `SendResult`, `Logger`. |
| `:android` | `com.mytapp.fastconnect:mytapp_fast_connect-android` | Implementação BLE Android: `MyTappFastConnect`, `BleConnectionManager`, `BleTransport`, `BleConnectionState`, `BleDevice`, `AndroidLogger`. Depende do `core`. |

- **Versão atual:** `0.1.0`
- **minSdk:** 24
- **Distribuição:** duas opções —
  - **JitPack** (recomendado p/ consumidores): público, **sem token**.
  - **GitHub Packages**: público, mas o download de artefatos Maven **sempre exige um token** (limitação do GitHub).

---

## Opção A — Consumir via JitPack (sem token, recomendado)

O [JitPack](https://jitpack.io) compila a lib direto do repositório público e serve os artefatos **sem necessidade de token**. É o jeito mais simples para quem vai consumir.

### 1. Declare o repositório (no `settings.gradle.kts` do projeto cliente)

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Adicione a dependência

```kotlin
dependencies {
    // App Android (BLE):
    implementation("com.github.felipeNardi09.mytapp_fast_connect:mytapp_fast_connect-android:0.1.0")

    // OU apenas o protocolo (Kotlin/JVM puro, sem Android):
    // implementation("com.github.felipeNardi09.mytapp_fast_connect:mytapp_fast_connect-core:0.1.0")
}
```

> Pelo JitPack o `group` é `com.github.felipeNardi09.mytapp_fast_connect` (prefixo do JitPack + repo).
> A primeira build de uma versão nova pode levar alguns minutos (o JitPack compila sob demanda).

---

## Opção B — Consumir via GitHub Packages (exige token)

O pacote é **público**, então qualquer pessoa com conta no GitHub pode consumir — não é preciso pedir acesso. Porém, o GitHub Packages **não permite download anônimo de artefatos Maven**: mesmo público, cada consumidor precisa autenticar com um **Personal Access Token (PAT) próprio** do GitHub com o escopo **`read:packages`**. Cada um usa o seu token; ninguém compartilha o do mantenedor.

### 1. Guarde as credenciais fora do projeto

Em `~/.gradle/gradle.properties` (no computador do desenvolvedor, **não** no repositório):

```properties
gpr.user=SEU_USUARIO_GITHUB
gpr.key=ghp_seuTokenComEscopo_read_packages
```

### 2. Declare o repositório (no `settings.gradle.kts` do projeto cliente)

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/felipeNardi09/mytapp_fast_connect")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

### 3. Adicione a dependência

```kotlin
dependencies {
    // App Android:
    implementation("com.mytapp.fastconnect:mytapp_fast_connect-android:0.1.0")

    // OU apenas o protocolo (Kotlin/JVM puro, sem Android):
    // implementation("com.mytapp.fastconnect:mytapp_fast_connect-core:0.1.0")
}
```

### 4. Uso no Android

```kotlin
val fc = MyTappFastConnect.create(context)
fc.transport.startScan()
// colete fc.transport.discoveredDevices, então:
fc.transport.connect(address)
// já conectado:
val result = fc.client.sendConfig(params)
```

> ⚠️ A lib não tem UI e **não pede permissões em runtime**. O app host deve solicitar
> `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+) ou `ACCESS_FINE_LOCATION` (API 24–30)
> antes de escanear/conectar.

---

## Como publicar uma nova versão (mantenedor)

### Manual

```bash
# token com escopo write:packages
export GITHUB_ACTOR=seu-usuario
export GITHUB_TOKEN=ghp_tokenComEscopo_write_packages

./gradlew publish -Pgithub.owner=felipeNardi09 -Pgithub.repo=mytapp_fast_connect
```

Para subir só um módulo: `./gradlew :core:publish` ou `./gradlew :android:publish`.

### Automático (CI)

O workflow `.github/workflows/publish.yml` publica sozinho quando você cria uma **Release**
no GitHub (usa o `GITHUB_TOKEN` da Action, sem precisar de PAT). Basta criar a release/tag.

### Versionamento

A versão fica em `build.gradle.kts` (bloco `subprojects { version = "..." }`). Faça bump
seguindo SemVer antes de publicar. O `Protocol.VERSION` (em `core`) é a versão do **protocolo
de fio**, independente da versão Maven.
