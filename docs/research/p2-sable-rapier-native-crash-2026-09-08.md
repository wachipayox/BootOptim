# P2: crash nativo Sable/Rapier durante entrada al mundo — 2026-09-08

## Estado

**DIAGNÓSTICO FÍSICO — Sable 2.0.4 separó el primer fallo; queda un fallo
independiente de OpenAL en el portátil.**

La pasada interactiva `interactive-world-20260908` no es una medición válida de
arranque: el runner transaccional declaró `invalid` porque no detectó Java en su
ventana de 90 s, aunque Prism terminó lanzando el juego y éste permaneció vivo
unos nueve minutos. Tras recoger la evidencia, la transacción fue recuperada y
el portátil quedó restaurado al wrapper original.

## Configuración efectiva

- BootOptim candidato durante la pasada: integración `fa6df8bc`, JAR SHA
  `9DB0C42F0435A00619616C37FB74759224527D51C7BC444BB12EC25518B198F0`.
- Wrapper restaurado: `63BC9C6BDD91725D1687FC9397F1283BFF360D3205EC9DFA99C44D30BFD0707D`.
- Java Oracle 21.0.9.
- Para la pasada de aislamiento, `ALSOFT_DRIVERS=null` se inyectó en el
  entorno efectivo de `javaw.exe`; el `instance.cfg` por sí solo no bastó.
- `mcefFirstConsumerDefer=true`, `exitOnTitle=false` y C2ME scheduling
  desactivado para la prueba interactiva.

### Prueba de propagación de audio (08-09-2026)

La primera tentativa sólo añadió `Env` al `instance.cfg`; la inspección PEB del
`javaw.exe` no encontró ninguna variable `ALSOFT_*`, por lo que esa tentativa
no demostró nada sobre el backend. Se descartó y se recuperó la transacción.

En `audio-null-env2-20260908` se inyectaron temporalmente las variables en el
proceso PowerShell que Prism usa para arrancar Java. Antes del menú, el entorno
real de `javaw.exe` contenía:

```text
ALSOFT_DRIVERS=null
ALSOFT_LOGLEVEL=3
ALSOFT_LOGFILE=C:\BootOptimBench\state\openal-runner.log
```

El log nativo confirmó, todavía antes de entrar al mundo, `Initialized backend
"null"` y `Added "null" for playback`; por tanto el juego estaba usando
OpenAL Soft sin dispositivo de salida (`No Output`). Esto valida el mecanismo
de aislamiento, pero no constituye una solución para usuarios finales porque
elimina el audio. La prueba de entrar al mundo debe decidir si el cierre
persistente es realmente independiente de OpenAL.

Resultado comunicado por el usuario: el cliente volvió a cerrarse incluso con
ese backend nulo. No apareció un WER nuevo ni `hs_err` asociado a esta pasada,
y el runner había quedado inválido por su antigua ventana de detección de Java
(90 s), así que no se puede atribuir un código de salida automático al cierre.
La conclusión operativa es suficiente: `ALSOFT_DRIVERS=null` no evita el fallo
observable y no merece otra campaña física de audio en este portátil.

El portátil se deja restaurado al wrapper/config originales. Las pruebas de
entrada al mundo, WebDisplays y compatibilidad de gameplay continúan en el PC
bueno; el portátil queda reservado para métricas de arranque y diagnósticos que
no requieran crear un mundo.

## Evidencia

`hs_err_pid1784.log` registra:

- `EXCEPTION_UNCAUGHT_CXX_EXCEPTION (0xe06d7363)` fuera de la VM;
- hilo actual `Server thread`;
- frame Java nativo `dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.initialize`;
- cadena `RapierPhysicsPipeline.init → SubLevelPhysicsSystem.initialize →
  Sable.defaultSubLevelContainerInitializer → ServerLevel.<init> →
  IntegratedServer.initServer`;
- DLL nativa cargada previamente:
  `.minecraft/.sable/natives/sable_rapier_x86_64_windows.dll`;
- el log alcanza `Starting integrated minecraft server` y la inicialización de
  la física Sable antes del cierre visual, por lo que no prueba un mundo ya
  estabilizado.

Los eventos WER 1000/1001 de la misma sesión/PID vuelven a nombrar
`OpenAL.dll` 1.23.1.0, offset `0xA2B05`, `0xc0000409`. Esa atribución entra en
conflicto con el stack de `hs_err`, que sitúa el C++ exception en la ruta
Sable/Rapier. No se debe escoger OpenAL como causa única hasta explicar la
discrepancia; la evidencia fuerte nueva es que la excepción Java→nativa ocurre
en Sable/Rapier durante la creación del `ServerLevel`.

## Decisión y siguiente gate

La evidencia pública del propio proyecto Sable identifica una causa que encaja
con este stack: en Sable 2.0.3 el logger Rust (`fern`) puede hacer `panic` al
escribir en un stdout cuyo pipe ya fue cerrado por el launcher. El panic cruza
JNI durante `Rapier3D.initialize` y termina la JVM como
`EXCEPTION_UNCAUGHT_CXX_EXCEPTION`; el evento WER que etiqueta `OpenAL.dll` no
es suficiente para preferir audio frente a este camino reproducible.

La corrección oficial está en Sable 2.0.4 (cambios `Handle broken stdout pipe
better`, PR upstream #1448/#1461): el logger ignora `BrokenPipe` y la entrada
nativa está protegida frente a panic. Se ha preparado una pasada interactiva
aislada con Sable 2.0.4, manteniendo el mismo BootOptim y Java 21; el JAR 2.0.3
se conserva fuera de `mods` para poder revertir sin dejar duplicados.

No cambiar Java, DLLs ni audio como solución permanente. Si Sable 2.0.4 permite
entrar y permanecer en el mundo, la acción recomendada es actualizar el pack a
esa versión y retirar el bypass de audio; si falla, conservar el nuevo `hs_err`
y separar la hipótesis residual (otro módulo nativo o incompatibilidad del pack)
antes de tocar BootOptim. Esta pasada no se usará para TTMM.

Referencias upstream: issue [ryanhcode/sable#1269](https://github.com/ryanhcode/sable/issues/1269),
fix [PR #1448](https://github.com/ryanhcode/sable/pull/1448) y cambio de release
[#1461](https://github.com/ryanhcode/sable/pull/1461).
