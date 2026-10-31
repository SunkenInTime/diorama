"""Run Diorama's client game tests on boat.dev VMs: software rendering, no GPU. See SKILL.md.

usage: python boat_run.py <node[,node...]> --tests <sections|all> [--type default|large] [--env KEY=VALUE]...
                           [--warm-minutes N] [--keep]

Each node gets its own VM, started at the same time from the newest warmed snapshot, or the VM an
earlier run from this checkout left warm. The script sends this working tree (uncommitted changes
included), runs :<node>:runClientGameTest, copies the results to build/boat-runs/<time>-<node>/,
and deletes the VM or leaves it warm for the next run. Exits non-zero if any node failed.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import threading
import time
from pathlib import Path

SNAPSHOT_PREFIX = 'diorama-gametest-'
HERE = Path(__file__).resolve().parent
SANDBOX_SH = '/home/user/src/.claude/skills/boat-game-test/sandbox.sh'
TTL = 7200
# How long a VM stays warm after a run, by default, and how many a run keeps: see WARM_PREFIX.
# An idle half hour costs less than the start it saves.
WARM_MINUTES = 30
WARM_NODES = 8
# SKILL.md gives the command 2 hours. sandbox.sh stops the tests at 90 min; every boat call is cut
# off at 105 min so fetching results fits, and cleanup has the rest.
WORK_MINUTES = 105
# A new VM fetches each snapshot file from boat.dev's storage the first time it's read, which made
# Gradle's start on a fresh VM about 80 s instead of 5. Reading the files a node's run opens (lists
# made by warm.sh) 64 at a time while the tree is pushed saved about a minute on a 26.3 run.
PREFETCH = 'tr "\\n" "\\0" < ~/prefetch-lists/{node}.list | xargs -0 -P 64 -n 20 cat > /dev/null 2>&1'
GONE = ('deleting', 'deleted')


def find_boat():
    found = shutil.which('boat')
    if found:
        return found
    # The installer adds `boat` as a shell function, which subprocesses can't see.
    exe = Path.home() / '.ascii' / 'bin' / ('boat.exe' if os.name == 'nt' else 'boat')
    if exe.exists():
        return str(exe)
    sys.exit('boat CLI not found. Dara installs it: https://docs.boat.dev')


BOAT = find_boat()
print_lock = threading.Lock()
# Every VM this run starts is named <PREFIX><what>; the random token makes the prefix this run's
# alone, so cleanup can delete by name without touching another agent's VMs.
PREFIX = f'diorama-test-{secrets.token_hex(3)}-'
work_deadline = time.time() + WORK_MINUTES * 60
# Names to stop rather than delete, recorded before the stop is tried.
keep_names = set()
# Set on a refused start or Ctrl-C: no thread starts another VM after it.
stop_starting = threading.Event()
interrupted = threading.Event()


class Refused(RuntimeError):
    pass


def say(node, text):
    with print_lock:
        print(f'[{node}] {text}' if node else text, flush=True)


def text_of(output):
    return output.decode('utf-8', 'replace') if isinstance(output, bytes) else (output or '')


def boat(*args, cwd=None, timeout=120):
    if args[0] in ('list', 'delete', 'stop', 'info'):
        # Cleanup runs past the work deadline, in short calls.
        timeout = min(timeout, 60)
    else:
        timeout = min(timeout, work_deadline - time.time())
        if timeout <= 0:
            return subprocess.CompletedProcess(args, 124, '', f'boat {args[0]} not run: past the deadline')
    try:
        return subprocess.run([BOAT, '--no-update', *args], capture_output=True, text=True, encoding='utf-8',
                              errors='replace', cwd=cwd, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        # What it printed before the timeout can hold the id of a VM it made.
        return subprocess.CompletedProcess(args, 124, text_of(e.stdout),
                                           text_of(e.stderr) + f'\nboat {args[0]} timed out after {timeout:.0f} s')


def boat_retry(*args, cwd=None, timeout=120):
    """boat for steps that are safe to repeat, tried three times: the API fails now and then."""
    for attempt in range(3):
        result = boat(*args, cwd=cwd, timeout=timeout)
        if result.returncode == 0 or time.time() > work_deadline:
            break
        if attempt < 2:
            time.sleep(15)
    return result


def events(output):
    for line in output.splitlines():
        try:
            found = json.loads(line)
        except ValueError:
            continue
        if isinstance(found, dict):
            yield found


def tail(result):
    return (result.stdout + result.stderr).strip()[-2000:]


def our_vms(prefix=None):
    """[(name, id, state)] of this run's VMs on the account (or those named with `prefix`), stopped
    ones included; None when boat list fails, which is not the same as having none."""
    listed = next(events(boat('list', '--all', '--json').stdout), None)
    if not listed or 'sandboxes' not in listed:
        return None
    return [(v['name'], v['id'], v.get('state')) for v in listed['sandboxes']
            if v.get('name', '').startswith(prefix or PREFIX)]


def find_vm(name):
    found = our_vms(name)
    return None if found is None else next((vm for n, vm, _ in found if n == name), '')


# A warm VM is one a run left running for the next run from the same checkout, named
# diorama-warm-<checkout>-<snapshot commit>-<node>. It has the node's build, Gradle's configuration
# cache and the snapshot's files already read, so a rerun starts about a minute sooner and costs
# no start. Only an idle VM has a warm name: a run takes one by renaming it to its own
# PREFIX + node, so while it's in use everything else (cleanup, keeping a VM with its evidence)
# works as for any VM of the run, and gives it back by renaming it after a run that went well.
# boat.dev archives it (stops it, keeping the disk) when the lifetime set then runs out; a stopped
# warm VM is never used again, and the next run deletes it.
WARM_PREFIX = 'diorama-warm-'
CHECKOUT = ''


def warm_name(node, snapshot):
    if snapshot and snapshot.startswith(SNAPSHOT_PREFIX):
        source = snapshot[len(SNAPSHOT_PREFIX):]
    else:
        # Any other snapshot by a hash of its name: no dash, so the node can be read off the end.
        source = 's' + hashlib.sha1((snapshot or '').encode()).hexdigest()[:7]
    return f'{WARM_PREFIX}{CHECKOUT}-{source}-{node}'


def warm_lock(repo, node, wait=0):
    """An open lock file while this script may take or give back the checkout's warm VM for
    `node`, else None after `wait` seconds. Only runs from this checkout use that VM, so holding
    this while taking and giving back means the two never overlap."""
    path = repo / 'build' / 'boat-runs' / f'warm-{node}.lock'
    until = time.time() + wait
    while True:
        handle = open(path, 'a+')
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return handle
        except OSError:
            handle.close()
            if time.time() >= until:
                return None
            time.sleep(2)


def vm_name(vm):
    """The VM's name as boat.dev has it now, or None when boat info fails."""
    return next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {}).get('name')


