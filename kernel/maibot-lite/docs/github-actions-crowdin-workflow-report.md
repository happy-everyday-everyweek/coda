# GitHub-Side Localization Workflow Report

## Scope

This document defines the repository-side localization workflow for Option B.
It focuses on GitHub Actions, branch conventions, pull requests, and validation gates around Crowdin.

This is intentionally repository-specific operational guidance, not a generic Crowdin or GitHub Actions tutorial.

External prerequisite:

- the Crowdin project and GitHub Actions secrets must already be configured
- if Crowdin's native GitHub integration exists on the Crowdin side, it must not be used as a second write-back path for this repository

## Repository Policy

- `zh-CN` is the only source language of truth in the repository
- target-locale files committed in the repository are synchronization artifacts and reviewable outputs, not the normal long-term editing surface
- existing committed target translations must be bootstrapped into Crowdin once before steady-state sync is trusted
- after bootstrap, Crowdin is the normal editing surface for target translations
- GitHub Actions is the only allowed GitHub-side synchronization mechanism between this repository and Crowdin
- translations return through `l10n_*` pull requests, not direct pushes into `main` or `r-dev`

## Branch Model

- source branches covered by the localization workflow:
  - `main`
  - `r-dev`
- Crowdin return branches:
  - `l10n_main`
  - `l10n_r-dev`
- merge strategy:
  - translations do not go directly into `main` or `r-dev`
  - translations are reviewed through pull requests before merge

## Source of Truth and Trigger Surface

- source locale for JSON translations: `locales/zh-CN/*.json`
- source locale for prompt templates: `prompts/zh-CN/**/*.prompt`
- source locale for dashboard WebUI translations: `dashboard/src/i18n/locales/zh.json`
- current prompt template extension in the repository: `.prompt`
- dashboard WebUI keeps short runtime locale filenames (`zh`, `en`, `ja`, `ko`) in Git, but `dashboard/src/i18n/locales/zh.json` is still the repository-side `zh-CN` source asset for that file group

Normal push-triggered source uploads remain strictly source-driven.
Translated target assets are not part of the steady-state upload trigger set.

## Workflows Involved

### 1. `crowdin-bootstrap.yml`

Role:

- provides a manual bootstrap path for existing committed target translations
- seeds Crowdin from the repository's current target-locale state
- keeps this exceptional upload path separate from normal source-driven sync

Triggers:

- manual dispatch only

Visibility requirement:

- because this workflow uses `workflow_dispatch`, GitHub only exposes it after the workflow file exists on the repository default branch
- in this repository, maintainers should merge the workflow file into `main` before expecting it to appear in the Actions UI or be runnable through `gh workflow run`

Inputs:

- `base_branch`: `main` or `r-dev`
- `confirm_bootstrap`: explicit confirmation string

Behavior:

- checks out the selected repository branch
- uploads sources and committed target translations to Crowdin
- does not download translations
- does not create or update `l10n_*` pull requests

Guardrail:

- this workflow is intentionally one-time or exceptional
- maintainers must not treat it as a continuous GitHub-to-Crowdin target-translation sync path

### 2. `crowdin-sync.yml`

Role:

- uploads source-language assets to Crowdin
- downloads currently available translations from Crowdin when the workflow runs
- creates or updates localization pull requests back to the matching base branch

Triggers:

- manual dispatch
- scheduled sync every 6 hours: `17 */6 * * *` UTC
- push to `main` or `r-dev` when one of these paths changes:
  - `crowdin.yml`
  - `locales/zh-CN/*.json`
  - `prompts/zh-CN/**/*.prompt`
  - `dashboard/src/i18n/locales/zh.json`

Branch behavior:

- push-triggered runs sync the current Git branch and use a matching localization branch name:
  - `main -> l10n_main -> PR into main`
  - `r-dev -> l10n_r-dev -> PR into r-dev`
- scheduled runs explicitly cover both `main` and `r-dev`

Permissions and credentials:

- `contents: write`
- `pull-requests: write`
- `GITHUB_TOKEN`
- `CROWDIN_PROJECT_ID`
- `CROWDIN_PERSONAL_TOKEN`

Important boundary:

- the steady-state workflow keeps the PR-based return flow intact
- normal runs do not upload direct GitHub edits to target-locale files back into Crowdin

### 3. `i18n-validate.yml`

Role:

- runs repository-side localization validation
- blocks structurally invalid or policy-breaking localization changes

Triggers:

- pull requests that touch:
  - `locales/**/*.json`
  - `prompts/**/*.prompt`
  - `dashboard/src/i18n/index.ts`
  - `dashboard/src/i18n/locales/*.json`
  - `scripts/i18n_validate.py`
  - `src/common/i18n/**/*.py`
  - `src/common/prompt_i18n.py`
  - `src/prompt/prompt_manager.py`
- pushes to `main` or `r-dev` for the same path set

Validation scope:

- JSON locale key alignment against `zh-CN`
- dashboard nested JSON locale key alignment against `dashboard/src/i18n/locales/zh.json`
- placeholder consistency
- dashboard i18next interpolation placeholder consistency
- plural structure consistency
- prompt placeholder consistency
- English locale protection against Chinese source-language leakage
- rejection of non-`zh-CN` entries that directly preserve Chinese source text

Prompt behavior note:

- missing target prompt files currently produce warnings, not hard failures
- runtime still falls back to `zh-CN` prompt templates when localized prompt files are absent

