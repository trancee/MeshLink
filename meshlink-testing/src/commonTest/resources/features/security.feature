Feature: Security profiles

  Scenario: Members of a classical gated mesh connect securely
    Given Alice and Bob use the same key for the same classical gated mesh
    When the discovery nodes start
    Then Alice and Bob list each other as direct peers
    And both discovery peers are secured

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
