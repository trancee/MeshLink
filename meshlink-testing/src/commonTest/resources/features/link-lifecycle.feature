Feature: Link lifecycle

  Scenario: A peer leaves the list when it moves out of range
    Given Alice and Bob have a direct link
    When Bob moves out of range
    Then neither node lists the other as a peer

  Scenario: Peers reconnect when they come back into range
    Given Alice and Bob have a direct link
    And Bob is out of range
    When Bob returns within range
    Then Alice and Bob again list each other as direct peers
