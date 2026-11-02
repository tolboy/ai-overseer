# Rename to AI Overseer

The project is **AI Overseer**, the repository is `tolboy/ai-overseer`, and the
command is **`oseer`**. Existing installations need an explicit configuration move;
the application does not move personal files automatically.

| Before | Now |
|---|---|
| `warden`, `bin/warden.cmd` | `oseer`, `bin/oseer.cmd` |
| Project `<repo>/.warden/` | `<repo>/.oseer/` |
| User `~/.warden/` | `~/.oseer/` |
| `WARDEN_CONFIG_HOME` | `OSEER_CONFIG_HOME` |
| `WARDEN_HOME` (installation directory) | `OSEER_HOME` |
| Other `WARDEN_*` environment variables | Corresponding `OSEER_*` variables |
| `warden.*` JVM properties | `oseer.*` JVM properties |
| Java package `dev.warden` | `dev.oseer` |
| `WardenTree` | `OverseerTree` |

The installation directory can be renamed to `ai-overseer` separately. Launchers
resolve their installation relative to their own location. Update PATH entries,
shortcuts and external integrations that name the old directory or command.

## Move configuration while everything is stopped

1. Stop the old controller and its workers, close dashboards, and reconcile any
   retained Orca workers. Do not run the old and new versions together in a checkout:
   their configuration paths and execution fences differ.
2. Back up the user configuration and each project's configuration. Move or copy
   the complete user `~/.warden/` directory to `~/.oseer/`, and each project's
   `.warden/` to `.oseer/`, including hidden files. If a destination already exists,
   inspect both directories and resolve the differences explicitly before proceeding.
   Keep `.project-identity` unchanged so the same project retains its corpus identity.
3. Update command and configuration-path references in editable profiles, policies,
   task contracts, shell settings, CI jobs and Orca/Conductor integrations. Replace
   old environment-variable names in the process that launches the tool. Keep the
   installation `OSEER_HOME` separate from the user configuration `OSEER_CONFIG_HOME`.
   An explicit `OSEER_CONFIG_HOME` may point to an existing user configuration
   directory if you prefer to move it later.
4. Preserve historical run evidence, ledger rows, identities, receipts, hashes and
   saved contract snapshots byte for byte. Do **not** perform text replacement in
   `runs/`, raw transcripts or corpus records. Old paths and field names in those
   records describe the original run. Treat pre-rename runs as historical evidence;
   start a fresh run after checking the renamed configuration instead of relying on
   an earlier acceptance to authorize landing a newly named contract.
5. Rebuild, run `oseer doctor`, inspect `oseer connections list`, and validate a
   task with `oseer validate <task-id>`. Path and connection changes can invalidate
   verification fingerprints: verify affected profiles again before admitting them
   to paid work. Native verification can call a provider; inspect the profile and
   [verification procedure](API-CONNECTIONS.md#configure-and-verify-a-text-profile)
   first.

The project-owned `.oseer/` directory belongs in the target repository; `.oseer/runs/`
remains evidence storage. The user directory contains subscriptions, profiles and
connection references and should not be copied into a project's history.

## Existing Windows Credential Manager entries

Imported connections may retain `os-store:warden/<id>`. That exact vault reference
continues to work and remains unchanged when the connection is edited. No secret is
copied, and a missing entry does not fall back to another namespace. New OS-store
connections use `os-store:oseer/<id>`.

Moving an existing connection to the new vault target is optional. If you change its
reference to `os-store:oseer/<id>`, explicitly supply the key again through the
Connections form or `oseer connections auth-set <id>`. Never put it into YAML or a
command argument. Keep the original entry until you have checked the new connection;
changing the reference also requires verification of affected profiles again.
Provider environment references such as `env:OPENROUTER_API_KEY` retain their configured
names; the rename only changes the application's own environment-variable prefix.