def rename(vm, name):
    """True if the VM is now called `name`, False if it isn't, None if that can't be told: a rename
    whose answer got lost may still have happened."""
    if boat('rename', vm, name, '--json').returncode == 0:
        return True
    now = vm_name(vm)
    return None if now is None else now == name


def take_warm(node, snapshot, size):
    """This checkout's warm VM for the node, renamed to this run's PREFIX + node, if one is running
    idle and was made from `snapshot` at this size; deletes this checkout's others for the node
    (stopped, from an older snapshot). Call it holding warm_lock."""
    found = our_vms(f'{WARM_PREFIX}{CHECKOUT}-') or []
    want = warm_name(node, snapshot)
    reuse = None
    for name, vm, state in found:
        # diorama-warm-<checkout>-<commit>-<node>
        if name[len(WARM_PREFIX) + len(CHECKOUT) + 1:].split('-', 1)[-1] != node:
            continue
        if reuse is None and name == want and state in ('ready', 'idle', 'running'):
            sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {})
            # Not while a run is on it: a script killed outright leaves its tests running there.
            idle = next(events(boat('exec', vm, '--timeout', '30', '--json', '--', 'flock', '-n',
                                    '/home/user/sandbox.lock', 'true').stdout), {}).get('exitCode') == 0
            if idle and sandbox.get('type') == size:
                taken = rename(vm, PREFIX + node)
                if taken is None:
                    # Maybe ours, maybe still warm: either way cleanup or its lifetime ends it.
                    continue
                if taken:
                    # Ours from here: cleanup finds it by this run's prefix. It needs to live
                    # long enough for this run; it's set back to the warm time after.
                    if boat_retry('extend', vm, '--ttl', str(TTL), '--json').returncode == 0:
                        reuse = vm
                    else:
                        boat('delete', vm, '--yes', '--json')
                continue
        if state not in GONE:
            boat('delete', vm, '--yes', '--json')
    return reuse


