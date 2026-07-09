# Diataxis in Complex Hierarchies

Most documentation fits the simple structure cleanly: one landing page per type, containing the four kinds of content. This reference covers what to do when a second axis cuts across the four types — multiple platforms, multiple user roles, or multiple products sharing one docs set.

## The Simple Case (Baseline)

```
Home            <- landing page, overview of everything below
Tutorial        <- landing page
  Part 1
  Part 2
Reference       <- landing page
  Command-line tool
  API
Explanation     <- landing page
  Architecture overview
```

Each landing page is an overview *in prose*, introducing what's below it — not just a bare list of links. Keep lists in a table of contents short: beyond ~7 items, group them under another layer of headings rather than presenting one long flat list.

## The Two-Dimensional Problem

Diataxis gives you one axis (the four types). Real projects often have a second axis:

- **Multiple platforms** doing the same job differently (e.g., the same app on Android, iOS, and desktop)
- **Multiple audiences** (end users vs. integrators vs. contributors)
- **Multiple deployment targets** (the same product on different clouds, with different workflows per target)

When both axes are real, you have two candidate structures:

**Type-first** (one tutorial/how-to/reference/explanation set, each internally split by platform):
```
tutorial/
  android/
  ios/
  desktop/
how-to/
  android/
  ios/
  desktop/
```

**Platform-first** (one Diataxis structure per platform):
```
android/
  tutorial/
  how-to/
  reference/
  explanation/
ios/
  tutorial/
  how-to/
  reference/
  explanation/
```

Neither is automatically correct, and both risk duplicating shared content. **Diataxis does not resolve which axis goes on top** — that call is a user-needs question, not a framework question.

## Resolving It: User-First Thinking

Ask how the *user* actually experiences the split, not how the product team conceives it:

- If a user who works on one platform essentially never touches the others, treat each platform as close to a separate product — platform-first is usually more honest to how they'll navigate.
- If most users move fluidly across the split (e.g., a contributor needs everything a regular user needs, plus more), let structure be uneven on purpose: some sections merge (e.g., one shared tutorial), others split cleanly (e.g., separate contributor how-to guides) — matching how the audiences actually relate to each other, rather than forcing symmetry.
- This repo (MeshLink) is exactly this shape: shared BLE mesh networking concepts, but Android/iOS/desktop each need their own platform-specific how-to and reference content, while explanation of the mesh protocol itself is likely shared across all three.

## The Governing Principle

**Diataxis is not "four boxes."** It's an approach — identify the four needs, and organize so nothing blurs the boundaries between them — not a mandate that the top-level hierarchy must literally have exactly four top-level folders. A structure that crosses the four types with platform, audience, or product can still be a faithful application of Diataxis, as long as:

- Within any given branch, the four types are still kept from bleeding into each other
- The structure is navigable and its logic is discoverable by the user, even if it's more complex than the four-box baseline

Let documentation be as complex as it needs to be — complexity is fine as long as it's logical and still serves user needs; it's an uncomplicated structure imposed for its own sake that causes problems, not complexity itself.
