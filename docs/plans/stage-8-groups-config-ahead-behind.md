# План Этапа 8 — «Группировка, конфиг и ahead/behind»

Ветка: `stage/8-groups-config-ahead-behind` (уже создана от `master`). PR в `master` по workflow из `CLAUDE.md` (Opus план+код → Sonnet тесты → Opus `/code-review` → до 3 итераций).
Исполнитель: Opus-агент (код), затем Sonnet (тесты), затем `/code-review`. **Fable не используется.**

---

## 0. Рекомендация по разбивке (прочитать первым)

**Рекомендую разбить три пункта на три последовательных PR внутри одного этапа, а не мержить одним диффом.** Обоснование:

- **Конфиг-файл** (пункт 2) архитектурно независим от группировки и ahead/behind: он трогает `scan/`-резолвинг корней, `Main.kt` и `WorktreeService.defaultWorktreePath`, но не таблицу.
- **Группировка вывода** (пункт 1) — чисто `ui/`-изменение (рендер), не трогает git-слой.
- **Ahead/behind + возраст** (пункт 3) — новые git-вызовы в `git/`, новая параллелизация в `scan/`, две новые колонки в `ui/TableLayout.kt`. Самый крупный и рискованный кусок (производительность, деградация колонок).

Раздельные PR дали бы более узкое ревью и понятный откат, если что-то из трёх окажется спорным по UX (особенно колонки ahead/behind в тесной таблице). **Но раз пользователь попросил взять их вместе — план ниже единый, с фазами, каждая из которых самодостаточна и может при желании стать отдельным PR.** Порядок фаз выбран так, чтобы независимые части шли первыми (конфиг → группировка → ahead/behind), а самая рискованная (колонки в таблице) — последней, когда группировка уже упростила визуальный ряд.

---

## 1. Цель

Взять три отложенных пункта из `docs/POST_MVP_PLAN.md` и реализовать их одним этапом:

1. **Группировка вывода `scan` по репозиторию** — на реальном портфеле (25 репо / 27 worktree) у репо с несколькими worktree имя дублируется по строкам, читается шумно. Сделать повтор имени репо менее шумным (не повторять имя на 2-й+ строке группы). Сортировку/порядок НЕ трогать — он уже стабилен (`RepoScanner.findRepos` сортирует репо по имени, git отдаёт worktree в стабильном порядке).
2. **Опциональный конфиг `~/.config/gwm/config.toml`** — список корней сканирования, дефолтный шаблон пути новых worktree, цветовая схема. При отсутствии файла всё работает как сейчас; явные CLI-флаги приоритетнее конфига.
3. **Ahead/behind + возраст worktree** — новые колонки: число коммитов ahead/behind upstream и возраст последнего коммита. Новые git-вызовы на каждый worktree, параллелить по паттерну `ScanService`. У worktree без upstream ahead/behind пустой (`—`), не ошибка. Колонки встраиваются в лестницу деградации Этапа 7 и деградируют РАНЬШЕ, чем PATH/BRANCH.

---

## 2. Принятые решения (и почему)

### Р1. Группировка: не повторять имя репо на 2-й+ строке группы

- В `scan`-таблице колонка «Репозиторий» на 2-й и последующих worktree того же репо становится **пустой строкой**. Аналог `git log --oneline` группировки: глаз видит границу группы по появлению непустого имени.
- Порядок строк НЕ меняется: `ScanService` уже возвращает worktree, сгруппированные по репо (репо обходятся по имени, внутри — как отдал git; `flatMap` сохраняет порядок). Группировка — чисто визуальная: «схлопнуть» повтор в уже соседних строках, а не пересортировать.
- **Реализация — в проекции строк** (`rowsAggregated`), а не в рендере ячеек: добавляем в `OverviewRow` флаг `repoIsGroupStart: Boolean` (true у первой строки каждого репо). В `plainCell(REPO)` при `!repoIsGroupStart` возвращаем `""`.
- **Важно для замера ширины:** natural-ширина колонки REPO по-прежнему считается по НЕпустым именам (первая строка группы). Пустые ячейки на ширину не влияют — берётся `max` по всем, и первая строка каждой группы даёт полное имя. Ничего не ломается.
- **Взаимодействие с выбросом колонки REPO по ширине (Этап 7):** когда REPO выброшена, имя репо и так живёт в первом сегменте относительного пути — группировка неактуальна, флаг просто игнорируется. Когда путь НЕ относителен (`~/...`/абсолютный) и REPO выброшена, к пути дописывается префикс `<repo>: ` (уже есть в Этапе 7) — на КАЖДОЙ строке, префикс не схлопываем (иначе потеряется принадлежность у строки без соседей сверху в узком выводе). Схлопывание имени — только в самой колонке REPO, только когда она присутствует.
- **Компактный режим (Р5 Этапа 7):** там нет колонки репо, группировка не применяется — оставляем как есть.

Отвергнуто: тонкий горизонтальный разделитель между группами — в компактной таблице Этапа 7 (`cellBorders = LEFT_RIGHT`) горизонтальных линий между строками нет намеренно (27 worktree → 31 строка), возвращать их выборочно между группами — регресс по компактности и усложнение рендера. Пустая ячейка имени — дешевле и достаточна.

### Р2. Конфиг: ручной парсер минимального TOML-подмножества, без новой зависимости

**Решение: НЕ добавлять TOML-библиотеку. Написать ручной парсер плоского подмножества.**

Обоснование:
- `docs/TECHNICAL_PLAN.md` §6 и вся философия проекта — **чисто локальный инструмент без лишних рантайм-зависимостей** («вся ценность которого — быть лёгким», $0 эксплуатация). Каждая новая зависимость — против этого. В `build.gradle.kts` сейчас ровно три рантайм-зависимости (Mordant, Clikt, coroutines); TOML-либа (`tomlkt`, `ktoml`, `4koma`) — четвёртая ради разбора ~10 строк конфига.
- Нужное подмножество тривиально: **плоские `key = value`** (строки, целые, булевы) + **один уровень массивов строк** (`roots = ["/a", "/b"]`) + опционально **одна секция** (`[colors]`) как префикс к ключам. Без вложенных таблиц, дат, multiline-строк, эскейпов внутри строк (кроме тримминга кавычек). Это ~60–80 строк чистого Kotlin с исчерпывающим юнит-тестом — меньше, чем интеграция и изучение чужого API.
- Парсер — **чистая функция** `String -> ParsedToml` (как `WorktreeParser`), тестируется без ФС. Это ложится в архитектуру проекта («чистая логика отделена от I/O»).