def keep_warm(repo, node, vm, snapshot, minutes):
    """Gives the VM back to this checkout's warm pool, until boat.dev archives it `minutes` from
    now. True when it's given back, False when it's still this run's to delete, None when that
    can't be told: then it's left alone, and its lifetime ends it."""
    lock = warm_lock(repo, node, wait=120)
    if not lock:
        return False
    try:
        if boat('extend', vm, '--ttl', str(minutes * 60), '--json').returncode:
            return False
        given = rename(vm, warm_name(node, snapshot))
    finally:
        lock.close()
    if given:
        say(node, f'VM {vm} stays warm {minutes} min for the next run from this checkout (--warm-minutes 0 to delete it now)')
    elif given is None:
        say(node, f'!! VM {vm} may have been given back warm or not; boat.dev stops it within {minutes} min either way')
    return given


def clear_stopped_warm():
    """Deletes warm VMs boat.dev archived when their time ran out, from any checkout: nothing
    resumes them, and they'd sit on the account for 14 days. Never one that is running."""
    for name, vm, state in our_vms(WARM_PREFIX) or []:
        if state == 'stopped':
            boat('delete', vm, '--yes', '--json')


def settle(name, vm, vm_state):
    """Deletes one of this run's VMs, or stops it if it's kept; None when that worked, else why not."""
    if name in keep_names:
        if vm_state in ('stopped', 'stopping') or boat('stop', vm, '--json').returncode == 0:
            return 'kept'
        return f'kept, but it is {vm_state} and boat stop failed: boat stop {vm}'
    if boat('delete', vm, '--yes', '--json').returncode == 0:
        return None
    # Already being deleted (by its own thread, a moment ago) also fails a delete.
    sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox')
    if sandbox is None:
        return 'delete failed and its state is unknown'
    return None if sandbox.get('state') in GONE else f'delete failed, {sandbox.get("state")}'


def ignore_signals():
    for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, signal.SIG_IGN)


def reconcile(rounds=1):
    """The final cleanup, on normal exit, on an error and after Ctrl-C: deletes every VM with this
    run's prefix (warm VMs it took included), stopping instead the ones kept on purpose, and says
    what's left. Signals are ignored meanwhile, so a second Ctrl-C can't cut it short."""
    ignore_signals()
    left = None
    for i in range(rounds):
        if i:
            # A `boat new` that was under way can still make its VM.
            time.sleep(20)
        found = our_vms()
        if found is None:
            left = None
            continue
        found = [f for f in found if f[2] not in GONE]
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            outcomes = list(pool.map(lambda f: settle(*f), found))
        left = [(name, vm, why) for (name, vm, _), why in zip(found, outcomes) if why]
    if left is None:
        say(None, f'!! could not list VMs to clean up: find yours with `boat list --all`, named {PREFIX}*, '
                  'and delete them with boat delete <id> --yes')
        return
    for name, vm, why in left:
        if why == 'kept':
            say(None, f'VM {vm} ({name}) is kept, stopped: boat resume {vm} to look at it, boat delete {vm} --yes when done')
        elif why.startswith('kept'):
            say(None, f'!! VM {vm} ({name}) is {why}')
        else:
            say(None, f'!! VM {vm} ({name}) is still on the account ({why}): boat delete {vm} --yes')


