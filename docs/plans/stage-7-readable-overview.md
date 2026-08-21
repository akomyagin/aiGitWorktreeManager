# Этап 7 — «Читаемый обзор»

Ветка: `stage/7-readable-overview` (от `master`). PR в `master` по workflow из `CLAUDE.md`.
Исполнитель: Opus-агент (код), затем Sonnet (тесты), затем `/code-review`.

## 1. Цель

Сделать `gwm scan` / `gwm list` пригодными для реального портфеля (25 репо / 27 worktree)
на терминале любой ширины и убрать два бага в разрешении корня портфеля.

Все пять проблем воспроизведены на собранном бинаре, это не гипотезы:

| # | Проблема | Наблюдение |
|---|---|---|
| 1 | Колонка «Путь» бесполезна | все 27 строк = `/home/alkom/Projects/ai-p…`, режется справа, различающая часть в хвосте |
| 2 | На 80 колонках таблица ломается | «Путь» схлопывается в нулевую ширину (`┬┐`, `││`), «Статус» теряет padding |
| 3 | Таблица вдвое выше нужного | разделитель между КАЖДОЙ строкой: 27 worktree → 58 строк вывода |
| 4 | Корневой `--root` молча игнорируется в `scan` | `gwm --root=/tmp/gwmtest scan` сканирует дефолтный корень |
| 5 | `gwm scan ~/Projects/ai-projects` падает | `Error: got unexpected extra argument` |

Замер «до» (для сравнения в PR):
```
$ COLUMNS=140 gwm scan | wc -l     # 58 строк на 27 worktree
$ COLUMNS=80  gwm scan             # колонка Путь нулевой ширины
```

## 2. Принятые решения (и почему)

### Р1. Путь показываем относительно базы, обрезаем с хвоста

- `scan`: база = корень портфеля (он уже напечатан в шапке `Портфель: <root> (N репо)`,
  значит относительный путь однозначен).
- `list`: база = **родительская директория репозитория** (`File(repo).absoluteFile.parentFile`).
  Дефолтная раскладка `gwm create` — сиблинг `../<repo>-<branch>`, поэтому относительно
  родителя главный worktree = `repo`, линкованные = `repo-branch`. В шапку `list` добавляем
  абсолютный якорь, чтобы относительность не была загадкой.
- Вне базы, но внутри `$HOME` → `~/...`; иначе абсолютный путь как есть.
- Обрезка — **с сохранением хвоста**: `…/ai-knowledge-vault/docs-session-handoff`. Уникальность
  пути живёт в конце строки; ровно поэтому дефолтный Mordant `ELLIPSES` (режет хвост) здесь
  бесполезен и мы обрезаем сами, до передачи в таблицу.
- Имена (репо, ветка) обрезаем наоборот — с головы, многоточие в конце (`aiGitWorktreeMana…`):
  идентичность имени читается с начала.

Отвергнуто: «показывать только имя директории worktree» — теряется, где он лежит, а `cd`-сценарий
требует узнаваемого пути.

### Р2. Своё бюджетирование ширин вместо `ColumnWidth.Expand()`

`Expand()` (WorktreeTable.kt:94) отдаёт колонке **остаток**, а на 80 колонках остатка нет → 0.
Это не настраивается: Expand не умеет «минимум». Поэтому:

1. считаем «естественные» ширины колонок сами (по plain-строкам, до раскраски);
2. чистой функцией решаем, какие колонки показать и какой ширины;
3. пред-обрезаем содержимое ячеек до назначенных ширин;
4. отдаём Mordant `ColumnWidth.Auto` — ужимать ему уже нечего, таблица гарантированно влезает.

`whitespace = NORMAL` + `overflowWrap = ELLIPSES` на колонке пути **оставляем** как страховку
(если наш расчёт где-то ошибётся на символ, обрезка будет видимой, а не молчаливой).

Лестница деградации (сверху вниз, пока не влезет):

1. Полный набор.
2. Убрать «Репозиторий» (только `scan`): при относительном пути имя репо — это его первый сегмент,
   информация не теряется. Экономит ~23 колонки — самый крупный выигрыш.
   Для строк, чей путь НЕ относителен корню (`~/...` или `/...`), ячейка пути получает
   префикс `<repo>: `, чтобы принадлежность репо не потерялась.
