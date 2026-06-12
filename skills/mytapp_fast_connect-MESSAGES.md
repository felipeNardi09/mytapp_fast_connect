# Protocolo FAST — Demais Mensagens

> Complemento de [`mytapp_fast_connect-CONFIG-MESSAGE.md`](./mytapp_fast_connect-CONFIG-MESSAGE.md).
> Documenta as mensagens **de saída** `INIT_SERVING`, `MANAGER_START`, `MANAGER_STOP` e as
> mensagens **de entrada** (`STATUS:IDLE`, `SERVING`, `FINISH_SERVING`).
>
> Regras gerais do protocolo (valem para todas):
> - Codificação **UTF-8**.
> - Toda mensagem **de saída** termina em **`&`**.
> - Campos separados por vírgula `,`, sem espaços.
> - Mensagens de **entrada** chegam como strings cruas via notificação BLE (CCCD);
>   o app faz `removeSuffix("&")` antes de parsear.

---

## Resumo do ciclo de vida da sessão

```
CONFIG:...&            → enviado uma vez, logo após conectar (configura a torneira)
        │
        ▼
INIT_SERVING:...&      → enviado no início do pagamento (libera o serviço)
        │
        ▼  (firmware responde)
STATUS:IDLE            ← dispositivo pronto para servir
SERVING:{vol},{vazão}  ← emitido repetidamente enquanto serve
FINISH_SERVING:{vol}   ← emitido quando termina (volume final consumido)

MANAGER_START& / MANAGER_STOP&   → fluxo paralelo (cartão de gerente: sangria/limpeza/servir)
```

---

## 1. `INIT_SERVING` (saída)

Libera o dispositivo para servir após o pagamento aprovado. É a segunda mensagem de
configuração da sessão (depois do `CONFIG`).

### Formato

```
INIT_SERVING:{liquid},{foam},{foamDecayMinutes},{foamMsAfterDecay}&
```

Construção em `viewmodel/PaymentViewModel.kt`:

```kotlin
val message =
    "INIT_SERVING:${config.liquid},${config.foam},${config.foamDecayMinutes},${config.foamMsAfterDecay}&"
```

### De onde vêm os valores

Os 4 campos vêm do mesmo endpoint do `CONFIG` — `GET /devices/info/{tapId}` →
`TapInfoResponse.Open`. Em `TapSelectionViewModel.fetchDeviceInfos()`, logo após montar o
`CONFIG`, monta-se também um `ServingConfig` e guarda-se em
`TapConsumptionHolder.servingConfig`:

```kotlin
val liquid          = open.beerMl
val foam            = open.foamMs
val foamDecayMinutes = open.foamDecayMinutes
val foamMsAfterDecay = open.foamMsAfterDecay
if (liquid != null && foam != null && foamDecayMinutes != null && foamMsAfterDecay != null) {
    TapConsumptionHolder.servingConfig = ServingConfig(liquid, foam, foamDecayMinutes, foamMsAfterDecay)
}
```

| # | Campo no `INIT_SERVING` | `ServingConfig` | Origem no JSON          | Tipo  |
|---|-------------------------|-----------------|-------------------------|-------|
| 1 | `liquid`                | `liquid`        | `beer_ml`               | `Int?` |
| 2 | `foam`                  | `foam`          | `foam_ms`               | `Int?` |
| 3 | `foamDecayMinutes`      | `foamDecayMinutes` | `foam_decay_minutes` | `Int?` |
| 4 | `foamMsAfterDecay`      | `foamMsAfterDecay` | `foam_ms_after_decay`| `Int?` |

`ServingConfig` (`data/holders/TapConsumptionHolder.kt`):

```kotlin
data class ServingConfig(
    val liquid: Int,
    val foam: Int,
    val foamDecayMinutes: Int,
    val foamMsAfterDecay: Int
)
```

### Validação

- Se **qualquer** um dos 4 campos do JSON vier `null`, o `ServingConfig` **não é criado** e
  o `INIT_SERVING` não pode ser enviado (`servingConfig is null, cannot send INIT_SERVING`).
- Não há transformação de unidade — os valores vão diretos (diferente do `adjust` do CONFIG).

### Quando é enviado e resposta esperada

Enviado em `PaymentViewModel.startPayment()`, após o pagamento. O envio é **síncrono com a
resposta**: o app aguarda a primeira mensagem de retorno com timeout de **10 s**:

```kotlin
val responseDeferred = async { withTimeoutOrNull(10_000L) { bluetoothViewModel.incomingMessages.first() } }
val sent = bluetoothViewModel.sendConfigMessage(message)
val response = responseDeferred.await()
if (response == null) { /* "Falha de comunicação, se reconecte via bluetooth" */ }
if (response.startsWith("STATUS:IDLE")) { /* dispositivo pronto */ }
```

- Pré-condição: conexão BLE em estado `Connected` (senão `BluetoothDisconnected`).
- Resposta `null` (timeout) → erro de comunicação.
- Resposta `STATUS:IDLE` → dispositivo pronto para servir.

### Exemplo

`ServingConfig(liquid=300, foam=20, foamDecayMinutes=5, foamMsAfterDecay=1000)` produz:

```
INIT_SERVING:300,20,5,1000&
```

### Na biblioteca

```kotlin
val result = fc.client.sendInitServing(
    ServingParams(liquid = 300, foam = 20, foamDecayMinutes = 5, foamMsAfterDecay = 1000)
)
```

