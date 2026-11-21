Feature: Security profiles

  Scenario: Nodes with different security profiles do not connect
    Given Alice uses the unsecured profile and Bob uses the classical profile in the same mesh
    When the discovery nodes start
    Then both discovery nodes are running
    And neither node lists a peer

  Scenario: A gated mesh rejects a peer with a different mesh key
    Given Alice and Bob use different keys for the same classical gated mesh
    When the discovery nodes start
    Then both discovery nodes are running
    And neither node lists a peer
