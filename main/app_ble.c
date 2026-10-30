#include "app_ble.h"
#include "app_firmware.h"

#include "esp_log.h"
#include "esp_random.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include <string.h>

#define FIRMWARE_PROTOCOL_VERSION 3
static uint8_t s_status_cursor;
#define FIRMWARE_COMMAND_PREFIX 0xf0
#define FIRMWARE_COMMAND_START 0x01
#define FIRMWARE_COMMAND_FINISH 0x02
#define FIRMWARE_COMMAND_CANCEL 0x03
#define FIRMWARE_COMMAND_CLEAR 0x05
#define FIRMWARE_COMMAND_SELECT 0x06

static const char *TAG = "passport_ble";
// Keep the service and compatibility characteristic stable for older APKs.
static const ble_uuid128_t s_service_uuid = BLE_UUID128_INIT(
    0x01,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_compat_write_uuid = BLE_UUID128_INIT(
    0x02,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_control_uuid = BLE_UUID128_INIT(
    0x10,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_data_uuid = BLE_UUID128_INIT(
    0x11,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_status_uuid = BLE_UUID128_INIT(
    0x12,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);

static volatile bool s_connected;
static volatile bool s_authenticated;
static volatile uint32_t s_passkey = UINT32_MAX;
static uint8_t s_addr_type;

static uint32_t read_u32_le(const uint8_t *data)
{
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
}

static void write_u32_le(uint8_t *data, uint32_t value)
{
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8);
    data[2] = (uint8_t)(value >> 16);
    data[3] = (uint8_t)(value >> 24);
}

static int compatibility_write_access(uint16_t conn_handle, uint16_t attr_handle,
                                      struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    int size = OS_MBUF_PKTLEN(ctxt->om);
    return size > 0 && size <= 512 ? 0 : BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
}

static int firmware_control_access(uint16_t conn_handle, uint16_t attr_handle,
                                   struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    uint8_t packet[8 + APP_FIRMWARE_NAME_MAX];
    int size = OS_MBUF_PKTLEN(ctxt->om);
    if (size < 2 || size > (int)sizeof(packet) ||
        ble_hs_mbuf_to_flat(ctxt->om, packet, sizeof(packet), NULL) != 0 ||
        packet[0] != FIRMWARE_COMMAND_PREFIX) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    if (packet[1] != 0x07) s_status_cursor = 0;
    esp_err_t error = ESP_ERR_INVALID_ARG;
    switch (packet[1]) {
    case 0x07:
        if (size != 3 || packet[2] >= APP_FIRMWARE_MAX_SLOTS) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        s_status_cursor = packet[2];
        error = ESP_OK;
        break;
    case FIRMWARE_COMMAND_START: {
        uint8_t slot = APP_FIRMWARE_AUTO_SLOT;
        uint32_t image_size;
        uint8_t name_length;
        const uint8_t *name_source;
        if (size >= 8 && packet[7] <= APP_FIRMWARE_NAME_MAX && size == 8 + packet[7]) {
            slot = packet[2];
            image_size = read_u32_le(packet + 3);
            name_length = packet[7];
            name_source = packet + 8;
        } else if (size >= 7 && packet[6] <= APP_FIRMWARE_NAME_MAX &&
                   size == 7 + packet[6]) {
            image_size = read_u32_le(packet + 2);
            name_length = packet[6];
            name_source = packet + 7;
        } else {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        char name[APP_FIRMWARE_NAME_MAX + 1];
        memcpy(name, name_source, name_length);
        name[name_length] = '\0';
        error = app_firmware_begin(slot, image_size, name);
        break;
    }
    case FIRMWARE_COMMAND_FINISH:
        if (size != 2) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        error = app_firmware_finish();
        break;
    case FIRMWARE_COMMAND_CANCEL:
        if (size != 2) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        app_firmware_cancel(ESP_ERR_INVALID_STATE);
        error = ESP_OK;
        break;
    case FIRMWARE_COMMAND_CLEAR:
        if (size != 3) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        error = app_firmware_clear(packet[2]);
        break;
    case FIRMWARE_COMMAND_SELECT:
        if (size != 3) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        error = app_firmware_select(packet[2]);
        break;
    default:
        return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
    }
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "firmware control 0x%02x failed: %s", packet[1],
                 esp_err_to_name(error));
        return BLE_ATT_ERR_UNLIKELY;
    }
    return 0;
}

static int firmware_data_access(uint16_t conn_handle, uint16_t attr_handle,
                                struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    uint8_t chunk[512];
    int size = OS_MBUF_PKTLEN(ctxt->om);
    if (size <= 0 || size > (int)sizeof(chunk) ||
        ble_hs_mbuf_to_flat(ctxt->om, chunk, sizeof(chunk), NULL) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    return app_firmware_write(chunk, (size_t)size) == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
}

static int firmware_status_access(uint16_t conn_handle, uint16_t attr_handle,
                                  struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    uint8_t response[192];
    size_t offset = 0;
    response[offset++] = FIRMWARE_PROTOCOL_VERSION;
    response[offset++] = (uint8_t)app_firmware_state();
    response[offset++] = app_firmware_progress();
    response[offset++] = app_firmware_selected_slot();
    response[offset++] = app_firmware_active_slot();
    response[offset++] = 0; // number of records in this page
    write_u32_le(response + offset, (uint32_t)app_firmware_last_error());
    offset += 4;
    write_u32_le(response + offset, (uint32_t)app_firmware_total_free());
    offset += 4;
    write_u32_le(response + offset, (uint32_t)app_firmware_largest_free_slot());
    offset += 4;

    response[offset++] = 0xff; // next cursor, or end of snapshot
    response[offset++] = s_status_cursor;
    uint8_t first_empty = 0xff;
    for (uint8_t i = 0; i < app_firmware_slot_count(); ++i) {
        app_firmware_slot_info_t info;
        app_firmware_slot_info(i, &info);
        if (!info.present) { first_empty = i; break; }
    }
    for (uint8_t slot = s_status_cursor; slot < app_firmware_slot_count(); ++slot) {
        app_firmware_slot_info_t info;
        app_firmware_slot_info(slot, &info);
        if (!info.present && slot != first_empty) continue;
        if (response[5] == 2) { response[18] = slot; break; }
        size_t name_length = info.present ? strnlen(info.name, APP_FIRMWARE_NAME_MAX) : 0;
        response[5]++;
        response[offset++] = slot;
        response[offset++] = (info.available ? 0x01 : 0) | (info.present ? 0x02 : 0);
        write_u32_le(response + offset, (uint32_t)info.capacity);
        offset += 4;
        write_u32_le(response + offset, (uint32_t)info.image_size);
        offset += 4;
        response[offset++] = (uint8_t)name_length;
        if (name_length > 0) {
            memcpy(response + offset, info.name, name_length);
            offset += name_length;
        }
    }
    return os_mbuf_append(ctxt->om, response, offset) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static const struct ble_gatt_chr_def s_chars[] = {
    { .uuid = &s_compat_write_uuid.u, .access_cb = compatibility_write_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN },
    { .uuid = &s_firmware_control_uuid.u, .access_cb = firmware_control_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN },
    { .uuid = &s_firmware_data_uuid.u, .access_cb = firmware_data_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN },
    { .uuid = &s_firmware_status_uuid.u, .access_cb = firmware_status_access,
      .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_READ_AUTHEN },
    { 0 }
};

static const struct ble_gatt_svc_def s_services[] = {
    { .type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &s_service_uuid.u,
      .characteristics = s_chars },
    { 0 }
};

static int gap_event(struct ble_gap_event *event, void *arg);

static void advertise(void)
{
    struct ble_hs_adv_fields fields = { 0 };
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (const uint8_t *)"PassportNotify";
    fields.name_len = strlen((const char *)fields.name);
    fields.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    struct ble_gap_adv_params params = { 0 };
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    if (rc == 0) rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER,
                                        &params, gap_event, NULL);
    if (rc != 0) ESP_LOGE(TAG, "advertise error %d", rc);
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        s_status_cursor = 0;
        s_connected = event->connect.status == 0;
        if (!s_connected) s_authenticated = false;
        if (!s_connected) s_passkey = UINT32_MAX;
        if (!s_connected && !ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        s_status_cursor = 0;
        s_connected = false;
        s_authenticated = false;
        s_passkey = UINT32_MAX;
        if (app_firmware_busy()) app_firmware_cancel(ESP_ERR_INVALID_STATE);
        if (!ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        if (event->passkey.params.action == BLE_SM_IOACT_DISP) {
            uint32_t code = esp_random() % 1000000;
            struct ble_sm_io io = { .action = BLE_SM_IOACT_DISP, .passkey = code };
            s_passkey = code;
            ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        break;
    case BLE_GAP_EVENT_ENC_CHANGE: {
        struct ble_gap_conn_desc description;
        bool have_description = ble_gap_conn_find(event->enc_change.conn_handle,
                                                   &description) == 0;
        s_authenticated = event->enc_change.status == 0 && have_description &&
                          description.sec_state.encrypted &&
                          description.sec_state.authenticated;
        s_passkey = UINT32_MAX;
        break;
    }
    default:
        break;
    }
    return 0;
}

static void on_reset(int reason)
{
    s_status_cursor = 0;
    app_firmware_cancel(ESP_ERR_INVALID_STATE);
    s_connected = false;
    s_authenticated = false;
    ESP_LOGW(TAG, "host reset %d", reason);
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) rc = ble_hs_id_infer_auto(0, &s_addr_type);
    if (rc == 0) advertise();
    else ESP_LOGE(TAG, "BLE sync error %d", rc);
}

static void host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

esp_err_t app_ble_start(void)
{
    esp_err_t error = nimble_port_init();
    if (error != ESP_OK) return error;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("PassportNotify");
    int rc = ble_gatts_count_cfg(s_services);
    if (rc == 0) rc = ble_gatts_add_svcs(s_services);
    if (rc != 0) return ESP_FAIL;
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    nimble_port_freertos_init(host_task);
    return ESP_OK;
}

bool app_ble_connected(void) { return s_connected; }
bool app_ble_authenticated(void) { return s_authenticated; }
uint32_t app_ble_passkey(void) { return s_passkey; }
