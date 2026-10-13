#include "app_firmware.h"

#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "nvs.h"
#include <stdio.h>
#include <string.h>

#define FIRMWARE_NAMESPACE "fw_slots"

typedef struct {
    const esp_partition_t *partition;
    bool present;
    size_t image_size;
    char name[APP_FIRMWARE_NAME_MAX + 1];
} firmware_slot_t;

static const char *TAG = "passport_fw";
static firmware_slot_t s_slots[APP_FIRMWARE_MAX_SLOTS];
static esp_ota_handle_t s_ota_handle;
static volatile app_firmware_state_t s_state;
static volatile size_t s_expected_size;
static volatile size_t s_written_size;
static volatile esp_err_t s_last_error;
static uint8_t s_active_slot = APP_FIRMWARE_AUTO_SLOT;
static uint8_t s_selected_slot = APP_FIRMWARE_AUTO_SLOT;

static void metadata_key(char *destination, size_t destination_size,
                         uint8_t slot, const char *suffix)
{
    snprintf(destination, destination_size, "s%u_%s", slot, suffix);
}

static void copy_name(char *destination, size_t destination_size, const char *source)
{
    if (!destination || destination_size == 0) return;
    snprintf(destination, destination_size, "%s", source && *source ? source : "未命名玩法");
}

static bool slot_index_valid(uint8_t slot)
{
    return slot < APP_FIRMWARE_MAX_SLOTS && s_slots[slot].partition != NULL;
}

static bool slot_image_valid(uint8_t slot)
{
    if (!slot_index_valid(slot) || !s_slots[slot].present ||
        s_slots[slot].image_size == 0 ||
        s_slots[slot].image_size > s_slots[slot].partition->size) {
        return false;
    }
    esp_app_desc_t description;
    return esp_ota_get_partition_description(s_slots[slot].partition, &description) == ESP_OK &&
           description.magic_word == ESP_APP_DESC_MAGIC_WORD;
}

static void save_slot_metadata(uint8_t slot)
{
    if (!slot_index_valid(slot)) return;
    nvs_handle_t handle;
    if (nvs_open(FIRMWARE_NAMESPACE, NVS_READWRITE, &handle) != ESP_OK) return;

    char key[16];
    metadata_key(key, sizeof(key), slot, "valid");
    nvs_set_u8(handle, key, s_slots[slot].present ? 1 : 0);
    metadata_key(key, sizeof(key), slot, "size");
    if (s_slots[slot].present) nvs_set_u32(handle, key, (uint32_t)s_slots[slot].image_size);
    else nvs_erase_key(handle, key);
    metadata_key(key, sizeof(key), slot, "name");
    if (s_slots[slot].present) nvs_set_str(handle, key, s_slots[slot].name);
    else nvs_erase_key(handle, key);
    nvs_commit(handle);
    nvs_close(handle);
}

static void save_selected_slot(void)
{
    nvs_handle_t handle;
    if (nvs_open(FIRMWARE_NAMESPACE, NVS_READWRITE, &handle) != ESP_OK) return;
    if (s_selected_slot == APP_FIRMWARE_AUTO_SLOT) nvs_erase_key(handle, "selected");
    else nvs_set_u8(handle, "selected", s_selected_slot);
    nvs_commit(handle);
    nvs_close(handle);
}

static void load_metadata(void)
{
    nvs_handle_t handle;
    if (nvs_open(FIRMWARE_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) return;

    uint8_t selected = APP_FIRMWARE_AUTO_SLOT;
    if (nvs_get_u8(handle, "selected", &selected) == ESP_OK && slot_index_valid(selected)) {
        s_selected_slot = selected;
    }

    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        if (!slot_index_valid(slot)) continue;
        char key[16];
        uint8_t valid = 0;
        uint32_t size = 0;
        metadata_key(key, sizeof(key), slot, "valid");
        if (nvs_get_u8(handle, key, &valid) != ESP_OK || valid != 1) continue;
        metadata_key(key, sizeof(key), slot, "size");
        if (nvs_get_u32(handle, key, &size) != ESP_OK || size == 0 ||
            size > s_slots[slot].partition->size) continue;
        metadata_key(key, sizeof(key), slot, "name");
        size_t name_size = sizeof(s_slots[slot].name);
        if (nvs_get_str(handle, key, s_slots[slot].name, &name_size) != ESP_OK) {
            copy_name(s_slots[slot].name, sizeof(s_slots[slot].name), "已安装玩法");
        }
        s_slots[slot].image_size = size;
        s_slots[slot].present = true;
    }
    nvs_close(handle);
}

