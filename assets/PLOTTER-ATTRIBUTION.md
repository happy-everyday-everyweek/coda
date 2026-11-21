# Source and attribution

Plotter was extracted from the coss repository into the standalone [pasqualevitiello/plotter](https://github.com/pasqualevitiello/plotter) repository.

Extraction baseline: coss commit `54c5a6dd2527d91127bd5efd0dfe2ee055570f27` on `codex/blueprint-illustration-studies`.

The extraction preserves the Plotter skill package originally located at `apps/ui/skills/plotter`, the illustration catalogue and related components from `apps/ui/app/plotter`, and the associated generation and export scripts from `apps/ui/scripts`.

The skill’s bundled reference manifest records the source revisions of its approved SVG snapshots. Dated feedback and audits from the coss period were removed from the tree after commit `37f3464` and remain in git history.

## License record

Plotter and its portable skill package use the [MIT license](LICENSE), selected by the maintainer on 2026-11-08. The notices retain the coss.com and Origin UI copyright and add Pasquale Vitiello’s copyright for Plotter. The current coss source also uses MIT.

For historical provenance, the initial extraction carried an AGPL-3.0 root license alongside an MIT declaration in `apps/ui/package.json`. That was the extraction record; the current project and packaged skill licenses are MIT.

Cal Sans is credited to Mark Davis, WORDMARK × Cal.com. The skill bundles one copy of the variable font with its SIL Open Font License 1.1 at `skills/plotter/assets/fonts/`. The site’s font package and any bundled third-party assets retain their own accompanying license information. Earlier reference images and review records remain in git history.
