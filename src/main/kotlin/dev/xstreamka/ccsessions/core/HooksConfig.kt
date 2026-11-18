package dev.xstreamka.ccsessions.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Установка/удаление хуков cc-sessions-hook в settings.json Claude Code.
 * Чужие хуки (GSD, rm-guard и т. п.) не трогаем: добавляем отдельные группы
 * и при удалении убираем только обработчики с нашим маркером.
 */
object HooksConfig {
    const val MARKER = "cc-sessions-hook"

    val EVENTS = listOf(
        "SessionStart", "SessionEnd",
        "UserPromptSubmit",
        "PreToolUse", "PostToolUse", "PostToolUseFailure",
        "PermissionRequest", "PermissionDenied",
        "Notification",
        "Stop", "StopFailure",
        "SubagentStart", "SubagentStop",
        "PreCompact", "PostCompact",
    )

    fun commandFor(binary: Path): String {
        val p = binary.toAbsolutePath().toString()
        return if (p.any { it.isWhitespace() || it == '\'' || it == '"' }) "\"" + p.replace("\"", "\\\"") + "\"" else p
    }

    fun isInstalled(settings: JsonObject, command: String? = null): Boolean {
        val hooks = settings.obj("hooks") ?: return false
        return EVENTS.all { ev ->
            hooks.arr(ev)?.any { g -> g.isJsonObject && g.asJsonObject.arr("hooks")?.any { h -> isOurs(h.asJsonObjectOrNull(), command) } == true } == true
        }
    }

    /** Добавляет наши обработчики (идемпотентно, обновляет путь). Возвращает true, если что-то изменилось. */
    fun install(settings: JsonObject, command: String): Boolean {
        val before = settings.deepCopy()
        uninstall(settings)
        val hooks = settings.obj("hooks") ?: JsonObject().also { settings.add("hooks", it) }
        for (ev in EVENTS) {
            val groups = hooks.arr(ev) ?: JsonArray().also { hooks.add(ev, it) }
            groups.add(JsonObject().apply {
                add("hooks", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "command")
                        addProperty("command", command)
                        addProperty("async", true)
                        addProperty("timeout", 10)
                    })
                })
            })
        }
        return before != settings
    }

    /** Убирает только наши обработчики; пустые группы/события удаляет. */
    fun uninstall(settings: JsonObject): Boolean {
        val hooks = settings.obj("hooks") ?: return false
        var changed = false
        for (ev in hooks.keySet().toList()) {
            val groups = hooks.arr(ev) ?: continue
            val it = groups.iterator()
            while (it.hasNext()) {
                val g = it.next().asJsonObjectOrNull() ?: continue
                val hs = g.arr("hooks") ?: continue
                val hit = hs.iterator()
                while (hit.hasNext()) {
                    if (isOurs(hit.next().asJsonObjectOrNull(), null)) {
                        hit.remove(); changed = true
                    }
                }
                if (hs.size() == 0) it.remove()
            }
            if (groups.size() == 0) hooks.remove(ev)
        }
        if (hooks.size() == 0) settings.remove("hooks")
        return changed
    }

    private fun isOurs(h: JsonObject?, command: String?): Boolean {
        h ?: return false
        val cmd = h.str("command") ?: return false
        return if (command != null) cmd == command else cmd.contains(MARKER)
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    // ---------- работа с файлом ----------

    private fun isPosix() = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    /** Новый пустой файл с правами rw------- (на POSIX); существующий заменяется. */
    private fun createPrivateFile(p: Path) {
        Files.deleteIfExists(p)
        if (isPosix()) {
            Files.createFile(p, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(p)
        }
    }

    /** Создаёт каталог бэкапов (новые уровни — только для владельца); существующий не трогаем. */
    private fun ensurePrivateDir(dir: Path): Path {
        if (Files.isDirectory(dir)) return dir
        dir.parent?.let { Files.createDirectories(it) }
        return if (isPosix()) {
            Files.createDirectory(dir, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
        } else Files.createDirectory(dir)
    }

    fun readSettings(file: Path): JsonObject {
        if (!Files.isRegularFile(file)) return JsonObject()
        val text = Files.readString(file)
        if (text.isBlank()) return JsonObject()
        val el = JsonParser.parseString(text)
        require(el.isJsonObject) { I18n.msg("error.settingsNotObject") }
        return el.asJsonObject
    }

    /**
     * Пишет settings.json, предварительно сохранив резервную копию. Возвращает путь бэкапа.
     * [backupDir] — куда класть бэкап; null — рядом с settings.json. Если каталог не создаётся,
     * бросаем исключение до записи settings.json, чтобы не править файл без бэкапа.
     */
    fun writeSettings(file: Path, settings: JsonObject, backupDir: Path? = null): Path? {
        Files.createDirectories(file.parent)
        var backup: Path? = null
        if (Files.isRegularFile(file)) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val name = "settings.json.bak-cc-sessions-$stamp"
            backup = if (backupDir != null) ensurePrivateDir(backupDir).resolve(name) else file.resolveSibling(name)
            // В settings.json бывают токены (env) — бэкап сразу создаём только для владельца, потом копируем содержимое
            createPrivateFile(backup)
            Files.newOutputStream(backup).use { out -> Files.copy(file, out) }
        }
        val tmp = file.resolveSibling("settings.json.cc-sessions.tmp")
        Files.deleteIfExists(tmp)
        // Права нового settings.json — как у прежнего (иначе закрытый 600 стал бы читаемым всем); нового файла — 600
        val perms = if (isPosix() && Files.isRegularFile(file)) Files.getPosixFilePermissions(file) else null
        if (perms != null) Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(perms))
        else createPrivateFile(tmp)
        Files.writeString(tmp, prettyGson.toJson(settings) + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return backup
    }
}
