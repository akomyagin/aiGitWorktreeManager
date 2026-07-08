# aiGitWorktreeManager (`gwm`)

TUI-менеджер `git worktree` на Kotlin. Единый обзор и управление worktree сразу по нескольким локальным репозиториям — прямо из терминала.

`git worktree` из коробки функционален, но неудобен: нет обзора «что у меня вообще есть», нужно помнить пути, легко забыть про осиротевшие worktree после удаления ветки. `gwm` даёт TUI-обзор по всем твоим репозиториям сразу (по умолчанию `~/Projects/ai-projects/*`): какие worktree есть, на какой ветке, чистые/грязные, какие orphaned — с созданием, удалением и переключением из TUI.

> Личный pet-проект соло-разработчика. Локальный, офлайн, стоимость эксплуатации ~$0. Полное видение — [`docs/PLAN.md`](docs/PLAN.md); технический план и обоснование стека — [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md); границы и идеи «на потом» — [`docs/POST_MVP_PLAN.md`](docs/POST_MVP_PLAN.md).

## Статус

Этап 0 (bootstrap) готов: Gradle-скелет, парсер `git worktree list --porcelain`, команда `gwm list`. Дальнейшие Этапы — в [`docs/PLAN.md`](docs/PLAN.md).

## Стек

Kotlin (JVM, JDK 17) · Gradle · [Mordant](https://github.com/ajalt/mordant) (терминальный UI) · [Clikt](https://github.com/ajalt/clikt) (CLI). Под капотом — настоящий `git` через `ProcessBuilder`, без JGit. Обоснование выбора TUI-технологии (JVM CLI vs Kotlin/Native vs Compose Desktop) — в [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md) §1.

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
