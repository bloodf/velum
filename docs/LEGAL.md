# Legal & interoperability note

Velum is an **independent open-source project**. It is **not affiliated with, endorsed by, or sponsored by Plaud Inc.** or related entities.

## Compatibility language

We may describe Velum as **compatible with** Plaud Note Pro (and other Plaud hardware) for the purpose of helping owners use devices they purchased. That does **not** imply partnership or official support.

Do **not** use Plaud logos, wordmarks, or marketing assets in Velum branding.

## Reverse engineering for interoperability

Protocol documentation in this repository summarizes community research into the BLE GATT interface used by Plaud devices so that **owners** can sync recordings without a mandatory cloud subscription from a third-party app.

Contributors must:

- Prefer analysis already published under permissive licenses (see [protocol/README.md](protocol/README.md)).
- **Not** redistribute proprietary APKs, native libraries, embedded cloud keys, assets, or copyrighted template text from Plaud’s app.
- **Not** commit user credentials or device identity material.

Laws vary by jurisdiction. In some places, reverse engineering solely for interoperability of independently created programs is discussed under software / copyright exceptions; that is **not** legal advice. If you contribute protocol work, do so for lawful interoperability with hardware you are authorized to use.

## Privacy

Recordings and transcripts can contain personal data of third parties. Users are responsible for consent and local law (e.g. call-recording rules, LGPD in Brazil). Future speaker-embedding features will be opt-in; voice can be treated as sensitive biometric data.

## License

Source code is under the [Apache License 2.0](../LICENSE).