**Явно НЕ делаем** (границы подмножества, зафиксировать в KDoc парсера):
- вложенные таблицы (`[a.b]`), массивы таблиц (`[[x]]`);
- даты/время, числа с плавающей точкой (нам не нужны);
- multiline-строки (`"""..."""`), эскейп-последовательности внутри строк;
- inline-таблицы (`{ a = 1 }`).
Встретив синтаксис вне подмножества — понятная ошибка с номером строки, НЕ тихое проглатывание и НЕ краш (см. тест-кейсы).

Формат конфига (пример; всё опционально):
```toml
# ~/.config/gwm/config.toml
roots = ["~/Projects/ai-projects", "~/work/repos"]
worktree-path-template = "{parent}/{repo}-{branch}"

[colors]
clean = "green"
dirty = "yellow"
muted = "gray"
```

- **`roots`** — список корней сканирования. Пока `gwm` поддерживает ОДИН корень за запуск (Этап 7 сводит все источники к одному). Поэтому на Этапе 8 из конфига берём **первый существующий корень** как ещё один источник в лестнице приоритетов `RootSelection`, с самым НИЗКИМ приоритетом. Полноценный мульти-корень (сканировать несколько корней за раз) — отдельный пункт, вне scope; но формат `roots` как массив закладываем сразу, чтобы не ломать конфиг потом. **Документируем: сейчас используется только первый элемент.**
- **`worktree-path-template`** — шаблон пути нового worktree с плейсхолдерами `{parent}` (родитель главного checkout), `{repo}` (имя репо), `{branch}` (имя ветки, слэши → `-`). Дефолт (при отсутствии) — текущее поведение `WorktreeService.defaultWorktreePath`: `{parent}/{repo}-{branch}`.
- **`[colors]`** — имена цветов Mordant для статусов. Дефолты — текущие зашитые (`clean=brightGreen`, `dirty=brightYellow`, `muted=gray`). Неизвестное имя цвета → предупреждение в stderr + дефолт (не краш).

**Приоритет источников корня (от высшего к низшему):**
1. явные CLI-источники (позиционный `ROOT`, `scan --root`, корневой `--root`) — как сейчас, конфликт между ними = ошибка (Этап 7);
2. `$GWM_ROOT`;
3. `roots[0]` из конфига;
4. дефолт `~/Projects/ai-projects`.

Это сохраняет инвариант «явный флаг перебивает конфиг» и «конфиг опционален». Конфликт-детекция (Этап 7, `RootSelection`) остаётся ТОЛЬКО между явными CLI-источниками — конфиг и env участвуют как молчаливый fallback, не как конфликтующие источники (иначе любой пользователь с конфигом и флагом получал бы ошибку). Реализация: `RepoScanner.resolveRoot` расширяется параметром `configRoot: String?`, вставленным в цепочку между `env("GWM_ROOT")` и `defaultRoot()`.

### Р3. Ahead/behind — новый git-вызов в `git/`, параллелится в `scan/`

- **Команда:** `git rev-list --left-right --count <branch>@{upstream}...<branch>` внутри директории worktree. Вывод — две колонки, разделённые табом: `<behind>\t<ahead>` (левая сторона `A...B` = коммиты в A но не в B; при `@{upstream}...HEAD` левое = behind, правое = ahead). **Проверить направление на артефакте** и зафиксировать в KDoc — легко перепутать. Экв. форма `--count HEAD...@{upstream}` даёт `ahead\tbehind` (левое = HEAD-only = ahead). Выбрать одну, тест зафиксирует семантику.
- **Нет upstream → не ошибка.** `git` при отсутствии upstream выходит с кодом 128 (проверено на этом репо: `fatal: вышестоящая ветка не настроена`). Это ожидаемый частый случай (detached, локальные ветки без upstream). `GitResult.ok == false` → возвращаем `AheadBehind = null` (рендер: `—`), НЕ добавляем в `result.errors`, НЕ шумим в stderr. Это отличается от «репо сломан» (весь repo падает в `RepoError` в `ScanService`) — здесь падает один worktree по ожидаемой причине.
- **Возраст worktree.** `git log -1 --format=%ct` внутри worktree → unix-timestamp последнего коммита. **Вычисляем относительную строку САМИ** (`"5м"`, `"3ч"`, `"2д"`, `"4нед"`, `"6мес"`, `"1г"`), НЕ используем `%cr`: `%cr` локализован (на этом репо отдал «25 минут назад» по-русски) и нестабилен для тестов/ширины. Своя чистая функция `AgeFormat.relative(nowEpoch, commitEpoch): String` — детерминированная, юнит-тестируемая, компактная (колонка узкая). Пустой репо без коммитов / detached без HEAD → `—`.
- **Слои.** Новый метод в `WorktreeService`: `withAheadBehindAndAge(worktrees): List<Worktree>` — по паттерну `withDirtyFlags`/`withOrphanStatus` (отдельный шаг, дорогой, наполняет новые поля). Внутри — вызовы `git` через тот же `this.git` runner (инъектируемый, тестируемый фейком). Парсинг вывода `rev-list` — чистая функция `parseAheadBehind(stdout): AheadBehind?`.
- **Модель.** В `Worktree` добавить поля: `aheadBehind: AheadBehind? = null` (data class `AheadBehind(val ahead: Int, val behind: Int)`), `lastCommitEpoch: Long? = null`. Дефолт null = «не считали» (как `dirty`). НЕ ломает существующий single-repo код (`list`/`create`/`remove` их не заполняют, рендер показывает `—`).

### Р4. Производительность: параллелить новые вызовы, оценить влияние

