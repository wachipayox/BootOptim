# FancyMenu fork: espera cooperativa híbrida — 2026-09-06

**Estado: CANDIDATO OPT-IN; sin promoción.**

El portátil mostró que la precarga de FancyMenu mantiene el hilo propietario en
busy-spin durante aproximadamente 18,2 s de CPU en `variance-fixed-021`.
El experimento anterior desde BootOptim (#117) no debe repetirse como si
validara este diseño: usaba otra frontera y empeoró el TTMM hosted en un 3x3.

## Candidato

La rama privada del fork de FancyMenu
`codex/fancymenu-hybrid-wait-20260906`, commit `79a5504c5`, añade la opción
`preload_cooperative_wait`, desactivada por defecto. Cuando está activada,
`Resource.waitForLoadingCompletedOrFailed` conserva un spin inicial de 1 ms y
después usa pausas acotadas de 100 µs mediante `LockSupport`; cuando está
desactivada o la configuración no puede leerse, se ejecuta exactamente el
busy-spin original.

La política conserva el orden de los predicados (completado, fallo, plazo), el
plazo absoluto basado en `currentTimeMillis` y la semántica de interrupción
no-clearing. El `rg` del fork sólo encuentra este método en
`ResourcePreLoader`, por lo que el alcance actual es la precarga de recursos;
no se mueven cargas OpenGL, audio, vídeo ni callbacks a otro hilo.

## Validación realizada

- `:common:compileJava :neoforge:build` del fork: correcto.
- JAR construido y SHA-256 preposicionado en el portátil:
  `2D27BDE29DE21E1E3615A4BBA78DDB3BDFEF1E77746FFB8E7A981A9FDD6D905C`.
- Se instaló de forma temporal para el gate físico y después se restauró el JAR
  stock; la instancia quedó sin la opción experimental.

## Gate físico inicial

Se ejecutó un par sin reinicio, con Prism reiniciado entre variantes,
`mcefFirstConsumerDefer=false`, `exitOnTitle=true` y el mismo pack:

| variante | mod entrypoint | precarga FancyMenu | main menu | resultado |
| --- | ---: | ---: | ---: | --- |
| stock, opción ausente | 281,177 ms | 31,481.140 ms | 588,530 ms | válido |
| `79a5504c5`, opción `true` | 153,614 ms | 31,795.323 ms | 432,312 ms | válido |

La diferencia de TTMM no es atribuible al fork: el control ya llevaba 127,563
ms más al entrar en el mod, y la precarga candidata quedó 314 ms peor, dentro
del ruido observado. No hay ahorro de pared demostrado ni motivo para activar
la opción por defecto. El candidato queda **NO-GO como optimización de TTMM por
ahora**, pero no se descarta el mecanismo de reducción de CPU: falta un probe
barato que mida CPU del hilo/proceso sólo durante las 132 esperas sin introducir
otra carga significativa. El portátil fue restaurado al JAR stock SHA-256
`8E1C68F2C91AED02057209252BBE221BF3B019C4E82FB20FE35809BAC2C08DB8` y la
opción volvió a ausente.

## Probe físico de CPU (no es un A/B de pared)

Después del par anterior se ejecutó una sola tanda con el mismo candidato y el
diagnóstico opt-in de PR #113 (`boot_optim.fancymenuWaitCpuDiagnostic=true`,
artefacto SHA-256 `39620D06E82BBD9EE89EBCFF4371C270EB3CF52FA32C8DA9025EA8F7F2D14D1E`),
manteniendo `mcefFirstConsumerDefer=false` y autocierre al título. Llegó al menú
sin errores nuevos y produjo:

| métrica | candidato híbrido |
| --- | ---: |
| llamadas de espera | 130 |
| precarga FancyMenu (wall) | 36,782.066 ms |
| precarga FancyMenu (CPU del hilo propietario) | 578.125 ms |
| panorama (wall / CPU) | 30,217.910 / 406.250 ms |
| slideshow (wall / CPU) | 1,822.068 / 0 ms |
| `preload` del proceso (wall / CPU) | 36,928.307 / 21,281.250 ms |

El control físico `variance-fixed-021` había medido, con el busy-spin stock,
18,298 ms de espera y 18,234 ms de CPU del hilo propietario (16,360 / 16,282
ms sólo en panoramas). Las tandas no son comparables para TTMM —la precarga
candidata tuvo distinta caché/contención y tardó 36.8 s—, pero sí confirman la
premisa mecánica: la opción híbrida elimina el consumo de CPU del busy-spin sin
cambiar la frontera ni introducir excepciones. Esto no convierte el candidato
en una optimización de pared: la decisión sigue siendo **NO-GO por defecto** y
requiere un A/B emparejado que demuestre ahorro de tiempo crítico antes de
activarlo. El portátil quedó restaurado al JAR stock, opciones y configuración
originales.