static uint8_t choose_slot(size_t image_size)
{
    uint8_t best = APP_FIRMWARE_AUTO_SLOT;
    size_t best_capacity = SIZE_MAX;
    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        if (!slot_index_valid(slot) || s_slots[slot].present ||
            image_size > s_slots[slot].partition->size) continue;
        if (s_slots[slot].partition->size < best_capacity) {
            best = slot;
            best_capacity = s_slots[slot].partition->size;
        }
    }
    return best;
}

static esp_err_t fail_transfer(esp_err_t error)
{
    if (s_ota_handle != 0) {
        esp_ota_abort(s_ota_handle);
        s_ota_handle = 0;
    }
    s_state = APP_FIRMWARE_ERROR;
    s_last_error = error;
    s_active_slot = APP_FIRMWARE_AUTO_SLOT;
    ESP_LOGE(TAG, "firmware transfer failed: %s", esp_err_to_name(error));
    return error;
}

void app_firmware_init(void)
{
    memset(s_slots, 0, sizeof(s_slots));
    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        s_slots[slot].partition = esp_partition_find_first(
            ESP_PARTITION_TYPE_APP,
            (esp_partition_subtype_t)(ESP_PARTITION_SUBTYPE_APP_OTA_0 + slot), NULL);
        copy_name(s_slots[slot].name, sizeof(s_slots[slot].name), "空槽");
    }
    s_state = APP_FIRMWARE_IDLE;
    s_last_error = ESP_OK;
    s_active_slot = APP_FIRMWARE_AUTO_SLOT;
    s_selected_slot = APP_FIRMWARE_AUTO_SLOT;
    load_metadata();

    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        if (s_slots[slot].present && !slot_image_valid(slot)) {
            s_slots[slot].present = false;
            s_slots[slot].image_size = 0;
            copy_name(s_slots[slot].name, sizeof(s_slots[slot].name), "空槽");
            save_slot_metadata(slot);
        }
        if (s_selected_slot == APP_FIRMWARE_AUTO_SLOT && s_slots[slot].present) {
            s_selected_slot = slot;
        }
    }
    save_selected_slot();
}

