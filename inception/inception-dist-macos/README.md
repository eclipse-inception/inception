# inception-dist-macos

Builds signed (and optionally notarized) macOS DMG installers of INCEpTION for
`aarch64` and `x86_64`, using `jpackage` from a target-arch JDK.

The module is opt-in: it does nothing by default. You activate it with the
`dist-macos` profile, and optionally also `release-macos` to notarize and
staple the resulting DMGs.

## Prerequisites

- Must be run on macOS.
- An Apple **Developer ID Application** certificate installed in your login
  keychain. The common-name string (e.g. `Developer ID Application: Jane Doe
  (TEAMID)`) is what you pass as `mac.signing.identity`.
- A JDK per target architecture you want to build for. `jpackage` cannot
  cross-build: an `aarch64` DMG requires an `aarch64` JDK, and an `x86_64`
  DMG requires an `x86_64` JDK. If only one is configured, the other arch is
  skipped with a warning (native-arch builds fall back to `JAVA_HOME`).
- For notarization (`release-macos` only): a `notarytool` keychain profile,
  created once with an app-specific password:

  ```
  xcrun notarytool store-credentials AC_NOTARY \
      --apple-id <your-apple-id> \
      --team-id  <your-team-id> \
      --password <app-specific-password>
  ```

  `AC_NOTARY` is just a name — use whatever you like and pass it as
  `mac.notarization.keychain.profile`.

## Properties

Configure these in `~/.m2/settings.xml` (recommended) or pass them on the
command line with `-D…`.

| Property                            | Required for          | Description |
|-------------------------------------|-----------------------|-------------|
| `mac.signing.identity`              | `dist-macos`          | Developer ID Application common name used to codesign native libs, the `.app`, and the DMG. |
| `jdk.aarch64.home`                  | `dist-macos` (aarch64)| Path to an aarch64 JDK. If unset and the host is aarch64, falls back to `JAVA_HOME`. |
| `jdk.x86_64.home`                   | `dist-macos` (x86_64) | Path to an x86_64 JDK. If unset and the host is x86_64, falls back to `JAVA_HOME`. |
| `mac.notarization.keychain.profile` | `release-macos`       | `notarytool` keychain profile name created via `xcrun notarytool store-credentials`. |

Example `~/.m2/settings.xml` snippet:

```xml
<settings>
  <profiles>
    <profile>
      <id>inception-macos</id>
      <properties>
        <mac.signing.identity>Developer ID Application: Jane Doe (TEAMID)</mac.signing.identity>
        <jdk.aarch64.home>/Library/Java/JavaVirtualMachines/temurin-25-aarch64/Contents/Home</jdk.aarch64.home>
        <jdk.x86_64.home>/Library/Java/JavaVirtualMachines/temurin-25-x86_64/Contents/Home</jdk.x86_64.home>
        <mac.notarization.keychain.profile>AC_NOTARY</mac.notarization.keychain.profile>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>inception-macos</activeProfile>
  </activeProfiles>
</settings>
```

## Building

Run from the repo root (or from this module after a full `mvn install` of the
webapp).

### Signed DMGs, no notarization

Produces signed DMGs that run locally for the signer, but Gatekeeper on other
machines will block them until notarization is added.

```
mvn -pl inception/inception-dist-macos -am package -Pdist-macos
```

Or, if the properties are not in `settings.xml`:

```
mvn -pl inception/inception-dist-macos -am package -Pdist-macos \
    -Dmac.signing.identity="Developer ID Application: Jane Doe (TEAMID)"
```

Outputs:

- `target/jpackage-aarch64/eclipse-inception-incubation-<version>-aarch64.dmg`
- `target/jpackage-x86_64/eclipse-inception-incubation-<version>-x86_64.dmg`

(only the arches with a configured JDK are produced)

### Signed and notarized DMGs

Adds an Apple notarization round-trip and staples the ticket so the DMG works
on any Mac with Gatekeeper enabled.

```
mvn -pl inception/inception-dist-macos -am verify -Pdist-macos,release-macos
```

This runs the `dist-macos` packaging in the `package` phase, then in the
`verify` phase for each produced DMG:

1. `xcrun notarytool submit --wait` — uploads and waits for Apple to accept.
2. `xcrun stapler staple` — attaches the ticket to the DMG.
3. `xcrun stapler validate` — sanity-checks the staple.
4. `spctl --assess -t install -vv` — confirms Gatekeeper accepts it.

Notarization typically takes 1–10 minutes per DMG.

## What gets signed

1. Native libraries (`.dylib`, `.jnilib`) inside dependency JARs are extracted,
   codesigned with the hardened runtime and a secure timestamp, and zipped
   back into the JARs. Foreign natives (`.so`, `.dll`, …) in those same JARs
   are stripped to slim the bundle. See
   [process-native-libs.sh](src/main/scripts/process-native-libs.sh).
2. `jpackage --mac-sign` signs the `.app` bundle inside the DMG.
3. The DMG container itself is signed with a Developer ID signature and
   secure timestamp. See [package-dmg.sh](src/main/scripts/package-dmg.sh).
4. With `release-macos`, the DMG is then notarized and stapled. See
   [notarize-dmg.sh](src/main/scripts/notarize-dmg.sh).
