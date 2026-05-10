# Writing Tutorials

A tutorial is a **lesson** — a learning experience that takes place under the guidance of a tutor. It is always **learning-oriented** and **practical**: the user does something meaningful under your guidance.

A tutorial serves the user's **acquisition of skills and knowledge** (their study). Its purpose is not to help them get something done, but to help them **learn**.

## The Tutorial as a Lesson

A lesson entails a relationship between teacher and pupil. Learning takes place as the pupil applies themself to tasks under the instructor's guidance.

**What matters is what the learner does and what happens** — not the teacher's explanations and recitations of fact.

A good lesson gives the learner **confidence** by showing them they can be successful.

### The Teacher's Obligations

Nearly all responsibility falls on the teacher:

- **You** decide what the pupil will learn
- **You** decide what the pupil will do to learn it
- **You** are responsible for the pupil's success
- The pupil's only responsibility is to be attentive and follow directions

The exercise must be:

- **Meaningful** — the pupil has a sense of achievement
- **Successful** — the pupil can complete it
- **Logical** — the path makes sense
- **Usefully complete** — the pupil encounters all needed actions, concepts, and tools

## Key Principles

### The First Rule: Don't Try to Teach

Provide the learner with an experience that allows them to learn. Don't give in to the anxiety to impart knowledge by telling and explaining — that jeopardizes the learning experience.

Give your learner things to **do**, through which they can learn.

### Show Where They'll Be Going

Let the learner form an idea of what they will achieve from the start.

**Good:** "In this tutorial, we will create and deploy a scalable web application. Along the way we will encounter containerisation tools and services."

**Bad:** "In this tutorial you will learn..." (presumptuous)

### Deliver Visible Results Early and Often

The learner is doing new and strange things they don't fully understand. Let them see results and make connections rapidly and repeatedly. Each result should be **meaningful**.

Every step should produce a **comprehensible result**, however small.

### Maintain a Narrative of the Expected

At every step, the user feels anxiety: will this produce the correct result? Keep providing feedback that they are on the right path.

- "You will notice that..."
- "After a few moments, the server responds with..."
- Show actual example output or exact expected output
- Flag likely signs of going wrong: "If the output doesn't show X, you probably forgot to..."
- Prepare for surprises: "The command will return several hundred lines of logs."

### Point Out What the Learner Should Notice

Learning requires reflection. Prompt the learner to observe things:

- How a command prompt changes
- What appeared in the output
- How a file was modified

Observing is an **active** part of craft, not merely passive.

### Target the Feeling of Doing

The accomplished practitioner experiences a joined-up purpose, action, thinking, and result. Your tutorial's tasks should tie together purpose and action to become a cradle for this feeling.

### Encourage Repetition

Learners return to exercises that give them success, for the pleasure of getting the expected result. Make it possible to repeat steps. Repetition is a key to establishing the feeling of doing.

### Ruthlessly Minimize Explanation

**A tutorial is not the place for explanation.**

The user is focused on following directions and getting results. Explanation distracts them and blocks their learning.

- Enough: "We're using HTTPS because it's more secure."
- Too much: A paragraph about TLS handshakes and certificate chains
- Best: Brief statement + link to the explanation doc for when they're ready

**Explanation is only pertinent at the moment the user wants it. It is not for the author to decide.**

### Focus on the Concrete

Keep the learner in the moment — concrete things, concrete steps, concrete results. Lead from step to concrete step.

Our minds perceive general patterns from concrete examples. All learning moves from the concrete and particular toward the general and abstract.

### Ignore Options and Alternatives

Guide the learner to a successful conclusion. Ignore interesting diversions — different options, different approaches. Everything else can wait for another time.

### Aspire to Perfect Reliability

A tutorial must inspire confidence. At every stage, the learner must see the result you promise. A learner who doesn't get expected results quickly loses confidence.

Your tutorial should be so well constructed that it works for **every user, every time**.

## Language Patterns

| Pattern | Example |
|---------|---------|
| First person plural | "We will create a new project..." |
| Describe what they'll accomplish | "In this tutorial, we will..." |
| Direct imperatives | "First, do x. Now, do y. Now that you have done y, do z." |
| Minimal explanation with links | "We must always do x before y because... (see Explanation for details)" |
| Clear expectations | "The output should look something like..." |
| Orientation clues | "Notice that...", "Remember that...", "Let's check..." |
| Admire the result | "You have built a secure, three-layer hylomorphic stasis engine..." |

## Anti-Patterns to Avoid

- Overloading with explanation (the #1 mistake)
- Presenting choices and options
- Assuming the learner can fill in gaps
- Skipping the expected output
- Not testing the tutorial end-to-end
- Trying to teach by telling rather than by doing
- Abstracting and generalizing instead of staying concrete
