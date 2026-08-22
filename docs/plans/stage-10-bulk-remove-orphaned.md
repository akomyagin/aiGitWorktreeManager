# Этап 10 — Bulk-удаление orphaned worktree (`gwm clean`)

Ветка: `stage/10-bulk-remove-orphaned` (от `master`). Реализатор: Opus по этому плану, без git-коммитов.

## Цель

Дать одну команду, которая находит **все orphaned worktree по всему портфелю** (все существующие корни из `config.roots`, как `scan` в Этапе 9) и удаляет их за один прогон — **строго по явному подтверждению**, с отдельным барьером для каждого dirty. Идея зафиксирована в `docs/POST_MVP_PLAN.md` §Функциональность: «Bulk-операции: удалить все orphaned за раз (с обязательным подтверждением каждого dirty)».

Это **первая** разрушающая bulk-операция в инструменте. Она НЕ ослабляет ни один существующий инвариант безопасности — она их переиспользует (`safeRemove` → `BLOCKED_DIRTY`, явное согласие на `--force`) и добавляет поверх агрегированный gate.

## Инвариант безопасности (нельзя нарушать)

Из `CLAUDE.md` / `SKILL.md` / `POST_MVP_PLAN.md`:

1. **`gwm` никогда не удаляет worktree сам, даже orphaned.** Значит: bulk-режим по умолчанию НИЧЕГО не удаляет без явного «да» пользователя на конкретный, показанный ему список.
2. **Все мутации — через настоящий `git`** (`WorktreeService.safeRemove`/`remove` → `git worktree remove`), никогда напрямую в `.git`. При ошибке git — показывать `stderr`, не «глотать».
3. **Dirty удаляется только по отдельному явному согласию.** `--force` в текущем `RemoveCommand` — это флаг пользователя на КОНКРЕТНОЕ удаление, а не общий bypass. В bulk мы сохраняем ту же семантику: **нет одного общего "да" на все dirty**. Каждый dirty worktree по умолчанию **пропускается** (не удаляется), если пользователь явно не согласился на потерю его изменений.
4. **orphaned-детекция — подсказка, не автоочистка.** Даже non-dirty orphaned удаляются только после того, как пользователь один раз подтвердил показанный ему список.
5. **Никогда не трогать main worktree.** `OrphanClassifier` уже никогда не помечает main как orphaned (`isMain → ACTIVE`), но bulk обязан ещё раз это гарантировать явным фильтром (defense-in-depth): main НИКОГДА не попадает в список кандидатов.
6. **Exit-код-контракт** (`SKILL.md` «Exit-коды»): все ветки-отказа — через `throw CliktError(...)`, не `println + return`. Успех (в т.ч. «нечего удалять») — exit 0.

Если при реализации возникает сомнение — выбирать более консервативный (более явный, более подтверждающий, менее удаляющий) вариант.

## Принятые дизайн-решения (обоснование)

### Форма команды: новая подкоманда `gwm clean`

Отдельная подкоманда `clean`, а НЕ флаг `remove --orphaned`:
- `RemoveCommand` — однорепозиторный, берёт один `target` (путь/ветку), один `--repo`. Bulk — портфельный и без target. Смешать две модели в одной команде (target обязателен vs. запрещён, `--repo` vs. `--root`) — испортить обе, как `RootSelection`/`MultiRootSelection` держат раздельно (см. `SKILL.md`).
- `clean` встаёт в один ряд с `scan`/`shell-init` как портфельная команда; регистрируется в `Gwm().subcommands(...)` (Main.kt).
- Расширять `interactive` в этом Этапе **НЕ надо** (см. Границы).

### Область действия: весь портфель, тем же fork'ом, что и `scan`

`clean` переиспользует `MultiRootSelection.resolveRootsToScan` + `ResolvedRoots.reposToScan()` — ту же машинерию, что `scan` и `--print-path` (Этап 9). Никакого дублирования логики выбора корней.
- Тот же override-триггер: явный CLI-корень (`clean --root` / `gwm --root`) или `$GWM_ROOT` → однокорневой override; иначе — мульти-корень по `config.roots`.
- Та же изоляция несуществующих корней: warning (stderr для diagnostics? — см. ниже поток вывода), exit 0; missing single/default-корень → `CliktError`.
- Bulk по определению полезнее всего на масштабе портфеля — а `remove` остаётся однорепозиторным. Это осознанное разделение, а не непоследовательность.

### UX подтверждения (вариант «в»: агрегированный gate + построчный dirty)

