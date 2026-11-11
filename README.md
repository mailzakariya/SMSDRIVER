# TecumSMS Notification Service & Driver for Niagara 4 (`TecumSMS-rt`)

Native, dependency-free **SMS Notification Service & Driver** (`TecumSMS-rt`) for **Niagara 4 Supervisor & JACE** stations (Niagara 4.0 through 4.14+).

## Key Features

- **Drop Under `Services` or `Drivers` (`tsms`)**:
  - **`SmsService` (`tsms:SmsService`)**: Drop directly under `Station -> Config -> Services` (recommended).
  - **`SmsNetwork` (`tsms:SmsNetwork`)**: Drop under `Station -> Config -> Drivers`.
- **Built-in SMS Subscription Website API Key Configuration**:
  - Enter your **Subscription API Key**, **Account / Device ID**, **Sender ID**, and **Subscription Preset** directly on the `SmsService` / `SmsNetwork` Property Sheet, or on individual `HttpGateway` components (`Api Key`, `Username`, `Sender Id`).
  - Ready-made presets in `Palette -> Gateways` for both open-source internet SMS platforms and commercial SMS subscription websites:
    - **`TextBeeCloud`** (`textbee.dev` — Open-Source MIT)
    - **`SmsGateCloud`** (`api.sms-gate.app` — Open-Source Apache-2.0)
    - **`TraccarSmsCloud`** (`traccar.org/sms` — Open-Source Apache-2.0)
    - **`NtfyInternetAlert`** (`ntfy.sh` — Zero-SIM Mobile Push Alert)
    - **`TwilioSmsGateway`** (Twilio SMS API Subscription)
    - **`ClickSendSmsGateway`** (ClickSend Cloud SMS Subscription)
    - **`VonageSmsGateway`** (Vonage / Nexmo SMS Subscription)
    - **`TextbeltSmsGateway`** (Textbelt SMS API Subscription)
    - **`MessageBirdSmsGateway`** (MessageBird SMS Subscription)
    - **`JasminHttpGateway`**, **`PlaySmsGateway`**, **`KannelGateway`**
- **Send SMS via Niagara Email (`Email-to-SMS`)**:
  - **Built-in on `SmsService` / `SmsNetwork`**: Set `Enable Niagara Email Sms = true`, select `Email Service Ord` (`station:|slot:/Services/EmailService`), and set your `Email Sms Domain` (`sms.clicksend.com`, `textmagic.com`, `vtext.com`, `txt.att.net`, `tmomail.net`, etc.) and `Email Sms To Format` (`{phoneDigits}@{domain}`).
  - **Dedicated `EmailSmsGateway` Component (`Palette -> Gateways -> EmailSmsGateway`)**: Supports routing SMS through Niagara's `EmailService` / `OutgoingAccount` (`Use Niagara EmailService = true`) **or** via a built-in pure-Java SMTP/SMTPS/STARTTLS client (`Use Niagara EmailService = false`).
- **Contacts, Groups, Point Rules, Wire-Sheet Triggers & Alarm Routing**:
  - **`SmsContact` & `SmsGroup`**: Address book with E.164 phone numbers, On-Duty days/hours, and checkbox group membership.
  - **`SmsPointRule`**: Monitors any Niagara point ORD (`>`, `<`, `>=`, `<=`, `=`, `!=`, range, alarm/fault/down status) with active delay, repeat interval, deadband, and normal-return SMS.
  - **`SmsSender`**: Wire-Sheet boolean rising-edge SMS trigger.
  - **`SmsAlarmRecipient`**: Routes Niagara `AlarmService` alarm classes directly to SMS contacts and groups.

---

## Installation in Niagara 4 Supervisor / Workbench

1. **Import the Tecum Root CA Certificate (Workbench & Platform)**:
   - **Workbench Trust Store (`My Host`)**: Open Niagara Workbench -> **My Host -> Certificate Management -> User Trust Store** (or **Tools -> Certificate Management -> User Trust Store**), click **Import**, and select [`certs/TecumRootCA.pem`](certs/TecumRootCA.pem) (or [`certs/TecumRootCA.cer`](certs/TecumRootCA.cer)).
   - **Platform / Station Trust Store (`Platform`)**: In the Nav tree, expand **Platform -> Certificate Management -> User Trust Store** (or **Station -> Config -> Services -> PlatformServices -> CertManagerService -> User Trust Store**), click **Import**, and select [`certs/TecumRootCA.pem`](certs/TecumRootCA.pem).