def newest_snapshot():
    """The most recent saved snapshot named diorama-gametest-<commit>, or None."""
    listed = next(events(boat('snapshots', '--json').stdout), {})
    named = [s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX) and s['status'] == 'ready']
    return max(named, key=lambda s: s['createdAt'])['name'] if named else None


def git_paths(repo, command, *args):
    found = subprocess.run(['git', command, '-z', *args], capture_output=True, cwd=repo)
    if found.returncode:
        return None
    return [p for p in found.stdout.decode('utf-8', 'surrogateescape').split('\0') if p]


def make_overlay(repo, base, into):
    """A tarball of what this working tree changes from commit `base` (what the snapshot's
    ~/diorama holds): changed and untracked files, and a list of deleted ones. None when git can't
    tell, or when it's so big that a whole push is as quick."""
    if subprocess.run(['git', 'cat-file', '-e', f'{base}^{{commit}}'], capture_output=True, cwd=repo).returncode:
        return None
    changed = git_paths(repo, 'diff', '--name-only', '--no-renames', base, '--')
    untracked = git_paths(repo, 'ls-files', '--others', '--exclude-standard')
    if changed is None or untracked is None:
        return None
    paths = sorted(set(changed) | set(untracked))
    present = [p for p in paths if (repo / p).is_file()]
    # Gone, or a directory now where the commit had a file.
    deleted = [p for p in paths if not (repo / p).is_file()]
    if sum((repo / p).stat().st_size for p in present) > 50_000_000:
        return None
    tgz = into / 'overlay.tgz'
    with tarfile.open(tgz, 'w:gz') as tar:
        for p in present:
            tar.add(repo / p, arcname=p, recursive=False)
        listing = '\n'.join(deleted).encode('utf-8', 'surrogateescape')
        info = tarfile.TarInfo('.boat-overlay-deleted')
        info.size = len(listing)
        tar.addfile(info, io.BytesIO(listing))
    return tgz, len(present), len(deleted)


# Applies overlay.tgz over the snapshot's ~/diorama: the newline-separated deletions first, so a
# directory can take a deleted file's place, then the files, each replacing any directory where it
# goes, then CRLF off the scripts (a Windows checkout has CRLF, which bash rejects). Safe to repeat.
APPLY_OVERLAY = ('set -e; cd ~/diorama; tar xzf ~/overlay.tgz .boat-overlay-deleted; '
                 'if [ -s .boat-overlay-deleted ]; then xargs -d "\\n" rm -rf -- < .boat-overlay-deleted; fi; '
                 'tar xzf ~/overlay.tgz --recursive-unlink; '
                 'rm -f .boat-overlay-deleted; sed -i "s/\\r$//" .claude/skills/boat-game-test/*.sh')


def wait_for_start_slot():
    """Waits (on `boat limits`, which is free) until the account may start a VM this minute, so a
    start isn't refused: refused starts count against the limits too. Raises when the hour's or
    the day's starts, or the plan's hours, are gone."""
    until = time.time() + 180
    while True:
        if stop_starting.is_set():
            raise Refused('not starting: an earlier start was refused, or the script is stopping')
        limits = next(events(boat('limits', '--json', timeout=30).stdout), None)
        if not limits or 'starts' not in limits:
            # boat limits failing says nothing about the limits; boat new will say.
            return
        starts = limits['starts']
        # With paid extra starts on, the day's limit is only where they begin.
        extra = (limits.get('keepGoing') or {}).get('enabled')
        if starts['day']['remaining'] < 1 and not extra or starts['hour']['remaining'] < 1 or limits.get('canStart') is False:
            stop_starting.set()
            raise Refused(f'boat.dev has no starts left ({starts["hour"]["remaining"]} this hour, '
                          f'{starts["day"]["remaining"]} today) or cannot start ({limits.get("startBlockedReason")}); '
                          'see `boat limits` and run later')
        if starts['minute']['remaining'] >= 1:
            return
        if time.time() > until:
            raise Refused('no start free for 3 min: other agents are starting VMs too; run later')
        time.sleep(10)


