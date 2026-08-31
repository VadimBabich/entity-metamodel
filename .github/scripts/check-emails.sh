#!/usr/bin/env bash
#
# Fails when an email address that is not on the allowlist appears in commit authorship, a commit
# message, or added file content. A published POM cannot be edited and pushed history cannot be
# unpublished, so prevention is the only control that works.
#
# Usage:
#   check-emails.sh staged             # pre-commit hook
#   check-emails.sh range <git-range>  # Guards workflow

# No -E: it propagates the ERR trap into $( ), where the trap's exit overwrites the real
# status and masks grep's exit 2 as a benign no-match. See docs/shell-code-style.md.
set -euo pipefail

readonly EMAIL_PATTERN='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z][A-Za-z]+'
readonly GREP_NO_MATCH_STATUS=1

violations=()

log_info() {
  printf '[%s] INFO  %s\n' "$(timestamp)" "$1"
}

log_error() {
  printf '[%s] ERROR %s\n' "$(timestamp)" "$1" >&2
}

timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

trap 'log_error "unexpected failure at line ${LINENO}"; exit 1' ERR

usage() {
  log_error "usage: check-emails.sh staged | range <git-range>"
}

resolve_allowlist_path() {
  local repository_root

  if ! repository_root="$(git rev-parse --show-toplevel)"; then
    log_error "not inside a git repository"
    return 1
  fi

  printf '%s\n' "${repository_root}/.github/allowed-emails.txt"
}

to_lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

is_allowed_address() {
  local candidate
  local entry
  local domain

  candidate="$(to_lowercase "$1")"

  while IFS= read -r entry || [[ -n "${entry}" ]]; do
    case "${entry}" in
      '' | \#*)
        continue
        ;;
    esac

    entry="$(to_lowercase "${entry//[[:space:]]/}")"

    case "${entry}" in
      '*@'*)
        domain="${entry#\*@}"

        if [[ "${candidate}" == *"@${domain}" ]]; then
          return 0
        fi
        ;;
      *)
        if [[ "${candidate}" == "${entry}" ]]; then
          return 0
        fi
        ;;
    esac
  done < "${ALLOWLIST_PATH}"

  return 1
}

record_violation() {
  violations+=("$1")
}

# grep exits 1 on no match and >=2 on a real failure; only the latter is an error here.
extract_addresses() {
  local text="$1"
  local matches
  local grep_status=0

  matches="$(printf '%s\n' "${text}" | grep -oE "${EMAIL_PATTERN}")" || grep_status=$?

  if [[ "${grep_status}" -gt "${GREP_NO_MATCH_STATUS}" ]]; then
    log_error "address extraction failed (grep exit ${grep_status})"
    return 1
  fi

  if [[ -z "${matches}" ]]; then
    return 0
  fi

  printf '%s\n' "${matches}" | sort -u
}

extract_added_lines() {
  local diff_text="$1"
  local added_lines
  local pipeline_status=0

  added_lines="$(printf '%s\n' "${diff_text}" | grep '^+' | grep -v '^+++' | cut -c2-)" \
    || pipeline_status=$?

  if [[ "${pipeline_status}" -gt "${GREP_NO_MATCH_STATUS}" ]]; then
    log_error "reading added lines from the diff failed (exit ${pipeline_status})"
    return 1
  fi

  printf '%s\n' "${added_lines}"
}

# Takes the list as an argument: a while-read at the end of a pipeline runs in a subshell and
# loses every violation it recorded.
scan_addresses() {
  local source_label="$1"
  local address_list="$2"
  local address

  if [[ -z "${address_list}" ]]; then
    return 0
  fi

  while IFS= read -r address; do
    if [[ -z "${address}" ]]; then
      continue
    fi

    if ! is_allowed_address "${address}"; then
      record_violation "${source_label}: ${address}"
    fi
  done <<< "${address_list}"
}

