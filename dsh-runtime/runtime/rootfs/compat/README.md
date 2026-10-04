# Android file publication compatibility

DSH 0.2 uses a hard link to publish new files without overwriting an existing
entry. PRoot's `--link2symlink` emulation can leave a dangling link when DSH
removes its staging directory. The shipped proroot also rejects the guarded
hard-link operation with `EINVAL` on Android's bound workspace storage.

For PRoot and proroot, the app substitutes Linux `renameat2(RENAME_NOREPLACE)` at this one
publication point. DSH still writes and syncs a private staging file first;
publication remains atomic and refuses existing files, directories and symlinks.
Updating existing files and DSH's observation/version checks are unchanged.
The Python helper uses the Ubuntu image's Python and passes paths without a shell.
Chroot continues using DSH's original hard-link operation.

The build checks the upstream patch point and fails if it changes. This patch
belongs to DSH Mobile and requires no changes to the reusable Ubuntu libraries.
