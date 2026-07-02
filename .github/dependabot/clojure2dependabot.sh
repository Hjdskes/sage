#!/usr/bin/env bash
#
# Help Dependabot manage Clojure project dependencies, based on the idea that we can use
# tools.deps to generate a pom.xml for Dependabot to inspect.
#
# This approach is simple (compared to alternatives that require sensitive access tokens
# or "act as" Dependabot), but has the downside that the PRs created by Dependabot are
# not mergable because they modify the generated pom.xml instead of deps.edn. To
# compensate, we have a GitHub Action that fails in Dependabot's PRs until we update
# deps.edn. For a project like this one with few dependencies, this trade of seems worth
# it.
#
# Based on https://github.com/frenchy64/dependabot-clojure-cli-via-mvn.

set -euo pipefail

GIT_TOPLEVEL=$(git rev-parse --show-toplevel)
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
SCRIPT_NAME="$SCRIPT_DIR/$(basename "$0")"
DEPS_EDN="$GIT_TOPLEVEL/deps.edn"
POM_XML="$SCRIPT_DIR/pom.xml"

update() {
  pushd "$GIT_TOPLEVEL" &> /dev/null
  clj -X:deps mvn-pom
  mv pom.xml "$POM_XML"
  popd &> /dev/null
}

check() {
  if git diff --ignore-all-space --exit-code "$POM_XML"; then
    echo "$DEPS_EDN and $POM_XML are in sync."
    exit 0
  else
    echo "$DEPS_EDN and $POM_XML are out of sync!
Please run $SCRIPT_NAME locally and commit the results.
If this is a PR from Dependabot, you must manually update the version(s) in deps.edn."
    exit 1
  fi
}

usage() {
  echo "Usage: $0 update|check|update+check" >&2
  exit 1
}

main() {
  [[ $# -eq 1 ]] || usage

  case "$1" in
    update+check) update; check ;;
    update) update ;;
    check) check ;;
    *) usage ;;
  esac
}

main "$@"