3. Убрать «Orphaned»: `⚠` дописывается в ячейку ветки; полные причины остаются в
   `gwm interactive` → «Показать детали».
4. Сжать «Ветку» до `MIN_BRANCH = 12`.
5. «Путь» не опускается ниже `MIN_PATH = 20`.
6. Если и это не влезло — компактный список без рамок (см. Р5).

Дополнительно, независимо от ширины: **колонка «Orphaned» рендерится только если есть хотя бы одна
orphaned-строка**. В реальном портфеле она сейчас пустая у всех 27 строк и жрёт 22 колонки.

### Р3. Компактная таблица

`body { cellBorders = Borders.LEFT_RIGHT }` — горизонтальные линии остаются только у заголовка и
у внешней рамки. Ожидаемо: 27 worktree → 31 строка (рамка, заголовок, разделитель, 27 строк, рамка).
Поведение `tableBorders` vs `cellBorders` в Mordant 3.0.1 не задокументировано — **проверить
эмпирически на собранном бинаре** и записать вывод в `.claude/skills/kotlin-worktree-tui-dev/SKILL.md`
(если нижняя рамка пропадёт — выставить `tableBorders = Borders.ALL` и/или бордер на последней строке).

### Р4. Один корень, конфликт — ошибка, а не приоритет

Причина бага не в Clikt, а в двух независимых опциях с одним именем (`Gwm.root` и `ScanCommand.root`).
`--root` на корневой команде убрать нельзя (его использует `--print-path`), а из `ScanCommand`
убрать тоже нельзя — сломается работающая сегодня форма `gwm scan --root=...` (Clikt разбирает
опции после токена подкоманды её собственным парсером).

Решение: три источника (позиционный `ROOT`, `scan --root`, корневой `--root`) сводятся к одному
значению чистой функцией. **Если заданы разные непустые значения — `CliktError`** с перечислением
источников. Одинаковые (после нормализации: `~`-раскрытие, `absoluteFile.normalize()`, trailing slash)
— ок. Молчаливого приоритета нет намеренно: «молча взять не тот корень хуже, чем ошибка», а
приоритет здесь всё равно был бы неугадываемым.

Передача корневого значения вниз — идиоматично для Clikt: `currentContext.obj` в `Gwm.run()`
(родительский `run()` всегда выполняется до подкоманды) + `findObject` в `ScanCommand.run()`.

### Р5. Компактный режим для очень узкого терминала

При ширине, где даже минимальный набор (Ветка 12 + Статус 7 + Путь 20 + рамки 10 = 49) не влезает,
таблица заменяется на список без рамок, по две строки на worktree:

```
✓ ai-knowledge-vault/docs-session-handoff-2026-08-20
  ⚠ merged · docs/session-handoff-2026-08-20
```
Строка 1: глиф статуса + путь (обрезка с хвоста до `width-2`).
Строка 2: два пробела + опциональный `⚠ причины · ` + ветка (обрезка до `width-2`).
Пустых строк между записями нет.

### Р6. Терминал — из контекста Clikt

Сейчас каждая команда делает `private val terminal = gwmTerminal()`. Из-за этого вывод нельзя
перехватить `CliktCommand.test(...)`, то есть баги 2/4/5 нечем закрыть автотестом. Переводим на
контекст: в `Gwm` — `init { context { terminal = gwmTerminal() } }`, в командах — свойство
`terminal` из `com.github.ajalt.clikt.core.terminal` (наследуется дочерними контекстами).
`test(argv, width = 80, ansiLevel = AnsiLevel.NONE)` подменит его своим — то есть ширину в тестах
можно задавать напрямую.

**Проверить первым же тестом**, что вывод действительно перехватывается (порядок применения
`context {}`-блоков не задокументирован). Если нет — план Б: оставить `gwmTerminal()`, но принимать
`Terminal` параметром конструктора команды со значением по умолчанию, а ширину в тестах гонять через
`WorktreeTable.render(..., width = 80)` напрямую.

## 3. Границы — что НЕ трогаем