def create_vm(size, snapshot, what, ttl=TTL):
    """A new VM named PREFIX + what. One start per call and no retry: a failed start that did make
    a VM is found by name, and one that didn't fails the node."""
    name = PREFIX + what
    wait_for_start_slot()
    if stop_starting.is_set():
        raise Refused('not starting: an earlier start was refused, or the script is stopping')
    result = boat('new', '--type', size, '--no-env', '--ttl', str(ttl), '--name', name, '--json',
                  *(['--from', snapshot] if snapshot else []), timeout=300)
    found = list(events(result.stdout + result.stderr))
    if any(e.get('event') == 'ready' for e in found):
        return next(e['id'] for e in found if e.get('event') == 'ready')
    error = next((e for e in found if e.get('event') == 'error' or 'error' in e), {})
    if error.get('status') == 429 or 'limit' in str(error.get('code', '')):
        stop_starting.set()
        raise Refused(f'boat.dev refused the start ({error.get("code")}: {error.get("error")}); '
                      'see `boat limits` and run later')
    # The answer got lost or the start failed: boat.dev may have made the VM anyway.
    vm = next((e['id'] for e in found if e.get('event') == 'created' and 'id' in e), None)
    for _ in range(3):
        if vm:
            break
        time.sleep(20)
        vm = find_vm(name)
        if vm is not None:
            break
    if not vm:
        raise RuntimeError(f'boat new failed: {error or tail(result)}')
    for _ in range(12):
        sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {})
        if sandbox.get('state') in ('ready', 'idle', 'running'):
            return vm
        time.sleep(10)
    raise RuntimeError(f'VM {vm} never came up')


def run_script(vm, script, *args, deadline, launched=None):
    """Starts a VM script detached and waits for the exit code it writes to ~/run-exit; returns it
    with the script's log, and puts the time it started in the list `launched`, if given. Each step is a short API call, so the run survives the API failing for a
    while, which happens: long live output streams drop, and calls return 500 now and then. None of
    this counts against start limits."""
    cleared = boat_retry('exec', vm, '--timeout', '30', '--json', '--', 'rm', '-f', '/home/user/run-started',
                         '/home/user/run-exit')
    if next(events(cleared.stdout), {}).get('exitCode') != 0:
        raise RuntimeError(f'could not clear the last run\'s exit code: {tail(cleared)}')
    for attempt in range(3):
        sent = time.time()
        started = boat('exec', vm, '--detach', '--json', '--', 'bash', script, *args)
        if any('pid' in e for e in events(started.stdout)):
            break
        # A start reported as failed may have started anyway (the script writes ~/run-started), and
        # the script's lock stops a second copy.
        time.sleep(20)
        check = boat('exec', vm, '--timeout', '30', '--json', '--', 'ls', '/home/user/run-started')
        if next(events(check.stdout), {}).get('exitCode') == 0:
            break
        if attempt == 2:
            raise RuntimeError(f'could not start {script}: {tail(started)}')
    if launched is not None:
        launched.append(sent)
    misses = 0
    while time.time() < deadline:
        time.sleep(30)
        if interrupted.is_set():
            raise RuntimeError('stopped: the script was interrupted')
        read = next(events(boat('exec', vm, '--timeout', '30', '--json', '--', 'cat', '/home/user/run-exit',
                                timeout=60).stdout), {})
        if read.get('exitCode') == 0 and read.get('stdout', '').strip().isdigit():
            log = next(events(boat('exec', vm, '--timeout', '60', '--json', '--', 'bash', '-c',
                                   'tail -c 20000 ~/sandbox.log ~/warm.out 2>/dev/null', timeout=120).stdout), {})
            return int(read['stdout'].strip()), log.get('stdout', '')
        # exitCode 1 is cat finding no file yet: still running. Anything else is the API failing.
        misses = 0 if read.get('exitCode') == 1 else misses + 1
        if misses == 20:
            raise RuntimeError(f'lost contact with VM {vm} for 10 min')
    raise RuntimeError(f'{script} was still running at the deadline')