- Сейчас `scan` ~1.4с на 25 репо. Новые вызовы: **2 git-вызова × 27 worktree = 54 новых процесса** (`rev-list` + `log -1`). Плюс уже есть `withDirtyFlags` (status на каждый worktree) и `withOrphanStatus`. Последовательно это удвоит/утроит время.
- **Параллелизация — по существующему паттерну `ScanService`.** `withAheadBehindAndAge` вызывается внутри `aggregateOne` (уже на `Dispatchers.IO`, по одному репо на корутину). Внутри одного репо worktree можно обрабатывать последовательно (их обычно 1–3) — параллелизм по репо уже даёт основной выигрыш. **Если замер покажет регресс > ~2×** — распараллелить и worktree внутри репо (`worktrees.map { async(Dispatchers.IO) {...} }.awaitAll()` внутри `aggregateOne`, тоже по паттерну `scanAsync`). Решение принять **по факту замера на артефакте**, а не заранее.
- **Обязательный замер:** `time COLUMNS=200 $B scan` на реальном портфеле `~/Projects/ai-projects` до и после. Зафиксировать в PR. Порог приемлемости — субъективный (это повседневная утилита); ориентир — не хуже ~3с.

### Р5. Деградация новых колонок: раньше PATH/BRANCH

Две новые колонки — `AHEAD_BEHIND` и `AGE` — дополнительная, не жизненно важная информация. Встраиваются в лестницу `TableLayout` (Этап 7) так, чтобы деградировать **РАНЬШЕ** PATH и BRANCH:

- Новый порядок выброса в лестнице (сверху вниз, пока не влезет):
  1. Полный набор: `REPO, BRANCH, STATUS, ORPHAN, AGE, AHEAD_BEHIND, PATH`.
  2. Убрать `AHEAD_BEHIND` (самая узкоспециальная).
  3. Убрать `AGE`.
  4. Убрать `REPO` (как в Этапе 7 — самый крупный выигрыш ширины).
  5. Убрать `ORPHAN` (⚠ → в ветку).
  6. Сжать `BRANCH` до `MIN_BRANCH`.
  7. `PATH` не ниже `MIN_PATH`.
  8. Компактный список.

  **Ключевое отличие от Этапа 7:** ступени 2–3 (выброс AHEAD_BEHIND, AGE) идут ПЕРЕД выбросом REPO. То есть на узком терминале мы жертвуем новыми колонками раньше, чем структурной информацией (репо/путь). Это ровно то, что просил пользователь: «деградировать раньше, чем PATH/BRANCH».
- `AHEAD_BEHIND` и `AGE` — **фиксированной небольшой ширины**, НЕ сжимаются посимвольно (как STATUS/ORPHAN): узкие колонки, сжатие сделает нечитаемыми — вместо этого выбрасываются целиком. Natural-ширина = `max(заголовок, макс. ячейка)`; заголовки короткие (`±` / `A/B` для ahead-behind, `Возраст` или `Age`/`⏱` для возраста — выбрать компактные, зафиксировать).
- **Колонки рендерятся только если есть данные** (как ORPHAN в Этапе 7): `AHEAD_BEHIND` — если хоть у одной строки `aheadBehind != null`; `AGE` — если хоть у одной `lastCommitEpoch != null`. В single-repo `list` эти поля не заполняются → колонки не появляются (обратная совместимость).
- Обновить сигнатуру `TableLayout.plan` не нужно — она уже принимает `wanted: List<OverviewColumn>` и `natural: Map<...>`; расширить только `enum OverviewColumn` и лестницу `ladder` внутри `plan`. **Внимание:** текущая `ladder` в `TableLayout.plan` жёстко прописывает `wanted - REPO`, `wanted - REPO - ORPHAN`. Её нужно переписать под новый порядок выброса (2–5 выше). Это самая тонкая правка — покрыть sweep-тестом инварианта (как тест 18 Этапа 7).

### Р6. Цветовая схема из конфига — тонкий слой, дефолты неизменны

- Текущие цвета зашиты в `WorktreeTable` (`brightGreen/brightYellow/gray`) и частично в `InteractiveScreen`, `Main.kt`. Полный вынос всех цветов в конфиг — большой рефактор; на Этапе 8 ограничиваемся **тремя семантическими ролями статуса** (clean/dirty/muted), которые реально повторяются и которые естественно кастомизировать.
- Резолвинг имени цвета → `TextColors` — чистая функция `Colors.resolve(name: String?): TextStyle` с таблицей (`"green"→brightGreen`, `"yellow"→brightYellow`, `"gray"/"grey"→gray`, `"red"`, `"blue"`, `"cyan"`, …). Неизвестное/null → дефолт роли. Схема передаётся в `WorktreeTable.render*` как параметр с дефолтом = текущая зашитая схема, **чтобы существующие рендер-тесты Этапа 7 не требовали правок** (они вызывают `render` без схемы → дефолт → тот же вывод; к тому же тесты гоняются на `AnsiLevel.NONE`, цвет не виден).
- **Ahead/behind цвет:** ahead > 0 — нейтрально/muted, behind > 0 — предупреждающе (нужно подтянуть). Но чтобы не раздувать конфиг — переиспользуем роли clean/dirty/muted, отдельные ключи не заводим.

---

## 3. Границы — что НЕ трогаем

- **Контракт `--print-path`** (`PrintPath.emit`) — печатает АБСОЛЮТНЫЙ путь сырым `println`. Ни группировка, ни колонки, ни конфиг сюда не проникают. Конфиг может лишь добавить ещё один fallback-источник корня для резолвинга (тот же путь, что `--root`), но формат вывода не меняется. Проверяется существующим `PrintPathIntegrationTest` (тест 47 Этапа 7 — путь абсолютный, без `~`/`…`/ANSI).
- **Orphaned-эвристики** (`git/OrphanClassifier.kt`, `git/OrphanStatus.kt`) — не трогаем. Ahead/behind — ОТДЕЛЬНАЯ информация, НЕ входит в orphaned-сигнал (upstream уже используется в orphaned как булев `noUpstream`; число коммитов — независимая колонка).
- **Полноценный TOML общего назначения** — не делаем, только подмножество под нужды gwm (Р2).
- **`--format=json`/`--porcelain`** — вне scope (отдельный пункт POST_MVP).
- **Мульти-корень** (сканировать несколько корней за раз) — вне scope; из `roots[]` берём первый существующий.
- **Автоочистка orphaned, alternate-screen TUI, сортировка/фильтрация** — вне scope.
- **`RepoScanner.findRepos`** — семантика поиска репо не меняется; порядок (по имени) не меняется.
- **`gradle.properties` / toolchain / configuration-cache** — не трогаем.
- **Не коммитить и не пушить** без явной команды пользователя.

---

## 4. Изменения по файлам

### Пункт 2 — Конфиг

