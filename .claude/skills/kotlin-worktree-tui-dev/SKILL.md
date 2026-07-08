---
name: kotlin-worktree-tui-dev
description: Конвенции и технические решения разработки aiGitWorktreeManager (gwm) — Kotlin JVM CLI/TUI-менеджер git worktree. Обёртка над git через ProcessBuilder, парсинг `git worktree list --porcelain`, TUI на Mordant + Clikt, слои git/scan/ui, интеграционные тесты на временном репо. Использовать при работе над любым кодом инструмента.
---

# kotlin-worktree-tui-dev — aiGitWorktreeManager (gwm)

Специфика **этого** проекта, не общий туториал по Kotlin CLI. Обоснования — `docs/TECHNICAL_PLAN.md`.

## Ядро продукта: обёртка над настоящим git, не замена

`gwm` **ничего не пишет в `.git` напрямую**. Каждая операция (list/add/remove/prune) — вызов установленного у пользователя `git` через `ProcessBuilder`. Причина — точное соответствие семантике worktree (locks, prune, prunable-флаги, `--porcelain`-форматы) и лёгкость (не тянем JGit). При ошибке git — показывать его `stderr` дословно, не «глотать».

## Обёртка над git: `GitCommand` (ProcessBuilder)

- Единая точка запуска — `GitCommand.run(workingDir, vararg args, timeoutSeconds)`. **Всегда** явная рабочая директория и **всегда** таймаут (дефолт 30 с) с `destroyForcibly()` — зависший git не должен фризить TUI.
- stdout/stderr **разделены** (`redirectErrorStream(false)`), возвращаются в `GitResult(exitCode, stdout, stderr)`; `ok = exitCode == 0`.
- Предпочитать **машинные форматы**: `--porcelain`, при списках путей — `-z` (NUL-разделители), а не человекочитаемый вывод (колоночное выравнивание брезгливо парсится).
- Не строить shell-строку и не запускать через `sh -c` — передавать аргументы списком (нет проблем с экранированием/инъекцией путей с пробелами).

## Парсинг `git worktree list --porcelain`

Формат — записи, разделённые пустой строкой; в записи строки `ключ значение`, булевы атрибуты — голым ключом:
```
worktree /path
HEAD <sha>
branch refs/heads/<name>
locked            # или: detached / bare / prunable <reason>
```
- Парсер (`WorktreeParser.parse`) — **чистая функция** `String -> List<Worktree>`: не знает ни про процессы, ни про терминал. Это самая хрупкая логика → покрывается юнит-тестами исчерпывающе (main/linked/detached/bare/prunable/пустой ввод).
- `branch` — снимать префикс `refs/heads/`. Первая запись — всегда main (primary) worktree (`isMain = index == 0`).
- Разделять записи по `\r?\n\r?\n` (устойчиво к CRLF), внутри — по строкам, `key = substringBefore(' ')`, `value = substringAfter(' ')`.

## Слои (держать раздельно, тестировать раздельно)

- `git/` — доменная модель (`Worktree`), чистый парсер (`WorktreeParser`), обёртка процесса (`GitCommand`), операции над одним репо (`WorktreeService`: list/withDirtyFlags/add/remove/prune).
- `scan/` (Фаза 2) — обход корня репозиториев (`~/Projects/ai-projects/*`, конфигурируемо), агрегация worktree всех репо, orphaned-эвристики. Параллелить через coroutines.
- `ui/` (Этап 1+) — интерактивные экраны Mordant. UI-слой **тонкий**: вся логика — в `git/`/`scan/`, чтобы её можно было тестировать без TTY.

**Правило:** чистая логика (парсинг, скоринг orphaned) не должна зависеть от I/O. `dirty`-статус наполняется отдельным шагом (`withDirtyFlags`), т.к. `git status` — дорогая часть; дешёвый `list` отдаётся первым.

## TUI: Mordant + Clikt

- **Clikt** — подкоманды/аргументы/help (`Gwm().subcommands(ListCommand(), ...)`). Тексты help — на русском (пользовательские), код — на английском.
- **Mordant** — рендеринг: `table { header {} body {} }`, цвета (`TextColors`), стили (`TextStyles.bold`), интерактивный выбор (`interactiveSelectList`, prompt). Для MVP хватает line-oriented интерактивности; full-screen (alternate screen) — post-MVP, не усложнять раньше времени.
- Для `./gradlew run` в `build.gradle.kts` проброшен `standardInput = System.in`, иначе Mordant не читает реальный терминал.
- Статусы: `✓ clean` (green) / `● dirty` (yellow) / `?` (gray, если не проверяли). main-worktree — `bold`.

## Переключение cwd

Дочерний процесс не может сменить cwd родительского шелла (POSIX). Решение: режим `--print-path` (печать **только** пути в stdout) + shell-функция-обёртка `gwm()` в `.bashrc`, которую пользователь ставит один раз (`gwm shell-init` печатает готовый сниппет). `gwm` **не редактирует dotfiles сам**. Детали — `docs/TECHNICAL_PLAN.md` §5.

## Разрушающие действия

`gwm` **никогда не удаляет worktree сам** — даже orphaned. Удаление — только по явному подтверждению пользователя в TUI. Dirty worktree удаляются с `--force` только после отдельного явного согласия. Orphaned-детекция — это **подсказка человеку**, не автоочистка.

## Тестирование

- **Юнит (без git, без TTY):** `WorktreeParser` и orphaned-эвристики — детерминированные таблицы вход/выход. `./gradlew test`.
- **Интеграционные (с Этапа 1):** `GitCommand`/`WorktreeService`/`scan` на реальном временном git-репо в `@TempDir` с настоящими worktree — детерминированный фейк реального стека, **не** мок ProcessBuilder. Не ограничиваться мок-юнитами: git-обёртке нужен интеграционный ярус.

## Сборка

- `org.gradle.configuration-cache=false` — осознанно (ложные ошибки сериализации в соседнем Kotlin-проекте портфеля). Не включать без измеренной необходимости и проверки зелёной сборки.
- toolchain JDK 17, wrapper 8.12. Перед коммитом — `./gradlew build` зелёный.
