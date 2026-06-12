# Mensagem `CONFIG` — Documentação do Payload

> Esta é a **primeira mensagem** enviada ao dispositivo FAST logo após a conexão BLE.
> Ela transmite toda a configuração da torneira (volume, passos do motor, sensor, LED,
> timeouts, etc.) que o firmware precisa antes de servir.
>
> Fonte da verdade no app:
> `app/src/main/java/com/mytapp/mytapppagueeretire/bluetooth/FastConfigMessage.kt`

---

## 1. Visão geral do fluxo (end-to-end)

```
┌────────────────────┐   GET /devices/info/{tapId}   ┌──────────────────┐
│  Backend (REST)    │ ────────────────────────────► │  App Android     │
│                    │ ◄──── TapInfoResponse.Open ─── │ (RetrofitClient) │
└────────────────────┘                                └────────┬─────────┘
                                                                │ .toFastConfigMessage()
                                                                │ (monta a string CONFIG)
                                                                ▼
                                          ┌──────────────────────────────────┐
                                          │ "CONFIG:300,200,200,8,250,120,    │
                                          │          00FF00,60,1&"            │
                                          └────────────────┬───────────────────┘
                                                           │ sendConfig(params)
                                                           ▼
┌────────────────────┐   write characteristic (UTF-8)  ┌──────────────────┐
│  Dispositivo FAST  │ ◄──────────────────────────────│  Transport BLE   │
│  (firmware)        │ ── STATUS:IDLE / SERVING:... ──►│  (GATT / MTU)    │
└────────────────────┘                                 └──────────────────┘
```

Resumindo, há **três etapas**:

1. **Obter os valores** — uma chamada REST `GET /devices/info/{tapId}` retorna o objeto
   `TapInfoResponse.Open`.
2. **Montar a mensagem** — os campos desse objeto são mapeados, transformados e
   concatenados na string `CONFIG:...&`.
3. **Enviar via BLE** — a string é escrita na característica gravável do dispositivo
   conectado.

---

## 2. De onde vêm os valores (o endpoint)

### Endpoint

| Item | Valor |
|------|-------|
| Método | `GET` |
| Path | `/devices/info/{tapId}` |
| Parâmetro | `tapId` (path, `Int`) |
| Corpo | nenhum |
| Resposta | `TapInfoResponse` (sealed: `Open` ou `ClosedResponse`) |

Definição em `data/network/ApiService.kt`:

```kotlin
@GET("/devices/info/{tapId}")
suspend fun getDeviceInfo(@Path("tapId") tapId: Int): TapInfoResponse
```

A mensagem `CONFIG` só é montada quando a resposta é `TapInfoResponse.Open`
(bar/torneira aberta). Quando é `ClosedResponse`, nada é enviado.

### Base URL

A base URL **não é fixa no código**. Ela é lida do dado de QRCode salvo localmente no
dispositivo (`data/network/RetrofitClient.kt`):

```kotlin
suspend fun fetch(context: Context): ApiService {
    var baseUrl = ""
    val storageData = JSONObject(getQRCodeData(context))   // QRCode salvo no storage
    if (storageData.has("url")) {
        baseUrl = storageData.getString("url")             // campo "url" do QRCode
    }
    // ... Retrofit.Builder().baseUrl(baseUrl) ...
}
```

Ou seja, o operador escaneia um QRCode de configuração que contém um JSON com o campo
`url`; essa URL vira a base de todas as chamadas. A URL completa fica:

```
{url do QRCode} + /devices/info/{tapId}
Ex.: http://172.16.224.81:3000/devices/info/1
```

### Onde a chamada acontece no app

`viewmodel/TapSelectionViewModel.kt → fetchDeviceInfos()`:

```kotlin
val apiService = RetrofitClient.fetch(getApplication())
for (tapId in tapIdsList) {
    when (val response = apiService.getDeviceInfo(tapId)) {
        is TapInfoResponse.Open -> {
            // ... ajustes de preço/promoção ...
            if (BarInfoHolder.is_fast_enabled) {
                val configMsg = (updatedResponse as TapInfoResponse.Open).toFastConfigMessage()
                if (configMsg != null) {
                    bluetoothViewModel?.sendConfigMessage(configMsg)   // ← envio BLE
                }
            }
        }
        is TapInfoResponse.ClosedResponse -> { /* nada de CONFIG */ }
    }
}
```

> Observação: o envio só ocorre quando a flag `BarInfoHolder.is_fast_enabled` é `true`.
> Existe um segundo ponto de envio em `ui/screens/BeerVolumeScreen.kt`, que reenvia o
> `CONFIG` assim que a conexão BLE muda para `Connected` (cobre o caso da resposta já ter
> chegado antes do BLE conectar).

---

## 3. Mapeamento campo a campo (JSON → CONFIG)

A string final tem **9 campos**, separados por vírgula, nesta ordem fixa
(`FastConfigMessage.kt`, linhas 48–49):

