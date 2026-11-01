# Скиллы проекта

Все скиллы, подключаемые для проекта, устанавливаются локально в `seasonvar/.agents/skills` — [ADR-0004](../../docs/adr/0004-project-local-skills.md). Этот файл перечисляет текущий используемый набор.

## Текущие используемые скиллы

Источник: [android/skills](https://github.com/android/skills), commit `6daa53ef27fa2aa87827d183a85c06cedceca833`. Лицензия Apache 2.0 включена как `LICENSE.txt` в каждом скилле.

| Скилл | Назначение |
| --- | --- |
| [android-cli](android-cli/SKILL.md) | Инструментарий Android, SDK, локальные эмуляторы, запуск APK, изучение интерфейса и документации |
| [jetpack-compose-performance](jetpack-compose-performance/SKILL.md) | Анализ и исправление производительности Compose |
| [leanback-to-compose-tv-migration](leanback-to-compose-tv-migration/SKILL.md) | Миграция существующего Leanback-интерфейса на Compose for TV; для нового приложения применяется только при появлении такой задачи |

Включены исходные инструкции и сопутствующие файлы из закреплённой версии. При обновлении сохранять лицензию и обновлять этот commit. Установка в пользовательские глобальные каталоги для этого проекта не требуется.

Codex обнаруживает `.agents/skills` внутри рабочего каталога проекта. См. [официальную документацию](https://learn.chatgpt.com/docs/build-skills#where-codex-loads-local-skills). Правила применения закреплены в [AGENTS.md](../../AGENTS.md).
