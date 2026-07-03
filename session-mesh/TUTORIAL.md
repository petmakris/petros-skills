# Session Mesh — tutorial & command matrix

Coordinate several Claude Code sessions from one **master** session. The master is a *task manager*; each **worker** session owns *one task* in its own worktree. They talk through a small SQLite queue — no tty injection, no shared memory. Workers execute queued commands themselves, at full autonomy. It's event-driven (a "doorbell"), so idle workers cost **zero tokens** and never spam the terminal.

Two roles, one rule of thumb:
- **Master** = the session where *you* sit and drive the fleet.
- **Worker** = a session parked in a task's worktree, listening for commands.

> Terminology note: a worker is identified by a free-form **label** (the `sessions.label` column). It accepts **any** string (e.g. `docs-rewrite` or `reporting-openapi`, not just `PMP-211`) — there is no Jira/branch coupling. The task-backlog and auto-spawn skills are designed in `specs/2026-07-03-task-manager-mesh-design.md` — see [Planned](#planned--task-manager-redesign) at the bottom.

## Command matrix

Only **`/mesh-dispatch`** takes arguments. Every other command acts on *this session* (worker) or the *whole fleet* (master), so you just run it.

### Setup — once per machine

| Command | Args | When to use |
|---|---|---|
| `/mesh-init` | — | First time on a machine, or when any skill reports "database missing". Creates the SQLite store. Idempotent — safe to re-run. |

### Worker — run inside the session doing the task

| Command | Args | When to use |
|---|---|---|
| `/mesh-join` | — | **The one command you type in a worker.** Registers this session and arms the doorbell in a single step. It auto-derives the task label from the branch; if it can't, it asks. After this, the session listens and executes whatever the master queues. |
| `/mesh-arm` | — | Re-arm the doorbell on its own (without re-registering). You rarely type this directly — `/mesh-join` already arms. Useful if the watcher was stopped but the session is still registered. |
| `/mesh-poll` | — | **Legacy.** One poll tick for the old `/loop 1m /mesh-poll` model. Superseded by the event-driven `/mesh-arm`; kept only for backward compatibility. |

### Master — run in the task-manager session

| Command | Args | When to use |
|---|---|---|
| `/mesh-board` | — | "What's running?" Live roster of every worker: alive / stale / dead, its task, current activity, and recent commands. |
| `/mesh-dispatch` | `<target> <kind> "<payload>"` | Queue work for a worker (or all). See the argument breakdown below. Runs on the worker under full autonomy the instant it's claimed. |
| `/mesh-await` | — | After dispatching, wait *quietly* to be woken the moment a result lands. Event-driven, no polling, zero tokens while waiting. |
| `/mesh-collect` | — | One-shot: pull any *already-finished* results right now, no waiting. Use for an instant inbox check; use `/mesh-await` when you want to be notified as results land. |
| `/mesh-pause` | — | Emergency stop the whole fleet. Workers keep heartbeating (stay green) but execute nothing. |
| `/mesh-resume` | — | Lift the pause; workers resume executing queued commands. |

### `/mesh-dispatch` arguments

`/mesh-dispatch <target> <kind> "<payload>"`

| Part | Values | Meaning |
|---|---|---|
| `target` | a task label (e.g. `PMP-211`, `reporting-openapi`), a session id, or `*` | Which worker(s). `*` broadcasts to every session. |
| `kind` | `shell` | Run `payload` as a shell command in the worker's worktree; captures output + exit code. |
| | `prompt` | Have the worker carry out `payload` as a task, using its normal tools; returns a summary. |
| | `control` | `payload` = `stop` takes that worker offline (disarms the doorbell). |
| `payload` | free text (quote it) | The command / prompt / control verb. May contain `|`. |

## Typical flows

**1 — Stand up a worker.** In a terminal, open a Claude Code session in the task's worktree, then:
```
/mesh-join
```
That's it. The session registers and starts listening. Leave it be — it costs nothing while idle.

**2 — Drive a worker from the master.**
```
/mesh-board                                  # see who's live
/mesh-dispatch PMP-211 shell "npm test"      # queue work
/mesh-await                                   # get woken when it finishes
```

**3 — Broadcast to everyone.**
```
/mesh-dispatch * prompt "pull main, rebase your branch, report conflicts"
```

**4 — Panic button.**
```
/mesh-pause        # everything holds
/mesh-resume       # ...back to work
```

**5 — Retire a worker.**
```
/mesh-dispatch reporting-openapi control stop
```

## Good to know

- **Full autonomy.** Workers execute what they claim, unattended. The global pause (`/mesh-pause`) is the only stop. Dispatch deliberately.
- **Don't remote-control a session you're hand-typing in.** Arm workers in dedicated worker sessions; keep your master session for driving.
- **The doorbell.** Detection (heartbeat + claim) runs in a detached background shell that blocks until there's real work, then exits — and a background command exiting wakes the session model exactly once. So the model runs only when there's something to do; idle cost is zero. (`/mesh-await` is the master-side twin: it waits the same way for results.)
- **Crash recovery.** A claimed command whose worker dies is automatically re-queued (the reaper runs inside `/mesh-board` and `/mesh-dispatch`) — no daemon, no manual step.

## Planned — task-manager redesign

Designed in `specs/2026-07-03-task-manager-mesh-design.md`, not yet implemented:

| Command | Args | Role | Purpose |
|---|---|---|---|
| `/mesh-task-add` | `<slug> "<title/desc>"` | master | Add a task to the **backlog** — before any session exists. |
| `/mesh-task-start` | `<slug> [cwd]` | master | **Auto-spawn** a worker for the task (`claude --bg`) so you no longer hand-start it. Marks the task in-progress. |
| `/mesh-task-done` | `<slug>` | master | Mark a task done (optionally stop its worker). |

Plus: `/mesh-board` gains a backlog section grouped by status. (The identifier de-Jira-ification is **done** — the old `ticket` column is now the free-form `label` column; the spec's `task` naming was superseded by `label`.)
