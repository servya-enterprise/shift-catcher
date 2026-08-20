# AI Parser Contract

IA interpreta; não decide claim.

## Input

```json
{
  "text": "amanhã noite ps central 1.2 se alguém quiser",
  "messageTimestamp": "2026-08-20T10:00:00Z",
  "timezone": "America/Sao_Paulo",
  "knownLocations": ["PS Central"]
}
```

## Output

```json
{
  "isShiftOffer": true,
  "confidence": 0.96,
  "date": "2026-08-21",
  "startTime": null,
  "endTime": null,
  "durationHours": null,
  "location": "PS Central",
  "city": null,
  "amount": 1200.00,
  "currency": "BRL",
  "specialty": null,
  "notes": null,
  "ambiguousFields": ["startTime", "endTime"]
}
```

Regras:
- `null` quando não suportado;
- JSON Schema obrigatório;
- schema inválido => review;
- provider/modelo atrás de `AiShiftParserPort`.
