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
- No está instalado todavía; la instancia que está en el mundo sigue usando el
  JAR anterior.

## Gate pendiente

Después de cerrar la sesión actual, instalar el JAR del candidato y ejecutar un
par control/candidato con el mismo pack, mismo estado de configuración y
`preload_cooperative_wait=true` sólo en el candidato. Registrar TTMM, la espera
de precarga y CPU del proceso, además de comprobar paridad visual/errores de
recarga. Promover únicamente si el ahorro de CPU mueve el tiempo a menú y no
aparece deriva en la primera pantalla; si no, mantenerlo como kill-switch
opt-in para hardware muy lento.