- **Контракт `--print-path`.** `PrintPath.emit` печатает АБСОЛЮТНЫЙ путь сырым `println` — сюда
  сокращённые/обрезанные пути не должны попасть ни при каких условиях (сломает `cd "$(...)"`).
  Единственное допустимое изменение рядом — ранняя `CliktError` на несуществующий корень
  (ветка отказа и так была ненулевой, stdout остаётся пустым).
- **Логику git-вызовов:** `git/GitCommand.kt`, `git/WorktreeService.kt`, `git/WorktreeParser.kt` —
  не менять. Никаких новых git-команд.
- **Orphaned-эвристики:** `git/OrphanClassifier.kt`, `git/OrphanStatus.kt` — не менять. Меняется
  только то, КАК бейдж рисуется.
- **`RepoScanner.findRepos`** — семантика поиска репо (только `.git`-директория) не меняется;
  трогаем только `resolveRoot` (раскрытие `~`).
- **`ui/ShellInit.kt`** — сниппет не меняется.
- Никакой автоочистки orphaned, никаких новых зависимостей в `build.gradle.kts`, никаких изменений
  в `gradle.properties` / toolchain.
- Не сортировать/не фильтровать вывод (это post-MVP), не добавлять alternate-screen TUI.
- Не коммитить и не пушить без явной команды пользователя.

## 4. Изменения по файлам

### Новый: `src/main/kotlin/dev/alkom/gwm/ui/PathDisplay.kt`

Чистые функции, без обращений к ФС (кроме `System.getProperty("user.home")` по умолчанию),
без Mordant — юнит-тестируется без TTY.

```kotlin
object PathDisplay {
    const val ELLIPSIS = "…"

    /**
     * Человекочитаемая форма абсолютного пути worktree:
     * относительно [base] (если внутри), иначе "~/..." (если внутри [home]), иначе как есть.
     * Сравнение — по границе сегмента: base=/a/b НЕ является префиксом /a/bc.
     */
    fun shorten(path: String, base: File?, home: File = File(System.getProperty("user.home"))): String

    /** Обрезка с сохранением ХВОСТА: "…/repo/worktree". Короткое возвращается как есть. */
    fun truncateTail(text: String, maxWidth: Int): String

    /** Обрезка с сохранением ГОЛОВЫ: "aiGitWorktreeMana…". Для имён репо и веток. */
    fun truncateHead(text: String, maxWidth: Int): String
}
```

Правила `shorten` (в порядке проверки): нормализовать `File(path).absoluteFile.normalize()`
(НЕ `canonicalPath` — не резолвим симлинки, git и так отдаёт реальные пути);
`p == base` → `"."`; `p` внутри `base` → относительный путь без ведущего `./`;
`p == home` → `"~"`; `p` внутри `home` → `"~/" + rest`; иначе `p.path`.

Правила `truncateTail`: `maxWidth <= 1` → `"…"`; иначе взять последние `maxWidth-1` символов,
и если в них есть `/` — отрезать всё до первого `/` включительно (чтобы получилось `…/полный-сегмент`,
а не `…ent/xyz`); результат = `"…" + tail`, длина ≤ `maxWidth`.

### Новый: `src/main/kotlin/dev/alkom/gwm/ui/TableLayout.kt`

```kotlin
enum class OverviewColumn { REPO, BRANCH, STATUS, ORPHAN, PATH }

data class LayoutPlan(
    /** Колонки в порядке отрисовки с назначенной шириной содержимого; пусто = компактный режим. */
    val columns: List<Pair<OverviewColumn, Int>>,
) {
    val compact: Boolean get() = columns.isEmpty()
}

object TableLayout {
    const val MIN_REPO = 10
    const val MIN_BRANCH = 12
    const val MIN_PATH = 20
    const val PREFERRED_PATH = 30

    /** Накладные расходы Mordant-таблицы: n+1 вертикальных рамок + по 2 пробела padding на колонку. */
    fun chrome(columnCount: Int): Int = 3 * columnCount + 1

    /**
     * @param width       ширина терминала
     * @param wanted      желаемый набор колонок в порядке отрисовки
     * @param natural     естественная ширина каждой колонки = max(ширина заголовка, максимум по ячейкам)
     */
    fun plan(width: Int, wanted: List<OverviewColumn>, natural: Map<OverviewColumn, Int>): LayoutPlan
}
```

