# Ignitechs SMS Notification Driver for Niagara 4 (`ignitechsSms-rt`)

A zero-dependency **SMS Notification Driver** (`ignitechsSms-rt`) for **Niagara 4 Supervisor & JACE** stations. Supports **GSM/LTE AT Modems (Serial/USB & TCP)**, **SMPP v3.4**, and **HTTP(S) SMS Gateways**, with automatic gateway failover, retry queue, rate limiting, duplicate suppression, address book with on-duty schedules, point condition rules, wire-sheet triggers, alarm routing, and two-way SMS alarm acknowledgement (`ACK <code>` / `STATUS`).

---

## Features

### 1. Open-Source Internet & Network SMS Gateways (Priority & Failover)
Place one or more gateways inside `SmsNetwork` (or `SmsGatewayFolder`). Gateways are tried in `priority` order (`1` = primary, `2` = backup, etc.):
- **`HttpGateway` (Open-Source Internet SMS Platforms & REST APIs)** — Ready-to-use palette items and presets:
  - **`TextBeeCloud` ([textbee.dev](https://textbee.dev) — Open-Source MIT)** — Cloud or self-hosted internet SMS gateway (`https://api.textbee.dev/api/v1/gateway/devices/{username}/send-sms`, `username` = Device ID, `password` = API Key).
  - **`SmsGateCloud` ([capcom6/android-sms-gateway](https://github.com/capcom6/android-sms-gateway) — Open-Source Apache-2.0)** — Internet Cloud mode (`https://api.sms-gate.app/3rdparty/v1/message` with HTTP Basic Auth) or Local HTTP mode (`http://<phone-ip>:8080/message`).
  - **`TraccarSmsCloud` ([traccar/traccar-sms-gateway](https://github.com/traccar/traccar-sms-gateway) — Open-Source Apache-2.0)** — Internet Cloud API (`https://www.traccar.org/sms/`, `password` = Cloud API Token).
  - **`NtfyInternetAlert` ([ntfy.sh](https://ntfy.sh) — Open-Source Apache-2.0/GPLv2)** — 100% free internet mobile push/alert service requiring no SIM card or account (`https://ntfy.sh/{username}`, `username` = topic name).
  - **`JasminHttpGateway` ([jookies/jasmin](https://github.com/jookies/jasmin) — Open-Source Apache-2.0)** — Carrier-grade open-source HTTP SMS gateway (`http://<jasmin-host>:1401/send`).
  - **`PlaySmsGateway` ([playsms.org](https://playsms.org) — Open-Source)** — Webservice API (`index.php?app=ws&op=pv`).
  - **`KannelGateway` ([kannel.org](https://www.kannel.org) — Open-Source)** — `sendsms` HTTP interface.
  - **`Twilio` / `TeltonikaRouter` / `Custom`** — Full custom URL, HTTP method, headers, JSON/URL-encoded body, and regex validation.
- **`SmppGateway`** — Native SMPP v3.4 Transmitter client (`bind_transmitter`, `submit_sm`, `enquire_link`, `unbind`) with optional TLS, alphanumeric/international `senderId`, and concatenated GSM-7 / UCS-2 segmentation (compatible with open-source **Jasmin** and **Kannel** SMPP servers or carrier SMPP trunks).
- **`TcpModemGateway`** — Network GSM/LTE modem over raw TCP.

### 2. Address Book (`Contacts` & `Groups`)
- **`SmsContact`** — Phone number (automatic E.164 normalization), `onDutyDays` (`BDaysOfWeekBits`), `onDutyTime` (`BTimeRange`), `allowSmsCommands` toggle, per-contact sent counter, and `Send Test SMS` action.
- **`SmsGroup`** — Named group of contacts (e.g., `Maintenance`, `Night Shift`) with a live auto-synced checklist of contacts.
- **`SmsRecipientList`** — Dynamic boolean checklist embedded on every rule, sender, and alarm recipient that automatically stays synchronized when contacts or groups are added, renamed, or deleted.

### 3. Point Monitoring Rules (`SmsPointRule`) & Wire-Sheet `SmsSender`
- **`SmsPointRule`** — Subscribes to any Niagara `BControlPoint` or status/value slot (`sourceOrd`) without modifying the monitored point:
  - **Conditions (`BSmsCondition`)**: `Greater than (>)`, `Less than (<)`, `Greater or equal (>=)`, `Less or equal (<=)`, `Outside range`, `Inside range`, `Value equals (=)` (supports comma/`|` lists such as `Fault|Trip|Alarm`), `Value not equals (!=)`, `Is true / active`, `Is false / inactive`, `Any value change`, `Status is Alarm`, `Status is Fault`, `Status is Down (offline)`, `Status is Overridden`, `Status is Not OK`.
  - **Filtering & Hysteresis**: Configurable `deadband`, `activeDelay` (filters short spikes), `repeatInterval` & `maxRepeats`, and `notifyOnNormal`.
  - **Placeholders**: `{station}`, `{time}`, `{point}`, `{path}`, `{value}`, `{status}`, `{state}`, `{rule}`, `{condition}`, `{limit}`, `{low}`, `{high}`, `{compare}`, `{description}`.
- **`SmsSender`** — Wire-sheet component that sends a templated or linked string SMS on a rising-edge boolean `trigger` or via its `send` / `sendText` actions.

### 4. Niagara Alarm Recipient & Two-Way SMS Commands
- **`SmsAlarmRecipient`** — Standard `BAlarmRecipient` that drops into `AlarmService` and links from any `AlarmClass`. Formats `{station}`, `{time}`, `{source}`, `{msg}`, `{state}`, `{alarmClass}`, `{priority}`, `{value}`, `{ackCode}`, `{ackHint}`, `{user}`.
- **Two-Way SMS Acknowledgement (via Serial/TCP Modem Gateways)**:
  - Each unacknowledged alarm gets a short numeric code (e.g., `Reply ACK 104`).
  - Authorized contacts (`allowSmsCommands = true`) can reply:
    - `ACK <code>` (or `A <code>`) — Acknowledges the alarm in Niagara's `BAlarmService` and replies with confirmation.
    - `STATUS` (or `S`) — Replies with a summary of currently active SMS rules.

---

## Installation in Niagara 4 Supervisor / Workbench

1. **Import the Ignitechs Root CA Certificate**:
   - Open Niagara Workbench -> **Tools** -> **Certificate Management** -> **User Trust Store** (and Platform/Station Trust Store if signed module verification is enforced).
   - Click **Import** and select [`certs/IgnitechsRootCA.pem`](certs/IgnitechsRootCA.pem) (or [`certs/IgnitechsRootCA.cer`](certs/IgnitechsRootCA.cer)).
2. **Install the Module JAR**:
   - Copy [`dist/ignitechsSms-rt.jar`](dist/ignitechsSms-rt.jar) to your Niagara `modules` directory (e.g., `C:\Niagara\Niagara-4.14.0.162\modules\ignitechsSms-rt.jar`), or run:
     ```powershell
     .\build.ps1 -Install
     ```
   - Restart the Niagara Station / Workbench.
3. **Add to Station**:
   - Open the **`ignitechsSms`** palette in Workbench and drag **`SmsNetwork`** under `station:|slot:/Drivers`, or drag **`SmsAlarmRecipient`** under `station:|slot:/Services/AlarmService`.

---

## Module Signatures & Certificate Details

### Signed Module Artifact
| Field | Value |
|---|---|
| **Module File** | `dist/ignitechsSms-rt.jar` |
| **Module Name / Symbol** | `ignitechsSms-rt` (`isms`) |
| **Vendor / Version** | `Ignitechs` / `1.0.0` |
| **JAR SHA-256 Digest** | `3645E34B653A60092D102A0C6736D1EB884DB6089087E306E3D420F9842CE5AB` |
| **Signature Files** | `META-INF/IGNITECH.SF`, `META-INF/IGNITECH.RSA` |
| **Signature Algorithm** | `SHA256withRSA` (2048-bit RSA, `SHA-256` entry digests) |
| **`MANIFEST.MF` Main-Attributes Digest** | `QmS47fn9fWSd89F6hK9RKBfJkaHyIJcOLdKNuGi+dVY=` |
| **`MANIFEST.MF` Full Digest** | `/dWI7XOeFXiQnLRn3YOlIiqCBuFXgmTKfz2KfDkUhpU=` |
| **`META-INF/module.xml` Digest** | `7VJ3ZphCqQ+oftneUZtkC7FQV85CrdxgDZ51MRTk3UY=` |

### Code Signing Certificate (`certs/IgnitechsCodeSigner.pem`)
| Field | Value |
|---|---|
| **Subject DN** | `CN=Ignitechs Module Signer, OU=Building Automation, O=Ignitechs, L=Dubai, ST=Dubai, C=AE` |
| **Issuer DN** | `CN=Ignitechs Root CA, OU=Building Automation, O=Ignitechs, L=Dubai, ST=Dubai, C=AE` |
| **Serial Number** | `4f34d73e` |
| **Validity** | `2026-11-09` to `2036-11-06` |
| **Key Usage / EKU** | `DigitalSignature` (critical), `codeSigning (1.3.6.1.5.5.7.3.3)` (critical) |
| **SHA-256 Fingerprint** | `8A:78:23:89:2B:A0:1D:9E:D2:05:A7:34:6C:EA:FB:F5:18:86:F1:63:AB:77:BE:DF:7C:09:04:7A:B3:AD:C7:B9` |
| **SHA-1 Fingerprint** | `D9:61:FC:44:C5:83:1B:58:2B:EE:A9:A6:C7:23:72:13:AC:57:C0:8D` |

### Root CA Certificate (`certs/IgnitechsRootCA.pem`)
| Field | Value |
|---|---|
| **Subject / Issuer DN** | `CN=Ignitechs Root CA, OU=Building Automation, O=Ignitechs, L=Dubai, ST=Dubai, C=AE` |
| **Serial Number** | `1206db9e` |
| **Validity** | `2026-11-09` to `2036-11-06` |
| **Basic Constraints / Key Usage** | `CA:true, pathlen:1` (critical), `Key_CertSign, Crl_Sign` (critical) |
| **SHA-256 Fingerprint** | `A9:A9:F0:A8:2C:1C:5F:4C:9D:86:1A:A5:11:22:CA:D4:D3:4F:6C:D4:71:3F:DE:B0:D4:0B:9E:0E:82:2E:67:F4` |
| **SHA-1 Fingerprint** | `DC:D7:38:22:8C:9E:9F:65:8D:83:93:BB:D6:7C:77:78:00:7F:4B:53` |

---

## Building & Testing

```powershell
# Run standalone protocol & rule engine unit tests (24 test suites)
.\build.ps1 -Test

# Full build, test, package, sign, and verify with Niagara nverify.exe
.\build.ps1

# Build, sign, and copy dist\ignitechsSms-rt.jar into C:\Niagara\Niagara-4.14.0.162\modules
.\build.ps1 -Install
```
