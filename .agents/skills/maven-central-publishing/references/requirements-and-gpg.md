# Maven Central Publishing — Requirements and GPG Reference

<pom_requirements>
## Required POM Metadata

Every artifact published to Maven Central must include a POM with these elements.

### Coordinates (GAV)

```xml
<groupId>com.example.applications</groupId>
<artifactId>example-application</artifactId>
<version>1.4.7</version>
<packaging>jar</packaging> <!-- optional if jar (default) -->
```

- **groupId**: Reverse domain you own. Hyphens allowed (`com.my-domain`). Must match a verified namespace.
- **artifactId**: Unique project name. Lowercase, dashes for separation (`maven-core`, `commons-math`).
- **version**: Semver recommended. Cannot end in `-SNAPSHOT` for releases.
- **packaging**: `jar` (default), `war`, `pom`, `aar`, `apklib`, `ear`, `rar`, `par`, `maven-plugin`, `ejb`, etc.

### Name, Description, URL

```xml
<name>Example Application</name>
<!-- OR: <name>${project.groupId}:${project.artifactId}</name> -->
<description>A library for doing X efficiently</description>
<url>https://github.com/myorg/example-application</url>
```

All three are **required**.

### Licenses

```xml
<licenses>
  <license>
    <name>The Apache License, Version 2.0</name>
    <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
  </license>
</licenses>
```

Other common licenses:

```xml
<!-- MIT -->
<license>
  <name>MIT License</name>
  <url>http://www.opensource.org/licenses/mit-license.php</url>
</license>
```

### Developers

```xml
<developers>
  <developer>
    <name>Jane Developer</name>
    <email>jane@example.com</email>
    <organization>Example Inc</organization>
    <organizationUrl>https://www.example.com</organizationUrl>
  </developer>
</developers>
```

A link to your GitHub profile is acceptable instead of a personal website.

### SCM (Source Control)

```xml
<!-- Git on GitHub -->
<scm>
  <connection>scm:git:git://github.com/myorg/myproject.git</connection>
  <developerConnection>scm:git:ssh://github.com:myorg/myproject.git</developerConnection>
  <url>http://github.com/myorg/myproject/tree/master</url>
</scm>
```

**Private URLs are acceptable** — the elements are required but the URLs don't need to be publicly accessible.

Other SCM examples:

```xml
<!-- BitBucket Git -->
<scm>
  <connection>scm:git:git://bitbucket.org/user/project.git</connection>
  <developerConnection>scm:git:ssh://bitbucket.org:user/project.git</developerConnection>
  <url>https://bitbucket.org/user/project/src</url>
</scm>

<!-- Subversion -->
<scm>
  <connection>scm:svn:http://svn.example.com/svn/project/trunk/</connection>
  <developerConnection>scm:svn:https://svn.example.com/svn/project/trunk/</developerConnection>
  <url>http://svn.example.com/svn/project/trunk/</url>
</scm>
```

### Complete Example POM

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
  <url>https://github.com/example/my-library</url>

  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>

  <developers>
    <developer>
      <name>Jane Developer</name>
      <email>jane@example.com</email>
      <organization>Example</organization>
      <organizationUrl>https://example.com</organizationUrl>
    </developer>
  </developers>

  <scm>
    <connection>scm:git:git://github.com/example/my-library.git</connection>
    <developerConnection>scm:git:ssh://github.com:example/my-library.git</developerConnection>
    <url>https://github.com/example/my-library/tree/main</url>
  </scm>
</project>
```
</pom_requirements>

<artifact_files>
## Required Artifact Files

For a non-POM artifact `example-1.0.0.jar`, you must provide:

| File | Purpose | Required |
|------|---------|----------|
| `example-1.0.0.jar` | Main artifact | Yes |
| `example-1.0.0.pom` | POM metadata | Yes |
| `example-1.0.0-sources.jar` | Source code | Yes (placeholder OK) |
| `example-1.0.0-javadoc.jar` | Documentation | Yes (placeholder OK) |
| `*.md5` | MD5 checksum (hex) | Yes, for each above |
| `*.sha1` | SHA-1 checksum (hex) | Yes, for each above |
| `*.asc` | GPG signature | Yes, for each above |
| `*.sha256`, `*.sha512` | Additional checksums | Optional |

**Placeholder JARs**: If you cannot provide real sources/javadoc, create a JAR containing a `README.md` directing users to your repository.

**Checksum files** contain the hex-encoded hash value only. `.asc` files don't need checksums, checksums don't need `.asc` signatures.
</artifact_files>

<gpg_setup>
## GPG Key Management

### Generate a Key Pair

```bash
gpg --gen-key
# Enter name, email, passphrase
# Key expires in 2 years by default
```

For full control (algorithm, expiry, comment):

```bash
gpg --full-generate-key
```

### List Keys

```bash
gpg --list-keys
# Shows keyid (40-char hex string)

gpg --list-keys --keyid-format short
# Shows last 8 chars of keyid (short ID)

gpg --list-signatures --keyid-format 0xshort
# Shows signature key IDs — needed if you have multiple keys
```

### Sign a File

```bash
gpg -ab myfile.jar
# Creates myfile.jar.asc (ASCII-armored detached signature)
```

The `-a` flag = ASCII armor, `-b` = detached signature.

### Verify a Signature

```bash
gpg --verify myfile.jar.asc
# Looks for myfile.jar in same directory
```

### Distribute Public Key

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <FULL_KEYID>
```

**Supported keyservers** (use any one):
- `keyserver.ubuntu.com`
- `keys.openpgp.org`
- `pgp.mit.edu`

SKS Keyserver Network is deprecated — do not use it.

### Import Someone's Key

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEYID>
```

### Extend an Expired Key

```bash
gpg --edit-key <KEYID>
gpg> expire          # set new expiration
gpg> save            # save changes
# Then redistribute:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
```

If the key has subkeys, also select and extend each subkey:

```bash
gpg> key 1           # select subkey
gpg> expire          # extend it
gpg> save
```

### Multiple Keys

If you have multiple keys, GPG uses the first listed signature key by default. To use a specific key:

1. Find the hex keyid: `gpg --list-signatures --keyid-format 0xshort`
2. Configure in your build tool (Maven `pom.xml` `<configuration>` section or Gradle signing plugin)

### Build Tool Integration

Most build tools have GPG signing plugins:
- **Maven**: `maven-gpg-plugin` (signs automatically during `deploy` phase)
- **Gradle**: `signing` plugin (built-in, configured in `build.gradle.kts`)

Passphrase can be passed via `gpg.passphrase` property, or GPG agent handles it interactively.
</gpg_setup>

<immutability>
## Immutability Policy

**Once published to Maven Central, artifacts cannot be modified, deleted, updated, or removed — ever.**

This is a long-standing policy. The rationale:
- Build reproducibility — builds referencing a specific version always get the same artifact
- Security — prevents supply-chain attacks via version replacement
- Trust — consumers can rely on version immutability

If you publish a broken version, publish a new corrected version instead. There is no rollback mechanism.

Deployment records visible on the Deployments tab are cleaned up after 90 days, but the published artifacts remain permanently on repo1.maven.org.
</immutability>