```
CONFIG:{volumeShown},{stepsForward},{stepsBackward},{tick},{adjust},{cupDist},{ledColor},{clientTimeout},{hall}&
```

| # | Campo no CONFIG | Origem no JSON (`TapInfoResponse.Open`) | Tipo | Transformação aplicada |
|---|-----------------|------------------------------------------|------|------------------------|
| 1 | `volumeShown`   | `volume_shown`        | `Int?`     | nenhuma |
| 2 | `stepsForward`  | `steps_forward`       | `Int?`     | nenhuma |
| 3 | `stepsBackward` | `steps_backward`      | `Int?`     | nenhuma |
| 4 | `tick`          | `sensor.tick`         | `Int`      | nenhuma (vem do objeto `sensor`) |
| 5 | `adjust`        | `sensor.adjust`       | `Float`    | **`round(adjust * 1000)`** → segundos vira milissegundos |
| 6 | `cupDist`       | `cup_dist`            | `Int?`     | nenhuma |
| 7 | `ledColor`      | `led_color`           | `String?`  | **`normalizeLedColor()`** → `RRGGBB` maiúsculo, sem `#` |
| 8 | `clientTimeout` | `client_timeout`      | `Int?`     | nenhuma |
| 9 | `hall`          | `use_hall_positioning`| `Boolean?` | `true → 1`, `false → 0` |

### Detalhe dos campos nullable

Os campos vindos diretamente de `Open` são **nullable** no modelo
(`data/model/TapInfoResponse.kt`):

```kotlin
@SerializedName("volume_shown")        val volumeShown: Int? = null
@SerializedName("steps_forward")       val stepsForward: Int? = null
@SerializedName("steps_backward")      val stepsBackward: Int? = null
@SerializedName("cup_dist")            val cupDist: Int? = null
@SerializedName("led_color")           val ledColor: String? = null
@SerializedName("client_timeout")      val clientTimeout: Int? = null
@SerializedName("use_hall_positioning") val useHallPositioning: Boolean? = null
```

Já `tick` e `adjust` vêm do objeto não-nulo `sensor`:

```kotlin
data class Sensor(
    @SerializedName("model")  val model: String,
    @SerializedName("tick")   val tick: Int,
    @SerializedName("adjust") val adjust: Float
)
```

### Transformação 1 — `adjust`

`sensor.adjust` é um `Float` em **segundos**. O firmware espera **milissegundos inteiros**:

```kotlin
val adjust = (sensor.adjust * 1000).roundToInt()
// ex.: 0.25 → 250 ; 1.5 → 1500
```

### Transformação 2 — `ledColor` (`normalizeLedColor`)

Normaliza qualquer formato de cor para `RRGGBB` em maiúsculas, sem `#`. Aceita:

| Entrada            | Saída    |
|--------------------|----------|
| `"00FF00"`         | `00FF00` |
| `"#00ff00"`        | `00FF00` |
| `"0,255,0"`        | `00FF00` |
| `"{0,255,0}"`      | `00FF00` |
| inválido / não parseável | `FFFFFF` (fallback) |

### Transformação 3 — `hall`

```kotlin
val hallField = if (useHallPositioning) 1 else 0
```

---

## 4. Regras de validação

A função `toFastConfigMessage()` retorna **`null`** (e **não envia nada**, apenas loga um
`Log.w`) se **qualquer** um destes 7 campos for `null`:

`volume_shown`, `steps_forward`, `steps_backward`, `cup_dist`, `led_color`,
`client_timeout`, `use_hall_positioning`.

Se o JSON vier sem algum desses campos, o CONFIG é silenciosamente pulado. Ao reproduzir,
**garanta que o backend retorne todos os 7**.

Há ainda um aviso (não-bloqueante) se a mensagem final passar de **200 bytes** — é só um
`Log.w` de alerta, a mensagem ainda é enviada.

---

## 5. Formato final + exemplo

**Formato:**

```
CONFIG:{volumeShown},{stepsForward},{stepsBackward},{tick},{adjust_ms},{cupDist},{RRGGBB},{clientTimeout},{hall}&
```

- Codificação: **UTF-8**
- Terminador: **`&`** (faz parte da mensagem)
- Sem espaços; separador é vírgula `,`

**Exemplo concreto.** Dado o JSON de resposta:

```json
{
  "tap_id": 1,
  "volume_shown": 300,
  "steps_forward": 200,
  "steps_backward": 200,
  "sensor": { "model": "X", "tick": 8, "adjust": 0.25 },
  "cup_dist": 120,
  "led_color": "#00FF00",
  "client_timeout": 60,
  "use_hall_positioning": true
}
```

A mensagem produzida é:

```
CONFIG:300,200,200,8,250,120,00FF00,60,1&
```

(observe `adjust`: `0.25 × 1000 = 250`; `led_color`: `#00FF00 → 00FF00`; `hall`: `true → 1`)

---

