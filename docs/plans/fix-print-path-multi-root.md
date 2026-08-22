# План — «`--print-path` тоже мульти-корневой»

Ветка: `fix/print-path-multi-root` (уже создана от `master`). PR в `master` по workflow из `CLAUDE.md`.

---

## 1. Цель

Выровнять `--print-path` (и завязанный на него `gwm cd <fuzzy>` из `gwm shell-init`) с
мульти-корневым `scan` (Этап 9): когда нет явного CLI/env-override корня, резолвить fuzzy-запрос
по worktree, агрегированным **из ВСЕХ существующих** корней `config.roots`, а не только из
`roots[0]` (`primaryRoot()`).

Сейчас разрыв виден пользователю: `gwm scan` показывает репозитории из всех корней портфеля, а
`gwm cd <имя-репо-во-2-м-корне>` не дотягивается — `PrintPath.emit` берёт единственный корень через
`RepoScanner.resolveRoot(root, config.primaryRoot())`.

## 2. Критический инвариант (нельзя ломать) — shell-контракт `--print-path`

`object PrintPath` (KDoc в `Main.kt`): при УСПЕХЕ печатается РОВНО `<абсолютный путь>\n` сырым
`println` в **stdout** (никакого Mordant/цвета — перехватывается `cd "$(gwm --print-path foo)"`);
при НЕУДАЧЕ — НИЧЕГО в stdout, `CliktError` в **stderr** + non-zero exit. Этот контракт остаётся
байт-в-байт во всех сценариях, включая мульти-корень.

**Следствие для дизайна:** мульти-корневые warning'и `scan` идут через `terminal.println` (stdout).
Для `--print-path` любой такой вывод в stdout ОТРАВИТ путь. Поэтому общий помощник резолвинга корней
НЕ должен сам ничего печатать и НЕ должен бросать при отсутствии корня — он возвращает список корней
+ структурированную диагностику; печатью/бросками распоряжается КАЖДЫЙ вызыватель отдельно:
- `scan` — warning'и в stdout через `terminal.println` (как сейчас), missing-default → `CliktError`;
- `--print-path` — warning'и в **stderr** (`System.err`), несуществующий корень НЕ бросает (пустой
  список репо → `Match.None` → `CliktError` — уже правильное поведение print-path).

## 3. Принятые решения

### Р1. Общий помощник резолвинга корней — чистая функция без I/O-побочек

Проблема: развилка override/мульти-корень сейчас инлайн в `ScanCommand.run` и вперемешку с
`terminal.println`-выводом и `CliktError`. Копировать её в `PrintPath` — дублирование (запрещено
глобальными правилами и §Р7 плана Этапа 9 уже отметил канонизацию путей в 4 местах как техдолг).

Решение: вынести **чистую** функцию, которая по источникам корня отдаёт корни + диагностику, БЕЗ
терминала и без `CliktError`. Помещаю в `scan/MultiRootSelection.kt` рядом с `resolveScanRoots`
(та же ответственность — «превратить источники корней в список корней сканирования»):

