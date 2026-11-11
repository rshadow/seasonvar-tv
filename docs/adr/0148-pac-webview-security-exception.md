# ADR-0148: ограничения WebView и исключение для исполнения PAC

Дата: 2026-11-11. Статус: принято. Дополняет [ADR-0143](0143-antizapret-pac-proxy.md) и [ADR-0058](0058-github-security-checks.md).

## Контекст

CodeQL отмечает включение JavaScript в обработчике PAC правилом `java/android/websettings-javascript-enabled`. JavaScript необходим для исполнения оригинального `FindProxyForURL`; отключение ломает оба PAC-режима. Предупреждение `java/android/websettings-allow-content-access` возникает при получении WebView из поля, хотя доступ запрещён при создании.

## Решение

Единая функция ограничивает сеть, файловый доступ и `content://` перед первой загрузкой пустого документа и перед каждым вызовом JavaScript. В WebView не загружаются страницы сайта и не добавляются JavaScript-мосты к Kotlin/Java. Входом служат PAC и подготовленные домены/IP без подписанных видеоссылок, выходом — проверенные строки маршрутов. Временный процесс и renderer завершаются после вычисления, скрипт ограничен временем и размером.

Принимается риск включённого JavaScript только в `PacParserService.prepare`, для выполнения PAC. Конкретную находку CodeQL оформлять как `won't fix` с обоснованием этих ограничений и ссылкой на это решение. Запрос CodeQL, остальные находки и проверки репозитория сохраняются. Перенос этого исключения на другие WebView требует отдельной оценки.

Отдельный процесс использует UID приложения и сам по себе не является границей доступа к его данным. Ограничения WebView и отсутствие JavaScript-мостов обязательны; остаточный риск ошибок провайдера WebView сохраняется.

## Альтернативы и проверка

Отключение JavaScript несовместимо с выбранным обработчиком PAC. Исключение всего файла или правила скрывало бы другие находки. Ограничение доступа к `content://` исправляется в коде и не входит в принятое исключение.

`make pac-engine-probe` проверяет реальные значения WebSettings, повторное применение ограничений без включения JavaScript, исполнение PAC отдельным сервисом, ошибки скрипта, таймаут и освобождение процесса. Настройки при выполнении проверяются тем же общим кодом, что и при создании WebView.

Источники: [правило JavaScript](https://codeql.github.com/codeql-query-help/java/java-android-websettings-javascript-enabled/), [правило content access](https://codeql.github.com/codeql-query-help/java/java-android-websettings-allow-content-access/), [закрытие конкретной находки GitHub](https://docs.github.com/en/code-security/how-tos/manage-security-alerts/manage-code-scanning-alerts/resolve-alerts).