Поток:
1. **Собрать кандидатов.** Просканировать портфель (`ScanService().scan(repos)` — переиспользуем, он уже наполняет `dirty` и `orphan`). Кандидаты = worktree, где `orphan.isOrphaned == true` И `!isMain`. (`ScanService` уже вызывает `withDirtyFlags` и `withOrphanStatus`.)
2. **Пусто → успех, exit 0.** Напечатать «Orphaned worktree не найдено — нечего удалять.» и `return`. НЕ ошибка.
3. **Показать полный список** кандидатов ДО любого удаления: репо / ветка / путь / статус (`✓ clean` / `● dirty`) / причины orphaned. Явно пометить, сколько всего N и сколько из них dirty M.
4. **Агрегированный gate (один на весь прогон):** `YesNoPrompt("Удалить N orphaned worktree (из них M dirty будут пропущены без --force)? ...", default = false)`.
   - `нет`/Enter → «Отменено.», exit 0, ничего не удалено.
   - `да` → перейти к удалению.
5. **Удаление, построчно, с политикой по dirty:**
   - **non-dirty кандидат:** удаляется без дополнительного prompt (пользователь уже подтвердил именно этот список на шаге 4). Это допустимое послабление из брифа: «non-dirty orphaned не требует поочерёдного prompt, если общий список показан заранее и пользователь один раз подтвердил именно этот список».
   - **dirty кандидат:** по умолчанию (без `--force`) — **пропускается** с пометкой «⚠ <repo>/<branch>: пропущен (незакоммиченные изменения; --force для удаления)». НЕ удаляется. Это точное соответствие `safeRemove(force=false) → BLOCKED_DIRTY`.
   - **dirty + флаг `--force`:** для КАЖДОГО dirty — **отдельный** `YesNoPrompt("⚠ <repo>/<branch> ДETALI: удалить с потерей незакоммиченных изменений?", default=false)`. Только на явное «да» — `safeRemove(force=true)`. На «нет» — пропустить с пометкой. Так `--force` остаётся «пользователь явно согласился на КОНКРЕТНОЕ удаление» (перечисление + отдельное «да» на каждый), а не общий bypass.
6. **Неинтерактивный режим (`--yes`):** флаг `--yes`/`-y` заменяет агрегированный gate шага 4 (для скриптов/CI). ВАЖНО: `--yes` подтверждает удаление non-dirty кандидатов, но **НЕ** является согласием на dirty. Без `--force` dirty всё равно пропускаются; с `--force` в неинтерактивном режиме поочерёдный prompt шага 5 невозможен (нет TTY) → в комбинации `--yes --force` каждый dirty удаляется как явно санкционированный (пользователь дал оба явных флага: «да на список» + «force на dirty»). Это единственный путь к массовому force-удалению, и он требует ДВУХ явных флагов одновременно — консервативно и скриптуемо.

Почему не Mordant multiselect: не нужен и хуже тестируется. Line-oriented `YesNoPrompt` уже используется в `InteractiveScreen` и достаточен (`SKILL.md`: full-screen — post-MVP). Логику «кто кандидат / кто dirty / что делать» вынести в **чистую функцию** (см. `BulkCleanPlan.plan` ниже) — она и покрывается юнит-тестами без TTY; сам prompt-I/O остаётся тонким.

### Атомарность / частичный отказ: продолжать, собрать сводку

Если одно удаление падает (`GIT_ERROR`) — **не останавливаться**, продолжить с остальными, накопить ошибки и вывести сводку в конце (как `RepoError` в `scan`, `SKILL.md` «Изоляция ошибок»). Итоговый exit-код: **non-zero (`CliktError`) если была хотя бы одна git-ошибка удаления**; иначе 0. Пропуски по dirty — это НЕ ошибка (exit 0). Финальная сводка: «Удалено: X, пропущено (dirty): Y, ошибок: Z».

### Dry-run: флаг `--dry-run`

Дать `--dry-run`: печатает список кандидатов (шаг 3) и сводку «что было бы удалено / пропущено», НЕ удаляет ничего и НЕ спрашивает подтверждения. Причина: bulk-удаление опаснее одиночного; дешёвая явная «репетиция» ценна, а `scan` показывает ВСЕ worktree (не только orphaned) и не даёт увидеть именно множество-кандидат bulk-операции. exit 0.

### Non-dirty «safe to delete» без поочерёдного prompt

