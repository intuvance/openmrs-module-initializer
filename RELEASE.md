# Releasing to Maven Central

This fork publishes to **Sonatype Central Portal** under the `io.github.intuvance`
namespace. Nothing is published by pushing a tag: the build produces a bundle that
is uploaded to the Portal, and a human then releases it.

## What gets published

All nine reactor modules, because `initializer-omod` depends on the eight
`initializer-api*` artifacts and Maven resolves those transitively even when the
omod is declared `provided` by a consumer:

```
io.github.intuvance:initializer:2.12.1-intuvance.1
io.github.intuvance:initializer-api:2.12.1-intuvance.1
io.github.intuvance:initializer-api-2.2 / -2.3 / -2.4 / -2.5 / -2.7 / -2.8
io.github.intuvance:initializer-api-bahmni:2.12.1-intuvance.1
io.github.intuvance:initializer-omod:2.12.1-intuvance.1
```

The `validator` and `validator-first-dependency` modules are behind the
`validator` profile and are **not** published. Do not add `-P validator` to a
release build unless that changes.

`initializer-omod-2.12.1-intuvance.1.jar` **is** the `.omod` — a Maven artifact
and an OpenMRS module at the same time. That is `maven-openmrs-plugin` with
`primaryArtifact=true` (its default) rewriting the project's main artifact. The
thin `omod/target/initializer-<version>.jar` left behind in `target/` is an
intermediate and is not what gets installed. If the installed jar is ever a few
kilobytes rather than ~2 MB, the packaging plugin did not take the omod and the
release is not usable — check that before uploading.

## Prerequisites

**JDK 8.** Not a preference. The module inherits `powermock-api-mockito` 1.6.1 and
Mockito 1.x from the OpenMRS parent, whose bundled cglib cannot initialise on a
modern JDK. On JDK 17 every test in the suite errors with
`ExceptionInInitializerError: ClassImposterizer`, which looks like a broken test
but is the test framework failing to start.

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
```

**A GPG signing key.** Central rejects unsigned artifacts.

```bash
gpg --full-generate-key          # RSA 4096, no expiry
gpg --list-secret-keys --keyid-formatter LONG
gpg --armor --export-secret-keys <KEYID> > /tmp/secret.asc   # back this up
```

The plugin cannot prompt for a passphrase non-interactively unless gpg-agent is
configured for loopback pinentry:

```
pinentry-program /usr/bin/pinentry-tty
allow-loopback-pinentry
```

in `~/.gnupg/gpg-agent.conf`.

**A Central Portal user token.** Generate one at
<https://central.sonatype.com/publishing/deploy> and base64 it. Store it in
`~/.m2/settings.xml`, never in this repository:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>intuvance</username>
      <password>BASE64_TOKEN</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>central</id>
      <gpgArguments>
        <arg>--pinentry-mode</arg>
        <arg>loopback</arg>
      </gpgArguments>
    </profile>
  </profiles>
</settings>
```

The `central` **server id** must match `publishingServerId` in the root `pom.xml`,
and the `central` **profile id** activates those GPG arguments.

## Release

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

# 1. Branch off the upstream release, bump the version, update the README note.
git switch -c intuvance/2.12.1-intuvance.2
#    ... edit <version> in pom.xml and every */pom.xml parent block ...
#    ... add a release-notes entry ...

# 2. Prove the patch is doing something: the new tests must fail without it.
mvn -pl api clean test -Dtest=MappingsConceptLineProcessorTest
#    expected: Tests run: 10, Failures: 0, Errors: 0

# 3. Full suite, then publish.
#    -Ppublish, NOT -Prelease. The parent pom has its own profile called `release`
#    that attaches a second sources jar, and Maven merges the two `attach-sources`
#    executions into one with two goals, failing the build with "Presumably you
#    have configured maven-source-plugin to execute twice". Naming this profile
#    `publish` keeps the parent's `release` dormant. See the comment on
#    maven-source-plugin in pom.xml.
mvn clean install
mvn -Ppublish clean deploy

# 4. Release the bundle.
#    central-publishing-maven-plugin is configured with <autoPublish>false</autoPublish>,
#    so the upload stops at a bundle waiting in the Portal. Go to
#    https://central.sonatype.com/publishing/deployments , check the validation
#    results, then publish it.

# 5. Tag and push.
git tag -s 2.12.1-intuvance.1 -m "Intuvance 2.12.1-intuvance.1"
git push origin intuvance/preserve-concept-mappings --tags
```

Step 2 matters more than it looks. `MappingsConceptLineProcessorTest` contains five
tests that fail against unpatched upstream and two that pass either way; that
asymmetry is what proves the fix works without regressing the "the CSV wins"
behaviour. If someone ever reverts the patch and the suite still goes green, the
tests have been weakened.

## Verifying a published artifact

The single most important check, because the failure is silent. Confirm the
published `initializer-omod` carries the patch:

```bash
curl -sO https://repo1.maven.org/maven2/io/github/intuvance/initializer-omod/2.12.1-intuvance.1/initializer-omod-2.12.1-intuvance.1.jar
unzip -p initializer-omod-2.12.1-intuvance.1.jar config.xml | grep -E '<id>|<version>'
#   <id>initializer</id>          <- must be "initializer", or it will not replace upstream
#   <version>2.12.1-intuvance.1</version>

unzip -o initializer-omod-2.12.1-intuvance.1.jar 'lib/initializer-api-2.12.1-intuvance.1.jar'
unzip -o lib/initializer-api-2.12.1-intuvance.1.jar \
      'org/openmrs/module/initializer/api/c/MappingsConceptLineProcessor.class'
```

Compare the resulting class's checksum against one built locally from the same
source; they must match. Comparing call-site counts of `Collection.clear` does
**not** work — the patched class still clears, it just does so only after it knows
the line declared something.

## Versioning

The version is `2.12.1-intuvance.N`.

The `-intuvance` qualifier is deliberate. OpenMRS prints each module's id and
version at startup, and without the qualifier a patched module reports itself as
plain `2.12.1` — leaving no way to tell from the logs which Initializer is
actually running. It also sorts after upstream `2.12.1`, so a consumer pinning a
range gets the fork only when it asks for it.

Do not change the **artifactIds**. `omod/src/main/resources/config.xml` filters the
OpenMRS module id from `${project.parent.artifactId}`, so renaming `initializer`
would change the installed module id and leave two Initializer modules on the
classpath — a worse failure than the bug this fork fixes, because whichever one the
classloader resolves wins silently.

## Staying current with upstream

The branch is based on upstream release `2.12.1`, not on upstream `main`.

```bash
git fetch upstream
git rebase upstream/2.12.2        # or the equivalent release tag
```

Expect the patch to apply cleanly, since it only touches the bodies of four
`fill()` methods. If upstream ever changes the grammar for any of them, revisit
the fix: it assumes that a line declaring nothing is not asking for the existing
terminology to be emptied. That assumption is also the one capability this fork
gives up, since the upstream `clear()` was the only way a CSV could express
deleting a mapping, a set member, an answer or a description.
