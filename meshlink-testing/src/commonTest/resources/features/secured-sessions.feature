Feature: Secured sessions

  In a classical or post-quantum mesh, a node's first message to a peer opens an end-to-end session,
  which the peer answers in its delivery receipt. Later messages and receipts between the two travel
  as session frames to conversation tags, which only the two can link. A session ends after 6 hours,
  after 4,096 frames in either direction, or when a frame sent with it gets no receipt by its second
  retry; the next message then opens a new one.

  Scenario Outline: Ten messages open one session and each is delivered once
    Given Alice to Eve are running in a <profile> line with links only between neighbors
    When Alice sends 10 messages to Eve, one every 5 seconds
    Then each of Alice's 10 messages was Delivered, and Eve received each once
    And Dave relayed the first as a first message to Eve, and the other 9 as session frames to one conversation tag

    Examples:
      | profile      |
      | classical    |
      | post-quantum |

  Scenario: A first message passed to Eve again gets the same answer
    Given Alice to Eve are running in a classical line with links only between neighbors
    And a stranger is linked to Eve
    And Alice's message "Hello" to Eve was Delivered
    When the stranger passes Alice's first message to Eve again
    Then Eve answered both copies with the same session answer, byte for byte
    And Eve receives "Hello" from Alice once

  Scenario: Sessions opened from both ends settle on the one the lower NodeId opened
    Given Alice to Eve are running in a classical line with links only between neighbors
    When Alice sends "Hi Eve" to Eve and Eve sends "Hi Alice" to Alice at the same moment
    And then Alice sends "Again, Eve" to Eve and Eve sends "Again, Alice" to Alice
    Then Alice and Eve each sent a first message, and all four messages were Delivered
    And both later messages traveled in the session that the lower NodeId of Alice and Eve opened

  Scenario: A session ends after 6 hours and the next message opens a new one
    Given Alice to Eve are running in a classical line with links only between neighbors
    And Alice's message "Hello" to Eve was Delivered
    When Alice sends "Before" to Eve 5 hours 59 minutes after "Hello", and "After" 6 hours 1 minute after it
    Then Dave relayed "Before" as a session frame and "After" as a first message
    And Alice's messages "Before" and "After" were Delivered

  Scenario: A session ends after 4,096 frames and the next message opens a new one
    Given Alice to Eve are running in a classical line with links only between neighbors
    When Alice sends 4098 messages to Eve, one every second
    Then each of Alice's 4098 messages was Delivered, and Eve received each once
    And Dave relayed the first and the last as first messages, and the 4096 between them as session frames

  Scenario: A session Eve forgot is replaced by a first message after the second retry
    Given Alice to Eve are running in a classical line with links only between neighbors
    And Alice's message "Hello" to Eve was Delivered
    And Eve restarted, forgetting her sessions
    When Alice sends "Are you there?" to Eve
    Then Dave relayed "Are you there?" as session frames, then as a first message with a new message ID
    And Alice's message "Are you there?" was Delivered, and Eve received it once

  Scenario: Messages keep arriving while the clocks of Alice and Eve are in different epochs
    Given Alice to Eve are running in a classical line with links only between neighbors, Eve's clock 20 seconds ahead
    When Alice sends 60 messages to Eve, one every 10 seconds
    Then each of Alice's 60 messages was Delivered, and Eve received each once
    And Dave relayed the session frames to 3 conversation tags, one for each epoch of Alice's clock

  Rule: A node drops a session frame it cannot accept, and its sessions are unchanged

    Scenario Outline: A forged frame passed on through the mesh is dropped
      Given Alice to Eve are running in a classical line with links only between neighbors
      And a stranger is linked to Carol
      And Alice's messages "Hello" and "Again" to Eve were Delivered
      When the stranger passes on <forgery>
      Then Alice and Eve received nothing more, and Dave relayed no more receipts
      And Alice's next message "Later" to Eve travels in the same session and is Delivered

      Examples:
        | forgery                                                                  |
        | Alice's message "Again" again, under a new message ID                    |
        | Alice's message "Again" with its last byte changed, under a new message ID |
        | Eve's answer to "Hello" acknowledging another message ID instead         |

    Scenario: A session frame far below the replay window is dropped
      Given Alice to Eve are running in a classical line with links only between neighbors
      And a stranger is linked to Carol
      And Alice's 1100 messages to Eve, one every 50 milliseconds, were Delivered
      When the stranger passes on Alice's second message again, under a new message ID
      Then Alice and Eve received nothing more, and Dave relayed no more receipts
      And Alice's next message "Later" to Eve travels as a session frame and is Delivered
