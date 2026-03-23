# Minecraft Client-Bridge MVP Roadmap

## Resumen

Este roadmap define el siguiente tramo del `client-bridge` de Fabric para convertir el agente actual en un MVP realmente util dentro de Minecraft survival temprano.

Hoy el bridge ya puede resolver movimiento corto, inventario basico, `dig-block`, `find-block`, `harvest-wood`, `mine-cobblestone`, chat, `place-block` y la suite inicial de crafting. El loop principal de progreso ya quedo mucho mas cerca de cerrarse, aunque todavia faltan `smelt-item`, navegacion mas segura y supervivencia basica.

El objetivo del MVP util es cerrar este loop:

`madera -> herramientas -> piedra -> horno/mesa -> comida/recursos -> seguir progresando`

## Estado Actual

- Implementado end-to-end en Fabric:
  - `get-position`
  - `list-inventory`
  - `find-item`
  - `equip-item`
  - `move-to-position`
  - `dig-block`
  - `harvest-wood`
  - `mine-cobblestone`
  - `get-block-info`
  - `find-block`
  - `place-block`
  - `list-recipes`
  - `get-recipe`
  - `can-craft`
  - `craft-item`
  - `detect-gamemode`
  - `send-chat`
- Declarado en MCP pero no implementado todavia en el mod:
  - `smelt-item`
- Limitaciones actuales:
  - no hay uso de horno
  - la navegacion sigue siendo corta y poco defensiva ante hazards
  - no existe loop de hambre/vida
  - el fallback de autocolocacion de `crafting_table` ya existe, pero sigue en validacion practica despues del ultimo ajuste

## Roadmap Priorizado

| Prioridad | Habilidad | Estado actual | API publica | Dependencias | Criterio de terminado |
| --- | --- | --- | --- | --- | --- |
| P1 | `place-block` funcional | Implementado end-to-end en Fabric | Reutilizar `place-block` | Ninguna | Puede colocar `crafting_table`, `furnace`, `torch` y bloques solidos en posiciones validas cercanas |
| P2 | Suite de crafting | Implementada end-to-end en Fabric | Reutilizar esas 4 tools | `place-block` minimo para recipes 3x3 | Puede craftear `planks`, `sticks`, `crafting_table`, `wooden_pickaxe`, `stone_pickaxe` |
| P3 | `smelt-item` | Tool declarada en TS, no implementada en Fabric | Reutilizar `smelt-item` | `place-block` | Puede usar un furnace cercano, meter input/fuel, esperar output y retirarlo |
| P4 | Navegacion segura basica | Navegacion corta y reactiva, pero aun con gestion de riesgo insuficiente | Sin tools nuevas en v1; endurecer `move-to-position`, `harvest-wood`, `mine-cobblestone` | Ninguna | Evita lava obvia, caidas peligrosas, ahogo simple y aborta con errores explicitos |
| P5 | Supervivencia basica | No existe loop de hambre/vida | Nuevas tools `get-player-status` y `eat-food` | Idealmente `smelt-item`, pero no bloqueante | Detecta hambre baja, elige comida comestible del inventario y la consume |

## Habilidad 1: `place-block`

Estado: implementada en Fabric.

### Objetivo

Desbloquear colocacion utilitaria minima para que el agente pueda usar mesa de crafteo, horno, antorchas y bloques basicos sin microgestion manual.

### Superficie MCP

Mantener la tool existente:

- `place-block { x, y, z, faceDirection? }`

### Implementacion minima

- Validar que el item actualmente equipado es colocable.
- Resolver automaticamente una cara y soporte valido si `faceDirection` no llega.
- Recolocar ligeramente al jugador si el objetivo no esta en alcance inmediato.
- Realizar el intento de colocacion desde el client thread.
- Confirmar post-accion que el bloque esperado quedo realmente colocado.
- Devolver error explicito si no hay item valido, si no existe soporte o si la posicion esta ocupada de forma no compatible.

### Casos fuera de scope

- Construccion compleja.
- Puentes, torres y escaleras automaticas.
- Colocacion multi-bloque planificada.
- Pathfinding de construccion.