#### Новый: `src/main/kotlin/dev/alkom/gwm/config/TomlLite.kt`
Чистый парсер подмножества TOML. Без ФС, без Mordant.
```kotlin
package dev.alkom.gwm.config

/** Ошибка парсинга с номером строки (1-based). */
class TomlParseException(val line: Int, message: String) : Exception("строка $line: $message")

/** Плоское представление разобранного TOML-подмножества. */
data class ParsedToml(
    val strings: Map<String, String>,        // ключ (с префиксом секции: "colors.clean") -> строка
    val stringLists: Map<String, List<String>>, // ключ -> массив строк
) {
    fun string(key: String): String? = strings[key]
    fun stringList(key: String): List<String>? = stringLists[key]
}

object TomlLite {
    /**
     * Разбирает ПОДМНОЖЕСТВО TOML: строки/целые/булевы как значения, массивы строк,
     * одна секция [name] как префикс ключей. НЕ поддерживает: вложенные таблицы,
     * массивы таблиц, даты, float, multiline/escaped-строки, inline-таблицы —
     * встретив их, бросает TomlParseException (не молча игнорирует).
     * Комментарии (# ...) и пустые строки пропускаются.
     */
    fun parse(text: String): ParsedToml
}
```
Правила: строки в двойных или одинарных кавычках → снять кавычки; голое значение → строка as-is (числа/булевы храним как строку, интерпретирует потребитель); `[section]` меняет текущий префикс; `key = ["a", "b"]` (в т.ч. на нескольких строках — опционально, проще одна строка) → массив; дубль ключа → ошибка; синтаксис вне подмножества (`[[`, `{`, `"""`) → ошибка с номером строки.

#### Новый: `src/main/kotlin/dev/alkom/gwm/config/GwmConfig.kt`
```kotlin
package dev.alkom.gwm.config

import java.io.File

/** Разобранная конфигурация gwm. Все поля опциональны; отсутствие файла = все null/дефолты. */
data class GwmConfig(
    val roots: List<String> = emptyList(),
    val worktreePathTemplate: String? = null,
    val colors: ColorScheme = ColorScheme.DEFAULT,
) {
    /** Первый существующий корень из roots (для одно-корневого scan Этапа 8), или null. */
    fun primaryRoot(home: String = System.getProperty("user.home")): String?

    companion object {
        val EMPTY = GwmConfig()
        /** Путь по умолчанию: $XDG_CONFIG_HOME/gwm/config.toml или ~/.config/gwm/config.toml. */
        fun defaultPath(env: (String) -> String? = System::getenv,
                        home: String = System.getProperty("user.home")): File
        /** Читает и парсит конфиг. Файла нет → EMPTY (не ошибка). Битый TOML → CliktError с внятным текстом. */
        fun load(path: File = defaultPath()): GwmConfig
        /** Чистое отображение ParsedToml -> GwmConfig, без ФС (тестируется без файла). */
        fun from(parsed: ParsedToml): GwmConfig
    }
}

/** Семантические роли цвета. Имена резолвятся в config/Colors.kt. */
data class ColorScheme(val clean: String?, val dirty: String?, val muted: String?) {
    companion object { val DEFAULT = ColorScheme(null, null, null) }
}
```
`load`: если файл не существует → `EMPTY`. Если существует, но не читается / битый TOML → `TomlParseException` оборачивается в `CliktError("Ошибка в конфиге ${path}: ...")` (внятная ошибка, не краш, ненулевой exit — контракт SKILL «Exit-коды»). Пустой файл → `EMPTY`. Частичный файл (только `roots`, без colors) → остальные поля дефолтные.

