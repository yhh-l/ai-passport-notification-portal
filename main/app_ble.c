#include "app_ble.h"
#include "app_firmware.h"
#include "demo_radio.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
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

#define FIRMWARE_PROTOCOL_VERSION 1
#define FIRMWARE_COMMAND_PREFIX 0xf0
#define FIRMWARE_COMMAND_START 0x01
#define FIRMWARE_COMMAND_FINISH 0x02
#define FIRMWARE_COMMAND_CANCEL 0x03
#define FIRMWARE_COMMAND_CLEAR 0x05
#define FIRMWARE_NAME_MAX 47

static const char *TAG = "passport_ble";
// Custom GATT service: 4a17d400-34ad-4d7b-93f8-84237aacc001
// Every characteristic is encrypted + MITM authenticated.
static const ble_uuid128_t s_service_uuid = BLE_UUID128_INIT(
    0x01,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_write_uuid = BLE_UUID128_INIT(
    0x02,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_control_uuid = BLE_UUID128_INIT(
    0x10,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_data_uuid = BLE_UUID128_INIT(
    0x11,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_firmware_status_uuid = BLE_UUID128_INIT(
    0x12,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);

static QueueHandle_t s_messages;
static volatile bool s_connected;
static volatile bool s_authenticated;
static volatile uint32_t s_passkey = UINT32_MAX;
static uint8_t s_addr_type;
static uint8_t s_rx[161];
static uint16_t s_rx_count, s_rx_expected;
static uint8_t s_rx_type;

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

static int notification_write_access(uint16_t conn_handle, uint16_t attr_handle,
                                     struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    uint8_t chunk[180];
    int size = OS_MBUF_PKTLEN(ctxt->om);
    if (size <= 0 || size > (int)sizeof(chunk) ||
        ble_hs_mbuf_to_flat(ctxt->om, chunk, sizeof(chunk), NULL) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    int offset = 0;
    if (s_rx_expected == 0) {
        if (size < 4 || chunk[0] != 0xa5 || chunk[1] < 1 || chunk[1] > 3) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint16_t length = chunk[2] | ((uint16_t)chunk[3] << 8);
        if (length == 0 || length > 160) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        s_rx_expected = length;
        s_rx_count = 0;
        s_rx_type = chunk[1];
        offset = 4;
    }
    if (size - offset > s_rx_expected - s_rx_count) {
        s_rx_expected = 0;
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    memcpy(s_rx + s_rx_count, chunk + offset, size - offset);
    s_rx_count += size - offset;
    if (s_rx_count == s_rx_expected) {
        app_message_t message = { .type = s_rx_type };
        memcpy(message.text, s_rx, s_rx_count);
        message.text[s_rx_count] = '\0';
        if (s_messages) xQueueSend(s_messages, &message, 0);
        s_rx_expected = 0;
    }
    return 0;
}

static int firmware_control_access(uint16_t conn_handle, uint16_t attr_handle,
                                   struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    uint8_t packet[7 + FIRMWARE_NAME_MAX];
    int size = OS_MBUF_PKTLEN(ctxt->om);
    if (size < 2 || size > (int)sizeof(packet) ||
        ble_hs_mbuf_to_flat(ctxt->om, packet, sizeof(packet), NULL) != 0 ||
        packet[0] != FIRMWARE_COMMAND_PREFIX) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    esp_err_t error = ESP_ERR_INVALID_ARG;
    switch (packet[1]) {
    case FIRMWARE_COMMAND_START: {
        if (size < 7) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        uint8_t name_length = packet[6];
        if (name_length > FIRMWARE_NAME_MAX || size != 7 + name_length) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        char name[FIRMWARE_NAME_MAX + 1];
        memcpy(name, packet + 7, name_length);
        name[name_length] = '\0';
        error = app_firmware_begin(read_u32_le(packet + 2), name);
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
        if (size != 2) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        error = app_firmware_clear();
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
    uint8_t response[13 + FIRMWARE_NAME_MAX];
    const char *name = app_firmware_slot_name();
    size_t name_length = name ? strnlen(name, FIRMWARE_NAME_MAX) : 0;
    response[0] = FIRMWARE_PROTOCOL_VERSION;
    response[1] = (uint8_t)app_firmware_state();
    response[2] = app_firmware_progress();
    response[3] = app_firmware_slot_present() ? 1 : 0;
    write_u32_le(response + 4, (uint32_t)app_firmware_image_size());
    write_u32_le(response + 8, (uint32_t)app_firmware_last_error());
    response[12] = (uint8_t)name_length;
    if (name_length > 0) memcpy(response + 13, name, name_length);
    return os_mbuf_append(ctxt->om, response, 13 + name_length) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static const struct ble_gatt_chr_def s_chars[] = {
    { .uuid = &s_write_uuid.u, .access_cb = notification_write_access,
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
        s_connected = event->connect.status == 0;
        // On a bonded reconnect ENC_CHANGE can arrive before CONNECT. Preserve
        // the authenticated result in that ordering; disconnect/failure clears it.
        if (!s_connected) s_authenticated = false;
        if (!s_connected) s_passkey = UINT32_MAX;
        s_rx_expected = 0;
        ESP_LOGI(TAG, "connection %s, status=0x%x, authenticated=%d",
                 s_connected ? "established" : "failed", event->connect.status,
                 s_authenticated ? 1 : 0);
        if (!s_connected && !ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected, reason=0x%x", event->disconnect.reason);
        s_connected = false;
        s_authenticated = false;
        memset(s_rx, 0, sizeof(s_rx));
        s_passkey = UINT32_MAX;
        s_rx_expected = 0;
        if (app_firmware_busy()) app_firmware_cancel(ESP_ERR_INVALID_STATE);
        if (!ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        ESP_LOGI(TAG, "advertising completed, reason=0x%x", event->adv_complete.reason);
        break;
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        if (event->passkey.params.action == BLE_SM_IOACT_DISP) {
            uint32_t code = esp_random() % 1000000;
            struct ble_sm_io io = { .action = BLE_SM_IOACT_DISP, .passkey = code };
            s_passkey = code;
            ESP_LOGI(TAG, "displaying passkey on device screen");
            ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        break;
    case BLE_GAP_EVENT_ENC_CHANGE: {
        struct ble_gap_conn_desc description;
        bool have_description = ble_gap_conn_find(event->enc_change.conn_handle,
                                                   &description) == 0;
        bool encrypted = have_description && description.sec_state.encrypted;
        bool authenticated = have_description && description.sec_state.authenticated;
        s_authenticated = event->enc_change.status == 0 && encrypted && authenticated;
        ESP_LOGI(TAG, "security change status=0x%x encrypted=%d authenticated=%d",
                 event->enc_change.status, encrypted ? 1 : 0, authenticated ? 1 : 0);
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
    s_messages = xQueueCreate(4, sizeof(app_message_t));
    if (!s_messages) return ESP_ERR_NO_MEM;
    esp_err_t error = demo_radio_nvs_prepare();
    if (error != ESP_OK) return error;
    error = nimble_port_init();
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

bool app_ble_receive(app_message_t *message)
{
    return s_messages && xQueueReceive(s_messages, message, 0) == pdTRUE;
}

bool app_ble_connected(void) { return s_connected; }
bool app_ble_authenticated(void) { return s_authenticated; }
uint32_t app_ble_passkey(void) { return s_passkey; }
