# Integration Guide: Tag Reassign Feature

This document outlines the MQTT communication protocol for the new **Tag Reassign** feature implemented in the SysOneScanner app.

## 1. Feature Overview
Reassign Mode allows an administrator to manually link a specific **Barcode** to an **RFID Tag ID**. This is typically used for tag replacement or data correction.

---

## 2. MQTT Communication

### A. Outgoing Request (App → Server)
The scanner publishes this message when the user submits the Reassign form.

- **Topic:** `PPNAM/scanner_{scannerId}/reassign`
- **Example Topic:** `PPNAM/scanner_1/reassign`

**Payload (JSON):**
```json
{
  "ts": "2023-10-27T14:30:05.123Z",
  "deviceId": "scanner_1",
  "tagId": "E28011912000701BD2180182",
  "barcode": "PAL-998877"
}
```


| Field | Type | Description |
| :--- | :--- | :--- |
| `ts` | String | ISO 8601 Timestamp of the request. |
| `deviceId` | String | Unique ID of the scanner (e.g., "scanner_1"). |
| `tagId` | String | The RFID EPC/Hex string scanned. |
| `barcode` | String | The Barcode string scanned. |

---

### B. Incoming Response (Server → App)
The scanner subscribes to this topic to receive feedback.

- **Topic:** `PPNAM/station_{stationId}/reassign_result`
- **Example Topic:** `PPNAM/station_1/reassign_result`

**Expected Success Payload:**
```json
{
  "status": "Success",
  "message": "Tag reassigned successfully to Barcode PAL-998877"
}
```

**Expected Failure Payload:**
```json
{
  "status": "Error",
  "message": "Invalid Barcode: PAL-998877 does not exist in the system."
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | String | Use "Success" for successful operations. Any other value (e.g., "Error", "Fail") triggers a failure state. |
| `message` | String | The text description shown to the user on the scanner screen. |

---

## 3. UI Implementation Details
- **Inputs**: The app populates fields automatically via hardware triggers (Barcode/RFID).
- **Clearing**: Upon a `status: "Success"` response, the app automatically clears the input fields for the next entry.
- **Feedback**: Success messages are shown in green; error messages are shown in red.
