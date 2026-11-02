# How do open and unsecured meshes resist fake identities (Sybil)?

Type: prototype
Status: open

## Question

In the routing simulation, 30% fake identities split the mesh under any neighbor selection strategy. Evaluate defenses (identity cost such as proof of work, peer scoring, neighbor diversity) in the simulator, and decide what v1 ships and what it documents as a limitation.

## Context

- Throwaway branch from `prototype/routing-sim`; pre-registered criteria.
- Gated meshes already cost a stolen mesh key (profile guarantees).
