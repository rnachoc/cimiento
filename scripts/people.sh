#!/usr/bin/env bash
# Sourced by demo.sh, e2e.sh and flow.sh. Prints three fictional people (one per role) as shell
# assignments: a random personal token each, and CIMIENTO_PEOPLE with only the SHA-256 of every
# token, which is what the core receives. The tokens stay in the caller's git-ignored state file.
people_env() {
  local tok_g tok_e tok_o
  tok_g="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  tok_e="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  tok_o="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
  printf 'GERENCIA_TOKEN=%s\nESTUDIOS_TOKEN=%s\nOBRA_TOKEN=%s\n' "$tok_g" "$tok_e" "$tok_o"
  printf "CIMIENTO_PEOPLE='[%s,%s,%s]'\n" \
    "{\"username\":\"gerencia\",\"displayName\":\"Marta Ibáñez\",\"role\":\"MANAGEMENT\",\"tokenHash\":\"$(sha "$tok_g")\"}" \
    "{\"username\":\"estudios\",\"displayName\":\"Luis Ferrer\",\"role\":\"ESTIMATING\",\"tokenHash\":\"$(sha "$tok_e")\"}" \
    "{\"username\":\"obra\",\"displayName\":\"Ana Ríos\",\"role\":\"SITE_MANAGER\",\"tokenHash\":\"$(sha "$tok_o")\"}"
}
