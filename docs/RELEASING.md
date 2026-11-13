# Releasing

A release is made by pushing a version tag. `.github/workflows/release.yml` then runs the tests on Java 17
and 21, builds the jar, pushes the multi-architecture image to Docker Hub and creates the GitHub release.
The version comes from the tag: the workflow sets it in `pom.xml` for its own build. Do not edit the
version in `pom.xml` for a release; main stays at the next `-SNAPSHOT` version. See [VERSIONING.md](../VERSIONING.md).

## Once, before the first release

1. Create the Docker Hub repository `ataiva/serengeti` if it does not exist.
2. In Docker Hub, create an access token that can read and write that repository.
3. In GitHub, open Settings, then Secrets and variables, then Actions, and add two repository secrets:
   - `DOCKERHUB_USERNAME`: the Docker Hub user name that owns the token.
   - `DOCKERHUB_TOKEN`: the access token from step 2.

## For each release

1. Update CHANGELOG.md. Rename the heading of the section being released to `## [X.Y.Z] - YYYY-MM-DD`,
   with the date of the tag, and merge it to main in the usual way. The release notes are that section;
   the workflow stops before it publishes anything if the section is missing.

2. Check that CI passed on the commit to tag:

   ```sh
   gh run list --repo ao/Serengeti --workflow ci.yml --branch main --limit 3
   ```

3. Create an annotated tag on that commit:

   ```sh
   git tag -a v2.0.0 -m "Serengeti 2.0.0" <commit>
   ```

4. Push the tag:

   ```sh
   git push origin v2.0.0
   ```

   From a container that holds the repository, for example a devops container, use:

   ```sh
   docker exec <devops-container> git -C /src/ao/Serengeti -c safe.directory=/src/ao/Serengeti push https://github.com/ao/Serengeti.git v2.0.0
   ```

5. Watch the run:

   ```sh
   gh run list --repo ao/Serengeti --workflow release.yml --limit 1
   gh run watch --repo ao/Serengeti <run-id> --exit-status
   ```

6. Check the result:

   ```sh
   docker buildx imagetools inspect ataiva/serengeti:2.0.0    # linux/amd64 and linux/arm64
   docker run --rm ataiva/serengeti:2.0.0 --version           # prints 2.0.0
   gh release view v2.0.0 --repo ao/Serengeti                 # jar, checksum and notes
   ```

A pre-release tag such as `v2.0.0-rc.1` publishes the image tag `2.0.0-rc.1` and a GitHub pre-release. It
does not change `latest`.

## If a run fails

- Nothing is published before the image step. Fix the cause on main, then open the failed run on the
  Actions page and choose "Re-run failed jobs". If the fix needs new code, release the next version
  instead; do not move the tag.
- If the image was pushed and the GitHub release was not created, re-running the failed job pushes the
  same tags again. Check `latest` afterwards.
- To withdraw a published release, delete the GitHub release and the image tag on Docker Hub. Deleting
  the GitHub release with `gh release delete v2.0.0 --repo ao/Serengeti --cleanup-tag --yes` also removes
  the tag.
