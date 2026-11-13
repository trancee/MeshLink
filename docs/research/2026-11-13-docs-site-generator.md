# Prose docs site generator for 0.1.0, 2026-11-13

Status: research findings only. This note does not record a decision. Every tool it names still needs an E3 rationale in the [dependency register](../reference/dependencies.md) and user approval.

- **Ticket:** `.scratch/meshlink-0.1.0/issues/02-docs-site-generator.md`; the decision belongs to ticket 08.
- **Builds on:** [ADR 0006](../adr/0006-documentation-architecture.md), decision 8: Dokka 2.2.0 HTML on GitHub Pages, no prose site until the first release, then "Zensical if it is stable, otherwise Starlight".
- **Every URL below was accessed on 2026-11-13.**

## Question

Is Zensical stable now? How does it compare with Starlight and other credible generators for publishing the Diátaxis prose (`docs/`) next to the Dokka HTML reference on one GitHub Pages site? The comparison covers maturity, license, the toolchain each adds, compatibility with the existing GitHub-flavored Markdown and its relative links (checked by lychee), search, versioning and maintenance. It also asks what publishing no prose site for 0.1.0 costs.

## Method and sources

Versions and dates come from the package registries (PyPI JSON API, npm registry), GitHub Releases (`gh release list/view`) and the projects' own documentation, read either on the published site or as source in the project repository. Secondary write-ups were not used.

In addition, I ran one trial build. Zensical 0.1.1 was installed into a throwaway virtual environment inside a scratch worktree (Python 3.15.2, macOS). It built the unchanged `docs/` tree with a five-line `mkdocs.yml` (`docs_dir: docs`, `tables`, `toc`). The environment and output were deleted afterwards. A script also compared GitHub's heading slugs with Python-Markdown's default `toc` slugs for every fragment link in `docs/`. No Node-based generator was built; their findings are from documentation only.

Repository facts used below (measured on `main` at `7b10537`):

- 76 Markdown files under `docs/`, none with front matter; `docs/README.md` is the docs entry page.
- Relative links in `docs/`: 213 to Markdown files, 85 more with a `#fragment`, 54 same-page anchors, 12 to `.json` and 5 to `.csv` files, 23 to directories, and 8 that leave `docs/` (`GLOSSARY.md` 3, `AGENTS.md` 2, `CONSTITUTION.md`, `KOTLIN.md`, `SECURITY.md` 1 each).
- None of the 23 directory targets (`adr/`, `agents/`, `research/`, `spec/data/`, evidence `raw/` and `summary/` folders) has a `README.md` or `index.md` today. `docs/adr/README.md` is generated later (ADR 0006, decision 9).
- `docs/` is 21 MB, almost all of it `docs/evidence/` (1,322 `.gz`, 293 `.json`, 94 `.csv` files).

## Version and status table

| Tool | Latest release (date) | Declared status | License | Toolchain added | Search | Versioning |
|---|---|---|---|---|---|---|
| Zensical | 0.1.1 (2026-11-10) | Beta since 0.1.0 (2026-11-05), "stable after those three months" if all goes as expected; PyPI classifier "4 - Beta" | MIT | Python ≥ 3.11, prebuilt `abi3` wheels (no Rust toolchain); `pip install zensical` adds 9 direct dependencies, including `zensical-ui` | Built in, new client-side engine, works offline; interface English only | Transitional: a `mike` fork installed from Git (`squidfunk/mike`, based on mike 2.2.0, "no new features"); native versioning on the roadmap |
| Material for MkDocs | 9.8.1 (2026-11-07) | End of life 2027-05-05; only critical and security fixes until then; PyPI classifier "7 - Inactive" | MIT | Python ≥ 3.11 plus MkDocs | Built in (Lunr) | `mike` |
| MkDocs (core) | 1.6.1 (2024-08-30) | No release for 26 months; repository last pushed 2025-10-20 | BSD-2-Clause | Python ≥ 3.8 | Built-in plugin | `mike` (third party) |
| Starlight (Astro) | 0.43.0 (2026-11-13, a breaking minor) | 0.x; actively developed | MIT | Node ≥ 22.23.4 (Astro 7.4.12), npm lockfile, about 27 direct dependencies of Starlight alone | Built in (Pagefind) | Community plugin `starlight-versions` 0.10.2 |
| Docusaurus | 3.10.2 (2026-07-10); 4.0 canary | Stable 3.x | MIT | Node ≥ 20 | Not built in: Algolia DocSearch (official) or community local search | Built in |
| VitePress | 1.6.4 (2025-08-05); 2.0.0-alpha.21 on `next` | Stable line not released for 15 months; 2.0 in alpha | MIT | Node | Not checked | Not checked |
| mdBook | 0.5.4 (2026-07-06) | 0.x, maintained by the Rust project | MPL-2.0 | One Rust binary (prebuilt releases) | Built in | None |

