#include "app_firmware.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "nvs.h"
#include <stdio.h>
#include <string.h>

#define FIRMWARE_NAMESPACE "fw_portal"
#define FIRMWARE_SLOT_LABEL "user_app"
#define FIRMWARE_NAME_MAX 47

static const char *TAG = "passport_fw";
static const esp_partition_t *s_slot;
static esp_ota_handle_t s_ota_handle;
static volatile app_firmware_state_t s_state;
static volatile size_t s_expected_size;
static volatile size_t s_written_size;
static volatile size_t s_installed_size;
static volatile esp_err_t s_last_error;
static char s_slot_name[FIRMWARE_NAME_MAX + 1] = "玩法槽";

static void copy_name(char *destination, size_t destination_size, const char *source)
{
    if (!destination || destination_size == 0) return;
    snprintf(destination, destination_size, "%s", source && *source ? source : "自定义玩法");
}

static void load_metadata(void)
{
    nvs_handle_t handle;
    if (nvs_open(FIRMWARE_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) return;

    uint8_t valid = 0;
    uint32_t size = 0;
    size_t name_size = sizeof(s_slot_name);
    if (nvs_get_u8(handle, "valid", &valid) == ESP_OK && valid == 1 &&
        nvs_get_u32(handle, "size", &size) == ESP_OK) {
        if (nvs_get_str(handle, "name", s_slot_name, &name_size) != ESP_OK) {
            copy_name(s_slot_name, sizeof(s_slot_name), "自定义玩法");
        }
        s_installed_size = size;
    }
    nvs_close(handle);
}

static bool image_is_valid(void)
{
    if (!s_slot || s_installed_size == 0 || s_installed_size > s_slot->size) return false;
    esp_app_desc_t description;
    return esp_ota_get_partition_description(s_slot, &description) == ESP_OK &&
           description.magic_word == ESP_APP_DESC_MAGIC_WORD;
}

static void save_metadata(bool valid)
{
    nvs_handle_t handle;
    if (nvs_open(FIRMWARE_NAMESPACE, NVS_READWRITE, &handle) != ESP_OK) return;
    if (valid) {
        nvs_set_str(handle, "name", s_slot_name);
        nvs_set_u32(handle, "size", (uint32_t)s_installed_size);
        nvs_set_u8(handle, "valid", 1);
    } else {
        nvs_erase_key(handle, "name");
        nvs_erase_key(handle, "size");
        nvs_set_u8(handle, "valid", 0);
    }
    nvs_commit(handle);
    nvs_close(handle);
}

static esp_err_t fail_transfer(esp_err_t error)
{
    if (s_ota_handle != 0) {
        esp_ota_abort(s_ota_handle);
        s_ota_handle = 0;
    }
    s_state = APP_FIRMWARE_ERROR;
    s_last_error = error;
    ESP_LOGE(TAG, "firmware transfer failed: %s", esp_err_to_name(error));
    return error;
}

void app_firmware_init(void)
{
    s_slot = esp_partition_find_first(ESP_PARTITION_TYPE_APP,
                                      ESP_PARTITION_SUBTYPE_APP_OTA_0,
                                      FIRMWARE_SLOT_LABEL);
    s_state = APP_FIRMWARE_IDLE;
    s_last_error = ESP_OK;
    load_metadata();
    if (image_is_valid()) {
        s_state = APP_FIRMWARE_READY;
        ESP_LOGI(TAG, "firmware slot ready: %s (%u bytes)", s_slot_name,
                 (unsigned)s_installed_size);
    } else {
        s_installed_size = 0;
        save_metadata(false);
        if (!s_slot) ESP_LOGE(TAG, "firmware slot partition not found");
    }
}

esp_err_t app_firmware_begin(size_t image_size, const char *display_name)
{
    if (!s_slot) return ESP_ERR_NOT_FOUND;
    if (s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    if (image_size < 64 || image_size > s_slot->size) return ESP_ERR_INVALID_SIZE;

    s_expected_size = image_size;
    s_written_size = 0;
    s_installed_size = 0;
    save_metadata(false);
    s_last_error = ESP_OK;
    copy_name(s_slot_name, sizeof(s_slot_name), display_name);
    esp_err_t error = esp_ota_begin(s_slot, image_size, &s_ota_handle);
    if (error != ESP_OK) return fail_transfer(error);
    s_state = APP_FIRMWARE_RECEIVING;
    ESP_LOGI(TAG, "receiving firmware: %s (%u bytes)", s_slot_name,
             (unsigned)s_expected_size);
    return ESP_OK;
}

esp_err_t app_firmware_write(const uint8_t *data, size_t size)
{
    if (s_state != APP_FIRMWARE_RECEIVING || s_ota_handle == 0) return ESP_ERR_INVALID_STATE;
    if (!data || size == 0 || size > s_expected_size - s_written_size) {
        return fail_transfer(ESP_ERR_INVALID_SIZE);
    }
    esp_err_t error = esp_ota_write(s_ota_handle, data, size);
    if (error != ESP_OK) return fail_transfer(error);
    s_written_size += size;
    return ESP_OK;
}

esp_err_t app_firmware_finish(void)
{
    if (s_state != APP_FIRMWARE_RECEIVING || s_ota_handle == 0) return ESP_ERR_INVALID_STATE;
    if (s_written_size != s_expected_size) return fail_transfer(ESP_ERR_INVALID_SIZE);

    esp_ota_handle_t handle = s_ota_handle;
    s_ota_handle = 0;
    esp_err_t error = esp_ota_end(handle);
    if (error != ESP_OK) return fail_transfer(error);

    esp_app_desc_t description;
    error = esp_ota_get_partition_description(s_slot, &description);
    if (error != ESP_OK || description.magic_word != ESP_APP_DESC_MAGIC_WORD) {
        return fail_transfer(error == ESP_OK ? ESP_ERR_OTA_VALIDATE_FAILED : error);
    }

    s_installed_size = s_written_size;
    s_state = APP_FIRMWARE_READY;
    s_last_error = ESP_OK;
    save_metadata(true);
    ESP_LOGI(TAG, "firmware slot installed: %s (%s)", s_slot_name, description.version);
    return ESP_OK;
}

void app_firmware_cancel(esp_err_t reason)
{
    if (s_state != APP_FIRMWARE_RECEIVING) return;
    fail_transfer(reason == ESP_OK ? ESP_ERR_INVALID_STATE : reason);
}

esp_err_t app_firmware_clear(void)
{
    if (s_state == APP_FIRMWARE_RECEIVING) app_firmware_cancel(ESP_ERR_INVALID_STATE);
    s_installed_size = 0;
    s_expected_size = 0;
    s_written_size = 0;
    s_last_error = ESP_OK;
    s_state = APP_FIRMWARE_IDLE;
    copy_name(s_slot_name, sizeof(s_slot_name), "玩法槽");
    save_metadata(false);
    return ESP_OK;
}

esp_err_t app_firmware_prepare_boot(void)
{
    if (!app_firmware_slot_present()) return ESP_ERR_NOT_FOUND;
    esp_err_t error = esp_ota_set_boot_partition(s_slot);
    if (error != ESP_OK) {
        s_last_error = error;
        s_state = APP_FIRMWARE_ERROR;
    }
    return error;
}

app_firmware_state_t app_firmware_state(void) { return s_state; }
bool app_firmware_busy(void) { return s_state == APP_FIRMWARE_RECEIVING; }
bool app_firmware_slot_present(void) { return s_state == APP_FIRMWARE_READY && image_is_valid(); }
uint8_t app_firmware_progress(void)
{
    if (s_state != APP_FIRMWARE_RECEIVING || s_expected_size == 0) return 0;
    size_t progress = (s_written_size * 100) / s_expected_size;
    return progress > 100 ? 100 : (uint8_t)progress;
}
size_t app_firmware_image_size(void)
{
    return s_state == APP_FIRMWARE_RECEIVING ? s_expected_size : s_installed_size;
}
const char *app_firmware_slot_name(void) { return s_slot_name; }
esp_err_t app_firmware_last_error(void) { return s_last_error; }
