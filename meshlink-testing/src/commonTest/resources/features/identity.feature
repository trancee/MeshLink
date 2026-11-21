Feature: Installation identity

  Scenario: A node retains its identity when restarted
    Given Alice and Bob have a direct link
    And Bob remembers Alice's identity
    When Alice restarts
    Then Bob sees Alice with the same identity
