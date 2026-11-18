package dev.xstreamka.ccsessions.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant

class CoreTest {
    private val sid = "11111111-2222-3333-4444-555555555555"

    private fun fixture(): Path {
        val url = javaClass.getResource("/fixtures/$sid.jsonl") ?: error("fixture not found")
        return Paths.get(url.toURI())
    }

    private fun parse() = TranscriptParser.parse(sid, Files.readAllLines(fixture()).asSequence())

    @Test
    fun summaryFromFixture() {
        val s = parse().summary
        assertEquals("csv-export", s.title)
        assertEquals("Экспорт заказов в CSV", s.aiTitle)
        assertEquals(2, s.prompts)
        assertEquals("/gsd:plan фича экспорта", s.firstPrompt)
        assertEquals(3, s.assistantMessages)
        assertEquals(150L, s.usage.output)
        assertEquals(9103L, s.lastContext)
        assertEquals(4, s.toolCalls)
        assertEquals("claude-opus-5-5", s.model)
        assertEquals("feature/x", s.gitBranch)
        assertEquals("/home/u/proj", s.cwd)
        assertEquals(Instant.parse("2026-11-17T09:00:00Z"), s.createdAt)
        assertEquals(Instant.parse("2026-11-18T08:01:05Z"), s.updatedAt)
    }

    @Test
    fun airArchivedTitle() {
        val acc = SessionAccumulator("s1")
        acc.accept(parseObject("""{"type":"ai-title","aiTitle":"Ревью d.volkov-88150","sessionId":"s1"}""")!!)
        acc.accept(parseObject("""{"type":"custom-title","customTitle":"[archived] Ревью d.volkov-88150","sessionId":"s1"}""")!!)
        acc.accept(parseObject("""{"type":"agent-name","agentName":"[archived] Ревью d.volkov-88150","sessionId":"s1"}""")!!)
        val archived = acc.build(0)
        assertTrue(archived.archivedInAir)
        assertEquals("Ревью d.volkov-88150", archived.title)
        // Air вернул сессию из архива — новый custom-title без префикса
        acc.accept(parseObject("""{"type":"custom-title","customTitle":"Ревью d.volkov-88150","sessionId":"s1"}""")!!)
        val restored = acc.build(0)
        assertFalse(restored.archivedInAir)
        assertEquals("Ревью d.volkov-88150", restored.title)
        // Только префикс без названия — берём AI-заголовок
        assertEquals("Ревью d.volkov-88150", SessionSummary("s2", customTitle = "[archived] ", aiTitle = "Ревью d.volkov-88150").title)
        assertFalse(SessionSummary("s3", customTitle = "Обычное название").archivedInAir)
    }

    @Test
    fun airArchiveWrite() {
        val f = Files.createTempDirectory("air").resolve("s1.jsonl")
        // транскрипт без завершающего перевода строки
        Files.writeString(f, """{"type":"ai-title","aiTitle":"Деплой ветки","sessionId":"s1"}""")
        AirArchive.write(f, "s1", "Деплой ветки", archived = true)
        val lines = Files.readAllLines(f)
        assertEquals(3, lines.size)
        assertEquals("""{"type":"custom-title","customTitle":"[archived] Деплой ветки","sessionId":"s1"}""", lines[1])
        assertEquals("""{"type":"agent-name","agentName":"[archived] Деплой ветки","sessionId":"s1"}""", lines[2])
        val archived = TranscriptParser.parse("s1", Files.readAllLines(f).asSequence()).summary
        assertTrue(archived.archivedInAir)
        assertEquals("Деплой ветки", archived.baseTitle)

        AirArchive.write(f, "s1", archived.baseTitle, archived = false)
        assertEquals(5, Files.readAllLines(f).size)
        val restored = TranscriptParser.parse("s1", Files.readAllLines(f).asSequence()).summary
        assertFalse(restored.archivedInAir)
        assertEquals("Деплой ветки", restored.title)
        assertEquals("первая строка промпта", SessionSummary("s2", firstPrompt = "первая строка\nпромпта").baseTitle)
        assertEquals("Своё", SessionSummary("s3", customTitle = "[archived] Своё", aiTitle = "AI").baseTitle)

        // переименование архивной сессии: имя новое, пометка архива сохраняется
        AirArchive.write(f, "s1", "Деплой ветки", archived = true)
        AirArchive.write(f, "s1", "Новое имя", archived = true)
        val renamed = TranscriptParser.parse("s1", Files.readAllLines(f).asSequence()).summary
        assertTrue(renamed.archivedInAir)
        assertEquals("Новое имя", renamed.title)
    }

