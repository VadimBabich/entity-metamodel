#!/usr/bin/env bash
# Controls for verify-release-wiring.py: both directions plus the fail-closed paths.
#
# The guard is invoked the way release.yml invokes it — by path, not via `python3` — so the
# executable bit and the shebang are exercised here rather than only on release day.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
guard="${repo_root}/.github/scripts/verify-release-wiring.py"

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

failures=0

log_failure() {
  printf 'FAIL  %s\n' "$1" >&2
  failures=$((failures + 1))
}

write_module_pom() {
  local tree="$1"
  local directory="$2"
  local artifact_id="$3"
  local parent_version="$4"
  local own_version="${5:-}"

  mkdir -p "${tree}/${directory}"

  {
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
    printf '  <parent><version>%s</version></parent>\n' "${parent_version}"
    printf '  <artifactId>%s</artifactId>\n' "${artifact_id}"
    if [ -n "${own_version}" ]; then
      printf '  <version>%s</version>\n' "${own_version}"
    fi
    printf '</project>\n'
  } > "${tree}/${directory}/pom.xml"
}

# Each managed entry is given as "groupId:artifactId".
write_bom_pom() {
  local tree="$1"
  local entry
  shift

  mkdir -p "${tree}/entity-metamodel-bom"

  {
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
    printf '  <parent><version>2.0.0</version></parent>\n'
    printf '  <artifactId>entity-metamodel-bom</artifactId>\n'
    printf '  <dependencyManagement><dependencies>\n'
    for entry in "$@"; do
      printf '    <dependency><groupId>%s</groupId><artifactId>%s</artifactId></dependency>\n' \
        "${entry%%:*}" "${entry##*:}"
    done
    printf '  </dependencies></dependencyManagement>\n'
    printf '</project>\n'
  } > "${tree}/entity-metamodel-bom/pom.xml"
}

# The baseline every positive control mutates: stamped parent, BOM matching the reactor.
make_healthy_tree() {
  local name="$1"
  local tree="${workdir}/${name}"

  mkdir -p "${tree}"
  {
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
    printf '  <groupId>io.github.vadimbabich</groupId>\n'
    printf '  <version>2.0.0</version>\n'
    printf '  <modules>\n'
    printf '    <module>entity-metamodel-bom</module>\n'
    printf '    <module>entity-metamodel-core</module>\n'
    printf '    <module>entity-metamodel-runtime</module>\n'
    printf '  </modules>\n'
    printf '</project>\n'
  } > "${tree}/pom.xml"

  write_module_pom "${tree}" entity-metamodel-core entity-metamodel-core 2.0.0
  write_module_pom "${tree}" entity-metamodel-runtime entity-metamodel-runtime 2.0.0

  write_bom_pom "${tree}" \
    io.github.vadimbabich:entity-metamodel-core \
    io.github.vadimbabich:entity-metamodel-runtime

  printf '%s' "${tree}"
}

assert_control() {
  local description="$1"
  local expected_status="$2"
  local expected_output="$3"
  local tree="$4"
  local output
  local status
  shift 4

  set +e
  output="$(cd "${tree}" && "${guard}" "$@" 2>&1)"
  status=$?
  set -e

  if [ "${status}" -ne "${expected_status}" ]; then
    log_failure "${description}: expected exit ${expected_status}, got ${status}"
    printf '      output: %s\n' "${output}" >&2
    return
  fi

  case "${output}" in
    *"${expected_output}"*) printf 'ok    %s\n' "${description}" ;;
    *)
      log_failure "${description}: output did not mention '${expected_output}'"
      printf '      output: %s\n' "${output}" >&2
      ;;
  esac
}

healthy="$(make_healthy_tree healthy)"
assert_control 'healthy reactor passes' 0 'promises only modules this reactor builds' \
  "${healthy}" 2.0.0

# The BOM's own description says the annotations artifact "joins when it lands", so it will at
# some point be listed before the module exists.
premature="$(make_healthy_tree premature)"
write_bom_pom "${premature}" \
  io.github.vadimbabich:entity-metamodel-core \
  io.github.vadimbabich:entity-metamodel-runtime \
  io.github.vadimbabich:entity-metamodel-annotations
assert_control 'BOM naming an artifact no module builds fails' 1 'entity-metamodel-annotations' \
  "${premature}" 2.0.0

third_party="$(make_healthy_tree third_party)"
write_bom_pom "${third_party}" \
  io.github.vadimbabich:entity-metamodel-core \
  io.github.vadimbabich:entity-metamodel-runtime \
  io.projectreactor:reactor-bom
assert_control 'managed third-party artifact is not a broken promise' 0 'promises only modules' \
  "${third_party}" 2.0.0