### Criterio de terminado

- Coloca `crafting_table` y `furnace` de forma fiable en suelo plano cercano.
- Coloca `torch` en una cara valida.
- Devuelve error claro si no hay soporte o si no hay item adecuado en mano.

### Nota de estado

- `place-block` ya quedo operativo y probado en vivo para `crafting_table`.
- El comportamiento esperado para `furnace`, `torch` y otros bloques funcionales ya esta implementado en el bridge y queda dentro del alcance actual de smoke testing continuo.

## Habilidad 2: Suite de crafting

Estado: implementada en Fabric.

### Objetivo

Convertir recursos simples como madera y piedra en progreso real dentro del juego.

### Superficie MCP

- `list-recipes`
- `get-recipe`
- `can-craft`
- `craft-item`

### Implementacion minima

- Soportar recipes 2x2 y 3x3.
- Resolver si la receta necesita mesa de crafteo.
- Buscar una `crafting_table` cercana antes de colocar una nueva.
- Si no hay mesa colocada pero hay una en inventario y la receta la requiere, colocarla automaticamente.
- Seleccionar una recipe valida con el inventario actual.
- Ejecutar el craft y verificar que el output entro al inventario.
- Empezar por una whitelist pequena de recipes necesarias para el MVP:
  - `planks`
  - `sticks`
  - `crafting_table`
  - `wooden_pickaxe`
  - `stone_pickaxe`

### Casos fuera de scope

- Optimizacion de recipes ambiguas.
- Autocrafting multi-step largo.
- Uso de recipes de mods.
- Cadenas complejas de crafting automatico.

### Criterio de terminado

- Puede craftear `sticks`, `crafting_table`, `wooden_pickaxe` y `stone_pickaxe`.
- `can-craft` y `list-recipes` reflejan de forma consistente el inventario real.
- `craft-item` falla con errores claros cuando faltan materiales o estacion de crafteo.

### Nota de estado

- La whitelist MVP original ya quedo implementada.
- Ademas de la whitelist minima, el catalogo actual tambien soporta herramientas basicas de madera, piedra, cobre, hierro y diamante.
- La ejecucion real en juego ya fue validada para `sticks`, `crafting_table` y el set completo de herramientas basicas de madera.
- El fallback que reutiliza una `crafting_table` cercana y, si no existe, coloca una desde inventario, ya esta implementado y sigue en validacion practica despues del ultimo ajuste de autocolocacion.

## Habilidad 3: `smelt-item`

### Objetivo

Habilitar el uso basico del horno para cocinar comida y procesar materiales esenciales.

### Superficie MCP

Mantener la tool existente:

- `smelt-item`

### Implementacion minima

- Abrir un `furnace` en coordenadas dadas.
- Validar acceso, existencia del bloque correcto, input y fuel.
- Mover stacks a los slots correctos del horno.
- Esperar output con timeout.
- Recoger el output.
- Dejar el horno en un estado consistente aunque falle la operacion.
- Soportar como minimo un ciclo simple de smelting y un ciclo simple de comida cocinada.

### Casos fuera de scope

- `blast_furnace` y `smoker`.
- Colas largas de smelting.
- Orquestacion automatica de multiples hornos.
- Reabastecimiento automatico de fuel.

### Criterio de terminado

- Puede cocinar comida cruda y fundir un input simple de prueba.
- Falla de forma clara si falta fuel, input, acceso al horno o si se cumple el timeout.
- No deja corrupcion observable en el inventario al terminar o fallar.

## Habilidad 4: Navegacion segura basica

### Objetivo

Reducir muertes y bloqueos tontos para que los jobs existentes sean mucho mas fiables.

### Superficie MCP

No anadir tools nuevas en v1. Reforzar internamente:

- `move-to-position`
- `harvest-wood`
- `mine-cobblestone`

### Implementacion minima

- Detectar lava inmediata en la ruta corta y abortar antes de entrar.
- Detectar caidas por encima de un umbral simple y rechazar esa trayectoria.
- Detectar ahogo basico o permanencia en agua peligrosa durante movimiento.
- Detectar dano repetido o perdida de vida durante un movimiento o job.
- Mejorar los mensajes de error para distinguir:
  - `stuck`
  - `hazard`
  - `timeout`

