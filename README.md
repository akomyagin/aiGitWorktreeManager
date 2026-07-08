# aiGitWorktreeManager (`gwm`)

TUI-менеджер `git worktree` на Kotlin. Единый обзор и управление worktree сразу по нескольким локальным репозиториям — прямо из терминала.

`git worktree` из коробки функционален, но неудобен: нет обзора «что у меня вообще есть», нужно помнить пути, легко забыть про осиротевшие worktree после удаления ветки. `gwm` даёт TUI-обзор по всем твоим репозиториям сразу (по умолчанию `~/Projects/ai-projects/*`): какие worktree есть, на какой ветке, чистые/грязные, какие orphaned — с созданием, удалением и переключением из TUI.

> Личный pet-проект соло-разработчика. Локальный, офлайн, стоимость эксплуатации ~$0. Полное видение — [`docs/PLAN.md`](docs/PLAN.md); технический план и обоснование стека — [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md); границы и идеи «на потом» — [`docs/POST_MVP_PLAN.md`](docs/POST_MVP_PLAN.md).

## Статус

MVP готов (Фазы 1–2, Этапы 0–6): обзор и управление worktree по одному репо (`list`/`interactive`/`create`/`remove`), агрегированный обзор всех репозиториев портфеля (`scan`), пометка orphaned/stale worktree и быстрое переключение между worktree (`--print-path` + `gwm shell-init`). Детали и границы — в [`docs/PLAN.md`](docs/PLAN.md) / [`docs/POST_MVP_PLAN.md`](docs/POST_MVP_PLAN.md).

## Стек

Kotlin (JVM, JDK 17) · Gradle · [Mordant](https://github.com/ajalt/mordant) (терминальный UI) · [Clikt](https://github.com/ajalt/clikt) (CLI). Под капотом — настоящий `git` через `ProcessBuilder`, без JGit. Обоснование выбора TUI-технологии (JVM CLI vs Kotlin/Native vs Compose Desktop) — в [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md) §1.

## Команды

```bash
gwm list [<repo>]          # worktree одного репо: ветка, статус, orphaned-пометка
gwm interactive [<repo>]   # интерактивный экран (выбор worktree, детали, удаление)
gwm create <branch> [<path>] [--repo <repo>] [--base <ref>]   # создать worktree
gwm remove <path|branch> [--repo <repo>] [--force]            # удалить worktree
gwm scan [--root <dir>]    # агрегированный обзор worktree по всему портфелю
gwm --print-path <fuzzy> [--root <dir>]   # напечатать путь worktree по неточному имени
gwm shell-init             # напечатать shell-функцию для быстрого перехода
```

Корень портфеля по умолчанию — `~/Projects/ai-projects` (переопределяется флагом `--root` или переменной `GWM_ROOT`).

## Быстрое переключение между worktree (`gwm cd`)

Дочерний процесс не может сменить рабочую директорию родительского шелла (это свойство
POSIX, не баг), поэтому сам `gwm` только **печатает** путь. Для бесшовного перехода
поставь один раз shell-функцию-обёртку — она вызывает `gwm --print-path` и делает `cd`
уже в твоём шелле:

```bash
# в ~/.bashrc или ~/.zshrc:
eval "$(gwm shell-init)"
```

После этого:

```bash
gwm cd login          # перейти в worktree, чьё имя/ветка содержит "login"
```

Обёртка делает `cd` **только при успешном** поиске (`path=$(gwm --print-path …) && cd …`):
если совпадений нет или их несколько, `gwm` завершается с ненулевым кодом и печатает ошибку
в stderr, а обёртка **никуда не переходит** — ты остаёшься в текущей директории (не улетишь
в `$HOME`). `gwm` печатает сниппет, но сам dotfiles **не редактирует**.

Без установки обёртки то же самое доступно вручную через подстановку:

```bash
cd "$(gwm --print-path login)"
```

## Сборка и запуск

```bash
source ~/.sdkman/bin/sdkman-init.sh   # JDK 17 через SDKMAN

./gradlew build          # компиляция + тесты
./gradlew test           # только тесты
./gradlew run --args="list ."          # показать worktree текущего репо
./gradlew installDist    # собрать запускаемый дистрибутив
./build/install/gwm/bin/gwm list .     # запуск собранного бинаря
```

## Лицензия

MIT — см. [LICENSE](LICENSE).
