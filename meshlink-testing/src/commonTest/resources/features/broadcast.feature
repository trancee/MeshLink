Feature: Broadcast messages

  Scenario: A broadcast with a zero hop limit is rejected
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    When Alice broadcasts "Hello" with hop limit 0
    Then the broadcast is rejected as InvalidHopLimit
    And Bob receives no messages
