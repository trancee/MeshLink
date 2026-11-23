Feature: Delivery receipts

  A destination returns a delivery receipt for each message its app takes. The receipt travels back
  to the sender, which then reports the message as delivered and stops sending it. A direct-only
  message travels only over a link to its destination, never through another node.

  Background:
    Given Alice, Bob, Carol and Dave are running in a line with links only between neighbors
    And Alice knows Dave's identity

  Rule: A message is delivered once its destination's app takes it

    Scenario: A routed message is delivered when its destination takes it
      Given Bob, Carol and Dave are collecting messages
      When Alice sends "Hello" to Dave through the mesh
      Then Dave receives the direct message "Hello" from Alice once
      And Alice sees "Hello" delivered

    Scenario: A message is not delivered until its destination's app takes it
      Given Alice has sent "Hello" to Dave while Dave's app takes no messages
      When Dave's app starts taking messages
      Then Dave receives the direct message "Hello" from Alice once
      And Alice sees "Hello" delivered

  Rule: A direct-only message travels only over a link to its destination

    Scenario: A direct-only message is held while its destination is reachable only through other nodes
      Given Bob, Carol and Dave are collecting messages
      When Alice sends "Hello" directly to Dave
      Then Alice sees "Hello" held
      And Bob, Carol and Dave receive no direct messages

    Scenario: A held direct-only message is delivered once its destination links
      Given Bob, Carol and Dave are collecting messages
      And Alice has sent "Hello" directly to Dave
      When Dave moves next to Alice
      Then Dave receives the direct message "Hello" from Alice once
      And Alice sees "Hello" delivered

    Scenario: A direct-only message expires if no link to its destination forms
      Given Bob, Carol and Dave are collecting messages
      And Alice has sent "Hello" directly to Dave
      When the lifetime of Alice's messages passes
      Then Alice sees "Hello" expired
      And Bob, Carol and Dave receive no direct messages
