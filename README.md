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

## Protocolo de mensagens

Esta seção descreve **como cada mensagem é enviada e recebida** — é o que você precisa para
consumir a lib. A documentação detalhada de cada mensagem está em
[`skills/`](./skills/) (`mytapp_fast_connect-CONFIG-MESSAGE.md` e `mytapp_fast_connect-MESSAGES.md`).

### Regras gerais

- Codificação **UTF-8**; toda mensagem **de saída** termina em **`&`**.
- Campos separados por vírgula `,`, **sem espaços**.
- Mensagens de **entrada** chegam como strings cruas via notificação BLE (CCCD).
- A lib monta o byte string a partir de objetos tipados (`ConfigParams` / `ServingParams`) —
  você nunca concatena a string na mão.
- Antes de enviar, a conexão é validada; se desconectado, retorna `SendResult.NotConnected`.
- `Protocol.VERSION` (no `core`) identifica a versão do **protocolo de fio**; firmware e lib
  evoluem juntos. Versão atual: **v1**.

### Ciclo de vida de uma sessão

```
connect()
   │
   ▼
CONFIG:...&            (configura a torneira — logo após conectar)
   │
   ▼
INIT_SERVING:...&      (libera o serviço — no início do pagamento)
   │
   ▼  respostas do firmware
STATUS:IDLE            → pronto para servir
SERVING:{vol},{vazão}  → progresso (repetido)
FINISH_SERVING:{vol}   → fim (volume final consumido)

MANAGER_START& / MANAGER_STOP&   → fluxo paralelo (cartão de gerente)
```

### De onde vêm os valores

`CONFIG` e `INIT_SERVING` derivam da mesma chamada REST, feita pelo app host
(a lib **não** faz essa chamada — ela só monta e envia a mensagem BLE):

```
GET {baseUrl}/devices/info/{tapId}   →   resposta da torneira "aberta"
```

No app de referência, a `baseUrl` vem do campo `url` do JSON do QRCode de configuração.
O host mapeia os campos dessa resposta para `ConfigParams` / `ServingParams`.

---

### `CONFIG` (saída) — 1ª mensagem

Transmite toda a configuração da torneira. Formato (9 campos):

```
CONFIG:{volumeShown},{stepsForward},{stepsBackward},{tick},{adjust_ms},{cupDist},{RRGGBB},{clientTimeout},{hall 1|0}&
```

| # | Campo            | `ConfigParams`        | Transformação aplicada pela lib |
|---|------------------|-----------------------|---------------------------------|
| 1 | `volumeShown`    | `volumeShown: Int`    | nenhuma |
| 2 | `stepsForward`   | `stepsForward: Int`   | nenhuma |
| 3 | `stepsBackward`  | `stepsBackward: Int`  | nenhuma |
| 4 | `tick`           | `tick: Int`           | nenhuma |
| 5 | `adjust_ms`      | `adjustSeconds: Double` | **`round(adjustSeconds × 1000)`** → segundos viram ms |
| 6 | `cupDist`        | `cupDist: Int`        | nenhuma |
| 7 | `RRGGBB`         | `ledColor: String`    | **`normalizeLedColor()`** → `RRGGBB` maiúsculo, sem `#` |
| 8 | `clientTimeout`  | `clientTimeout: Int`  | nenhuma |
| 9 | `hall`           | `useHallPositioning: Boolean` | `true → 1`, `false → 0` |

**`ledColor`** aceita vários formatos e normaliza para `RRGGBB`:

| Entrada | Saída | | Entrada | Saída |
|---|---|---|---|---|
| `"00FF00"` | `00FF00` | | `"0,255,0"` | `00FF00` |
| `"#00ff00"` | `00FF00` | | `"{0,255,0}"` | `00FF00` |
| inválido / não parseável | `FFFFFF` (fallback) | | | |

**Importante sobre `adjust`:** passe o valor **em segundos** (ex.: `0.25`); a lib aplica
`round(adjustSeconds × 1000)` internamente (→ `250`).