The GitHub Actions `ubuntu-24.04` image ships Python 3.12.3 and Node.js 22.23.4, so both Zensical (≥ 3.11) and Astro 7 (≥ 22.23.4) run on the image's own interpreter. Astro's minimum equals the image's Node version exactly, so the next Astro minimum bump needs `actions/setup-node`.

## Findings

### 1. Zensical

- **Stability.** Zensical is not declared stable. The 0.1.0 release notes say: "With 0.1.0, Zensical enters beta for the next three months … If all goes as expected, we plan to move to stable after those three months." That points to about 2027-02-05, about three months before Material for MkDocs reaches end of life (2027-05-05). The date is a plan, not a commitment. The project ships often: 0.0.69 to 0.1.1 took eight releases in five weeks.
- **License and maintenance.** MIT, from the Material for MkDocs team (Zensical LLC). The repository was pushed today. The Material for MkDocs README names Zensical as its "maintained successor". The paid offerings (Zensical Studio, Zensical Spark) are optional; the 0.1.0 notes state that "Zensical is and will always be free". The Module API for custom extensions goes to Spark members for early access first, so MeshLink could not write a custom plugin on the free tier yet. MeshLink needs none today.
- **Compatibility.** Zensical reads `mkdocs.yml` unchanged; the only unsupported setting is `hooks`. It does not run arbitrary MkDocs plugins; it ships native rewrites of the popular ones, such as `search`, `offline`, `redirects`, `gh-admonitions` (renders GitHub `> [!NOTE]` alerts), `llmstxt`, `tags` and `mike`. `docs_dir` cannot be `.`, so the prose must stay in a subdirectory such as `docs/`. `README.md` counts as a section index page.
- **Link checking.** Zensical validates internal links and their anchors at build time by default; `--strict` turns warnings into a failed build.
- **Trial build.** The unchanged `docs/` tree built in about 2 seconds into 85 HTML pages and a 28 MB site, which includes the copied evidence files. There were 8 warnings, all "page does not exist", and they were exactly the 8 links that leave `docs/`. There were no anchor warnings. The slug comparison agrees: for the 78 fragment links the script could resolve, GitHub's slug and Python-Markdown's default slug are identical. The 23 directory links produced no warning. In the output they point to directories without an `index.html`, so they would return 404 on GitHub Pages. Linked `.json` and `.csv` files were copied and work.
- **GitHub Pages.** The documented workflow uses `actions/configure-pages`, `actions/setup-python`, `pip install zensical`, `zensical build --clean`, `actions/upload-pages-artifact` and `actions/deploy-pages`. There is no `gh-deploy` command. The docs advise against CI caches for now.

### 2. Starlight

- **Stability and maintenance.** Version 0.43.0 was released today. It is a minor release with a breaking change (scoped-style strategy), which is what 0.x minors do. MIT; the Astro team maintains it; the repository was pushed today.
- **Toolchain.** Node ≥ 22.23.4 plus npm, a `package.json` and a lockfile. Everything in the dependency tree is new to MeshLink, which has no Node toolchain.
- **Markdown compatibility.** Astro supports GFM by default. Three gaps affect the existing docs:
  - Starlight requires a `title` in the front matter of every page. None of the 76 files has front matter, and Starlight renders `title` as the page's `<h1>`, so the existing `# Heading` lines would duplicate it.
  - `docsLoader()` loads only from `src/content/docs/`, and its documented options do not include a different base directory. The prose would have to move, or be copied in at build time.
  - Starlight's authoring guide shows site-absolute links (`/getting-started/`). Neither the Starlight nor the Astro guide documents rewriting relative `*.md` links. The community rehype plugin `astro-rehype-relative-markdown-links` (0.19.2) exists for this; `starlight-links-validator` (0.27.0) checks links.
- **Search and versioning.** Pagefind search is built in. Versioning needs the community `starlight-versions` plugin.
- **GitHub Pages.** The official `withastro/action` workflow; a project site needs `base: '/MeshLink'`, after which "all of your internal page links must be prefixed with your base value".

### 3. Other options

