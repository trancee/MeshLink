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
