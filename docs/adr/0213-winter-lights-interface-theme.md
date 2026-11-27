# ADR-0213. Тема «Зимние огни»

Дата 2026-11-27. Статус принят. Дополняет [ADR-0161](0161-resource-defined-interface-themes.md) и переиспользует свечение из [ADR-0212](0212-neon-interface-theme.md).

## Контекст

Для новогодней темы нужны размытая ёлка с игрушками, золотой фокус карточки и анимированная гирлянда слева. Элементы должны хорошо читаться на телевизоре при управлении с пульта.

## Решение

У темы «Зимние огни» постоянный фон с украшенной ёлкой у окна справа. Левая часть кадра затемнена под меню и текст. Изображение из APK размывается и затемняется тем же способом, что постеры в теме «Стекло». Для локальной сцены используется размер до 384 пикселей по длинной стороне, чтобы сохранить силуэт ёлки. Готовый фон кешируется для заданных размеров экрана и остаётся видимым при переходах между карточками. Точки над лампочками берутся из карты изображения и плавно меняют яркость отдельным слоем поверх размытости.

При фокусе карточки и кнопки получают золотой контур или заливку с мягким свечением. Тёмный стиль плеера задаётся через `seasonvarPlayerStyle`, а просмотренная часть шкалы использует тот же золотой цвет.

Провод гирлянды входит из-за верхнего левого края экрана, провисает за боковым меню, появляется под ним и уходит за нижний край, сделав петлю. Провод и лампочки размываются один раз для заданных размеров экрана. Поверх них мягко мерцают ореолы огоньков, как на ёлке. Гирлянда видна на всех экранах с боковым меню. При переходе между ними анимация продолжается и перерисовывает только слой гирлянды. Гирлянда рисуется за панелью меню, сохраняет её геометрию и не входит в порядок фокуса.

## Альтернативы и последствия

Статичная гирлянда была бы проще. Мягкое мерцание заметно у края экрана и сохраняет спокойный ритм подсветки во время просмотра каталога. Затемнение фона и непрозрачные панели поддерживают читаемость текста. Фон хранится как локальное WebP-изображение в APK.

## Генерация фона

| Параметр | Значение |
| --- | --- |
| Инструмент | Встроенный ImageGen, генерация по тексту |
| Параметр вызова | `transparent_background=false` |
| Исходное изображение | PNG, 1672 × 941 |
| Ресурс в приложении | [theme_winter_tree.webp](../../app/src/main/res/drawable-nodpi/theme_winter_tree.webp) |
| Упаковка ресурса | `cwebp -q 87 -resize 1280 720` |

Финальный промпт

> Photorealistic horizontal 16:9 winter evening living-room photograph to be used as a dark background for a TV series catalog. Single clear tall real Christmas fir entirely in view in the RIGHT THIRD of the image, from tip at 10 percent height to the tree skirt at 90 percent height, centered at about 76 percent across. The dark green needles form a recognizable tapered silhouette. Decorate the tree with rich burgundy and muted brass glass baubles and dozens of individual tiny warm amber and soft golden fairy lights. The golden points vary in size and brightness and are visible at 7–16 pixel scale in a 1920 by 1080 composition, balanced mostly across the front of the tree. No starburst effects. A dim window just to the right of the tree hints at a deep blue snowy evening. The entire left half is simple dark midnight teal shadow from the same room, a very subdued natural gradient with negative space for a white-text interface. Keep this one coherent low-light photograph with a trace of light on the wooden floor and no collage. Use refined natural lens softness while retaining clearly recognizable tree shape and separable light centers. Elegant warmly lit Christmas magazine interior, restrained palette, warm tree and cool room. Leave 8 percent safety space around the tree for widescreen cropping. No people, no text, no overlays, no garland on screen edges, no large gifts, no fireplaces, no visible snow indoors. One whole scene.
