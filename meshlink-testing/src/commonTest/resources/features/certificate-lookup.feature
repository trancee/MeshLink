Feature: Certificate lookup

  In a secured mesh, a node finds the identity certificate of a node it has no link to by asking
  the mesh. The nearest node that holds a valid certificate for that node answers, and the request
  goes no farther.

  Scenario Outline: The target's neighbor answers with the certificate their link proved
    Given Alice to Frank are running in a <profile> line with links only between neighbors
    When Alice looks up Frank's certificate
    Then Alice holds the certificate Frank proved to Eve on their link
    And every lookup reply Alice received was <size> bytes
    And Frank received no lookup request

    Examples:
      | profile      | size  |
      | classical    | 223   |
      | post-quantum | 1439  |

  Scenario: A middle node that looked the certificate up answers and the request stops there
    Given Alice to Frank are running in a classical line with links only between neighbors
    And Carol has looked up Frank's certificate
    And Alice has restarted holding no certificates
    When Alice looks up Frank's certificate
    Then Alice holds the certificate Frank proved to Eve on their link
    And Dave received no lookup request since Alice restarted

  Scenario: A non-member of a gated mesh sees no lookup frame
    Given Alice, Bob and Carol are running in a line in a classical gated mesh, and Mallory beside Bob holds another mesh key
    When Alice looks up Carol's certificate
    Then Alice holds the certificate Carol proved to Bob on their link
    And Mallory received no lookup frame
    And no lookup frame went out in the clear
