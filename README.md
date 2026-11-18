# CC Sessions — менеджер сессий Claude Code для PhpStorm / GoLand

Плагин для IDE на платформе IntelliJ 2026.2+. Claude Code работает **как обычно — во встроенном терминале**
(с вашими плагинами GSD/Superpowers, claude-mem, хуками и статуслайном), а плагин добавляет вокруг него:

| Что | Как |
|---|---|
| **Панель сессий** (справа, «CC Sessions») | Все сессии проекта из `~/.claude/projects/<проект>/*.jsonl`: заголовок (`/rename` → AI-title → первый промпт), время, ветка, число промптов, размер контекста, модель |
| **Поиск** | По названию/промпту/ветке/ID мгновенно; от 3 символов — ещё и по полному тексту транскриптов; `#тег` — по тегам |
| **Избранное, теги, свои названия** | Хранятся в `~/.claude/cc-sessions/meta.json` — общий файл для PhpStorm и GoLand |
| **Продолжить / ответвить** | `Enter` / двойной клик → `claude --resume <id>` в новой вкладке терминала — по умолчанию вкладкой в редакторе, рядом с файлами (как «Move to Editor»; в настройках можно вернуть окно Terminal); fork → `--resume <id> --fork-session`. Если вкладка уже открыта плагином — просто переключается на неё. Если сессия сейчас активна в другом терминале — предложит fork, чтобы не смешать два процесса в одном транскрипте |
| **Новая сессия** | `Ctrl+Alt+Shift+C` или «+»: сразу `claude --session-id <uuid>`, без вопроса об имени (назвать — «Переименовать…») — вкладка сразу связана с сессией |
| **Live-статус** | Асинхронный хук пишет события; в списке видно: думает / выполняет `Bash: npm test` / ждёт разрешения / ждёт ввода / ошибка. Иконка тул-окна «оживает», пока что-то работает. Уведомления «Claude закончил» и «ждёт разрешения» |
| **Просмотр транскрипта** | `Space` или «Открыть транскрипт» в меню: JCEF-вкладка с Markdown, свёрнутыми вызовами инструментов, diff правок Edit/MultiEdit, todo-списками, поиском (`Ctrl+F`), фильтрами. Клик по пути файла открывает его в редакторе. Обновляется вживую |
| **Экспорт** | В Markdown (с diff и выводом команд) |

## Сборка

Нужны: JDK 21 (Gradle подтянет сам через foojay), **Go 1.22+** (собирается хук), доступ к Maven Central и репозиториям JetBrains.

```bash
./gradlew buildPlugin          # → build/distributions/cc-sessions-<версия>.zip
./gradlew runIde               # песочница IDE с плагином — удобно для отладки
./gradlew test                 # тесты ядра (парсер, индекс, live-статус, хуки, рендер)
```

Установка: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → zip.

Задача `buildHook` собирает `hook/` под linux/darwin (amd64, arm64) и windows/amd64 и кладёт бинарники в ресурсы плагина.

## Публикация в JetBrains Marketplace

1. Повысить `version` в `gradle.properties` (Marketplace не принимает две сборки с одной версией) и дописать
   `<change-notes>` в `src/main/resources/META-INF/plugin.xml`.
2. Проверить совместимость с установленными IDE:
   ```bash
   ./gradlew test verifyPlugin -PverifyIdes=/путь/к/phpstorm,/путь/к/goland
   ```