Алгоритм `plan`:

1. Кандидаты-наборы (лестница): `wanted` → `wanted - REPO` → `wanted - REPO - ORPHAN` → компакт.
   (Для `list` шаг с REPO просто отсутствует — колонки нет.)
2. Для каждого набора пробуем `assign` (ниже); первый успешный — результат.
3. `assign(width, cols, natural)`:
   - `avail = width - chrome(cols.size)`; `avail <= 0` → неудача;
   - стартуем со всех колонок в natural-ширине;
   - `rest = avail - сумма(всех кроме PATH)`;
   - `target = min(natural[PATH], PREFERRED_PATH)`;
   - если `rest < target`: занимаем недостающее у BRANCH (не ниже `MIN_BRANCH`), затем у REPO
     (не ниже `MIN_REPO`); STATUS и ORPHAN не сжимаются никогда (7 и «⚠ причины» и так узкие;
     сжатие сделает их нечитаемыми — вместо этого колонка выбрасывается целиком);
   - `floor = min(natural[PATH], MIN_PATH)`; если `rest < floor` → неудача (следующая ступень);
   - `width[PATH] = min(natural[PATH], rest)`; успех.
4. Все ступени провалились → `LayoutPlan(emptyList())` (компактный режим).

Инвариант, который обязан держаться (и проверяется тестом-циклом по width 30..200):
`сумма назначенных ширин + chrome(n) <= width`.

### Изменённый: `src/main/kotlin/dev/alkom/gwm/ui/WorktreeTable.kt`

Общий движок рендера + две тонкие обёртки. Публичные сигнатуры:

```kotlin
/** Строка обзора в plain-виде: ширины считаются по ней, стили накладываются на отрисовке. */
data class OverviewRow(
    val repo: String?,              // null для однорепного list
    val branch: String,
    val isMain: Boolean,
    val dirty: Boolean?,
    val orphanReasons: List<String>,
    val path: String,               // УЖЕ сокращённый через PathDisplay.shorten
    val pathIsRelative: Boolean,    // false → при выброшенной колонке REPO ячейка получает префикс "<repo>: "
)

object WorktreeTable {
    fun statusCell(wt: Worktree): String            // без изменений
    fun orphanCell(wt: Worktree): String            // без изменений
    fun orphanHint(wt: Worktree): String?           // без изменений

    fun render(worktrees: List<Worktree>, base: File?, width: Int): Widget
    fun renderAggregated(worktrees: List<AggregatedWorktree>, root: File?, width: Int): Widget

    internal fun rows(worktrees: List<Worktree>, base: File?): List<OverviewRow>
    internal fun rowsAggregated(worktrees: List<AggregatedWorktree>, root: File?): List<OverviewRow>
    internal fun renderRows(rows: List<OverviewRow>, width: Int): Widget
    internal fun naturalWidths(rows: List<OverviewRow>, cols: List<OverviewColumn>): Map<OverviewColumn, Int>
    internal fun renderCompact(rows: List<OverviewRow>, width: Int): Widget
}
```

Детали `renderRows`:
- набор колонок: `ORPHAN` включается только если `rows.any { it.orphanReasons.isNotEmpty() }`;
- `plan = TableLayout.plan(width, cols, naturalWidths(rows, cols))`;
- если `plan.compact` → `renderCompact`;
- ячейки формируются в plain-виде, обрезаются (`truncateTail` для PATH, `truncateHead` для REPO/BRANCH),
  и только потом красятся (`bold` для main-ветки и имени репо, `brightGreen/brightYellow/gray` для статуса);
- если `ORPHAN` выброшена, а строка orphaned — к ячейке ветки дописывается ` ⚠` (и это учитывается
  в natural-ширине ветки);
- `table { borderType` по умолчанию; `body { cellBorders = Borders.LEFT_RIGHT }`;
  колонки — `ColumnWidth.Auto` (Expand больше не используется нигде);
  на колонке PATH оставить `whitespace = Whitespace.NORMAL` + `overflowWrap = OverflowWrap.ELLIPSES`
  как страховку от ошибки расчёта на ±1;
