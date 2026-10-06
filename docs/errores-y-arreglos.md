# Errores que ha tenido esto, y cómo se arreglaron

Registro de los defectos reales encontrados durante el desarrollo. No es una
lista de tareas: es lo que se rompió, por qué, y qué se hizo. Está aquí para que
quien venga después —persona o máquina— no vuelva a tropezar en lo mismo.

Ordenado por lo que enseña, no por fecha.

La versión cronológica —qué se hizo cada día en los dos repositorios, con el
hash de cada commit— está en `docs/bitacora.md` del repositorio de la web
(privado). Este documento es la otra mitad: los mismos fallos, agrupados por lo
que hay que aprender de ellos. Es el que conviene leer antes de escribir código
nuevo aquí.

---

## El fallo que más veces ha aparecido

**La app deja de funcionar y sigue diciendo que funciona.**

Salió **seis veces**, con formas distintas. Es el modo de fallo de este
proyecto, y merece mirarse primero en cualquier código nuevo.

| Dónde | Qué pasaba | Arreglo |
|---|---|---|
| Arranque de la pantalla de bloqueo | Android descarta en silencio el arranque de una Activity desde segundo plano sin permiso de superposición. El servicio marcaba "ya la he mostrado" al *intentarlo*, no al conseguirlo | Contador de reintentos; si la app bloqueada sigue delante tres vueltas después, se reintenta y el latido lo dice |
| Latido de la notificación | Texto fijo, idéntico siempre. Un servicio atascado se veía igual que uno vivo | Lleva la hora de la última comprobación |
| Permiso de uso revocado | MIUI lo revoca y las consultas devuelven vacío **sin lanzar excepción**. Nada se bloqueaba, nada fallaba | El latido comprueba el permiso y lo dice |
| Sesión de Supabase muerta | El reenganche dejaba de avisar para siempre; solo se veía abriendo Ajustes por casualidad | Notificación única al perderla, y la pantalla ofrece reconectar |
| Asistente de permisos | El refresco se disparaba al tocar el botón, antes de abrir los ajustes de Android. La tarjeta seguía diciendo "Falta" para un permiso recién concedido | Contador que sube al volver a primer plano |
| Sincronización de uso | `ultimaSyncOkMs` se escribía y no lo leía nadie. Si se rompía, el coach razonaría con números de hace semanas | Sale en Ajustes junto al latido |

**Lección**: cada vez que el código detecte que algo no funciona, hay que
preguntarse *"¿y esto quién lo ve?"*. Si la respuesta es nadie, falta la mitad.

---

## Errores que habrían hecho la app inútil

### La lista de apps salía vacía
Desde Android 11 una app solo ve las demás si lo pide. El manifiesto se escribió
en una tarea y el selector de apps en otra, con horas de diferencia, y nadie
conectó `targetSdk 34` con la visibilidad de paquetes. Habría abierto una lista
sin nada: imposible elegir qué limitar.

**Arreglo**: `QUERY_ALL_PACKAGES`. Es un permiso llamativo, pero sin él no hay app.

### Abrir la app la mataba
El servicio se declara en primer plano al crearse, pero cada vez que se abría
Cerrojo se le volvía a dar la orden de arranque, y esa orden trae un plazo que
nadie cumplía. Android mata el proceso ~10 s después.

O sea: **la única forma manual de revivir el servicio era lo que lo mataba**.

**Arreglo**: entrar en primer plano también desde `onStartCommand`.

### El reloj no corría casi nunca
`ACTIVITY_RESUMED` se emite **una sola vez** al entrar en una app, no se repite.
El código miraba los últimos 10 segundos de eventos, así que quien llevara más
de 10 s dentro de una app "no estaba en ninguna parte" y el reloj se paraba.

Habría contado medio minuto de dos horas en Instagram.

**Arreglo**: arrastrar el último paquete conocido entre consultas.

### El permiso de uso se daba por no comprobable
Un repaso concluyó —mal— que el estado `MODE_DEFAULT` significaba "no se puede
saber". Es el estado normal de *"nunca concedido"*. Con ese cambio, el botón del
asistente se habilitaba sin el permiso, nada se bloqueaba nunca, y el latido
decía "vigilando" los tres días.

**Arreglo**: en vez de interpretar el código, **preguntar al sistema por datos
de uso reales**. Si vienen, el permiso está.

**Lección**: cuando un valor es ambiguo, medir en vez de deducir.

---

## Errores de aritmética que los tests no habrían pillado

### Un tope inalcanzable
La spec fijaba el enfriamiento máximo en 90 minutos. Con la sesión topada en 20
y la fórmula `sesión × 4`, el máximo real es **80**. El tope de 90 era código
muerto y el test que lo esperaba, imposible.

### Un fuera-por-uno en el arranque del enfriamiento
Los 600 tics de una sesión de 10 minutos van de 0 a 599.000 ms, no a 600.000. El
test esperaba como si el reloj empezara *después* del último tic.