    @Test
    fun sessionFilesDelete() {
        val root = Files.createTempDirectory("claude")
        val paths = ClaudePaths(root)
        val id = "11111111-2222-3333-4444-555555555555"
        val other = "99999999-2222-3333-4444-555555555555"
        val proj = paths.projectsDir.resolve("-home-u-proj")
        fun touch(p: Path) { Files.createDirectories(p.parent); Files.writeString(p, "x") }
        val transcript = proj.resolve("$id.jsonl")
        touch(transcript)
        touch(proj.resolve("$id/tool-results/r1.txt"))
        touch(root.resolve("session-env/$id/env"))
        touch(root.resolve("file-history/$id/a@v1"))
        touch(root.resolve("security/security_warnings_state_$id.json"))
        touch(root.resolve("security/security_warnings_state_$id.lock"))
        touch(paths.eventsDir.resolve("$id.jsonl"))
        // чужая сессия и общие файлы — не трогаем
        touch(proj.resolve("$other.jsonl"))
        touch(root.resolve("session-env/$other/env"))
        touch(root.resolve("history.jsonl"))

        val found = SessionFiles.of(paths, id, transcript)
        assertEquals(7, found.size)
        assertTrue(SessionFiles.sizeOf(found) > 0)
        SessionFiles.delete(paths, found)
        assertTrue(SessionFiles.of(paths, id, transcript).isEmpty())
        assertFalse(Files.exists(proj.resolve(id)))
        assertTrue(Files.exists(proj.resolve("$other.jsonl")))
        assertTrue(Files.exists(root.resolve("session-env/$other/env")))
        assertTrue(Files.exists(root.resolve("history.jsonl")))

        // защита: не-UUID id и путь вне ~/.claude не удаляются
        assertTrue(SessionFiles.of(paths, "../x", null).isEmpty())
        val outside = Files.createTempFile("outside", ".jsonl")
        try {
            SessionFiles.delete(paths, listOf(outside))
            fail("ожидалось исключение")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(Files.exists(outside))
    }

    @Test
    fun markdownLinksNotNested() {
        val html = Markdown.inline("[https://a.com](https://b.com) и https://c.com/x?a=\"2\"&b=1")
        assertEquals(2, Regex("<a ").findAll(html).count())
        assertTrue(html.contains("<a href=\"https://b.com\">https://a.com</a>"))
        // кавычки из адреса остаются сущностями внутри атрибута и не закрывают его
        assertTrue(html.contains("<a href=\"https://c.com/x?a=&quot;2&quot;&amp;b=1\">"))
        assertFalse(html.contains("javascript:"))
        assertFalse(Markdown.inline("[x](javascript:alert(1))").contains("<a "))
    }

    @Test
    fun settingsWriteKeepsPermissions() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        val root = Files.createTempDirectory("ccs-perm")
        val file = root.resolve("settings.json")
        Files.writeString(file, "{\"env\":{\"TOKEN\":\"x\"}}")
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        val backup = HooksConfig.writeSettings(file, JsonParser.parseString("{\"a\":1}").asJsonObject)!!
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(backup)))
        assertEquals(1, HooksConfig.readSettings(file).get("a").asInt)
    }

    @Test
    fun safeTerminalText() {
        assertEquals("x rm -rf", Shell.safeTerminalText("x; rm -rf ~"))
        assertEquals("a echo pwned id", Shell.safeTerminalText("a\n$(echo pwned) `id` | > <"))
        assertEquals("Ревью d.volkov-88150", Shell.safeTerminalText("Ревью d.volkov-88150"))
    }

    @Test
    fun itemsFromFixture() {
        val items = parse().items
        val kinds = items.map {
            when (it) {
                is TItem.UserPrompt -> "prompt"
                is TItem.SlashCommand -> "cmd"
                is TItem.AssistantText -> "text"
                is TItem.Thinking -> "think"
                is TItem.ToolCall -> "tool:" + it.name
                is TItem.Note -> "note:" + it.kind
            }
        }
        assertEquals(
            listOf(
                "cmd", "prompt", "think", "text", "tool:Bash", "tool:Edit",
                "note:INTERRUPT", "note:COMPACT", "note:COMPACT", "prompt",
                "tool:TodoWrite", "tool:mcp__memory__memory_read",
            ),
            kinds,
        )
        val prompt = items[1] as TItem.UserPrompt
        assertEquals(1, prompt.images)
        val bash = items[4] as TItem.ToolCall
        assertEquals("No syntax errors detected", bash.result?.text)
        assertFalse(bash.result!!.isError)
        assertNotNull(bash.result!!.structured)
        assertEquals("The file has been updated", (items[5] as TItem.ToolCall).result?.text)
        assertNull((items[11] as TItem.ToolCall).result)
    }

    @Test
    fun indexIsIncremental() {
        val dir = Files.createTempDirectory("ccs")
        val f = dir.resolve("$sid.jsonl")
        Files.copy(fixture(), f)
        Files.writeString(dir.resolve("agent-abc.jsonl"), "{}\n")
        val index = SessionIndex()
        val first = index.scan(listOf(dir))
        assertEquals(1, first.size)
        assertEquals(2, first[0].prompts)

        // дописываем окончание недописанной строки + новый промпт
        val tail = "дописанная строка\"}}\n" +
            """{"type":"user","uuid":"u9","sessionId":"$sid","timestamp":"2026-11-19T10:00:00.000Z","message":{"role":"user","content":"Ещё один"}}""" + "\n"
        val partial = Files.readString(f).substringAfterLast('\n')
        // восстановим строку целиком: заменяем хвост на корректный JSON
        Files.writeString(f, Files.readString(f).removeSuffix(partial))
        Files.writeString(f, """{"type":"user","uuid":"u8","sessionId":"$sid","timestamp":"2026-11-19T09:00:00.000Z","message":{"role":"user","content":"""", StandardOpenOption.APPEND)
        assertEquals(2, index.scan(listOf(dir))[0].prompts) // незавершённая строка не считается
        Files.writeString(f, tail, StandardOpenOption.APPEND)
        val s = index.scan(listOf(dir))[0]
        assertEquals(4, s.prompts)
        assertEquals("Ещё один", s.lastPrompt)
        assertEquals(Instant.parse("2026-11-19T10:00:00Z"), s.updatedAt)
    }

    @Test
    fun liveReducer() {
        var st: LiveState? = null
        fun ev(e: String, ts: Long, f: (LiveEvent) -> LiveEvent = { it }) {
            st = LiveReducer.apply(st, f(LiveEvent(ts, e, "s")))
        }
        ev("SessionStart", 1)
        assertEquals(LiveStatus.IDLE, st!!.status)
        ev("UserPromptSubmit", 2) { it.copy(prompt = "сделай") }
        assertEquals(LiveStatus.WORKING, st!!.status)
        ev("PreToolUse", 3) { it.copy(toolName = "Bash", toolSummary = "npm test") }
        assertEquals(LiveStatus.TOOL, st!!.status)
        assertEquals("Bash: npm test", st!!.describe(3))
        ev("PermissionRequest", 4) { it.copy(toolName = "Bash") }
        assertEquals(LiveStatus.WAITING_PERMISSION, st!!.status)
        ev("PostToolUse", 5)
        assertEquals(LiveStatus.WORKING, st!!.status)
        ev("SubagentStart", 6)
        assertEquals(1, st!!.activeSubagents)
        ev("Stop", 7) { it.copy(agentId = "a1") } // стоп субагента не завершает ход
        assertEquals(LiveStatus.WORKING, st!!.status)
        ev("Stop", 8) { it.copy(lastMessage = "Готово") }
        assertEquals(LiveStatus.IDLE, st!!.status)
        assertEquals("Готово", st!!.lastMessage)
        assertEquals(0, st!!.activeSubagents)
        ev("PostToolUse", 9) // запоздавшее событие не будит сессию
        assertEquals(LiveStatus.IDLE, st!!.status)
        ev("SessionEnd", 10)
        assertEquals(LiveStatus.ENDED, st!!.status)
        val working = LiveState("x", LiveStatus.TOOL, 0, 0)
        assertEquals(LiveStatus.STALE, working.effectiveStatus(now = 30 * 60_000L))
    }

    @Test
    fun liveTrackerReadsFiles() {
        val dir = Files.createTempDirectory("ev")
        val f = dir.resolve("s1.jsonl")
        Files.writeString(f, """{"v":1,"ts":100,"event":"UserPromptSubmit","session_id":"s1","prompt":"hi"}""" + "\n")
        val tr = LiveTracker(dir)
        val t1 = tr.poll()
        assertEquals(1, t1.size)
        assertEquals(LiveStatus.WORKING, tr.snapshot()["s1"]!!.status)
        assertTrue(tr.poll().isEmpty())
        // события пришли не по порядку (async-хуки)
        Files.writeString(
            f,
            """{"v":1,"ts":300,"event":"Stop","session_id":"s1"}""" + "\n" +
                """{"v":1,"ts":200,"event":"PreToolUse","session_id":"s1","tool_name":"Read"}""" + "\n",
            StandardOpenOption.APPEND,
        )
        tr.poll()
        assertEquals(LiveStatus.IDLE, tr.snapshot()["s1"]!!.status)
    }

    @Test
    fun hooksInstallAndUninstall() {
        val settings = JsonParser.parseString(
            """{"permissions":{"defaultMode":"bypassPermissions"},"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"node ~/.claude/hooks/rm-guard.js"}]}]}}""",
        ).asJsonObject
        val cmd = "/home/u/.claude/cc-sessions/bin/cc-sessions-hook"
        assertTrue(HooksConfig.install(settings, cmd))
        assertTrue(HooksConfig.isInstalled(settings, cmd))
        assertFalse(HooksConfig.install(settings, cmd)) // идемпотентно
        assertEquals(2, settings.obj("hooks")!!.arr("PreToolUse")!!.size())
        assertTrue(HooksConfig.uninstall(settings))
        val pre = settings.obj("hooks")!!.arr("PreToolUse")!!
        assertEquals(1, pre.size())
        assertEquals("node ~/.claude/hooks/rm-guard.js", pre[0].asJsonObject.arr("hooks")!![0].asJsonObject.str("command"))
        assertNull(settings.obj("hooks")!!.get("Stop"))
        assertEquals("bypassPermissions", settings.obj("permissions")!!.str("defaultMode"))
        assertEquals("\"/a b/cc-sessions-hook\"", HooksConfig.commandFor(Paths.get("/a b/cc-sessions-hook")))
    }

    @Test
    fun settingsBackupGoesToBackupDir() {
        val root = Files.createTempDirectory("ccs-backup")
        val file = root.resolve("claude/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{\"a\":1}")
        val dir = root.resolve("backups/claude-settings-cc-sessions")
        val backup = HooksConfig.writeSettings(file, JsonParser.parseString("{\"a\":2}").asJsonObject, dir)!!
        assertEquals(dir, backup.parent)
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(backup)))
        }
        assertTrue(backup.fileName.toString().startsWith("settings.json.bak-cc-sessions-"))
        assertEquals("{\"a\":1}", Files.readString(backup))
        assertEquals(2, HooksConfig.readSettings(file).get("a").asInt)
        assertEquals(listOf("settings.json"), Files.list(file.parent).map { it.fileName.toString() }.toList())
        // Без каталога — по-старому, рядом с settings.json
        val sibling = HooksConfig.writeSettings(file, JsonParser.parseString("{\"a\":3}").asJsonObject)!!
        assertEquals(file.parent, sibling.parent)
    }

    @Test
    fun shellQuoting() {
        assertEquals("claude --resume abc-1", Shell.claude("claude", "", listOf("--resume", "abc-1")))
        assertEquals("claude -n 'моя сессия' --session-id x", Shell.claude("claude", "", listOf("-n", "моя сессия", "--session-id", "x")))
        assertEquals("'it'\\''s'", Shell.quote("it's"))
        assertEquals(listOf("npx", "@anthropic-ai/claude-code"), Shell.split("npx @anthropic-ai/claude-code"))
        assertEquals(listOf("--permission-mode", "bypassPermissions", "--append-system-prompt", "a b"), Shell.split("--permission-mode bypassPermissions --append-system-prompt \"a b\""))
        assertEquals(listOf(""), Shell.split("''"))
    }

    @Test
    fun markdownIsSafe() {
        val html = Markdown.toHtml("# Заголовок\n\nТекст <script>alert(1)</script> и `код <b>` и **жирный**\n\n- a\n- b\n  - c\n\n```php\n<?php echo 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("<code>код &lt;b&gt;</code>"))
        assertTrue(html.contains("<b>жирный</b>"))
        assertTrue(html.contains("<h2>Заголовок</h2>"))
        assertTrue(html.contains("&lt;?php echo 1;"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<li>c</li>"))
    }

    @Test
    fun toolInputsAreEscaped() {
        val line = """{"sessionId": "$sid", "cwd": "/home/u/proj", "type": "assistant", "uuid": "x1", "timestamp": "2026-11-18T08:01:05.000Z", "message": {"id": "m1", "type": "message", "role": "assistant", "model": "m", "content": [""" +
            """{"type": "tool_use", "id": "t1", "name": "Grep", "input": {"pattern": "p", "glob": "<img src=x onerror=alert(1)>"}},""" +
            """{"type": "tool_use", "id": "t2", "name": "TodoWrite", "input": {"todos": [{"content": "c", "status": "x\"><script>alert(2)</script>"}]}},""" +
            """{"type": "tool_use", "id": "t3", "name": "WebFetch", "input": {"url": "javascript:alert(3)"}},""" +
            """{"type": "tool_use", "id": "t4", "name": "WebFetch", "input": {"url": "https://example.com/?a=1&b=2"}}]}}"""
        val html = TranscriptHtml().document(TranscriptParser.parse(sid, sequenceOf(line)), TranscriptHtml.Options(dark = true))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertFalse(html.contains("<script>alert(2)"))
        assertTrue(html.contains("<li class=\"pending\">"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("javascript:alert(3)"))
        assertTrue(html.contains("<a href=\"https://example.com/?a=1&amp;b=2\">"))
    }

    @Test
    fun diff() {
        val d = LineDiff.diff("a\nb\nc", "a\nB\nc\nd")
        assertEquals(listOf("KEEP a", "DEL b", "ADD B", "KEEP c", "ADD d"), d.map { "${it.op} ${it.text}" })
        assertEquals(2 to 1, LineDiff.stats(d))
    }

    @Test
    fun htmlDocument() {
        withLocale(java.util.Locale.of("ru")) { htmlDocumentRu() }
    }

    private fun htmlDocumentRu() {
        val t = parse()
        val html = TranscriptHtml().document(t, TranscriptHtml.Options(dark = true, alias = "Мой алиас", live = "думает"))
        assertTrue(html.contains("<html lang=\"ru\">"))
        assertTrue(html.contains("<h1>Мой алиас</h1>"))
        assertTrue(html.contains("data-file=\"/home/u/proj/src/Order.php\""))
        // ссылка только на имени файла: клик по пути к папке и остальной строке раскрывает блок
        assertTrue(html.contains("<b data-file=\"/home/u/proj/src/Order.php\">Order.php</b><span class=\"dir\">/home/u/proj/src/</span>"))
        assertTrue(html.contains("data-file=\"src/Order.php\" data-line=\"42\""))
        assertTrue(html.contains("class=\"add\">+ B"))
        assertTrue(html.contains("задачи 1/2"))
        assertTrue(html.contains("выполняется"))
        assertFalse(html.contains("<?php"))
        val md = MarkdownExport.export(t)
        assertTrue(md.startsWith("# csv-export"))
        assertTrue(md.contains("```diff"))
        assertTrue(md.contains("- Промптов: 2, вызовов инструментов: 4"))
    }

    @Test
    fun htmlDocumentEnglish() {
        withLocale(java.util.Locale.ROOT) { htmlDocumentEn() }
    }

    private fun htmlDocumentEn() {
        val t = parse()
        val html = TranscriptHtml(zone = utc).document(t, TranscriptHtml.Options(dark = true, live = "thinking"))
        assertTrue(html.contains("<html lang=\"en\">"))
        listOf(
            "<i>now</i>", "<i>branch</i>", "<i>model</i>", "<i>prompts</i>", "<i>tools</i>",
            "<i>started</i>17 Nov 2026, 09:00", "<div class=\"day\">17 November 2026</div>",
            "placeholder=\"Search transcript…\"", "Nothing found", "tasks 1/2", "running", "<div class=\"who\">You ",
            "Interrupted by user", "Context compacted (/compact)",
        ).forEach { assertTrue("no «$it» in English transcript", html.contains(it)) }

        val md = MarkdownExport.export(t, o = MarkdownExport.Options(zone = utc))
        listOf("- Session: `", "- Branch: `feature/x`", "- Prompts: 2, tool calls: 4", "## 🧑 You").forEach {
            assertTrue("no «$it» in English Markdown", md.contains(it))
        }
    }

    /** Вызовы инструментов и служебные записи, на которых видны все подписи транскрипта; [bigOutput] — вывод второго Bash. */
    private fun toolZoo(bigOutput: String): List<String> {
        val extra = """{"sessionId": "$sid", "type": "assistant", "uuid": "z1", "timestamp": "2026-11-18T09:00:00.000Z", "message": {"id": "mz", "role": "assistant", "model": "m", "content": [""" +
            """{"type": "tool_use", "id": "r1", "name": "Read", "input": {"file_path": "/a/b.kt", "offset": 1200, "limit": 2000}},""" +
            """{"type": "tool_use", "id": "r2", "name": "Write", "input": {"file_path": "/a/c.kt", "content": "a"}},""" +
            """{"type": "tool_use", "id": "r3", "name": "MultiEdit", "input": {"file_path": "/a/d.kt", "edits": [{"old_string": "1\n2\n3\n4\n5\n6\n7\n8\n9\n10", "new_string": "1\n2\n3\n4\n5\n6\n7\n8\n9\nX"}]}},""" +
            """{"type": "tool_use", "id": "r4", "name": "Grep", "input": {"pattern": "p", "path": "/src"}},""" +
            """{"type": "tool_use", "id": "r5", "name": "Task", "input": {"subagent_type": "x", "description": "d", "prompt": "p"}},""" +
            """{"type": "tool_use", "id": "r6", "name": "ExitPlanMode", "input": {"plan": "p"}},""" +
            """{"type": "tool_use", "id": "r7", "name": "AskUserQuestion", "input": {"q": 1}},""" +
            """{"type": "tool_use", "id": "r8", "name": "Bash", "input": {"command": "ls"}},""" +
            """{"type": "tool_use", "id": "r9", "name": "Bash", "input": {"command": "big"}},""" +
            """{"type": "tool_use", "id": "r10", "name": "TodoWrite", "input": {}},""" +
            """{"type": "thinking", "thinking": "hmm"}]}}"""
        val results = """{"sessionId": "$sid", "type": "user", "uuid": "z2", "timestamp": "2026-11-18T09:00:01.000Z", "message": {"role": "user", "content": [""" +
            """{"type": "tool_result", "tool_use_id": "r5", "content": "done"},""" +
            """{"type": "tool_result", "tool_use_id": "r8", "content": ""},""" +
            """{"type": "tool_result", "tool_use_id": "r9", "content": ${com.google.gson.Gson().toJson(bigOutput)}},""" +
            """{"type": "tool_result", "tool_use_id": "r2", "is_error": true, "content": [{"type": "image"}]}]}}"""
        fun user(uuid: String, sec: Int, content: String) =
            """{"sessionId": "$sid", "type": "user", "uuid": "$uuid", "timestamp": "2026-11-18T09:00:0$sec.000Z", "message": {"role": "user", "content": $content}}"""
        return listOf(
            extra, results,
            user("z3", 2, """[{"type": "text", "text": "see"}, {"type": "image", "source": {}}]"""),
            user("z4", 3, """"<bash-stdout>out</bash-stdout>""""),
            user("z5", 4, """"<local-command-stdout>out</local-command-stdout>""""),
            """{"sessionId": "$sid", "type": "assistant", "uuid": "z6", "isApiErrorMessage": true, "timestamp": "2026-11-18T09:00:05.000Z", "message": {"role": "assistant", "content": [{"type": "text", "text": "boom"}]}}""",
        )
    }

    @Test
    fun englishLabelsHaveNoCyrillic() {
        withLocale(java.util.Locale.ROOT) {
            // Кириллицу из сообщений фикстуры заменяем латиницей: тогда любая кириллица в выводе — забытая подпись кода/бандла
            val lines = Files.readAllLines(fixture()).map { it.replace(Regex("[А-Яа-яЁё]"), "x") } + toolZoo("x\n".repeat(80))
            val t = TranscriptParser.parse(sid, lines.asSequence())
            val h = TranscriptHtml(zone = utc, maxOutputChars = 50)
            val forked = t.summary.copy(forkedFrom = "abcdef1234567")
            val out = listOf(
                h.document(t, TranscriptHtml.Options(dark = true, live = LiveStatus.WORKING.label)),
                h.header(forked, TranscriptHtml.Options(dark = true)),
                h.items(Transcript(emptyList(), t.summary)),
                MarkdownExport.export(t, o = MarkdownExport.Options(includeThinking = true, maxOutputLines = 5, zone = utc)),
                LiveState("s", LiveStatus.WAITING_PERMISSION, 0, 0, tool = "Task", activeSubagents = 2).describe(0),
                LiveState("s", LiveStatus.ERROR, 0, 0, error = "e").describe(0),
                SessionSummary("1234567890").title,
            ) + DayGroup.values().map { it.title } + LiveStatus.values().map { it.label }
            val all = out.joinToString("\n")
            val cyr = Regex(".{0,40}[А-Яа-яЁё]+.{0,20}").findAll(all).map { it.value }.toList()
            assertTrue("Cyrillic in English output: $cyr", cyr.isEmpty())
            listOf(
                "from line 1200, 2,000 lines", "1 line", "edit 1", "… 6 unchanged", "in /src", ">prompt<", ">result<", ">plan<",
                "question for the user", "(empty output)", "(truncated, 159 characters total)", "images: 1", "shell output",
                "command output", "API error", " · forked from ", "No messages in the transcript yet", "… 75 more lines",
                "<summary>thinking</summary>", "[image]", "Summary after context compaction", ">tasks<",
            ).forEach { assertTrue("no «$it» in English output", all.contains(it)) }
            assertEquals("1 line", I18n.msg("transcript.lines", 1))
            assertEquals("0 lines", I18n.msg("transcript.lines", 0L))
            assertEquals("… 1 more line", I18n.msg("export.moreLines", 1))
            assertEquals("… 2 more lines", I18n.msg("export.moreLines", 2))
        }
    }

    @Test
    fun russianNumbersAreNotGrouped() {
        withLocale(java.util.Locale.of("ru")) {
            val lines = Files.readAllLines(fixture()) + toolZoo("y".repeat(40_001))
            val t = TranscriptParser.parse(sid, lines.asSequence())
            val html = TranscriptHtml(zone = utc).document(t, TranscriptHtml.Options(dark = false))
            assertTrue(html.contains("<div class=\"day\">17 ноября 2026</div>"))
            assertTrue(html.contains("<i>начата</i>17 нояб. 2026, 09:00"))
            assertTrue(html.contains("… (обрезано, всего 40001 символов)"))
            assertTrue(html.contains("с 1200, 2000 строк"))
            assertEquals("2000 строк", I18n.msg("transcript.lines", 2000L))
            assertEquals("… ещё 1500 строк", I18n.msg("export.moreLines", 1500))
            assertEquals("… 12345 без изменений", I18n.msg("diff.unchanged", 12345))
            assertEquals("задачи 1000/2000", I18n.msg("transcript.todos.progress", 1000, 2000))
            assertEquals("Промптов: 1000, вызовов инструментов: 25000", I18n.msg("export.counts", 1000, 25000))
            assertEquals("субагентов: 1000", I18n.msg("live.subagents", 1000))
            assertEquals("изображений: 1000", I18n.msg("transcript.prompt.images", 1000))
            assertEquals("правка 1000", I18n.msg("transcript.multiEdit.edit", 1000))
        }
    }

    @Test
    fun coreLabelsFollowLocale() {
        val perm = LiveState("s", LiveStatus.WAITING_PERMISSION, 0, 0, tool = "Bash", toolSummary = "rm -rf x", activeSubagents = 2)
        withLocale(java.util.Locale.ROOT) {
            assertEquals("Today", DayGroup.TODAY.title)
            assertEquals("Favorites", DayGroup.FAVORITES.title)
            assertEquals("thinking", LiveStatus.WORKING.label)
            assertEquals("waiting for permission: Bash — rm -rf x · subagents: 2", perm.describe(0))
            assertEquals("(empty session 12345678)", SessionSummary("1234567890").title)
            assertEquals("… 8 unchanged", LineDiff.collapse(LineDiff.diff((1..12).joinToString("\n"), (1..11).joinToString("\n") + "\nX"))[0].text)
        }
        withLocale(java.util.Locale.of("ru")) {
            assertEquals("Сегодня", DayGroup.TODAY.title)
            assertEquals("Избранное", DayGroup.FAVORITES.title)
            assertEquals("думает", LiveStatus.WORKING.label)
            assertEquals("ждёт разрешения: Bash — rm -rf x · субагентов: 2", perm.describe(0))
            assertEquals("(пустая сессия 12345678)", SessionSummary("1234567890").title)
            assertEquals("… 8 без изменений", LineDiff.collapse(LineDiff.diff((1..12).joinToString("\n"), (1..11).joinToString("\n") + "\nX"))[0].text)
        }
    }

    @Test
    fun projectDirEncoding() {
        assertEquals("-home-user-name-proj", ClaudePaths.encodeProjectDir("/home/user.name/proj"))
        assertEquals("-home-u-my-project", ClaudePaths.encodeProjectDir("/home/u/my_project/"))
    }

    @Test
    fun metaStore() {
        val f = Files.createTempDirectory("meta").resolve("cc-sessions/meta.json")
        val store = MetaStore(f)
        store.update("a") { it.copy(favorite = true, alias = "Фича") }
        store.update("b") { it.copy(tags = listOf("bug")) }
        assertEquals(SessionMeta(favorite = true, alias = "Фича"), MetaStore(f).get("a"))
        store.update("b") { SessionMeta() }
        assertFalse(MetaStore(f).all().containsKey("b"))

        store.update("c") { it.copy(archived = true) }
        assertTrue(MetaStore(f).get("c").archived)
        assertFalse(SessionMeta(archived = true).isEmpty)

        val old = Files.createTempDirectory("meta-old").resolve("meta.json")
        Files.writeString(old, "{\"version\":1,\"sessions\":{\"x\":{\"favorite\":true}}}")
        assertFalse(MetaStore(old).get("x").archived)
    }

    private val utc = java.time.ZoneOffset.UTC

    private fun g(iso: String?, fav: Boolean = false, today: String = "2026-11-18") =
        SessionGroups.groupOf(iso?.let { Instant.parse(it) }, fav, java.time.LocalDate.parse(today), utc)

    @Test
    fun dayGroups() {
        assertEquals(DayGroup.TODAY, g("2026-11-18T10:00:00Z"))
        assertEquals(DayGroup.YESTERDAY, g("2026-11-17T23:59:00Z"))
        assertEquals(DayGroup.THIS_WEEK, g("2026-11-16T00:00:00Z"))
        assertEquals(DayGroup.EARLIER, g("2026-11-15T12:00:00Z"))
        assertEquals(DayGroup.EARLIER, g(null))
        assertEquals(DayGroup.FAVORITES, g("2020-01-01T00:00:00Z", fav = true))
        assertEquals(DayGroup.FAVORITES, g(null, fav = true))
        assertEquals(DayGroup.TODAY, g("2026-11-19T00:00:00Z"))
        assertEquals(DayGroup.YESTERDAY, g("2026-11-15T12:00:00Z", today = "2026-11-16"))
    }

    @Test
    fun groupOrderAndEmpty() {
        val items = listOf(
            "e1" to DayGroup.EARLIER, "t1" to DayGroup.TODAY, "e2" to DayGroup.EARLIER,
            "f1" to DayGroup.FAVORITES, "t2" to DayGroup.TODAY,
        )
        val r = SessionGroups.group(items) { it.second }
        assertEquals(listOf(DayGroup.FAVORITES, DayGroup.TODAY, DayGroup.EARLIER), r.map { it.first })
        assertEquals(listOf("t1", "t2"), r[1].second.map { it.first })
        assertEquals(listOf("e1", "e2"), r[2].second.map { it.first })
        assertTrue(SessionGroups.group(emptyList<String>()) { DayGroup.TODAY }.isEmpty())
    }

    // ---------- I18n ----------

    private fun <T> withLocale(locale: java.util.Locale, block: () -> T): T {
        val prev = I18n.localeProvider
        I18n.localeProvider = { locale }
        try {
            return block()
        } finally {
            I18n.localeProvider = prev
        }
    }

    @Test
    fun i18nRootIsEnglishEvenOnRussianJvm() {
        val prevDefault = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.of("ru"))
        try {
            withLocale(java.util.Locale.ROOT) {
                assertEquals(java.util.Locale.ROOT, I18n.locale())
                assertEquals("Plugin language:", I18n.msg("settings.language.label"))
                assertEquals("Same as IDE", I18n.msg("settings.language.auto"))
            }
        } finally {
            java.util.Locale.setDefault(prevDefault)
        }
    }

    @Test
    fun i18nRussian() {
        withLocale(java.util.Locale.of("ru")) {
            assertEquals("Язык плагина:", I18n.msg("settings.language.label"))
            assertEquals("Как в IDE", I18n.msg("settings.language.auto"))
            assertEquals("English", I18n.msg("settings.language.en"))
            assertEquals("Русский", I18n.msg("settings.language.ru"))
        }
        withLocale(java.util.Locale.ROOT) {
            assertEquals("English", I18n.msg("settings.language.en"))
            assertEquals("Русский", I18n.msg("settings.language.ru"))
        }
    }

    @Test
    fun i18nUnknownKeyReturnsKey() {
        withLocale(java.util.Locale.ROOT) { assertEquals("no.such.key", I18n.msg("no.such.key", 1)) }
        withLocale(java.util.Locale.of("ru")) { assertEquals("no.such.key", I18n.msg("no.such.key")) }
    }

    @Test
    fun i18nEnglishLocaleUsesBaseNotJvmDefault() {
        val prevDefault = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.of("ru"))
        try {
            // Без no-fallback контрола для «en» (файла _en нет) взялся бы _ru по локали JVM
            withLocale(java.util.Locale.ENGLISH) {
                assertEquals("Plugin language:", I18n.msg("settings.language.label"))
                assertEquals("Same as IDE", I18n.msg("settings.language.auto"))
            }
        } finally {
            java.util.Locale.setDefault(prevDefault)
        }
    }

    @Test
    fun i18nMessageFormat() {
        withLocale(java.util.Locale.ROOT) {
            assertEquals("3 of 5", I18n.format("{0} of {1}", 3, 5))
            assertEquals("doesn't fit: x", I18n.format("doesn''t fit: {0}", "x"))
            // без аргументов — как есть, MessageFormat не применяется
            assertEquals("it's", I18n.format("it's"))
            assertEquals("{0} it's", I18n.format("{0} it's"))
            // сломанный шаблон — вернуть как есть
            assertEquals("broken {0", I18n.format("broken {0", 1))
            assertEquals("Plugin language:", I18n.msg("settings.language.label", "unused"))
        }
    }

    @Test
    fun i18nBundlesHaveSameKeys() {
        fun load(name: String): java.util.Properties {
            val stream = javaClass.getResourceAsStream("/messages/$name") ?: error("$name not found")
            val p = java.util.Properties()
            stream.reader(Charsets.UTF_8).use { p.load(it) }
            return p
        }
        val baseProps = load("CcSessionsBundle.properties")
        val ruProps = load("CcSessionsBundle_ru.properties")
        val base = baseProps.stringPropertyNames()
        val ru = ruProps.stringPropertyNames()
        assertTrue("toolwindow.stripe.Claude_Sessions" in base)
        assertEquals("only in base: ${base - ru}; only in _ru: ${ru - base}", base, ru)

        val placeholder = Regex("""\{(\d+)""")
        fun indexes(v: String) = placeholder.findAll(v).map { it.groupValues[1].toInt() }.toSet()
        for (key in base) {
            val b = baseProps.getProperty(key)
            val r = ruProps.getProperty(key)
            assertEquals("placeholders of $key", indexes(b), indexes(r))
            listOf(b to java.util.Locale.ROOT, r to java.util.Locale.of("ru")).forEach { (v, loc) ->
                if (indexes(v).isNotEmpty()) {
                    try {
                        java.text.MessageFormat(v, loc)
                    } catch (e: IllegalArgumentException) {
                        fail("$key ($loc) is not a valid MessageFormat: $v (${e.message})")
                    }
                }
            }
        }
    }

    @Test
    fun relativeTimeRussianAsBefore() {
        val zone = java.time.ZoneOffset.UTC
        val now = Instant.parse("2026-11-18T12:00:00Z")
        fun rel(iso: String) = RelativeTime.relative(Instant.parse(iso), now, zone)
        withLocale(java.util.Locale.of("ru")) {
            assertEquals("только что", rel("2026-11-18T11:59:30Z"))
            assertEquals("5 мин назад", rel("2026-11-18T11:55:00Z"))
            assertEquals("сегодня 10:05", rel("2026-11-18T10:05:00Z"))
            assertEquals("вчера 23:10", rel("2026-11-17T23:10:00Z"))
            assertEquals(
                java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("ru"))
                    .format(Instant.parse("2026-11-03T09:00:00Z").atZone(zone)),
                rel("2026-11-03T09:00:00Z"),
            )
            assertEquals("45 с", RelativeTime.short(java.time.Duration.ofSeconds(45)))
            assertEquals("3 мин", RelativeTime.short(java.time.Duration.ofMinutes(3)))
            assertEquals("1500 ч", RelativeTime.short(java.time.Duration.ofHours(1500)))
            assertEquals("1500 пр.", I18n.msg("row.prompts", 1500))
        }
    }

    @Test
    fun relativeTimeEnglish() {
        val zone = java.time.ZoneOffset.UTC
        val now = Instant.parse("2026-11-18T12:00:00Z")
        fun rel(iso: String) = RelativeTime.relative(Instant.parse(iso), now, zone)
        withLocale(java.util.Locale.ROOT) {
            assertEquals("just now", rel("2026-11-18T11:59:30Z"))
            assertEquals("5 min ago", rel("2026-11-18T11:55:00Z"))
            assertEquals("today 10:05", rel("2026-11-18T10:05:00Z"))
            assertEquals("yesterday 23:10", rel("2026-11-17T23:10:00Z"))
            assertEquals("Nov 3", rel("2026-11-03T09:00:00Z"))
            assertEquals("45s", RelativeTime.short(java.time.Duration.ofSeconds(45)))
            assertEquals("3m", RelativeTime.short(java.time.Duration.ofMinutes(3)))
            assertEquals("2h", RelativeTime.short(java.time.Duration.ofHours(2)))
            assertEquals("1 prompt", I18n.msg("row.prompts", 1))
            assertEquals("2 prompts", I18n.msg("row.prompts", 2L))
            assertEquals("0 prompts", I18n.msg("row.prompts", 0))
        }
    }

    @Test
    fun ideTextsWithApostrophesAndArgs() {
        withLocale(java.util.Locale.ROOT) {
            assertEquals("Couldn't modify settings.json: boom", I18n.msg("hooks.error", "boom"))
            assertEquals("Couldn't delete the session", I18n.msg("notify.deleteFailed"))
            assertTrue(I18n.msg("settings.live.comment", "/x").contains("It doesn't block"))
        }
        withLocale(java.util.Locale.of("ru")) {
            assertEquals("Не удалось изменить settings.json: boom", I18n.msg("hooks.error", "boom"))
            assertEquals(
                "Удалить сессию «a» навсегда?\n\nБудут удалены (1 KB):\n  x\n\nОтменить удаление нельзя.",
                I18n.msg("dialog.delete.message", "a", "1 KB", "  x"),
            )
        }
    }

    @Test
    fun indexClearReparsesInNewLanguage() {
        val dir = Files.createTempDirectory("idx")
        val file = dir.resolve("$sid.jsonl")
        Files.writeString(
            file,
            """{"sessionId": "$sid", "type": "user", "uuid": "u1", "timestamp": "2026-11-18T09:00:00.000Z", """ +
                """"message": {"role": "user", "content": [{"type": "image", "source": {}}]}}""" + "\n",
        )
        val index = SessionIndex()
        withLocale(java.util.Locale.of("ru")) {
            assertEquals("[изображение]", index.scan(listOf(dir)).single().firstPrompt)
        }
        withLocale(java.util.Locale.ROOT) {
            // файл не менялся — сводка из кеша, на старом языке
            assertEquals("[изображение]", index.scan(listOf(dir)).single().firstPrompt)
            index.clear()
            assertEquals("[image]", index.scan(listOf(dir)).single().firstPrompt)
        }
    }
}