- ширина считается по plain-строкам через `String.length` (все используемые глифы `✓ ● ⚠ …` — шириной 1);
  в `avail` заложить запас 0 — но при первой же ручной проверке на `COLUMNS=80` убедиться, что ни одна
  строка не переносится; при переносе — уменьшить `avail` на 1 и зафиксировать причину в SKILL.md.

### Изменённый: `src/main/kotlin/dev/alkom/gwm/ui/InteractiveScreen.kt`

- Конструктор: `InteractiveScreen(terminal: Terminal, service: WorktreeService, pathBase: File? = null)`.
- `rowLabel(wt: Worktree, base: File?): String` — путь через `PathDisplay.shorten` (сигнатура меняется,
  обновить `InteractiveScreenTest`).
- Строка «Выбрано: …» — тоже сокращённый путь.
- **`showDetails` оставляет АБСОЛЮТНЫЙ путь** — это экран, откуда путь копируют; отметить комментарием.
- `InteractiveCommand.run()` передаёт `pathBase = File(repo).absoluteFile.parentFile`.

### Новый: `src/main/kotlin/dev/alkom/gwm/scan/RootSelection.kt`

```kotlin
/** Источник значения корня — для внятного сообщения о конфликте. */
data class RootCandidate(val source: String, val value: String?)   // source: "аргумент ROOT", "scan --root", "gwm --root"

sealed interface RootChoice {
    /** Единственное согласованное значение (null = источников нет, дальше GWM_ROOT/дефолт). */
    data class One(val value: String?) : RootChoice
    data class Conflict(val candidates: List<RootCandidate>) : RootChoice
}

object RootSelection {
    fun choose(candidates: List<RootCandidate>): RootChoice
}
```
Пустые/blank значения игнорируются. Сравнение — по нормализованной форме
(`RepoScanner.expandTilde` → `File(...).absoluteFile.normalize().path`), поэтому `/tmp/x` и `/tmp/x/`
конфликтом не считаются.

### Изменённый: `src/main/kotlin/dev/alkom/gwm/scan/RepoScanner.kt`

```kotlin
/** Раскрывает ведущий "~" / "~/..." в $HOME. "~user/..." оставляется как есть (мы не резолвим чужие домашние). */
fun expandTilde(path: String, home: String = System.getProperty("user.home")): String
```
`resolveRoot` пропускает `override` и `GWM_ROOT` через `expandTilde`. Остальное — без изменений.

### Изменённый: `src/main/kotlin/dev/alkom/gwm/Main.kt`

```kotlin
/** Опции корневой команды, видимые подкомандам через Clikt Context.obj. */
data class GwmGlobals(val root: String?)

class Gwm : CliktCommand(name = "gwm") {
    init { context { terminal = gwmTerminal() } }          // Р6
    override val invokeWithoutSubcommand = true
    private val printPath: String? by option(...)          // без изменений
    private val root: String? by option("--root", help = "корень портфеля для scan и --print-path (по умолчанию ~/Projects/ai-projects или $GWM_ROOT)")

    override fun run() {
        currentContext.obj = GwmGlobals(root)              // ДО раннего return: подкоманда должна его увидеть
        val query = printPath ?: return
        PrintPath.emit(query, root)
    }
}

class ScanCommand : CliktCommand(name = "scan") {
    private val rootArg: String? by argument("ROOT", help = "корень портфеля (по умолчанию ~/Projects/ai-projects или $GWM_ROOT)").optional()
    private val rootOpt: String? by option("--root", help = "то же, что позиционный ROOT")

    override fun run() {
        val globals = currentContext.findObject<GwmGlobals>()
        val choice = RootSelection.choose(listOf(
            RootCandidate("аргумент ROOT", rootArg),
            RootCandidate("scan --root", rootOpt),
            RootCandidate("gwm --root", globals?.root),
        ))
        val chosen = when (choice) {
            is RootChoice.One -> choice.value
            is RootChoice.Conflict -> throw CliktError(
                "Корень портфеля указан несколько раз и по-разному:\n" +
                    choice.candidates.joinToString("\n") { "  ${it.source} = ${it.value}" } +
                    "\nУкажите его один раз.",
            )
        }
        val rootDir = RepoScanner.resolveRoot(chosen)
        if (!rootDir.isDirectory) throw CliktError("Корень портфеля не найден или не директория: ${rootDir.path}")
        ...
        terminal.println(WorktreeTable.renderAggregated(result.worktrees, rootDir, terminal.size.width))
    }
}
```
- Все `private val terminal = gwmTerminal()` в командах удаляются; используется `terminal` из контекста.
- `ListCommand`: база = `File(repo).absoluteFile.parentFile`, шапка становится
  `Worktrees: <имя репо>` + серым `(<абсолютный путь базы>)`, чтобы относительные пути читались однозначно.