```kotlin
// scan/MultiRootSelection.kt

/** Итог резолвинга источников корня в набор корней сканирования + диагностика для вызывателя. */
data class ResolvedRoots(
    /** Корни для сканирования (уже отфильтрованы по isDirectory), в порядке источника. */
    val roots: List<File>,
    /** true = сработал однокорневой override (CLI/GWM_ROOT); false = мульти-корневая ветка. */
    val singleRootOverride: Boolean,
    /** Сконфигурированные, но несуществующие корни (для warning). Пусто в ветке override. */
    val missing: List<String>,
    /** true, когда мульти-корневая ветка НЕ нашла ни одного config-корня и упала на дефолт,
     *  ПРИ ЭТОМ были непустые (не-blank) записи в config.roots (для warning "ни один не найден"). */
    val fellBackToDefaultFromMissing: Boolean,
)

object MultiRootSelection {
    // resolveScanRoots(...) — как есть, не трогаем.

    /**
     * Единая точка развилки override / мульти-корень (Этап 9, Р1), общая для `scan` и `--print-path`.
     * Чистая: ФС только через isDirectory (resolveScanRoots/resolveRoot); НЕ печатает и НЕ бросает —
     * диагностику отдаёт полями ResolvedRoots, вызыватель сам решает, куда её направить и что считать
     * отказом (scan: warning в stdout + CliktError на missing-default; --print-path: warning в stderr,
     * несуществующий корень не отказ — пустой список репо даст Match.None → CliktError выше).
     *
     * @param chosen        согласованное CLI-значение корня (RootSelection.choose), null = CLI молчит
     * @param configRoots   config.roots (для мульти-корневой ветки)
     * @param env           доступ к env (для тестируемости $GWM_ROOT), дефолт System::getenv
     */
    fun resolveRootsToScan(
        chosen: String?,
        configRoots: List<String>,
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): ResolvedRoots
}
```

Логика `resolveRootsToScan` (перенос из `ScanCommand.run`, поведение сохранено):
- `hasSingleRootOverride = chosen != null || !env("GWM_ROOT").isNullOrBlank()`.
- **override:** `rootDir = RepoScanner.resolveRoot(chosen, configRoot = null, env)`; в `roots` кладём
  `listOf(rootDir)` ТОЛЬКО если `rootDir.isDirectory`, иначе пустой список (вызыватель решает — scan
  бросит `CliktError`, print-path даст `Match.None`). `singleRootOverride = true`, `missing = []`.
  - Тонкость: `scan` сейчас бросает `CliktError` на `!rootDir.isDirectory` ДО сбора репо. Чтобы
    сохранить это точь-в-точь, `resolveRootsToScan` в override-ветке при `!isDirectory` возвращает
    `roots = []` и **отдельный признак** — переиспользуем: scan проверит `singleRootOverride &&
    roots.isEmpty()` → бросит `CliktError` со старым текстом. Для этого достаточно `roots.isEmpty()`
    в override-ветке (см. вызыватель scan ниже) — отдельный флаг не нужен.
- **мульти-корень:** `sr = resolveScanRoots(configRoots, home)`.
  - `missing = sr.missing`.
  - если `sr.roots` непуст → `roots = sr.roots`, `fellBackToDefaultFromMissing = false`.
  - если `sr.roots` пуст:
    - `rootsToScan = listOf(RepoScanner.defaultRoot())` отфильтрованный по `isDirectory`
      (несуществующий дефолт → пустой список).
    - `fellBackToDefaultFromMissing = sr.missing.isNotEmpty()` (были непустые config-записи, но ни
      одна не существует — обобщённый warning; пустой/blank-only config → false, тихий дефолт).
  - `singleRootOverride = false`.

**Замечание про env в `resolveRoot`.** Текущий `ScanCommand` вызывает `RepoScanner.resolveRoot(chosen,
configRoot = null)` (env по умолчанию `System::getenv`) — прокидываю тот же `env` в
`resolveRootsToScan` → `resolveRoot`, чтобы `$GWM_ROOT` учитывался в override-ветке И был
инъектируемым в юнит-тестах helper'а. Поведение прод-кода не меняется (дефолт тот же).

### Р2. `ScanCommand.run` — переиспользует helper, вывод/CliktError остаются в команде

