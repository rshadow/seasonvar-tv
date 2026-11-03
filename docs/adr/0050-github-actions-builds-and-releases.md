# ADR-0050: Автосборка и релизы APK через GitHub Actions

Дата: 2026-11-03

Статус: Формат имени APK уточнён [ADR-0051](0051-universal-apk-filename.md); остальные решения действуют.

## Контекст

Публичный репозиторий должен проверять изменения и выдавать APK без локальной сборки. Для GitHub Releases используется постоянная подпись по [ADR-0048](0048-release-apk-signing.md); локальный источник секретов определён [ADR-0049](0049-project-local-secrets.md).

## Решение

- Workflow [android.yml](../../.github/workflows/android.yml) запускается при push в `main`, push тега `v*`, открытии/обновлении pull request и вручную через Actions.
- На Ubuntu 24.04 с Python 3.12 выполняются `make ci-lint`, `make ci-prepare` и `make check`. Инструменты берутся из `toolchain.lock.json`; CI устанавливает только необходимые для сборки пакеты, принимает лицензии SDK и загружает зависимости. Локальная полная подготовка с эмулятором сохраняется.
- Каждый успешный запуск сохраняет debug APK. Push и ручной запуск дополнительно собирают подписанный release APK; pull request не получают ключ подписи.
- Репозиторный GitHub Actions Secret `SEASONVAR_SIGNING_ENV` содержит четыре параметра подписи из локального `secrets.env`. Он передаётся только шагу подписания. На runner файл создаётся с правами `0600`, значения не печатаются, файл удаляется шагом `always()`. Отдельная копия бинарного ключа не создаётся.
- `make release-package` формирует `build/release/seasonvar-tv-<versionName>-universal.apk` (имя уточнено ADR-0051) и `SHA256SUMS`. Для тега обязательно совпадение с `v<versionName>`. Перед новым релизом обновляются `versionName` и `versionCode` в Gradle.
- Только тег запускает отдельную job публикации. Она скачивает проверенный артефакт, проверяет SHA256 и создаёт GitHub Release с APK, контрольной суммой и автоматически сформированными примечаниями. Версия с суффиксом, например `-beta.1`, публикуется как prerelease. Уже опубликованный тег автоматически не перезаписывается.
- Сборочная job имеет `contents: read`; публикация — `contents: write` и автоматически выдаваемый `GITHUB_TOKEN`. Личный токен для CI не нужен. Job публикации не получает ключ подписи и не выполняет код репозитория.
- Используемые GitHub Actions закреплены полными SHA. Кэш содержит только `.tools`, Gradle dependencies и Wrapper. В артефакты попадают только явно названные APK и SHA256SUMS; сроки хранения — 14 дней. Публичные Releases сохраняют APK независимо от этого срока.

## Альтернативы

- **Ручная сборка и прикрепление APK.** Требуют повторять выпуск на компьютере разработчика.
- **Релиз после каждого коммита.** Переполняет список Releases; для промежуточных сборок достаточно артефактов Actions.
- **Ключ в репозитории или кэше.** Не используется; ключ передаётся через GitHub Secrets.

## Последствия и ограничения

Тесты и Lint в CI не заменяют проверку интерфейса и реального видео. Начальная загрузка инструментария требует сети; следующие запуски используют кэш. Новый release требует нового тега и увеличенного `versionCode`.

При отсутствии `SEASONVAR_SIGNING_ENV` проверки проходят, но шаг подписания завершается явной ошибкой и релиз не публикуется. Настройка секрета выполняется владельцем через Settings → Secrets and variables → Actions.

Источники: [GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets), [GitHub CLI release create](https://cli.github.com/manual/gh_release_create), официальные [checkout](https://github.com/actions/checkout), [upload-artifact](https://github.com/actions/upload-artifact) и [download-artifact](https://github.com/actions/download-artifact).
