# Workspace Guidelines

## Repository Synchronization

- **Dual Remote Sync**: Always ensure changes are synchronized to both git repositories:
  - **GitHub (`origin`)**: `https://github.com/bhuwanbhaskar-dev/aem-wknd.git`
  - **Adobe Cloud Manager (`adobe`)**: `https://git.cloudmanager.adobe.com/vlab22-us/wknd-test/`
- Whenever pushing commits or branches, push to both remotes:
  ```sh
  git push origin <branch-name>
  git push adobe <branch-name>
  ```
- Keep branches and commits in sync across both `origin` and `adobe` before considering tasks complete.