### Casos fuera de scope

- Pathfinding global estilo Baritone.
- Combate y escape avanzado de mobs.
- Estrategias de terreno complejas.
- Navegacion de larga distancia en cuevas o estructuras.

### Criterio de terminado

- Rehusa trayectorias obviamente peligrosas.
- Los jobs largos no insisten ciegamente en una ruta danina.
- Los errores devueltos distinguen entre atasco, peligro y timeout.

## Habilidad 5: Supervivencia basica

### Objetivo

Mantener al agente operativo durante runs largas sin supervision constante.

### Superficie MCP

Anadir dos tools nuevas:

- `get-player-status`
- `eat-food { itemName?: string }`

### Implementacion minima

- Exponer vida, hambre, saturacion y si el jugador esta comiendo.
- Listar alimentos validos disponibles en inventario.
- Si `itemName` no llega, elegir automaticamente la mejor comida disponible.
- Equipar la comida si hace falta.
- Consumirla y verificar que la barra de hambre cambio realmente.
- Integrar la skill en jobs largos como check preventivo, aunque el control automatico completo puede entrar en una iteracion posterior.

### Casos fuera de scope

- Farming.
- Caza.
- Gestion avanzada de buffs.
- Seleccion nutricional compleja.

### Criterio de terminado

- Puede recuperar hambre usando comida del inventario.
- `get-player-status` expone datos coherentes con el HUD del jugador.
- `eat-food` falla claramente si no hay comida valida o si el estado actual no permite consumir.

## Cambios Importantes en APIs y Tipos

- Reutilizar tools ya declaradas en [client-bridge-tools.ts](C:/Users/sebas/tools/minecraft-mcp-server/src/tools/client-bridge-tools.ts) para:
  - `place-block`
  - `list-recipes`
  - `get-recipe`
  - `can-craft`
  - `craft-item`
  - `smelt-item`
- Anadir solo dos nuevas tools en este roadmap:
  - `get-player-status`
  - `eat-food`
- Dejar claro en la implementacion que "navegacion segura" es una mejora interna del bridge y no requiere una nueva tool publica en v1.

## Acceptance Tests

### `place-block`

- Colocar `crafting_table` en suelo valido cercano.
- Colocar `furnace` en suelo valido cercano.
- Colocar `torch` en una cara valida.
- Devolver error claro cuando no exista soporte o no haya item colocable.

### Crafting

- Craftear `sticks`.
- Craftear `crafting_table`.
- Craftear `wooden_pickaxe`.
- Craftear `stone_pickaxe`.
- Verificar que `list-recipes` y `can-craft` concuerdan con el inventario usado en la prueba.

### `smelt-item`

- Completar un ciclo simple de comida cocinada.
- Completar un ciclo simple de smelting de material.
- Verificar que no hay corrupcion de inventario ni desincronizacion evidente del horno.

### Navegacion segura

- Intentar una ruta con lava inmediata y confirmar aborto con motivo `hazard`.
- Intentar una ruta con caida evidente y confirmar rechazo.
- Forzar un atasco simple y comprobar que el error no se reporta como timeout generico si hay hazard o stuck identificable.

### Supervivencia

- Exponer vida y hambre con `get-player-status`.
- Consumir comida desde el inventario con `eat-food`.
- Fallar claramente si el inventario no contiene comida valida.

## Suposiciones y Defaults

- Este roadmap aplica a `fabric-companion`, no a `mineflayer`.
- El archivo vive en `fabric-companion/ROADMAP.md`.
- Idioma: espanol.
- Tono: ingenieria interna, orientado a implementacion.
- El roadmap no incluye por ahora:
  - combate
  - construccion avanzada
  - farming
  - jobs multi-etapa tipo "consigue herramientas de hierro"
- La prioridad es tener habilidades pequenas y composables antes de introducir un planner mayor o una jerarquia de jobs mas compleja.
