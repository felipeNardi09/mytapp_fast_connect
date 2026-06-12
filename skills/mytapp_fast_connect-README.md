# `mytapp_fast_connect` — Protocolo de Comunicação FAST

Biblioteca para **escanear**, **conectar** e **trocar mensagens** com o dispositivo FAST
(torneira) via BLE. Este README é o índice da documentação do **protocolo**: o formato das
mensagens, de onde vêm os valores e como reproduzir o fluxo do app.

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [`mytapp_fast_connect-CONFIG-MESSAGE.md`](./mytapp_fast_connect-CONFIG-MESSAGE.md) | Mensagem `CONFIG` (a 1ª enviada): fluxo end-to-end, endpoint de origem dos valores, mapeamento campo a campo, transformações, validação e passo a passo de reprodução. |
| [`mytapp_fast_connect-MESSAGES.md`](./mytapp_fast_connect-MESSAGES.md) | Demais mensagens: `INIT_SERVING`, `MANAGER_START`/`MANAGER_STOP` (saída) e `STATUS:IDLE`, `SERVING`, `FINISH_SERVING` (entrada). |
| [`mytapp_fast_connect-DEVELOPMENT-PROMPT.md`](./mytapp_fast_connect-DEVELOPMENT-PROMPT.md) | Prompt de desenvolvimento / arquitetura planejada da biblioteca (core JVM + Android + wrappers Flutter/RN). |

## Uso da biblioteca (resumo)

```kotlin
val fc = MyTappFastConnect.create(context)

// 1. Descobrir dispositivos
fc.transport.startScan()
// colete fc.transport.discoveredDevices, então:
fc.transport.connect(address)

// 2. Já conectado — enviar mensagens
fc.client.sendConfig(configParams)        // configura a torneira (1ª mensagem)
fc.client.sendInitServing(servingParams)  // libera o serviço (no pagamento)
fc.client.managerStart()                  // modo gerente
fc.client.managerStop()

// 3. Ouvir o dispositivo
fc.client.incomingMessages.collect { msg ->
    // STATUS:IDLE | SERVING:{vol},{vazão} | FINISH_SERVING:{vol}
}
```

`sendConfig` / `sendInitServing` / `managerStart` / `managerStop` retornam `SendResult`:
`Success` · `NotConnected` · `InvalidPayload(field)` · `TransportError(reason)`.

## Regras gerais do protocolo

- Codificação **UTF-8**; mensagens de **saída** terminam em **`&`**.
- Campos separados por vírgula `,`, sem espaços.
- Mensagens de **entrada** chegam como strings cruas via notificação BLE (CCCD).
- Transporte: MTU negociado 512, fragmentação em chunks de `MTU − 3`, fila de escrita com
  retentativas (até 10 / backoff 300 ms).
- Antes de enviar, valida-se a conexão; se desconectado, retorna `NotConnected`.

## Ciclo de vida de uma sessão

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
```

## Origem dos valores

`CONFIG` e `INIT_SERVING` derivam da mesma chamada REST:

```
GET {baseUrl}/devices/info/{tapId}   →   TapInfoResponse.Open
```

A `baseUrl` vem do campo `url` do JSON do QRCode de configuração lido pelo dispositivo.
Detalhes do mapeamento JSON → mensagem estão em cada documento.

## Tabela-resumo das mensagens

| Mensagem | Direção | Parâmetros | Origem |
|----------|---------|------------|--------|
| `CONFIG:...&` | saída | 9 campos | `GET /devices/info/{tapId}` |
| `INIT_SERVING:...&` | saída | `liquid, foam, foamDecayMinutes, foamMsAfterDecay` | mesmo endpoint |
| `MANAGER_START&` | saída | — | comando fixo |
| `MANAGER_STOP&` | saída | — | comando fixo |
| `STATUS:IDLE` | entrada | — | firmware |
| `SERVING:{vol},{vazão},...&` | entrada | volume, vazão | firmware |
| `FINISH_SERVING:{vol},...&` | entrada | volume final | firmware |