```kotlin
val result = fc.client.sendConfig(
    ConfigParams(
        volumeShown        = 300,
        stepsForward       = 200,
        stepsBackward      = 200,
        tick               = 8,
        adjustSeconds      = 0.25,      // em segundos
        cupDist            = 120,
        ledColor           = "#00FF00", // ou "0,255,0", "00FF00"...
        clientTimeout      = 60,
        useHallPositioning = true,
    ),
)
// → CONFIG:300,200,200,8,250,120,00FF00,60,1&
```

---

### `INIT_SERVING` (saída) — libera o serviço

Enviada após o pagamento aprovado. Formato (4 campos, **sem transformação de unidade**):

```
INIT_SERVING:{liquid},{foam},{foamDecayMinutes},{foamMsAfterDecay}&
```

| # | Campo              | `ServingParams`           |
|---|--------------------|---------------------------|
| 1 | `liquid`           | `liquid: Int`             |
| 2 | `foam`             | `foam: Int`               |
| 3 | `foamDecayMinutes` | `foamDecayMinutes: Int`   |
| 4 | `foamMsAfterDecay` | `foamMsAfterDecay: Int`   |

```kotlin
val result = fc.client.sendInitServing(
    ServingParams(liquid = 300, foam = 20, foamDecayMinutes = 5, foamMsAfterDecay = 1000),
)
// → INIT_SERVING:300,20,5,1000&
```

A resposta esperada é a primeira mensagem de entrada (tipicamente `STATUS:IDLE`).

---

### `MANAGER_START` / `MANAGER_STOP` (saída) — cartão de gerente

Comandos fixos, sem parâmetros, para o fluxo de gerente (sangria, limpeza, servir manual).

```
MANAGER_START&
MANAGER_STOP&
```

```kotlin
fc.client.managerStart()   // → SendResult
fc.client.managerStop()    // → SendResult
```

---

### Mensagens de entrada (dispositivo → app)

Chegam cruas pelo fluxo único `fc.client.incomingMessages` (um `Flow<String>`). Faça o parse
por prefixo:

```kotlin
fc.client.incomingMessages.collect { msg ->
    when {
        msg.startsWith("STATUS:IDLE")     -> { /* pronto para servir */ }
        msg.startsWith("SERVING:")        -> { /* progresso: {volume},{vazão},... */ }
        msg.startsWith("FINISH_SERVING:") -> { /* fim: {volume final},... */ }
    }
}
```

| Mensagem | Significado | Campos |
|----------|-------------|--------|
| `STATUS:IDLE` | dispositivo pronto/ocioso | — |
| `SERVING:{vol},{vazão},...&` | progresso (emitido repetidamente) | `0`=volume servido, `1`=vazão |
| `FINISH_SERVING:{vol},...&` | fim do serviço | `0`=volume final consumido |

> As mensagens de entrada vêm com `&` no fim; remova-o (`removeSuffix("&")`) antes de parsear.
> Em `FINISH_SERVING`, `volume == 0` indica serviço bloqueado no fluxo de referência.

---

### Tabela-resumo

| Mensagem | Direção | Parâmetros | Origem |
|----------|---------|------------|--------|
| `CONFIG:...&` | saída | 9 campos | `GET /devices/info/{tapId}` |
| `INIT_SERVING:...&` | saída | `liquid, foam, foamDecayMinutes, foamMsAfterDecay` | mesmo endpoint |
| `MANAGER_START&` | saída | — | comando fixo |
| `MANAGER_STOP&` | saída | — | comando fixo |
| `STATUS:IDLE` | entrada | — | firmware |
| `SERVING:{vol},{vazão},...&` | entrada | volume, vazão | firmware |
| `FINISH_SERVING:{vol},...&` | entrada | volume final | firmware |

### `SendResult` — retorno de todo envio

`sendConfig` / `sendInitServing` / `managerStart` / `managerStop` retornam:

| Resultado | Quando ocorre |
|-----------|---------------|
| `Success` | mensagem montada e aceita pelo transporte para entrega |
| `NotConnected` | não havia conexão BLE viva; nada foi enviado |
| `InvalidPayload(field)` | um campo falhou na validação (nomeado em `field`); nada foi enviado |
| `TransportError(reason)` | o transporte rejeitou a mensagem válida |
