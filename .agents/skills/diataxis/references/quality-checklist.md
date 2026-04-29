# Diataxis Quality Checklist

Use this checklist to verify that a document stays true to its type. Check the column for the type you're writing.

## Universal Checks

- [ ] The document clearly belongs to ONE type (not a mix)
- [ ] The title accurately reflects the content and type
- [ ] Cross-references link to the correct type (e.g., "For more details, see [explanation doc]" not inline explanation)
- [ ] The document lives in the correct section of the docs architecture

---

## Tutorial Checklist

- [ ] It is a practical, hands-on lesson (the user does things)
- [ ] The teacher takes full responsibility for the learner's success
- [ ] It shows where the learner will end up at the start
- [ ] Every step produces a visible, meaningful result
- [ ] It provides expected output or confirms the user is on track
- [ ] Explanation is minimal (one sentence max, with link to more)
- [ ] It stays concrete — no abstraction or generalization
- [ ] Options and alternatives are ignored
- [ ] It uses "we" language ("We will...", "Now, let's...")
- [ ] It has been tested end-to-end and works reliably
- [ ] It does NOT assume the learner knows the domain
- [ ] It does NOT present choices to the learner

---

## How-to Guide Checklist

- [ ] It addresses a specific real-world goal or problem
- [ ] The title clearly states what the user will accomplish
- [ ] It assumes the user is already competent
- [ ] It provides executable, actionable steps
- [ ] It stays focused on the task — no digressions
- [ ] It does NOT teach or explain background concepts inline
- [ ] It does NOT list every possible option (links to reference instead)
- [ ] It handles real-world complexity (not just the happy path)
- [ ] It starts and ends at reasonable points (doesn't need to be end-to-end)
- [ ] It addresses the user's needs, not the tool's capabilities
- [ ] The ordering of steps has logical flow

---

## Reference Checklist

- [ ] It describes the machinery — nothing more
- [ ] It is austere, neutral, and factual
- [ ] It is accurate and complete
- [ ] It follows a consistent, standard pattern
- [ ] Its structure mirrors the structure of what it describes
- [ ] It includes examples where helpful
- [ ] It does NOT contain opinions or interpretations
- [ ] It does NOT include instructional "how to" steps
- [ ] It does NOT explain "why" (links to explanation instead)
- [ ] All parameters, options, return values, and errors are documented
- [ ] Similar items use the same format

---

## Explanation Checklist

- [ ] It provides context, background, and "why"
- [ ] The title works with an implicit "About..." prefix
- [ ] It makes connections to other concepts and broader context
- [ ] It considers multiple perspectives and alternatives
- [ ] It admits opinions where appropriate
- [ ] It is bounded — doesn't try to cover everything
- [ ] It does NOT include step-by-step instructions
- [ ] It does NOT provide detailed technical reference
- [ ] It could be read away from the product and still make sense
- [ ] It helps the reader form a mental model or deeper understanding

---

## Common Boundary Violations

| Symptom | Problem | Fix |
|---------|---------|-----|
| Tutorial has paragraphs of "why" | Explanation leaked into tutorial | Extract to explanation doc, add link |
| How-to guide teaches basics first | Tutorial content in how-to | Assume competence; link to tutorial |
| Reference says "you should..." | How-to guidance in reference | Move to how-to guide |
| Reference explains design decisions | Explanation in reference | Move to explanation doc |
| Explanation has code blocks with steps | How-to guide in explanation | Move steps to how-to guide |
| One page tries to do everything | Mixed document | Split into separate docs by type |

## The Compass Quick-Check

When in doubt about classification, ask:

1. **Action or cognition?**
   - Action = tutorial or how-to guide
   - Cognition = reference or explanation

2. **Acquisition or application?**
   - Acquisition (study) = tutorial or explanation
   - Application (work) = how-to guide or reference

The intersection gives you the type.