3. **Первая версия** — только вручную: [plugins.jetbrains.com](https://plugins.jetbrains.com) → *Upload plugin* →
   `build/distributions/cc-sessions-<версия>.zip`; там же выбрать лицензию (MIT) и указать исходники:
   https://github.com/xstreamka/cc-sessions.
4. Следующие версии — из Gradle, токен из профиля Marketplace (*My Tokens*):
   ```bash
   PUBLISH_TOKEN=… ./gradlew publishPlugin
   ```
   Подпись автора необязательна (Marketplace подписывает сам). Если нужна своя — задайте `CERTIFICATE_CHAIN`,
   `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, и `signPlugin` подпишет сборку перед публикацией.

Каждая версия проходит ручную модерацию JetBrains перед появлением в Marketplace.

## Первый запуск

1. Откройте тул-окно **CC Sessions** (справа).
2. Нажмите **«Включить live-статус…»** на тулбаре. Плагин:
   - скопирует `cc-sessions-hook` в `~/.claude/cc-sessions/bin/`;
   - сделает бэкап `~/.claude/settings.json` (`settings.json.bak-cc-sessions-<дата>`) рядом с `settings.json` или в каталог, заданный в настройках плагина («Каталог бэкапов settings.json»), с правами только для владельца;
   - добавит **отдельные** группы хуков с `"async": true` на события сессии. Ваши хуки (rm-guard, GSD и т. д.) не трогаются;
   - удаление — той же кнопкой, убираются только записи с `cc-sessions-hook`.
3. Статус появится у новых и перезапущенных сессий.

## Настройки (*Settings → Tools → CC Sessions*)

- **Команда Claude.** Если IDE не видит `claude` в PATH (npm-prefix в `~/.npm-global`), укажите полный путь:
  `/home/<you>/.npm-global/bin/claude`. Вкладки запускают ваш обычный shell, поэтому чаще всего хватает `claude`.
- **Аргументы resume/fork.** Claude Code **не восстанавливает `bypassPermissions` при resume**. Если он нужен —
  `--permission-mode bypassPermissions` здесь или `permissions.defaultMode` в `settings.json`.
- **Язык плагина.** «Как в IDE» (по умолчанию), English или Русский. Языку IDE следуют только название группы в меню Tools и названия действий в Keymap.
- Уведомления: о завершении хода, о запросе разрешения, «только когда IDE не в фокусе».

## Как это устроено

```
claude (терминал IDE) ──hook, async──▶ cc-sessions-hook (Go) ──▶ ~/.claude/cc-sessions/events/<session>.jsonl
        │                                                                     │
        └──▶ ~/.claude/projects/<проект>/<session>.jsonl                      │
                         │                                                     │
                  SessionIndex (инкрементально, append-only)          LiveTracker (раз в секунду)
                         └──────────────▶ панель / просмотрщик ◀──────────────┘
```

- `src/main/kotlin/.../core` — чистый Kotlin без зависимостей от IDE: парсер транскриптов, индекс, live-редьюсер,
  правка `settings.json`, Markdown/HTML-рендер, diff. Покрыт тестами.
- `src/main/kotlin/.../ide` — тул-окно, сервисы, терминал (Reworked Terminal API), JCEF-просмотрщик, настройки.
- `hook/` — Go-хук: читает stdin, ужимает событие (без содержимого файлов из `Write`), дописывает строку. Всегда `exit 0`.
- Файлы событий старше 7 дней удаляются, разросшиеся (>2 МБ) — обрезаются.

## Известные ограничения

- **Формат транскриптов внутренний** и меняется между версиями Claude Code. Парсер терпимый (неизвестное пропускает),
  но после крупных обновлений CLI что-то может отображаться хуже.
- **Транскрипты живут 30 дней** (`cleanupPeriodDays`). Избранное в плагине их не спасает — для долгоживущих сессий
  увеличьте `cleanupPeriodDays` в `~/.claude/settings.json`.
- **Terminal API помечен как experimental.** Если вкладку открыть не удалось, команда копируется в буфер, и
  появляется уведомление. Если при сборке будет `Unresolved reference: TerminalToolWindowTabsManager`, добавьте в
  `build.gradle.kts` рядом с `bundledPlugin(...)`: `bundledModule("intellij.terminal.frontend")`.
- Переименование задаёт имя самой сессии (`claude --resume <имя>`, Air): во вкладке плагина — командой `/rename`,
  у незапущенной сессии — записью в транскрипт. Сессию, работающую в чужом терминале, переименуйте там через `/rename`.
- Сессии, запущенные вне плагина, видны и с live-статусом, но «переключиться на вкладку» можно только для открытых плагином.

Неофициальный плагин, с Anthropic не связан. Лицензия — MIT (см. `LICENSE`). Исходники: https://github.com/xstreamka/cc-sessions