Non-dirty orphaned (merged / no-upstream / prunable, но clean working tree) — теряют только administrative-запись и ветку-worktree, незакоммиченной работы там нет. После одного агрегированного «да» на показанный список они удаляются без поштучного prompt. Это и есть заложенное в брифе послабление; безопасность держится на том, что (а) список показан заранее, (б) main исключён, (в) dirty отделены и защищены отдельно.

## Затрагиваемые файлы и сигнатуры

### Новый: `src/main/kotlin/dev/alkom/gwm/scan/BulkCleanPlan.kt`

Чистая логика отбора кандидатов и классификации — БЕЗ I/O, БЕЗ git, БЕЗ TTY (по образцу `OrphanClassifier`/`MultiRootSelection`), чтобы исчерпывающе покрыть юнит-тестами.

```kotlin
package dev.alkom.gwm.scan

/** Один worktree-кандидат на bulk-удаление, с уже вычисленными фактами для решения. */
data class CleanCandidate(
    val aggregated: AggregatedWorktree, // repo + Worktree (path, branch, dirty, orphan…)
    val dirty: Boolean,                 // из Worktree.dirty ?: false — null трактуем как НЕ dirty (нечего терять)
    val reasons: List<String>,          // orphan.reasons, для показа
) {
    val repo: String get() = aggregated.repo
    val label: String get() = aggregated.worktree.label
    val path: String get() = aggregated.worktree.path
}

/**
 * Итог планирования bulk-clean: кандидаты, разбитые на удаляемые сразу и требующие защиты по dirty.
 * Пустой [candidates] = нечего удалять.
 */
data class BulkCleanPlan(
    val candidates: List<CleanCandidate>,
) {
    val dirtyCandidates: List<CleanCandidate> get() = candidates.filter { it.dirty }
    val cleanCandidates: List<CleanCandidate> get() = candidates.filter { !it.dirty }
    val total: Int get() = candidates.size
    val dirtyCount: Int get() = dirtyCandidates.size
    val isEmpty: Boolean get() = candidates.isEmpty()

    companion object {
        /**
         * Чистый отбор кандидатов из результата скана. Кандидат ⟺ orphan.isOrphaned И НЕ isMain.
         * main исключается ЯВНО (defense-in-depth поверх OrphanClassifier). Порядок сохраняется
         * как в scan (группировка по репо — инвариант WorktreeTable).
         */
        fun from(worktrees: List<AggregatedWorktree>): BulkCleanPlan
    }
}
```

Правило dirty: `Worktree.dirty` — `Boolean?`; `null` (не проверяли) трактуем как `false` (нечего терять) — но на практике `ScanService` всегда наполняет `dirty` через `withDirtyFlags`, так что `null` тут не встретится; трактовка задокументирована для полноты.

### Новый: `ui/CleanReport.kt` (чистое форматирование строк отчёта)

Чистые функции формирования человекочитаемых строк (список кандидатов, сводка), без Mordant-раскраски в самих функциях (раскраска — на стороне команды, как в `formatMissingRootsWarning`). Тестируются по тексту.

```kotlin
package dev.alkom.gwm.ui
// строки для: заголовка списка, строки одного кандидата (repo/branch/status/reasons/path),
// финальной сводки "Удалено: X, пропущено (dirty): Y, ошибок: Z".
object CleanReport {
    fun candidateLine(c: CleanCandidate, base: java.io.File?): String
    fun summary(removed: Int, skippedDirty: Int, errors: Int): String
}
```
(Переиспользовать `PathDisplay.shorten` для пути — как `scan`/`interactive`.)

### Изменяемый: `Main.kt`

- Новый класс `CleanCommand : CliktCommand(name = "clean")`.
- Регистрация в `main(...)`: добавить `CleanCommand()` в `.subcommands(...)`.
- Опции команды:
  - `rootArg: String?` (позиционный `ROOT`, optional) и `rootOpt: String?` (`--root`) — как в `ScanCommand`, чтобы `clean` был портфельным с тем же fork'ом; свести к `chosen` через `RootSelection.choose` + `MultiRootSelection.resolveRootsToScan` (скопировать паттерн из `ScanCommand.run`, вынести общий кусок при желании, но НЕ обязательно — дублирование ~15 строк допустимо, если аккуратно; предпочтительно вынести резолв корней в приватный хелпер, разделяемый scan/clean, если это не раздувает diff).
  - `dryRun: Boolean by option("--dry-run").flag()` — показать и выйти.
  - `assumeYes: Boolean by option("--yes", "-y").flag()` — заменить агрегированный gate (для скриптов).
  - `force: Boolean by option("--force").flag()` — разрешить удаление dirty (с поштучным подтверждением в TTY; с `--yes --force` — без prompt).
