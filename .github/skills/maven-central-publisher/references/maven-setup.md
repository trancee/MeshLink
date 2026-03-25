# Maven Setup

Complete configuration for publishing to Maven Central using Apache Maven and the `central-publishing-maven-plugin`.

## Table of Contents

- [Plugin setup](#plugin-setup)
- [settings.xml credentials](#settingsxml-credentials)
- [POM metadata](#pom-metadata)
- [Source and Javadoc JARs](#source-and-javadoc-jars)
- [GPG signing](#gpg-signing)
- [Complete working pom.xml](#complete-working-pomxml)
- [Plugin configuration options](#plugin-configuration-options)
- [Multi-module projects](#multi-module-projects)

## Plugin setup

Add the `central-publishing-maven-plugin` to your build:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.sonatype.central</groupId>
            <artifactId>central-publishing-maven-plugin</artifactId>
            <version>0.9.0</version>
            <extensions>true</extensions>
            <configuration>
                <publishingServerId>central</publishingServerId>
                <autoPublish>true</autoPublish>
                <waitUntil>published</waitUntil>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The `<extensions>true</extensions>` line is required — it replaces Maven's default deploy mechanism with the Central Publisher Portal upload.

### What the options do

- `publishingServerId`: References the `<server>` block in `settings.xml` where credentials are stored
- `autoPublish`: When `true`, the deployment publishes automatically after passing validation. When `false` (default), you must click "Publish" on the portal manually.
- `waitUntil`: Controls how long `mvn deploy` blocks. Set to `published` if you want the command to wait until the artifact is actually on Maven Central. Set to `validated` (default) to return after validation passes. Set to `uploaded` to return immediately after upload.

## settings.xml credentials

Your `~/.m2/settings.xml` (or wherever Maven's settings are) must contain the portal token credentials:

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username><!-- token username from central.sonatype.com/usertoken --></username>
            <password><!-- token password --></password>
        </server>
    </servers>
</settings>
```

The `<id>` must match the `<publishingServerId>` in the plugin config.

Maven also supports encrypted passwords. See the [Maven Password Encryption guide](https://maven.apache.org/guides/mini/guide-encryption.html) — the plugin supports this as of version 0.6.0.

## POM metadata

All of these elements are required. Here's a template:

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-library</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>My Library</name>
    <description>A library that does useful things</description>
    <url>https://github.com/username/my-library</url>

    <licenses>
        <license>
            <name>The Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>

    <developers>
        <developer>
            <name>Your Name</name>
            <email>you@example.com</email>
            <organization>Your Org</organization>
            <organizationUrl>https://example.com</organizationUrl>
        </developer>
    </developers>

    <scm>
        <connection>scm:git:git://github.com/username/my-library.git</connection>
        <developerConnection>scm:git:ssh://github.com:username/my-library.git</developerConnection>
        <url>https://github.com/username/my-library/tree/main</url>
    </scm>
</project>
```

For `<name>`, a common acceptable shortcut is `${project.groupId}:${project.artifactId}`.

## Source and Javadoc JARs

These are required for all non-POM packaging types.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-source-plugin</artifactId>
            <version>3.3.1</version>
            <executions>
                <execution>
                    <id>attach-sources</id>
                    <goals>
                        <goal>jar-no-fork</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-javadoc-plugin</artifactId>
            <version>3.11.2</version>
            <executions>
                <execution>
                    <id>attach-javadocs</id>
                    <goals>
                        <goal>jar</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

For Kotlin projects, replace `maven-javadoc-plugin` with `dokka-maven-plugin` to generate proper KDoc-based documentation:

```xml
<plugin>
    <groupId>org.jetbrains.dokka</groupId>
    <artifactId>dokka-maven-plugin</artifactId>
    <version>1.9.20</version>
    <executions>
        <execution>
            <phase>prepare-package</phase>
            <goals>
                <goal>javadocJar</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

If the project genuinely cannot provide meaningful documentation, a placeholder JAR with a README.md inside is accepted by the validator.

## GPG signing

Use the `maven-gpg-plugin`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-gpg-plugin</artifactId>
    <version>3.2.7</version>
    <executions>
        <execution>
            <id>sign-artifacts</id>
            <phase>verify</phase>
            <goals>
                <goal>sign</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

For CI environments where the GPG key is imported from a secret, you may need to configure the plugin to use a specific key and not prompt for a passphrase:

```xml
<configuration>
    <gpgArguments>
        <arg>--pinentry-mode</arg>
        <arg>loopback</arg>
    </gpgArguments>
</configuration>
```

And pass the passphrase via the `gpg.passphrase` system property:

```bash
mvn deploy -Dgpg.passphrase="${GPG_PASSPHRASE}"
```

## Complete working pom.xml

Here's a minimal but complete POM that will pass Maven Central validation:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/maven-v4_0_0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-library</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>com.example:my-library</name>
    <description>A library that does useful things</description>
    <url>https://github.com/username/my-library</url>

    <licenses>
        <license>
            <name>The Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>

    <developers>
        <developer>
            <name>Your Name</name>
            <email>you@example.com</email>
        </developer>
    </developers>

    <scm>
        <connection>scm:git:git://github.com/username/my-library.git</connection>
        <developerConnection>scm:git:ssh://github.com:username/my-library.git</developerConnection>
        <url>https://github.com/username/my-library/tree/main</url>
    </scm>

    <build>
        <plugins>
            <!-- Central publishing -->
            <plugin>
                <groupId>org.sonatype.central</groupId>
                <artifactId>central-publishing-maven-plugin</artifactId>
                <version>0.9.0</version>
                <extensions>true</extensions>
                <configuration>
                    <publishingServerId>central</publishingServerId>
                    <autoPublish>true</autoPublish>
                    <waitUntil>published</waitUntil>
                </configuration>
            </plugin>

            <!-- Sources -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-source-plugin</artifactId>
                <version>3.3.1</version>
                <executions>
                    <execution>
                        <id>attach-sources</id>
                        <goals><goal>jar-no-fork</goal></goals>
                    </execution>
                </executions>
            </plugin>

            <!-- Javadoc -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>3.11.2</version>
                <executions>
                    <execution>
                        <id>attach-javadocs</id>
                        <goals><goal>jar</goal></goals>
                    </execution>
                </executions>
            </plugin>

            <!-- GPG signing -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-gpg-plugin</artifactId>
                <version>3.2.7</version>
                <executions>
                    <execution>
                        <id>sign-artifacts</id>
                        <phase>verify</phase>
                        <goals><goal>sign</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

## Plugin configuration options

The `central-publishing-maven-plugin` supports these configuration options:

| Option | Default | Description |
|--------|---------|-------------|
| `publishingServerId` | `central` | References `<server>` in settings.xml |
| `autoPublish` | `false` | Auto-publish after validation passes |
| `waitUntil` | `validated` | How long to block: `uploaded`, `validated`, or `published` |
| `waitMaxTime` | `1800` | Max seconds to wait (minimum 1800) |
| `waitPollingInterval` | `5` | Seconds between status checks (minimum 5) |
| `checksums` | `all` | Checksum generation: `all`, `required` (MD5+SHA1), or `none` |
| `skipPublishing` | `false` | Create bundle but don't upload (for manual upload) |
| `deploymentName` | `Deployment` | Name shown on the portal Deployments page |
| `excludeArtifacts` | — | Skip specific artifacts by artifactId |
| `ignorePublishedComponents` | `false` | Skip already-published components (useful for multi-module) |
| `centralBaseUrl` | `https://central.sonatype.com` | Portal URL |

## Multi-module projects

For multi-module Maven projects:

- Put shared metadata (licenses, developers, SCM, plugin config) in the parent POM
- Each module inherits the publishing setup
- Use `ignorePublishedComponents: true` if you want to publish only changed modules
- Use `excludeArtifacts` to skip modules that shouldn't be published (e.g., test-only modules)

If you have a parent POM with `<packaging>pom</packaging>`, it will also be published. This is normal and expected — it allows consumers to use your BOM or parent POM.
