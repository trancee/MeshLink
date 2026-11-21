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

  Scenario: A closed node cannot start
    Given a closed node
    When the node tries to start
    Then starting is rejected because the node is closed

  Scenario: A node blocked by Bluetooth being off runs when Bluetooth becomes available
    Given a node blocked because Bluetooth is off
    When Bluetooth becomes available
    Then the node is running