- Help: «Найти и удалить orphaned worktree по всему портфелю (с подтверждением; dirty — только с --force)».

Логика `run()` (псевдо):
```
globals = findObject<GwmGlobals>()
chosen  = reconcile(rootArg, rootOpt, globals.root)  // RootSelection.choose → Conflict = CliktError
rr      = MultiRootSelection.resolveRootsToScan(chosen, globals.config.roots.orEmpty())
// диагностика корней ТОЧНО как в ScanCommand: singleRootOverride пустой → CliktError;
// multi-root missing/fellBack → warning (terminal.println), пустой default → CliktError.
repos   = rr.reposToScan()
if repos пусто → terminal.println(warning "репозитории не найдены в …"); return   // exit 0
result  = ScanService().scan(repos)
plan    = BulkCleanPlan.from(result.worktrees)
// показать result.errors (сканирования) как warning — как в scan
if plan.isEmpty → terminal.println("Orphaned worktree не найдено — нечего удалять."); return
// печать списка кандидатов (CleanReport.candidateLine) + строка "Всего N, из них M dirty"
if dryRun → печать сводки "было бы: удалить cleanCount, пропустить/при force удалить dirtyCount"; return
// gate:
proceed = assumeYes || (interactive? YesNoPrompt(...).ask() : throw CliktError("нет TTY — используйте --yes"))
if !proceed → terminal.println("Отменено."); return
// удаление:
removed=0; skippedDirty=0; gitErrors=[]
for c in plan.candidates:
    svc = WorktreeService(File(reposDirFor(c)))   // см. ниже про resolve репо-каталога
    if c.dirty:
        if !force: skippedDirty++; print skip; continue
        // force:
        ok = assumeYes ? true : (interactive ? YesNoPrompt(per-item, default=false).ask() ?: false : true)
        if !ok: skippedDirty++; print skip; continue
        outcome = svc.safeRemove(c.path, force=true)
    else:
        outcome = svc.safeRemove(c.path, force=false)
    when(outcome.status):
        REMOVED       → removed++; print ok
        GIT_ERROR     → gitErrors += (c, stderr); print err (не прерывать)
        BLOCKED_DIRTY → (только если dirty-флаг разошёлся с реальностью гонки) skippedDirty++; print skip
        NOT_FOUND     → print "уже удалён"; (не ошибка)
print CleanReport.summary(removed, skippedDirty, gitErrors.size)
if gitErrors.isNotEmpty() → throw CliktError(сводка ошибок git)   // non-zero exit
```

**Как получить `WorktreeService` для кандидата.** `AggregatedWorktree` несёт имя репо, но не его `File`-каталог. Нужен путь к репо-каталогу, чтобы завести `WorktreeService(repoDir).safeRemove(...)`. Варианты (выбрать при реализации, минимально инвазивно):
- (а) Расширить `AggregatedWorktree` полем `repoDir: File` — но это тронет доменную модель и все её конструкции (`ScanService.aggregateOne`, тесты). Аккуратно, но шире.
- (б) **Предпочтительно:** в `CleanCommand` держать `repos: List<File>` (из `reposToScan()`), и для каждого репо получить его worktree-каталог из самого worktree. `git worktree remove` работает из ЛЮБОГО worktree того же репо, но чище — вызывать из primary checkout. Проще всего: сгруппировать удаление ПО РЕПО — для каждого `repoDir in repos` открыть `WorktreeService(repoDir)`, взять его orphaned-кандидатов (у которых `aggregated.repo == repoDir.name` И путь принадлежит этому репо) и удалять их этим сервисом. Так `safeRemove(pathOrBranch=c.path)` резолвит worktree внутри своего репо (`findWorktree` по абсолютному пути). Это устраняет неоднозначность при одноимённых репо из разных корней: сопоставление идёт по `repoDir`, а не по имени.

   Реализовать группировку так: пройти `for repoDir in repos { svc=WorktreeService(repoDir); val own = plan.candidates.filter { File(it.path).absoluteFile.normalize().startsWith(repoDir.absoluteFile.normalize().path) || svc.findWorktree(it.path) != null } }`. Достаточно и надёжно: `svc.findWorktree(c.path)` вернёт worktree, только если он принадлежит этому репо. Использовать `svc.findWorktree(c.path) != null` как критерий принадлежности (один git-list на репо, кэшировать).

   Выбрать вариант (б) — он не трогает доменную модель и корректен при одноимённых репо (остаточный техдолг Этапа 9).

