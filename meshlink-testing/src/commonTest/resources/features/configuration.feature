Feature: Node configuration

  Scenario: An empty mesh ID is rejected
    When a configuration with an empty mesh ID is built
    Then the configuration is rejected for meshId