unstamped="$(make_healthy_tree unstamped)"
sed 's|<version>2.0.0</version>|<version>2.0.0-SNAPSHOT</version>|' "${unstamped}/pom.xml" \
  > "${unstamped}/pom.xml.tmp" && mv "${unstamped}/pom.xml.tmp" "${unstamped}/pom.xml"
assert_control 'unstamped reactor version fails' 1 '2.0.0-SNAPSHOT' "${unstamped}" 2.0.0

assert_control 'missing arguments fail closed' 2 'usage:' "${healthy}"

no_group="$(make_healthy_tree no_group)"
sed '/<groupId>/d' "${no_group}/pom.xml" > "${no_group}/pom.xml.tmp" \
  && mv "${no_group}/pom.xml.tmp" "${no_group}/pom.xml"
assert_control 'reactor without a groupId fails closed' 1 'declares no groupId' "${no_group}" 2.0.0

empty="${workdir}/empty"
mkdir -p "${empty}"
assert_control 'missing root POM fails closed' 1 'not a reactor checkout' "${empty}" 2.0.0

# Maven lets a module's directory name and its artifactId diverge, so comparing directory names
# would pass while nothing produces the coordinate the BOM promises.
diverged="$(make_healthy_tree diverged)"
write_module_pom "${diverged}" entity-metamodel-annotations \
  entity-metamodel-annotations-jakarta 2.0.0
sed 's|<module>entity-metamodel-runtime</module>|<module>entity-metamodel-runtime</module><module>entity-metamodel-annotations</module>|' \
  "${diverged}/pom.xml" > "${diverged}/pom.xml.tmp" && mv "${diverged}/pom.xml.tmp" "${diverged}/pom.xml"
write_bom_pom "${diverged}" \
  io.github.vadimbabich:entity-metamodel-core \
  io.github.vadimbabich:entity-metamodel-runtime \
  io.github.vadimbabich:entity-metamodel-annotations
assert_control 'BOM matching a directory name but no artifactId fails' 1 \
  'entity-metamodel-annotations' "${diverged}" 2.0.0

stale_parent="$(make_healthy_tree stale_parent)"
write_module_pom "${stale_parent}" entity-metamodel-core entity-metamodel-core 1.9.9
assert_control 'module left on a stale parent version fails' 1 'entity-metamodel-core' \
  "${stale_parent}" 2.0.0

# versions:set stamps a child's parent reference but leaves a divergent own version alone.
own_version="$(make_healthy_tree own_version)"
write_module_pom "${own_version}" entity-metamodel-core entity-metamodel-core 2.0.0 1.9.9
assert_control 'module declaring its own version fails' 1 '1.9.9' "${own_version}" 2.0.0

# The deploy step asserts the BOM manages every module it publishes.
unmanaged="$(make_healthy_tree unmanaged)"
write_module_pom "${unmanaged}" entity-metamodel-processor entity-metamodel-processor 2.0.0
sed 's|<module>entity-metamodel-runtime</module>|<module>entity-metamodel-runtime</module><module>entity-metamodel-processor</module>|' \
  "${unmanaged}/pom.xml" > "${unmanaged}/pom.xml.tmp" && mv "${unmanaged}/pom.xml.tmp" "${unmanaged}/pom.xml"
assert_control 'module the BOM does not manage fails' 1 'entity-metamodel-processor' \
  "${unmanaged}" 2.0.0

# GitHub does not read workflow commands from stderr, so diagnostics moved there would fail the
# step without annotating it.
set +e
annotation="$(cd "${premature}" && "${guard}" 2.0.0 2>/dev/null)"
set -e
case "${annotation}" in
  '::error::'*) printf 'ok    %s\n' 'failure annotation is written to stdout' ;;
  *) log_failure 'failure annotation is not on stdout' ;;
esac

# A module entry the parser cannot read escapes every other check at once.
unreadable="$(make_healthy_tree unreadable)"
mkdir -p "${unreadable}/entity-metamodel-annotations"
printf '<project><artifactId>entity-metamodel-annotations</artifactId></project>\n' \
  > "${unreadable}/entity-metamodel-annotations/pom.xml"
sed 's|<module>entity-metamodel-runtime</module>|<module>entity-metamodel-runtime</module><module>entity-metamodel-annotations</module>|' \
  "${unreadable}/pom.xml" > "${unreadable}/pom.xml.tmp" && mv "${unreadable}/pom.xml.tmp" "${unreadable}/pom.xml"
assert_control 'module whose POM cannot be read fails' 1 'could not be read' "${unreadable}" 2.0.0

if [ "${failures}" -ne 0 ]; then
  printf '\n%d control(s) failed\n' "${failures}" >&2
  exit 1
fi

printf '\nall controls passed\n'