`ScanCommand.run` после `RootSelection.choose` → `chosen`:
```kotlin
val rr = MultiRootSelection.resolveRootsToScan(chosen, globals?.config?.roots.orEmpty())
if (rr.singleRootOverride && rr.roots.isEmpty()) {
    throw CliktError("Корень портфеля не найден или не директория: ${RepoScanner.resolveRoot(chosen, null).path}")
}
if (!rr.singleRootOverride) {
    if (rr.missing.isNotEmpty() && rr.roots.isNotEmpty())
        terminal.println(brightYellow("⚠ пропущены несуществующие корни из конфига: ${rr.missing.joinToString(", ")}"))
    if (rr.fellBackToDefaultFromMissing)
        terminal.println(brightYellow("⚠ ни один из корней в конфиге (${rr.missing.joinToString(", ")}) не найден — используется корень по умолчанию."))
    if (rr.roots.isEmpty())  // мульти-ветка + дефолт тоже не существует → как Этап 9 bug1
        throw CliktError("Корень портфеля не найден или не директория: ${RepoScanner.defaultRoot().path}")
}
val rootsToScan = rr.roots
val base = rootsToScan.singleOrNull()
```
Далее — БЕЗ изменений: `flatMap findRepos`, `dedupRepos`, пустой результат → warning + return,
заголовок (один/несколько корней), `scan`, рендер, ошибки репо.

**Проверка эквивалентности со старым `ScanCommand`:** все ветки warning/CliktError сохранены
1-в-1 (тексты идентичны), меняется только МЕСТО принятия решения о списке корней (в helper). Все
существующие тесты `ScanCommandTest` (35, 35b, 36-41, все regression) должны остаться зелёными без
правок — это и есть критерий отсутствия регресса.

### Р3. `PrintPath.emit` — принимает config-источники, warning в stderr, контракт stdout цел

Меняю сигнатуру `emit`, чтобы она видела те же источники, что и scan:
```kotlin
fun emit(query: String, root: String?, config: GwmConfig) {
    // root = корневой gwm --root (единственный CLI-источник корня для print-path; позиционного
    // ROOT / scan --root у корневой команды нет). Сводим к chosen так же, как scan сводит три источника.
    val chosen = root?.takeIf { it.isNotBlank() }
    val rr = MultiRootSelection.resolveRootsToScan(chosen, config.roots)
    // Диагностику — в STDERR (не в stdout: его перехватывает `cd "$(...)"`; см. KDoc object PrintPath).
    if (!rr.singleRootOverride) {
        if (rr.missing.isNotEmpty() && rr.roots.isNotEmpty())
            System.err.println("⚠ пропущены несуществующие корни из конфига: ${rr.missing.joinToString(", ")}")
        if (rr.fellBackToDefaultFromMissing)
            System.err.println("⚠ ни один из корней в конфиге (${rr.missing.joinToString(", ")}) не найден — используется корень по умолчанию.")
    }
    val repos = ScanService.dedupRepos(rr.roots.flatMap { RepoScanner.findRepos(it) })
    val worktrees = ScanService().scan(repos).worktrees
    when (val match = WorktreeMatcher.resolve(worktrees, query)) { /* как сейчас */ }
}
```
- **Несуществующий корень НЕ бросает** здесь (в отличие от scan): `rr.roots` пуст → `repos` пуст →
  `worktrees` пуст → `Match.None` → `CliktError` (stderr, non-zero, stdout пуст). Это ровно текущее
  поведение print-path на плохом корне — контракт цел.
- **Warning'и — plain `System.err.println` без Mordant-цвета.** print-path и так не имеет доступа к
  `terminal` контекста (это root-команда), а цвет в stderr не отравил бы stdout — но держим stderr
  простым и предсказуемым, консистентно с тем, что print-path вообще избегает Mordant.
- Вызов в `Gwm.run`: `PrintPath.emit(query, root, config)` (было `config.primaryRoot()`).

### Р4. Неоднозначность имён репо из разных корней (`Ambiguous`)

Мульти-корень может свести два РАЗНЫХ репо с одинаковым именем из разных корней в один список
(известный техдолг Этапа 9). `WorktreeMatcher.resolve` уже это обрабатывает: точное совпадение имени
в 2+ worktree → `Match.Ambiguous`, `PrintPath.emit` печатает кандидатов как
`  ${c.repo}/${c.worktree.label} → <абсолютный путь>` — пути РАЗНЫЕ (репо в разных корнях), так что
пользователь их различит. Это то же поведение, что уже сегодня для двух репо `main` в одном корне
(тест `an exact branch name across multiple repos is reported ambiguous`). Отдельного нового
поведения не требуется; тест на межкорневую коллизию добавлю как подтверждение (Р6), но это не новый
код — просто расширение покрытия.