- **Material for MkDocs until its end of life:** builds like Zensical but is inactive and reaches end of life on 2027-05-05, possibly within months of 0.1.0. It loses because Zensical reads the same configuration.
- **MkDocs core:** unmaintained (last release 2024-08-30). It loses.
- **Docusaurus 3.10.2:** stable, with built-in versioning and relative file links converted to URLs. It parses `.md` as MDX by default (`markdown.format: 'mdx'`), so `<` and `{` in prose need `format: 'detect'`. Search needs Algolia or a plugin, and it adds Node. It loses on toolchain and setup weight for a small site.
- **VitePress:** relative links with `.md` work. The stable line has not had a release since 2025-08-05, and 2.0 is in alpha. It loses on maintenance signal.
- **mdBook 0.5.4:** one binary (MPL-2.0) with built-in search and GitHub-style `> [!NOTE]` admonitions. It rewrites relative `.md` links to `.html` and `README.md` to `index.html`, so links written for GitHub keep working. It needs a hand-maintained `SUMMARY.md` listing every page and has no versioning. It is a credible fallback but not one of ADR 0006's named candidates.
- **Dokka only, prose on GitHub (status quo):** zero new tools. GitHub renders the Markdown, and the relative links and anchors are already checked by lychee. The weaknesses are search and navigation (see §5).

### 4. Co-hosting Dokka and whether links keep working

A repository has one GitHub Pages project site (`https://trancee.github.io/MeshLink/`). Every option co-hosts the same way. One Pages workflow runs `./gradlew dokkaGenerate` and the site build, copies Dokka's HTML output into a subdirectory such as `api/` of the site output, and deploys the combined folder with `actions/upload-pages-artifact` and `actions/deploy-pages`. Starlight (`public/`) and Docusaurus (`static/`) can instead take the Dokka output as verbatim static files. Published sites may be up to 1 GB; the trial site was 28 MB before Dokka. Dokka 2.2.0 HTML also has its own versioning plugin.

Prose cannot link to the API pages with a relative link that also works on GitHub, because the Dokka output is not in the repository. Such links must be absolute Pages URLs, which only lychee's weekly external run checks.

| Generator | In-`docs/` `.md` links and anchors | 8 links leaving `docs/` | 23 directory links | lychee config |
|---|---|---|---|---|
| Zensical, Material, MkDocs | Unchanged; trial build: 0 anchor warnings | Break (build warns) | 404 (no warning) | Unchanged; exclude the build output folder |
| Starlight | Need moving into `src/content/docs/`, front matter and a rehype plugin | Break | 404 | Paths change with the move |
| Docusaurus | Converted within the docs plugin; anchors not verified | Break | 404 | Unchanged |
| mdBook | Converted (`.md` to `.html`, `README.md` to `index.html`) | Break | 404 | Unchanged |
| No site | Unchanged (GitHub renders) | Work | Work (GitHub lists directories) | Unchanged |

The fixes for any site are the same: rewrite the 8 links that leave `docs/` to absolute GitHub URLs, or keep those files out of the site. For the 23 directory links, add index pages or point them to GitHub tree URLs. Lychee keeps checking the Markdown sources, so its configuration needs only an `exclude_path` for the build output.

### 5. No prose site versus a site

**No site for 0.1.0.**

- Costs nothing new: no tool, no E3 row, no workflow job, no link changes. Every link and anchor already works and is gated.
- Pages hosts only the Dokka reference, which needs a Pages workflow anyway.
- Downsides: no site search (GitHub code search needs a signed-in account and indexes only the default branch), no sidebar navigation beyond `docs/README.md`, and no single home for the prose and the API.
- Two decision-8 artifacts remain to be decided: `llms.txt` and per-page Markdown exports.

**A site.**

- Adds one toolchain (Python packages or Node), pinned versions, a Pages job and E3 rows.
- Needs the link fixes in §4 and ongoing upgrades. Starlight 0.x and Zensical's beta both still ship breaking or frequent changes.
- Gains search, navigation, offline use and one entry point next to the API reference.
- Zensical can generate `llms.txt` with its `llmstxt` plugin.

## Recommendation for the decision ticket

These options are for ticket 08 to decide. ADR 0006's rule ("Zensical if it is stable, otherwise Starlight") does not settle the question by itself, because neither candidate is stable: Zensical calls itself beta and Starlight is 0.x.

1. **Dokka-only for 0.1.0, revisit when Zensical declares stable.** This is the lowest cost and keeps ADR 0006 as written. The trigger is Zensical's stable announcement (planned for about 2027-02-05). The risk is that users get no prose search at 0.1.0.
2. **Zensical 0.1.x now, pinned exactly.** It needs no move and no front matter, and the trial shows links and anchors already resolve; only 8 outside links and 23 directory links need changes. Adoption would require amending decision 8, because beta is not stable, and accepting churn until the stable release.
3. **Starlight now.** This follows decision 8's fallback literally. It costs a Node toolchain, moving 76 files, adding front matter to each and a relative-link plugin, and the links then no longer work unchanged on GitHub.
4. **mdBook now.** It has a single binary and GitHub-compatible links, but needs a maintained `SUMMARY.md` and offers no versioning. Choosing it needs an ADR amendment because it is not a named candidate.

