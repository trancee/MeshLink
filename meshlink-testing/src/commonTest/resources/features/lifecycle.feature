Feature: Node lifecycle

  Scenario: Starting a stopped node makes it running
    Given a stopped node
    When the node starts
    Then the node is running

  Scenario: Starting a running node has no effect on its lifecycle
    Given a running node
    And the node's lifecycle is observed
    When the node starts
    Then the node remains running without a lifecycle transition

  Scenario: Stopping a running node makes it stopped
    Given a running node
    When the node stops
    Then the node is stopped

  Scenario: A stopped node can start again
    Given a running node
    And the node has stopped
    When the node starts
    Then the node is running

  Scenario: Nodes starting together on different radios both run without being blocked
    Given two stopped nodes on different radios
    And both nodes' lifecycles are observed
    When both nodes start at the same time
    Then both nodes are running and neither was blocked

  Scenario: A node waiting for a radio in use leaves the radio with its holder when it stops
    Given a running node
    And a second node on the same radio is blocked because the radio is in use
    When the waiting node stops
    Then a third node started on that radio is blocked because the radio is in use

  Scenario: Only one waiting node takes a radio when its holder stops
    Given a running node
    And two more nodes on the same radio are blocked because the radio is in use
    When the running node stops
    Then exactly one waiting node is running and the other is blocked because the radio is in use

  Scenario: A closed node cannot start
    Given a closed node
    When the node tries to start
    Then starting is rejected because the node is closed

  Scenario: Resetting identity while running is rejected
    Given a running node
    When the node tries to reset its identity
    Then the reset is rejected because the node is running

  Scenario: A node blocked by Bluetooth being off runs when Bluetooth becomes available
    Given a node blocked because Bluetooth is off
    When Bluetooth becomes available
    Then the node is running

  Scenario: A node blocked by scan permission runs when permission is granted
    Given a node blocked because Bluetooth scan permission is denied
    When Bluetooth scan permission is granted
    Then the node is running
