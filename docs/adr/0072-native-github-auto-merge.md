# ADR-0072: Штатный Auto-merge GitHub вместо собственного workflow слияния

Дата: 2026-11-04

Статус: Принято

Заменяет [ADR-0059](0059-owner-pull-request-auto-merge.md). Сохраняет защиту main по [ADR-0058](0058-github-security-checks.md) и порядок выпуска релизов по [ADR-0053](0053-explicit-release-tag-command.md).

## Контекст

Владелец включил штатный Auto-merge GitHub и удаление слитых веток. Собственный workflow сливал PR через GITHUB_TOKEN и отдельно запускал проверки main: обычный push после такого слияния не запускает Actions. Цепочка оказалась ненадёжной: после PR #14 отсутствовал снимок main, а Dependency review следующего PR #15 завершилась по таймауту. Ручная генерация и публикация снимка восстановила проверку.

Пользователь выбрал удаление собственного owner-automerge и переход на функцию платформы.

## Решение

- Удалить workflow owner-automerge.yml, scripts/owner-automerge.py, его тесты и команду make owner-automerge.
- Использовать штатный Auto-merge GitHub, включаемый отдельно для выбранного PR владельцем или подключённым им агентом. Для готовых PR rshadow агент может включать его при подготовке PR; для Dependabot и других авторов нужна явная команда пользователя.
- Сохранять merge commit, все обязательные проверки и действующие правила main. Готовность к слиянию определяет GitHub. Изменения плеера держать в Draft до обязательной визуальной проверки.
- Проверки main запускаются обычными push-workflow. Генерация графа и публикация готовых данных сохраняют раздельные права; привилегированный workflow не исполняет код PR.
- Удаление слитых веток выполняет настройка GitHub. Релизный тег и публикация релиза требуют отдельной команды пользователя.

## Альтернативы

- Исправлять собственный workflow слияния: сохраняет дублирование проверок GitHub, обработку ошибок API и отдельную цепочку запуска CI после merge.
- Сливать каждый PR вручную после проверок: поддерживается, но требует возвращаться к PR после завершения CI.

## Последствия и проверка

В репозитории больше нет автоматического отбора PR по автору и REST-слияния через GITHUB_TOKEN. Для автоматического слияния выбранного PR требуется включить штатный Auto-merge. Старые запуски собственного workflow могут сохраняться в истории Actions.

Удаление проверяется make site-test и make ci-lint. Для сквозной проверки нужен PR со штатным Auto-merge, последующая публикация Dependency snapshot для нового main и проверка снимка базы следующего PR. Локальные проверки не подтверждают прохождение этой серверной цепочки.

Источники: [штатный Auto-merge](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/automatically-merging-a-pull-request), [события от GITHUB_TOKEN](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow), [таймаут Dependency review PR #15](https://github.com/rshadow/seasonvar-tv/actions/runs/41887560463/attempts/1), [восстановление графа main](https://github.com/rshadow/seasonvar-tv/actions/runs/41894046198).
