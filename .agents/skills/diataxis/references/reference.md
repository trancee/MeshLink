# Writing Reference Documentation

Reference guides are **technical descriptions** of the machinery and how to operate it. Reference material is **information-oriented**.

Reference contains **propositional or theoretical knowledge** that a user needs to do things correctly: accurate, complete, reliable information, free of distraction and interpretation.

## Reference as Description

Reference material **describes the machinery**. It should be **austere**. One hardly reads reference material — one **consults** it.

There should be **no doubt or ambiguity** in reference; it should be wholly authoritative.

Reference is like a **map**: it tells you what you need to know about the territory without having to go check the territory yourself. A reference guide serves the same purpose for the product and its internal machinery.

### What Reference Can Include

Although reference should not show how to perform tasks, it **can and often needs to** include:

- A description of how something works
- The correct way to use something
- Technical constraints and limitations

### What Reference Should Not Include

- Tutorials or learning paths
- Goal-oriented procedures
- Opinions, interpretations, or context
- Extended explanations of "why"

## Key Principles

### Describe and Only Describe

**Neutral description** is the key imperative of technical reference.

| Do | Don't |
|----|-------|
| Austere and uncompromising | Explain motivations |
| Neutral, objective, factual | Instruct or guide |
| Structured by the machinery itself | Discuss or opine |

One of the hardest things to do is describe something neutrally. It's not natural — what's natural is to explain, instruct, discuss, and opine. All of these run counter to reference, which demands **accuracy, precision, completeness, and clarity**.

When tempted to add instruction or explanation, **link to how-to guides and explanation docs instead**.

### Adopt Standard Patterns

Reference material is useful when it is **consistent**. Standard patterns allow users to use reference effectively.

Your job is to place material where users **expect to find it**, in a format they are **familiar with**.

- Use the same structure for every API endpoint, every CLI command, every config option
- Use tables consistently
- Use the same heading hierarchy
- Don't vary your approach for creative effect

### Respect the Structure of the Machinery

The documentation structure should **mirror the product structure**, so users can work through both simultaneously.

- If a method belongs to a class in a module, the docs should reflect that hierarchy
- API docs should follow the API's organizational structure
- Config docs should follow the config file's structure

This doesn't mean forcing unnatural structure. What matters is that the **logical, conceptual arrangement** of the code helps make sense of the documentation.

### Provide Examples

Examples illustrate without distracting from the job of describing:

- An example of a command's usage shows context succinctly
- An example API call demonstrates the expected shape
- An example config block shows valid structure

Examples help understanding **without falling into the trap of trying to explain or instruct**.

## Standard Reference Patterns

### API Reference

```markdown
## `functionName(param1, param2, options?)`

Description of what this function does.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| param1 | string | Yes | What this parameter is |
| param2 | number | Yes | What this parameter is |
| options | Object | No | Configuration options |

**Returns:** `ReturnType` — Description of return value.

**Throws:** `ErrorType` — When this error occurs.

**Example:**

\`\`\`javascript
const result = functionName("hello", 42);
\`\`\`
```

### CLI Reference

```markdown
## `command subcommand [options] <required> [optional]`

Description of what this command does.

**Arguments:**

| Argument | Required | Description |
|----------|----------|-------------|
| required | Yes | What this argument is |
| optional | No | What this argument is |

**Options:**

| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| --verbose | -v | false | Enable verbose output |
| --output | -o | stdout | Output destination |

**Example:**

\`\`\`bash
command subcommand --verbose my-input
\`\`\`
```

### Configuration Reference

```markdown
## Configuration Options

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| port | number | 3000 | Server port |
| host | string | "localhost" | Bind address |
| debug | boolean | false | Enable debug mode |

**Example configuration:**

\`\`\`yaml
port: 8080
host: "0.0.0.0"
debug: true
\`\`\`
```

## Language Patterns

| Pattern | Example |
|---------|---------|
| State facts | "Django's default logging configuration inherits Python's defaults." |
| List things | "Sub-commands are: a, b, c, d, e, f." |
| Provide warnings | "You must use a. You must not apply b unless c. Never d." |

## Anti-Patterns to Avoid

- Assuming auto-generated reference is sufficient (it rarely is)
- Mixing instructional content into reference material
- Inconsistent formatting between similar items
- Incomplete descriptions (missing parameters, missing return values)
- Documentation structure that doesn't match the product structure
- Opinions, perspectives, or "why" discussions embedded in reference
- Varying style for creative effect
