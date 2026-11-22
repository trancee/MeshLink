Feature: Delivery across a mesh

  A direct message to a node its sender can reach through nodes in range of each other arrives
  once, intact and within its lifetime, whatever the shape of the mesh.

  Scenario Outline: Every message arrives once and intact in a <layout> layout
    Given <nodes> nodes are running in a <layout> layout with seed <seed>
    When each node sends a 256-byte message to <contacts> other nodes it can reach
    Then every message arrives once and intact within its lifetime

    Examples:
      | layout | nodes | contacts | seed |
      | line   | 6     | 2        | 1    |
      | star   | 6     | 2        | 2    |
      | random | 12    | 3        | 3    |

  Scenario: Every message arrives once and intact while links drop and reconnect
    Given 12 nodes are running in a random layout with seed 4
    And each link drops on average every 45 seconds and its nodes reconnect
    When each node sends a 256-byte message to 3 other nodes it can reach over 5 minutes
    Then every message arrives once and intact within its lifetime