def push_tree(node, vm, repo, snapshot, reused, out):
    """Brings the VM's ~/diorama up to this working tree; returns the sandbox.sh to run. A VM fresh
    from the snapshot gets only what changed since the commit the snapshot was warmed on, a few
    seconds instead of the 15 to 80 a whole push took; a reused VM, whose ~/diorama an earlier run
    changed, gets the whole tree in ~/src, which sandbox.sh copies over ~/diorama."""
    # Results of an earlier run on this VM could pass for this run's.
    paths = ['/home/user/src', '/home/user/result', '/home/user/result.tgz', '/home/user/overlay.tgz']
    cleared = boat_retry('exec', vm, '--timeout', '120', '--', 'rm', '-rf', *paths)
    if cleared.returncode:
        raise RuntimeError(f'could not clear the VM: {tail(cleared)}')
    base = snapshot[len(SNAPSHOT_PREFIX):] if snapshot and snapshot.startswith(SNAPSHOT_PREFIX) else None
    overlay = make_overlay(repo, base, out) if base and not reused else None
    if overlay:
        tgz, files, deleted = overlay
        sent = boat_retry('scp', tgz.name, f'{vm}:/home/user/overlay.tgz', cwd=tgz.parent, timeout=300)
        tgz.unlink()
        if sent.returncode:
            raise RuntimeError(f'boat scp of the changed files failed: {tail(sent)}')
        applied = boat_retry('exec', vm, '--timeout', '120', '--', 'bash', '-c', APPLY_OVERLAY)
        if applied.returncode:
            raise RuntimeError(f'could not apply the changed files: {tail(applied)}')
        say(node, f'sent {files} changed files and {deleted} deletions since {base}')
        return '/home/user/diorama/.claude/skills/boat-game-test/sandbox.sh'
    pushed = boat_retry('push', vm, str(repo), '--to', '/home/user/src', timeout=300)
    if pushed.returncode:
        raise RuntimeError(f'boat push failed: {tail(pushed)}')
    # A Windows checkout can have CRLF line endings, which bash rejects.
    normalized = boat_retry('exec', vm, '--timeout', '60', '--', 'sed', '-i', r's/\r$//', SANDBOX_SH)
    if normalized.returncode:
        raise RuntimeError(f'could not prepare sandbox.sh: {tail(normalized)}')
    return SANDBOX_SH


