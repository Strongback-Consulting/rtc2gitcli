# Linking migrated commits to EWM work items

EWM's Git integration, through the EWM Git Server Toolkit, links work items to Git commits. EWM learns about a commit when it is pushed to a registered repository:
- **Servers with the toolkit's hooks** (its Node.js server, Apache, Bitbucket, GitLab server hooks): the hooks report every pushed commit.
- **GitHub, GitHub Enterprise and GitLab webhooks:** the server sends EWM a push event.

EWM reads the work item numbers from each commit message and links those work items to the commit. Migrated commits get the same links once the migrated history is pushed to a registered repository. This takes four steps.

## 1. Before migrating: work item numbers EWM recognizes

The toolkit's hooks (`server/hooks/UpdateWorkItemCommitLinks.js`) recognize two forms:
- `#<number>`;
- a keyword followed by a number: `task 73`, `bug 73`, `workitem 73`, `story-73`, ...

A bare number is not recognized. The migration's default format, `rtc.workitem.number.format=%s`, writes exactly that, so set it in `migration.properties` before migrating:

```properties
rtc.workitem.number.format=#%s
```

The commit message then starts with `#73 #75 <comment>`. Changing the format later changes every commit message, so it means migrating again.

## 2. Register the repository in EWM

`tools/register-git-repo` runs the toolkit's `RegisterGitRepository.js`. The toolkit asks for the password itself, so it never appears on the command line.

```bash
cd <Git Server Toolkit>/rtcgit-admin-scripts && npm install     # once
tools/register-git-repo -k <Git Server Toolkit> -r https://ewm.example.com:9443/ccm -u <user> \
    -p "<project area>" -n <repository name> -g <clone URL> -s GITLAB -P
```

- **`-s` (hosting server):** `NONE` for the toolkit's Node.js server or Apache with the toolkit's hooks; otherwise `GITHUB`, `GITHUB_ENTERPRISE`, `GITLAB` or `GERRIT`.
- **`-P` (no process):** registers the repository without EWM process. With process, the project area's preconditions apply to pushes, and one such as "commits must reference a work item" would reject history from before the integration. Permissions are checked either way: every pushing user needs the permission for the *Git Push* operation (`CRRTC8822E` otherwise). Turn the process on after the import:

  ```bash
  node UpdateGitRepository.js hostUrl:"<uri>" userId:"<user>" prompt:"true" searchUrl:"<clone URL>" ownerPresent:"true"
  ```

- **`-x`:** prints the toolkit command without running it.
- **`-i`:** skips the check of EWM's TLS certificate (test servers with a self-signed certificate).

Requirements found on a live EWM 7.2 server:
- **Project area:** `-p` takes a *project* area. A stream's owner shown by `scm list streams` can be a team area: use its project area here, and make the team area the owner afterwards (`UpdateGitRepository.js ... owner:'<project area>/<team area>'`).
- **License and permission:** the registering user needs one of these licenses, plus the *Repository Registration* permission in the project or team area that controls the repository (error `CRRTC8821E` otherwise):
  - IBM Engineering Lifecycle Management solution – Practitioner;
  - IBM Engineering Workflow Management – Developer;
  - IBM Engineering Workflow Management – Developer for IBM Enterprise Platforms.
- **Authorizing Git requests:** every user who pushes to a server with the toolkit's hooks must select "Authorize Git Requests" once in the EWM web client, logged in as themselves. Until they do, the Git server rejects even a fetch with `CRRTC8814E` (HTTP 403). The server's check is anonymous, so it can be tested without a password: POST `gitUser=<user>&repositoryKey=<key>` with the header `x-com-ibm-team-git-auth: true` to `<EWM URI>/service/com.ibm.team.git.common.internal.IGitOperationInvokeService/ValidateAccess`. It answers 200 once the user is authorized.
- **Node.js compatibility:** the toolkit's 7.2 scripts do not run on current Node.js as shipped. `tools/register-git-repo` preloads `tools/lib/toolkit-node-compat.js`, which changes no IBM file:
  - it restores `util.log`, removed in Node.js 23;
  - it drops the empty `cookie` header the toolkit sends after its anonymous pre-login. EWM 7.2 with form login sets no cookie there, and Node.js rejects the header.
