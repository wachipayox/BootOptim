# Triage de cinco frentes con replay real — 2026-09-08

## Objetivo

Tras cerrar el crash nativo del portátil como bloqueo operativo, se enviaron
cinco investigaciones basadas en el replay real de PR #183. El propósito era
convertir costes físicos del portátil en candidatas verificables mediante
replay, hosted exact-pack A/B y, sólo para mecanismos sensibles a hardware, una
compuerta física posterior.

La campaña no produjo una optimización de producción. Sí demostró qué premisas
eran falsas y localizó una cola crítica que merece el siguiente diagnóstico.

## Resultados

### Deserialización y pre-sizing — #186/#188 — REJECTED

El replay escalado a 4.096 raíces produjo 1.295 GB de allocation exacta, pero
las muestras `grow/resize` fueron sólo 90.07 MB de peso JFR (2.615%, 8 muestras)
y todas pertenecían a `JsonArray.add` durante la construcción del árbol Gson,
antes de que `BlockModel.Deserializer` recibiera el árbol. La colección final de
`BlockElement`/caras/texturas no es la fuente observada.

La variante runtime mantuvo digest y probes de errores, pero ahorró sólo
0.018–0.092% de allocation en el replay. Su exact-pack 3×3 tuvo una mediana
nominal de −1.296 s, pero signos [-,+,+] y variaciones simultáneas en panorama;
no existe causalidad. No se integra ni se lleva al portátil.

Esto cierra el pre-sizing, no todo el parse. Reabrir requiere un diseño distinto
(árbol/parser Gson) con equivalencia completa y ahorro exclusivo material.

### FilePackResources / ZIP — #187 — REJECTED, REOPENABLE

El replay seleccionó 22.745 recursos ganadores (9.86 MB sin comprimir) y validó
la procedencia/digest de #183. El cache de bytes tuvo **0 hits** en el pase
semántico; el 100% sólo apareció en una repetición artificial. El `strace`
confirma una ruta read/seek-heavy, pero no una segunda lectura real durante la
ruta crítica de Minecraft.

No se implementa cache ni se pide portátil. Reabrir sólo con traza exact-pack de
aperturas repetidas reales antes del join crítico, ≥~200 ms exclusivos y sin
intercambiar I/O por allocation/GC.

### Discovery/bootstrap — #185 — REJECTED, REOPENABLE

El fallback `union:` a un barrido serial de 160 JARs existe, pero el lookup del
wrapper BootOptim mediano fue 36.715 ms hosted. Incluso un factor conservador
20× sólo da ~734 ms, insuficiente para el root/FML físico de 18–21 s y no
explica los 28–34 s de bootstrap/ModernFix. El harness mantuvo el scan cache frío
(~239–240 misses), por lo que no es evidencia para reabrir #134.

Sólo una pasada física con wrapper empaquetado debe reabrirlo si `wrapper_lookup`
alcanza ≥500 ms o ≥10% del root wall. No se confunde reducción de JARs con
mejora de menú.

### Listeners/apply — #184 — DIAGNOSTIC, PRIORITY NEXT

El probe de turnos monótonos separó correctamente la cola crítica: después del
preparation gate quedaron 13.5578 s de wall exclusivo hosted. Excluyendo
FancyMenu (4.162 s ya retenido), quedan 8.921 s. `Minecraft$$Lambda` índices
25/26 suman 4.443 s y `EntityRenderDispatcher` 2.153 s. Shader Loader,
Veil, Flywheel y LevelRenderer están ligados a GL; `ClientModLoader` y
`AddLayers` atraviesan lifecycle mutable de mods.

No se mueve nada genéricamente. El siguiente gate es obtener callsites/identidad
de las lambdas 25/26 e inventariar subscribers exact-pack de
`EntityRenderersEvent.AddLayers`. Sólo un subconjunto GL-free, inmutable,
thread-safe y no observable antes del commit puede convertirse en candidata
`prepare/commit` o defer; después debe superar exact-pack A/B 3×.

## Conclusión metodológica

El replay real es válido y útil, pero debe filtrar causalidad antes de escribir
runtime. Los no-go de #186/#188, #187 y #185 son cierres específicos de sus
mecanismos, no evidencia de que el arranque sea rápido ni de que el hardware
antiguo no importe. La prioridad cambia de micro-optimizaciones por allocation,
conteos o cache hipotética a atribución de callsite sobre la cola crítica real
de #184.

## Referencias

- Replay base: [PR #183](https://github.com/wachipayox/BootOptim/pull/183).
- Allocation/pre-sizing: [PR #186](https://github.com/wachipayox/BootOptim/pull/186),
  [PR #188](https://github.com/wachipayox/BootOptim/pull/188).
- ZIP rereads: [PR #187](https://github.com/wachipayox/BootOptim/pull/187).
- Discovery: [PR #185](https://github.com/wachipayox/BootOptim/pull/185).
- Listener critical path: [PR #184](https://github.com/wachipayox/BootOptim/pull/184).
