# ADR-0060: Совместимое обновление Kotlin, Compose и coroutines

Дата: 2026-11-04

Статус: Частично пересмотрено [ADR-0061](0061-agp9-built-in-kotlin-and-sdk37.md): инструментарий и подмена R8.

## Контекст

PR №1 обновляет Kotlin, Compose BOM и coroutines одним набором. Compose BOM `2026.09.00` выбирает Compose `1.12.1`: проверка AAR в GitHub отклоняет SDK 36 и Android Gradle Plugin 8.13.2, требуя SDK 37 и AGP не ниже 9.1.0. Локальная debug-сборка с Kotlin 2.4.21 дополнительно выявила ошибки переписывания метаданных в D8 8.13.19, встроенном в текущий AGP. Отдельный конфликт с `main` затрагивает соседние строки зависимостей `org.json` и coroutines-test.

## Решение

Обновить Kotlin Android и Compose Compiler вместе до `2.4.21`, coroutines-android и coroutines-test вместе до `1.11.0`. Для Compose использовать BOM `2026.06.01`, который выбирает Compose `1.11.4` и подходит текущему инструментарию. Сохранить `org.json:json:20260814` из `main`.

SDK 36, AGP 8.13.2 и Gradle 8.13 остаются текущим набором инструментов. D8/R8 закрепить на стабильной версии `9.1.57` из Google Maven через `pluginManagement.buildscript` в `settings.gradle.kts`, по официальной инструкции замены встроенного R8. Для Kotlin 2.4 требуется R8 не ниже 9.1.29; переопределение пересмотреть при следующем обновлении AGP. Переход на Compose 1.12 требует отдельного согласованного обновления SDK и AGP. Требования проверять по метаданным реальных AAR, а не по величине изменения номера версии BOM.

Записи предварительной загрузки в `toolchain.lock.json` обновлять вместе с используемыми версиями: URL, размер, SHA-1 для Gradle-кэша и SHA-256 для проверки скачивания. Артефакты получать из официальных Google Maven и Maven Central.

Проверять `make check`, `make ci-lint` и неподписанный оптимизированный APK через `make build BUILD_TYPE=release`; последняя проверка нужна для совместимости Kotlin с D8/R8. Проверки GitHub остаются обязательными перед слиянием.

## Альтернативы

- Обновить SDK и AGP до требований Compose 1.12 в этом PR. Увеличивает объём миграции и пересекается с отдельными обновлениями инструментов.
- Оставить прежний Compose BOM `2026.02.01`. Сборка совместима, но не получает доступные исправления ветки 1.11.
- Принудительно отключить проверку AAR или подменить отдельные Compose-зависимости. Не обеспечивает совместимость набора библиотек.

## Последствия

Обновление остаётся в пределах текущего SDK и AGP. Новые версии Compose, требующие другого инструментария, нельзя принимать отдельно от соответствующей миграции. Результаты сборки и тестов не заменяют ручную проверку интерфейса на устройстве.

## Источники

Проверено 2026-11-04:

- [Матрица Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin 2.4.21 поддерживает используемые Gradle и AGP.
- [Официальное соответствие Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping).
- [POM Compose BOM 2026.06.01](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom) и AAR `ui-android:1.11.4`: `minCompileSdk=35`, `minAndroidGradlePluginVersion=8.6.0`.
- [Минимальные версии D8/R8 для Kotlin](https://developer.android.com/build/kotlin-support).
- [Официальная замена встроенного R8](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-android-gradle-plugin).
