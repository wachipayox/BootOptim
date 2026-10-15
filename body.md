## Qué es

Candidato (borrador) para BootOptim, portado de los experimentos privados de RRLS: responder las sondas `getResource` de **EMF** para `.jem`/`.properties` ausentes a partir del listado completo que EMF ya hace (`emf/cem`, `optifine/cem`), en vez de recorrer toda la pila de packs en cada una. Detalle, evidencia y criterios en `docs/research/emf-existence-index-2026-10-15.md`.

- Premisa nueva respecto al registro: el cierre de `EntityRenderDispatcher` (#94/#95/#190/#194, #96) rechaza **mover, diferir o paralelizar**; esto no mueve ni reordena nada, solo quita consultas redundantes dentro del bucle de proveedores (slot serial de ~2,1 s hospedado).
- Evidencia heredada (no de BootOptim): en el PC rápido, ~5100 consultas ausentes costaban 173–191 ms exclusivos y con el índice 3–5 ms (3799 evitadas). No es un A/B emparejado ni mide `main_menu`.
- Sin puerta por versión de EMF. Se desactiva solo si cambia el código: la inyección no encuentra su objetivo (`CompatInjectionCheck`), el predicado del listado no deja pasar todo, un pack no es estático, o la guarda diferencial (primeras 64 respuestas y luego 1 de cada 64 contrastadas con `getResource`) ve un desacuerdo. Avisos `[WBOMBO-COMPAT]` ACTIVE/FALLBACK.
- Interruptor: `-Dboot_optim.emfExistenceIndex=false`.

## Qué mirar en el CI

1. `[WBOMBO-COMPAT] ACTIVE ... mechanism=emf-existence-index` y la línea `BootOptim EMF existence index` con contadores y estimación de pared evitada. Si el pack Beta actual ya no trae EMF o hay un proveedor no soportado, la premisa se cierra como no aplicable.
2. 0 FALLBACK nuevos.
3. A/B: segmento primero (`EntityRenderDispatcher`), después `main_menu`. Un efecto de ~0,15–0,2 s queda por debajo del ruido de runner hospedado; con ACTIVE y efecto de segmento coherente, el siguiente paso es el portátil.

[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-fixture: latest
exact-pack-candidate-jvm-arg: -Dboot_optim.emfExistenceIndex=true
exact-pack-control-jvm-arg: -Dboot_optim.emfExistenceIndex=false

## Estado

- Compilación y A/B hospedado: pendientes (no se compiló en local por indicación del usuario).
- Frentes relacionados, **no implementados**: recorrido de caracteres del parser de expresiones de EMF (−164/−177 ms exclusivos en el mismo ámbito), texto de animación preparado de EMF (−109 ms en `jem-prepare`), memo de formas rotadas de CreateFood en el listener de formas de MoreCulling.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
