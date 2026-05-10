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

For a new project or major restructuring:

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
| [references/quality-checklist.md](references/quality-checklist.md) | Quality checklist for all four types |
