# GPG Details

Advanced GPG topics for Maven Central publishing.

## Table of Contents

- [Sub-key issues](#sub-key-issues)
- [Key expiration and renewal](#key-expiration-and-renewal)
- [Exporting keys for CI](#exporting-keys-for-ci)
- [Supported key servers](#supported-key-servers)
- [Troubleshooting signature failures](#troubleshooting-signature-failures)

## Sub-key issues

GPG can create sub-keys with specific purposes. If you have a sub-key with Signing (`S`) usage, GPG will use it by default instead of the primary key. This can cause Maven Central to reject the signature because the verifier may only check against primary keys on the key server.

To check if you have a signing sub-key:

```bash
gpg --edit-key <KEY_ID>
```

Look for a line like:
```
ssb  rsa3072/01265B6DAB6DEA96  created: 2021-06-24  usage: S
```

The `S` in `usage: S` means it's a signing sub-key. To fix this, you can either:

1. **Delete the signing sub-key** (if you don't need it for other purposes):
   ```
   gpg> key 2       # select the sub-key (adjust index)
   gpg> delkey       # delete it
   gpg> save
   ```

2. **Revoke the signing sub-key** (better if the key has already been distributed):
   ```
   gpg> key 2
   gpg> revkey
   gpg> save
   ```

After removing/revoking, re-distribute your public key:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### Using a specific key in Maven

If you have multiple keys and need Maven to use a particular one, configure the `maven-gpg-plugin`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-gpg-plugin</artifactId>
    <configuration>
        <keyname>0x0ABA0F98</keyname>
    </configuration>
</plugin>
```

Use the hexadecimal short ID (find it with `gpg --list-signatures --keyid-format 0xshort`).

## Key expiration and renewal

GPG keys have an expiration date (default: 2 years). When a key expires, you can extend it without generating a new key:

```bash
gpg --edit-key <KEY_ID>
gpg> expire
# Enter new validity period (e.g., "2y" for 2 years)
gpg> save
```

After extending, re-distribute the updated public key:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

You do NOT need to re-sign previously published artifacts. The key ID stays the same — only the expiration date changes.

## Exporting keys for CI

### Full private key export

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

Store the output as a CI secret. In GitHub Actions with `setup-java`:

```yaml
- uses: actions/setup-java@v4
  with:
    gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
```

### Base64 export (for Gradle in-memory signing)

```bash
gpg --armor --export-secret-keys <KEY_ID> | base64 -w 0
```

The `-w 0` prevents line wrapping. Store as a CI secret and pass as `signingInMemoryKey`.

### Importing in CI without setup-java

If not using `setup-java`, import the key manually:

```bash
echo "$GPG_PRIVATE_KEY" | gpg --batch --import
```

For non-interactive signing, use `--pinentry-mode loopback`:

```bash
export GPG_TTY=$(tty)
gpg --batch --pinentry-mode loopback --passphrase "$GPG_PASSPHRASE" ...
```

## Supported key servers

Maven Central verifies signatures against these key servers:

- `keyserver.ubuntu.com` (recommended — most reliable)
- `keys.openpgp.org`
- `pgp.mit.edu`

The SKS Keyserver Network is deprecated and should not be used.

After uploading your key, verify it's available:
```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEY_ID>
```

Key propagation can take a few minutes.

## Troubleshooting signature failures

| Problem | Cause | Fix |
|---------|-------|-----|
| "No public key" on portal | Key not on a supported server | `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>` |
| Signature check fails | Sub-key used for signing | Remove/revoke signing sub-key (see above) |
| "BAD signature" | Key mismatch or corrupt upload | Re-sign and re-upload; check that `gpg --verify` works locally |
| "gpg: signing failed: No secret key" in CI | Key not imported | Add `gpg --batch --import` step in CI |
| "gpg: signing failed: Inappropriate ioctl" | No TTY in CI | Use `--pinentry-mode loopback` and pass passphrase via `--passphrase` |