### Р5. `primaryRoot()` больше не используется в `PrintPath` — оставить или удалить?

`GwmConfig.primaryRoot()` после этой правки не зовётся из прод-кода (`PrintPath` был единственным
вызывателем; `scan` не зовёт его с Этапа 9). Решение: **оставить `primaryRoot()`** — у него есть
собственный юнит-тест (`GwmConfigTest`), удаление тянет удаление теста и сужает публичный API
конфига без выгоды; это чистая функция без побочек. Зафиксировать в KDoc `primaryRoot`, что прямых
вызывателей в проде больше нет (кандидат на удаление, если так и останется). НЕ трогаю сам метод.
(Альтернатива — удалить — отвергнута: расширяет diff, ломает тест, а метод безвреден.)

## 4. Затрагиваемые файлы

- **`scan/MultiRootSelection.kt`** (изменяемый): + `data class ResolvedRoots`, + `fun
  resolveRootsToScan(chosen, configRoots, env, home): ResolvedRoots`. `resolveScanRoots` и `ScanRoots`
  — без изменений.
- **`Main.kt`** (изменяемый):
  - `Gwm.run`: `PrintPath.emit(query, root, config)` вместо `PrintPath.emit(query, root, config.primaryRoot())`.
  - `object PrintPath.emit`: новая сигнатура `(query, root, config)`, вызов `resolveRootsToScan`,
    warning в stderr, сбор репо из всех корней + `dedupRepos`. Резолвинг/печать пути — без изменений.
  - `ScanCommand.run`: развилка override/мульти заменена вызовом `resolveRootsToScan` + локальная
    печать warning/CliktError (Р2). Хвост (findRepos/dedup/заголовок/scan/рендер) — без изменений.
- **`config/GwmConfig.kt`**: только KDoc-пометка у `primaryRoot` (прод-вызывателей нет). Метод не трогаю.

### Не трогать
- `scan/RootSelection.kt`, `scan/RepoScanner.kt` (`resolveRoot`/`findRepos`/`expandTilde`/`defaultRoot`),
  `scan/ScanService.kt` (`scan`/`dedupRepos`/`scanAsync`), `WorktreeMatcher.kt`, `ui/*`, парсер конфига,
  команды `list`/`interactive`/`create`/`remove`, `ShellInit.kt`.

## 5. Пошаговый план

1. `MultiRootSelection.kt`: `ResolvedRoots` + `resolveRootsToScan` (перенос логики развилки из
   `ScanCommand`, чистая, без печати/бросков).
2. `Main.kt` `ScanCommand.run`: заменить инлайн-развилку вызовом helper + локальные warning/CliktError.
   Проверить, что вывод и exit-коды идентичны старым (сверка по существующим тестам).
3. `Main.kt` `PrintPath.emit`: новая сигнатура + helper + stderr-warning + мульти-корневой сбор репо.
4. `Gwm.run`: обновить вызов `PrintPath.emit`.
5. KDoc: `resolveRootsToScan` (развилка, «не печатает/не бросает»), `PrintPath` (мульти-корень,
   warning в stderr, контракт цел), `primaryRoot` (нет прод-вызывателей).
6. Тесты (§6).
7. `./gradlew clean build` зелёный.
8. Ручная проверка на `installDist` (§7 брифа): два временных корня, конфиг с обоими,
   `--config ... --print-path <fuzzy из 2-го корня>` резолвит; scan и print-path видят одинаковый
   набор репо.
9. Доки: `POST_MVP_PLAN.md` (снять техдолг «print-path однокорневой»), `TECHNICAL_PLAN.md` §7 (строка),
   `SKILL.md` (раздел мульти-корня — print-path теперь тоже мульти-корневой).

