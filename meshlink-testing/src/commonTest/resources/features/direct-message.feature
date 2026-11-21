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