check_staged() {
  local configured_identity
  local diff_text
  local added_lines

  # git config exits 1 when the key is unset; the empty case is handled below.
  configured_identity="$(git config user.email || true)"

  if [[ -z "${configured_identity}" ]]; then
    record_violation "git config user.email is unset — set it before committing"
  elif ! is_allowed_address "${configured_identity}"; then
    record_violation "git config user.email: ${configured_identity}"
  fi

  if ! diff_text="$(git diff --cached -U0)"; then
    log_error "reading the staged diff failed"
    return 1
  fi

  if ! added_lines="$(extract_added_lines "${diff_text}")"; then
    return 1
  fi

  local content_addresses

  if ! content_addresses="$(extract_addresses "${added_lines}")"; then
    return 1
  fi

  scan_addresses "staged content" "${content_addresses}"
}

check_range() {
  local range="$1"
  local commits
  local authorship_lines
  local message_text
  local diff_text
  local added_lines

  # Fail closed: an unresolvable or empty range makes every scan below produce nothing, which
  # is indistinguishable from clean.
  if ! commits="$(git rev-list "${range}" 2>/dev/null)"; then
    log_error "cannot resolve range '${range}' — refusing to report clean"
    log_error "check out enough history (fetch-depth: 0) or pass a resolvable range"
    return 1
  fi

  if [[ -z "${commits}" ]]; then
    log_error "range '${range}' contains no commits — refusing to report clean"
    return 1
  fi

  if ! authorship_lines="$(git log --format='%ae%n%ce' "${range}")"; then
    log_error "reading authorship for '${range}' failed"
    return 1
  fi

  if ! message_text="$(git log --format='%s%n%b' "${range}")"; then
    log_error "reading commit messages for '${range}' failed"
    return 1
  fi

  if ! diff_text="$(git diff "${range}" -U0)"; then
    log_error "reading the diff for '${range}' failed"
    return 1
  fi

  if ! added_lines="$(extract_added_lines "${diff_text}")"; then
    return 1
  fi

  local authorship_addresses
  local message_addresses
  local content_addresses

  authorship_addresses="$(printf '%s\n' "${authorship_lines}" | sort -u)"

  if ! message_addresses="$(extract_addresses "${message_text}")"; then
    return 1
  fi

  if ! content_addresses="$(extract_addresses "${added_lines}")"; then
    return 1
  fi

  scan_addresses "commit authorship" "${authorship_addresses}"
  scan_addresses "commit message" "${message_addresses}"
  scan_addresses "added content" "${content_addresses}"
}

report_violations() {
  local violation

  log_error "email addresses not on the allowlist:"

  for violation in "${violations[@]}"; do
    log_error "  ${violation}"
  done

  log_error "remediation, authorship — set the privacy-preserving form:"
  log_error "  git config user.email '<id>+<user>@users.noreply.github.com'"
  log_error "remediation, content or message — prefer a contact route that is not an address;"
  log_error "  this project routes vulnerability reports through GitHub security advisories."
  log_error "  If an address genuinely must ship, use a role or alias address you control,"
  log_error "  never a personal one, and add it to .github/allowed-emails.txt in the same commit."
}

main() {
  local mode="${1:-}"
  local range="${2:-}"

  if ! ALLOWLIST_PATH="$(resolve_allowlist_path)"; then
    exit 1
  fi
  readonly ALLOWLIST_PATH

  if [[ ! -f "${ALLOWLIST_PATH}" ]]; then
    log_error "allowlist missing at .github/allowed-emails.txt — refusing to pass by default"
    exit 1
  fi

  case "${mode}" in
    staged)
      if ! check_staged; then
        exit 1
      fi
      ;;
    range)
      if [[ -z "${range}" ]]; then
        log_error "mode 'range' needs a git range"
        exit 1
      fi

      if ! check_range "${range}"; then
        exit 1
      fi
      ;;
    *)
      usage
      exit 1
      ;;
  esac

  if [[ "${#violations[@]}" -gt 0 ]]; then
    report_violations
    exit 1
  fi

  log_info "email guard: clean"
}

if ! main "$@"; then
  exit 1
fi
