# 2. Архитектура системы MultiBypass

## 2.1. Схема потоков данных

```text
                    [ Android VpnService (TUN 10.10.10.10) ]
                     (только приложения из routedApps, кроме LAN/Multicast)
                                        │
                            hev-socks5-tunnel (v2.19.1)
                                        │
                        Socks5Dispatcher (127.0.0.1:10800)
               ┌────────────────────────┴────────────────────────┐
               ▼                                                 ▼
      [ TCP-соединения ]                                [ UDP ASSOCIATE ]
               │                                                 │
               ▼                                     ┌───────────┴───────────┐
    ByeDpiController (:1080)                         ▼                       ▼
     (нативный libbyedpi.so)                   [ DNS :53 ]             [ Прочий UDP ]
     ├─ Группа Meta/IG (-s2 -a1)           SmartHostsDnsRelay         Прямой сокет
     └─ Группа YT/General                 ├─ Type 65 -> NODATA          (protect())
        (-s1 -f-1 -t5 -a1)                ├─ SmartHosts -> SNI IP
                                          ├─ Anti-DPI -> Clean DNS
                                          │  (8.8.8.8 / 1.1.1.1 / 9.9.9.9 / 77.88.8.8)
                                          └─ Прочее -> DNS оператора

   [ Автономный сервис TelegramProxyService (вне TUN) ]
   Telegram клиент ──▶ 127.0.0.1:1443 (libtgwsproxy.so, Rust) ──▶ Cloudflare WS over TLS
```

## 2.2. Ключевые программные модули (`app/src/main/java/com/multibypass/app`)

### Ядро и сеть (`core/`)
- `core/vpn/MultiBypassVpnService.kt` — управление TUN-интерфейсом, маршрутами (`excludeRoute` для LAN и `224.0.0.0/4`), списком `addAllowedApplication`, мониторинг сети (`NetworkCallback`) и перезапуск при смене Wi-Fi ↔ LTE.
- `core/vpn/Socks5Dispatcher.kt` — асинхронный локальный SOCKS5-сервер на порту `10800`, проксирование TCP в `ByeDpiController`, перехват UDP-дейтаграмм DNS (порт `53`) и реестр активных сокетов для мгновенного сброса при хэндовере.
- `core/byedpi/ByeDpiController.kt` — обёртка над `ByeDpiProxy` (`libbyedpi.so`), генерация раздельных файлов хостов (`domains_antidpi_meta.txt`, `domains_antidpi_general.txt`, `domains_antidpi_clean.txt`), запуск в одно- или мультигрупповом режиме (`-A`).
- `core/byedpi/PresetStrategies.kt` (`StrategyTester`) — движок автоподбора из 50 стратегий, DoH-клиент проверки, `WakeLock`, уведомление с переходом на экран тестирования (`PendingIntent` -> `MainActivity` + `EXTRA_NAVIGATE_TO`).
- `core/smarthosts/SmartHostsDnsRelay.kt` & `DnsPacketCodec.kt` — разбор/сборка DNS-пакетов (RFC 1035), подавление `TYPE_HTTPS` (`65`), локальный резолвинг `SmartHostsManager`, суффиксный матчинг `CORE_ANTIDPI_ROOT_DOMAINS` и запрос к чистым/операторским DNS через `protect()`.
- `core/smarthosts/SmartHostsManager.kt` — загрузка и иерархический поиск `smart-no-crutch.hosts` (с жёстким исключением `4pda`).
- `core/tgproxy/TelegramProxyService.kt` & `NativeTgProxy.kt` — управление Rust-демоном `libtgwsproxy.so` (порт `1443`), обновление пула CF-доменов (`cfproxy_domains.txt`), диплинк `tg://proxy` для 18+ клиентов Telegram.
- `core/watchdog/ServiceWatchdog.kt` — контроль живучести туннеля и прокси (приостанавливается на время автоподбора).
- `core/lists/ListUpdateManager.kt` — обновление списков с `ntvampire.github.io/MultiBypass` + GitHub Raw fallback + немедленная инвалидация кэшей DNS и ByeDPI.
- `core/updater/AppUpdateManager.kt` & `InstallReceiver.kt` — проверка релизов GitHub, загрузка APK и тихая/сессионная установка через `PackageInstaller`.
- `core/diagnostics/NetworkDiagnostics.kt` — комплексный «Сетевой доктор» (проверка DNS, CDN, превью YouTube, портов `1080`/`10800`/`1443`).
- `core/tiles/` — плитки быстрых настроек Android (`VpnTileService`, `TelegramProxyTileService`).

### Данные и UI (`data/`, `ui/`)
- `data/repository/SettingsRepository.kt` — хранение настроек в `SharedPreferences` (`CURRENT_DOMAINS_VERSION = 15`), автомиграции, сохранение результатов автоподбора.
- `data/model/AppModels.kt` — модели конфигураций, пресеты пакетов `GEOBLOCK_PACKAGES` и `ANTIDPI_PACKAGES`.
- `MainActivity.kt` — одноэкранный Compose-хост (`singleTop`), обработка deep-link интентов из уведомлений (`onCreate` + `onNewIntent`).
- `ui/screens/` — `MainScreen.kt`, `AntiDpiGroupScreen.kt`, `AutoStrategyScreen.kt`, `SettingsScreen.kt`.
- `ui/components/` — `AppPickerBottomSheet.kt`, `DiagnosticsDialog.kt`.

## 2.3. Нативные библиотеки и внешний контур
- **C/C++ (`app/src/main/cpp/`)**: `ciadpi` (`libbyedpi.so`), `hev-socks5-tunnel` (`libhev-socks5-tunnel.so`), JNI-обвязка.
- **Rust (`src-rust/`)**: `libtgwsproxy.so` — асинхронный MTProto-to-WebSocket прокси с балансировкой и пулом Cloudflare-узлов.
- **Автономный генератор списков (`scripts/update_domains.py` + `seeds/`)**:
  - Запускается ежедневно по расписанию в `.github/workflows/update_domains.yml`.
  - Собирает SmartHosts из первичных источников (`GeoHide EU`, `ImMALWARE`, `AstraCat`, резерв `Noktomezo`) + `seeds/seeds_geoblock.txt`.
  - Собирает Anti-DPI домены из `seeds/seeds_antidpi.txt` + `Flowseal/zapret-discord-youtube`.
  - Выкладывает готовые артефакты и `domains_version.json` в `docs/` и `app/src/main/assets/`.