#### Новый: `src/main/kotlin/dev/alkom/gwm/config/Colors.kt`
```kotlin
package dev.alkom.gwm.config
import com.github.ajalt.mordant.rendering.TextStyle

/** Резолвинг имён цветов в стили Mordant. Неизвестное имя → fallback роли (+ предупреждение — на стороне вызова). */
object Colors {
    fun resolve(name: String?, fallback: TextStyle): TextStyle
    fun isKnown(name: String?): Boolean
}
```

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/scan/RepoScanner.kt`
`resolveRoot` получает новый параметр `configRoot: String?` (fallback между env и default):
```kotlin
fun resolveRoot(
    override: String? = null,
    configRoot: String? = null,
    env: (String) -> String? = System::getenv,
): File {
    override?.takeIf { it.isNotBlank() }?.let { return File(expandTilde(it)).absoluteFile }
    env("GWM_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(expandTilde(it)).absoluteFile }
    configRoot?.takeIf { it.isNotBlank() }?.let { return File(expandTilde(it)).absoluteFile }
    return defaultRoot()
}
```
Все существующие вызовы `resolveRoot(x)` продолжают работать (новый параметр с дефолтом null).

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/git/WorktreeService.kt`
`defaultWorktreePath(branch)` учитывает шаблон из конфига. Добавить перегрузку/параметр:
```kotlin
fun defaultWorktreePath(branch: String, template: String? = null): File
```
При `template == null` — текущее поведение (`{parent}/{repo}-{branch}`). Иначе подставить `{parent}`, `{repo}`, `{branch}` (branch: слэши → `-`). Плейсхолдер-рендер — чистая функция, тестируемая: вынести в `WorktreePathTemplate.render(template, parent, repo, branch): File` (можно в `config/` или рядом). Неизвестный плейсхолдер оставить как есть или ошибка — решить, зафиксировать тестом.

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/Main.kt`
- `Gwm` / команды загружают конфиг один раз: `val config = GwmConfig.load()` (в `Gwm.run()` положить в `GwmGlobals`, чтобы подкоманды видели через `currentContext.obj` — как `root` в Этапе 7). Расширить `GwmGlobals`:
  ```kotlin
  data class GwmGlobals(val root: String?, val config: GwmConfig)
  ```
- `ScanCommand.run()`: `configRoot = globals.config.primaryRoot()`, передать в `resolveRoot(chosen, configRoot)`. **Важно:** конфиг НЕ участвует в конфликт-детекции `RootSelection` (только явные CLI-источники), он идёт как fallback в `resolveRoot`.
- `PrintPath.emit(query, root, configRoot)` — добавить configRoot тем же путём (fallback), формат вывода не меняется.
- `CreateCommand.run()`: `service.defaultWorktreePath(branch, globals.config.worktreePathTemplate)`.
- Цветовая схема: где рендерятся статусы (`WorktreeTable`, `ListCommand`/`ScanCommand` шапки) — прокинуть `config.colors` (см. Р6). Минимально — только в `WorktreeTable.render*`.
- Загрузка конфига обёрнута так, что битый файл → `CliktError` (ненулевой exit) ещё до сканирования.

### Пункт 1 — Группировка

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/ui/WorktreeTable.kt`
- В `data class OverviewRow` добавить `val repoIsGroupStart: Boolean = true` (для single-repo `list` всегда true — там нет группировки, колонки REPO нет).
- `rowsAggregated`: вычислить `repoIsGroupStart` = имя репо отличается от предыдущей строки (`prev?.repo != agg.repo`). Порядок строк НЕ меняется.
- `plainCell(REPO)`: `if (r.repoIsGroupStart) r.repo ?: "" else ""`.
- `naturalWidths(REPO)`: без изменений — `max` по всем ячейкам, первая строка группы даёт полное имя, пустые не влияют.
- `styledCell(REPO)`: пустая строка → пустая ячейка (не `bold("")`).
- Проверить, что схлопывание НЕ применяется, когда REPO выброшена по ширине (там колонки нет вовсе).

### Пункт 3 — Ahead/behind + возраст

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/git/Worktree.kt`
Добавить поля:
```kotlin
val aheadBehind: AheadBehind? = null,   // null = не считали или нет upstream
val lastCommitEpoch: Long? = null,      // unix-время последнего коммита; null = не считали / нет коммитов
```
Новый data class (в этом же файле или рядом в `git/`):
```kotlin
data class AheadBehind(val ahead: Int, val behind: Int)
```

#### Новый: `src/main/kotlin/dev/alkom/gwm/git/AheadBehind.kt` (или внутри Worktree.kt)
Чистый парсер вывода `git rev-list --left-right --count`:
```kotlin
/** Парсит "<left>\t<right>" в AheadBehind. Пустой/битый ввод -> null. Семантика сторон зафиксирована KDoc + тестом. */
fun parseAheadBehind(revListStdout: String): AheadBehind?
```

#### Новый: `src/main/kotlin/dev/alkom/gwm/ui/AgeFormat.kt`
```kotlin
/** Компактная локаль-независимая относительная давность: "5м","3ч","2д","4нед","6мес","1г". */
object AgeFormat {
    fun relative(nowEpochSec: Long, commitEpochSec: Long): String
}
```
Чистая функция, детерминированный юнит-тест.

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/git/WorktreeService.kt`
Новый шаг-аннотатор (по паттерну `withDirtyFlags`/`withOrphanStatus`):
```kotlin
/**
 * Наполняет aheadBehind (git rev-list --left-right --count <branch>@{upstream}...<branch>)
 * и lastCommitEpoch (git log -1 --format=%ct) для каждого worktree. Дорого (2 git-вызова
 * на worktree) → отдельный шаг, как withDirtyFlags. Нет upstream (git exit != 0) →
 * aheadBehind = null, НЕ ошибка. bare/detached без HEAD → оба null.
 */
fun withAheadBehindAndAge(worktrees: List<Worktree>): List<Worktree>
```
Вызовы git — через `this.git` runner (инъектируемый фейком в юнит-тестах). НЕ добавлять неудачу no-upstream в ошибки.

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/scan/ScanService.kt`
В `aggregateOne` добавить шаг:
```kotlin
val flagged = service.withAheadBehindAndAge(
    service.withOrphanStatus(service.withDirtyFlags(service.list()))
)
```
Параллелизм: `aggregateOne` уже на `Dispatchers.IO` по одному репо. Если замер (Р4) покажет регресс — распараллелить и worktree внутри `withAheadBehindAndAge`/`aggregateOne` тем же паттерном `async{}.awaitAll()` внутри `aggregateOne`. **Решение по факту замера.** Ошибки no-upstream не всплывают в `result.errors` (падение в `Worktree.aheadBehind = null`, а не исключение).

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/ui/TableLayout.kt`
- `enum OverviewColumn` → добавить `AHEAD_BEHIND, AGE`.
- Переписать `ladder` в `plan` под новый порядок выброса (Р5): выбрасывать `AHEAD_BEHIND`, затем `AGE`, ПОТОМ `REPO`, `ORPHAN`. Текущая `ladder` = `[wanted, wanted-REPO, wanted-REPO-ORPHAN]` заменить на:
  ```
  wanted
  wanted - AHEAD_BEHIND
  wanted - AHEAD_BEHIND - AGE
  wanted - AHEAD_BEHIND - AGE - REPO
  wanted - AHEAD_BEHIND - AGE - REPO - ORPHAN
  ```
  (`.distinct()` уберёт дубли, если каких-то колонок нет).
- `AHEAD_BEHIND`/`AGE` не сжимаются (как STATUS/ORPHAN) — только целиком выбрасываются; в `assign` они входят в `nonPathSum()` в natural-ширине.
- Sweep-инвариант (тест 18-аналог) обновить на новый набор колонок.

#### Изменённый: `src/main/kotlin/dev/alkom/gwm/ui/WorktreeTable.kt`
- `OverviewRow` → добавить `aheadBehind: AheadBehind?`, `lastCommitEpoch: Long?`.
- `wanted` в `renderRows`: добавить `AGE` (если `rows.any { it.lastCommitEpoch != null }`) и `AHEAD_BEHIND` (если `rows.any { it.aheadBehind != null }`), в правильном порядке относительно PATH.
- `header`: заголовки для AGE (`Возраст`) и AHEAD_BEHIND (компактный, напр. `±` или `↑↓`; выбрать, зафиксировать — учесть, что это влияет на natural-ширину).
- `plainCell(AGE)`: `lastCommitEpoch?.let { AgeFormat.relative(now, it) } ?: "—"`.
- `plainCell(AHEAD_BEHIND)`: `aheadBehind?.let { "↑${it.ahead} ↓${it.behind}" } ?: "—"` (формат компактный; `↑`/`↓` — глифы шириной 1, проверить на артефакте — при сомнении заменить на `+N/-N`).
- `styledCell`: возраст — muted; ahead/behind — behind>0 предупреждающе (dirty-цвет), иначе muted. Через `config.colors` (Р6).
- `now` (текущее время) прокинуть в `render*` как параметр с дефолтом `System.currentTimeMillis()/1000` — чтобы рендер-тесты были детерминированны (передают фиксированный `now`).
- `renderCompact` (Р5): опционально дописать возраст в строку 2 (`… · 3д`), ahead/behind не показывать (места нет). Решить, зафиксировать тестом.

---

## 5. Порядок реализации (по фазам)

Каждая фаза самодостаточна и может стать отдельным PR (см. Р0).

1. **Фаза A — Конфиг (пункт 2).**
   - A1. `TomlLite` + `TomlLiteTest` (чистый парсер, исчерпывающие кейсы подмножества и ошибок).
   - A2. `GwmConfig` (+ `ColorScheme`) + `Colors` + `WorktreePathTemplate` + тесты (чистые, без ФС через `from(parsed)`; `load` — с `@TempDir`-файлом).
   - A3. Интеграция в `RepoScanner.resolveRoot` (configRoot fallback) + тесты приоритета.
   - A4. Интеграция в `Main.kt` (`GwmGlobals.config`, загрузка, битый файл → `CliktError`), `CreateCommand` (шаблон пути), цвета в `WorktreeTable` (дефолт = текущая схема).
   - A5. Ручная проверка: конфиг отсутствует / пустой / частичный / битый; `roots[0]` как fallback; CLI-флаг перебивает конфиг.
2. **Фаза B — Группировка (пункт 1).**
   - B1. `OverviewRow.repoIsGroupStart` + `rowsAggregated` + `plainCell(REPO)` схлопывание.
   - B2. Обновить/дополнить `WorktreeTableRenderTest` (группировка не ломает natural-ширину, порядок, выброс REPO).
3. **Фаза C — Ahead/behind + возраст (пункт 3).**
   - C1. `Worktree` новые поля + `AheadBehind` + `parseAheadBehind` + тест парсера (+ семантика сторон).
   - C2. `AgeFormat.relative` + тест.
   - C3. `WorktreeService.withAheadBehindAndAge` + юнит-тест на фейк-runner (upstream есть / нет / detached / нет коммитов).
   - C4. `ScanService.aggregateOne` — новый шаг; интеграционный тест на реальном временном репо с upstream и без.
   - C5. `TableLayout` — новые колонки + переписанная лестница + sweep-инвариант.
   - C6. `WorktreeTable` — рендер новых колонок, детерминированный `now`, деградация.
   - C7. **Замер производительности** на артефакте (Р4); при регрессе — параллелить worktree внутри репо.
4. **Фаза D — Ручная проверка на артефакте** (раздел 7) — ВСЯ таблица, обязательно.
5. **Фаза E — Документация** (раздел 8).

---

## 6. Тест-кейсы (полный список)

### `TomlLiteTest.kt` (юнит, чистый)
1. плоские `key = "value"` (двойные кавычки) → строка без кавычек
2. одинарные кавычки → строка без кавычек
3. голое значение (число/bool) → строка as-is
4. массив строк `roots = ["/a", "/b"]` → список из двух
5. пустой массив `roots = []` → пустой список
6. секция `[colors]` + ключ `clean = "green"` → ключ `"colors.clean"`
7. комментарии `# ...` и пустые строки пропускаются
8. дубль ключа → `TomlParseException` с номером строки
9. синтаксис вне подмножества (`[[x]]`) → `TomlParseException`, номер строки
10. inline-таблица `{ a = 1 }` → `TomlParseException`
11. multiline `"""` → `TomlParseException`
12. пустой ввод → пустой `ParsedToml`
13. значение без `=` (мусорная строка) → ошибка с номером строки

### `GwmConfigTest.kt` (юнит + `@TempDir`)
14. `from(EMPTY parsed)` → все дефолты
15. только `roots` → `worktreePathTemplate == null`, `colors == DEFAULT`
16. `primaryRoot` возвращает первый СУЩЕСТВУЮЩИЙ корень (первый несуществующий пропускается) — или решить «первый как есть» и зафиксировать; тест под выбранное правило
17. `roots` с `~` → раскрывается (через `expandTilde`)
18. `load` несуществующего файла → `EMPTY`, не ошибка
19. `load` пустого файла → `EMPTY`
20. `load` частичного файла (только colors) → roots пуст, template null
21. `load` битого TOML → `CliktError` (ненулевой контракт), текст содержит путь
22. `defaultPath`: `$XDG_CONFIG_HOME` уважается, иначе `~/.config/gwm/config.toml`

### `ColorsTest.kt` (юнит)
23. известное имя `"green"` → соответствующий стиль
24. неизвестное имя → fallback
25. null → fallback
26. `"grey"` и `"gray"` — оба валидны

### `WorktreePathTemplateTest.kt` (юнит)
27. template null → дефолт `{parent}/{repo}-{branch}`
28. кастомный template с `{branch}`, слэши → `-`
29. плейсхолдеры подставляются корректно (`{parent}`, `{repo}`, `{branch}`)

### `RepoScannerTest.kt` (дополнить)
30. `resolveRoot(override=null, configRoot="/x")` без env → `/x`
31. приоритет: `override` перебивает `configRoot`
32. приоритет: `GWM_ROOT` перебивает `configRoot`
33. `configRoot` с `~` раскрывается
34. существующие кейсы `expandTilde`/`resolveRoot` Этапа 7 — сохранить зелёными

### `ScanCommandTest.kt` (дополнить)
35. `--root=X` перебивает `roots` из конфига (флаг приоритетнее) — через `GwmGlobals` с фейк-конфигом или временный конфиг-файл
36. битый конфиг → `test(...)` даёт `statusCode != 0`, сообщение про конфиг
37. существующие кейсы 36–41 Этапа 7 — сохранить зелёными (конфликт корней ТОЛЬКО между CLI-источниками, конфиг не считается конфликтом)

### `parseAheadBehindTest.kt` (юнит)
38. `"3\t5"` → семантика (зафиксировать: behind=3, ahead=5 ИЛИ наоборот — согласно выбранной команде) + тест ловит инверсию
39. `"0\t0"` → ahead=0, behind=0 (up-to-date, НЕ null — upstream есть, просто синхронно)
40. пустой/битый ввод → null

### `AgeFormatTest.kt` (юнит)
41. < часа → минуты (`"5м"`)
42. часы (`"3ч"`)
43. дни (`"2д"`)
44. недели/месяцы/годы — пороги зафиксировать тестом
45. только что (0 сек) → `"0м"` или `"сейчас"` (решить)

### `WorktreeServiceAheadBehindTest.kt` (юнит на фейк-runner)
46. upstream настроен, ahead/behind → поля наполнены
47. нет upstream (runner отдаёт `ok=false`) → `aheadBehind == null`, НЕ исключение
48. detached / нет ветки → `aheadBehind == null`
49. `lastCommitEpoch` парсится из `%ct`; нет коммитов (`log` fail) → null
50. no-upstream НЕ добавляет worktree в ошибки (метод не бросает)

### `ScanServiceIntegrationTest.kt` (дополнить, реальный git `@TempDir`)
51. репо с upstream (создать «remote» через `git init --bare` + push/track): `aheadBehind != null`, значения корректны после локального коммита сверх upstream
52. репо без upstream: `aheadBehind == null`, `result.errors` пуст (частичная неудача не всплывает)
53. `lastCommitEpoch` заполнен, не null, у всех worktree с коммитом

### `TableLayoutTest.kt` (дополнить/обновить)
54. width=200, полный набор с AGE+AHEAD_BEHIND: все колонки, PATH == natural
55. width, где AHEAD_BEHIND выброшена ПЕРВОЙ (раньше REPO): REPO ещё присутствует, AHEAD_BEHIND нет
56. следующая ступень: AGE выброшена, REPO ещё есть
57. далее REPO выброшена (после AGE/AHEAD_BEHIND) — порядок Р5
58. AGE/AHEAD_BEHIND не сжимаются посимвольно (либо natural, либо выброшены)
59. sweep-инвариант `for (w in 30..220)`: `sum(assigned)+chrome(n) <= w`, при `!compact` PATH ≥ `min(natural, MIN_PATH)`
60. существующие кейсы 11–18 Этапа 7 — сохранить (или обновить под новый набор, если ширины сместились)

### `WorktreeTableRenderTest.kt` (дополнить/обновить)
61. группировка: у репо с 3 worktree имя в колонке «Репозиторий» на 2-й и 3-й строке пустое, на 1-й — есть
62. группировка НЕ меняет порядок строк
63. группировка не влияет на natural-ширину REPO (колонка не схлопывается по ширине из-за пустых ячеек)
64. AGE-колонка есть при непустых `lastCommitEpoch`, отсутствует когда все null (single-repo `list`)
65. AHEAD_BEHIND-колонка есть при непустых `aheadBehind`, отсутствует когда все null
66. worktree без upstream → в ячейке ahead/behind `—`, не ошибка/не пусто-без-плейсхолдера
67. детерминированный `now`: возраст рендерится ожидаемой строкой
68. на width=80 с реальным портфелем AGE/AHEAD_BEHIND выброшены раньше REPO (или все влезли — проверить, что не переполняет 80)
69. **существующие кейсы 19–28 Этапа 7 — прогнать; обновить ТОЛЬКО те, где формат вывода реально сместился** (напр. счёт строк 21 — если добавились колонки, число строк не меняется, но ширины да; тест 21 считает СТРОКИ — не пострадает). Явно проверить: тесты 19 (≤80), 20 (нет `││`), 24 (REPO на 160 / выброс на 80) — не сломались от новых колонок.

### `InteractiveScreenTest.kt`
70. существующие кейсы 44–46 Этапа 7 — сохранить (ahead/behind в интерактивный список НЕ добавляем на этом этапе, чтобы не раздувать; если добавляем — отдельный кейс). Решить и зафиксировать: интерактивный список меняем минимально/не меняем.

### `PrintPathIntegrationTest.kt`
71. существующий тест 47 — путь абсолютный, без `~`/`…`/ANSI — сохранить (конфиг-fallback корня не портит контракт).

---

## 7. Ручная проверка на собранном артефакте (обязательна)

`./gradlew build` не пересобирает `build/install/gwm/bin/gwm` — сначала:
```bash
source ~/.sdkman/bin/sdkman-init.sh
./gradlew build installDist
B=build/install/gwm/bin/gwm
```

| Проверка | Ожидание |
|---|---|
| `COLUMNS=200 $B scan` | все колонки, включая Возраст и ahead/behind; имя репо не повторяется внутри группы |
| `time COLUMNS=200 $B scan` (до и после Фазы C) | замер производительности; регресс не катастрофичен (ориентир ≤ ~3с) |
| `COLUMNS=80 $B scan \| cat` | AHEAD_BEHIND и AGE выброшены раньше REPO; ни одна строка не > 80 |
| `COLUMNS=80 $B scan \| awk '{ if (length($0)>80) print "TOO LONG:",length($0) }'` | (по display-width! `awk length` считает БАЙТЫ — использовать Python `unicodedata.east_asian_width`, см. SKILL) пусто |
| `COLUMNS=140 $B scan \| wc -l` | ≈ число worktree + 4 (группировка/колонки не добавляют строк) |
| worktree без upstream (детач или локальная ветка) | в ahead/behind `—`, scan не падает, нет лишнего варнинга |
| worktree с upstream позади/впереди | корректные числа ahead/behind (сверить с `git rev-list` вручную) |
| repo с несколькими worktree (напр. `knowledge_vault`) | имя репо только на первой строке группы, дальше пусто |
| нет конфига (`mv ~/.config/gwm/config.toml ...bak`) | всё работает как раньше (дефолты) |
| пустой конфиг-файл | всё работает, дефолты |
| конфиг с `roots=["/tmp/x"]`, без `--root`/`$GWM_ROOT` | scan использует `/tmp/x` |
| `$B --root=/other scan` при наличии конфига | `--root` перебивает конфиг |
| битый конфиг (`echo '[[bad' > config.toml`) | внятная ошибка про конфиг, exit≠0, НЕ stacktrace |
| конфиг с `worktree-path-template` | `gwm create` кладёт worktree по шаблону |
| конфиг с `[colors]` (напр. `clean="cyan"`) | статус clean окрашен иначе; неизвестный цвет → дефолт + варнинг |
| `COLUMNS=40 $B scan` | компактный список (колонки ahead/behind туда не лезут — ок) |
| `$B --print-path gwm` | абсолютный путь, без цвета/`~`/`…`; конфиг не сломал контракт |
| `COLUMNS=80 $B list .` | новые колонки НЕ появляются (single-repo), таблица валидна |

Вывод «до/после» для `COLUMNS=200 scan` (группировка + новые колонки) и замер `time` приложить в PR.

---

## 8. Документация (в этом же PR)

- **`README.md`:** секция про конфиг `~/.config/gwm/config.toml` (формат, опциональность, приоритет над дефолтом но не над флагами); упоминание колонок Возраст / ahead-behind и их деградации по ширине; упоминание группировки вывода scan.
- **`.claude/skills/kotlin-worktree-tui-dev/SKILL.md`:** (а) решение «ручной парсер TOML-подмножества вместо зависимости» и его границы; (б) семантика сторон `git rev-list --left-right --count` (какая сторона ahead/behind — зафиксировать, легко перепутать); (в) no-upstream = ожидаемый exit≠0, обрабатывается как `null`, не ошибка; (г) `%cr` локализован → возраст считаем сами из `%ct`; (д) новый порядок лестницы деградации (новые колонки выбрасываются раньше REPO); (е) замер производительности scan с новыми вызовами и решение по параллелизму worktree внутри репо.
- **`docs/PLAN.md`** и **`docs/TECHNICAL_PLAN.md`:** строка «Этап 8 ✅» с точками входа (`config/TomlLite.kt`, `config/GwmConfig.kt`, `ui/AgeFormat.kt`, `git/AheadBehind.kt`/`WorktreeService.withAheadBehindAndAge`, изменения `ui/TableLayout.kt`/`WorktreeTable.kt`).
- **`docs/POST_MVP_PLAN.md`:** отметить реализованными три пункта (группировка scan, конфиг-файл, ahead/behind + возраст); оставить как отложенные: мульти-корень одновременно, ширина/набор колонок из конфига, полная цветовая тема, `--format=json`.

---

## 9. Критерий готовности (DoD)

1. `./gradlew build` зелёный (все тесты раздела 6 включены и проходят).
2. `./gradlew installDist` выполнен, **вся** таблица раздела 7 прогнана на `build/install/gwm/bin/gwm` и совпала с ожиданиями (тестов недостаточно — ловушка `installDist` и реальный портфель ловят баги, что тесты пропускают — так было в Этапе 7).
3. Группировка: имя репо не повторяется внутри группы; порядок строк не изменился; ширины не сломались.
4. Конфиг опционален: без файла — поведение идентично Этапу 7. Битый TOML → внятная ошибка, exit≠0, не краш. CLI-флаг перебивает конфиг. `roots[0]` работает как fallback-корень.
5. Ahead/behind: у worktree с upstream — верные числа; без upstream — `—`, scan не падает, ошибки не всплывают. Возраст — компактная локаль-независимая строка.
6. Новые колонки деградируют по ширине РАНЬШЕ REPO/ORPHAN/BRANCH/PATH; ни одна строка вывода не превышает ширину при `COLUMNS` = 200/140/80/60/40.
7. Производительность `scan` на реальном портфеле замерена до/после; регресс приемлем (ориентир ≤ ~3с); при необходимости worktree внутри репо распараллелены.
8. `--print-path` контракт не нарушен (абсолютный путь, exit-коды).
9. `/code-review` на diff ветки пройден, замечания закрыты (до 3 итераций).
10. Документация раздела 8 обновлена.

---

## 10. Риски и ловушки

- **`installDist` ≠ `build`** — DoD п.2; проверять на пересобранном бинаре.
- **Семантика сторон `rev-list --left-right`** — легко инвертировать ahead/behind. Зафиксировать тестом (кейс 38) и проверить на артефакте против ручного `git`.
- **No-upstream — это exit≠0, не пустой вывод.** Не спутать с «пустой ahead/behind». Проверено: `fatal: вышестоящая ветка не настроена`, exit 128. Обрабатывать по `GitResult.ok`, не по пустоте stdout.
- **`%cr` локализован** (на этом хосте — русский). Не использовать для парсинга/ширины; считать из `%ct` самим.
- **Производительность:** +54 процесса на 27 worktree. Замерить; параллелить worktree внутри репо только если нужно (по паттерну `ScanService`, не изобретать).
- **Лестница деградации `TableLayout`** — самая тонкая правка. Текущая `ladder` жёстко прописана под REPO/ORPHAN; переписать аккуратно и покрыть sweep-инвариантом (кейс 59), иначе новые колонки могут переполнить строку.
- **Ширина глифов** `↑ ↓ ⚠ … ✓ ●` — считать по `String.length` в предположении ширины 1; проверить на артефакте (если перенос — уменьшить avail на 1, зафиксировать в SKILL). Для ahead/behind при сомнении — ASCII `+N/-N`.
- **Группировка × выброс REPO по ширине:** схлопывать имя ТОЛЬКО когда колонка REPO присутствует; при выброшенной REPO префикс `<repo>: ` у non-relative путей НЕ схлопывать (каждая строка самодостаточна).
- **Обратная совместимость рендер-тестов Этапа 7:** новые колонки/группировка/цвета-параметр — с дефолтами, чтобы существующие вызовы `render*` без новых аргументов давали прежний вывод; тесты на `AnsiLevel.NONE` цвет не видят. Прогнать кейсы 19–28 и 44–47 — обновлять только реально сместившиеся.
- **Конфиг и конфликт корней:** конфиг НЕ участвует в `RootSelection`-конфликте (только CLI-источники), иначе сломаются кейсы 36–41 Этапа 7 и пользователь с конфигом+флагом получит ложную ошибку.

---

## Критичные файлы для реализации
- `src/main/kotlin/dev/alkom/gwm/git/WorktreeService.kt` — новый шаг `withAheadBehindAndAge`, `defaultWorktreePath` с шаблоном
- `src/main/kotlin/dev/alkom/gwm/ui/TableLayout.kt` — новые колонки + переписанная лестница деградации (самая тонкая правка)
- `src/main/kotlin/dev/alkom/gwm/ui/WorktreeTable.kt` — группировка, рендер новых колонок, цветовая схема
- `src/main/kotlin/dev/alkom/gwm/Main.kt` — загрузка конфига в `GwmGlobals`, проброс в scan/create/print-path
- `src/main/kotlin/dev/alkom/gwm/scan/RepoScanner.kt` — `resolveRoot(configRoot)` fallback (+ новый пакет `config/` с `TomlLite.kt`, `GwmConfig.kt`)
