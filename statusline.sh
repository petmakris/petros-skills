#!/usr/bin/env bash
input=$(cat)

# --- Colors ---
RESET=$'\033[0m'
DIM=$'\033[2m'
BOLD=$'\033[1m'
CYAN=$'\033[36m'
GREEN=$'\033[32m'
YELLOW=$'\033[33m'
RED=$'\033[31m'
BLUE=$'\033[34m'
MAGENTA=$'\033[35m'

# --- Icons (require a Nerd Font) ---
ICON_DIR=$''      #   folder
ICON_GIT=$''      #   git branch
ICON_CTX=$''      #   bolt (context)
ICON_DIFF=$''     #   diff
ICON_USER=$''     #   user / account
ICON_TREE=$'\xef\x83\xa8'     #   sitemap (worktrees)
ICON_SESSION=$''     #   hashtag (session id)

# --- Parse JSON ---
cwd=$(echo "$input"        | jq -r '.workspace.current_dir // ""')
model_id=$(echo "$input"   | jq -r '.model.id // ""')
used_pct=$(echo "$input"   | jq -r '.context_window.used_percentage // 0')
used_tokens=$(echo "$input"| jq -r '.context_window.total_input_tokens // .context_window.used_tokens // 0')
added=$(echo "$input"      | jq -r '.cost.total_lines_added // 0')
removed=$(echo "$input"    | jq -r '.cost.total_lines_removed // 0')
rl5h_pct=$(echo "$input"   | jq -r '.rate_limits.five_hour.used_percentage // empty')
rl7d_pct=$(echo "$input"   | jq -r '.rate_limits.seven_day.used_percentage // empty')
session_id=$(echo "$input" | jq -r '.session_id // ""')
session_short=${session_id:0:8}

# --- Tee raw stdin for the annotate webpage's stat strip ---
# The annotate skill renders a browser view of Claude's response; its header
# mirrors this statusline. It reads the latest snapshot from this file, keyed by
# session id (joined via claude_session_id in the session's meta.json). The
# annotate server resolves this dir from $HOME/.claude regardless of profile, so
# pin to $HOME/.claude here too (not CLAUDE_CONFIG_DIR) to keep the paths aligned.
if [ -n "$session_id" ]; then
  sl_dir="$HOME/.claude/annotate/statusline"
  mkdir -p "$sl_dir" 2>/dev/null && printf '%s' "$input" > "$sl_dir/$session_id.json" 2>/dev/null
fi

dir=$(basename "$cwd")

# --- Repo / worktree detection ---
# repo_name  = basename of the main repo (where the real .git lives)
# wt_name    = basename of the current worktree dir (only set when in a linked worktree)
repo_name=""
wt_name=""
git_dir=$(git -C "$cwd" --no-optional-locks rev-parse --git-dir 2>/dev/null)
common_dir=$(git -C "$cwd" --no-optional-locks rev-parse --git-common-dir 2>/dev/null)
if [ -n "$common_dir" ]; then
  common_abs=$(cd "$cwd" 2>/dev/null && cd "$common_dir" 2>/dev/null && pwd)
  [ -n "$common_abs" ] && repo_name=$(basename "$(dirname "$common_abs")")
  if [ -n "$git_dir" ] && [ "$git_dir" != "$common_dir" ]; then
    toplevel=$(git -C "$cwd" --no-optional-locks rev-parse --show-toplevel 2>/dev/null)
    [ -n "$toplevel" ] && wt_name=$(basename "$toplevel")
  fi
fi
[ -z "$repo_name" ] && repo_name="$dir"

# --- Pressure color picker (shared by context + rate limits) ---
pressure_color() {
  local p="$1"
  if   [ "$p" -ge 75 ]; then printf '%s' "$RED"
  elif [ "$p" -ge 50 ]; then printf '%s' "$YELLOW"
  else                       printf '%s' "$GREEN"
  fi
}

# --- Git ---
branch=$(git -C "$cwd" --no-optional-locks rev-parse --abbrev-ref HEAD 2>/dev/null)
dirty=""
ab=""
if [ -n "$branch" ]; then
  if [ -n "$(git -C "$cwd" --no-optional-locks status --porcelain 2>/dev/null)" ]; then
    dirty=" ${YELLOW}●${RESET}"
  fi
  upstream=$(git -C "$cwd" --no-optional-locks rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)
  if [ -n "$upstream" ]; then
    counts=$(git -C "$cwd" --no-optional-locks rev-list --left-right --count "${upstream}...HEAD" 2>/dev/null)
    behind=$(echo "$counts" | awk '{print $1+0}')
    ahead=$(echo  "$counts" | awk '{print $2+0}')
    parts=""
    [ "$ahead"  != "0" ] && parts="${MAGENTA}↑${ahead}${RESET}"
    [ "$behind" != "0" ] && parts="${parts:+$parts }${MAGENTA}↓${behind}${RESET}"
    [ -n "$parts" ] && ab=" $parts"
  fi
