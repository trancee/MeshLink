Feature: Reference scenarios

  Each preset has a reference scenario that shows the job an app uses it for, through the public API
  only. The Swift fake MeshLinkTesting runs the same scenarios under the same titles, so an app's Swift
  tests and these Kotlin tests agree on what MeshLink does (ADR 0017 decision 6). The nodes run on
  seeded virtual air.

  Scenario: Offline chat: a message routed over a relay becomes Delivered and then Read
    Given Alice, a relay and Bob run OfflineChat in a classical mesh, with the relay between them
    When Alice sends "Hi Bob" to Bob and Bob marks it read
    Then Bob, who is not Alice's neighbor, received "Hi Bob" from Alice
    And Alice's message "Hi Bob" went from Delivered to Read

  Scenario: Emergency broadcast: an alert reaches the nodes within its hop limit only
    Given five nodes run EmergencyBroadcast in a classical line
    When the first node broadcasts "Flood warning" with a hop limit of 3
    Then the next three nodes received "Flood warning" from the first node, and the fifth received nothing

  Scenario: Proximity discovery: each neighbor carries the proximity bucket of its signal strength
    Given three nodes run ProximityDiscovery, at -45, -65 and -85 dBm from where Alice will be
    When Alice starts ProximityDiscovery among them
    Then Alice sees those neighbors Immediate, Near and Far

  Scenario: Local multiplayer: four players on a custom preset exchange moves directly
    Given four players run the custom preset "Arena", based on LocalMultiplayer, all in range of each other
    When every player sends a move directly to every other player
    Then every player received a move from each of the 3 others within 1 second

  Scenario: Field teams: a message held while the scout is away is delivered when the scout returns
    Given the base and a scout run FieldTeams in a post-quantum mesh, and the base sent "Regroup at hut" to the scout while the scout was away
    When the scout returns
    Then the scout received "Regroup at hut", and the base's message was Held, then Sent and Delivered

  Scenario: Field teams: a held message expires when its lifetime passes
    Given the base and a scout run FieldTeams in a post-quantum mesh, and the base sent "Regroup at hut" to the scout while the scout was away
    When the hour-long lifetime of the message passes
    Then the scout received nothing, and the base's message went from Held to Expired
