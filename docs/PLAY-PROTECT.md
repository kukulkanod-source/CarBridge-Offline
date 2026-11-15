# Предупреждение Google Play Protect при распространении APK

Показанный экран соответствует категории «App blocked to protect your device» в официальной документации Google:
https://developers.google.com/android/play-protect/warning-dev-guidance

В некоторых регионах Google автоматически блокирует установку APK из браузеров, мессенджеров и файловых менеджеров, если объявлены чувствительные службы доступа к уведомлениям или специальных возможностей. В текущем манифесте CarBridge есть обе службы:

- HuMediaNotificationListener / BIND_NOTIFICATION_LISTENER_SERVICE — музыка, управление MediaSession, уведомления и навигационные подсказки на ГУ. Доступ включает сам пользователь в системных настройках.
- AccessibilityTouchService / BIND_ACCESSIBILITY_SERVICE — старый запасной механизм касаний зеркала. Основной режим виртуального дисплея использует управление через локальный ADB.

Конкретное решение Google по этому APK неизвестно: экран не называет конкретную службу и не является результатом нашего аудита безопасности. Смена подписи или номера версии не гарантирует исправления. Сертификат существующих выпусков сохранён.

Путь для распространения: проверить необходимость каждой чувствительной службы и ясность согласия пользователя, документировать назначение доступа, затем направить APK и объяснение разработчикам Play Protect через официальную форму апелляции со страницы выше. Удаление доступа к уведомлениям лишит текущую сборку соответствующих возможностей; отказ только от старой Accessibility-службы тоже не гарантирует снятия блокировки. Обход защиты пользователями не является решением для публичного выпуска.

## Черновик объяснения для проверки Google (не отправлен)

CarBridge Offline is a user-controlled car head-unit companion. Its notification listener displays notifications on an alternative automotive user interface and obtains MediaSession metadata and playback controls after the user grants Android notification access. This permission is required for the music widget, notification shade and published navigation alerts. The application uses a local ADB connection explicitly prepared by the user to create and control a virtual display. The APK also declares a legacy Accessibility gesture-injection fallback; its necessity should be reviewed before public distribution. Please review the installation block for the submitted APK and advise which declared capability requires changes.

К обращению приложить именно заблокированный APK, его SHA-256, package ID com.example.carbridge.adb, версию, скриншот и описание шагов выдачи доступа. Файл ключа подписи и пароли не прикладывать.