## 6. Passo a passo de reprodução (com a API da biblioteca)

A biblioteca `mytapp_fast_connect` expõe `transport` (scan/connect) e `client`
(montagem + envio das mensagens). Combinando com o endpoint REST, o fluxo completo é:

```kotlin
// (0) Obtenha a base URL — no app vem do QRCode; aqui pode ser fixa para teste.
//     baseUrl = JSON do QRCode -> campo "url"

// (1) Busque a configuração da torneira no backend.
//     GET {baseUrl}/devices/info/{tapId}  ->  TapInfoResponse.Open
val open = apiService.getDeviceInfo(tapId) as TapInfoResponse.Open

// (2) Crie a lib e descubra/conecte o dispositivo BLE.
val fc = MyTappFastConnect.create(context)
fc.transport.startScan()
// colete fc.transport.discoveredDevices, então:
fc.transport.connect(address)

// (3) Monte os parâmetros a partir da resposta do endpoint.
//     (mapeamento idêntico à tabela da seção 3 — adjust em SEGUNDOS aqui;
//      a lib aplica round(adjustSeconds * 1000) internamente)
val params = ConfigParams(
    volumeShown        = open.volumeShown!!,
    stepsForward       = open.stepsForward!!,
    stepsBackward      = open.stepsBackward!!,
    tick               = open.sensor.tick,
    adjustSeconds      = open.sensor.adjust.toDouble(),   // 0.25 (segundos)
    cupDist            = open.cupDist!!,
    ledColor           = open.ledColor!!,                 // "#00FF00", "0,255,0", etc.
    clientTimeout      = open.clientTimeout!!,
    useHallPositioning = open.useHallPositioning!!,
)

// (4) Já conectado: envie o CONFIG.
val result = fc.client.sendConfig(params)
// result: Success | NotConnected | InvalidPayload(field) | TransportError(reason)
```

### Mapeamento `TapInfoResponse.Open` → `ConfigParams`

| `ConfigParams`        | Origem                       | Observação |
|-----------------------|------------------------------|------------|
| `volumeShown`         | `open.volumeShown`           | obrigatório (não-null) |
| `stepsForward`        | `open.stepsForward`          | obrigatório |
| `stepsBackward`       | `open.stepsBackward`         | obrigatório |
| `tick`                | `open.sensor.tick`           | do objeto `sensor` |
| `adjustSeconds`       | `open.sensor.adjust`         | **em segundos** — a lib multiplica por 1000 |
| `cupDist`             | `open.cupDist`               | obrigatório |
| `ledColor`            | `open.ledColor`              | a lib normaliza para `RRGGBB` |
| `clientTimeout`       | `open.clientTimeout`         | obrigatório |
| `useHallPositioning`  | `open.useHallPositioning`    | obrigatório; vira `1`/`0` |

> **Importante sobre `adjust`:** no app legado, `toFastConfigMessage()` recebe o valor já
> como `Float` e faz `round(adjust * 1000)`. Na biblioteca, `ConfigParams.adjustSeconds` é
> `Double` e a **mesma** conversão (`round(adjustSeconds * 1000)`) é aplicada dentro do
> builder. Portanto passe o valor **em segundos**, não em milissegundos.

---

## 7. Notas de transporte (camada BLE)

Detalhes do `Transport`/`BleManager` que afetam o envio:

- A string é convertida em **bytes UTF-8** e escrita na característica gravável detectada
  (primeira característica com `WRITE_NO_RESPONSE`, com fallback para `WRITE`).
- **MTU** negociado é 512; mensagens são fragmentadas em chunks de **`MTU − 3`** bytes,
  enfileiradas e drenadas uma a uma (com até 10 retentativas / backoff de 300 ms).
- O `CONFIG` (algumas dezenas de bytes) cabe folgadamente em um único chunk; o aviso de
  **200 bytes** em `FastConfigMessage.kt` é só um alerta de log.
- O dispositivo responde por **notificações** (CCCD), entregues como strings cruas. Mensagens
  típicas de retorno: `STATUS:IDLE`, `SERVING:{volume},...`, `FINISH_SERVING:{volume},...`.
- `sendConfig` valida a conexão **antes** de enviar: se não estiver conectado, retorna
  `NotConnected` sem escrever nada.

---

## 8. Checklist de reprodução

1. [ ] QRCode de configuração contém um JSON com o campo `url`.
2. [ ] `GET {url}/devices/info/{tapId}` responde `200` com `TapInfoResponse.Open`.
3. [ ] A resposta inclui os 7 campos nullable preenchidos (senão o CONFIG é pulado).
4. [ ] O objeto `sensor` traz `tick` e `adjust`.
5. [ ] Dispositivo BLE descoberto via `transport.startScan()` e conectado via `connect()`.
6. [ ] `client.sendConfig(params)` retorna `Success`.
7. [ ] Dispositivo passa a emitir `STATUS:IDLE` (pronto para servir).
