Feature: Direct messaging

  Scenario: A linked peer receives a message
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    When Alice sends "hello, Bob" to Bob
    Then Bob receives "hello, Bob" from Alice

  Scenario: A stopped node cannot send a message
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    And Alice has stopped
    When Alice sends "hello, Bob" to Bob
    Then the send is rejected as NotRunning
    And Bob receives no messages

  Scenario: A message is held while the sender has no link
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    And Bob is out of direct contact with Alice
    When Alice sends "hello, Bob" to Bob
    Then Alice's message is held
    And Bob receives no messages

  Scenario: A held message reaches its recipient after the link returns
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    And Bob is out of direct contact with Alice
    And Alice has sent "hello, Bob" while disconnected
    When Bob returns to direct contact with Alice
    Then Bob receives "hello, Bob" from Alice
    And Alice's message is sent

  Scenario: An expired message is not delivered when the link returns
    Given Alice and Bob are running in the same mesh
    And Alice and Bob are directly linked
    And Bob is collecting received messages
    And Bob is out of direct contact with Alice
    And Alice has sent "hello, Bob" while disconnected
    And Alice's held message has expired
    When Bob returns to direct contact with Alice
    Then Alice and Bob are directly linked again
    And Alice's message remains expired
    And Bob receives no messages
