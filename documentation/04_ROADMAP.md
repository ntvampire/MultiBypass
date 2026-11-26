# 4. План разработки и дорожная карта (Roadmap)

## 4.1. Текущая итерация — Переходный релиз `v3.3.3` (завершён)

> [!NOTE]
> Переходный релиз `v3.3.3` успешно опубликован на старом ключе и включает плашку-уведомление для переустановки `v4.0.0` начисто.

| Статус | Этап / Задача | Описание / Компоненты |
| :--- | :--- | :--- |
| ✅ Выполнено | Переработка иконки в строгом плоском стиле | Сплошной графитовый фон `#121417`, строгая двухцветная плоская геометрия (`#29B6F6` маршрутные дуги + `#2ECC71` щит с вырезом) без градиентов и переливов; обновлены векторные XML и все растры `mipmap-*`. |
| ✅ Выполнено | **Этап 1: Надёжность и баги** | 1) Единый список `CORE_ANTIDPI_ROOT_DOMAINS` (`AppModels.kt`).<br>2) Подключение флага `smartHostsConfig.enabled` в `SmartHostsDnsRelay`.<br>3) Подмешивание пользовательских доменов `antiDpiConfig.domains` в `ByeDpiController` и `SmartHostsDnsRelay`.<br>4) Кнопка «Применить стратегии» в `AntiDpiGroupScreen` вместо рестарта на каждую букву.<br>5) Сброс пула соединений DoH при смене сети (`evictAll()`) и корректное закрытие полуоткрытых сокетов в `Socks5Dispatcher`.<br>6) Маскировка секрета Telegram-прокси в логах, вывод динамического `$port`, запрет облачного бэкапа `multibypass_prefs.xml`. |
| ✅ Выполнено | **Этап 2: Безопасность и тесты** | 1) Проверка `responseCode` при загрузке APK в `AppUpdateManager`.<br>2) Проверка `SHA-256` списков по `domains_version.json` и санитизация резервного источника в `ListUpdateManager`.<br>3) Включение R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) и правил ProGuard для `hev.htproxy` / `Gson`.<br>4) Добавление юнит-тестов `CoreLogicTest` (`ByeDpiController`, `AppUpdateManager`, `ListUpdateManager`, общие константы). |
| ✅ Выполнено | **Этап 3.1: Поддержка миграции подписи** | Автоопределение релиза с новой подписью (`version >= 4.0.0` или тег `[NEW_SIGNATURE]`) в `AppUpdateManager`, отображение предупреждения-плашки на `MainScreen` и в `SettingsScreen` со ссылкой на GitHub Releases. |

---

## 4.2. Текущий релиз — `v4.0.0` (Новая приватная подпись)

| Статус | Задача | Описание |
| :--- | :--- | :--- |
| ✅ Выполнено | **Этап 3.2: Смена ключа подписи разработчика** | Сгенерирован новый приватный keystore (RSA 4096, вне Git в `keystore/`), секреты `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` загружены в GitHub Secrets и `local.properties`, старый `app/release.p12` удалён из репозитория, обновлён `.github/workflows/release.yml`, выпуск `v4.0.0` (`[NEW_SIGNATURE]`). |
| ✅ Выполнено | **Верификация TLS в `libtgwsproxy.so` (Rust)** | Удалён `NoVerify` в `src-rust/ws.rs`, включена проверка цепочки сертификатов и подписей по корневому хранилищу `webpki-roots` (`rustls::RootCertStore`) + добавлены юнит-тесты и автоматическая сборка `.so` через `cargo-ndk` (c флагом `-Wl,-z,max-page-size=16384`) в `.github/workflows/build.yml` и `.github/workflows/release.yml`. |

---

## 4.3. Релиз `v4.1.0` — Бэклог и финализация плана

| Статус | Задача | Описание |
| :--- | :--- | :--- |
| ✅ Выполнено | **Health-check SNI-релеев** | Параллельная TCP `:443` проверка европейских прокси IP в `scripts/update_domains.py`, сортировка по минимальной задержке и отсев недоступных узлов (с безопасным фоллбэком). |
| ✅ Выполнено | **Кнопка остановки автоподбора в уведомлении** | Action «Остановить» (`StrategyStopReceiver` + `StrategyTester.ACTION_STOP_STRATEGY_TEST`) в шторке Android с корректной отменой HTTP-вызовов и восстановлением `ByeDpiController`. |
| ✅ Выполнено | **Экспорт отчёта «Сетевого доктора»** | Включение версии приложения, типа физической сети (`Wi-Fi` / `LTE/5G`), DNS и сводки профилей в `DiagnosticsReport.toPlainText()` + кнопка «Отправить» (`Intent.ACTION_SEND`) в `DiagnosticsDialog`. |

### Архитектурные инварианты (не нарушать)
- Не пропускать российские сервисы, банки, `4pda.*`, `apis.google.com` и `suggestqueries.google.com` через SNI-релеи или десинхронизацию.
- Не добавлять `com.google.android.gms` в автовыборку приложений и держать `routeAllApps = false` по умолчанию.
- Не смешивать домены Anti-DPI и SmartHosts.
- Не удалять предыдущие релизы и теги на GitHub.
