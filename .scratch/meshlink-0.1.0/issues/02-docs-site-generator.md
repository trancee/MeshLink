# Which prose docs site generator fits 0.1.0?

Type: research
Status: resolved

## Question

Is Zensical stable now? Compare it with Starlight and any other credible generator for publishing the Diátaxis prose plus the Dokka API reference on GitHub Pages: maturity, license, toolchain added (Python, Node), Markdown compatibility with the existing docs, search, versioning and maintenance.

## Context

- ADR 0006 decision 8: no site generator until the first release; re-evaluate then (Zensical if stable, otherwise Starlight). Material for MkDocs reaches end-of-life on 2027-05-05.

## Answer

2026-11-13, research on [`research/docs-site-generator`](https://github.com/trancee/MeshLink/blob/research/docs-site-generator/docs/research/2026-11-13-docs-site-generator.md) (commit `862fd76`).

- **Zensical** 0.1.1 is in beta, with stable planned around 2027-02-05. It's MIT-licensed and needs Python 3.11+. It built the unchanged `docs/` in about 2 s; only the 23 links to folders would break on Pages.
- **Starlight** 0.43.0 is still 0.x and needs Node 22. All 76 pages would need a `title` and a move to `src/content/docs/`, and relative `.md` links need a plugin.
- **The others:** Material for MkDocs reaches end of life on 2027-05-05, and MkDocs core has had no release since 2024. mdBook needs a hand-maintained table of contents; Docusaurus and VitePress add Node.
- **Dokka:** every option co-hosts it by copying its output into `api/` in one Pages workflow.
- **No site:** costs nothing and all links keep working, but there's no search or site navigation.
- **Options for ticket 08:** Dokka only until Zensical is stable; Zensical's beta now; Starlight; or mdBook. ADR 0006's rule doesn't decide it, because neither preferred generator is stable.
