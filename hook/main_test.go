package main

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var now = time.UnixMilli(1763380000000)

func TestBuildPreToolUseBash(t *testing.T) {
	in := `{"session_id":"abc-123","cwd":"/home/u/p","permission_mode":"default","hook_event_name":"PreToolUse",
	"tool_name":"Bash","tool_input":{"command":"npm   test","description":"Run tests"},"tool_use_id":"toolu_1"}`
	ev, ok := Build([]byte(in), now)
	if !ok {
		t.Fatal("not ok")
	}
	if ev.ToolName != "Bash" || ev.ToolSummary != "Run tests: npm test" || ev.Ts != now.UnixMilli() {
		t.Fatalf("bad event: %+v", ev)
	}
}

func TestBuildWriteDoesNotCopyContent(t *testing.T) {
	big := strings.Repeat("x", 100000)
	in, _ := json.Marshal(map[string]any{
		"session_id": "s1", "hook_event_name": "PreToolUse", "tool_name": "Write",
		"tool_input": map[string]any{"file_path": "/a/b.go", "content": big},
	})
	ev, ok := Build(in, now)
	if !ok || ev.ToolSummary != "/a/b.go" {
		t.Fatalf("bad: %+v", ev)
	}
	line, _ := json.Marshal(ev)
	if len(line) > 1000 {
		t.Fatalf("event too big: %d", len(line))
	}
}

func TestBuildRejectsBadSessionID(t *testing.T) {
	for _, id := range []string{"", "../etc/passwd", "a/b", strings.Repeat("a", 200)} {
		in, _ := json.Marshal(map[string]any{"session_id": id, "hook_event_name": "Stop"})
		if _, ok := Build(in, now); ok {
			t.Fatalf("accepted %q", id)
		}
	}
}

func TestBuildNotificationAndPromptClip(t *testing.T) {
	ev, _ := Build([]byte(`{"session_id":"s","hook_event_name":"Notification","message":"Claude needs your permission to use Bash"}`), now)
	if ev.Kind != "permission_prompt" {
		t.Fatalf("kind %q", ev.Kind)
	}
	p := strings.Repeat("я", 500)
	in, _ := json.Marshal(map[string]any{"session_id": "s", "hook_event_name": "UserPromptSubmit", "prompt": p})
	ev, _ = Build(in, now)
	if n := len([]rune(ev.Prompt)); n != maxText+1 {
		t.Fatalf("prompt runes %d", n)
	}
}

func TestAppend(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"SessionStart", "UserPromptSubmit", "Stop"} {
		ev, _ := Build([]byte(`{"session_id":"sid","hook_event_name":"`+name+`"}`), now)
		if err := Append(dir, ev); err != nil {
			t.Fatal(err)
		}
	}
	f, err := os.Open(filepath.Join(dir, "sid.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	n := 0
	for sc.Scan() {
		var ev Event
		if err := json.Unmarshal(sc.Bytes(), &ev); err != nil {
			t.Fatal(err)
		}
		n++
	}
	if n != 3 {
		t.Fatalf("lines %d", n)
	}
}

func TestEventsDirHonorsConfigDir(t *testing.T) {
	t.Setenv("CLAUDE_CONFIG_DIR", "/tmp/cfg")
	if got := EventsDir(); got != filepath.Join("/tmp/cfg", "cc-sessions", "events") {
		t.Fatal(got)
	}
}
