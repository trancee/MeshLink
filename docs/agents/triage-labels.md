# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
|--------------------------|--------------------|----------------------------------------|
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Two more states track work on a ticket (ADR 0021):

| State | Meaning |
|---|---|
| `claimed` | Someone is working on it; set before any work |
| `done` | Its pull request is merged |

The states move `needs-triage` → `ready-for-agent` or `ready-for-human` → `claimed` → `done`. `needs-info` and `wontfix` can follow any open state.
