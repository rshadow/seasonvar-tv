# Настройка тем

Пользователь выбирает тему в «Настройки → Приложение → Тема оформления». В поставке есть «Классическая» и «Стекло». Конфигурации входят в APK; после изменения ресурсов нужно пересобрать приложение.

## Добавить тему

Создать `app/src/main/res/values/theme_night.xml`, например:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="SeasonvarInterfaceTheme.Night" parent="SeasonvarInterfaceTheme.Classic">
        <item name="seasonvarPosterBackgroundEnabled">true</item>
        <item name="seasonvarPosterBlurRadius">3</item>
        <item name="seasonvarPosterDimPercent">65</item>
        <item name="seasonvarAccentColor">#FF80CBC4</item>
        <item name="seasonvarOnAccentColor">#FF102020</item>
        <item name="seasonvarSurfaceColor">#66102020</item>
        <item name="seasonvarDialogBackgroundColor">#FF102020</item>
        <item name="seasonvarSpacingSmall">8dp</item>
    </style>
</resources>
```

Добавить название в `app/src/main/res/values/strings.xml`:

```xml
<string name="interface_theme_night">Ночная</string>
```

Зарегистрировать стиль в `app/src/main/res/xml/interface_themes.xml`, сохранив существующие записи:

```xml
<theme id="night" label="@string/interface_theme_night" style="@style/SeasonvarInterfaceTheme.Night" />
```

Идентификатор сохраняется в настройках: его нельзя менять при простом переименовании темы. Он начинается с латинской строчной буквы, содержит строчные буквы, цифры и подчёркивания, длина не больше 64 символов. Значение `blurred_poster` зарезервировано для восстановления старой настройки и не используется как идентификатор новой темы. Атрибут `defaultTheme` корневого элемента задаёт выбор при отсутствии подходящего сохранённого значения.

Изменения Kotlin для добавления такой темы не требуются. Новые возможности оформления, которых нет в схеме атрибутов, требуют реализации в общих компонентах.

## Где находятся параметры

| Файл | Назначение |
| --- | --- |
| `res/xml/interface_themes.xml` | Реестр, порядок выбора и тема по умолчанию |
| `res/values/theme_attributes.xml` | Полная схема поддерживаемых атрибутов и их типов |
| `res/values/theme_classic.xml` | Классический стиль и значения по умолчанию |
| `res/values/theme_glass.xml` | Цвета и отличия стеклянного стиля |
| `res/values/themes.xml` | Общие размеры элементов, пропорции, параметры поведения и тема Android-окна |

Пути в таблице отсчитываются от `app/src/main`. Стиль может наследовать встроенную тему и переопределять только нужные значения.

## Группы атрибутов

Все имена начинаются с `seasonvar`; точные имена и типы перечислены в `theme_attributes.xml`.

- Палитра: акцент и текст на нём, основной и второстепенный текст, поверхности, фон страницы, подложка диалога, цвета успеха, ошибки и проверки.
- Рамки: `TextButtonOutline`, `IconButtonOutline`, `CardOutline`, `SurfaceOutlineColor`, `SurfaceOutlineWidth`, `FocusBorderWidth`. В «Стекле» рамки иконок выключены.
- Скругления: `ButtonCornerRadius`, `CardCornerRadius`.
- Интервалы: внешние поля страницы, `SpacingSmall/Medium/Large/ExtraLarge/Section`, промежутки и отступы меню, контента, карточек и меток, внутренний вертикальный отступ часов. Габариты кнопок, карточек, постеров, диалогов и фотографий остаются общими.
- Фон: `PosterBackgroundEnabled`, `PosterBlurRadius`, `PosterDimPercent`, `PosterEdgePercent`, `PosterFadeEdgePercent`, `PosterFadeMillis`, `PosterFallbackImage`. Источник — уже загруженный постер выбранного сериала. Если он отсутствует, `PosterFallbackImage` задаёт локальный drawable: в «Стекле» это `@drawable/ic_launcher`. Он использует те же размытие, затемнение и ограниченный кеш, без сетевых запросов. Радиус относится к уменьшенному изображению, а не к пикселям экрана; поддерживаются значения от 0 до 256. Затемнение и края задаются в процентах, ширина затухания края — от 0 до 50%.
- Анимации: `PosterFadeMillis` допускает 0 для мгновенного показа; `RefreshRotationMillis` задаёт положительную длительность оборота индикатора. Отдельных размытий под кнопками нет.
- Плеер: `PlayerBackgroundColor`, `PlayerClockBackgroundColor`, `PlayerControlsColor`, `PlayerPlayedColor`, `PlayerBufferedColor`, `PlayerUnplayedColor`. Размеры, шрифты, шаг перемотки и поведение плеера остаются общими.

Цвета допускают альфа-канал `#AARRGGBB`. Фон страницы, видеофон и акцент должны быть непрозрачными. Толщина рамки фокуса положительна; отступы и скругления неотрицательны. Для диалогов предпочтительна непрозрачная подложка: иначе через текст может просвечивать содержимое нижнего экрана. Размеры текста и шрифты темой не меняются.

## Проверить изменение

```bash
make check
make theme-config-probe DEVICE=emulator-5554
make button-layout-probe DEVICE=emulator-5554 PROBE_THEME=night
make ui-screens-probe DEVICE=emulator-5554 PROBE_THEME=night
make run-profile DEVICE=emulator-5554
```

`theme-config-probe` проверяет все зарегистрированные стили, сохранение выбора, отключение фона, геометрию и фокус. Дополнительная debug-фикстура проверяет нестандартные значения параметров и передачу цветов нативным элементам Media3. Она отсутствует в пользовательском реестре и в profile/release APK.

Снимки проб необходимо просмотреть: автоматическая проверка не оценивает контраст и удачность сочетания цветов. Проверить пультом диалоги, длинные названия, оба вида списка, возврат из карточки и перезапуск. После изменения оформления плеера запустить реальное видео по инструкции `make player-probe` в [development.md](development.md), просмотреть кадры, паузу, перемотку и переходы.

Производительность сравнивать на одном profile APK и одном устройстве командами `make profile-list` и `make profile-details`; идентификатор темы передаётся через `LIST_APPEARANCE` или `DETAILS_APPEARANCE`. Результаты эмулятора не заменяют замеры на приставке.
