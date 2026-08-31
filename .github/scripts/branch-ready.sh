#!/usr/bin/env bash
#
# Reports whether the staged change is ready to commit. Blocking failures are the ones a machine
# can decide; everything else is reported and left to judgement, because a gate that blocks on a
# guess gets switched off and then guards nothing.
#
# Takes no arguments. Exit 0 = ready (notes may still be printed), 1 = not ready, 2 = usage error.

# No -E: it propagates the ERR trap into $( ), where the trap's exit overwrites the real status and
# masks a command's own exit code. See docs/shell-code-style.md.
set -euo pipefail

readonly NOT_READY=1
readonly USAGE_ERROR=2
readonly GREP_NO_MATCH_STATUS=1
readonly CHECK_IGNORE_NO_MATCH_STATUS=1

blockers=()
notes=()

log_error() {
  printf 'ERROR %s\n' "$1" >&2
}

log_note() {
  printf 'NOTE  %s\n' "$1" >&2
}

if [ "$#" -gt 0 ]; then
  log_error "branch-ready.sh takes no arguments; got: $*"
  exit "${USAGE_ERROR}"
fi

repository_root="$(git rev-parse --show-toplevel)"
readonly repository_root
cd "${repository_root}"

staged_paths="$(git diff --cached --name-only --diff-filter=ACMR)"
readonly staged_paths

if [ -z "${staged_paths}" ]; then
  log_note "Nothing is staged, so there is nothing to check."
  exit 0
fi

# Empty output is a legitimate answer here, so a no-match exit is not a failure. Any other status
# is, and set -e turns it into one at the assignment.
staged_matching() {
  printf '%s\n' "${staged_paths}" | grep -E "$1" || [ "$?" -eq "${GREP_NO_MATCH_STATUS}" ]
}

newest_test_report() {
  local newest=""

  while IFS= read -r report; do
    [ -n "${report}" ] || continue
    if [ -z "${newest}" ] || [ "${report}" -nt "${newest}" ]; then
      newest="${report}"
    fi
  done < <(find . -path '*/target/surefire-reports/*.txt' -type f)

  printf '%s' "${newest}"
}

# A force-added ignored file. Derived from .gitignore rather than a second list of paths, so the
# ignore rules stay the single place naming what must never be committed. --no-index is
# load-bearing: without it a forced add makes the path tracked, and check-ignore then reports it as
# not ignored — precisely the case this exists to catch.
force_added="$(printf '%s\n' "${staged_paths}" | git check-ignore --no-index --stdin \
  || [ "$?" -eq "${CHECK_IGNORE_NO_MATCH_STATUS}" ])"

if [ -n "${force_added}" ]; then
  blockers+=("Ignored files are staged, which only a forced add can do: $(printf '%s' "${force_added}" | tr '\n' ' ')")
fi

staged_shell="$(staged_matching '\.(sh|bash)$')"
if [ -n "${staged_shell}" ]; then
  if ! command -v shellcheck >/dev/null 2>&1; then
    notes+=("shellcheck is not installed, so staged shell scripts were not linted.")
  elif ! printf '%s\n' "${staged_shell}" | xargs shellcheck -S style >/dev/null 2>&1; then
    blockers+=("shellcheck -S style fails on staged shell scripts. Run it to see the findings.")
  fi
fi

# Code staged after the last test run. Not proof the build passed, but it catches the common case:
# editing after a green run and committing without re-running it.
staged_code="$(staged_matching '(\.java|pom\.xml)$')"
if [ -n "${staged_code}" ]; then
  last_test_run="$(newest_test_report)"

  if [ -z "${last_test_run}" ]; then
    blockers+=("No test reports exist, so mvn -B verify has not run in this working tree.")
  else
    stale=""
    while IFS= read -r path; do
      [ -n "${path}" ] || continue
      [ -e "${path}" ] || continue
      if [ "${path}" -nt "${last_test_run}" ]; then
        stale="${stale} ${path}"
      fi
    done <<EOF
${staged_code}
EOF

    if [ -n "${stale}" ]; then
      blockers+=("Staged code is newer than the last test run:${stale} — re-run mvn -B verify.")
    fi
  fi
fi

# Judgement calls. Reported, never blocking: only a person can say whether a change moved a
# capability, and a gate that guesses gets disabled.
if [ -n "$(staged_matching 'contract-corpus/expected/')" ]; then
  notes+=("Golden corpus files are staged; the commit message must name the approving decision record — docs/runbooks/golden-corpus-update.md.")
fi

if [ -n "${staged_code}" ] && [ -z "$(staged_matching '^(README|ROADMAP|CHANGELOG|SECURITY)\.md$')" ]; then
  notes+=("Code changed and no front-door document did. If this moved a capability, a state word or a floor, sweep them — docs/runbooks/front-door-docs.md.")
fi

notes+=("Not checkable from a script: IDE inspections on every changed file, and whether mvn -B verify actually passed.")

for note in "${notes[@]}"; do
  log_note "${note}"
done

if [ "${#blockers[@]}" -gt 0 ]; then
  for blocker in "${blockers[@]}"; do
    log_error "${blocker}"
  done
  log_error "Branch is not ready to commit."
  exit "${NOT_READY}"
fi

log_note "Mechanical checks passed."
exit 0
