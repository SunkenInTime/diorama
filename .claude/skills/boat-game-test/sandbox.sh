#!/bin/bash
# Runs on a boat.dev VM, started by boat_run.py: installs what's missing, copies the pushed tree
# from ~/src over ~/diorama (keeping its Gradle caches) unless boat_run.py already brought ~/diorama
# up to date with just the changed files, runs one node's client game tests with software
# rendering, and packs the results into ~/result.tgz. Its output goes to ~/sandbox.log and its exit
# code to ~/run-exit, which boat_run.py polls for.
# usage: sandbox.sh <node> <tests|all> [KEY=VALUE...]
set -u
# One run per VM: a start the API reported as failed may have started after all.
exec 9>~/sandbox.lock
flock -n 9 || exit 75
rm -f ~/run-exit
touch ~/run-started
exec > ~/sandbox.log 2>&1
trap 'echo $? > ~/run-exit' EXIT
node=$1
tests=$2
shift 2
# Lines of this log and of gradle.log start with the time; boat_run.py reads the run's phases off them.
stamp() { while IFS= read -r line; do printf '%(%T)T %s\n' -1 "$line"; done; }
mark() { printf '%(%T)T %s\n' -1 "$*"; }
mark "started"

if ! dpkg -s xvfb xauth libgl1-mesa-dri mesa-vulkan-drivers rsync >/dev/null 2>&1; then
	echo "installing packages"
	sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
	sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb xauth libgl1-mesa-dri libglx-mesa0 libegl1 \
		mesa-vulkan-drivers rsync libxrandr2 libxi6 libxcursor1 libxxf86vm1 libxtst6 libxkbcommon-x11-0 \
		libasound2t64 libpulse0 libdecor-0-0 >/dev/null || exit 1
fi
if [ ! -x ~/jdk25/bin/java ]; then
	echo "installing JDK 25"
	curl -sSfL https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-linux_x64.tar.gz | tar xz -C ~ || exit 1
	mv ~/zulu25.36.205-ca-jdk25.0.4.1-linux_x64 ~/jdk25
fi

mkdir -p ~/diorama
if [ -d ~/src ]; then
	rsync -a --delete --exclude .git --exclude .gradle --exclude build --exclude run --exclude logs ~/src/ ~/diorama/ || exit 1
fi
cd ~/diorama || exit 1

export JAVA_HOME=~/jdk25 GRADLE_OPTS=-Dorg.gradle.daemon=false
# Gradle's own heap is set in gradle.properties; this caps the game at 40% of the VM's memory.
export JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40
# Mesa's CPU renderer, OpenGL as on Dara's PC. Minecraft 26.x asks SDL for an sRGB window, which
# Xvfb's GLX visuals lack and EGL has; without this it falls back to Vulkan, which is no faster here.
# Versions without SDL ignore the setting.
export LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1
if [ "$tests" != all ]; then
	export DIORAMA_TESTS=$tests
fi
for pair in "$@"; do
	export "$pair"
done

# A real dedicated server for the tests to join (RealServer.java): the node's release jar first.
if [ -n "${DIORAMA_REAL_SERVER:-}" ]; then
	mark "building the release jar for the real server"
	# Only this build's jar is left for real_server.sh to find.
	rm -f "versions/$node/build/libs/"diorama-*.jar
	bash ./gradlew --no-daemon --console=plain --configuration-cache --configuration-cache-problems=warn ":$node:assemble" 2>&1 | stamp > ~/assemble.log
	if [ "${PIPESTATUS[0]}" != 0 ]; then
		tail -60 ~/assemble.log
		exit 1
	fi
	export DIORAMA_REAL_SERVER=~/diorama/.claude/skills/boat-game-test/real_server.sh DIORAMA_NODE=$node
	rm -rf ~/real-servers/$node/console-*.log ~/real-servers/$node/world
fi

# Other mods' release jars beside Diorama: boat_run.py --mods leaves ~/mods.tgz, holding jars and
# urls.txt (name, tab, URL) for the ones to download. How they load depends on the node, and
# mods.init.gradle.kts says why.
task=runClientGameTest
dev=versions/$node/build/run/clientGameTest
run=$dev
with_mods=()
mods=~/test-mods
rm -rf "$mods"
if [ -e ~/mods.tgz ]; then
	mkdir -p "$mods" ~/mod-cache && tar xzf ~/mods.tgz -C "$mods" || exit 1
	while IFS=$'\t' read -r name url; do
		# A download stays on the VM for its next runs.
		cached=~/mod-cache/$(printf %s "$url" | sha1sum | cut -c1-12)-$name
		if [ ! -s "$cached" ]; then
			curl -sSfL --retry 3 -o "$cached.part" "$url" && mv "$cached.part" "$cached" || exit 1
		fi
		cp "$cached" "$mods/$name" || exit 1
	done < "$mods/urls.txt"
	rm "$mods/urls.txt"
	mark "other mods: $(ls "$mods" | tr '\n' ' ')"
	case "$node" in
		*-neoforge) ;;
		*-forge) with_mods=(-I .claude/skills/boat-game-test/mods.init.gradle.kts) ;;
		1.*)
			with_mods=(-I .claude/skills/boat-game-test/mods.init.gradle.kts)
			task=runProductionClientGameTest
			run=versions/$node/build/run-production
			# The development run gets this from stonecutter.gradle.kts.
			export DIORAMA_GPU_BAKE=${DIORAMA_GPU_BAKE:-0}
			# A mod that can't run on the VM's JDK 25 (Cobblemon on Fabric: its battle engine stops
			# at start) gets the Java it asks for: DIORAMA_TEST_JAVA=21, from Ubuntu's packages.
			# mods.init.gradle.kts hands it to the production run.
			if [ -n "$DIORAMA_TEST_JAVA" ] && [ ! -x "/usr/lib/jvm/java-$DIORAMA_TEST_JAVA-openjdk-amd64/bin/java" ]; then
				(sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "openjdk-$DIORAMA_TEST_JAVA-jdk") > ~/apt-java.log 2>&1 || { tail -20 ~/apt-java.log; exit 1; }
				mark "installed Java $DIORAMA_TEST_JAVA for the game"
			fi
			;;
		*) with_mods=(-I .claude/skills/boat-game-test/mods.init.gradle.kts) ;;
	esac
	export DIORAMA_TEST_MODS=$mods
