Feature: Security profiles

  Scenario: Nodes with different security profiles do not connect
    Given Alice uses the unsecured profile and Bob uses the classical profile in the same mesh
    When the discovery nodes start
    Then both discovery nodes are running
    And neither node lists a peer
