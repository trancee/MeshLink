Feature: Installation identity

  Scenario: A node retains its identity when restarted
    Given Alice and Bob have a direct link
    And Bob remembers Alice's identity
    When Alice restarts
    Then Bob sees Alice with the same identity

  Scenario: Resetting a stopped node's identity gives it a new identity
    Given Alice and Bob have a direct link
    And Bob remembers Alice's identity
    And Alice is stopped
    When Alice resets her identity and starts
    Then Bob sees Alice with a new identity

  Scenario: An unavailable identity blocks a restart without replacing the identity
    Given Alice and Bob are linked and Alice's identity can become unavailable
    When Alice restarts with her identity unavailable
    Then Alice is blocked on identity availability
    When Alice's identity becomes available and she retries starting
    Then Bob recognizes Alice's original identity