fi
rm -rf "$dev/screenshots" "$dev/crash-reports" "$dev/logs" "$run/screenshots" "$run/crash-reports" "$run/logs" ~/result ~/result.tgz ~/thread-dumps
# The game starts from the folder the snapshot left (its settings, options and map folders), on a
# warm VM an earlier run left too: otherwise a test that stopped partway leaves the settings it
# changed, and the next run's tests start from them. The first run on a VM keeps a copy to go back to.
pristine=~/run-pristine/$node
if [ -d "$pristine" ]; then
	rsync -a --delete "$pristine/" "$dev/" || exit 1
	mark "game folder reset to this VM's first run ($(du -sh "$dev" | cut -f1))"
else
	mkdir -p "$dev" "$pristine" && rsync -a "$dev/" "$pristine/" || exit 1
	mark "game folder kept to reset to on the next run ($(du -sh "$pristine" | cut -f1))"
fi
if [ "$run" != "$dev" ]; then
	# The production run has a game folder of its own, which starts empty every time.
	rm -rf "$run" && mkdir -p "$run" || exit 1
elif [ -e ~/mods.tgz ] && [ ${#with_mods[@]} = 0 ]; then
	# NeoForge. The reset above takes them out again before the next run.
	mkdir -p "$run/mods" && cp "$mods"/*.jar "$run/mods/" || exit 1
fi
start=$(date +%s)
# A game that stops writing its log for HANG_DUMP_SECONDS (default 180) is probably stuck: its
# threads are dumped then, and twice more at the same spacing while it stays quiet, into
# thread-dumps/ in the results. A hang that ends in a test timeout minutes later would otherwise
# leave nothing to tell where it stuck.
watch_hang() {
	# Not holding the run's lock: a sleep left over after the run would keep the next run out.
	exec 9>&-
	local quiet=${HANG_DUMP_SECONDS:-180} dumps=0 log="$run/logs/latest.log" last=0
	while [ $dumps -lt 3 ]; do
		sleep 15
		[ -e "$log" ] || continue
		local changed now
		changed=$(stat -c %Y "$log")
		now=$(date +%s)
		if [ $((now - changed)) -ge "$quiet" ] && [ $((now - last)) -ge "$quiet" ]; then
			mkdir -p ~/thread-dumps
			for pid in $(pgrep -u "$(id -u)" java); do
				# The game, not Gradle.
				if tr '\0' ' ' < /proc/$pid/cmdline | grep -q -E 'GradleDaemon|GradleMain|GradleWorkerMain|GradleWrapperMain'; then
					continue
				fi
				local out=~/thread-dumps/$(date +%H%M%S)-$pid.txt
				timeout 60 ~/jdk25/bin/jcmd "$pid" Thread.print -l > "$out" 2>&1 || kill -QUIT "$pid"
			done
			mark "game log quiet for $((now - changed)) s: threads dumped"
			dumps=$((dumps + 1))
			last=$now
		fi
	done
}
watch_hang &
watcher=$!
mark "running :$node:$task (tests: $tests)"
# 90 min at most; boat_run.py passes TEST_SECONDS, what its own deadline leaves after setup. The
# configuration cache skips configuring the build when a VM runs the same node again (about 15 s);
# its problems are only warnings, so a build that can't use it still runs.
timeout --kill-after=60 "${TEST_SECONDS:-5400}" xvfb-run -a -s '-screen 0 1920x1080x24' \
	bash ./gradlew --no-daemon --console=plain --configuration-cache --configuration-cache-problems=warn \
	"${with_mods[@]}" ":$node:$task" 2>&1 | stamp > ~/gradle.log
code=${PIPESTATUS[0]}
kill $watcher 2>/dev/null
mark "gradle finished"
minutes=$(( ($(date +%s) - start) / 60 ))

# Any failure packing the results fails the script, so the run isn't reported without its evidence.
mkdir -p ~/result || exit 1
cp ~/gradle.log ~/sandbox.log ~/result/ || exit 1
if [ -d ~/thread-dumps ]; then
	cp -r ~/thread-dumps ~/result/ || exit 1
fi
if [ -n "${DIORAMA_REAL_SERVER:-}" ]; then
	bash "$DIORAMA_REAL_SERVER" stop "$node"
	mkdir -p ~/result/real-server
	cp ~/real-servers/$node/console-*.log ~/result/real-server/ 2>/dev/null
	cp ~/real-servers/$node/install.log ~/result/real-server/ 2>/dev/null
fi
for part in screenshots crash-reports logs/latest.log; do
	if [ -e "$run/$part" ]; then
		cp -r "$run/$part" ~/result/ || exit 1
	fi
done
echo "{\"exit\": $code, \"minutes\": $minutes}" > ~/result/result.json || exit 1
tar czf ~/result.tgz -C ~/result . || exit 1
echo "exit=$code after $minutes min"