### Изменяемый: `docs/POST_MVP_PLAN.md`

В §Функциональность отметить пункт bulk-операций как **Сделано (Этап 10)** с кратким описанием (команда `gwm clean`, портфельный охват через `MultiRootSelection`, агрегированный gate + отдельная защита каждого dirty, `--dry-run`/`--yes`/`--force`, частичный отказ = сводка + non-zero exit). Это делает финальный просмотр (шаг 7 пайплайна) — но реализатору достаточно оставить пометку; итоговые формулировки актуализирует основная сессия.

### Изменяемый: `docs/TECHNICAL_PLAN.md` §7

Добавить строку «Этап 10 (сделано ✅): `gwm clean` — bulk-удаление orphaned по портфелю…». (Опционально для реализатора; основная сессия актуализирует на шаге 7.)

### Обновить skill (опционально, шаг 7): `.claude/skills/kotlin-worktree-tui-dev/SKILL.md`

Зафиксировать находки Этапа 10 (агрегированный gate + per-dirty prompt, `--yes`/`--force` семантика, группировка удаления по repoDir против одноимённых репо). Делает основная сессия.

## Границы — что НЕ делать

- **НЕ трогать `RemoveCommand`** (одиночный remove) — его контракт и тесты остаются как есть.
- **НЕ расширять `interactive`** bulk-режимом в этом Этапе (возможная будущая идея, но не здесь — держим diff сфокусированным).
- **НЕ менять `OrphanClassifier`/`OrphanStatus`/`WorktreeService.safeRemove`** — переиспользуем как есть. Никаких новых эвристик orphaned.
- **НЕ добавлять авто-`prune`** в bulk (prunable-кандидаты удаляются через `git worktree remove`, как обычные; отдельный массовый `prune` — не в этом Этапе).
- **НЕ трогать доменную модель** `Worktree`/`AggregatedWorktree` (вариант (б) выше это позволяет).
- **НЕ вводить общий bypass dirty** одним «да». Каждый dirty — отдельное согласие (TTY) или два явных флага (`--yes --force`).
- **НЕ менять поведение `scan`/`--print-path`** — только переиспользуем их fork.
- **НЕ добавлять сеть/JSON-вывод/сортировку** — вне области.

## Пошаговый план реализации

1. `scan/BulkCleanPlan.kt` — `CleanCandidate`, `BulkCleanPlan`, `BulkCleanPlan.from(...)` (чистая, main-исключение явное). + юнит-тесты сразу.
2. `ui/CleanReport.kt` — `candidateLine`, `summary` (чистые). + юнит-тесты.
3. `Main.kt` — `CleanCommand`: резолв корней (копия/вынос из `ScanCommand`), сбор кандидатов, печать списка, `--dry-run`, агрегированный gate (`YesNoPrompt`/`--yes`), удаление по репо-группам с per-dirty политикой, сводка, `CliktError` при git-ошибках. Регистрация в `subcommands`.
4. Проверить exit-код-контракт: все отказы — `CliktError`; «нечего удалять»/«отменено»/«dry-run» — exit 0; git-ошибка удаления → non-zero.
5. `./gradlew build` (компиляция + тесты) зелёный.
6. Обновить `docs/POST_MVP_PLAN.md` (пометка «Сделано»).

## Тест-кейсы

### Юнит — `BulkCleanPlanTest.kt`
- Пустой вход → `isEmpty`, `total=0`.
- Смесь active/orphaned → в `candidates` только orphaned.
- **main worktree, даже если бы был orphan-помечен → исключён** (собрать `AggregatedWorktree` с `isMain=true` и непустым orphan — убедиться, что не кандидат). Защита инварианта №5.
- dirty/clean разбиение: `dirtyCandidates`/`cleanCandidates`/`dirtyCount` корректны.
- Порядок кандидатов сохраняется как во входе (группировка по репо не ломается).
- `dirty == null` трактуется как не-dirty.

### Юнит — `CleanReportTest.kt`
- `candidateLine` содержит repo, branch, статус-глиф, причины, укороченный путь.
- `summary(2,1,0)` / `summary(0,0,1)` — числа и слова на месте.

