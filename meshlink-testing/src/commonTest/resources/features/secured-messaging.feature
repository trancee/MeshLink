Feature: Secured messages

  In a classical or post-quantum mesh, a message to any node travels end-to-end encrypted, sealed
  to the identity certificate of its destination. A sender that holds no certificate for the
  destination looks it up first. The nodes that relay the message learn neither its text nor its
  exact size.

  Scenario Outline: A message to a node four hops away waits for its certificate and arrives sealed
    Given Alice to Eve are running in a <profile> line with links only between neighbors
    When Alice sends "Meet at the north gate" to Eve, holding no certificate of Eve
    Then Alice's message stayed Queued until Alice held Eve's certificate, was then Sent, and then Delivered
    And Eve receives "Meet at the north gate" from Alice once
    And Bob, Carol and Dave relayed the message only as <size>-byte frames without its text

    Examples:
      | profile      | size |
      | classical    | 408  |
      | post-quantum | 3960 |

  Scenario: A message to a node whose certificate nobody holds expires
    Given Alice to Eve are running in a classical line with links only between neighbors
    When Alice sends "Anyone there?" to a node of another mesh
    Then Alice's message stays Queued until 30 seconds after its expiry, and is then Expired

  Scenario: A secured node is listed as routed only once its certificate and a route are both held
    Given Alice to Eve are running in a classical line with links only between neighbors
    And Alice is in the medium density class
    And Alice holds Eve's certificate, has no route to Eve, and does not list Eve as a peer
    When Alice sends "Hi" to Eve
    Then Alice lists Eve as a routed peer 4 hops away

  Scenario: A route found to one node leaves a message waiting for another node's certificate queued
    Given Alice to Eve are running in a classical line with links only between neighbors
    And Alice is in the medium density class
    When Alice sends "Anyone there?" to a node of another mesh, and then "Hi" to Eve
    Then Eve receives "Hi" from Alice once
    And Alice's message "Anyone there?" is still Queued