2. **Install the Module JAR**:
   - Copy [`dist/TecumSMS-rt.jar`](dist/TecumSMS-rt.jar) to your Niagara `modules` directory (e.g., `C:\Niagara\Niagara-4.x.x.x\modules\TecumSMS-rt.jar`), or run:
     ```powershell
     .\build.ps1 -Install
     ```
   - Restart the Niagara Station and Niagara Workbench.
3. **Add to Station**:
   - Open the **`TecumSMS`** palette in Workbench and drag **`SmsService`** under `station:|slot:/Services` (or **`SmsNetwork`** under `station:|slot:/Drivers`).

---

## Module Signatures & Certificate Details

### Signed Module Artifact
| Field | Value |
|---|---|
| **Module File** | [`dist/TecumSMS-rt.jar`](dist/TecumSMS-rt.jar) |
| **Module Name / Symbol** | `TecumSMS-rt` (`tsms`) |
| **Vendor / Version** | `Tecum` / `1.0.0` |
| **JAR SHA-256 Digest** | `C818E10D378D7F6AD343BB6649F531552EB3F2139C577EC53BA7CD569EFAC8BF` |
| **Signature Files** | `META-INF/TECUM.SF`, `META-INF/TECUM.RSA` |
| **Signature Algorithm** | `SHA256withRSA` (2048-bit RSA, `SHA-256` entry digests) |
| **`MANIFEST.MF` Main-Attributes Digest** | `UKyVs9x6Up//559D1s8UIfiyG3khT3/NHmB/l/P/aNw=` |
| **`MANIFEST.MF` Full Digest** | `lWi8yi4o16Obs7y9yWWj6RJnvponURTJbQl/vryCAQ4=` |

### Code Signing Certificate ([`certs/TecumCodeSigner.pem`](certs/TecumCodeSigner.pem))
| Field | Value |
|---|---|
| **Subject DN** | `CN=Tecum Module Signer, OU=Building Automation, O=Tecum, L=Dubai, ST=Dubai, C=AE` |
| **Issuer DN** | `CN=Tecum Root CA, OU=Building Automation, O=Tecum, L=Dubai, ST=Dubai, C=AE` |
| **Serial Number** | `7f1b71a9` |
| **Validity** | `2026-11-11` to `2036-11-08` |
| **Key Usage / EKU** | `DigitalSignature` (critical), `codeSigning (1.3.6.1.5.5.7.3.3)` (critical) |
| **SHA-256 Fingerprint** | `68:55:87:5C:3A:A8:54:DA:7B:C0:CF:A7:81:E2:2E:42:11:90:BC:44:BE:32:4F:D7:A9:EF:EC:7D:21:CF:BF:52` |
| **SHA-1 Fingerprint** | `F9:F0:A5:8B:04:75:94:29:23:54:87:10:0B:CD:C6:C2:F0:80:DA:45` |

### Root CA Certificate ([`certs/TecumRootCA.pem`](certs/TecumRootCA.pem))
| Field | Value |
|---|---|
| **Subject / Issuer DN** | `CN=Tecum Root CA, OU=Building Automation, O=Tecum, L=Dubai, ST=Dubai, C=AE` |
| **Serial Number** | `2095868` |
| **Validity** | `2026-11-11` to `2036-11-08` |
| **Basic Constraints / Key Usage** | `CA:true, pathlen:1` (critical), `Key_CertSign, Crl_Sign` (critical) |
| **SHA-256 Fingerprint** | `76:A4:88:9A:EB:2E:3B:8F:25:26:CB:6A:E7:65:4D:9C:70:1D:C1:E7:FE:4F:B8:BA:99:6B:89:A1:6C:2B:05:9C` |
| **SHA-1 Fingerprint** | `60:F5:38:9F:0C:77:8E:BF:49:13:67:68:FC:D8:D1:E4:BD:FE:C6:71` |

---

## Building & Testing

```powershell
# Run standalone protocol & rule engine unit tests (26 test suites)
.\build.ps1 -Test

# Full build, test, package, sign, and verify with Niagara nverify.exe
.\build.ps1

# Build, sign, and copy dist\TecumSMS-rt.jar into C:\Niagara\Niagara-4.x.x.x\modules
.\build.ps1 -Install
```