### Командные — `CleanCommandTest.kt` (Clikt `test()`, по образцу `ScanCommandTest`)
Реальные временные git-репозитории в `@TempDir` (helper `makeRepo` + `WorktreeService.add`), `assumeTrue(gitAvailable())`. Прогонять с `--yes` (или `--dry-run`), т.к. `test()` не даёт TTY для `YesNoPrompt`.
- **dry-run:** портфель с orphaned worktree (напр. ветка без upstream) → `clean --dry-run <root>` exit 0, вывод называет кандидата, НИЧЕГО не удалено (worktree на месте после).
- **--yes удаляет non-dirty orphaned:** создать worktree на ветке без upstream (no-upstream orphaned, clean) → `clean --yes <root>` exit 0, worktree УДАЛЁН (каталог/`git worktree list` больше не содержит), сводка «Удалено: 1».
- **dirty пропускается без --force:** orphaned worktree + незакоммиченный файл → `clean --yes <root>` exit 0, worktree НА МЕСТЕ, вывод «пропущен … незакоммиченные», сводка «пропущено (dirty): 1».
- **dirty удаляется с `--yes --force`:** тот же dirty → `clean --yes --force <root>` exit 0, worktree УДАЛЁН.
- **нечего удалять:** портфель без orphaned (только main) → `clean --yes <root>` exit 0, «нечего удалять», ничего не тронуто.
- **отмена (без --yes, без TTY):** `clean <root>` (нет `--yes`, нет TTY) → должен НЕ удалять; ожидаемо `CliktError` «нет TTY — используйте --yes» (non-zero) ЛИБО, если решено трактовать отсутствие TTY как отказ, — exit ≠0 и worktree на месте. Зафиксировать выбранное поведение тестом. (Инвариант: без явного «да» — ноль удалений.)
- **main никогда не в кандидатах:** портфель, где main мог бы выглядеть stale, → `clean --dry-run` не перечисляет main. (Дублирует юнит, но на реальном стеке.)
- **портфельный охват (мульти-корень):** два корня в конфиге, orphaned в обоих → `clean --dry-run` (без CLI-корня) перечисляет кандидатов из ОБОИХ корней (переиспользование `MultiRootSelection`). По образцу `multi-root from config aggregates all existing roots`.
- **частичный отказ / сводка:** смоделировать git-ошибку удаления сложно детерминированно; вместо интеграции — покрыть счётчики сводки на уровне логики (юнит `CleanReport.summary`) и проверить, что цикл НЕ прерывается: портфель с двумя non-dirty orphaned → оба удалены, «Удалено: 2» (косвенно проверяет продолжение после каждого). Если удастся детерминированно вызвать `GIT_ERROR` (напр. заранее удалить каталог одного worktree вручную, как в `WorktreeServiceIntegrationTest`), добавить кейс: один REMOVED + сводка, exit-код отражает ошибку.

### Интеграционные — при желании отдельным `BulkCleanIntegrationTest.kt`
Если командные тесты выше уже гоняют реальный git через `test()`, отдельный интеграционный ярус на уровне сервиса не обязателен — `safeRemove` уже покрыт в `WorktreeServiceIntegrationTest`. Добавить только если понадобится проверить группировку удаления по `repoDir` при одноимённых репо из двух корней (два репо `foo` в разных корнях, orphaned в каждом → оба удалены корректно, ничего лишнего).

## Критерий готовности

1. `gwm clean` существует, зарегистрирован, портфельный (мульти-корень через `MultiRootSelection`), с `--dry-run`/`--yes`/`--force`/`--root`(+позиционный ROOT).
2. Без явного согласия (агрегированный gate «да» или `--yes`) — **ноль удалений**.
3. dirty worktree **не удаляется** без `--force`; с `--force` — только после per-item согласия (TTY) или пары флагов `--yes --force`. **Нет общего одного «да» на все dirty.**
4. main worktree никогда не кандидат.
5. Частичный отказ: цикл не прерывается, сводка «Удалено/пропущено/ошибок», non-zero exit при git-ошибке удаления; «нечего удалять»/«отменено»/«dry-run» → exit 0.
6. Все мутации — через `WorktreeService.safeRemove` (настоящий `git`), stderr git не проглатывается.
7. Отказные ветки — `CliktError` (exit-код-контракт), не `println + return`.
8. `./gradlew build` зелёный; новые юнит- и командные тесты покрывают: отбор кандидатов (вкл. исключение main), dry-run, удаление non-dirty, пропуск dirty, force-удаление dirty, мульти-корневой охват, «нечего удалять».
9. `docs/POST_MVP_PLAN.md` — пункт bulk отмечен «Сделано (Этап 10)».
