# PROTOTYPE, ticket 73: low-class retry cost (throwaway, never merged)

Question: which low-class `MESSAGE` retry rule brings n200-low under the 4,700 B/s busiest-node band
without losing delivery? `ProtoRetry.rule` (`-Pproto.rule=`) picks the rule; `run.sh` runs n200-low
seeds 1-5 per rule; each seed writes `<cell>-seed<N>.counts` (frames queued per kind during traffic).

| Rule | Busiest B/s | Unicast | Msg retry relays | Receipt relays | Total relays |
|---|---|---|---|---|---|
| d61 (main) | 4,755 fail | 2032/2034 | 1.90 M | 2.85 M | 6.17 M |
| route | 4,971 fail | 2033/2034 | 1.94 M | 2.95 M | 6.32 M |
| slow (5 to 60 s) | 4,596 pass | 2032/2034 | 1.73 M | 2.66 M | 5.83 M |
| nolink | 4,896 fail | 2060/2062 | 1.24 M | 3.35 M | 6.04 M |
| cap3 | 4,735 fail | 2008/2008 | 1.89 M | 2.58 M | 5.86 M |
| routeslow | 5,005 fail | 2010/2012 | 1.80 M | 2.86 M | 6.05 M |

Verdict (owner, 2026-11-26): adopt `slow` for the low class only; reject the rest; receipts (46 % of
relay writes) go to a new ticket. Busiest-node spread between seeds of one rule is about 600 B/s.
