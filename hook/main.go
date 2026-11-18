// cc-sessions-hook — хук Claude Code для плагина CC Sessions.
//
// Читает JSON события хука из stdin, ужимает его до компактной записи
// и дописывает одной строкой в <config>/cc-sessions/events/<session_id>.jsonl.
// Плагин IDE читает эти файлы и показывает live-статус сессий.
//
// Хук никогда не блокирует Claude Code: любые ошибки молча игнорируются,
// код выхода всегда 0, в stdout ничего не пишется.
package main

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxStdin      = 8 << 20 // 8 MiB: tool_input у Write бывает большим
	maxText       = 300
	maxSummary    = 200
	eventsSubdir  = "cc-sessions/events"
	schemaVersion = 1
)

var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

// Event — компактная запись, которую читает плагин.
type Event struct {
	V              int    `json:"v"`
	Ts             int64  `json:"ts"`
	Event          string `json:"event"`
	SessionID      string `json:"session_id"`
	Cwd            string `json:"cwd,omitempty"`
	TranscriptPath string `json:"transcript_path,omitempty"`
	PermissionMode string `json:"permission_mode,omitempty"`
	AgentID        string `json:"agent_id,omitempty"`
	AgentType      string `json:"agent_type,omitempty"`
	ToolName       string `json:"tool_name,omitempty"`
	ToolSummary    string `json:"tool_summary,omitempty"`
	ToolUseID      string `json:"tool_use_id,omitempty"`
	Prompt         string `json:"prompt,omitempty"`
	Message        string `json:"message,omitempty"`
	Kind           string `json:"kind,omitempty"` // notification_type / source / reason / matcher-like
	LastMessage    string `json:"last_message,omitempty"`
	Error          string `json:"error,omitempty"`
}

func main() {
	defer func() { _ = recover() }()
	data, err := io.ReadAll(io.LimitReader(os.Stdin, maxStdin))
	if err != nil || len(data) == 0 {
		return
	}
	ev, ok := Build(data, time.Now())
	if !ok {
		return
	}
	_ = Append(EventsDir(), ev)
}

// Build разбирает вход хука и строит компактное событие.
func Build(data []byte, now time.Time) (Event, bool) {
	var in map[string]any
	if err := json.Unmarshal(data, &in); err != nil {
		return Event{}, false
	}
	ev := Event{
		V:              schemaVersion,
		Ts:             now.UnixMilli(),
		Event:          str(in, "hook_event_name"),
		SessionID:      str(in, "session_id"),
		Cwd:            str(in, "cwd"),
		TranscriptPath: str(in, "transcript_path"),
		PermissionMode: str(in, "permission_mode"),
		AgentID:        str(in, "agent_id"),
		AgentType:      str(in, "agent_type"),
	}
	if ev.Event == "" || !safeID.MatchString(ev.SessionID) {
		return Event{}, false
	}

	switch ev.Event {
	case "PreToolUse", "PostToolUse", "PostToolUseFailure", "PermissionRequest", "PermissionDenied":
		ev.ToolName = str(in, "tool_name")
		ev.ToolUseID = str(in, "tool_use_id")
		if ti, ok := in["tool_input"].(map[string]any); ok {
			ev.ToolSummary = ToolSummary(ev.ToolName, ti)
		}
		if ev.Event == "PostToolUseFailure" {
			ev.Error = clip(firstStr(in, "error", "message"), maxText)
		}
	case "UserPromptSubmit":
		ev.Prompt = clip(firstStr(in, "prompt", "user_prompt"), maxText)
	case "Notification":
		ev.Message = clip(str(in, "message"), maxText)
		ev.Kind = firstStr(in, "notification_type", "type", "matcher")
		if ev.Kind == "" {
			ev.Kind = guessNotificationKind(ev.Message)
		}
	case "SessionStart":
		ev.Kind = str(in, "source")
	case "SessionEnd":
		ev.Kind = firstStr(in, "reason", "source")
	case "Stop", "SubagentStop":
		ev.LastMessage = clip(firstStr(in, "last_assistant_message", "last_message"), maxText)
	case "StopFailure":
		ev.Error = clip(firstStr(in, "error", "message", "reason"), maxText)
	case "PreCompact", "PostCompact":
		ev.Kind = firstStr(in, "trigger", "source")
	}
	return ev, true
}

// ToolSummary — короткое человекочитаемое описание вызова инструмента.
func ToolSummary(tool string, in map[string]any) string {
	var s string
	switch tool {
	case "Bash":
		s = firstStr(in, "description", "command")
		if d := str(in, "description"); d != "" {
			if c := str(in, "command"); c != "" {
				s = d + ": " + c
			}
		}
	case "Edit", "MultiEdit", "Write", "Read", "NotebookEdit":
		s = firstStr(in, "file_path", "notebook_path", "path")
	case "Glob", "Grep":
		s = firstStr(in, "pattern")
		if p := str(in, "path"); p != "" {
			s += " в " + p
		}
	case "WebFetch":
		s = str(in, "url")
	case "WebSearch":
		s = str(in, "query")
	case "Task", "Agent":
		s = firstStr(in, "description", "subagent_type", "prompt")
	case "Skill":
		s = firstStr(in, "skill", "name", "command")
	default:
		s = firstStr(in, "description", "file_path", "command", "query", "url", "pattern", "name")
	}
	s = strings.Join(strings.Fields(s), " ")
	return clip(s, maxSummary)
}

func guessNotificationKind(msg string) string {
	m := strings.ToLower(msg)
	switch {
	case strings.Contains(m, "permission"):
		return "permission_prompt"
	case strings.Contains(m, "waiting for your input"), strings.Contains(m, "idle"):
		return "idle_prompt"
	}
	return ""
}

// EventsDir — каталог событий с учётом CLAUDE_CONFIG_DIR.
func EventsDir() string {
	base := os.Getenv("CLAUDE_CONFIG_DIR")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return ""
		}
		base = filepath.Join(home, ".claude")
	}
	return filepath.Join(base, filepath.FromSlash(eventsSubdir))
}

// Append дописывает событие одной строкой (O_APPEND — атомарно для коротких записей).
func Append(dir string, ev Event) error {
	if dir == "" {
		return os.ErrInvalid
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	line, err := json.Marshal(ev)
	if err != nil {
		return err
	}
	line = append(line, '\n')
	f, err := os.OpenFile(filepath.Join(dir, ev.SessionID+".jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(line)
	return err
}

func str(m map[string]any, key string) string {
	switch v := m[key].(type) {
	case string:
		return v
	case nil:
		return ""
	case map[string]any, []any:
		return ""
	default:
		b, _ := json.Marshal(v)
		return string(b)
	}
}

func firstStr(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s := str(m, k); s != "" {
			return s
		}
	}
	return ""
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	r := []rune(s)
	return string(r[:n]) + "…"
}
