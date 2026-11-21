Feature: Node lifecycle

  Scenario: Starting a stopped node makes it running
    Given a stopped node
    When the node starts
    Then the node is running

  Scenario: Stopping a running node makes it stopped
    Given a running node
    When the node stops
    Then the node is stopped

  Scenario: A node blocked by Bluetooth being off runs when Bluetooth becomes available
    Given a node blocked because Bluetooth is off
    When Bluetooth becomes available
    Then the node is running