## 6. Тест-кейсы

### Юнит — `MultiRootSelectionTest.kt` (дополнить, без git)
- `resolveRootsToScan`, override через `chosen` (существующий `@TempDir`) → `roots=[тот]`,
  `singleRootOverride=true`, `missing=[]`.
- `resolveRootsToScan`, override через `$GWM_ROOT` (инъекция `env`, существующий `@TempDir`, `chosen=null`)
  → `singleRootOverride=true`, `roots=[тот]`. (Подтверждает, что env считается override.)
- `resolveRootsToScan`, override на несуществующий путь (`chosen`) → `roots=[]`, `singleRootOverride=true`.
- `resolveRootsToScan`, мульти: два существующих `@TempDir` в `configRoots`, `chosen=null`, `env` без
  `GWM_ROOT` → `roots=[оба]`, `singleRootOverride=false`, `missing=[]`, `fellBackToDefaultFromMissing=false`.
- `resolveRootsToScan`, мульти-partial: [существующий, несуществующий] → `roots=[существующий]`,
  `missing=[несуществующий]`, `fellBack...=false`.
- `resolveRootsToScan`, мульти, все config-корни отсутствуют, `home`→`@TempDir` с созданным
  `Projects/ai-projects` → `roots=[дефолт]`, `fellBackToDefaultFromMissing=true`, `missing=[оба]`.
- `resolveRootsToScan`, пустой `configRoots`, `home`→`@TempDir` с дефолтом → `roots=[дефолт]`,
  `fellBackToDefaultFromMissing=false` (тихий дефолт).
  **ВСЕ тесты, полагающиеся на `defaultRoot()`, ОБЯЗАНЫ подменять `System.setProperty("user.home", @TempDir)`
  и восстанавливать в finally** (урок Этапа 9 bug1: иначе зелено локально, красно на CI).

### Интеграционные `--print-path` — `PrintPathIntegrationTest.kt` (дополнить, реальный git)
Текущий `resolve(root, query)` в тесте вызывает `findRepos(root)` по ОДНОМУ корню. Добавить хелпер
`resolveMulti(roots: List<File>, query)` = `ScanService.dedupRepos(roots.flatMap{findRepos})` → `scan`
→ `WorktreeMatcher.resolve` (зеркалит новый `PrintPath.emit`), и кейсы:
- **Резолв из ВТОРОГО корня:** два `@TempDir` корня, репо+worktree только во втором; `resolveMulti([root1,root2], <fuzzy>)`
  → `Match.Found`, путь = worktree из root2. (Прямое подтверждение цели.)
- **Дедуп при одном репо из двух корней:** тот же корень указан дважды в списке → один worktree в
  результате (число совпадает с однокорневым). Подтверждает, что `dedupRepos` в print-path работает.
- **Межкорневая коллизия имён:** `root1/foo` и `root2/foo` (разные репо, одно имя, оба с веткой
  `main`) → `resolveMulti(..., "main")` = `Match.Ambiguous`, 2 кандидата с РАЗНЫМИ путями (Р4).

### Командные `--print-path` через Clikt `test()` — `PrintPathCommandTest.kt` (новый) ИЛИ дополнить `ScanCommandTest`-паттерном
Проверяют полный путь `Gwm().test("--config=... --print-path <fuzzy>")`, включая stdout/exit:
- **Мульти-корень, резолв из 2-го корня:** конфиг с двумя `@TempDir` (репо+worktree во 2-м),
  `--config=... --print-path <fuzzy>` (без `--root`) → exit 0, stdout содержит абсолютный путь
  worktree из 2-го корня, НЕТ warning-мусора в stdout. `assumeTrue(gitAvailable())`.