### Días de 23 y 25 horas
El fin de cada día se calculaba sumando 24 horas fijas. Los dos días del año con
cambio de hora duran otra cosa: en uno se contaba una hora dos veces, en el otro
se perdía. Sesgo silencioso en la media de la que salen los límites.

**Lección**: en los tres casos el implementador paró y preguntó en vez de
ajustar el número hasta que pasara. Fue siempre lo correcto: un test que no
cuadra con la implementación es una pregunta sobre el diseño.

---

## Errores de concurrencia

### Tormenta de hilos
El recálculo semanal comprobaba "¿ya están todos los límites?" para no repetirse,
pero eso seguía siendo falso mientras el hilo de fondo trabajaba. Cada vuelta de
un segundo lanzaba otro recálculo completo. Se disparaba justo al elegir apps
por primera vez.

**Arreglo**: bandera `@Volatile` y `try/finally`.

### Excepción en un hilo pelado
Una excepción sin capturar en un `Thread` mata el proceso entero, y
`START_STICKY` lo reinicia contra el mismo fallo: bucle de caídas. Apareció dos
veces —al construir el almacén cifrado y al decodificar JSON— y **la segunda vez
el código correcto ya existía en el mismo fichero**, con un comentario
explicándolo. No se copió.

**Arreglo**: capturar `Throwable` en el sitio donde se lanza el hilo, no delegarlo.

### Un cerrojo que no cerraba nada
Se puso `@Synchronized` para evitar que dos hilos refrescaran el token a la vez.
Pero es un cerrojo **de instancia**, y las dos llamantes creaban cada una su
propio objeto. Dos cerraduras distintas en la misma puerta.

**Arreglo**: objeto compartido a nivel de fichero.

---

## Cuando arreglar algo rompió otra cosa

La tarea del espejo de avisos necesitó **cinco rondas**. Cada arreglo generaba el
hallazgo siguiente:

1. Excepción sin capturar podía tumbar el cerrojo → se captura todo
2. Capturarlo todo escondía fallos permanentes → se avisa al usuario
3. El aviso saltaba al quedarse sin cobertura → se distingue red de rechazo
4. Distinguir con más precisión hizo determinante el código de una segunda
   petición que Supabase limita → se quita la segunda petición
5. Quitarla eliminó un escudo accidental y el motivo del fallo se quedaba rancio
   → se reinicia al empezar cada consulta

**Lección**: cuando tres arreglos seguidos generan el siguiente problema, el fallo
es estructural. En la ronda 3 se dejó de parchear y se separaron
responsabilidades: `token()` solo consigue tokens; decidir que la sesión murió y
borrar credenciales es de quien avisa.

---

## Errores de proceso

### Borrar versiones publicadas
La `v0.3` se cortó **tres veces** borrando la anterior, destruyendo esos APKs.
Es lo contrario de tener control de versiones.

**Regla**: una versión publicada no se toca. Si sale mal, se sube la siguiente.

### `git push --tags` no dispara el workflow
Comprobado. Hay que empujar la etiqueta por su referencia:
`git push origin refs/tags/vX.Y`.

### Firma nueva en cada compilación
Gradle firmaba con una clave de depuración generada al vuelo, distinta en cada
máquina y cada build. Android veía cada versión como otra app suplantando a la
instalada y la rechazaba: para actualizar había que desinstalar, perdiendo
límites, apps vigiladas y la fecha de instalación que cuenta las semanas.

**Arreglo**: clave fija en los secretos del repositorio, desde la v0.6.

### Dos guiones seguidos en un comentario XML
Rompe el compilador de recursos de Android. Pasó **tres veces**, siempre igual:
citando el nombre de una variable CSS (`--background`, `--gold`, `--radius`)
dentro de un comentario. La tercera fue justo después de escribir este
documento.

**Regla**: el nombre de una variable CSS no se escribe nunca dentro de un
comentario de XML de Android. Se nombra sin los guiones.

### `continue` dentro de una lambda
Kotlin lo trata como función experimental. Pasó **dos veces** en este repo.

---

### "Seguidos" que no eran seguidos (v1.4)

**Qué se rompía**: Ajustes decía "puedes usarla 20 min seguidos", pero el
reloj de sesión solo volvía a cero al terminar un descanso. Sumaba todos los
ratos del día: tres ratos sueltos de 7 minutos bloqueaban 80 minutos sin haber
estado nunca 20 seguidos. El usuario lo notó como "me bloquea antes de lo que
debería" y no tenía forma de comprobarlo, porque la pantalla de bloqueo solo
decía "Toca descansar. Vuelve luego."

**Arreglo**: salir de la app 5 minutos (`PAUSA_QUE_CIERRA_SESION_MS`, elegido
por el usuario) cierra la sesión. Las salidas cortas no, para que entrar y
salir no sirva de trampa. El desbloqueo con fricción pone a cero la marca de
último uso: si no, desbloquear tras 5 minutos de descanso reiniciaba la sesión
y daba la sesión entera en vez de los 5 minutos. La pantalla de bloqueo dice
ahora el motivo, a qué hora se puede volver y cuánto queda del día.

### Texto negro sobre fondo negro (v1.5)