- **The toolkit's Node.js Git server needs the same preload:** `NODE_OPTIONS="--require <rtc2gitcli>/tools/lib/toolkit-node-compat.js" node main.js`. Its hook processes inherit it.

The registration shows the repository key and URL. The Git server side then needs one of these:
- **Toolkit hooks:** set them up for the repository, as described in the toolkit's `server/README` and `server/hooks/examples`.
- **GitHub or GitLab webhook:** point it at EWM. Set the webhook secret in EWM's web UI for the registered repository, not on the command line.

## 3. Push the history

```bash
git -C <repository> remote add origin <clone URL>
tools/push-history -n 20 <repository> origin main release-1 ...
```

`tools/push-history` pushes each branch in steps of `-n` commits, then the tags. This matters for webhooks:
- a GitLab push event lists at most 20 commits;
- a GitHub push event lists at most 2048.

A single push of a long history would leave most commits unlinked. Hooks on the server see every pushed commit, so there the step size only spreads the load.

Notes:
- Branches that share history with a branch already pushed only push their own commits.
- A rerun continues where the remote is.
- With Git LFS, git-lfs's pre-push hook uploads the content (`git lfs install --local` in the repository first).

## 4. Branches and team areas

Each migrated stream belonged to a team area in EWM. To keep that ownership, map each branch to its team area:

```bash
node UpdateGitRepository.js hostUrl:"<uri>" userId:"<user>" prompt:"true" searchUrl:"<clone URL>" \
    refMappings:"[{'name':'main','processArea':'<project area>/<team area>'},{'name':'release-1','processArea':'<project area>/<other team area>'}]"
```

## Checking the result

- Open a work item that a migrated commit references. Its links show the commit.
- `node GetGitRepository.js hostUrl:"<uri>" userId:"<user>" prompt:"true" searchUrl:"<clone URL>" showDetails:"true"` shows the registration.
- The `EWM-ChangeSet` trailer of each commit names the change set it came from. EWM keeps the change sets themselves, with their own work item links.

Verified on EWM 7.2 with the Mortgage sample's application repository (branches `master` and `test`, two tags):
- **Registration:** `tools/register-git-repo` registered the repository in "JKE Banking (Change Management)" without process.
- **Server:** the toolkit's Node.js Git server ran on the same machine with the compatibility preload. Its hooks were installed by hand (below) and authenticated against EWM.
- **Push:** `tools/push-history` pushed both branches and the tags. The pre-receive hook had EWM validate every commit, and the post-receive hook reported every commit to EWM. The server's branches and tags equal the local ones.
- **No links in this test:** the Mortgage change sets reference no work items, so the hooks reported "no links to create".
- **Errors on the way, in order:**
  - `CRRTC8821E`: license or Repository Registration permission missing;
  - `CRRTC8814E`: Git requests not authorized by the pushing user;
  - `CRRTC8822E`: Git Push permission missing.

On macOS, the toolkit's `tools/install/configure.sh` needs GNU `getopt` and fails with the system one. Its effect can be applied by hand:
- `git config rtc.repokey <key>` and `git config rtc.repourl <EWM URI>` in the bare repository;
- copy `server/hooks/examples/pre-receive` and `post-receive` into its `hooks/` folder, set `NODE_EXECUTABLE`, `RTC_GIT_SERVER_TOOLKIT_PATH` and `RTC_GIT_SERVER_TRACE_LEVEL` above the `#@@...@@` markers, and make them executable.

The toolkit's Node.js server serves only repositories directly under its root (`/git/<name>.git`).

- **Work item link:** the migrated JKE Banking commit `#73` (migrated with `rtc.workitem.number.format=#%s`) was pushed as a test branch. The post-receive hook asked EWM to link it to work item 73, and EWM accepted. The toolkit records each accepted link as a git note (`refs/notes/commits`, `rtc.wi:73`). That needs a Git server with the toolkit's hooks or a webhook, and permission to register Git repositories in the project area. `tools/push-history` was tested against a local bare repository whose hook counted the commits of each push.
