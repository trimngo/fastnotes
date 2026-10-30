# App updates

Version 0.3.0 introduces in-app updating. Install it over the previous APK once;
older versions cannot discover it automatically. The application ID, note format
and signing key are unchanged.

## User flow

Automatic checking is on by default. When the main activity resumes, Fastnotes
checks at most once per day after a successful check. A failed check has a one-hour
retry interval. There is no periodic background service or network requirement for
writing. **Settings → App updates → Check now** bypasses the interval; the same
screen can disable automatic checking. Network failures do not interrupt writing.

An available update adds an **Update** button beside **Sheets**. It opens a
resizable update screen. **Download & install** downloads and validates the APK,
then opens Android's installer while that screen is in the foreground. If Android
requires **Allow from this source** for Fastnotes, the app opens the appropriate
settings and continues after the user returns with permission granted. Permission
denial or canceling installation leaves **Install update** available for retry.
There is no silent installation. If the user closes the update screen during a
download, installation waits until they return and select it. A verified download survives process restart
while its cache files remain available. Android can evict that cache; the app then
offers to download again.

The update worker is separate from the note-storage worker. Entering the update
screen finishes the current gesture and queues a save; native ink is paused while
the writing activity is paused. Updates do not migrate or delete sheets. As before,
uninstalling or clearing application data deletes private notes.

## Discovery and validation

The source is the public `trimngo/fastnotes` GitHub repository. Checks use the
releases list because `/releases/latest` omits the development prereleases used
by this project. Drafts and releases without uploaded APK and `update.json` assets
are skipped. The newest published complete release is the offered channel.
The APK's integer version code must exceed the installed version; semantic version
labels and tag names are not used to decide whether to install.

`update.json` uses schema 1 and contains application ID, version name/code,
minimum Android API, APK filename/size and SHA-256. URLs must refer to the same
repository/release; redirects stay on known GitHub HTTPS asset hosts. Metadata is
size-bounded, APKs are limited to 64 MiB, and incomplete downloads stay in `.part`
files. Before an APK is offered to Android, the app checks its exact bytes/hash,
package identity, version, minimum API and current signing certificate against the
installed app. Android's package installer additionally enforces APK signature and
update rules. Signing-key rotation is not implemented; updates require the same key.

Only the private `cache/updates` directory is shared through a nonexported,
read-granted FileProvider. Notes and other application files are outside that path.
No storage permission, GitHub credential, note upload or analytics is used.
GitHub receives normal HTTPS update requests, the app version/User-Agent and the
connection's IP address. Automatic requests can be disabled independently of notes.

## Publishing future builds

1. Increase `versionCode` for every installable revision and set `versionName`.
2. Build/test and sign with the preserved Fastnotes development key.
3. Stage the signed APK and `build.json` on an isolated builds branch. Metadata
   includes `application_id`, `version`, `version_code` and `sha256`.
4. Create the GitHub release and dispatch **Publish signed development APK** with
   the immutable artifact commit, release tag and checksum.
5. The workflow validates APK identity and the pinned signing certificate, generates
   `update.json` from the actual signed APK, uploads APK/checksums, then publishes
   `update.json` last. Incomplete releases are not offered by the app.
6. Verify the public downloads and their hash after publishing.

The signing key is never uploaded to GitHub Actions. Earlier releases without
`update.json` are historical/manual downloads. Never reuse a version code or
replace an APK under an existing offered release.

On-device checks still needed: install the first updater APK, check discovery,
download a later signed build, deny/allow unknown-source permission, cancel/retry
the installer, rotate or leave during download, and reopen existing notes after
the update. Software contract tests cannot approve Android's installer on the tablet.