### 4. `precheck.yml`

Role:

- checks whether a pull request conflicts with its real target branch
- preserves the existing conflict-label behavior

Behavior:

- checks out the PR head commit
- fetches the actual PR base branch from `github.event.pull_request.base.ref`
- performs a merge simulation against that real base branch
- marks the PR as conflicted only if the merge simulation produces unmerged files

This means:

- feature branches into `main` are checked against `main`
- feature branches into `r-dev` are checked against `r-dev`
- `l10n_main` PRs are checked against `main`
- `l10n_r-dev` PRs are checked against `r-dev`

### 5. `ruff-pr.yml`

Role:

- runs Ruff lint and format checks for pull requests that are relevant to Python code quality

Effect:

- translation-only localization pull requests do not run Ruff by default
- Python or Ruff-related pull requests still run the existing Ruff checks

## End-to-End GitHub Flow

### A. One-time bootstrap of existing target translations

1. A maintainer chooses `main` or `r-dev` as the branch whose committed target translations should seed Crowdin.
2. The maintainer manually runs `crowdin-bootstrap.yml` with explicit confirmation.
3. The workflow uploads the selected branch's current sources and committed target translations to Crowdin.
4. No `l10n_*` pull request is created by this bootstrap workflow.
5. After bootstrap, target-language maintenance should move to Crowdin as the normal editing surface.

### B. Normal source-language update on `main` or `r-dev`

1. A source-language change is pushed to `main` or `r-dev`.
2. `crowdin-sync.yml` uploads source-language assets to Crowdin, including the dashboard WebUI source file `dashboard/src/i18n/locales/zh.json`.
3. The same workflow may also download any translations currently available in Crowdin when that workflow run executes.
4. A localization pull request is opened or updated:
   - `l10n_main -> main`
   - `l10n_r-dev -> r-dev`

### C. Translation return flow

1. Translators work in Crowdin.
2. Repository updates do not appear in `main` or `r-dev` immediately at approval time.
3. Repository write-back happens when `crowdin-sync.yml` runs.
4. GitHub updates or creates `l10n_${branch}` pull requests.
5. Maintainers review and merge the localization pull request in the normal PR flow.

### D. Scheduled sync

1. Every 6 hours, GitHub Actions runs a scheduled localization sync.
2. The workflow explicitly processes both `main` and `r-dev`.
3. If Crowdin currently has downloadable translation updates, GitHub updates or creates the corresponding `l10n_` pull requests.

## How the Setup Avoids Sync Loops

- source uploads are triggered only from:
  - `crowdin.yml`
  - `locales/zh-CN/*.json`
  - `prompts/zh-CN/**/*.prompt`
  - `dashboard/src/i18n/locales/zh.json`
- translated target files do not trigger another steady-state upload cycle
- the bootstrap path is manual and confirmation-gated
- translations return through `l10n_` branches and PRs instead of direct pushes to base branches
- translation-only PRs do not trigger Ruff, which reduces unnecessary CI noise without weakening Python quality gates

## GitHub-Usable Maintainer Operations

### Trigger the bootstrap path

GitHub UI:

- Actions -> `Crowdin Bootstrap Target Translations`
- if it does not appear yet, first make sure the workflow file has already landed on the default branch (`main`)
- choose `main` or `r-dev`
- set `confirm_bootstrap` to `yes-bootstrap-current-target-translations`

GitHub CLI:

```bash
gh workflow run crowdin-bootstrap.yml \
  --ref main \
  -f base_branch=r-dev \
  -f confirm_bootstrap=yes-bootstrap-current-target-translations
```

Use this only when seeding Crowdin from already-committed target translations, or in another exceptional recovery scenario.

### Trigger a normal manual sync

GitHub UI:

- Actions -> `Crowdin Sync`
- if a newly added manual workflow does not appear, confirm that workflow file is already on the default branch
- run the workflow on `main` or `r-dev`

GitHub CLI:

```bash
gh workflow run crowdin-sync.yml --ref main
```

### Inspect workflow runs

```bash
gh run list --workflow crowdin-sync.yml --limit 5
gh run list --workflow crowdin-bootstrap.yml --limit 5
```

### Inspect resulting localization pull requests

```bash
gh pr list --head l10n_main
gh pr list --head l10n_r-dev
```

### Verify that GitHub Actions is the repository write-back path

- confirm there is a successful `crowdin-sync.yml` run corresponding to the latest `l10n_*` PR update
- confirm translated content returned through `l10n_main` or `l10n_r-dev`, not a direct push into `main` or `r-dev`
- do not rely on a separate Crowdin native GitHub integration PR or branch flow for this repository

## Guardrails That Remain Intact

- localization PRs are still checked against their real base branch
- repository-side localization validation still runs where expected
- translation-only PRs still avoid unnecessary Ruff noise by default
- Python-impacting PRs still run Python quality gates where appropriate
- the steady-state `zh-CN` source-trigger model remains unchanged

## Bottom Line

The GitHub-side localization workflow now supports the intended Option B model:

- `zh-CN` remains the only repository source language
- existing committed target translations can be bootstrapped into Crowdin once through a manual workflow
- steady-state sync remains source-driven and GitHub Actions-only
- translated content still returns through `l10n_${branch}` pull requests
- existing PR validation and reduced-noise translation PR behavior remain intact
