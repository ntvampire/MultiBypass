# Сводка проекта MultiBypass (Актуально для v1.2.10 + Wlrus DNS)

## 1. Назначение и Архитектура

**MultiBypass** — специализированный клиент сетевой маршрутизации и обхода цензуры/замедлений для Android (API 28–34+), построенный на модели домашних роутеров (OpenWrt / Keenetic):

- **Ядро перехвата**: `hev-socks5-tunnel` транслирует IP-трафик с виртуального TUN-интерфейса в локальный SOCKS5-прокси.
- **Ядро десинхронизации**: модифицированный нативный C-движок `proxy.c` (`ByeDPI`), управляемый через JNI `native-lib.c`.
- **SmartDNS**: прямой перехват UDP 53 через туннель без промежуточных DoH-прослоек.
- **Изоляция сетей**:
  - Исключение приватных сетей (`Bypass LAN` по умолчанию) через `builder.excludeRoute()`.
  - Изоляция невыбранных приложений через `builder.allowBypass()`.
  - Виртуальный сетевой стек RFC 2544 (`198.18.0.1/32`, DNS `198.18.0.2`, MTU 1500).
  - Динамический `ConnectivityManager.NetworkCallback` (`setUnderlyingNetworks`).
- **Встроенный Telegram Proxy**: локальный прокси на порту `1443` с постоянным секретом в `SharedPreferences`.
- **Автообновление**: системный `PackageInstaller.Session` с разрешением `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

---

## 2. Текущее состояние (Версия v1.2.10)

- **Репозиторий**: `d:\Projects\MultiBypass`
- **Ветка**: `main` (полная синхронизация с `origin/main`).
- **Релизы на GitHub**: проект зафиксирован на стабильном релизе **`v1.2.10`** (все предыдущие релизы и теги удалены).
- **GitHub Pages**: каталоги списков хостятся на `https://ntvampire.github.io/MultiBypass/` (версия списков `6`).

### Каталог SmartDNS (проверены на разблокировку Google Gemini):
1. **Comss.one**: Anycast `195.133.25.16` (пинг ~15 мс)
2. **Wlrus DNS**: Anycast `138.124.254.134` / `95.85.240.254` (пинг ~16.5 мс)
3. **WebShift DNS**: Anycast `31.31.207.68` (пинг ~16 мс)
4. **DNS-AI**: Anycast `45.151.102.219` (пинг ~17 мс)
5. **NextDNS**: поддержка кастомного ID конфигурации

### Последние изменения:
- **v1.2.9**: Полная очистка кода от дропа QUIC (UDP 443). Убран переключатель из интерфейса. Трафик идет 100% прозрачно.
- **v1.2.10**: Внедрен `PackageInstaller.Session` + `InstallReceiver.kt`. Устранен назойливый спойлер Play Защиты «Установить без проверки» и требование отпечатка пальца при обновлении на Nothing Phone (Android 14+).
- **Списки (v6)**: Добавлен проверенный сервер **Wlrus DNS** (`138.124.254.134`, DoH `https://dns.wlrus.cfd/dns-query`, 16.5 мс, Gemini 200 OK).

---

## 3. Окружение и Правила Разработки

1. **Пользователь — инженер, а не программист**:
   - Запрещено давать инструкции по ручной вставке кода или частичные сниппеты с многоточиями.
   - Все правки агент вносит самостоятельно через инструменты редактирования файлов.
2. **Android SDK & JDK**:
   - Android SDK: `D:\Projects\AndroidSDK`
   - JDK 17: `D:\Projects\AndroidSDK\jdk-17`
   - Команда локальной сборки:
     ```powershell
     cmd.exe /c "set JAVA_HOME=D:\Projects\AndroidSDK\jdk-17&& set ANDROID_HOME=D:\Projects\AndroidSDK&& gradlew.bat assembleRelease"
     ```
3. **Сетевые особенности (ТСПУ / Провайдер РФ)**:
   - Домен `github.com` и `api.github.com` на прямом соединении пользователя подвержены сбоям TLS handshake.
   - Для push в репозиторий используется готовый скрипт с прямым IP и заголовком Host:
     ```powershell
     powershell -ExecutionPolicy Bypass -File "C:\Users\Mike\.gemini\antigravity\brain\7cd7688e-1822-4c0a-b0e5-7db5cf4ac4cc\scratch\push.ps1"
     ```
   - Запросы к GitHub API выполняются через IP `140.82.121.6` с заголовком `Host: api.github.com`.
