package dev.xstreamka.ccsessions.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Пути к данным Claude Code. Учитывает CLAUDE_CONFIG_DIR.
 */
class ClaudePaths(val configDir: Path) {

    val projectsDir: Path get() = configDir.resolve("projects")
    val settingsFile: Path get() = configDir.resolve("settings.json")
    val pluginDir: Path get() = configDir.resolve("cc-sessions")
    val eventsDir: Path get() = pluginDir.resolve("events")
    val binDir: Path get() = pluginDir.resolve("bin")
    val metaFile: Path get() = pluginDir.resolve("meta.json")

    /**
     * Каталог транскриптов для рабочей директории проекта.
     * Claude Code заменяет все не-алфавитно-цифровые символы на '-'.
     * Длинные (>200) имена он обрезает и добавляет хеш — их ищем сканированием [findProjectDirs].
     */
    fun projectDirFor(workDir: String): Path = projectsDir.resolve(encodeProjectDir(workDir))

    /** Все каталоги проектов, относящиеся к [workDir] (точное совпадение имени или cwd внутри транскриптов). */
    fun findProjectDirs(workDir: String): List<Path> {
        val direct = projectDirFor(workDir)
        val result = LinkedHashSet<Path>()
        if (Files.isDirectory(direct)) result.add(direct)
        val encoded = encodeProjectDir(workDir)
        if (encoded.length > 200 && Files.isDirectory(projectsDir)) {
            val prefix = encoded.substring(0, 200)
            Files.list(projectsDir).use { s ->
                s.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }.forEach { result.add(it) }
            }
        }
        return result.toList()
    }

    fun allProjectDirs(): List<Path> {
        if (!Files.isDirectory(projectsDir)) return emptyList()
        return Files.list(projectsDir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
    }

    companion object {
        fun encodeProjectDir(workDir: String): String =
            workDir.trimEnd('/', '\\').ifEmpty { workDir }.replace(Regex("[^A-Za-z0-9]"), "-")

        fun detect(env: Map<String, String> = System.getenv()): ClaudePaths {
            val custom = env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }
            val dir = if (custom != null) Paths.get(custom) else Paths.get(System.getProperty("user.home"), ".claude")
            return ClaudePaths(dir)
        }
    }
}
