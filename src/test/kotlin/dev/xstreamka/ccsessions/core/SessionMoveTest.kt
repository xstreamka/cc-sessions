package dev.xstreamka.ccsessions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionMoveTest {
    @Test
    fun rewriteLineReplacesOnlyProjectCwd() {
        val line = """{"type":"user","cwd":"/home/u/old","message":{"content":"run in /home/u/old \"cwd\":\"/home/u/old\""}}"""
        assertEquals(
            """{"type":"user","cwd":"/home/u/new","message":{"content":"run in /home/u/old \"cwd\":\"/home/u/old\""}}""",
            SessionMove.rewriteLine(line, "/home/u/old", "/home/u/new"),
        )
        // вложенная папка сохраняет хвост, похожий префикс и чужой путь не трогаем
        assertEquals("""{"cwd":"/home/u/new/sub"}""", SessionMove.rewriteLine("""{"cwd":"/home/u/old/sub"}""", "/home/u/old", "/home/u/new"))
        assertEquals("""{"cwd":"/home/u/older"}""", SessionMove.rewriteLine("""{"cwd":"/home/u/older"}""", "/home/u/old", "/home/u/new"))
        assertEquals("""{"cwd":"/tmp"}""", SessionMove.rewriteLine("""{"cwd":"/tmp"}""", "/home/u/old", "/home/u/new"))
        // Windows-пути с экранированием и не-ASCII
        assertEquals(
            """{"cwd":"D:\\Проекты\\new\\sub"}""",
            SessionMove.rewriteLine("""{"cwd":"C:\\work\\old\\sub"}""", "C:\\work\\old", "D:\\Проекты\\new"),
        )
    }

    @Test
    fun moveRelocatesTranscriptAndCompanions() {
        val root = Files.createTempDirectory("ccs-move")
        val paths = ClaudePaths(root)
        val id = "11111111-2222-3333-4444-555555555555"
        val src = paths.projectDirFor("/w/old").also { Files.createDirectories(it) }
        val transcript = src.resolve("$id.jsonl")
        Files.writeString(transcript, """{"cwd":"/w/old","sessionId":"$id"}""" + "\n" + """{"cwd":"/w/old/sub"}""")
        Files.writeString(src.resolve("$id.desktop-released.json"), "{}")
        val sub = src.resolve(id).resolve("subagents").also { Files.createDirectories(it) }
        Files.writeString(sub.resolve("agent-a.jsonl"), """{"cwd":"/w/old"}""" + "\n")
        Files.writeString(src.resolve("other.jsonl"), """{"cwd":"/w/old"}""")

        assertEquals("/w/old", SessionMove.sourceRoot(transcript))
        val moved = SessionMove.move(paths, id, transcript, "/w/old", "/w/new")

        val dst = paths.projectDirFor("/w/new")
        assertEquals(dst.resolve("$id.jsonl"), moved)
        assertFalse(Files.exists(transcript))
        assertEquals("""{"cwd":"/w/new","sessionId":"$id"}""" + "\n" + """{"cwd":"/w/new/sub"}""", Files.readString(moved))
        assertTrue(Files.exists(dst.resolve("$id.desktop-released.json")))
        assertEquals("""{"cwd":"/w/new"}""" + "\n", Files.readString(dst.resolve(id).resolve("subagents").resolve("agent-a.jsonl")))
        assertFalse(Files.exists(src.resolve(id)))
        assertTrue(Files.exists(src.resolve("other.jsonl"))) // чужие сессии остаются
        assertEquals(listOf("other.jsonl"), Files.list(src).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun moveRefusesExistingTarget() {
        val root = Files.createTempDirectory("ccs-move2")
        val paths = ClaudePaths(root)
        val id = "11111111-2222-3333-4444-555555555556"
        val src = paths.projectDirFor("/w/a").also { Files.createDirectories(it) }
        val dst = paths.projectDirFor("/w/b").also { Files.createDirectories(it) }
        val transcript = src.resolve("$id.jsonl").also { Files.writeString(it, """{"cwd":"/w/a"}""") }
        Files.writeString(dst.resolve("$id.jsonl"), "x")
        val e = runCatching { SessionMove.move(paths, id, transcript, "/w/a", "/w/b") }.exceptionOrNull()
        assertTrue(e is SessionMove.MoveException)
        assertTrue(Files.exists(transcript))
        assertEquals("x", Files.readString(dst.resolve("$id.jsonl")))
    }
}
