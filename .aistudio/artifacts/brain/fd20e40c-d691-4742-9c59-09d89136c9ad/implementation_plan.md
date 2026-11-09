# Clawd Bridge — Media Sync Service: Архитектура и Реализация

Проект полностью реализован в строгом соответствии с ТЗ v1.2, ТЗ v1.3 и ТЗ на устранение потери связки `NotificationListenerService` при закрытии приложения (Авто-Rebind и Foreground Service).

---

## 1. Решённые задачи и архитектурные решения

### A. Устранение потери связки при закрытии/смахивании приложения (Auto-Rebind & Foreground Service)
- **`MediaBridgeForegroundService`**:
  - Запущен постоянный Foreground Service с типом `dataSync` (`FOREGROUND_SERVICE_DATA_SYNC`).
  - Уведомление в тихом канале `clawd_bridge_foreground_channel` (`IMPORTANCE_LOW`) отображает статус моста.
  - Возвращает `START_STICKY`, в манифесте указано `android:stopWithTask="false"`.
  - При закрытии приложения защищён OOM-рейтингом Android (не выгружается агрессивными таск-киллерами MIUI/OneUI/HyperOS).
  - Встроен Watchdog-мониторинг (каждые 10 секунд проверяет наличие IPC-связки и при обнаружении «зависшей» сессии автоматически инициирует безопасный ребинд).
- **`NotificationRebindHelper`**:
  - `rebindService()`: вызывает штатный `NotificationListenerService.requestRebind()` для Android 7.0+ (API 24+).
  - `forceRebindService()`: универсальный трюк с переключением состояния компонента через `PackageManager`:
    `COMPONENT_ENABLED_STATE_DISABLED` $\to$ `COMPONENT_ENABLED_STATE_ENABLED` с флагом `DONT_KILL_APP`.
    Это сбрасывает устаревший Binder-токен в системном `NotificationManagerService` и заставляет систему переподключить службу без необходимости пользователю вручную переключать тумблер в настройках ОС.
  - Кнопка **«Сбросить и переподключить службу»** в интерфейсе `MainActivity` для мгновенной ручной переинициализации связки.

### B. Решение проблемы Cleartext HTTP (Android API 34+)
- Создан файл конфигурации безопасности сети `res/xml/network_security_config.xml`:
  разрешён незашифрованный HTTP исключительно для локального сетевого интерфейса `127.0.0.1` и `localhost`.
- В манифесте подключён атрибут `android:networkSecurityConfig="@xml/network_security_config"`.
- Диагностический блок в приложении динамически отображает реальный HTTP статус-код и тело ответа либо текст ошибки подключения (без зашитого в коде «200 OK»).

### C. Точный расчёт позиции и заморозка на паузе (Монотонное время)
- Расчёт смещения позиции воспроизведения выполняется строго по `SystemClock.elapsedRealtime()`.
- Эпохальное время `System.currentTimeMillis()` используется только для поля `timestamp` в JSON.
- Дельта времени прибавляется **только при `state.state == PlaybackState.STATE_PLAYING`**. На паузе позиция остаётся замороженной.
- Значение позиции ограничивается диапазоном `[0, durationMs]`.
- При `state.position == PLAYBACK_POSITION_UNKNOWN` (-1) возвращается `position_ms: 0` и `"position_known": false`.

### D. Локальный HTTP-сервер и бэкап в Downloads
- `LocalHttpServer`: нативный HTTP-сервер на базе стандартного Java `ServerSocket`, привязанный к `127.0.0.1:44444`.
- Эндпоинт `GET /now-playing` мгновенно отдаёт JSON в памяти без блокировок.
- `BackupFileWriter`: атомарная запись файла `Download/ClaudePet/sync.json` с гарантированным ограничением размера до 512 байт.
- Сгенерирован готовый файл `ClawdBridge.apk` в корневом каталоге.

---

## 2. Проверка и тесты
1. `compile_applet` — успешно собран без ошибок и предупреждений.
2. `gradle :app:testDebugUnitTest` — все модульные тесты пройдены:
   - Формат JSON при воспроизведении и паузе.
   - Экранирование спецсимволов.
   - Ограничение размера файла бэкапа (до 512 байт).
3. `gradle :app:assembleDebug` — создан обновлённый APK `ClawdBridge.apk` (~22.9 MB).
