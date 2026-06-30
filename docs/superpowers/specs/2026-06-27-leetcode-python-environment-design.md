# Leetcode Python Environment Design

## Goal

Use the Conda environment named `leetcode` as the single Python runtime for this
repository. This applies to Playwright browser tests, Kafka detection workers,
utility scripts, and tests under `tests_python`. The Spring Boot backend and
Vue/Vite frontend keep their existing Java and Node.js runtimes.

## Approach

Add a repository-level PowerShell runner that executes Python through
`conda run -n leetcode`. Project commands and documentation will call this
runner instead of relying on whichever `python` happens to be active in the
terminal. Existing Windows test launchers will use the same environment name
instead of a machine-specific interpreter path.

VS Code workspace settings will select the installed `leetcode` interpreter on
this development machine. Command-line scripts remain portable because they
refer to the environment name rather than `D:\ruanjian\anaconda3\envs\leetcode`.

## Components

- `scripts/run-python.ps1`: validates that Conda and the `leetcode` environment
  are available, then forwards all Python arguments through `conda run`.
- Python dependency manifest: records Playwright and the existing Kafka worker
  dependencies needed by the repository's Python tooling.
- `.vscode/settings.json`: selects the local `leetcode` Python interpreter while
  preserving the existing Java settings.
- Python and Playwright launchers: remove hard-coded interpreter and Chromium
  executable paths. Playwright resolves its installed browser normally.
- `README.md`: documents environment setup and canonical commands for browser
  tests, Python tests, and the Kafka worker.

## Execution Flow

1. A developer invokes the repository runner with a Python script or module.
2. The runner checks for Conda and verifies that `leetcode` exists.
3. The runner executes `conda run -n leetcode python ...` and returns the same
   process exit code to the caller.
4. Playwright uses the Chromium revision installed for its package version,
   without a repository-specific absolute browser path.

The runner must not silently fall back to the base environment. Missing Conda,
a missing environment, or a failed Python command produces a non-zero exit code
and a concise remediation message.

## Compatibility

The canonical environment remains Python 3.10, matching the installed
`leetcode` interpreter. Java/Maven and Node/npm commands are unchanged. Direct
activation with `conda activate leetcode` remains supported, but documented
project commands use the runner so their behavior is deterministic.

## Verification

- Print `sys.executable` through the runner and confirm it is inside
  `envs\leetcode`.
- Import Playwright and report its installed version.
- Launch and close headless Chromium through Playwright.
- Run the lightweight Python test entry point under `tests_python` where its
  external service requirements permit.
- Confirm the runner propagates a failing Python process exit code.

## Non-Goals

- Replacing Conda with another environment manager.
- Moving Java or Node dependencies into Conda.
- Rebuilding or upgrading unrelated Python components under the vendored
  `mem0` directory.
