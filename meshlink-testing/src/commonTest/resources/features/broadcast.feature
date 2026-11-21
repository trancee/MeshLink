Feature: Broadcast messages

  Scenario: A broadcast with a zero hop limit is rejected
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    When Alice broadcasts "Hello" with hop limit 0
    Then the broadcast is rejected as InvalidHopLimit
    And Bob receives no messages

  Scenario: A broadcast reaches only nodes within its hop limit
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Bob, Carol and Dave are collecting messages
    When Alice broadcasts "Hello" with a hop limit of 2
    Then Bob and Carol each receive the broadcast "Hello" from Alice once
    And Dave receives no broadcasts

  Scenario: A returning node catches up on a missed broadcast
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Bob, Carol and Dave are collecting messages
    And Dave is out of range
    And Alice broadcasts "Hello" while Dave is away
    When Dave returns within a minute
    Then Dave receives the broadcast "Hello" from Alice once