---

## 2. `MANAGER_START` / `MANAGER_STOP` (saída)

Comandos sem parâmetros usados no fluxo de **cartão de gerente** (sangria, limpeza, servir
manual). Não dependem do endpoint REST — são strings fixas.

### Formato

```
MANAGER_START&
MANAGER_STOP&
```

Definição em `viewmodel/ManagerCardViewModel.kt`:

```kotlin
private const val MANAGER_START = "MANAGER_START&"
private const val MANAGER_STOP  = "MANAGER_STOP&"
```

### Quando são enviados

- **`MANAGER_START`** — em `selectMode()`, após um cartão de gerente válido ser lido e um
  modo (`SANGRAR` / `LIMPEZA` / `SERVIR`) ser escolhido. Requer BLE `Connected`; se o envio
  falhar, mostra `"Falha ao enviar comando ao tap"`.
- **`MANAGER_STOP`** — em `stopManagerMode()`, ao encerrar o modo gerente (e, no caso de
  sangria, após salvar o volume sangrado).

Apenas cartões com papel `Gerente` ou `Master` são aceitos (`ALLOWED_ROLES`).

### Na biblioteca

```kotlin
fc.client.managerStart()   // -> SendResult
fc.client.managerStop()    // -> SendResult
```

---

## 3. Mensagens de entrada (do dispositivo → app)

Chegam por notificação BLE como strings cruas e são distribuídas pelo
`BluetoothViewModel.incomingMessages` (um `SharedFlow<String>`). Os consumidores
(`PaymentScreen`, `BeerVolumeScreen`, `ManagerCardViewModel`) fazem o parse por prefixo.

### 3.1 `STATUS:IDLE`

Dispositivo pronto / ocioso, aguardando para servir. Tipicamente a resposta ao
`INIT_SERVING`.

```kotlin
message.startsWith("STATUS:IDLE") -> { preparingToServe = true }
```

### 3.2 `SERVING:{volume},{vazão},...&`

Emitido **repetidamente** durante o serviço, reportando o progresso.

```kotlin
message.startsWith("SERVING:") -> {
    val raw = message.removePrefix("SERVING:").removeSuffix("&")
    servingVolume = viewModel.validateMaxVolumeForFast(raw)   // valida contra o máximo
    servingFinished = false
}
```

- **Campo 0** = volume servido até o momento.
- **Campo 1** = vazão (flow rate). Em `BeerVolumeScreen` é exibido como
  `"Volume: {parts[0]}\nVazão: {parts[1]}"`.
- No fluxo de gerente, `recordServingMessage()` guarda `parts[0]` como volume da sangria.

### 3.3 `FINISH_SERVING:{volume},...&`

Emitido quando o serviço termina. Carrega o **volume final consumido**.

```kotlin
// PaymentScreen
message.startsWith("FINISH_SERVING:") -> {
    val raw = message.removePrefix("FINISH_SERVING:").removeSuffix("&")
    servingVolume = viewModel.validateMaxVolumeForFast(raw)
    servingFinished = true
}

// BluetoothViewModel.onDataReceived (persistência)
if (message.trimEnd('&').startsWith("FINISH_SERVING")) {
    val volume = message.substringAfter("FINISH_SERVING:").substringBefore(",").toIntOrNull() ?: 0
    if (volume == 0) TapConsumptionHolder.isServingBlocked = true
    TapConsumptionHolder.consumptionEnd = System.currentTimeMillis()
    persistPendingConsumption(volume)        // grava o consumo
}
```

- **Campo 0** = volume final (usado para registrar o consumo / cobrança).
- `volume == 0` → marca `isServingBlocked = true` (serviço bloqueado).
- Dispara a persistência do consumo pendente e marca `consumptionEnd`.

### Qualquer outra mensagem

Em `PaymentScreen`, mensagens fora desses prefixos caem no `else` e disparam
`handleBluetoothCommunicationError()`.

### Na biblioteca

Todas as mensagens de entrada são expostas cruas pelo fluxo único:

```kotlin
fc.client.incomingMessages.collect { msg ->
    when {
        msg.startsWith("STATUS:IDLE")     -> { /* pronto */ }
        msg.startsWith("SERVING:")        -> { /* progresso: vol, vazão */ }
        msg.startsWith("FINISH_SERVING:") -> { /* fim: volume final */ }
    }
}
```

---

## 4. Tabela-resumo do protocolo

| Mensagem | Direção | Parâmetros | Origem dos valores |
|----------|---------|------------|--------------------|
| `CONFIG:...&` | saída | 9 campos | `GET /devices/info/{tapId}` (ver doc do CONFIG) |
| `INIT_SERVING:...&` | saída | `liquid, foam, foamDecayMinutes, foamMsAfterDecay` | mesmo endpoint (`beer_ml`, `foam_ms`, `foam_decay_minutes`, `foam_ms_after_decay`) |
| `MANAGER_START&` | saída | — | comando fixo (cartão de gerente) |
| `MANAGER_STOP&` | saída | — | comando fixo (cartão de gerente) |
| `STATUS:IDLE` | entrada | — | firmware (pronto para servir) |
| `SERVING:{vol},{vazão},...&` | entrada | volume, vazão | firmware (durante o serviço) |
| `FINISH_SERVING:{vol},...&` | entrada | volume final | firmware (fim do serviço) |
