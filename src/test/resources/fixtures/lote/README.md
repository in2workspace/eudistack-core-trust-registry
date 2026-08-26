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

Two absences in that table matter more than the entries. There is **no list for qualified EAA
providers**, because a QEAA provider is a qualified trust service provider and therefore appears in
its national Trusted List under TS 119 612, reached through the LOTL. And there is **no list for
non-qualified EAA providers** at all: eIDAS does not supervise them, so Europe never says who they
are. Deciding that is precisely what a private list is for.

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

## What goes where, for our components

| Our component | Its role | Fixture |
|---|---|---|
| Issuer (LEAR, DoctorID, employee credentials) | Non-qualified EAA provider | `eudistack-eaa-providers-lote.json` |
| Verifier, and any customer portal requesting attributes | Relying party | `eudistack-relying-parties-lote.json` |
| Wallet provider | Wallet provider | `eudistack-wallet-providers-lote.json` |

The Issuer does **not** belong in the relying parties list. A relying party is who *requests*
attributes; the Issuer *issues* them. They are separate roles with separate service type
identifiers, and the registry must be able to answer "trusted to issue?" separately from "trusted
to request?" — an entity registered for one role must not resolve as trusted for the other.

The day our Issuer becomes a QEAA provider, its entry stops being ours: it moves to the national
Trusted List and reaches us through the LOTL path instead.

## These fixtures are not a production list

They are the **shape**, not the content. Everything identifying in them is invented: the
certificates are throwaway self-signed EC keys whose private halves were discarded, the
`uri.eudistack.net` namespace is illustrative rather than a domain anyone controls, the postal
address is fiction, and the sequence number is 1. A production list needs real organisation
identifiers, real certificates, a namespace we actually publish under, and a signature from a key
with real custody. Producing those from what the Verifier, the Issuer and the proximity validator
hold today is the import story.

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