def run_node(node, args, repo, snapshot, start):
    out = repo / 'build' / 'boat-runs' / f'{time.strftime("%Y%m%d-%H%M%S")}-{PREFIX}{node}'
    out.mkdir(parents=True)
    name = PREFIX + node
    if args.keep:
        keep_names.add(name)
    # With --warm-minutes 0 a warm VM is still reused, then deleted at the end. The lock keeps two
    # runs from this checkout from taking the same one at once.
    vm = None
    lock = warm_lock(repo, node) if not args.keep else None
    if lock:
        try:
            vm = take_warm(node, snapshot, args.type)
        finally:
            lock.close()
    reused = vm is not None
    if not vm:
        vm = create_vm(args.type, snapshot, node)
    say(node, f'VM {vm} ({args.type}) {"reused, warm" if reused else "ready"} after {time.time() - start:.0f} s')
    ready = time.time() - start
    ran = False
    done = False
    try:
        if interrupted.is_set():
            raise RuntimeError('stopped: the script was interrupted')
        if not args.no_prefetch and not reused:
            boat('exec', vm, '--detach', '--json', '--', 'bash', '-c', PREFETCH.format(node=node))
        script = push_tree(node, vm, repo, snapshot, reused, out)
        pushed = time.time() - start
        say(node, f'pushed after {pushed:.0f} s; running {args.tests}')
        # The tests get what's left after setup, less 2 min to pack the results and 10 to fetch
        # them and delete the VM.
        test_seconds = int(min(5400, work_deadline - 720 - time.time()))
        if test_seconds < 300:
            raise RuntimeError('setup took so long that no time is left for the tests')
        ran = True
        launched = []
        code, log = run_script(vm, script, node, args.tests, *args.env, f'TEST_SECONDS={test_seconds}',
                               deadline=work_deadline - 600, launched=launched)
        (out / 'vm.log').write_text(log, encoding='utf-8')
        if code:
            raise RuntimeError(f'the VM script failed setting up or packing results; see {out / "vm.log"}')
        fetched = boat_retry('scp', f'{vm}:/home/user/result.tgz', 'result.tgz', cwd=out, timeout=300)
        if fetched.returncode or not (out / 'result.tgz').exists():
            raise RuntimeError(f'no results came back; see {out / "vm.log"}')
        with tarfile.open(out / 'result.tgz') as tar:
            tar.extractall(out, filter='data')
        (out / 'result.tgz').unlink()
        usage = next(events(boat('usage', vm, '--json', timeout=30).stdout), {})
        done = True
    except Exception:
        # Once the tests ran, the VM holds their evidence: keep it, stopped, rather than lose it.
        if ran and not interrupted.is_set():
            keep_names.add(name)
            say(node, f'!! keeping VM {vm} with what the run left in ~/result.tgz and ~/sandbox.log: '
                      f'boat resume {vm}, boat scp {vm}:/home/user/result.tgz ., then boat delete {vm} --yes')
        raise
    finally:
        # A failure here is caught by reconcile() at the end.
        warm = done and args.warm_minutes and not args.keep and not interrupted.is_set()
        given = keep_warm(repo, node, vm, snapshot, args.warm_minutes) if warm else False
        if given is False:
            boat('stop' if name in keep_names else 'delete', vm, '--json', *([] if name in keep_names else ['--yes']))

    result = json.loads((out / 'result.json').read_text())
    shots = sorted((out / 'screenshots').glob('*.png')) if (out / 'screenshots').exists() else []
    say(node, f'exit={result["exit"]} after {result["minutes"]} min of tests, '
              f'{(time.time() - start) / 60:.0f} min in all, ${usage.get("dollars", 0):.3f}')
    say(node, f'time: {timings(out, node, ready, pushed, launched[0] - start)}')
    say(node, f'{len(shots)} screenshots in {out / "screenshots"}')
    if result['exit']:
        lines = (out / 'gradle.log').read_text(encoding='utf-8', errors='replace').splitlines()
        cause = [l for l in lines if any(k in l for k in ('AssertionError', 'Description:', 'Caused by', 'FAILED'))]
        say(node, '\n  '.join(['failure lines:', *cause[:8]]))
        say(node, f'full output: {out / "gradle.log"}; game log: {out / "latest.log"}')
    return result['exit']


def first_time(path, *needles):
    """Seconds since midnight of the first line in the log that holds every needle, read off the
    time at its start ('12:34:56 ...' from sandbox.sh, '[12:34:56] ...' from the game), or None."""
    if not path.exists():
        return None
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if all(n in line for n in needles):
            clock = line.lstrip('[')[:8]
            try:
                h, m, s = (int(p) for p in clock.split(':'))
            except ValueError:
                continue
            return h * 3600 + m * 60 + s
    return None


def timings(out, node, ready, pushed, launched):
    """Where the run's time went, from the VM's logs: one line, phases that can't be told left out.
    ready, pushed and launched (sandbox.sh started) are seconds from the script's start; the rest is
    read off the VM's clock."""
    marks = [
        ('Gradle', first_time(out / 'sandbox.log', 'running :')),
        ('game start', first_time(out / 'gradle.log', f'Task :{node}:runClientGameTest')),
        ('world', first_time(out / 'gradle.log', 'Starting integrated minecraft server')),
        ('map warm-up', first_time(out / 'gradle.log', 'joined the game')),
        ('tests', first_time(out / 'gradle.log', 'Map warm-up done')),
        (None, first_time(out / 'sandbox.log', 'gradle finished')),
    ]
    started = first_time(out / 'sandbox.log', 'started')
    # The push takes in copying the tree into place on the VM.
    copied = (marks[0][1] - started) % 86400 if started is not None and marks[0][1] is not None else 0
    parts = [f'VM {ready:.0f} s', f'push {launched - ready + copied:.0f} s']
    for (name, at), (_, end) in zip(marks, marks[1:]):
        if at is not None and end is not None:
            parts.append(f'{name} {(end - at) % 86400} s')
    warm = marks[4][1]
    if started is not None and warm is not None:
        parts.append(f'first test after {launched + (warm - started) % 86400:.0f} s')
    return ', '.join(parts)