fi

# --- Context % + tokens (color by pressure) ---
pct_int=$(printf '%.0f' "$used_pct")
ctx_color=$(pressure_color "$pct_int")
if [ "$used_tokens" -ge 1000 ]; then
  tok_str=$(awk -v t="$used_tokens" 'BEGIN{printf "%.0fk", t/1000}')
else
  tok_str="$used_tokens"
fi

# --- Model family + 1M badge ---
model_family=""
if [[ "$model_id" == *"fable"* ]]; then
  model_family="${CYAN}Fable${RESET}"
elif [[ "$model_id" == *"mythos"* ]]; then
  model_family="${CYAN}Mythos${RESET}"
elif [[ "$model_id" == *"opus"* ]]; then
  model_family="${MAGENTA}Opus${RESET}"
elif [[ "$model_id" == *"sonnet"* ]]; then
  model_family="${BLUE}Sonnet${RESET}"
elif [[ "$model_id" == *"haiku"* ]]; then
  model_family="${GREEN}Haiku${RESET}"
elif [ -n "$model_id" ]; then
  # Unknown family (future models): show the segment after "claude-",
  # capitalized, instead of dropping the badge entirely.
  fam=${model_id#claude-}
  fam=${fam%%-*}
  fam=${fam%%\[*}
  [ -n "$fam" ] && model_family="${DIM}$(printf '%s' "$fam" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')${RESET}"
fi

badge=""
[[ "$model_id" == *"1m"* ]] && badge=" ${BOLD}${MAGENTA}[1M]${RESET}"

# --- Profile (Claude account, derived from CLAUDE_CONFIG_DIR) ---
config_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
case "$config_dir" in
  "$HOME/.claude")        profile="personal"; profile_color="$CYAN" ;;
  "$HOME/.claude-evooq")  profile="evooq";    profile_color="$YELLOW" ;;
  *)                      profile=$(basename "$config_dir"); profile_color="$MAGENTA" ;;
esac

# --- Compose ---
# Line 1 = identity (who/where): profile, repo, worktree (when in one), branch.
# Line 2 = runtime state: context, rate limits, model, diff.
out=""
out+="${DIM}[${RESET}${profile_color}${ICON_USER} ${profile}${RESET}${DIM}]${RESET} "
out+="${DIM}[${RESET}${BLUE}${ICON_DIR} ${repo_name}${RESET}${DIM}]${RESET}"
if [ -n "$wt_name" ]; then
  out+=" ${DIM}[${RESET}${YELLOW}${ICON_TREE} ${wt_name}${RESET}${DIM}]${RESET}"
fi
if [ -n "$branch" ]; then
  out+=" ${DIM}[${RESET}${GREEN}${ICON_GIT} ${branch}${RESET}${dirty}${ab}${DIM}]${RESET}"
fi
if [ -n "$session_short" ]; then
  out+=" ${DIM}[${RESET}${DIM}${ICON_SESSION} ${session_short}${RESET}${DIM}]${RESET}"
fi
out+=$'\n'
out+="${DIM}[${RESET}${ctx_color}${ICON_CTX} ${pct_int}% · ${tok_str}${RESET}${DIM}]${RESET}"
# Rate-limit segments: 5-hour and 7-day rolling windows, sourced from
# .rate_limits.{five_hour,seven_day}.used_percentage in the Claude Code
# statusline JSON. Each window also exposes .resets_at (Unix epoch seconds),
# currently unused.
#
# Easy follow-ups if these get noisy when usage is low:
#   - Hide a segment below some threshold (e.g. only show 7d when >=10%).
#   - Show reset countdown (e.g. "5h 60% · 2h12m") only when usage is high,
#     using .resets_at minus `date +%s`.
if [ -n "$rl5h_pct" ]; then
  rl5h_int=$(printf '%.0f' "$rl5h_pct")
  rl5h_color=$(pressure_color "$rl5h_int")
  out+=" ${DIM}[${RESET}${rl5h_color}5h ${rl5h_int}%${RESET}${DIM}]${RESET}"
fi
if [ -n "$rl7d_pct" ]; then
  rl7d_int=$(printf '%.0f' "$rl7d_pct")
  rl7d_color=$(pressure_color "$rl7d_int")
  out+=" ${DIM}[${RESET}${rl7d_color}7d ${rl7d_int}%${RESET}${DIM}]${RESET}"
fi
if [ -n "$model_family" ]; then
  out+=" ${DIM}[${RESET}${model_family}${DIM}]${RESET}"
fi
if [ "$added" != "0" ] || [ "$removed" != "0" ]; then
  out+=" ${DIM}[${RESET}${BLUE}${ICON_DIFF}${RESET} ${GREEN}+${added}${RESET} ${RED}-${removed}${RESET}${DIM}]${RESET}"
fi
out+="$badge"

printf '%s' "$out"
