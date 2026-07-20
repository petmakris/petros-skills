# Resuming a workspace

Read this when the user invokes `/annotate resume` (with or without a slug
argument), or when you're about to push a fresh response and want to check
whether this project already has a live workspace worth reusing instead of
forking a new one (the "auto-offer" below).

This is a **separate, later invocation** from the pushing pipeline — it doesn't
create content, it points the conversation's `workspace` marker (see
`references/pushing.md` § "Create-or-attach a workspace for this conversation")
at an existing session, then optionally re-arms a watcher on it. Once the
marker is set, the *next* push in this conversation attaches to it
automatically — no different from a second push in a conversation that never
left, per `references/pushing.md`.

Every step below needs the server URL:

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
```

## `/annotate resume <slug>`

1. **Look it up:**

   ```bash
   curl -sf "$SERVER_URL/api/sessions?scope=all"
   ```

   Find the row whose `"slug"` field matches the argument.

2. **Not found, or found with `"status": "done"`** — tell the user plainly and
   stop. Do not POST, do not create anything. A `"done"` row is a workspace
   the user already clicked Done/cancelled on — attaching to it would reopen
   the same directory but the page still renders "This annotation round is
   closed" (the `finished`/`cancelled` marker file is still on disk), so treat
   it the same as not-found rather than attaching:

   > *"`<slug>` isn't a live workspace. Run `/annotate resume` with no argument
   > to list this project's live workspaces, or open the browser at
   > `<SERVER_URL>/`."*

3. **Found** — attach to it (same call `references/pushing.md` uses for a
   subsequent push, so it doubles as the "reuse this workspace" step and
   hands back the full directory bundle in one call):

   ```bash
   curl -sf -X POST "$SERVER_URL/api/sessions" \
     -H 'Content-Type: application/json' \
     -d "$(printf '{"cwd": "%s", "title": "%s", "slug": "%s", "attach": true}' "$PWD" "$ROW_TITLE" "$SLUG")"
   ```

   `$SLUG` is the resume argument; `$ROW_TITLE` is the matched row's `title`
   (attaching by slug never overwrites an existing workspace's title, so this
   is cosmetic — send it anyway for consistency with `references/pushing.md`
   § "Subsequent pushes"). This returns `created: false` and the same `sid`/`response_dir`/`state_dir`/
   `events_dir`/`consumed_dir`/`url`/`localhost_url` the workspace has always
   had.

4. **Set the conversation's workspace marker and re-arm a watcher** on the
   returned `state_dir`/`events_dir`/`consumed_dir` — exactly the "Arming the
   watcher" procedure in `references/pushing.md`, sourced from this attach
   response instead of a create response. That step is also what writes the
   `workspace: {"sid","slug"}` marker into
   `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`, which is what
   makes the *next* push in this conversation attach here instead of creating
   a new workspace.

5. **Announce** the slug URL: *"Resumed `<title>` → `<localhost_url>` (or
   `<url>`). Comments will attach to this workspace from here."*

Subsequent pushes in this conversation now follow the ordinary
"Subsequent pushes" path in `references/pushing.md` — no special-casing needed
after this point.

## `/annotate resume` (no argument)

The server only tracks a workspace's directory basename as `"project"`, not
its full path — see "Why not `?cwd=`" below — so listing "this project's"
workspaces means filtering by that basename, not an exact path match:

1. ```bash
   curl -sf "$SERVER_URL/api/sessions?scope=all"
   ```
2. Filter rows client-side to `row["project"] == "$(basename "$PWD")" and
   row["status"] != "done"`. Rows are already newest-first by `last_active`;
   `"done"` rows are ones the user already finished or cancelled — resuming one
   would just show "This annotation round is closed", so leave them out.
3. **None match** — say so and point at the alternatives:

   > *"No live annotate workspaces for this project. Open the browser at
   > `<SERVER_URL>/` to browse every project, or just push — a new workspace
   > will be created."*

4. **One or more match** — present a short list (slug, title, last-active) and
   ask which to resume, e.g.:

   > *"Live workspaces for this project: `fixing-the-flaky-test` (updated 2m
   > ago), `auth-refactor-plan` (updated 1h ago). Which one, or start a new
   > one? You can also browse all of them at `<SERVER_URL>/`."*

   Once the user names one, follow `/annotate resume <slug>` above.

Two projects that happen to share a directory basename (e.g. `backend` in two
different repos) will both show up in this filter — the mismatch is rare
enough not to special-case, but don't assume every row you see actually lives
under `$PWD` without checking the title if it matters.

## Auto-offer: don't silently fork a new workspace

Before creating a fresh workspace for what looks like the first push of a
conversation (the `$WORKSPACE` marker in `references/pushing.md` is empty),
run the same project-filtered lookup as the no-argument case above. If it
finds any live (non-`"done"`) row, don't create — offer the choice instead:

> *"This project already has a live annotate workspace: `<title>` (`<slug>`),
> last active `<last_active>`. Resume it, or start a new one?"*

- **Resume** → follow `/annotate resume <slug>` above.
- **New** → proceed with `references/pushing.md` § "First push of this
  conversation" as normal; the two workspaces coexist (different slugs, both
  under the same project's `.claude/annotate/`).

## Why not `GET /api/sessions?cwd=`

`?cwd=<path>` is a **legacy, exact-path** query kept byte-for-byte compatible
for `interactive_review`. It returns only `{"sid", "pr_ref", "title",
"state_dir"}` — no `slug`, no `project`, no `last_active` — and `title`/`pr_ref`
come from a `state_dir/meta.json` file the annotate skill never writes (that's
a different file from the `response_dir/meta.json` `references/pushing.md`
writes on every push), so for annotate-created sessions `title` there is
always `""`. It also can't hand back a slug URL to announce. `?scope=all` is
the query that carries `slug`/`project`/`last_active`/`status`
(`skills/_shared/web_companion/server.py` — see `list_rows`,
`session_row(..., legacy=False)`); it's also what the browser's own landing
page (`skills/_shared/web_companion/static/sessions.html`) fetches and filters
client-side by project. Resume and the auto-offer both do the same.

## Never crash on a bad slug

If `<slug>` doesn't resolve, or the server is unreachable, degrade to a plain
message and stop — same "re-run `ensure_server.sh` and retry" pattern as
`references/handling-events.md` § Edge cases for a transient failure. Never
let a resume attempt fall through into creating (or attaching to) the wrong
workspace silently.
