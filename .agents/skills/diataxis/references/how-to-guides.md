# Writing How-to Guides

How-to guides are **directions** that guide the reader through a problem or towards a result. They are **goal-oriented**.

A how-to guide helps the user **get something done**, correctly and safely. It guides the user's **action** in the context of **real work**.

## How-to Guides Are About Problems and Goals

How-to guides must be written from the **perspective of the user, not the machinery**.

A how-to guide is defined by a **human need** — it should show what the human needs to do, with the tools at hand, to obtain the result they need.

### The Wrong Pattern

Guides defined by operations that can be performed with a tool (rather than by user needs) offer little value:

**Useless:** "To deploy the desired database configuration, select the appropriate options and press Deploy."

This tells the user nothing they couldn't figure out from the interface. It's disconnected from purpose.

**Useful:** What database configuration options align with particular real-world needs.

### The Right Pattern

How-to guides are about **goals, projects, and problems** — not about tools.

Tools appear as incidental bit-players, the means to the user's end. Sometimes a guide concentrates on a particular tool because the goal aligns with it. Other times a guide cuts across different tools, joining them in a series of activities defined by something a human needs to get done.

## Good Examples

- "How to store cellulose nitrate film" (specific goal)
- "How to configure frame profiling" (specific task)
- "How to configure reconnection back-off policies" (specific problem)
- "Troubleshooting deployment problems" (specific problem domain)

## Bad Examples

- "How to build a web application" (too open-ended — that's an entire domain of skill)
- "Using the Settings panel" (tool-centric, not goal-centric)

## What How-to Guides Are NOT

- **Not tutorials** — tutorials serve study; how-to guides serve work. Conflating them causes widespread documentation problems.
- **Not just procedures** — real-world problems don't always reduce to linear sequences. They fork, overlap, have multiple entry and exit points, and require judgment.

## Key Principles

### Maintain Focus on the Goal

A how-to guide is concerned with **work** — a task or problem with a practical goal. Everything in the guide serves that goal.

| Do | Don't |
|----|-------|
| Focused on tasks or problems | Digress into explanation |
| Assume the user knows what they want | Teach background concepts |
| Action and only action | Provide reference for completeness |

If explanation or reference material is important, **link to it** — don't include it inline.

### Assume Competence

A how-to guide serves the **already-competent user**. You can assume they:

- Know what they want to achieve
- Can follow your instructions correctly
- Have basic domain competence
- Don't need to be taught fundamentals

### Address Real-World Complexity

A guide useless for any purpose except exactly the narrow case you've addressed is rarely valuable. Find ways to remain open to the range of possibilities so users can adapt your guidance to their needs.

### Omit the Unnecessary

Practical usability is more helpful than completeness. A how-to guide does not need to be end-to-end. It should start and end in a reasonable, meaningful place and require the reader to join it up to their own work.

### Provide Executable Instructions

A how-to guide describes an **executable solution** to a real-world problem. It's a contract: if you're facing this situation, take these steps.

"Actions" includes physical acts but also **thinking and judgment** — solving a problem involves thinking it through. Address how the user thinks as well as what the user does.

### Describe a Logical Sequence

The fundamental structure is a **sequence** with logical ordering in time:

- Step two requires step one (obvious ordering)
- One operation sets up the environment for another (subtle ordering)
- What is the user asked to think about, and how does their thinking flow?

### Seek Flow

Ground your sequences in the patterns of the user's activities and thinking. Achieve **smooth progress**.

- Avoid making the user repeatedly switch contexts and tools
- Consider how long you require the user to hold thoughts open before resolution
- Avoid unnecessary jumping back to earlier concerns
- Mind the pace and rhythm of the guide

At its best, how-to documentation gives the user flow — the guide that appears to anticipate the user, like a helper who has the tool you were about to reach for.

### Pay Attention to Naming

Choose titles that say **exactly** what the guide shows.

| Quality | Title |
|---------|-------|
| Good | "How to integrate application performance monitoring" |
| Bad | "Integrating application performance monitoring" (is it about how to, or whether to?) |
| Very bad | "Application performance monitoring" (how? whether? or just what it is?) |

Search engines appreciate good titles as much as humans do.

## Language Patterns

| Pattern | Example |
|---------|---------|
| State the goal up front | "This guide shows you how to..." |
| Use conditional imperatives | "If you want x, do y. To achieve w, do z." |
| Link don't inline | "Refer to the x reference guide for a full list of options." |

## Anti-Patterns to Avoid

- Teaching the user instead of guiding them
- Including every possible option (that's reference)
- Explaining why things work (that's explanation)
- Being so narrow the guide is useless for real work
- Tool-centric rather than goal-centric framing
- Titles that don't clearly state the goal
