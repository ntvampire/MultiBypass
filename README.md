# MultiBypass for Android

<div align="center">
  <h3>Инструмент раздельной маршрутизации и обхода сетевых ограничений для Android 9+</h3>
</div>

---

## 🌟 Основные возможности

- **Группа 1: Свой DNS (Выборочный резолвинг)**:
  - Поддержка стандартного DNS (UDP/TCP IP) и зашифрованного **DNS-over-HTTPS (DoH)**.
  - Встроенные готовые пресеты: **Google, Quad9, AdGuard, Comss.one, Yandex, Xbox-DNS, DNS-AI, NextDNS** (с вводом персонального ID профиля) и ручной ввод любого DNS.
  - Выборочный список доменов и IP (OpenAI/ChatGPT, Claude, Spotify, Canva, Notion и др.).
  - Выбор отдельных приложений для резолвинга через свой DNS.

- **Группа 2: Анти-DPI (ByeByeDPI)**:
  - Встроенный нативный C-движок `ciadpi` для расщепления и модификации пакетов (fake SNI, split, disorder, fake, ttl).
  - Выбор стратегий из базы пресетов.
  - **Автоподбор стратегий**: тестирование стратегий в реальном времени против сервисов (YouTube, Discord и др.) с замером пинга и процента доступности, с возможностью применить лучшую стратегию в 1 клик.
  - Настройка списков доменов и приложений для обработки Анти-DPI.

- **Telegram WS Proxy**:
  - Встроенный локальный MTProto WebSocket-прокси на Rust (`127.0.0.1:1443`).
  - Отдельный переключатель работы.
  - Кнопка **«Подключить в Telegram»** (`tg://proxy`) для моментального добавления прокси в клиент Telegram.

- **⚡ Полная совместимость с «белыми списками» операторов (Прямой обход)**:
  - Все адреса и приложения, явно **не указанные** в первой и второй группах, используют стандартное прямое подключение устройства.
  - Невыбранные приложения изолируются на уровне ядра ОС Android (`addAllowedApplication`).

- **Встроенное автообновление**:
  - Автоматическая проверка свежих версий через GitHub Releases API и установка прямо из приложения.

- **Современный интерфейс**:
  - Разработано на **Jetpack Compose** и **Material 3** с темной темой и удобным выбором приложений.

---

## 📥 Загрузка (Скачать APK)

Свежие скомпилированные и подписанные APK доступны на странице релизов:
👉 **[Скачать MultiBypass из GitHub Releases](https://github.com/ntvampire/MultiBypass/releases/latest)**

- **`MultiBypass-arm64-v8a.apk`** — для большинства современных Android-устройств (64-bit).
- **`MultiBypass-universal.apk`** — универсальная версия для любых процессоров.
- **`MultiBypass-armeabi-v7a.apk`** — для 32-битных устройств.

---

## 🚀 Сборка проекта

Проект полностью настроен для непрерывной сборки (CI/CD) через **GitHub Actions**:
- На каждый пуш в ветку `main` автоматически собираются релизные APK (`universal`, `arm64-v8a`, `armeabi-v7a`).
- Готовые APK доступны для скачивания во вкладке **Actions -> Artifacts**.
- При создании тега версии (`v*`) автоматически формируется **GitHub Release** с прикрепленными подписанными APK.

---

## 💖 Благодарности и используемые сторонние проекты (Credits)

В проекте **MultiBypass** используются наработки и компоненты следующих открытых проектов:

- **[ByeByeDPI (ciadpi)](https://github.com/hufrea/byedpi)** (автор: [@hufrea](https://github.com/hufrea))  
  Локальный SOCKS-прокси на Си для обхода блокировок DPI (Deep Packet Inspection) с помощью фрагментации TCP-пакетов, манипуляции с TLS ClientHello (fake SNI, split, disorder) и OOB-пакетов. Исходный код C-модуля скомпилирован через CMake в `libbyedpi.so`.

- **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)** (автор: [@heiher](https://github.com/heiher))  
  Высокопроизводительный туннель TUN-to-SOCKS5, служащий мостом между Android TUN (`VpnService`) и локальным SOCKS5-прокси ByeDPI.

- **[ByeDPIAndroid](https://github.com/romanvht/ByeDPIAndroid)** (автор: [@romanvht](https://github.com/romanvht))  
  Эталонная архитектура интеграции ByeDPI и hev-socks5-tunnel в среду Android с JNI-обвязкой, послужившая основой для нативной интеграции.

- **[tgws / Telegram WS Proxy](https://github.com/coyove/tgws)** (и реализация MTProto WebSocket на Rust)  
  WebSocket/TLS-транспорт для трафика Telegram MTProto, маскирующий прокси-подключение под обычный защищённый веб-трафик и обеспечивающий устойчивость к фильтрации.

- **[OkHttp & okhttp-dnsoverhttps](https://github.com/square/okhttp)** (Square)  
  Высокопроизводительный HTTP-клиент и модуль для выполнения зашифрованных DNS-запросов (DNS-over-HTTPS / DoH) к сервисам Comss.one, AdGuard, NextDNS, Google, Quad9 и Cloudflare.

- **[Jetpack Compose & Material 3](https://developer.android.com/jetpack/compose)** (Google)  
  Декларативный UI-фреймворк для создания современного и отзывчивого интерфейса приложения с поддержкой динамических цветов и тем.

- **Базы стратегий обхода сообщества**:  
  Списки проверенных параметров ByeDPI/Zapret, оптимизированные для восстановления стабильной работы YouTube, Discord и других сервисов при различных типах блокировок ТСПУ.

---

## 📄 Лицензия

Распространяется под лицензиями GPLv3 и MIT в соответствии с лицензиями используемых компонентов (ByeByeDPI, hev-socks5-tunnel, tgws).

