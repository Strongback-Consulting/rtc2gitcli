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
- **`-P` (no process):** registers the repository without EWM process. The toolkit's pre-receive hook checks pushes against the project area's process. Preconditions such as "commits must reference a work item" would reject history from before the integration. Turn the process on after the import:

  ```bash
  node UpdateGitRepository.js hostUrl:"<uri>" userId:"<user>" prompt:"true" searchUrl:"<clone URL>" ownerPresent:"true"
  ```

- **`-x`:** prints the toolkit command without running it.

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

Not verified yet: an actual registration and push on a live server. That needs a Git server with the toolkit's hooks or a webhook, and permission to register Git repositories in the project area. `tools/push-history` was tested against a local bare repository whose hook counted the commits of each push.
