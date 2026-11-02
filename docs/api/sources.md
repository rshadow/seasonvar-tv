# Источники и проверка

Дата исследования: **2026-11-01**. Источники ниже прочитаны напрямую через GitHub или npm registry. Контракты восстановлены по исходникам; официальный опубликованный OpenAPI/Swagger документ не найден.

## 1. Старый API с ключом

### npm seasonvar-api@1.0.10

- [Метаданные конкретной версии](https://registry.npmjs.org/seasonvar-api/1.0.10).
- [Опубликованный архив](https://registry.npmjs.org/seasonvar-api/-/seasonvar-api-1.0.10.tgz).
- Изучен `package/index.js`: POST-обёртка и семь команд; код пакета не выполнялся.
- SHA-1 архива: `ace97cfda2d968827afd6e1799787c424e5ef72b`. Контрольная сумма скачанных байтов совпала с `dist.shasum` registry.
- Registry указывает репозиторий `wormen/seasonvar-api`; запрос дерева через GitHub API вернул 404. Исходником для документации служит опубликованный npm-архив.
- Пакет относится к 2016 году; его наличие в registry не доказывает текущую работу API.

### Ruby-клиент avsej/seasonvar.rb

- [lib/seasonvar/client.rb](https://github.com/avsej/seasonvar.rb/blob/35f5495c817892b707d3ed513e0ea72dbbd7107d/lib/seasonvar/client.rb).
- Commit: `35f5495c817892b707d3ed513e0ea72dbbd7107d`.
- Независимо подтверждает адрес, POST, имена команд и основные параметры.
- Не используется как доказательство правильной сериализации вложенных параметров: их кодирование нужно проверить отдельно.

## 2. Протокол аккаунта и HTML сайта

### 0x3654/lampa-plugins

- Commit: `71e97be0264ef1c69acaf227707b651750d0b64b`.
- Дата commit: `2026-10-25T20:44:59Z`.
- [tracker-top/seasonvar.go](https://github.com/0x3654/lampa-plugins/blob/71e97be0264ef1c69acaf227707b651750d0b64b/tracker-top/seasonvar.go).

Изучены исходящие запросы к Seasonvar: логин, `svid1`, поиск, подборки, `/resume`, страницы сезонов и `jsonMark.php`. Собственные маршруты прокси `/seasonvar/*` не являются API исходного сайта. Код не запускался, аккаунт этому сервису не передавался.

### Zhmot666/SeasonvarTV

- Commit: `f79d2f379a3504d5de9668f3d16feef58de5a33f`.
- Дата commit: `2026-03-21T17:47:43Z`.
- [Описание Android TV-клиента](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/README_ANDROID.md).
- [SeasonvarApi.kt](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/src/main/java/ru/seasonvar/tv/data/api/SeasonvarApi.kt).
- [SeasonvarParser.kt](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/src/main/java/ru/seasonvar/tv/data/api/SeasonvarParser.kt).
- [SeasonvarRepository.kt](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/src/main/java/ru/seasonvar/tv/data/repository/SeasonvarRepository.kt).
- [PlayerActivity.kt](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/src/main/java/ru/seasonvar/tv/player/PlayerActivity.kt).
- [AndroidManifest.xml](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/src/main/AndroidManifest.xml).
- [app/build.gradle.kts](https://github.com/Zhmot666/SeasonvarTV/blob/f79d2f379a3504d5de9668f3d16feef58de5a33f/app/build.gradle.kts).

Исходники подтверждают предназначение для ТВ: Leanback, управление пультом, горизонтальные Activity, отсутствие требования touchscreen. В конфигурации `minSdk=21`. Авторизация и личные разделы реализованы через сайт. Плеер — WebView, а не нативный ExoPlayer/Media3. Сборка, установка, пригодность UX и воспроизведение в этом исследовании не проверялись.

## 3. Старое получение плейлистов

### weirdgiraffe/plugin.video.giraffe.seasonvar

- Commit: `a81ce38c88df7ced1d72effbe513b9307d315390`.
- Дата commit: `2024-06-05T12:47:35Z`. Это дата выбранного commit репозитория, не подтверждение обновления самого протокола в 2024 году.
- [requester.py](https://github.com/weirdgiraffe/plugin.video.giraffe.seasonvar/blob/a81ce38c88df7ced1d72effbe513b9307d315390/resources/site-packages/seasonvar/requester.py).
- [parser.py](https://github.com/weirdgiraffe/plugin.video.giraffe.seasonvar/blob/a81ce38c88df7ced1d72effbe513b9307d315390/resources/site-packages/seasonvar/parser.py).
- [playlist.json — старая фикстура](https://github.com/weirdgiraffe/plugin.video.giraffe.seasonvar/blob/a81ce38c88df7ced1d72effbe513b9307d315390/resources/site-packages/seasonvar/tests/assets/playlist.json).

Подтверждает старую цепочку `data4play → player.php → pl → JSON с file`. URL из фикстур не переносились в примеры как действующие.

### AlexanderC/seasonvar-api

- Commit: `df12e7b909be2ea957710619ef3f3ccb01c4735a`.
- Дата commit: `2018-04-15T21:12:01Z`.
- [lib/playlist.js](https://github.com/AlexanderC/seasonvar-api/blob/df12e7b909be2ea957710619ef3f3ccb01c4735a/lib/playlist.js).
- [lib/secure-mark.js](https://github.com/AlexanderC/seasonvar-api/blob/df12e7b909be2ea957710619ef3f3ccb01c4735a/lib/secure-mark.js).

Это другой пакет/проект, не `seasonvar-api@1.0.10` от Oleg Bogdanov. Он парсит сайт без API-ключа. Старое конструирование `playls2/…/list.xml` и изменение security mark не рекомендованы как современный контракт без проверки.

## 4. Проверка сети и Quad9

Все обращения выполнялись без учётных данных. Проверка TLS не отключалась. Системные DNS-настройки не менялись.

| Проверка | Наблюдение |
| --- | --- |
| Системный resolver: `api.seasonvar.ru` | Ошибка разрешения имени |
| HTTPS `seasonvar.ru/` и `/?mod=api` | Ошибка проверки самоподписанного сертификата |
| HTTP `seasonvar.ru/?mod=api` с редиректами | HTTP 200 у конечной страницы `https://m.megafonpro.ru/rkn-landing/sales-communication`; это не ответ Seasonvar |
| Quad9 `9.9.9.9`, обычный DNS | `api.seasonvar.ru`: `NXDOMAIN`; основной домен: A-записи |
| Quad9 DoH, TLS-проверка включена | Те же результаты; подключение к `dns.quad9.net` закреплено на `9.9.9.9` |
| HTTPS с `curl --resolve` по адресам из Quad9 | Тайм-ауты либо ошибка сертификата; страница API не получена |

DoH-запрос использовал `https://dns.quad9.net/dns-query`, формат `application/dns-message` и базовую bootstrap-запись `dns.quad9.net:443:9.9.9.9`. Это исключает зависимость данного запроса от системного разрешения имени DoH-сервера. Причину ответа `NXDOMAIN` он сам по себе не устанавливает.

Адреса, полученные от Quad9 2026-11-01, приведены для воспроизводимости диагностики, не для постоянного хардкода:

| IP seasonvar.ru | Результат HTTPS `/?mod=api` |
| --- | --- |
| `89.124.99.46` | TLS-тайм-аут |
| `185.154.14.179` | Ошибка самоподписанного сертификата |
| `91.198.166.23` | Ошибка самоподписанного сертификата |
| `46.30.41.42` | TLS-тайм-аут |
| `195.63.143.40` | Тайм-аут подключения |

Вывод ограничен нашей сетью и временем проверки: Quad9 не обеспечил доступ. Из этого нельзя заключать, что основной сайт недоступен у пользователя или что старый API окончательно закрыт.

## 5. Ответ поиска, полученный пользователем

2026-11-01 пользователь открыл в браузере `GET /autocomplete.php?query=Игра%20престолов` и передал полный JSON в чат. Ответ сохранён в [examples/autocomplete-game-of-thrones.json](examples/autocomplete-game-of-thrones.json), Unicode-экранирование декодировано для чтения. Cookie, пароли и персональные данные в нём отсутствуют.

Наблюдения: `query` — строка; `suggestions.valu`, `suggestions.kp`, `data`, `id` — массивы строк длиной 14; ID относятся к отдельным сезонам/страницам, а названия и рейтинги могут содержать HTML. Это пользовательская проверка поиска, не доказательство доступности из сети агента, анонимности браузера, работы логина или видеоплеера.

## 6. Что ещё проверить

1. Поиск: пользователь уже прислал реальный JSON, сохранённый в [примере](examples/autocomplete-game-of-thrones.json). Дальше проверить пустой запрос, отсутствие результатов, ошибки и поведение без cookie.
2. Проверить выдачу и истечение сессии своего аккаунта, пустые личные списки и ошибку пароля.
3. Проверить HTML страницы сезона и текущий вариант плейлиста.
4. Установить, доступен ли прямой видеопоток для нативного плеера; проверить озвучки, качество и права аккаунта.
5. С разрешённой тестовой отметкой подтвердить поля `jsonMark.php`, ответ и повторное чтение прогресса.
6. Если `/?mod=api` доступна после входа, уточнить действующий адрес, условия API-ключа и официальные схемы.

Реальные пароли, API-ключи, cookie и подписанные видеоссылки в документацию и примеры не включать. Для будущих локальных скриптов источник секретов — только `~/secrets.env`; значения не выводить.

## 7. Доступ через восстановленную DPI-стратегию

2026-11-01 найден прежний исследовательский `dpitest.py` и подтверждённая пользователем стратегия ByeByeDPI `-o1 -a1 -r-5+se`. Её TCP/TLS-часть перенесена в [проектный загрузчик](../../scripts/fetch-seasonvar.py); значение UDP-флага `-a1` сверено с [исходной документацией ByeDPI](https://github.com/hufrea/byedpi/blob/main/README.md).

Проверка `make site-fetch` с Quad9 DoH и включённой проверкой сертификата вернула настоящую главную: HTTP 200, 458 332 байта, восемь блоков `div.news`, первая дата 01.11.2026. Поиск тем же загрузчиком вернул валидный JSON с 14 результатами; сервер указал для него `text/html; charset=UTF-8`. Запросы выполнялись без авторизации и cookie. Инструкция и ограничения — [access.md](access.md).

Предыдущие наблюдения в разделе 4 относятся к прямым запросам до применения стратегии; они не описывают результат загрузчика.

## 8. Подборка «Обновления»

2026-11-02 повторный `make site-fetch` получил публичную главную HTTP 200 (458 332 байта). Блок `li[data-tabr=rside-new][data-tabgr=rightside]` содержит 50 обновлений на 48 страниц сезонов; повторения страниц относятся к разным сериям/озвучкам. Порядок, подписи и ссылки используются в клиенте без собственной сортировки. Последний датированный блок — 01.11.2026, поэтому выдача «Сегодня» за 02.11 пуста.

Официальный [main.min.js?25.4.1](https://cdn.svstat.net/asset/js/main.min.js?25.4.1) скачан через `make site-scripts`; в коде подтверждён AJAX-режим `new` для этой вкладки. Сам POST не проверялся: начальная подборка уже присутствует в главной. Поля и ограничения описаны в [web-api.md](web-api.md).

## 9. Проверка AJAX-запроса «Обновлений»

2026-11-02 `make site-updates` отправил анонимный `POST /ajax.php?mode=new` с формой из официального JavaScript, без предварительного GET главной. Ответ: HTTP 200, `text/html; charset=UTF-8`, 30 828 байт, 50 карточек на 49 страниц сезонов. [Снимок ответа](../../app/src/test/resources/updates-ajax.html) используется тестом полного порядка выдачи.

Повторные запросы с `Accept: application/json` и с JSON-телом (`Content-Type: application/json`) вернули побайтово тот же HTML. Это проверка фактического поведения сервера, не предположение по заголовкам. Описание запроса — [web-api.md](web-api.md), принятое изменение источника — [ADR-0017](../adr/0017-updates-via-ajax.md).

### Различие главной и AJAX

Повторная проверка 2026-11-02 и ответ пользователя без авторизации подтвердили различие начальных записей: главная начинается с «Хмельного сна», AJAX — с «Оно: Добро пожаловать в Дерри». В сопоставленных ответах первые 39 ссылок главной совпадают с позициями 12–50 AJAX. Приложение показывает полный AJAX-порядок. Это расхождение источников, а не установленная ошибка парсера или сортировки. Серверное кэширование/разное время обновления — гипотеза; точная причина со стороны сайта не установлена.

## 10. Метка «Новинка»

2026-11-02 в сохранённом публичном AJAX-ответе подтверждён класс `rside-p-new`: 10 помеченных позиций из 50. В полученном официальном `pg.index.css?25.4.1` правило `.rside-p-new:before` задаёт надпись «новинка» и бордовую полосу поверх верхнего края постера. Это отдельный признак сайта, не вычисление по номеру серии. [ADR-0018](../adr/0018-site-new-badge.md) описывает отображение в приложении.

## 11. Проверка «Топа» через AJAX

2026-11-02 `make site-top` выполнил анонимный `POST /ajax.php?mode=pop` с формой `ganre=&country=&block=0&main=1`. Без начального GET главной получены HTTP 200, `text/html; charset=UTF-8`, 28 729 байт и 49 карточек. Первые три — «Тверская», «Жизнь заново», «Ставка на любовь». Разметка совпадает с `mode=new`; [снимок ответа](../../app/src/test/resources/top-ajax.html) проверяет порядок, метаданные и бейджи автономно.

## 12. Проверка «Новинок» через AJAX

2026-11-02 `make site-newest` выполнил анонимный `POST /ajax.php?mode=newest` с общей формой боковых подборок. Получены HTTP 200, `text/html; charset=UTF-8`, 30 062 байта и 50 карточек. Первая — «Другие (Бразилия)» (`Os Outros`, сезон 1, `1-12 серия`). Метка `rside-p-new` есть у 27 карточек, у первой её нет. [Снимок ответа](../../app/src/test/resources/newest-ajax.html) используется автономными тестами порядка и метаданных.
