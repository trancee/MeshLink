Feature: Read receipts

  A sender can ask for a read receipt with each message. If the recipient allows read receipts and its
  app marks the message read, the recipient lists the message in a read receipt to its sender, and the
  message becomes Read. A recipient lists up to 64 messages of one sender in one read receipt, and sends
  it 2 seconds after it listed the first at the latest. A read receipt is sent once and nothing
  acknowledges it. In a secured mesh it travels in the session the two share only if the recipient
  knows the sender holds that session, and otherwise as a first message.

  Scenario Outline: Only a message that asked, read by a recipient that allows it, becomes Read
    Given Alice to Eve are running in a <profile> line, where Alice asks for read receipts by default and every node but Dave sends them
    When Alice sends "Asked" to Eve as by default, "Not asked" to Eve without asking, and "Refused" to Dave asking
    And Eve and Dave mark every message they received read
    Then Alice's message "Asked" is Read, and "Not asked" and "Refused" stay Delivered
    And Eve received "Asked" asking for a read receipt and "Not asked" not

    Examples:
      | profile      |
      | unsecured    |
      | classical    |
      | post-quantum |

  Scenario: Sixty-five messages read at once are listed in two read receipts
    Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
    And Alice's 65 messages to Eve were Delivered
    When Eve marks all 65 read at once
    Then 1.5 seconds later Alice had received 1 read receipt, and 64 of her messages were Read
    And 1 second after that Alice had received 2 read receipts, and all 65 were Read

  Rule: A secured read receipt travels in the session only once the recipient knows the sender holds it

    Scenario: Eve, who has opened only Alice's first message, sends her read receipt as a first message
      Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
      And Alice's message "Hello" to Eve was Delivered
      When Eve marks "Hello" read
      Then Alice's message "Hello" is Read, and Alice received the read receipt as a first message

    Scenario: Eve, who has opened a session frame of Alice, sends her read receipt in the session
      Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
      And Alice's messages "Hello" and "Again" to Eve were Delivered
      When Eve marks "Again" read
      Then Alice's message "Again" is Read, and Alice received the read receipt as a session frame

    Scenario: Eve, who restarted and so ended her sessions, sends her read receipt as a first message
      Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
      And Alice's messages "Hello" and "Again" to Eve were Delivered
      And Eve restarted, forgetting her sessions
      When Eve marks "Again" read
      Then Alice's message "Again" is Read, and Alice received the read receipt as a first message

    Scenario: A message whose delivery receipts were all lost becomes Read with its read receipt
      Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default, every node sends them, and Eve's delivery receipts are lost
      When Alice sends "Hello" to Eve
      And Eve marks "Hello" read
      Then Alice received no delivery receipt, and her message "Hello" went from Queued and Sent to Read
      And Alice received the read receipt as a first message

  Scenario: A message marked read after its lifetime still becomes Read
    Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
    And Alice's message "Hello" to Eve was Delivered
    When Eve marks "Hello" read 150 seconds later, after its lifetime of 120 seconds
    Then Alice's message "Hello" is Read, and Alice received the read receipt as a first message

  Scenario: Alice watches at most 1,000 delivered messages for their read receipts
    Given Alice to Eve are running in a classical line, where Alice asks for read receipts by default and every node sends them
    When Alice's 1001 messages to Bob were Delivered, and Bob read none of them
    Then Alice reported once that she stopped watching her first message for its read receipt
