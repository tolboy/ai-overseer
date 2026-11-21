# First run with AI Overseer

You need Java 21 or newer and Git on PATH. The portable archive includes the Overseer
JAR, launchers and browser-check script. Open the archive's `ai-overseer` folder after
extracting it. The launcher can run from anywhere.

```text
sh path/to/ai-overseer/bin/oseer --version
path\to\ai-overseer\bin\oseer.cmd --version
```

Use the launcher for your system wherever the steps below say `oseer`.
From the root of a Git project, check the detected commands and scope:

```text
oseer init --scan
```

If the project does not have `.oseer/project.yaml`, run `oseer init` after reviewing
the scan. If there is no check to detect, add one to the project or write the project
configuration from the setup guide. Review the generated checks before using them.
`oseer setup` writes your operator configuration. `oseer doctor` reports project, profile and
connection readiness. Install and verify the vendor profiles you intend to dispatch
using the [setup guide](https://github.com/tolboy/ai-overseer/blob/main/docs/SETUP.md).

```text
oseer setup
oseer doctor
oseer dashboard --project .
```

1. Open the loopback URL printed by the dashboard. Keep that terminal open.
2. In **Launch**, check the project directory and enter a goal. For a first run,
   leave planner preparation off and select **Draft only** if you want to inspect
   the task contract before using any vendor.
3. Click **Preview plan and estimate**. Confirm the draft plan and click **Start**.
   The job ends at `drafted` and gives the path to the task contract.
4. Review that contract, then point **Launch** at its worktree and pick the existing
   task contract. Check the cast, stages and call budget in the new preview.
   Confirm it before clicking **Start**. The job offers the review decision when
   the result is ready.

For browser checks on the project itself, install Node and Chrome or Edge. This is
separate from opening the Overseer dashboard. See the
[walkthrough](https://github.com/tolboy/ai-overseer/blob/main/docs/WALKTHROUGH.md)
for a full run.
