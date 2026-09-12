#!/usr/bin/env bash
#
# Fails when a path under a private prefix is tracked at HEAD or was added by any commit in the
# given range. The tree alone is not enough: a file committed and removed within one push leaves a
# clean tip and sits in public history all the same.
#
# Usage:
#   check-private-paths.sh <git-range>   # Guards workflow

# No -E: it propagates the ERR trap into $( ), where the trap's exit overwrites the real
# status and masks grep's exit 2 as a benign no-match. See docs/shell-code-style.md.
set -euo pipefail

# The prefixes .gitignore excludes. A path with unusual characters arrives C-quoted
# (core.quotePath), so an opening double quote is allowed ahead of the prefix.
readonly PRIVATE_PATH_PATTERN='^"?(_doc/|docs/(repo_usage|repository)/)'
readonly GREP_NO_MATCH_STATUS=1

violations=""

log_info() {
  printf '[%s] INFO  %s\n' "$(timestamp)" "$1"
}

log_error() {
  printf '[%s] ERROR %s\n' "$(timestamp)" "$1" >&2
}

timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

usage() {
  log_error "usage: check-private-paths.sh <git-range>"
}

# Fail closed: an unresolvable or empty range makes the history scan produce nothing, which is
# indistinguishable from clean.
require_scannable_range() {
  local range="$1"
  local commits

  if ! commits="$(git rev-list "${range}" 2>/dev/null)"; then
    log_error "cannot resolve range '${range}' — refusing to report clean"
    log_error "check out enough history (fetch-depth: 0) or pass a resolvable range"
    return 1
  fi

  if [[ -z "${commits}" ]]; then
    log_error "range '${range}' contains no commits — refusing to report clean"
    return 1
  fi
}

# grep exits 1 on no match and >=2 on a real failure; only the latter is an error here.
private_paths_among() {
  local candidate_paths="$1"
  local matches
  local grep_status=0

  matches="$(printf '%s\n' "${candidate_paths}" | grep -E "${PRIVATE_PATH_PATTERN}")" \
    || grep_status=$?

  if [[ "${grep_status}" -gt "${GREP_NO_MATCH_STATUS}" ]]; then
    log_error "matching private paths failed (grep exit ${grep_status})"
    return 1
  fi

  if [[ -z "${matches}" ]]; then
    return 0
  fi

  printf '%s\n' "${matches}" | sort -u
}

check_range() {
  local range="$1"
  local tracked_paths
  local added_paths

  if ! require_scannable_range "${range}"; then
    return 1
  fi

  if ! tracked_paths="$(git ls-files)"; then
    log_error "listing the tracked files failed"
    return 1
  fi

  if ! added_paths="$(git log --format='' --name-only --diff-filter=ACR \
      --diff-merges=first-parent "${range}")"; then
    log_error "reading the paths added in '${range}' failed"
    return 1
  fi

  if ! violations="$(private_paths_among "${tracked_paths}"$'\n'"${added_paths}")"; then
    return 1
  fi
}

report_violations() {
  local path

  log_error "private material is tracked at HEAD or was committed in the scanned range:"

  while IFS= read -r path; do
    log_error "  ${path}"
  done <<< "${violations}"

  log_error "these paths are gitignored on purpose and only a forced add puts them in history;"
  log_error "  pushed history cannot be unpublished, so treat the content as disclosed"
}

main() {
  local range="${1:-}"

  if [[ -z "${range}" ]]; then
    usage
    exit 1
  fi

  if ! check_range "${range}"; then
    exit 1
  fi

  if [[ -n "${violations}" ]]; then
    report_violations
    exit 1
  fi

  log_info "private-path guard: clean at HEAD and across '${range}'"
}

if ! main "$@"; then
  exit 1
fi
