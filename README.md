<div align="center">

# Wayline

**VPN-клиент для своих серверов. Без лишних панелей и ручной настройки прокси.**

[![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white)](https://tauri.app/)
[![React](https://img.shields.io/badge/React-TypeScript-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![sing-box](https://img.shields.io/badge/Core-sing--box-5B7FFF)](https://github.com/SagerNet/sing-box)
[![Xray](https://img.shields.io/badge/Core-Xray--core-00A884)](https://github.com/XTLS/Xray-core)
[![Status](https://img.shields.io/badge/Status-Development-orange)](#текущий-статус)

</div>

Wayline — клиент с открытым исходным кодом для подключения к собственным VPN- и proxy-серверам. Проект использует **sing-box** для TUN и современных протоколов, а **Xray-core** — для совместимого локального прокси.

> **Важно:** Wayline находится в активной разработке. Сборка приложения и запуск сетевого ядра сами по себе не гарантируют, что туннель работает в любой сети. Перед повседневным использованием проверяйте соединение на своём устройстве.

---

## Возможности

- **Импорт серверов и подписок** — VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS и sing-box JSON в пределах поддержки выбранного ядра.
- **Два сетевых ядра** — sing-box и Xray-core. Путь к исполняемому файлу на desktop указывать не требуется.
- **TUN и Proxy** — системный VPN-туннель через sing-box или локальный прокси.
- **Системный прокси** — автоматическое включение и восстановление настроек Windows; поддержка GNOME Proxy Settings в Linux.
- **Маршрутизация** — домены через прокси, напрямую или в блокировку; обход локальных сетей; правила GeoSite/GeoIP.
- **DNS-over-HTTPS** — настраиваемый DoH endpoint и выбор маршрута DNS.
- **Список серверов** — фильтр по протоколу и стране, избранное и TCP-проверка задержки.
- **Подписки** — отображение даты истечения, если сервер подписки передаёт соответствующий HTTP-заголовок.
- **Резервные копии** — создание, просмотр, восстановление и удаление копий профилей и настроек.
- **Внешний вид** — темы, пользовательский фон, прозрачность списка, масштаб текста и Liquid Glass.

## Платформы

| Платформа | Режимы и особенности | Статус |
|---|---|---|
| Windows 10/11 x64 | sing-box TUN (запрос UAC), Proxy с системными настройками; Xray Proxy | В разработке, требуется проверка на реальном ПК |
| Linux x64 | sing-box TUN через pkexec, Proxy и GNOME system proxy; DEB/AppImage | В разработке, TUN зависит от polkit и окружения |
| Android ARM64 | sing-box TUN через Android VpnService, маршрутизация трафика устройства | Экспериментально; требуется проверка APK и физического устройства |

### Ограничения Android

Android-версия использует нативный VpnService и libbox для VPN-туннеля, но интерфейс пока отображается через Tauri WebView. Это **ещё не полностью нативный Compose-интерфейс**. Полноценная Android UI-реализация остаётся отдельной задачей.

### Proxy и TUN — в чём разница

- **TUN** перехватывает трафик через системный VPN-интерфейс. На Android используйте этот режим, чтобы приложениям не приходилось вручную задавать адрес прокси.
- **Proxy** поднимает локальный SOCKS/HTTP endpoint. На Windows и GNOME Wayline автоматически включает системный прокси и восстанавливает прежние настройки при отключении. На других desktop-окружениях системная интеграция может быть недоступна.
- Приложения, игнорирующие системные настройки прокси, требуют TUN или собственной настройки.

### Ядра и протоколы

| Ядро | Proxy | TUN | Особенности |
|---|---:|---:|---|
| sing-box | Да | Да | Основное ядро для TUN и современных протоколов |
| Xray-core | Да | Нет | Локальный SOCKS-прокси; не использовать для TUN |

Набор протоколов зависит от формата импорта и возможностей ядра. Hysteria2, TUIC и AnyTLS следует запускать через sing-box.

## Маршрутизация и геоданные

Wayline поддерживает пользовательские списки доменов:

- **Через прокси** — домены, которые должны идти через выбранный сервер.
- **Напрямую** — исключения из прокси.
- **Блокировать** — домены, соединения с которыми нужно отклонять.
- **Локальные сети напрямую** — частные IP-диапазоны.
- **Страна напрямую** — двухбуквенный код для GeoIP; GeoSite-теги можно отдельно назначать напрямую, через прокси или в блокировку.

Xray использует совместимые geoip.dat и geosite.dat из каталога ресурсов приложения. sing-box использует rule-set формата .srs; выбранные наборы загружаются из официальных репозиториев SagerNet и кешируются ядром. Эти форматы не взаимозаменяемы.

## Установка

### Windows

Скачайте Windows-сборку из [Releases](https://github.com/kesakuuta/wayline/releases), установите приложение и импортируйте ссылку или подписку.

Для TUN Windows может запросить права администратора через UAC. Это необходимо для создания системного сетевого интерфейса.

### Linux

Для Debian/Ubuntu используйте DEB, для других совместимых дистрибутивов — AppImage из [Releases](https://github.com/kesakuuta/wayline/releases).

Для TUN требуется pkexec и настроенный polkit authentication agent. Автоматический системный прокси сейчас рассчитан на GNOME Settings; другие окружения могут требовать отдельной интеграции.

### Android

Установите APK из [Releases](https://github.com/kesakuuta/wayline/releases), разрешите создание VPN-подключения и выберите сервер. Android использует sing-box TUN; Xray и локальный-only Proxy в Android UI не предлагаются.

## Portable ZIP-сборки

Windows и Linux ZIP-архивы собираются отдельным [GitHub Actions workflow](https://github.com/kesakuuta/wayline/actions/workflows/portable-desktop.yml). Артефакты доступны в соответствующем запуске Actions в течение 14 дней; это экспериментальные portable-пакеты, не заменяющие установщик.

## Быстрый запуск из исходников

### Desktop

Требуются Node.js 22+, Rust stable и системные зависимости Tauri 2 для вашей ОС.

    npm install
    npm test
    npm run build
    npm run tauri dev

Release-сборка:

    npm run tauri build

### Android

Требуются Java 17, Android SDK, NDK, Rust target aarch64-linux-android и Tauri CLI 2.12.4.

    npm install
    npm run build
    cargo install tauri-cli --version 2.12.4 --locked
    bash scripts/prepare-android.sh
    cargo tauri android build --apk --target aarch64

Подготовка создаёт проект Tauri в src-tauri/gen/android/ и копирует Kotlin overlay. После изменения Android overlay запустите prepare-android.sh повторно.

## Где хранятся данные

- Профили и настройки интерфейса — в локальном хранилище приложения.
- Резервные копии — в каталоге backups внутри системной папки данных Wayline. Полный путь показывается в **Настройки → Резервные копии**.
- Диагностические логи остаются локально.

**Резервные копии не зашифрованы и могут содержать UUID, пароли и другие секреты конфигураций.** Не отправляйте их посторонним и не публикуйте диагностические логи без проверки.

Проверка задержки сервера использует TCP connect timing, а не ICMP ping. Она показывает время установления TCP-соединения с серверным адресом и портом.

## Разработка

Основные каталоги:

    src/
      App.tsx                 React UI
      lib/runtime.ts          Tauri / Android bridge и конфигурация sing-box
      lib/links.ts            Импорт ссылок и подписок
      lib/types.ts            Модели профилей и настроек
    src-tauri/
      src/core.rs             Desktop core lifecycle, конфиги, routing, backups
      android/                Kotlin VPN service и libbox bridge overlay
      binaries/               Ресурсы сетевых ядер и геоданные
    scripts/
      prepare-android.sh      Подготовка Android-проекта

Проверки перед отправкой изменений:

    npm test
    npm run build
    cargo test --manifest-path src-tauri/Cargo.toml

Для TUN дополнительно необходимы тесты на реальном Windows/Linux/Android-устройстве. Успешный npm run build не проверяет создание системного VPN-интерфейса.

## Используемые проекты

- [sing-box](https://github.com/SagerNet/sing-box) — сетевое ядро и TUN.
- [Xray-core](https://github.com/XTLS/Xray-core) — Xray engine и GeoIP/GeoSite.
- [Tauri](https://github.com/tauri-apps/tauri) — desktop/mobile shell.
- [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) — референс Android VPN lifecycle.
- [Throne](https://github.com/throneproj/Throne) — референс desktop proxy/routing UX.

Исходники upstream-проектов сохраняют собственные лицензии. Wayline не включает VPN-серверы и не предоставляет VPN-подписку — используйте собственные серверы и проверяйте доверие к их операторам.

## Лицензия

См. файл [LICENSE](LICENSE).
