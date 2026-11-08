# OpenProxy

Android VPN-клиент на Kotlin + Jetpack Compose с ядром **sing-box** (`libbox`).
Material 3 Expressive, динамические цвета, импорт ссылок и подписок, пинг серверов,
раздельное туннелирование и настраиваемая маршрутизация.

## Что уже реализовано

### Протоколы

| Протокол | Импорт | Подключение | Примечание |
|---|---|---|---|
| VLESS (TLS / REALITY / TCP / WS / gRPC / HTTP / HTTPUpgrade) | да | да | `xtls-rprx-vision`, `pbk`, `sid`, `fp`, `sni`, `alpn` |
| VMess | да | да | base64 JSON и plain URI |
| Trojan | да | да | TLS, WS/gRPC/HTTP |
| Shadowsocks | да | да | SIP002, legacy base64, плагины |
| Hysteria 2 | да | да | пароль, obfs salamander, pinSHA256 не используется ядром |
| TUIC | да | да | congestion control, UDP relay mode |
| WireGuard | да | да | `wireguard://`, `wg://`, `.conf` |
| SOCKS5 / HTTP-прокси | да | да | логин/пароль |
| SSH | да | да | пароль |
| ShadowsocksR | да | **нет** | ядро sing-box не поддерживает SSR |
| Hysteria 1 | да | **нет** | поддерживается только Hysteria 2 |
| AmneziaWG | да | **нет** | нужна сборка ядра с AWG-обфускацией |
| OpenVPN | да | **нет** | `.ovpn` сохраняется; нужен OpenVPN-движок |
| XHTTP / SplitHTTP | определяется | **нет** | требует Xray; в конфиге будет явная ошибка |

Профили неподдерживаемых протоколов не теряются: они импортируются, отображаются в
списке, их можно удалить/экспортировать, а в карточке написана причина, почему нельзя
подключиться.

### Подписки

- вставка ссылки `http(s)://` — автоопределение подписки;
- base64-список ссылок, обычный список, sing-box JSON (`outbounds`/`endpoints`);
- заголовок `subscription-userinfo`: использовано / лимит / остаток;
- `profile-title`, `profile-update-interval`, `profile-web-page-url`, `support-url`;
- дата окончания и количество дней;
- автообновление устаревших подписок при запуске;
- Clash YAML распознаётся и отклоняется с понятным сообщением.

### Сеть и маршрутизация

- TUN через `VpnService`, MTU и IPv6 настраиваются;
- режимы: глобально / правила / напрямую;
- обход локальной сети;
- DNS: системный, Cloudflare, Google, AdGuard или свой; DNS через туннель;
- раздельное туннелирование: все приложения / только выбранные / кроме выбранных;
- собственные правила `domain:…=direct`, `ip:…=direct`, `port:…=block`, `process:…=proxy`;
- блокировка рекламы через `geosite-category-ads-all`;
- пинг TCP-рукопожатием (для UDP-протоколов — по 443, это оценка доступности);
- живая статистика трафика через локальный Clash API.

### Интерфейс

- 4 экрана: Главная, Серверы, Подписки, Настройки;
- Material 3 Expressive (`MaterialExpressiveTheme`, `MotionScheme.expressive()`);
- динамические цвета Android 12+, ручная светлая/тёмная тема;
- у каждой настройки есть пояснение простым языком;
- импорт из буфера обмена и файла `.conf`/`.ovpn`/`.txt`;
- обработка `vless://…` и `ACTION_SEND` из других приложений.

## Архитектура

```
core/
  model/      ServerProfile, Subscription, AppSettings, Protocol, VpnStatus
  parse/      ShareLinkParser, SubscriptionParser, WireGuardConfigParser,
              OpenVpnConfigParser, SingBoxJsonParser, ImportDetector
  net/        SubscriptionFetcher (OkHttp), LatencyTester
  store/      AppRepository (Preferences DataStore + kotlinx.serialization)
  singbox/    SingBoxConfigBuilder, SingBoxRuntime, ClashApiClient
  vpn/        ProxyVpnService (PlatformInterface + CommandServerHandler),
              VpnController, NotificationHelper
ui/           Compose-экраны, компоненты, тема, AppViewModel
```

Ядро запускается через `Libbox.newCommandServer(...)`; Android TUN создаётся в
`PlatformInterface.openTun` по параметрам, которые присылает sing-box, а исходящие
сокеты защищаются через `VpnService.protect(fd)`.

## Сборка

Требования: JDK 17, Android SDK 36, Android Studio последней версии.

```bash
./gradlew assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`.

Зависимости: AGP 8.13.2, Kotlin 2.4.21, Compose BOM 2026.09.00,
Material3 1.5.0-rc01, lifecycle 2.11, OkHttp 5.5.1, libbox 2.1.4
(`net.clever-vpn:libbox-android`, Maven Central).

## Ограничения и честные предупреждения

- **Проект не компилировался в этой среде.** SDK и зависимости не скачивались:
  сначала нужно ваше решение о сборке. Возможны правки под конкретную версию AGP.
- `libbox` — сторонняя сборка sing-box (MIT). Проверьте, что её конфигурационная
  схема совпадает с вашей версией; при обновлении ядра пересоберите AAR.
- OpenVPN, AmneziaWG, SSR, Hysteria 1 и XHTTP не туннелируются встроенным ядром.
  Для них нужны отдельные движки (OpenVPN/AmneziaWG) или Xray-ядро.
- Пинг — TCP-рукопожатие, а не проверка реального проксирования. Полноценный
  URL-test можно добавить через `/proxies/{tag}/delay` Clash API.
- Приложение не отправляет телеметрию. Ключи хранятся в приватном DataStore.
- Для Android 14+ используется foreground service type `specialUse` с подтипом `vpn`.
  Для Google Play потребуется обоснование (VPN-приложения проходят отдельную проверку).

## Идеи следующего шага

1. Собрать проект и устранить ошибки компиляции.
2. Проверить генерацию конфигов на реальном сервере VLESS/REALITY.
3. Добавить URL-test через Clash API и сортировку серверов по задержке.
4. Добавить OpenVPN-движок и AmneziaWG-сборку ядра.
