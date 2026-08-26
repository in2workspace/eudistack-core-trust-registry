# LoTE fixtures

Sample Lists of Trusted Entities in the **ETSI TS 119 602** data model, used by the tests and
as the reference for what a provisioned private list looks like.

Field names and URIs follow the model of the European Commission reference library
[`eudi-lib-kmp-etsi-1196x2`](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2)
(`119602-data-model`), so a parser written against these fixtures also reads a real EU list.

## How the EU models this, and where we differ

The EU does not publish one list with mixed roles. It publishes **one LoTE per provider type**,
each with its own `LoTEType`, status determination approach and service type identifiers:

| EU list | Entities it contains |
|---|---|
| `EUWalletProvidersList` | Wallet providers |
| `EUPIDProvidersList` | PID providers |
| `EUPubEAAProvidersList` | Public body EAA providers |
| `EUWRPACProvidersList` | CAs that issue **access certificates** to relying parties |
| `EUWRPRCProvidersList` | CAs that issue **registration certificates** to relying parties |
| `EURegistrarsAndRegistersList` | Registrars and registers |

Note what the relying-party lists hold: **the authorities that certify relying parties**, not the
relying parties themselves. In the EU model an individual relying party is never an entry in a
LoTE. Its identity and its **entitlements** travel in its own access or registration certificate
(ETSI TS 119 475), and a wallet trusts it by chaining that certificate up to a CA found in
`EUWRPACProvidersList`.

Our ecosystem is permissioned and closed, so `eudistack-relying-parties-lote.json` **deviates
deliberately**: the trusted entity is the relying party itself and `ServiceDigitalIdentity` carries
its end-entity certificate, which is what the trusted issuers file of the Verifier expresses today.
The container model is unchanged, so the day an official list covers the role, the deviation
collapses into pointing at the EU list instead of ours.

## Details worth not re-deriving

- **`ServiceStatus` is absent on purpose.** In the EU profiles the set of allowed service statuses
  is empty, and the reference implementation checks that no status is present: membership in the
  list *is* the status, resolved by `StatusDeterminationApproach`. Do not invent a granted URI.
- **`X509Certificates[].val` is base64 of the DER**, with `encoding` naming the encoding. The
  certificates here are throwaway self-signed EC keys generated for tests; they are not secrets.
- **`TETradeName` carries the organisation identifier** (OID 2.5.4.97), which is the key the
  registry resolves trust by.
- The real artefact is **signed**: the JSON is the `LoTE` claim of a JWS. `LoTEFixtureTest` signs a
  fixture and verifies it, which is the same shape the loader will consume.