esp_err_t app_firmware_begin(uint8_t slot, size_t image_size, const char *display_name)
{
    if (s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    if (slot == APP_FIRMWARE_AUTO_SLOT) slot = choose_slot(image_size);
    if (!slot_index_valid(slot)) return ESP_ERR_NOT_FOUND;
    if (image_size < 176 || image_size > s_slots[slot].partition->size) {
        return ESP_ERR_INVALID_SIZE;
    }
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (running && running->address == s_slots[slot].partition->address) {
        return ESP_ERR_INVALID_STATE;
    }

    s_active_slot = slot;
    s_expected_size = image_size;
    s_written_size = 0;
    s_last_error = ESP_OK;
    s_slots[slot].present = false;
    s_slots[slot].image_size = 0;
    copy_name(s_slots[slot].name, sizeof(s_slots[slot].name), display_name);
    save_slot_metadata(slot);

    esp_err_t error = esp_ota_begin(s_slots[slot].partition, image_size, &s_ota_handle);
    if (error != ESP_OK) return fail_transfer(error);
    s_state = APP_FIRMWARE_RECEIVING;
    ESP_LOGI(TAG, "receiving slot %u: %s (%u bytes)", slot,
             s_slots[slot].name, (unsigned)s_expected_size);
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
    if (s_state != APP_FIRMWARE_RECEIVING || s_ota_handle == 0 ||
        !slot_index_valid(s_active_slot)) return ESP_ERR_INVALID_STATE;
    if (s_written_size != s_expected_size) return fail_transfer(ESP_ERR_INVALID_SIZE);

    esp_ota_handle_t handle = s_ota_handle;
    s_ota_handle = 0;
    esp_err_t error = esp_ota_end(handle);
    if (error != ESP_OK) return fail_transfer(error);

    esp_app_desc_t description;
    error = esp_ota_get_partition_description(s_slots[s_active_slot].partition, &description);
    if (error != ESP_OK || description.magic_word != ESP_APP_DESC_MAGIC_WORD) {
        return fail_transfer(error == ESP_OK ? ESP_ERR_OTA_VALIDATE_FAILED : error);
    }

    uint8_t completed_slot = s_active_slot;
    s_slots[completed_slot].image_size = s_written_size;
    s_slots[completed_slot].present = true;
    s_selected_slot = completed_slot;
    s_active_slot = APP_FIRMWARE_AUTO_SLOT;
    s_state = APP_FIRMWARE_READY;
    s_last_error = ESP_OK;
    save_slot_metadata(completed_slot);
    save_selected_slot();
    ESP_LOGI(TAG, "slot %u installed: %s (%s)", completed_slot,
             s_slots[completed_slot].name, description.version);
    return ESP_OK;
}

void app_firmware_cancel(esp_err_t reason)
{
    if (s_state != APP_FIRMWARE_RECEIVING) return;
    fail_transfer(reason == ESP_OK ? ESP_ERR_INVALID_STATE : reason);
}

esp_err_t app_firmware_clear(uint8_t slot)
{
    if (!slot_index_valid(slot)) return ESP_ERR_NOT_FOUND;
    if (s_state == APP_FIRMWARE_RECEIVING) {
        if (slot != s_active_slot) return ESP_ERR_INVALID_STATE;
        app_firmware_cancel(ESP_ERR_INVALID_STATE);
    }
    s_slots[slot].present = false;
    s_slots[slot].image_size = 0;
    copy_name(s_slots[slot].name, sizeof(s_slots[slot].name), "空槽");
    save_slot_metadata(slot);
    if (s_selected_slot == slot) {
        s_selected_slot = APP_FIRMWARE_AUTO_SLOT;
        for (uint8_t candidate = 0; candidate < APP_FIRMWARE_MAX_SLOTS; ++candidate) {
            if (s_slots[candidate].present) {
                s_selected_slot = candidate;
                break;
            }
        }
        save_selected_slot();
    }
    s_state = APP_FIRMWARE_IDLE;
    s_last_error = ESP_OK;
    return ESP_OK;
}

esp_err_t app_firmware_select(uint8_t slot)
{
    if (!slot_index_valid(slot) || !slot_image_valid(slot)) return ESP_ERR_NOT_FOUND;
    if (s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    s_selected_slot = slot;
    save_selected_slot();
    return ESP_OK;
}

esp_err_t app_firmware_prepare_boot(uint8_t slot)
{
    if (slot == APP_FIRMWARE_AUTO_SLOT) slot = s_selected_slot;
    if (!slot_index_valid(slot) || !slot_image_valid(slot)) return ESP_ERR_NOT_FOUND;
    esp_err_t error = esp_ota_set_boot_partition(s_slots[slot].partition);
    if (error == ESP_OK) {
        s_selected_slot = slot;
        save_selected_slot();
    } else {
        s_last_error = error;
        s_state = APP_FIRMWARE_ERROR;
    }
    return error;
}

app_firmware_state_t app_firmware_state(void) { return s_state; }
bool app_firmware_busy(void) { return s_state == APP_FIRMWARE_RECEIVING; }
uint8_t app_firmware_active_slot(void) { return s_active_slot; }
uint8_t app_firmware_selected_slot(void) { return s_selected_slot; }
size_t app_firmware_slot_count(void) { return APP_FIRMWARE_MAX_SLOTS; }

uint8_t app_firmware_progress(void)
{
    if (s_state != APP_FIRMWARE_RECEIVING || s_expected_size == 0) return 0;
    size_t progress = (s_written_size * 100) / s_expected_size;
    return progress > 100 ? 100 : (uint8_t)progress;
}

bool app_firmware_slot_info(uint8_t slot, app_firmware_slot_info_t *info)
{
    if (!info || slot >= APP_FIRMWARE_MAX_SLOTS) return false;
    memset(info, 0, sizeof(*info));
    if (!slot_index_valid(slot)) return true;
    info->available = true;
    info->present = slot_image_valid(slot);
    info->capacity = s_slots[slot].partition->size;
    info->image_size = info->present ? s_slots[slot].image_size : 0;
    copy_name(info->name, sizeof(info->name), info->present ? s_slots[slot].name : "空槽");
    return true;
}

size_t app_firmware_total_free(void)
{
    size_t total = 0;
    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        if (slot_index_valid(slot) && !slot_image_valid(slot)) {
            total += s_slots[slot].partition->size;
        }
    }
    return total;
}

size_t app_firmware_largest_free_slot(void)
{
    size_t largest = 0;
    for (uint8_t slot = 0; slot < APP_FIRMWARE_MAX_SLOTS; ++slot) {
        if (slot_index_valid(slot) && !slot_image_valid(slot) &&
            s_slots[slot].partition->size > largest) {
            largest = s_slots[slot].partition->size;
        }
    }
    return largest;
}

esp_err_t app_firmware_last_error(void) { return s_last_error; }
