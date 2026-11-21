Feature: Routed messages

  Scenario: A message reaches a destination through intermediate nodes
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Bob, Carol and Dave are collecting messages
    And Alice knows Dave's identity
    When Alice sends "Hello" to Dave through the mesh
    Then Dave receives the direct message "Hello" from Alice once
    And Bob and Carol receive no direct messages
