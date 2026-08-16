# Running the client — including in a sandbox that blocks JitPack

This is a runbook, not a blueprint. It has no section IDs and nothing validates it; it exists
because getting `game-client` to build and render has been rediscovered from scratch in three
separate sessions, and the answer is twenty minutes of work that nobody should have to find twice.

Related memory: `DISC-052` (the JitPack workaround), `DISC-024` and `DISC-046` (the two earlier and
now-superseded readings of the same problem), `DISC-051` (why running it matters).

---

## The short version

```bash
./gradlew :game-client:installDist
xvfb-run -a -s "-screen 0 1280x720x24" \
  ./game-client/build/install/syndicate-client/bin/syndicate-client \
  --start-screen garage --capture /tmp/shot.png --capture-frame 90
```

If the first line fails with a 403 from `jitpack.io`, do the JitPack workaround below first. If it
fails complaining about a Java 17 toolchain, `apt-get install -y openjdk-17-jdk-headless` — Gradle
finds it automatically once it is on disk, and the foojay resolver that would download one is
usually blocked.

---

## Why it breaks: gdx-gltf is published to JitPack only

`gdx-gltf` (DEV-001) is the glTF importer `game-client` renders through, and it has never been
published to Maven Central. The development sandbox's egress proxy denies `jitpack.io` with a 403
on CONNECT, so `:game-client:compileJava` cannot resolve it and **every other module builds fine** —
which makes it look like a client problem rather than a network one.

Whether the proxy allows JitPack varies between sessions. Do not spend time finding out; the
workaround below takes minutes and works either way.

## The workaround: build the dependency from source

The `gltf` module is about 135 Java files depending on nothing but `com.badlogicgames.gdx:gdx`,
which *is* on Maven Central and is already in the Gradle cache. `github.com` is reachable through
the session's git proxy even when `jitpack.io` is not.

```bash
WORK=/tmp/gltf                      # anywhere; nothing here is committed
git clone --depth 1 --branch 2.3.0 https://github.com/mgsx-dev/gdx-gltf.git "$WORK/src"

GDX=$(find ~/.gradle/caches/modules-2/files-2.1/com.badlogicgames.gdx/gdx -name 'gdx-*.jar' | head -1)
cd "$WORK/src/gltf"
mkdir -p "$WORK/classes"
javac -nowarn -encoding UTF-8 -source 17 -target 17 -cp "$GDX" -d "$WORK/classes" \
      $(find src -name '*.java')

# The shaders and the BRDF lookup live beside the sources and are NOT optional: without them
# every metal in the scene is wrong at grazing angles, which is most of a car's silhouette.
cd src && cp -r --parents $(find . -type f ! -name '*.java') "$WORK/classes/" && cd ..
(cd "$WORK/classes" && jar cf "$WORK/gltf-2.3.0.jar" .)

M2="$WORK/m2/com/github/mgsx-dev/gdx-gltf/gltf/2.3.0"
mkdir -p "$M2" && cp "$WORK/gltf-2.3.0.jar" "$M2/"
cat > "$M2/gltf-2.3.0.pom" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.github.mgsx-dev.gdx-gltf</groupId>
  <artifactId>gltf</artifactId>
  <version>2.3.0</version>
  <packaging>jar</packaging>
  <dependencies>
    <dependency>
      <groupId>com.badlogicgames.gdx</groupId><artifactId>gdx</artifactId>
      <version>1.14.2</version><scope>compile</scope>
    </dependency>
  </dependencies>
</project>
POM
```

Then an init script that adds the directory as a repository. **Use `--init-script` rather than
editing `settings.gradle.kts`** — this is a property of one sandbox, not of the project, and it must
not be committed:

```kotlin
// /tmp/gltf/local-gltf.init.gradle.kts
beforeSettings {
    dependencyResolutionManagement {
        repositories { maven { url = uri("/tmp/gltf/m2") } }
    }
}
```

Pass it to every Gradle invocation that touches the client:

```bash
./gradlew --init-script /tmp/gltf/local-gltf.init.gradle.kts :game-client:test check
```

Keep the version in the POM matching `gdxGltf` in `gradle/libs.versions.toml`, and the gdx version
matching `gdx`.

---

## Running it headlessly

`xvfb-run` supplies the display. Both `xvfb-run` and `Xvfb` are already installed.

```bash
xvfb-run -a -s "-screen 0 1280x720x24" \
  ./game-client/build/install/syndicate-client/bin/syndicate-client [options]
```

The client's own options, all of which exist for this:

| Option | What it does |
|---|---|
| `--capture <path>` | write a PNG and exit |
| `--capture-frame <n>` | which frame to capture on; earlier frames are simulated and drawn |
| `--start-screen <menu\|garage\|match>` | skip straight to a screen |
| `--assets <dir>` | point at any asset tree — how one build of the content is compared against another |
| `--night <0..1>` | 0 is noon, 1 is midnight; a capture cannot press `N` |
| `--garage-row <n>` | which garage row to open focused — vehicles first, then that vehicle's mountings |
| `--fit <slotId>=<weaponId>` | fits a weapon to a mounting before the first frame; repeatable, and `=none` clears one |

The capture's log line reports the screen, the tick, how many models were drawn and how many
particle quads, which is usually enough to tell a black frame from an empty one.

### Comparing two versions of the content

`--assets` is the whole trick. To see what a change did, extract the old tree beside the new one and
capture the same frame from both:

```bash
git archive HEAD~1 assets | tar -x -C /tmp/old
xvfb-run -a ... syndicate-client --assets /tmp/old/assets --capture /tmp/before.png --capture-frame 210
xvfb-run -a ... syndicate-client --assets "$PWD/assets"  --capture /tmp/after.png  --capture-frame 210
```

Pick the frame deliberately: the garage rotates the vehicle, so frame 90 is a rear three-quarter and
frame 210 is close to a side profile. A side profile is the one that shows ride height.

### Photographing a loadout

The last three options in the table exist for the same reason: **a capture has no keyboard.** Without
them the garage can only ever be photographed on its first row with the vehicle the artist armed, so
the half of the screen that moves is the half nobody can check. Together they cover it:

```bash
# The Stampede with a machine gun bracketed to each flank as well as its cannon,
# with the left-hand mounting focused so the < > affordance shows.
xvfb-run -a ... syndicate-client --start-screen garage --vehicle stampede \
  --fit hardpoint_flank_l=weapon_machinegun_l_01 --fit hardpoint_flank_r=weapon_machinegun_01 \
  --garage-row 3 --capture /tmp/garage.png --capture-frame 210

# The same loadout, driven. `--start-screen match` applies the fittings and deploys,
# because CONFIRM is a key a capture cannot press either.
xvfb-run -a ... syndicate-client --start-screen match --vehicle stampede \
  --fit hardpoint_flank_r=weapon_machinegun_01 --capture /tmp/match.png --capture-frame 240
```

The log line names the assembly that was built — `vehicle_stampede_01_fitted` rather than
`vehicle_stampede_01` — which is the quickest confirmation that a loadout took effect at all.

---

## What this is for

Every claim about how the game looks should be a capture from the real client rather than an
inference from the code. The style pass shipped a car with black discs for wheels past eleven
passing unit tests (DISC-051), and the garage drew every wheel a fifth of a metre up inside its own
arch for three sessions, because nobody had looked.

Frame rate in a capture is meaningless — `xvfb` is a software rasteriser and reports 3–5 fps on a
scene that runs fine on a GPU. Do not read it as a performance measurement.
