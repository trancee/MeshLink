Feature: Delivery across a mesh

  A direct message to a node its sender can reach through nodes in range of each other arrives
  once, intact and within its lifetime, whatever the shape of the mesh, and its sender sees it
  delivered. A relay holds a message whose destination is away and hands it over when the
  destination returns, even after its sender has gone.

  Scenario Outline: Every message arrives once and intact in a <layout> layout
    Given <nodes> nodes are running in a <layout> layout with seed <seed>
    When each node sends a 256-byte message to <contacts> other nodes it can reach
    Then every message arrives once and intact within its lifetime
    And every sender sees its messages delivered

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
    And every sender sees its messages delivered

  Scenario: A relay hands a held message to its destination after the sender has gone
    Given 3 nodes are running in a line layout with seed 5
    When node 2 leaves
    And node 0 sends a 256-byte message to node 2
    And node 0 leaves
    And node 2 returns to its place
    And node 0 returns to its place
    Then every message arrives once and intact within its lifetime
    And every sender sees its messages delivered
