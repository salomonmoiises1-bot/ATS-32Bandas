# Auditoría de permisos y dependencias — ATS-32Bandas EQ314

## Cambios de esta versión
- Retirado del AndroidManifest el componente `PlaybackListenerService` y su permiso de vinculación `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`.
- Eliminado `PlaybackListenerService.kt`, porque su única lógica era observar notificaciones con `android.mediaSession`, registrar el paquete y arrancar `EqService`. No modificaba ni procesaba muestras de audio.
- Conservado `POST_NOTIFICATIONS`: es el permiso de notificaciones normales de Android 13+; no concede acceso a las notificaciones de otras aplicaciones ni es el permiso de Notification Listener. Se conserva para que el aviso del servicio en primer plano pueda mostrarse normalmente.
- Conservados los componentes separados `EqService`, `AudioSessionReceiver`, `BootCompletedReceiver` y `SJBStudioTileService`. El inicio manual desde `MainActivity`, las sesiones de efectos y el mosaico rápido no dependen de `PlaybackListenerService`.
- No se han añadido dependencias nuevas. Las dependencias declaradas en `app/build.gradle.kts` son AndroidX, Material, RecyclerView, Lifecycle, LocalBroadcastManager y Coroutines; no hay una biblioteca de Notification Listener.

## Permisos/componentes restantes revisados
- `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: usados por `EqService`, que llama a `startForeground()` y publica una notificación persistente.
- `POST_NOTIFICATIONS`: permiso para publicar notificaciones visibles; no es acceso de lectura a datos de otras aplicaciones.
- `RECEIVE_BOOT_COMPLETED`: usado por `BootCompletedReceiver` para solicitar el arranque del servicio después del inicio del dispositivo.
- `BIND_QUICK_SETTINGS_TILE`: protege el mosaico del sistema; no es un permiso para leer datos sensibles.
- `AudioSessionReceiver`: recibe los broadcasts de apertura/cierre de sesiones de efectos de audio. Se conservó porque es una vía independiente de integración de audio.

## Resultado de la auditoría estática
- Ya no hay en el código ni en el manifiesto fuente referencias a `NotificationListenerService`, `PlaybackListenerService` o `BIND_NOTIFICATION_LISTENER_SERVICE`.
- No se encontraron declaraciones explícitas en el manifiesto fuente de permisos de SMS, contactos, registro de llamadas, accesibilidad, superposición de ventanas, gestión total de archivos ni consulta de todos los paquetes.
- No se encontró una dependencia dedicada a lectura de notificaciones.

## Límites de verificación
- Este entorno no tiene `gradle` instalado y el ZIP no incluye `gradle-wrapper.jar`; por tanto, no se afirma que la compilación se haya ejecutado correctamente. El workflow de GitHub instala Gradle 8.4.
- La revisión estática del código fuente no puede garantizar que Google Play Protect acepte el APK. Play Protect puede considerar firma, reputación, comportamiento del APK o componentes/manifest fusionados. Tras compilar, hay que revisar el manifiesto fusionado y probar la instalación.
- Se conserva la lógica DSP existente sin reescribirla, para evitar introducir regresiones ajenas al problema de permisos. Esta entrega no constituye una validación matemática completa del procesamiento de audio.

## Nota adicional
- `AudioPolicyDumpParser` usa reflexión sobre `android.os.ServiceManager` (API oculta) y un `dump` del servicio de audio que normalmente requiere `android.permission.DUMP`. Solo se ejecuta en el modo por app (respaldo) y falla de forma segura (devuelve `null`). Podría ser observado por Google Play.
- Se agregó `<queries>` con tres paquetes (YouTube, Spotify, AIMP) para la detección en el modo por app; no se usa `QUERY_ALL_PACKAGES`.
- Se solicita en tiempo de ejecución `POST_NOTIFICATIONS` (Android 13+).