**Qué se rompía**: en Ajustes no se veían los nombres de las apps. `Text` sin
color explícito toma `LocalContentColor`, y ese valor no lo pone
`MaterialTheme`: lo pone `Surface`. Las pantallas de Seal van sobre un `Box`
con el fondo pintado a mano, así que todo texto sin color salía **negro**,
invisible sobre el negro del fondo.

**Arreglo**: `TemaDeSeal` provee `LocalContentColor` con el color de texto de
la web. Una vez, en la raíz, para todas las pantallas.

### Sesiones que se esquivaban solas (v1.6)

**Qué se rompía**: con sesiones de "X min seguidos" más la pausa de 5 min de
la v1.4, ratos sueltos de 5 minutos no bloqueaban nunca: solo contaban para el
tope del día (150 min en Instagram). Y tras "Desbloquear igualmente" en un
descanso, dejar la app 5 minutos cerraba la sesión y daba otra entera: el
descanso desaparecía. El usuario lo vivió como "me pone que me lo bloquea y ni
una hora, se desengancha de repente".

**Arreglo**: una sola regla, el tope del día. Todo minuto con la app delante
suma, en los ratos que sea, y al llegar se bloquea hasta el día siguiente. Sin
sesiones, descansos ni pausas. El selector de tope va de 5 en 5 hasta 1 h para
poder poner 20 min. Y el WebView tiene ahora `WebChromeClient`: sin él no
mostraba los `confirm()` de la web y el botón de borrar tareas no hacía nada.

### El aviso de "en 10 min" llegaba a falta de 6 (v1.7)

**Qué se rompía**: el servidor apuntó el aviso previo a las 18:50:01 para una
tarea de las 19:00, pero el espejo de avisos solo leía `task_logs` cada cinco
minutos y sin push web es el único canal del móvil: llegó a las 18:54.

**Arreglo**: el espejo mira cada minuto.

### Un tope diario que no llegaba nunca (v1.8)

**Qué se rompía**: en la v1.6, sin tope elegido a mano, el tope del día salía
de la media de uso (unas 2 h en Instagram). Un día con 1 h 22 min no bloqueaba
ni una vez: el código hacía lo que decía, pero la regla no servía.

**Cambio, pedido por el usuario**: una barra de maná por app. Se llena con
cada segundo de uso, se congela fuera de la app (no baja) y, al llenarse,
bloquea el tiempo elegido dentro de un rango que sale de la media (de un tercio
a la media entera). Al acabar el bloqueo, y al empezar el día, vuelve a cero.
El tamaño de la barra lo elige él (por defecto, una sexta parte de la media).
Se ve en Ajustes y en la notificación fija. Las claves de "lo elegido a mano"
son nuevas (`barra:` y `bloqueo:`): el `tope:` de la v1.6 eran 150 min diarios y
leído como barra no habría bloqueado nunca.

### Pasada la medianoche, el espejo miraba el día equivocado (v1.9)
La web y el reenganche pasaron a un día lógico que acaba a las 05:00
(`settings.inicio_dia_minutos`): una tarea a las 00:30 es del día anterior, y lo
que sigue sin hacer baja la barra también después de medianoche. El espejo
calculaba "hoy" con la fecha del reloj, así que a las 00:30 pedía las filas del
día nuevo y no veía ni esa tarea ni los avisos que seguían llegando.

**Arreglo**: "hoy" se calcula restando el corte, que se lee de `settings` una
vez por hora y se guarda (sin red se usa el último bueno, o las 05:00).

## Errores de interpretación, no de código

### Iconos inventados en vez de usar la referencia
Se pidió un icono basado en una imagen concreta. Dos veces se entregó un dibujo
"del mismo estilo" en vez de usar la imagen. La tercera se extrajeron los
símbolos de los píxeles reales.

**Lección**: cuando alguien da una referencia, la referencia se usa, no se
interpreta.

### La cerradura que se rellenaba
El emblema del icono eran dos figuras solapadas. Con la regla de relleno
`evenOdd`, un solape vuelve a contar como relleno: donde tenía que haber hueco
salía una barra.

**Arreglo**: un solo contorno continuo.

---

## Cosas que siguen abiertas

- **El símbolo del icono** no es el que el usuario quería. Pendiente de elegir.
- **El fallback de `MODE_DEFAULT`** a `checkCallingOrSelfPermission` es
  probablemente código muerto en una ROM estándar. Es inofensivo, pero si el
  latido dijera "sin permiso" con el cerrojo funcionando, mirar ahí.
- **Para publicar hace falta que la gente pueda crearse una cuenta.** Hoy no
  existe registro: la única cuenta se creó a mano con la Admin API de Supabase
  porque esto es de un solo usuario. Antes de que lo use alguien más hay que
  añadir alta de cuenta, y decidir qué pasa con lo que hoy es global (los
  `settings`, las claves VAPID, el cron del reenganche) cuando haya más de una
  persona.
- **Nada del móvil se ha probado en un teléfono real.** Todo lo de este
  repositorio está razonado y revisado, no ejecutado.
