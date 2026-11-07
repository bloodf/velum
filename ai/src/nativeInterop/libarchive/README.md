# iOS model ZIP dependency

The public iPhoneOS 27.2 SDK has no `archive.h`. Do not link the private system library.
`ai/build.gradle.kts` builds libarchive 3.8.11 statically for each existing iOS target and embeds
`libarchive.a` through Kotlin cinterop. CMake and the selected Xcode SDK are build prerequisites.
Only SDK zlib is enabled as an optional compression dependency. No prebuilt native binaries are committed.

Source: https://github.com/libarchive/libarchive/releases/tag/v3.8.11
Release asset: `libarchive-3.8.11.tar.gz`
Official GitHub release asset SHA-256: `900fe9fc7237c2b04f090f3c3648de8fe597525771f5035016537ce2d667d12b`
The build checks this digest before unpacking. Upstream copyright and license terms are retained in
`COPYING` and in the downloaded source. Include these notices with binary distributions.

`raw-path.patch` adds validation at libarchive's existing local-header, central-directory and Unicode
extra-field name reads. This prevents embedded NUL truncation and DOS backslash conversion from hiding
unsafe names from the adapter. Raw Unix file types in central-directory attributes and local/central
`xl` extra fields are checked before upstream can turn FIFOs into regular files or special entries
with trailing slashes into directories. Only missing types, regular files and directories are accepted.
It also rejects data-bearing directories, whose contents libarchive
otherwise skips without CRC verification. Consequently ZIP directories must be stored with zero
compressed size, uncompressed size and CRC. ZIP parsing, ZIP64 handling, decompression and file CRC
verification remain libarchive's implementation. Never enable `DONT_FAIL_ON_CRC_ERROR` or `ignorecrc32`.

The adapter accepts only unencrypted regular files and empty directories. It rejects duplicate names
including case aliases, links, special files and parent/file collisions. It requires an absent destination,
which matches the existing LocalModels staging caller. An extraction failure removes its private sibling
staging directory; successful extraction moves the complete tree to the destination.

After heavy-task admission, run `:ai:iosSimulatorArm64Test` for `IosZipArchiveTest` and
`ModelStoreAppendTest`, the relevant iOS compile tasks, and repository lint. Native task execution downloads
and compiles the pinned source; source inspection alone does not validate that build or the iOS ABI.
