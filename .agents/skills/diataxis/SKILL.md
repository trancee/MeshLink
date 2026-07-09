---
name: diataxis
description: Write, audit, and restructure documentation using the Diataxis framework. Classifies content into four types — tutorials, how-to guides, reference, explanation — and provides writing guidance for each. Use when creating docs, reviewing docs, restructuring a docs site, writing READMEs, or when asked about documentation strategy, information architecture, or "what kind of doc should this be?"
---

# Diataxis — Systematic Documentation Authoring

Diataxis is a systematic approach to technical documentation. It identifies four distinct documentation types that serve four different user needs, and prescribes how to write each one correctly.

Source: [diataxis.fr](https://diataxis.fr) by Daniele Procida.

## The Four Types

| Type | Orientation | Serves | User is... | Analogy |
|------|-------------|--------|------------|---------|
| **Tutorial** | Learning | Acquisition of skill | Studying | A cooking lesson |
| **How-to guide** | Goals/Tasks | Application of skill | Working | A recipe |
| **Reference** | Information | Application of skill | Working | A nutrition label |
| **Explanation** | Understanding | Acquisition of skill | Studying | A food history book |

## The Compass (Decision Tree)

When you need to classify a piece of documentation, ask two questions:

1. **Does this inform action (doing) or cognition (thinking)?**
2. **Does this serve acquisition of skill (study) or application of skill (work)?**

| Content informs... | And serves the user's... | It belongs to... |
|--------------------|--------------------------|------------------|
| Action | Acquisition of skill | **Tutorial** |
| Action | Application of skill | **How-to guide** |
| Cognition | Application of skill | **Reference** |
| Cognition | Acquisition of skill | **Explanation** |

## The Map (Why Types Get Confused)

Diataxis is a **map**, not a list: each type sits next to two neighbors it shares a dimension with, and that shared dimension is exactly where the two blur together in practice:

| Neighbors | Shared dimension | How the blur shows up |
|-----------|-------------------|------------------------|
| Tutorial ↔ How-to guide | Both guide **action** | Steps that teach basics instead of assuming competence, or vice versa |
| How-to guide ↔ Reference | Both serve **application of skill** (work) | A how-to that degrades into an exhaustive option list; reference that sneaks in imperative instructions |
| Reference ↔ Explanation | Both are **propositional knowledge** (cognition) | Reference that argues or justifies design choices instead of stating facts |
| Explanation ↔ Tutorial | Both serve **acquisition of skill** (study) | Explanation that drifts into a walkthrough; a tutorial that digresses into background theory |

When a document feels hard to classify, it's almost always blurring with one of its two map-neighbors, not with the type diagonally opposite it. Diagnose by naming which shared dimension is bleeding through.

## When to Use This Skill

Invoke this skill when any of these apply:

- Writing new documentation (README, guides, API docs, wikis)
- Auditing or reviewing existing documentation
- Restructuring a documentation site or information architecture
- Deciding "what kind of doc should this be?"
- Creating a documentation strategy or content plan
- Separating tangled content that mixes tutorials with reference, etc.

## How to Apply Diataxis

### Step 1: Classify

Look at the content (or planned content). Use the compass above to determine which type it is or should be. If a single document mixes types, it needs to be split.

### Step 2: Write According to Type

Each type has specific writing rules. Read the appropriate reference:

- [Tutorials](references/tutorials.md) — learning-oriented lessons
- [How-to guides](references/how-to-guides.md) — goal-oriented directions
- [Reference](references/reference.md) — information-oriented descriptions
- [Explanation](references/explanation.md) — understanding-oriented discussion

### Step 3: Check Quality

Use the [quality checklist](references/quality-checklist.md) to verify the document stays true to its type.

### Step 4: Organize

Documentation should be organized around these four types. Each type gets its own section or area. Do not intermingle them.

```
docs/
  tutorials/       # Learning-oriented
  how-to/          # Goal-oriented
  reference/       # Information-oriented
  explanation/     # Understanding-oriented
```

**But treat this as a destination, not a starting plan.** See Workflow below — don't create four empty folders up front and pour content into them; let the structure emerge from repeated small improvements.

## Workflow: a Guide, Not a Plan

Diataxis describes where documentation ends up, not a project plan for getting there top-down. Applying it as a big upfront restructuring plan fights the framework's own grain.

- **Don't pre-build empty structure.** Creating empty tutorials/how-to/reference/explanation folders with nothing in them and waiting to fill them is explicitly discouraged — let categories emerge as content accumulates a critical mass that demands its own heading.
- **Work one small step at a time**, not the whole architecture at once. Every improvement is worth publishing (or committing) immediately, even a single paragraph.
- **The iteration cycle:** Choose something (any page, section, or paragraph in front of you — don't go hunting) → Assess it against the compass (what need does this serve? how well?) → Decide the single next action that improves it → Do it and consider it done → repeat.
- **Complete, not finished.** At any point in this process the docs should be complete for their current stage — useful and structurally sound — even though, like any living project, they're never "finished."

## Quick Rules Per Type

### Tutorials

- Take the learner through a hands-on experience
- YOU are responsible for their success
- Minimize explanation — link to it instead
- Focus on the concrete and particular
- Deliver visible results early and often
- Use "we" language: "We will...", "Now, do x..."
- Ignore options and alternatives

### How-to Guides

- Address a real-world goal or problem
- Assume the user is already competent
- Provide a set of executable instructions
- Stay focused on the task — no teaching, no digressions
- Name them clearly: "How to configure X for Y"
- Adapt to real-world complexity; don't over-simplify

### Reference

- Describe the machinery — nothing more
- Be austere, accurate, complete, and neutral
- Mirror the structure of the thing being described
- Use standard, consistent patterns
- Provide examples to illustrate, not to teach
- State facts, list options, provide warnings

### Explanation

- Provide context, background, and the "why"
- Can include opinions and multiple perspectives
- Approach the subject from different angles
- Keep it bounded — don't let reference or how-to creep in
- Name with implicit "About...": "About user authentication"
- This is the only doc type worth reading away from the product

## Auditing Existing Documentation

When reviewing docs, look for these common anti-patterns:

1. **Tutorial stuffed with explanation** — The learner loses focus. Extract explanations and link to them.
2. **How-to guide that teaches** — The working user doesn't need a lesson. Strip the teaching; keep the steps.
3. **Reference that explains** — Reference must be neutral descriptions. Move opinions and context to explanation.
4. **Explanation that instructs** — Discussion is not the place for step-by-step instructions. Move procedures to how-to guides.
5. **Mixed documents** — A single page that tries to be all four at once. Split into separate pages by type.
6. **Missing types** — Most projects have reference (maybe auto-generated) but lack tutorials and explanation. Identify the gaps.

## Documentation Architecture Template

For a new project or major restructuring, this is the shape documentation tends toward — use it as a reference point to compare against, not a blueprint to fill in from day one (see Workflow above):

```markdown
# Project Documentation

## Getting Started (Tutorials)
- Your first [project] in 10 minutes
- Building a [simple example] step by step

## Guides (How-to)
- How to install and configure [project]
- How to deploy to production
- How to migrate from version X to Y
- Troubleshooting common issues

## Reference
- API reference
- Configuration options
- CLI commands
- Error codes

## Background (Explanation)
- Architecture and design decisions
- About the security model
- Understanding the data pipeline
- Why we chose [technology X]
```

## Deep-Dive References

| Reference | Content |
|-----------|---------|
| [references/tutorials.md](references/tutorials.md) | Full guidance on writing tutorials |
| [references/how-to-guides.md](references/how-to-guides.md) | Full guidance on writing how-to guides |
| [references/reference.md](references/reference.md) | Full guidance on writing reference docs |
| [references/explanation.md](references/explanation.md) | Full guidance on writing explanation docs |
| [references/quality-checklist.md](references/quality-checklist.md) | Quality checklist for all four types, plus functional vs. deep quality |
| [references/complex-hierarchies.md](references/complex-hierarchies.md) | When the four types cross another axis — multiple platforms, audiences, or products in one docs set |
