#include "app_ble.h"
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
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include <string.h>

static const char *TAG = "passport_ble";
// Custom GATT service: 4a17d400-34ad-4d7b-93f8-84237aacc001
// Encrypted + MITM-authenticated writes only. Phone must pair using the displayed passkey.
static const ble_uuid128_t s_service_uuid = BLE_UUID128_INIT(
    0x01,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static const ble_uuid128_t s_write_uuid = BLE_UUID128_INIT(
    0x02,0xc0,0xac,0x7a,0x23,0x84,0xf8,0x93,0x7b,0x4d,0xad,0x34,0x00,0xd4,0x17,0x4a);
static QueueHandle_t s_messages;
static volatile bool s_connected;
static volatile bool s_authenticated;
static volatile uint32_t s_passkey = UINT32_MAX;
static uint8_t s_addr_type;
static uint8_t s_rx[161];
static uint16_t s_rx_count, s_rx_expected;
static uint8_t s_rx_type;

static int write_access(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)arg;
    uint8_t chunk[180];
    int size = OS_MBUF_PKTLEN(ctxt->om);
    if (size <= 0 || size > (int)sizeof(chunk) ||
        ble_hs_mbuf_to_flat(ctxt->om, chunk, sizeof(chunk), NULL) != 0) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;

    int offset = 0;
    if (s_rx_expected == 0) {
        if (size < 4 || chunk[0] != 0xa5 || chunk[1] < 1 || chunk[1] > 3) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
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
        app_message_t msg = { .type = s_rx_type };
        memcpy(msg.text, s_rx, s_rx_count);
        msg.text[s_rx_count] = '\0';
        if (s_messages) xQueueSend(s_messages, &msg, 0); // bounded; drop when UI is busy
        s_rx_expected = 0;
    }
    return 0;
}

static const struct ble_gatt_chr_def s_chars[] = {
    { .uuid = &s_write_uuid.u, .access_cb = write_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN },
    { 0 }
};
static const struct ble_gatt_svc_def s_services[] = {
    { .type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &s_service_uuid.u, .characteristics = s_chars },
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
    if (rc == 0) rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
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
        // ESP-IDF NimBLE automatically restarts legacy advertising after the
        // controller's 0x3e establishment failure. Starting it again from an
        // ADV_COMPLETE callback races that recovery and can leave BLE idle.
        if (!s_connected && !ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected, reason=0x%x", event->disconnect.reason);
        s_connected = false;
        s_authenticated = false;
        memset(s_rx, 0, sizeof(s_rx));
        s_passkey = UINT32_MAX;
        s_rx_expected = 0;
        if (!ble_gap_adv_active()) advertise();
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        ESP_LOGI(TAG, "advertising completed, reason=0x%x", event->adv_complete.reason);
        // Do not immediately restart here: NimBLE performs its own advertising
        // recovery for 0x3e connection-establishment failures.
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
        struct ble_gap_conn_desc desc;
        bool have_desc = ble_gap_conn_find(event->enc_change.conn_handle, &desc) == 0;
        bool encrypted = have_desc && desc.sec_state.encrypted;
        bool authenticated = have_desc && desc.sec_state.authenticated;
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
static void on_reset(int reason) { s_connected = false; s_authenticated = false; ESP_LOGW(TAG, "host reset %d", reason); }
static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) rc = ble_hs_id_infer_auto(0, &s_addr_type);
    if (rc == 0) advertise(); else ESP_LOGE(TAG, "BLE sync error %d", rc);
}
static void host_task(void *arg) { (void)arg; nimble_port_run(); nimble_port_freertos_deinit(); }

esp_err_t app_ble_start(void)
{
    s_messages = xQueueCreate(4, sizeof(app_message_t));
    if (!s_messages) return ESP_ERR_NO_MEM;
    esp_err_t err = demo_radio_nvs_prepare();
    if (err != ESP_OK) return err;
    err = nimble_port_init();
    if (err != ESP_OK) return err;
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
bool app_ble_receive(app_message_t *message) { return s_messages && xQueueReceive(s_messages, message, 0) == pdTRUE; }
bool app_ble_connected(void) { return s_connected; }
bool app_ble_authenticated(void) { return s_authenticated; }
uint32_t app_ble_passkey(void) { return s_passkey; }
