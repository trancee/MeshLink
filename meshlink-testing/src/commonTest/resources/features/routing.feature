Feature: Routed messages

  Scenario: A message reaches a destination through intermediate nodes
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Bob, Carol and Dave are collecting messages
    And Alice knows Dave's identity
    When Alice sends "Hello" to Dave through the mesh
    Then Dave receives the direct message "Hello" from Alice once
    And Bob and Carol receive no direct messages

  Scenario: A message reaches its destination after the route changes
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Bob, Carol and Dave are collecting messages
    And Alice knows Dave's identity
    And Alice has sent "Before" to Dave
    When Bob leaves and Carol and Dave move closer
    And Alice sends "After" to Dave through the mesh
    Then Dave receives "Before" and "After" from Alice once each
    And Alice, Carol and Dave have links only to their new neighbors

  Scenario: A message uses an existing alternative route after a peer leaves
    Given Alice, Bob, Carol and Dave are running with two routes from Alice to Dave
    And Bob, Carol and Dave are collecting messages
    And Alice knows Dave's identity
    And Alice has sent "Before" to Dave
    When Bob leaves the two-route mesh
    And Alice sends "After" to Dave through the mesh
    Then Dave receives "Before" and "After" from Alice once each
    And Alice, Carol and Dave have links only to their new neighbors

  Scenario: A reply reaches the sender when relays also hear each other's copies
    Given Alice, Bob, Carol and Dave are running with Bob and Carol linked to each other and to both ends
    And Bob, Carol and Dave are collecting messages
    And Alice is collecting messages
    And Dave has sent "Ping" to Alice through the mesh
    When Alice replies "Pong" to the sender of "Ping"
    Then Dave receives the direct message "Pong" from Alice once
    And Bob and Carol receive no direct messages
