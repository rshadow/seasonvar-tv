# Безопасность репозитория GitHub

Конфигурация и ограничения — [ADR-0058](adr/0058-github-security-checks.md). Работают только бесплатные функции для публичного репозитория; платный Advanced Security не требуется.

## Что делает конфигурация

| Файл | Назначение |
| --- | --- |
| [.github/dependabot.yml](../.github/dependabot.yml) | PR обновлений Gradle и GitHub Actions по понедельникам; Auto-merge включается только после выбора владельца |
| [codeql.yml](../.github/workflows/codeql.yml) | Java/Kotlin, Python и workflow Actions: анализ на PR/main и раз в неделю |
| [dependency-graph.yml](../.github/workflows/dependency-graph.yml) | Разрешённые Gradle-зависимости, включая транзитивные; код сборки исполняется с read-only токеном |
| [dependency-submit.yml](../.github/workflows/dependency-submit.yml) | Публикация готового графа и статуса Dependency snapshot на исходном коммите; код PR не исполняется |
| [dependency-review.yml](../.github/workflows/dependency-review.yml) | Проверка новых high/critical уязвимостей в PR |
| [main.json](../.github/rulesets/main.json) | Обязательные PR и проверки, защита main |
| [release-tags.json](../.github/rulesets/release-tags.json) | Защита v* от удаления и force push |

## Однократные действия владельца

1. Открыть [Settings → Advanced Security](https://github.com/rshadow/seasonvar-tv/settings/security_analysis). Убедиться, что включены **Dependency graph**, **Dependabot alerts**, **Dependabot security updates**, **Secret scanning** и **Push protection**. Уже включённые пункты повторно настраивать не нужно. CodeQL использует advanced setup из workflow проекта; default setup одновременно включать не нужно. Граф Gradle отправляет наш workflow.
2. Дождаться первого успешного CodeQL, Dependency graph/Publish dependency graph и проверки тестового PR. Только после появления этих статусов включать обязательные проверки, чтобы не заблокировать первоначальную настройку.
3. Открыть [Settings → Rules → Rulesets](https://github.com/rshadow/seasonvar-tv/settings/rules). В меню **New ruleset → Import a ruleset** выбрать локальный [main.json](../.github/rulesets/main.json), проверить и нажать **Create**. Повторить импорт для [release-tags.json](../.github/rulesets/release-tags.json).

Правила активны после импорта, обход для владельца не настроен. Для работы одного человека число обязательных одобрений — 0; проверки и PR обязательны. В ruleset main должны появиться семь статусов от приложения GitHub Actions:

- build
- CodeQL (java-kotlin)
- CodeQL (python)
- CodeQL (actions)
- Dependency graph
- Dependency review
- Dependency snapshot

Правило Code scanning требует CodeQL без ошибок и high/critical security alerts. Блокировка не означает автоматическое исправление: проблему нужно разобрать и исправить в PR. Для тегов запрет force push не препятствует созданию следующей версии по явной команде пользователя.

## Повседневная работа

Изменения отправляются в отдельную ветку, затем открывается PR в main. Для готовых PR rshadow агент может включать штатный Auto-merge GitHub; PR Dependabot и других авторов выбирает владелец. Без включённого Auto-merge PR сливается вручную. Для изменений плеера перевести PR из Draft и разрешить слияние только после обязательной визуальной проверки видео. Решение — [ADR-0072](adr/0072-native-github-auto-merge.md). При обновлении Kotlin/AGP/SDK сохранять совместимость с toolchain.lock.json; при обновлении Actions синхронизировать их SHA с security_ci в этом файле; одного зелёного обновления текста зависимости недостаточно для изменения инструментария.

Проверки доступны в Actions, находки — во вкладке Security and quality. Зависимости видны в Insights → Dependency graph. Обнаружение API-токенов не гарантирует обнаружение любого пользовательского пароля; secrets.env и файлы cookie остаются вне Git.

## Как проходит проверка PR

`Dependency graph` разрешает дерево Gradle и сохраняет снимок. Затем `Publish dependency graph` публикует данные и выставляет статус `Dependency snapshot` исходному коммиту. `Dependency review` сначала проверяет успешную публикацию снимков обоих коммитов — базового и нового — и только потом сравнивает зависимости. Без подтверждения публикации пустой diff не считается успешной проверкой. Эта последовательность нужна и для PR из forks: привилегированный шаг получает готовые данные и не запускает присланную сборку.

Проверка зависимостей может несколько минут ожидать снимок. При ошибке подготовки графа исправить соответствующий workflow; отсутствие данных не означает отсутствие уязвимостей. Для первого запуска нужен опубликованный граф main и файл workflow публикации в main.

## Локальные команды

```bash
make ci-lint
make ci-tools
make codeql-build
```

`make ci-tools` устанавливает закреплённые JDK/SDK без загрузки эмулятора, `make codeql-build` повторно компилирует Kotlin/Java и инструмент подписи без запуска подписи и чтения секретов. Сам анализ CodeQL выполняется workflow GitHub. `make ci-export-env` нужен только внутри GitHub Actions: экспортирует пути JDK/SDK в GITHUB_ENV для официального Gradle action.

Приложение не меняется; установка на эмулятор или телевизор для настройки CI не требуется.

## Штатный Auto-merge GitHub

В Settings → General → Pull Requests включён Allow auto-merge. Эта настройка разрешает функцию для репозитория; каждый PR выбирается отдельно. В блоке слияния PR нажать Enable auto-merge, выбрать способ слияния и подтвердить Confirm auto-merge. Если все условия уже выполнены, доступна обычная кнопка Merge. Включить Auto-merge может владелец или подключённый им агент с правами записи.

GitHub ждёт обязательные проверки и требования защиты main. При конфликте или отставании от main обновить ветку PR и дождаться новых проверок. Draft остаётся под управлением автора. Для PR Dependabot и других авторов агент включает Auto-merge только по явной команде пользователя.

После слияния обычные push-workflow запускают Android build, CodeQL и Dependency graph на main; Publish dependency graph публикует снимок и статус Dependency snapshot. Включено удаление слитых веток репозитория. Релиз по-прежнему требует отдельной команды на создание и отправку тега.

Для проверки перехода провести PR через штатный Auto-merge, дождаться публикации снимка нового main и проверить, что следующий PR получает снимок базы без ручного запуска. Старые записи запусков удалённого workflow могут оставаться в истории Actions.

## Проверка первоначальной настройки

2026-11-04 владелец подтвердил включение серверных опций безопасности. Построение и публикация графа main прошли, статус Dependency snapshot установлен приложением GitHub Actions. На [PR №6](https://github.com/rshadow/seasonvar-tv/pull/6) прошли сборка/тесты/Lint, три анализа CodeQL, граф и Dependency review; снимок head был опубликован. Владелец слил PR. Импорт правил main и тегов выполняется отдельно по инструкции выше.
