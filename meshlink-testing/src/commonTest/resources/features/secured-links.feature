Feature: Secured links

  In a classical or post-quantum mesh, two linked nodes keep exchanging messages while their link
  moves from GATT to an L2CAP channel, and while each direction of the link changes its key every
  hour. Every message is delivered once and in the order it was sent.

  Scenario Outline: Messages sent before and after a link moves to an L2CAP channel arrive once each, in order
    Given Alice and Bob are linked in a <profile> mesh, Alice's L2CAP channel to Bob opening 2 seconds after the link
    When Alice sends a message to Bob before the channel opens, and 3 more once the link moved to it
    Then Bob received Alice's 4 messages once each, in order, and each was Delivered
    And Bob received the first while the link was on GATT, and Alice wrote the other 3 on the L2CAP channel

    Examples:
      | profile      |
      | classical    |
      | post-quantum |

  Scenario Outline: Messages sent before and after each hourly rekey arrive once each, in order
    Given Alice and Bob are linked in a <profile> mesh
    When Alice sends a message to Bob, and one more a minute after each of the next 3 hourly rekeys
    Then Bob received Alice's 4 messages once each, in order, and each was Delivered
    And Alice and Bob each rekeyed their direction of the link 3 times, and the link stayed open

    Examples:
      | profile      |
      | classical    |
      | post-quantum |
