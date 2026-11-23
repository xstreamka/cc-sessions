package dev.xstreamka.ccsessions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SessionHandoffTest {
    private fun run(vararg events: LiveEvent): Map<String, LiveState> {
        val states = HashMap<String, LiveState>()
        for (ev in events.sortedBy { it.ts }) states[ev.sessionId] = LiveReducer.apply(states[ev.sessionId], ev)
        return states
    }

    private fun ev(ts: Long, event: String, sid: String, kind: String? = null, cwd: String? = "/p") =
        LiveEvent(ts, event, sid, cwd = cwd, kind = kind)

    @Test
    fun reducerRemembersSwitch() {
        val st = run(ev(1, "SessionStart", "old", "startup"), ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "clear"))
        assertEquals(SessionSwitch("clear", 100), st["old"]!!.switchedOut)
        assertNull(st["old"]!!.switchedIn)
        assertEquals(SessionSwitch("clear", 170), st["new"]!!.switchedIn)
        assertNull(st["new"]!!.switchedOut)
        // Работа и сжатие контекста в новой сессии метку не стирают
        var later = LiveReducer.apply(st["new"], ev(500, "UserPromptSubmit", "new"))
        later = LiveReducer.apply(later, ev(600, "SessionStart", "new", "compact"))
        assertEquals(SessionSwitch("clear", 170), later.switchedIn)
        // Обычный выход — не смена сессии; повторный запуск (claude --resume) метку снимает
        val exit = run(ev(1, "SessionStart", "a", "startup"), ev(2, "SessionEnd", "a", "prompt_input_exit"))
        assertNull(exit["a"]!!.switchedOut)
        val resumed = LiveReducer.apply(st["old"], ev(900, "SessionStart", "old", "startup"))
        assertNull(resumed.switchedOut)
    }

    @Test
    fun successorAfterClear() {
        val st = run(
            ev(1, "SessionStart", "old", "startup"),
            ev(100, "SessionEnd", "old", "clear"),
            ev(170, "SessionStart", "new", "clear"),
            ev(171, "UserPromptSubmit", "new"),
        )
        assertEquals("new", SessionHandoff.successor("old", st, emptySet()))
        // Уже привязана к другой вкладке — не берём
        assertNull(SessionHandoff.successor("old", st, setOf("new")))
    }

    @Test
    fun successorAfterResume() {
        // События из настоящего /resume внутри claude 2.1.330
        val st = run(
            ev(1795444245473, "SessionStart", "a", "resume"),
            ev(1795444255336, "SessionEnd", "a", "resume"),
            ev(1795444255349, "SessionStart", "b", "resume"),
        )
        assertEquals("b", SessionHandoff.successor("a", st, emptySet()))
        // Вышли из claude и запустили claude --resume: у прежней сессии причина другая — это не смена в том же claude
        val relaunch = run(ev(100, "SessionEnd", "a", "prompt_input_exit"), ev(1500, "SessionStart", "b", "resume"))
        assertNull(SessionHandoff.successor("a", relaunch, emptySet()))
    }

    @Test
    fun kindsMustMatch() {
        // /clear в одной вкладке и claude --resume рядом — не пара
        val st = run(ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "resume"))
        assertNull(SessionHandoff.successor("old", st, emptySet()))
    }

    @Test
    fun noSuccessorWithoutMatchingSwitch() {
        // Сессия просто закончилась
        val ended = run(ev(100, "SessionEnd", "old", "prompt_input_exit"), ev(170, "SessionStart", "new", "clear"))
        assertNull(SessionHandoff.successor("old", ended, emptySet()))
        // Ещё работает
        val alive = run(ev(100, "UserPromptSubmit", "old"), ev(170, "SessionStart", "new", "clear"))
        assertNull(SessionHandoff.successor("old", alive, emptySet()))
        // Смена в другой папке
        val otherCwd = run(ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "clear", cwd = "/q"))
        assertNull(SessionHandoff.successor("old", otherCwd, emptySet()))
        // Новая сессия началась намного позже — это не наша смена
        val late = run(ev(100, "SessionEnd", "old", "clear"), ev(60_000, "SessionStart", "new", "clear"))
        assertNull(SessionHandoff.successor("old", late, emptySet()))
        // Новая сессия запущена с нуля
        val startup = run(ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "startup"))
        assertNull(SessionHandoff.successor("old", startup, emptySet()))
    }

    @Test
    fun nearestSuccessorWins() {
        // Две смены почти одновременно в одной папке: каждой прежней — ближайшая по времени новая
        val st = run(
            ev(100, "SessionEnd", "a", "clear"),
            ev(150, "SessionStart", "a2", "clear"),
            ev(3000, "SessionEnd", "b", "clear"),
            ev(3050, "SessionStart", "b2", "clear"),
        )
        assertEquals("a2", SessionHandoff.successor("a", st, emptySet()))
        assertEquals("b2", SessionHandoff.successor("b", st, emptySet()))
        // Одинаково близко — неизвестно, чья: не угадываем
        val tie = run(ev(100, "SessionEnd", "a", "clear"), ev(50, "SessionStart", "x", "clear"), ev(150, "SessionStart", "y", "clear"))
        assertNull(SessionHandoff.successor("a", tie, emptySet()))
    }

    @Test
    fun openTabsListReplaced() {
        val list = listOf("a", "old", "b")
        assertEquals(listOf("a", "new", "b"), OpenTabsList.replaced(list, "old", "new")) // место в порядке сохраняется
        assertEquals(listOf("a", "b"), OpenTabsList.replaced(list, "old", "b")) // новая уже есть — без дубля
        assertEquals(listOf("a", "old", "b", "new"), OpenTabsList.replaced(list, "zzz", "new")) // старой не было — как открытие
        assertSame(list, OpenTabsList.replaced(list, "old", "old"))
    }
}