Docusaurus, VitePress, MkDocs core and Material for MkDocs are not recommended, for the reasons in §3.

## Open questions and limitations

- The planned 0.1.0 release date was not available to this note, so it does not say whether Zensical's planned stable release lands before or after 0.1.0.
- Starlight, Docusaurus, VitePress and mdBook were not test-built. Their link behavior comes from their documentation only.
- VitePress search and versioning were not checked.
- The trial used Python-Markdown defaults. Theme, navigation and the Dokka merge were not built.
- A Zensical stable date of about 2027-02-05 is the project's stated plan, not a commitment.

## Sources

Zensical:

- 0.1.0 release notes (beta, three months): <https://github.com/zensical/zensical/releases/tag/v0.1.0>
- 0.1.1 release notes: <https://github.com/zensical/zensical/releases/tag/v0.1.1>
- PyPI metadata (version, license, Python, classifiers, wheels): <https://pypi.org/pypi/zensical/json>
- MkDocs compatibility: <https://zensical.org/docs/compatibility/mkdocs/>
- Migration and unsupported settings: <https://zensical.org/docs/compatibility/mkdocs/migration/>
- Supported plugins: <https://zensical.org/docs/compatibility/mkdocs/plugins/>
- Versioning with mike: <https://zensical.org/docs/compatibility/mkdocs/mike/>
- Search: <https://zensical.org/docs/setup/search/>
- Validation: <https://zensical.org/docs/setup/validation/>
- Basics (`docs_dir`): <https://zensical.org/docs/setup/basics/>
- Navigation (`README.md` index): <https://zensical.org/docs/setup/navigation/>
- Publishing: <https://zensical.org/docs/publish-your-site/>
- Roadmap: <https://zensical.org/roadmap/>

Material for MkDocs and MkDocs:

- End-of-life notice: <https://github.com/squidfunk/mkdocs-material#readme>
- 9.8.1 release: <https://github.com/squidfunk/mkdocs-material/releases/tag/9.8.1>
- PyPI metadata: <https://pypi.org/pypi/mkdocs-material/json>
- MkDocs 1.6.1 release: <https://github.com/mkdocs/mkdocs/releases/tag/1.6.1>

Starlight and Astro:

- Starlight 0.43.0 release: <https://github.com/withastro/starlight/releases/tag/%40astrojs/starlight%400.43.0>
- npm metadata: <https://registry.npmjs.org/@astrojs/starlight>, <https://registry.npmjs.org/astro>
- Frontmatter reference (`title` required): <https://starlight.astro.build/reference/frontmatter/>
- `docsLoader()`: <https://starlight.astro.build/reference/configuration/#docsloader>
- Authoring content (links): <https://starlight.astro.build/guides/authoring-content/>
- Site search: <https://starlight.astro.build/guides/site-search/>
- Plugins: <https://starlight.astro.build/resources/plugins/>
- Astro prerequisites (Node): <https://docs.astro.build/en/install-and-setup/>
- Astro Markdown (GFM): <https://docs.astro.build/en/guides/markdown-content/>
- Astro on GitHub Pages (`base`): <https://docs.astro.build/en/guides/deploy/github/>
- Relative-link rehype plugin: <https://registry.npmjs.org/astro-rehype-relative-markdown-links>
- `starlight-versions`: <https://registry.npmjs.org/starlight-versions>
- `starlight-links-validator`: <https://registry.npmjs.org/starlight-links-validator>

Others:

- Docusaurus npm metadata: <https://registry.npmjs.org/@docusaurus/core>
- Docusaurus Markdown links: <https://docusaurus.io/docs/markdown-features/links>
- Docusaurus `markdown.format`: <https://docusaurus.io/docs/api/docusaurus-config#markdown>
- Docusaurus search: <https://docusaurus.io/docs/search>
- VitePress npm metadata: <https://registry.npmjs.org/vitepress>
- VitePress linking between pages: <https://vitepress.dev/guide/routing#linking-between-pages>
- mdBook releases: <https://github.com/rust-lang/mdBook/releases>
- mdBook Markdown (links, admonitions): <https://rust-lang.github.io/mdBook/format/markdown.html>

GitHub and Dokka:

- GitHub Pages limits: <https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits>
- GitHub search (sign-in for code search): <https://docs.github.com/en/search-github/getting-started-with-searching-on-github/about-searching-on-github>
- GitHub code search (default branch only): <https://docs.github.com/en/search-github/github-code-search/about-github-code-search>
- `ubuntu-24.04` runner image (Python 3.12.3, Node.js 22.23.4): <https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md>
- Dokka HTML output: <https://kotlinlang.org/docs/dokka-html.html>