- Ширина берётся как `terminal.size.width` в момент рендера; `updateSize()` не вызывать
  (Mordant рендерит по тому же `size.width`).
- `PrintPath.emit` — без изменений, кроме опциональной ранней `CliktError` на несуществующий корень.

## 5. Порядок реализации

1. **Фаза 0 — терминал из контекста (Р6).** Мелкий рефактор, разблокирует тесты. Сразу написать
   первый `test()`-тест и убедиться, что вывод перехватывается; иначе включить план Б.
2. **Фаза 1 — `PathDisplay` + тесты.** Чистая логика, без UI.
3. **Фаза 2 — `TableLayout` + тесты.** Чистая логика, без Mordant.
4. **Фаза 3 — `WorktreeTable` (Р1/Р2/Р3/Р5) + рендер-тесты.** Здесь же удалить `Expand()`.
5. **Фаза 4 — `InteractiveScreen`** (сокращённый путь в списке, абсолютный в деталях).
6. **Фаза 5 — `RootSelection` + `ScanCommand` (Р4, п.5) + `expandTilde`.**
7. **Фаза 6 — ручная проверка на артефакте** (`./gradlew installDist`, раздел 7).
8. **Фаза 7 — документация** (раздел 8).

## 6. Тест-кейсы

`src/test/kotlin/dev/alkom/gwm/PathDisplayTest.kt` (юнит)
1. путь внутри base → относительный, без ведущего `./`
2. путь == base → `.`
3. base = null, путь внутри `$HOME` → `~/...`
4. путь вне base и вне home → абсолютный без изменений
5. префикс не по границе сегмента: base=`/a/b`, путь=`/a/bc/d` → НЕ относительный
6. trailing slash в base не ломает сравнение
7. `truncateTail`: короче лимита — без изменений
8. `truncateTail`: длиннее — начинается с `…`, длина ≤ лимита, последний сегмент сохранён целиком
9. `truncateTail(maxWidth = 1)` → `…`
10. `truncateHead`: `"aiGitWorktreeManager"`, лимит 12 → длина 12, заканчивается на `…`, начинается на `aiGit`

`src/test/kotlin/dev/alkom/gwm/TableLayoutTest.kt` (юнит)
11. width=140, 5 колонок: все на месте, PATH == natural, сумма+chrome ≤ 140
12. width=80: колонка REPO отброшена, PATH ≥ MIN_PATH
13. width=80, набор без ORPHAN (нет orphaned-строк): 3 колонки, PATH ≥ PREFERRED_PATH
14. width=80 с длинными ветками: BRANCH сжат, но ≥ MIN_BRANCH
15. width=60: ORPHAN отброшена, PATH ≥ MIN_PATH
16. width=40: `compact == true`
17. natural у всех маленькие → никому не раздаётся больше natural
18. инвариант циклом `for (w in 30..200)`: сумма + chrome(n) ≤ w, и при `!compact` PATH ≥ min(natural, MIN_PATH)

