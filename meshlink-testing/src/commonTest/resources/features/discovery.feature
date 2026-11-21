Feature: Peer discovery

  Scenario: Nodes in the same mesh list each other as direct peers
    Given Alice and Bob are configured for the same mesh
    When the discovery nodes start
    Then Alice and Bob list each other as direct peers

  Scenario: Nodes in different meshes do not list each other
    Given Alice and Bob are configured for different meshes
    When the discovery nodes start
    Then neither node lists a peer