- **CLI `--root` перекрывает конфиг:** конфиг с двумя корнями + `--root=<пустой @TempDir>` +
  `--print-path <fuzzy-из-config>` → не найдено (exit≠0, stdout пуст) — доказывает, что config-корни
  проигнорированы override'ом.
- **Обратная совместимость (один корень / только `--root`):** `--root=<корень с репо> --print-path <fuzzy>`
  → exit 0, stdout = путь. (Байт-идентично прежнему поведению — регресс-страховка shell-контракта.)
- **Partial multi-root: warning в STDERR, НЕ в stdout, при успешном резолве.** Один существующий
  `@TempDir` (репо+worktree) + один несуществующий в конфиге, `--print-path <fuzzy>` → exit 0,
  stdout содержит РОВНО путь (одна строка, без «пропущены»), а «пропущены» уходит в stderr.
  **Ключевой тест на контракт:** проверить, что stdout НЕ содержит слова «пропущены»/«⚠».
  (Как отделить stdout от stderr в Clikt `test()`: `test(...).output` = только stdout; stderr
  проверяется отдельно — см. ниже.)
  - **Уточнение по Clikt `test()`:** `CommandResult.output` — это перехваченный терминал (stdout).
    `System.err.println` идёт мимо него. Значит достаточно проверить, что `.output` (одна строка =
    путь) НЕ содержит warning-текста. Дополнительно можно перехватить `System.err` через
    `System.setErr(...)` на время вызова, если нужно ПОДТВЕРДИТЬ, что warning ушёл в stderr — сделать,
    если тривиально; иначе ограничиться проверкой «stdout чист».
- Все прежние тесты `PrintPathIntegrationTest` — зелёные без правок (сигнатура `emit` внутренняя, а
  тест зовёт `WorktreeMatcher`/`ScanService` напрямую, не `emit`).

## 7. Критерий готовности

- `./gradlew clean build` зелёный: компиляция + все тесты (старые + новые).
- `resolveRootsToScan` — чистая, покрыта юнит-таблицами (override / мульти / partial / all-missing /
  empty), тесты с дефолтом подменяют `user.home`.
- `--print-path` резолвит worktree из НЕ-первого корня; warning'и о пропущенных корнях уходят в
  stderr, stdout при успехе = РОВНО путь (shell-контракт цел).
- CLI `--root` по-прежнему сужает print-path до одного корня; один корень / `--root` — байт-идентично
  прежнему.
- `scan` не регрессировал: все существующие `ScanCommandTest` зелёные без правок.
- Ручная проверка на `installDist`: `--config <2 корня> --print-path <fuzzy из 2-го>` резолвит; `scan`
  и `--print-path` видят одинаковый набор репо при одинаковом конфиге.
- Доки актуализированы: `POST_MVP_PLAN.md` (техдолг снят), `TECHNICAL_PLAN.md` §7, `SKILL.md`.
- Не тронуты: `RootSelection`/`RepoScanner`/`ScanService`/`WorktreeMatcher`/`ui`/парсер конфига/
  `list`/`interactive`/`create`/`remove`/`ShellInit`.

## 8. Post-review addendum

Независимое ревью нашло, что §Р2/Р3 выше буквально дублировали ТЕКСТ двух warning'ов и пайплайн
«корни → репозитории» (`flatMap findRepos` + `dedupRepos`) между `PrintPath.emit` и `ScanCommand.run`.
Почищено: `formatMissingRootsWarning`/`formatAllRootsMissingWarning` (текст без потока/стилизации) и
`ResolvedRoots.reposToScan()` вынесены в `MultiRootSelection.kt`; оба вызывающих места используют их и
сами решают КУДА печатать (stderr `println` vs stdout `terminal.println(brightYellow(...))`).
Заодно `ResolvedRoots` получила `rejectedSingleRoot: File?`, чтобы `ScanCommand` не резолвил
override-корень повторно только ради текста ошибки. Код в §Р2/Р3 выше остаётся как исторический
конспект решения на момент реализации; актуальная форма — в исходниках.