`src/test/kotlin/dev/alkom/gwm/WorktreeTableRenderTest.kt` (рендер через `Terminal(ansiLevel = AnsiLevel.NONE, width = N)` + `terminal.render(widget)`)
19. width=80, 27 фейковых строк: каждая строка вывода ≤ 80 символов
20. width=80: в выводе нет `││` и нет `┬┐` (нулевых колонок не осталось) — прямая регрессия на баг 2
21. компактность: число строк == rows + 4 — прямая регрессия на баг 3
22. заголовок содержит `Путь`; ячейка пути относительная (`ai-knowledge-vault/...`), не начинается с `/home`
23. при обрезке пути ячейка начинается с `…` и содержит последний сегмент — регрессия на баг 1
24. width=80: заголовок НЕ содержит `Репозиторий`; width=160: содержит
25. нет orphaned-строк → заголовок не содержит `Orphaned`; есть → содержит (при достаточной ширине)
26. ORPHAN выброшена по ширине, строка orphaned → в ячейке ветки есть `⚠`
27. width=40 → компактный режим: в выводе нет `┌` и `│`, строк ровно `2 * rows`
28. `render(...)` (однорепный) не содержит колонки `Репозиторий` ни при какой ширине

`src/test/kotlin/dev/alkom/gwm/RootSelectionTest.kt` (юнит)
29. только корневой `--root` → `One(его)`
30. только `scan --root` → `One(его)`
31. только позиционный → `One(его)`
32. корневой + позиционный, значения совпадают (одно с trailing slash) → `One`
33. корневой + `scan --root` разные → `Conflict`, в списке оба источника
34. позиционный + `--root` разные → `Conflict`
35. пусто/blank везде → `One(null)`

`src/test/kotlin/dev/alkom/gwm/ScanCommandTest.kt` (новый, Clikt `test()`, без git — пустой `@TempDir` корень)
36. `Gwm().subcommands(ScanCommand()).test("--root=$tmp scan")` → в выводе путь `$tmp`, statusCode 0 — регрессия на баг 4
37. `test("scan --root=$tmp")` → тот же результат (старая форма не сломана)
38. `test("scan $tmp")` → работает, никакого `unexpected extra argument` — регрессия на баг 5
39. `test("--root=$a scan --root=$b")` → statusCode != 0, в stderr «указан несколько раз»
40. `test("scan /definitely/not/here")` → statusCode != 0, сообщение про ненайденный корень
41. `test("scan", width = 80)` с реальным временным портфелем (git, `assumeTrue(gitAvailable())`) →
    каждая строка вывода ≤ 80

`src/test/kotlin/dev/alkom/gwm/RepoScannerTest.kt` (дополнить)
42. `resolveRoot("~/x")` → `$HOME/x`; `resolveRoot("~")` → `$HOME`
43. `resolveRoot("~someone/x")` → не раскрывается

`src/test/kotlin/dev/alkom/gwm/InteractiveScreenTest.kt` (обновить)
44. `rowLabel(wt, base)` печатает относительный путь
45. `rowLabel(wt, base = null)` для пути внутри `$HOME` печатает `~/...`
46. существующие проверки маркеров/`⚠` — сохранить

`src/test/kotlin/dev/alkom/gwm/PrintPathIntegrationTest.kt` (дополнить)
47. результат резолвинга — абсолютный путь: `startsWith("/")`, не содержит `~`, `…`, ANSI-кодов —
    защита контракта `cd "$(...)"`

## 7. Ручная проверка на собранном артефакте (обязательна)

`./gradlew build` не пересобирает `build/install/gwm/bin/gwm` — сначала:

```bash
source ~/.sdkman/bin/sdkman-init.sh
./gradlew build installDist
B=build/install/gwm/bin/gwm
```

| Проверка | Ожидание |
|---|---|
| `COLUMNS=200 $B scan` | все 5 колонок, пути относительные, коротко |
| `COLUMNS=80 $B scan \| cat` | нет `││`/`┬┐`, ни одна строка не переносится, «Репозиторий» отсутствует, хвосты путей видны |
| `COLUMNS=80 $B scan \| awk '{ if (length($0) > 80) print "TOO LONG:", length($0) }'` | пусто |
| `COLUMNS=80 $B scan \| wc -l` | ≈ число worktree + 4 (было 58 на 27) |
| `COLUMNS=60 $B scan` | таблица валидна, путь читаем |
| `COLUMNS=40 $B scan` | компактный список без рамок |
| `$B --root=/tmp/gwmtest scan` | шапка/сообщение про `/tmp/gwmtest`, НЕ про дефолтный корень |
| `$B scan --root=/tmp/gwmtest` | то же самое |
| `$B scan ~/Projects/ai-projects` | работает, без `unexpected extra argument` |
| `$B --root=/tmp/a scan --root=/tmp/b; echo $?` | сообщение о конфликте, код ≠ 0 |
| `$B --print-path gwm` | абсолютный путь, без цвета и без `~`; `cd "$($B --print-path gwm)"` работает |
| `$B --print-path нетакого; echo $?` | stdout пуст, код ≠ 0 |
| `COLUMNS=80 $B list .` | таблица валидна, пути относительно родителя репо |
| `$B interactive` (в TTY) | список с сокращёнными путями; «Показать детали» — абсолютный путь |

