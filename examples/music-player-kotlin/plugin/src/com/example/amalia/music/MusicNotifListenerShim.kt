package com.example.amalia.music

import android.service.notification.NotificationListenerService

/**
 * Шим — пустой NotificationListenerService, нужный только для того,
 * чтобы Android согласился дать нам доступ к MediaSessionManager.getActiveSessions().
 *
 * Без этой записи в манифесте (или без права MEDIA_CONTENT_CONTROL)
 * MediaSessionManager бросает SecurityException.
 *
 * ВАЖНО: пользователь должен вручную выдать разрешение
 * «Доступ к уведомлениям» в Настройках → Спецвозможности.
 * Хост Амалии показывает экран с объяснением, когда плагин запрашивает
 * capability "system.notification_listener".
 *
 * Если разрешение не выдано, MusicController тихо падает обратно на
 * dispatchMediaKeyEvent — музыка всё равно управляется, просто без
 * информации о текущем треке.
 */
class MusicNotifListenerShim : NotificationListenerService()