def interrupt_on_signals():
    """SIGTERM and SIGBREAK raise KeyboardInterrupt in the main thread, like Ctrl-C, so the cleanup
    runs in ordinary code rather than inside a signal handler."""
    def interrupt(*_):
        raise KeyboardInterrupt
    for sig in (signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, interrupt)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('nodes', help='Stonecutter nodes, as in versions/, comma-separated: 26.3,1.21.1,26.3-neoforge')
    parser.add_argument('--tests', required=True, help='DIORAMA_TESTS sections, comma-separated, or "all"')
    parser.add_argument('--type', default='default', choices=['small', 'default', 'large'],
                        help='VM size. default (4 vCPU) unless a run is long: see SKILL.md')
    parser.add_argument('--env', action='append', default=[], help='extra KEY=VALUE for the game, e.g. DIORAMA_GIF=1')
    parser.add_argument('--keep', action='store_true', help='stop the VM instead of deleting it, to look around after')
    parser.add_argument('--snapshot', help='saved snapshot to start from (default: the newest diorama-gametest-*)')
    parser.add_argument('--warm-minutes', type=int,
                        help='keep each VM running this long after the run, for the next run from this checkout '
                             f'(default {WARM_MINUTES} for up to {WARM_NODES} nodes, else 0; 0 deletes it at once)')
    parser.add_argument('--no-prefetch', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    repo = Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True,
                               cwd=HERE, check=True).stdout.strip())
    nodes = list(dict.fromkeys(n for n in args.nodes.split(',') if n))
    missing = [n for n in nodes if not (repo / 'versions' / n).is_dir()]
    if missing:
        sys.exit(f'no node versions/{", ".join(missing)}')
    if args.warm_minutes is None:
        args.warm_minutes = WARM_MINUTES if len(nodes) <= WARM_NODES else 0
    global CHECKOUT
    # Per machine and worktree: other machines share the boat.dev account, and paths only fold case
    # where the file system does.
    CHECKOUT = hashlib.sha1(f"{socket.gethostname()}:{os.path.normcase(str(repo))}".encode()).hexdigest()[:6]

    snapshot = args.snapshot or newest_snapshot()
    if snapshot:
        say(None, f'starting from snapshot {snapshot}')
    else:
        say(None, f'no {SNAPSHOT_PREFIX}* snapshot: each VM sets up from scratch (about 5 min more)')
    say(None, f'this run\'s VMs are named {PREFIX}<node>'
              + (f', or {WARM_PREFIX}{CHECKOUT}-<snapshot>-<node> while warm' if args.warm_minutes else ''))
    clear_stopped_warm()

    interrupt_on_signals()
    start = time.time()
    codes = {}

    def one(node):
        try:
            codes[node] = run_node(node, args, repo, snapshot, start)
        except Exception as e:
            say(node, f'!! {e}')
            codes[node] = 2

    threads = [threading.Thread(target=one, args=(n,), daemon=True) for n in nodes]
    try:
        for i, t in enumerate(threads):
            # 10 starts a minute from this script, inside the account's 12; wait_for_start_slot
            # covers other agents' starts.
            if i:
                time.sleep(6)
            t.start()
        for t in threads:
            while t.is_alive():
                t.join(1)
    except KeyboardInterrupt:
        interrupted.set()
        stop_starting.set()
        ignore_signals()
        say(None, 'stopping: each node deletes its VM, then the script checks for any left')
        # A thread inside `boat new` learns its VM's id within 5 min, then deletes it.
        until = time.time() + 360
        for t in threads:
            if t.is_alive():
                t.join(max(0, until - time.time()))
        reconcile(rounds=2)
        sys.exit(130)
    reconcile()
    if len(nodes) > 1:
        say(None, 'summary: ' + ', '.join(f'{n} {"passed" if codes.get(n) == 0 else "FAILED"}' for n in nodes))
    sys.exit(max(codes.values(), default=2))


if __name__ == '__main__':
    main()
