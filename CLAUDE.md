# CLAUDE.md

Guidance for AI sessions (Claude Code) working in this repository.

## О репозитории

**aiGitWorktreeManager** (`gwm`) — TUI-менеджер `git worktree` на **Kotlin (JVM)**: единый обзор и управление worktree сразу по нескольким локальным репозиториям, прямо из терминала. Чистый локальный CLI/TUI-инструмент, без сервера, стоимость эксплуатации ~$0.

Полное видение — `docs/PLAN.md`; технический план, обоснование стека и разбивка по Этапам — `docs/TECHNICAL_PLAN.md`; границы и post-MVP — `docs/POST_MVP_PLAN.md`.

## Структура

```
docs/                    # PLAN, TECHNICAL_PLAN, POST_MVP_PLAN
.claude/skills/          # kotlin-worktree-tui-dev — конвенции разработки этого инструмента
src/main/kotlin/dev/alkom/gwm/
├── Main.kt              # Clikt entrypoint + подкоманды (тонкий слой UI)
├── git/                 # GitCommand (ProcessBuilder), Worktree, WorktreeParser, WorktreeService
├── scan/                # (Фаза 2) обход корня репозиториев, агрегация, orphaned-эвристики
└── ui/                  # (Этап 1+) интерактивные экраны Mordant
src/test/kotlin/         # юнит-тесты (парсер) + интеграционные (временный git-репо, с Этапа 1)
build.gradle.kts, settings.gradle.kts, gradle.properties
```

## Стек

Kotlin (JVM, JDK 17) · Gradle (wrapper 8.12) · Mordant (TUI) · Clikt (CLI). Под капотом — настоящий `git` через `ProcessBuilder`, не JGit. Package root — `dev.alkom.gwm`. Обоснование выбора JVM CLI (а не Kotlin/Native или Compose Desktop) — `docs/TECHNICAL_PLAN.md` §1.

## Команды

```bash
source ~/.sdkman/bin/sdkman-init.sh   # JDK 17 / Gradle через SDKMAN

./gradlew build          # компиляция + тесты
./gradlew test           # тесты (юнит + интеграционные)
./gradlew run --args="list ."       # запуск
./gradlew installDist    # запускаемый дистрибутив в build/install/gwm/bin/gwm
```

## Git / dev-workflow (обязательный процесс на каждый Этап/задачу)

1. **Opus 4.8** — если требуется детальное планирование этапа — планирование, затем написание кода.
2. **Sonnet** — проверка качества покрытия тестами, тестирование, проверка работоспособности, проверка покрытия новых функций.
3. **Opus** — независимое ревью: skill `/code-review` на diff ветки, фиксируем замечания.
4. **Цикл исправлений** — до 3 итераций: Sonnet правит замечания → тесты снова.
5. **Commit + push + PR** — conventional-commit с русским subject, PR в `master`-ветку.

**Fable 5 не используется.** Главная ветка — **`master`** (не `main`).

## Конвенции

- **Язык:** документация и commit-subject — на русском; код, идентификаторы, code-комментарии — на английском.
- **Commit-стиль:** conventional commits с русским subject, например `feat(stage1): интерактивный TUI-список worktree с навигацией стрелками`. Завершать trailer'ом `Co-Authored-By: Claude`.
- **Ветки:** `stage/<N>-<topic>` от `master`; PR в `master`.
- Перед коммитом кода: `./gradlew build` (компиляция + тесты) зелёный.
- **Обёртка, не замена git:** все мутации worktree — через настоящий `git` (`ProcessBuilder`), никогда напрямую в `.git`. При ошибке git — показывать stderr, не «глотать».
- **Разрушающие действия — только по явному подтверждению.** `gwm` никогда не удаляет worktree сам (даже orphaned).
- **Не редактировать dotfiles пользователя** — только предлагать готовый сниппет shell-функции.

## Почему нет docker-compose

`gwm` — чисто локальный CLI/TUI без сервисного рантайма (нет HTTP-сервера, БД, воркеров). Единственная внешняя зависимость — установленный локально `git`. Поэтому docker-compose не используется и не планируется — подробнее в `docs/TECHNICAL_PLAN.md` §6.
