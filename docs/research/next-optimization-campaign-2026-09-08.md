# Próxima campaña de optimizaciones — 2026-09-08

## Objetivo prioritario

Una vez clasificado y corregido el cierre del portátil en la prueba interactiva
actual, la prioridad principal del proyecto será obtener **varios puntos de
coste reales y separables** del arranque/reload y enviar cada punto a un agente distinto para
desarrollar una candidata independiente. No se considerará suficiente reducir
conteos, CPU inclusiva o un microphase aislado: cada candidata debe demostrar
una relación con la ruta crítica hasta el menú.

El replay real de clases Minecraft/NeoForge de PR #183 (Agente 51) ya está
disponible como primera herramienta de esa campaña: permite explorar
deserialización de `BlockModel`, resolución de padres, materiales y lowering sin
arrancar el cliente completo. Tras cerrar P2, este replay es el siguiente punto
obligatorio antes de pedir nuevas runs físicas; sus límites pre-sprite/pre-bake
deben mantenerse explícitos.

## Orden de trabajo

1. Clasificar el cierre del portátil sin confundir `joined the game`,
   `exitCode=0` o una salida del controller con un frame estable del mundo.
2. Usar PR #183 para medir y falsificar hipótesis de clase en el dominio
   `BlockModel`/parents/materials, y capturar además una traza de clase/tarea
   suficientemente fiel para localizar otros costes todavía no cubiertos por
   las optimizaciones integradas o los no-go históricos.
3. Separar esos costes en frentes no solapados y asignarlos a agentes con la
   plantilla estricta de 25 minutos.
4. Exigir replay o microbenchmark primero, exact-pack smoke/A-B después y
   portátil sólo cuando exista una mejora crítica coherente.

## Familias candidatas que deben salir de la traza

- enumeración/consulta de `FilePackResources` que quede después del snapshot de
  #142, sólo si se mide el tuple exacto de consultas y su coste residual;
- deserialización de `ExtendedBlockModel` y resolución de padres, con digest
  semántico, sin repetir #36/#66 ni introducir una caché persistente prematura;
- operaciones de `ModelManager`/`ModelBakery` que aparezcan como barrera real y
  no estén cubiertas por #55, #66, #170/#171 o #177;
- listeners de resource reload de mods concretos cuyo coste exclusivo y orden
  de callbacks estén demostrados;
- discovery/bootstrap o lectura de archivos sólo cuando la traza confirme una
  frontera crítica que no esté ya cubierta por el cache de metadatos;
- scheduling adaptativo únicamente con una premisa distinta de la mutación
  ModernFix 3→2 cerrada en #180.

Cada punto debe producir una hipótesis, una implementación mínima fail-open o
un cierre razonado. Las tres últimas líneas de agentes (#180, #182, #181) son
no-go específicos, no una razón para abandonar esta campaña.

## Gate de promoción

No enviar una candidata al portátil por ganar en el replay aproximado. Requiere
equivalencia/compatibilidad, cero errores de inicio, una mejora coherente de
TTMM o de una barrera crítica y documentación de qué parte de la métrica es
CPU, wall inclusivo o ruta crítica.
