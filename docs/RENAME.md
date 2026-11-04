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

## Compatibility break

The rename is a **declared breaking change**. AI Overseer contains no compatibility
layer for an old Warden installation: nothing below is read, migrated, honoured as a
lock or reported as a conflict.

| Old name | What `oseer` does with it |
|---|---|
| `bin/warden`, `bin/warden.cmd` | Removed. There is no `warden` shim; scripts that call it fail. |
| User `~/.warden/` | Not read. `oseer` uses `OSEER_CONFIG_HOME`, otherwise `~/.oseer/`. |
| Project `<repo>/.warden/` | Not read. A project with only `.warden/` has no project configuration. |
| `WARDEN_CONFIG_HOME`, `WARDEN_HOME`, `WARDEN_BROWSER` and every other `WARDEN_*` variable | Ignored. Only the `OSEER_*` names are read. |
| `warden.*` JVM properties | Ignored. Only `oseer.*` properties are read. |
| `.git/warden-execution.json`, `.git/warden-execution.lock` (the old execution fence) | Ignored. The new fence is `oseer-execution.json` / `oseer-execution.lock`. |
| `*.warden-write.lock` sidecars next to configuration files | Ignored. The new sidecar is `*.oseer-write.lock`. |

Consequently an old controller and `oseer` do not exclude each other. An old run that is
still active, crashed or paused in a worktree is invisible to `oseer`, which can start a
second execution in the same checkout. **Before using `oseer`, finish every unfinished
Warden run with the old version, or stop it and clean it up there** (release its worker,
let it settle or abandon it, and remove the leftover `warden-execution.*` marker once no
old process is alive). Stale `*.warden-write.lock` files can then be deleted.

The only historical names that remain readable are listed explicitly: ledger and corpus
identities, historical evidence hashes, and `os-store:warden/<id>` credential references
(see below). They are readable because they describe existing data, not because the old
installation keeps working.

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
   task with `oseer validate <task-id>`. Compare the `verified_profiles` list in
   `oseer doctor` with the old installation: the move itself can drop API profiles from
   it (see [Verification after the move](#verification-after-the-move)). Verify affected
   profiles again before admitting them to paid work. Native verification can call a
   provider; inspect the profile and
   [verification procedure](API-CONNECTIONS.md#configure-and-verify-a-text-profile)
   first.

The project-owned `.oseer/` directory belongs in the target repository; `.oseer/runs/`
remains evidence storage. The user directory contains subscriptions, profiles and
connection references and should not be copied into a project's history.

## External names kept on purpose

Some old names are not AI Overseer names at all: they belong to another tool, to an
account identity, or to a machine path. The rename changed the repository examples but
leaves these in personal configuration untouched, because changing only one side breaks
the link.

- **Codex external profiles.** A Codex profile such as `codex-review.yaml` may pass
  `--profile warden-read` or `--profile warden-write` in `args`. Those names refer to
  `[profiles.warden-read]` / `[profiles.warden-write]` tables in Codex's own
  `~/.codex/config.toml`, not to anything in `~/.oseer/`. Renaming only the argument makes
  Codex start without the intended sandbox and approval settings, or fail. The examples
  under `examples/` now use `oseer-read` / `oseer-write`; existing names keep working.
- **`account: warden-read` / `account: warden-write`.** `account:` names one
  authentication inside a service (see [model settings](MODEL-SETTINGS.md)). Checks,
  readiness and service bindings are grouped by that label, so it is an identity rather
  than branding. Renaming it starts a new account entry with no check history.
- **`C:/ProgramData/warden/gitconfig-sandbox`.** Profiles that set `GIT_CONFIG_GLOBAL` to
  this file point at a machine-wide file outside both the repository and `~/.oseer/`.
  The path is only a location; renaming it in the profile without moving the file makes
  Git in the worker read no sandbox configuration.

To rename them consistently, do it as one change with controllers stopped:

1. Codex: rename the tables in `~/.codex/config.toml` (for example `[profiles.oseer-read]`
   and `[profiles.oseer-write]`, keeping their contents), then change every `--profile`
   argument in `~/.oseer/profiles/*.yaml` that names them. Check with
   `grep -rn -- "warden-" ~/.oseer/profiles ~/.codex/config.toml`.
2. Account labels: change `account:` in **all** profiles that share the label at the same
   time, so they remain one account.
3. Git sandbox file: move or copy the file (for example to
   `C:/ProgramData/oseer/gitconfig-sandbox`) and update every `GIT_CONFIG_GLOBAL` value
   that names it.
4. Run `oseer doctor`, `oseer check --profile <name>` for each changed profile, and
   `oseer profiles --verify <name>` before admitting it to work again. A CLI profile's
   stamp is not invalidated automatically when its arguments change, but the stamp then
   describes a launch configuration that no longer exists.

## Verification after the move

A profile's verification stamp (`verification:` in its YAML) is accepted only while the
fingerprint recorded with it still matches the profile. For API and OpenCode profiles the
fingerprint covers the connection, role, model, model vendor, output limit, schema
enforcement and required artifact fields, the stored
`prompt_template` / `json_schema` references, and a digest of each referenced file. That
digest is computed over the file's **resolved absolute location and its contents**
(relative references are resolved against the user configuration directory). OpenCode
coding profiles additionally include the absolute `harness_executable` path, the
declared wall-clock window and the request limit.

So verification resets when:

- prompt or schema text changes, including a Warden-to-Overseer wording replacement;
- the OpenCode executable moves or is reinstalled at another path;
- the user configuration directory moves, for example `~/.warden/` to `~/.oseer/`,
  even when the references are relative and the files are byte-identical, because the
  resolved location is part of the digest;
- the connection, model, output limit or the other listed inputs change.

The profile is then listed under `unverified_profiles` by `oseer doctor`, and routing no
longer selects it. Restore it with:

```text
oseer profiles --verify <name>
oseer doctor
```

Run verification from a **disposable project**: the probe runs in the current
directory, and a coding profile creates an `oseer-profile-probe-*` fixture there.
Verification runs the profile's own probe and can call a paid provider. It stamps the
profile only when the probe passes; read the transcript path it prints. A Jev decision
profile additionally requires `--confirm --reported-model <exact-model>`.

**A PONG is not verification.** `oseer check` (alias `oseer ping`) asks a profile for a
fresh random marker and reports whether the model answered at that moment. It does not
change the stamp, prove coding tools, or admit the profile to routing. A profile can
answer PONG and still be unverified, and a verified profile can fail a check because its
provider is busy. Admission is decided only by `oseer profiles --verify` and is shown in
`oseer doctor` under `verified_profiles`.

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
