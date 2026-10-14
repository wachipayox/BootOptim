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
sin nuevos errores de BootOptim/Mixin y produjo:

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

## Causa raíz de que no baje el tiempo de pared

La inspección del fork confirma que `preload_cooperative_wait` cambia sólo la
política de observación, no el trabajo que determina cuándo queda listo el
recurso:

1. `ResourcePreLoader.preLoadAll` recorre la lista plana de recursos y
   `preLoadCubicPanorama` recorre cada cara de cada panorama en orden.
2. `ResourceSupplier.get()` registra la textura local. Para las panorámicas,
   `LocalTexturePanoramaRenderer` construye suppliers con rutas `LOCAL` y
   `PngTexture.local()` delega en un hilo nuevo; ese hilo ejecuta
   `NativeImage.read` y publica los flags `volatile loadingCompleted`/
   `loadingFailed`.
3. El hilo Render sólo consulta esos flags. El busy-spin consume CPU, pero no
   acelera la lectura/decodificación ni inicia el siguiente panorama. Aparcar
   ese hilo reduce CPU del propietario —578 ms frente a 18.234 s observados—,
   pero no elimina el camino crítico de I/O/decodificación y la suma de los
   lotes de panoramas.
4. La subida/registro OpenGL ocurre más tarde en `getResourceLocation()` en el
   hilo de Minecraft; la espera no la solapa ni la mueve. Tampoco se altera la
   barrera posterior de recarga que decide cuándo aparece el menú.

Por tanto, el resultado no es paradójico: se ha demostrado una reducción de
CPU, no una reducción del trabajo ni del camino crítico. La implementación
actual incluso sigue siendo una espera por sondeo (sin `unpark` al completar),
por lo que una pausa de 100 µs puede añadir latencia de despertar —normalmente
pequeña frente a la lectura— sin cambiar la planificación de productores. El
`+2.302 s` de TTMM en el 3×3 hosted no debe atribuirse todo a esa pausa: el par
físico limpio quedó en `+314 ms` de precarga y el resto de la diferencia de
TTMM ya estaba antes del entrypoint; el efecto observado es compatible con
varianza y con una política que no toca el cuello de botella.

## Arreglo que sí cambiaría la premisa

El siguiente experimento, si se reabre, debe ser arquitectónico y separado de
esta opción: tomar una instantánea fail-open de los panoramas/slideshows
seleccionados, lanzar todos sus suppliers antes de empezar a esperar y usar un
barrier de finalización (preservando timeout, fallos y la posterior fase de
registro en el hilo de Minecraft). Eso puede convertir la suma de lotes en el
máximo de los productores, pero sólo es seguro si la instantánea demuestra que
las cargas son `LOCAL`/decode puro y no contienen audio, vídeo, MCEF ni
callbacks. Debe medirse primero en hosted con trazas por recurso de
`supplier.get` → `loadingCompleted` → retorno del preloader → fin de recarga;
sin esa prueba no se debe activar ni repetir una tanda cara del portátil.

La opción cooperativa queda, por ahora, como una herramienta de ahorro de CPU
opt-in y no como una optimización de TTMM.

## Qué significa una API segura `select → prepare → commit`

No me refería a añadir otra espera con una firma distinta, sino a separar
explícitamente tres responsabilidades que hoy están mezcladas en el listener
de recarga:

- **select**: en el hilo seguro de FancyMenu, decidir y congelar una lista
  inmutable de fuentes del layout inicial; si la selección falla, devolver
  `null` y conservar la lista completa original.
- **prepare**: fuera del hilo de render, operar sólo sobre esa instantánea y
  sobre recursos demostrados como `LOCAL`/PNG: abrir y decodificar los bytes,
  sin tocar registros de Minecraft, GL, pantallas, callbacks de mods ni estado
  global mutable.
- **commit**: volver al hilo de Minecraft para publicar los resultados,
  registrar las texturas y aplicar la selección aleatoria ya decidida. Si una
  preparación falla, se descarta ese resultado y se ejecuta el camino stock.

La API importa porque evita que un mixin tenga que descubrir campos privados y
llamar `get()` desde el hilo equivocado. El fork ya tiene un `InitialResourcePreloadPlan`
con selección y `commit()` para los picks; lo que falta para adelantar tiempo
es una interfaz equivalente para el trabajo de recursos, con invariantes
explícitas de hilos, invalidación en cada reload y fail-open. Sin esa frontera,
"paralelizar" puede adelantar una mutación de caché o un registro OpenGL y
convertir una mejora de arranque en un crash o en texturas inconsistentes.

## Alcance respecto al perfil final

El perfil final ya lleva activa la opción del fork
`preload_only_initial_layout_resources`. En `active-layout-006` FancyMenu
registró `kept 1 / skipped 21` y terminó la precarga seleccionada en unos
3,7 s, frente a los 46,6 s del control de lista completa. Ese flujo llama a
la sobrecarga `preLoadAll(long, selectedSources)`; el mixin histórico de
BootOptim que sólo conoce `preLoadAll(long)` no interviene allí. Por eso las
130 esperas del probe cooperativo pertenecen a la carga completa histórica,
no al workload final que el usuario ha aceptado. El candidato cooperativo no
sólo no ganó tiempo: en la configuración final su superficie de trabajo ya ha
sido eliminada por el filtro de layout activo.