Скриншот/вывод «до и после» для `COLUMNS=80 scan` приложить в описание PR.

## 8. Документация (в этом же PR)

- `README.md`: `gwm scan [ROOT] [--root <dir>]`, объяснение приоритета/конфликта, упоминание, что
  пути в таблице — относительно корня портфеля.
- `.claude/skills/kotlin-worktree-tui-dev/SKILL.md`: дописать в секцию про Mordant —
  (а) `Expand()` не годится, когда остатка нет: колонка схлопывается в 0, вместо него собственное
  бюджетирование + `Auto`; (б) рецепт компактной таблицы `body { cellBorders = Borders.LEFT_RIGHT }`
  и фактическое поведение `tableBorders` в 3.0.1; (в) `terminal.size.width` как источник ширины;
  новая секция про Clikt: `context { terminal = ... }` + `CliktCommand.test(argv, width = ...)`,
  и про то, что одноимённая опция подкоманды затеняет корневую молча.
- `docs/PLAN.md` и `docs/TECHNICAL_PLAN.md`: строка/абзац «Этап 7 — читаемый обзор ✅» с точками входа
  (`ui/PathDisplay.kt`, `ui/TableLayout.kt`, `scan/RootSelection.kt`).
- `docs/POST_MVP_PLAN.md`: перенести в идеи то, что осознанно НЕ делаем сейчас — сортировка/группировка
  вывода, ширина колонок из конфига, `--format=json`/`--porcelain` для скриптов.

## 9. Критерий готовности (DoD)

1. `./gradlew build` зелёный (все тесты раздела 6 включены и проходят).
2. `./gradlew installDist` выполнен, **вся** таблица раздела 7 прогнана на `build/install/gwm/bin/gwm`
   и совпала с ожиданиями (тестов недостаточно: ловушка `installDist` уже кусала).
3. Ни одна строка вывода `scan`/`list` не превышает ширину терминала при `COLUMNS` = 200/120/80/60/40.
4. Вывод `scan` на 27 worktree помещается в ≈ 31 строку.
5. Колонка «Путь» на 80 колонках содержательна: видно репо/имя worktree.
6. `gwm --root=X scan` и `gwm scan X` дают ровно один и тот же корень; конфликт — явная ошибка с кодом ≠ 0.
7. `--print-path` печатает абсолютный путь, `cd "$(gwm --print-path ...)"` работает; на неудаче
   stdout пуст и код ≠ 0.
8. `/code-review` на diff ветки пройден, замечания закрыты (до 3 итераций).
9. Документация раздела 8 обновлена.

## 10. Риски и ловушки

- **`installDist` ≠ `build`** — см. DoD п.2.
- **`Borders`/`tableBorders` в Mordant 3.0.1 не задокументированы** — проверять глазами на артефакте,
  находку писать в SKILL.md.
- **Ширина глифов.** Расчёт ведётся по `String.length` в предположении, что `✓ ● ⚠ …` шириной 1.
  Если на артефакте что-то переносится — уменьшить доступную ширину на 1 и зафиксировать причину.
- **Порядок применения `context {}`** при `test()` — проверить первым тестом (Р6, план Б).
- **`currentContext.obj` должен ставиться до раннего `return`** в `Gwm.run()`, иначе баг 4 «почти
  починен»: `scan` снова увидит null.
- **Цвет ломает ширину.** Ширины считать по plain-строкам ДО раскраски; в рендер-тестах использовать
  `AnsiLevel.NONE`.
- **Регрессия `--print-path`.** Любое место, где путь сокращается, должно быть в `ui/`; `PrintPath`
  из `ui/PathDisplay` ничего не импортирует. Проверяется тестом 47.
